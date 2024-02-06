/*
 * Copyright (C) 2015 The Nevolution Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.oasisfeng.nevo.decorators.wechat;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.Icon;
import android.media.AudioAttributes;
import android.net.Uri;
import android.os.Bundle;
import android.os.Build.VERSION_CODES;
import android.provider.Settings;
import android.util.Log;

import java.io.File;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import androidx.annotation.ColorInt;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;
import androidx.core.app.NotificationCompat.MessagingStyle;
import androidx.core.graphics.drawable.IconCompat;

import static android.os.Build.VERSION.SDK_INT;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

import com.oasisfeng.nevo.decorators.wechat.ConversationManager.Conversation;
import com.oasisfeng.nevo.sdk.Decorating;
import com.oasisfeng.nevo.sdk.Decorator;
import com.oasisfeng.nevo.sdk.HookSupport;
import com.oasisfeng.nevo.sdk.NevoDecoratorService;
import com.oasisfeng.nevo.xposed.BuildConfig;
import com.oasisfeng.nevo.xposed.R;

import notxx.xposed.Hook;
import notxx.xposed.hook.FileOutputStream;

/**
 * Bring state-of-art notification experience to WeChat.
 *
 * Created by Oasis on 2015/6/1.
 *
 * @class WeChatImageDecorator.
 * 
 */
@Decorator(title = R.string.decorator_wechat_title, description = R.string.decorator_wechat_description, priority = -20)
public class WeChatDecorator extends NevoDecoratorService {
	
	public static final String WECHAT_PACKAGE = "com.tencent.mm";
	private static final long GROUP_CHAT_SORT_KEY_SHIFT = 24 * 60 * 60 * 1000L;			// Sort group chat like one day older message.
	private static final String CHANNEL_MESSAGE = "message_channel_new_id";				// Channel ID used by WeChat for all message notifications
	private static final String OLD_CHANNEL_MESSAGE = "message";						//   old name for migration
	private static final String CHANNEL_MISC = "reminder_channel_id";					// Channel ID used by WeChat for misc. notifications
	private static final String OLD_CHANNEL_MISC = "misc";								//   old name for migration
	private static final String CHANNEL_DND = "message_dnd_mode_channel_id";			// Channel ID used by WeChat for its own DND mode
	private static final String CHANNEL_GROUP_CONVERSATION = "group";					// WeChat has no separate group for group conversation
	private static final String RECALL_PATTERN = "(\"(?<recaller>[^\"]+)\" )?撤回了?一条消息";
	private static final Pattern pattern = Pattern.compile(RECALL_PATTERN);				// [2条]"🦉 " 撤回了一条消息 / [2条]撤回一条消息

	private static final @ColorInt int PRIMARY_COLOR = 0xFF33B332;
	private static final @ColorInt int LIGHT_COLOR = 0xFF00FF00;
	static final String ACTION_SETTINGS_CHANGED = "SETTINGS_CHANGED";
	static final String ACTION_DEBUG_NOTIFICATION = "DEBUG";
	private static final String KEY_SILENT_REVIVAL = "nevo.wechat.revival";
	private static final String EXTRA_RECALL = "nevo.wechat.recall";
	private static final String EXTRA_RECALLER = "nevo.wechat.recaller";
	public static final String EXTRA_PICTURE_PATH = "nevo.wechat.picturePath";
	private static final String EXTRA_PICTURE = "nevo.wechat.picture";
	private static final String STORAGE_PREFIX = "/storage/emulated/0/";

	static final String TAG = "WeChatDecorator";

	public static interface ModifyNotification { void modify(Notification n); }

	private static long now() { return System.currentTimeMillis(); }

	@Override public LocalDecorator createLocalDecorator(String packageName) {
		return new Local(this.prefKey);
	}

	public static class Local extends LocalDecorator implements HookSupport {
		public Local(String prefKey) {
			super(prefKey);
		}

		private final FileOutputStream hffos = new FileOutputStream();
		private final Hook forAuto = new notxx.xposed.hook.Auto();
		
		/**
		 * 
		 * Created by Oasis on 2018-11-30.
		 * Modify by Kr328 on 2019-1-5
		 * Modify by notXX on 2019-8-5
		 * 
		 * @param loadPackageParam
		 */
		@Override public void hook(XC_LoadPackage.LoadPackageParam loadPackageParam) {
			hffos.hook(loadPackageParam);
			// DiagnoseForLargeIcon.hook(loadPackageParam);
			forAuto.hook(loadPackageParam);
		}

		private MessagingBuilder mMessagingBuilder;
		private String channelGroupMessage, channelMessage, channelMisc;
		private boolean mWeChatTargetingO;
		private final ConversationManager mConversationManager = new ConversationManager();

		@Override public void onCreate(SharedPreferences pref) {
			super.onCreate(pref);

			mMessagingBuilder = new MessagingBuilder(getAppContext(), getPackageContext(), this::modifyNotification);		// Must be called after loadPreferences().
			channelGroupMessage = getString(R.string.channel_group_message);
			channelMessage = getString(R.string.channel_message);
			channelMisc = getString(R.string.channel_misc);
		}

		@Override public Decorating apply(NotificationManager nm, String tag, int id, Notification n) {
			mWeChatTargetingO = isWeChatTargeting26OrAbove();
			if (BuildConfig.DEBUG) Log.d(TAG, "apply tag " + tag + " id " + id);
			cache(id, n);

			// Log.d(TAG, "deleteIntent " + n.deleteIntent);
			final Bundle extras = n.extras,
				extensions = extras.getBundle("android.car.EXTENSIONS");
			if (extensions != null) {
				Bundle conversation = extensions.getBundle("car_conversation");
				PendingIntent onRead = (PendingIntent)conversation.get("on_read");
				// Log.d(TAG, "on_read " + onRead);
				n.deleteIntent = onRead;
			}
			// Log.d(TAG, "deleteIntent " + n.deleteIntent);
			CharSequence title = extras.getCharSequence(Notification.EXTRA_TITLE);
			if (title == null || title.length() == 0) {
				Log.e(TAG, "Title is missing: " + n);
				return Decorating.Unprocessed;
			}
			if (title != (title = EmojiTranslator.translate(title))) extras.putCharSequence(Notification.EXTRA_TITLE, title);
			n.color = PRIMARY_COLOR;        // Tint the small icon

			String channel_id = SDK_INT >= VERSION_CODES.O ? n.getChannelId() : null;
			if (CHANNEL_MISC.equals(channel_id)) return Decorating.Unprocessed;	// Misc. notifications on Android 8+.
			
			final CharSequence text = extras.getCharSequence(Notification.EXTRA_TEXT);
			String content = text != null ? text.toString() : null;
			// [2条]...
			if (content != null && content.startsWith("[")) {
				if (BuildConfig.DEBUG) Log.d(TAG, "content " + content);
				final int end = content.indexOf(']');
				if (content.charAt(end - 1) == '条') {
					n.number = Integer.parseInt(content.substring(1, end - 1));
					if (BuildConfig.DEBUG) Log.d(TAG, "n.number " + n.number);
					content = content.substring(end + 1);
				}
			}
			// 撤回...
			int type = Conversation.TYPE_UNKNOWN;
			String recaller = null;
			boolean is_recall = false;
			if (content != null && content.contains("撤回")) {
				if (BuildConfig.DEBUG) Log.d(TAG, "content " + content);
				if (CHANNEL_MISC.equals(channel_id)) {	// Misc. notifications on Android 8+.
					return Decorating.Unprocessed;
				} else if (n.tickerText == null) {		// Legacy misc. notifications.
					if (SDK_INT >= VERSION_CODES.O && channel_id == null) setChannelId(n, CHANNEL_MISC);
					Matcher matcher = pattern.matcher(content);
					if (BuildConfig.DEBUG) Log.d(TAG, "matcher " + matcher.matches());
					if (matcher.matches()) {
						// 撤回
						// Log.d(TAG, matcher.group(0) + ", " + matcher.group(1) + ", " + matcher.group(2) + ", " + matcher.group(3));
						is_recall = true;
						recaller = matcher.group("recaller");
						extras.putBoolean(EXTRA_RECALL, true);
						extras.putString(EXTRA_RECALLER, recaller);
						if (BuildConfig.DEBUG) Log.d(TAG, "recaller " + recaller);
					} else {
						Log.d(TAG, "Skip further process for non-conversation notification: " + title);    // E.g. web login confirmation notification.
						return Decorating.Unprocessed;
					}
				}
				if (is_recall) type = (recaller == null) ? Conversation.TYPE_DM_RECALL : Conversation.TYPE_GC_RECALL;
			}
			if (content == null || content.isEmpty()) return Decorating.Unprocessed;

			extras.putCharSequence(Notification.EXTRA_TEXT, content);

			int sep = content.indexOf(WeChatMessage.SENDER_MESSAGE_SEPARATOR);
			if (sep > 0) {
				String person = content.substring(0, sep);
				String msg = content.substring(sep + WeChatMessage.SENDER_MESSAGE_SEPARATOR.length());
				if (BuildConfig.DEBUG) Log.d(TAG, person + "|" + msg);
				hffos.export(msg, extras);
			}

			if (type == Conversation.TYPE_UNKNOWN) type = WeChatMessage.guessConversationType(content, n.tickerText.toString().trim(), title);
			final boolean is_group_chat = Conversation.isGroupChat(type);
			if (SDK_INT >= VERSION_CODES.O) {
				if (extras.containsKey(KEY_SILENT_REVIVAL)) {
					setGroup(n, "nevo.group.auto");	// Special group name to let Nevolution auto-group it as if not yet grouped. (To be standardized in SDK)
					setGroupAlertBehavior(n, Notification.GROUP_ALERT_SUMMARY);		// This trick makes notification silent
				}
				if (is_group_chat && ! CHANNEL_DND.equals(channel_id)) setChannelId(n, CHANNEL_GROUP_CONVERSATION);
				else if (channel_id == null) setChannelId(n, CHANNEL_MESSAGE);		// WeChat versions targeting O+ have its own channel for message
			}

			// WeChat previously uses dynamic counter starting from 4097 as notification ID, which is reused after cancelled by WeChat itself,
			//   causing conversation duplicate or overwritten notifications.
			final Conversation conversation = mConversationManager.getConversation(id);

			final Icon icon = n.getLargeIcon();
			conversation.icon = icon != null ? IconCompat.createFromIcon(getAppContext(), icon) : null;
			conversation.title = title;
			conversation.summary = content;
			conversation.ticker = n.tickerText;
			conversation.timestamp = n.when;
			if (is_recall)
				conversation.setType((recaller == null) ? Conversation.TYPE_DM_RECALL : Conversation.TYPE_GC_RECALL);
			else if (conversation.getType() == Conversation.TYPE_UNKNOWN)
				conversation.setType(WeChatMessage.guessConversationType(conversation));

			extras.putBoolean(Notification.EXTRA_SHOW_WHEN, true);
			// if (mPreferences.getBoolean(mPrefKeyWear, false)) n.flags &= ~ Notification.FLAG_LOCAL_ONLY; // TODO
			setSortKey(n, String.valueOf(Long.MAX_VALUE - n.when + (is_group_chat ? GROUP_CHAT_SORT_KEY_SHIFT : 0))); // Place group chat below other messages

			MessagingStyle messaging = mMessagingBuilder.buildFromExtender(conversation, n, title, getArchivedNotifications(id)); // build message from android auto
			if (messaging == null) return Decorating.Unprocessed;
			final List<MessagingStyle.Message> messages = messaging.getMessages();
			if (messages.isEmpty()) return Decorating.Unprocessed;

			if (is_group_chat) messaging.setGroupConversation(true).setConversationTitle(title);
			MessagingBuilder.flatIntoExtras(messaging, extras);

			if (extras.containsKey(EXTRA_PICTURE_PATH)) {
				String path = extras.getString(EXTRA_PICTURE_PATH);
				final BitmapFactory.Options options = new BitmapFactory.Options();
				options.inPreferredConfig = SDK_INT >= VERSION_CODES.O ? Bitmap.Config.HARDWARE : Bitmap.Config.ARGB_8888;
				extras.putString(Notification.EXTRA_TEMPLATE, TEMPLATE_BIG_PICTURE);
				extras.putParcelable(Notification.EXTRA_PICTURE, BitmapFactory.decodeFile(path, options));
				// extras.putCharSequence(Notification.EXTRA_SUMMARY_TEXT, text);
			} else {
				extras.putString(Notification.EXTRA_TEMPLATE, TEMPLATE_MESSAGING);
			}

			if (SDK_INT >= VERSION_CODES.N && extras.getCharSequenceArray(Notification.EXTRA_REMOTE_INPUT_HISTORY) != null)
				n.flags |= Notification.FLAG_ONLY_ALERT_ONCE;		// No more alert for direct-replied notification.

			// 维护NotificationChannel
			channel_id = n.getChannelId();
			if (channel_id != null) { // 确保NotificationChannel存在
				NotificationChannel channel = nm.getNotificationChannel(channel_id);
				if (BuildConfig.DEBUG) Log.d(TAG, channel_id + " " + channel);
				if (channel != null) return Decorating.Processed;
				switch (channel_id) {
					case CHANNEL_GROUP_CONVERSATION:
					channel = makeChannel(CHANNEL_GROUP_CONVERSATION, channelGroupMessage, false);
					break;
					case CHANNEL_MESSAGE:
					channel = migrate(nm, OLD_CHANNEL_MESSAGE,	CHANNEL_MESSAGE,	channelMessage, false);
					break;
					case CHANNEL_MISC:
					channel = migrate(nm, OLD_CHANNEL_MISC,		CHANNEL_MISC,		channelMisc, true);
					break;
				}
				if (BuildConfig.DEBUG) Log.d(TAG, channel_id + " " + channel);
				nm.createNotificationChannel(channel);
				channel = nm.getNotificationChannel(channel_id);
				if (BuildConfig.DEBUG) Log.d(TAG, channel_id + " " + channel);
			}

			return Decorating.Processed;
		}

		private void reviveNotificationAfterChannelDeletion(final int id) {
			Log.d(TAG, ("Revive silently: ") + id);
			modifyNotification(id, n -> {
				n.extras.putBoolean(KEY_SILENT_REVIVAL, true);
			});
		}

		@RequiresApi(VERSION_CODES.O) private NotificationChannel migrate(NotificationManager nm, final String old_id, final String new_id, final String new_name, final boolean silent) {
			final NotificationChannel channel_message = nm.getNotificationChannel(old_id);
			nm.deleteNotificationChannel(old_id);
			if (channel_message != null) return cloneChannel(channel_message, new_id, new_name);
			else return makeChannel(new_id, new_name, silent);
		}

		@RequiresApi(VERSION_CODES.O) private NotificationChannel makeChannel(final String channel_id, final String name, final boolean silent) {
			final NotificationChannel channel = new NotificationChannel(channel_id, name, NotificationManager.IMPORTANCE_HIGH/* Allow heads-up (by default) */);
			if (silent) channel.setSound(null, null);
			else channel.setSound(getDefaultSound(), new AudioAttributes.Builder().setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
					.setUsage(AudioAttributes.USAGE_NOTIFICATION_COMMUNICATION_INSTANT).build());
			channel.enableLights(true);
			channel.setLightColor(LIGHT_COLOR);
			return channel;
		}

		@RequiresApi(VERSION_CODES.O) private NotificationChannel cloneChannel(final NotificationChannel channel, final String id, final String new_name) {
			final NotificationChannel clone = new NotificationChannel(id, new_name, channel.getImportance());
			clone.setGroup(channel.getGroup());
			clone.setDescription(channel.getDescription());
			clone.setLockscreenVisibility(channel.getLockscreenVisibility());
			clone.setSound(Optional.ofNullable(channel.getSound()).orElse(getDefaultSound()), channel.getAudioAttributes());
			clone.setBypassDnd(channel.canBypassDnd());
			clone.setLightColor(channel.getLightColor());
			clone.setShowBadge(channel.canShowBadge());
			clone.setVibrationPattern(channel.getVibrationPattern());
			return clone;
		}

		@Nullable private Uri getDefaultSound() {	// Before targeting VERSION_CODES.O, WeChat actually plays sound by itself (not via Notification).
			return mWeChatTargetingO ? Settings.System.DEFAULT_NOTIFICATION_URI : null;
		}

		private boolean isWeChatTargeting26OrAbove() {
			try {
				return getPackageManager().getApplicationInfo(WECHAT_PACKAGE, PackageManager.GET_UNINSTALLED_PACKAGES).targetSdkVersion >= VERSION_CODES.O;
			} catch (final PackageManager.NameNotFoundException e) {
				return false;
			}
		}

		private void modifyNotification(final int id, final ModifyNotification... modifies) {
			if (hasArchivedNotifications(id)) {
				Notification n = getArchivedNotification(id);
				for (ModifyNotification modify : modifies) modify.modify(n);
				Log.d(TAG, "recast " + id + " " + n.extras.getCharSequence(Notification.EXTRA_TITLE));
				recastNotification(id, n);
			} else {
				Log.d(TAG, "can not recast " + id + ", so cancel it");
				cancelNotification(id);
			}
		}
	}
}
