package com.oasisfeng.nevo.decorators.wechat;

import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.Notification.Action;
import android.app.Notification.CarExtender;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.RemoteInput;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Build.VERSION_CODES;
import android.os.Bundle;
import android.provider.ContactsContract.Contacts;
import android.provider.ContactsContract.Profile;
import android.service.notification.StatusBarNotification;
import android.text.TextUtils;
import android.util.ArrayMap;
import android.util.Log;
import android.util.LongSparseArray;

import com.oasisfeng.nevo.decorators.wechat.ConversationManager.Conversation;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;
import androidx.core.app.NotificationCompat.MessagingStyle;
import androidx.core.app.NotificationCompat.MessagingStyle.Message;
import androidx.core.app.Person;
import androidx.core.graphics.drawable.IconCompat;

import static android.app.Notification.EXTRA_REMOTE_INPUT_HISTORY;
import static android.app.Notification.EXTRA_TEXT;
import static android.app.PendingIntent.FLAG_UPDATE_CURRENT;
import static android.os.Build.VERSION.SDK_INT;
import static androidx.core.app.NotificationCompat.EXTRA_CONVERSATION_TITLE;
import static androidx.core.app.NotificationCompat.EXTRA_IS_GROUP_CONVERSATION;
import static androidx.core.app.NotificationCompat.EXTRA_MESSAGES;
import static androidx.core.app.NotificationCompat.EXTRA_SELF_DISPLAY_NAME;

import com.oasisfeng.nevo.xposed.BuildConfig;
import com.oasisfeng.nevo.xposed.R;

import static com.oasisfeng.nevo.decorators.wechat.WeChatMessage.SENDER_MESSAGE_SEPARATOR;
import static com.oasisfeng.nevo.sdk.NevoDecoratorService.TEMPLATE_BIG_PICTURE;
import static com.oasisfeng.nevo.sdk.NevoDecoratorService.TEMPLATE_MESSAGING;
import static com.oasisfeng.nevo.sdk.NevoDecoratorService.LocalDecorator.setActions;


/**
 * Build the modernized {@link MessagingStyle} for WeChat conversation.
 *
 * Refactored by Oasis on 2018-8-9.
 */
class MessagingBuilder {

	private static final int MAX_NUM_HISTORICAL_LINES = 10;

	private static final String ACTION_REPLY = "REPLY";
	private static final String ACTION_MENTION = "MENTION";
	private static final String ACTION_ZOOM = "ZOOM";
	private static final String SCHEME_ID = "id";
	private static final String EXTRA_REPLY_ACTION = "pending_intent";
	private static final String EXTRA_RESULT_KEY = "result_key";
	private static final String EXTRA_REPLY_PREFIX = "reply_prefix";

	private static final String KEY_TEXT = "text";
	private static final String KEY_TIMESTAMP = "time";
	private static final String KEY_SENDER = "sender";
	@RequiresApi(VERSION_CODES.P) private static final String KEY_SENDER_PERSON = "sender_person";
	private static final String KEY_DATA_MIME_TYPE = "type";
	private static final String KEY_DATA_URI= "uri";
	private static final String KEY_EXTRAS_BUNDLE = "extras";

	private static final String KEY_USERNAME = "key_username";
	private static final String MENTION_SEPARATOR = " ";			// Separator between @nick and text. It's not a regular white space, but U+2005.

	public static int guessType(String key) {
		if (key.endsWith("@chatroom") || key.endsWith("@im.chatroom"/* WeWork */))
			return Conversation.TYPE_GROUP_CHAT;
		if (key.startsWith("gh_"))
			return Conversation.TYPE_BOT_MESSAGE;
		return Conversation.TYPE_DIRECT_MESSAGE;
	}

	/**
	 * 从已存档消息重建会话
	 * 
	 * @param conversation 会话的内存存根
	 * @param n 当前消息
	 * @param title 标题
	 * @param archieve 消息存档
	 * @return 会话的图形化
	 */
	@Nullable MessagingStyle buildFromArchive(final Conversation conversation, final Notification n, final CharSequence title, final List<Notification> archive) {
		// Chat history in big content view
		if (archive.isEmpty()) {
			Log.d(TAG, "No history");
			return null;
		}

		final LongSparseArray<CharSequence> tickerArray = new LongSparseArray<>(MAX_NUM_HISTORICAL_LINES);
		final LongSparseArray<CharSequence> textArray = new LongSparseArray<>(MAX_NUM_HISTORICAL_LINES);
		CharSequence text;
		int count = 0, num_lines_with_colon = 0;
		final String redundant_prefix = title.toString() + SENDER_MESSAGE_SEPARATOR;
		for (final Notification notification : archive) {
			tickerArray.put(notification.when, notification.tickerText);
			final Bundle its_extras = notification.extras;
			final CharSequence its_title = EmojiTranslator.translate(its_extras.getCharSequence(Notification.EXTRA_TITLE));
			if (! title.equals(its_title)) {
				Log.d(TAG, "Skip other conversation with the same key in archive: " + its_title);	// ID reset by WeChat due to notification removal in previous evolving
				continue;
			}
			final CharSequence its_text = its_extras.getCharSequence(EXTRA_TEXT);
			if (its_text == null) {
				Log.w(TAG, "No text in archived notification.");
				continue;
			}
			final int result = trimAndExtractLeadingCounter(its_text);
			if (result >= 0) {
				count = result & 0xFFFF;
				CharSequence trimmed_text = its_text.subSequence(result >> 16, its_text.length());
				if (trimmed_text.toString().startsWith(redundant_prefix))	// Remove redundant prefix
					trimmed_text = trimmed_text.subSequence(redundant_prefix.length(), trimmed_text.length());
				else if (trimmed_text.toString().indexOf(SENDER_MESSAGE_SEPARATOR) > 0) num_lines_with_colon ++;
				textArray.put(notification.when, trimmed_text);
			} else {
				count = 1;
				textArray.put(notification.when, text = its_text);
				if (text.toString().indexOf(SENDER_MESSAGE_SEPARATOR) > 0) num_lines_with_colon ++;
			}
		}
		n.number = count;
		if (textArray.size() == 0) {
			Log.w(TAG, "No lines extracted, expected " + count);
			return null;
		}

		final MessagingStyle messaging = new MessagingStyle(mUserSelf);
		final boolean sender_inline = num_lines_with_colon == textArray.size();
		for (int i = 0, size = textArray.size(); i < size; i++)	{		// All lines have colon in text
			messaging.addMessage(buildMessage(conversation,
					textArray.keyAt(i),
					tickerArray.valueAt(i),
					textArray.valueAt(i),
					sender_inline ? null : title.toString()));
		}
		return messaging;
	}

	/**
	 * 从车载扩展信息重建会话
	 */
	@Nullable MessagingStyle buildFromExtender(final Conversation conversation, final Notification n, final CharSequence title, final List<Notification> archive) {
		final int id = conversation.id;
		final MessagingStyle messaging = buildFromArchive(conversation, n, title, archive);
		
		final Notification.CarExtender extender = new Notification.CarExtender(n);
		final CarExtender.UnreadConversation convs = extender.getUnreadConversation();
		if (convs == null) return messaging;
		final long latest_timestamp = convs.getLatestTimestamp();
		if (latest_timestamp > 0) n.when = conversation.timestamp = latest_timestamp;

		final PendingIntent on_reply = convs.getReplyPendingIntent();
		if (conversation.key == null) {
			try {
				if (on_reply != null) on_reply.send(mContext, 0, null, (p, intent, r, d, b) -> {
					final String key = conversation.key = intent.getStringExtra(KEY_USERNAME);	// setType() below will trigger rebuilding of conversation sender.
					conversation.setType(guessType(key));
				}, null);
			} catch (final PendingIntent.CanceledException e) {
				Log.e(TAG, "Error parsing reply intent.", e);
			}
		}

		final PendingIntent on_read = convs.getReadPendingIntent();
		if (on_read != null) mMarkReadPendingIntents.put(id, on_read);	// Mapped by evolved key,

		final List<Action> actions = new ArrayList<>();
		// 回复
		final RemoteInput remote_input;
		if (SDK_INT >= VERSION_CODES.N && on_reply != null && (remote_input = convs.getRemoteInput()) != null) {
			final CharSequence[] input_history = n.extras.getCharSequenceArray(EXTRA_REMOTE_INPUT_HISTORY);
			final PendingIntent proxy = proxyDirectReply(id, n, on_reply, remote_input, input_history, null);
			final RemoteInput.Builder reply_remote_input = new RemoteInput.Builder(remote_input.getResultKey()).addExtras(remote_input.getExtras())
					.setAllowFreeFormInput(true);
			final String participant = convs.getParticipant();	// No need to getParticipants() due to actually only one participant at most, see CarExtender.Builder().
			if (participant != null) reply_remote_input.setLabel(participant);

			final Action.Builder reply_action = new Action.Builder(null, actionReply, proxy)
					.addRemoteInput(reply_remote_input.build()).setAllowGeneratedReplies(true);
			if (SDK_INT >= VERSION_CODES.P) reply_action.setSemanticAction(Action.SEMANTIC_ACTION_REPLY);
			actions.add(reply_action.build());
		}
		// 放大
		if (n.extras.containsKey(WeChatDecorator.EXTRA_PICTURE_PATH)) {
			final Intent intent = new Intent(ACTION_ZOOM).setData(Uri.fromParts(SCHEME_ID, Integer.toString(id), null));
			final Action.Builder zoom_action = new Action.Builder(null, actionZoom, PendingIntent.getBroadcast(mContext, 0, intent.setPackage(mContext.getPackageName()), FLAG_UPDATE_CURRENT));
			actions.add(zoom_action.build());
		}
		setActions(n, actions.toArray(new Action[actions.size()]));
		return messaging;
	}

	private static Message buildMessage(
		final Conversation conversation,
		final long when,
		final @Nullable CharSequence ticker,
		final CharSequence text,
		@Nullable String sender) {
		CharSequence actual_text = text;
		if (sender == null) {
			sender = extractSenderFromText(text);
			if (sender != null) {
				actual_text = text.subSequence(sender.length() + SENDER_MESSAGE_SEPARATOR.length(), text.length());
				if (TextUtils.equals(conversation.title, sender)) sender = null;		// In this case, the actual sender is user itself.
			}
		}
		actual_text = EmojiTranslator.translate(actual_text);

		final Person person;
		if (sender != null && sender.isEmpty()) person = null;		// Empty string as a special mark for "self"
		else if (conversation.isGroupChat()) {
			final String ticker_sender = ticker != null ? extractSenderFromText(ticker) : null;	// Group nick is used in ticker and content text, while original nick in sender.
			person = sender == null ? null : conversation.getGroupParticipant(sender, ticker_sender != null ? ticker_sender : sender);
		} else person = conversation.sender().build();
		return new Message(actual_text, when, person);
	}

	private static @Nullable String extractSenderFromText(final CharSequence text) {
		final int pos_colon = TextUtils.indexOf(text, SENDER_MESSAGE_SEPARATOR);
		return pos_colon > 0 ? text.toString().substring(0, pos_colon) : null;
	}

	/** @return the extracted count in 0xFF range and start position in 0xFF00 range */
	private static int trimAndExtractLeadingCounter(final CharSequence text) {
		// Parse and remove the leading "[n]" or [n条/則/…]
		if (text == null || text.length() < 4 || text.charAt(0) != '[') return - 1;
		int text_start = 2, count_end;
		while (text.charAt(text_start++) != ']') if (text_start >= text.length()) return - 1;

		try {
			final String num = text.subSequence(1, text_start - 1).toString();	// may contain the suffix "条/則"
			for (count_end = 0; count_end < num.length(); count_end++) if (! Character.isDigit(num.charAt(count_end))) break;
			if (count_end == 0) return - 1;			// Not the expected "unread count"
			final int count = Integer.parseInt(num.substring(0, count_end));
			if (count < 2) return - 1;

			return count < 0xFFFF ? (count & 0xFFFF) | ((text_start << 16) & 0xFFFF0000) : 0xFFFF | ((text_start << 16) & 0xFF00);
		} catch (final NumberFormatException ignored) {
			Log.d(TAG, "Failed to parse: " + text);
			return - 1;
		}
	}

	/** Intercept the PendingIntent in RemoteInput to update the notification with replied message upon success. */
	private PendingIntent proxyDirectReply(final int id, final Notification notification, final PendingIntent on_reply, final RemoteInput remote_input,
										   final @Nullable CharSequence[] input_history, final @Nullable String mention_prefix) {
		final Intent proxy = new Intent(mention_prefix != null ? ACTION_MENTION : ACTION_REPLY)		// Separate action to avoid PendingIntent overwrite.
				.putExtra(EXTRA_REPLY_ACTION, on_reply).putExtra(EXTRA_RESULT_KEY, remote_input.getResultKey())
				.setData(Uri.fromParts(SCHEME_ID, Integer.toString(id), null));
		if (mention_prefix != null) proxy.putExtra(EXTRA_REPLY_PREFIX, mention_prefix);
		if (SDK_INT >= VERSION_CODES.N && input_history != null)
			proxy.putCharSequenceArrayListExtra(EXTRA_REMOTE_INPUT_HISTORY, new ArrayList<>(Arrays.asList(input_history)));
		return PendingIntent.getBroadcast(mContext, 0, proxy.setPackage(mContext.getPackageName()), FLAG_UPDATE_CURRENT);
	}

	private final BroadcastReceiver mReplyReceiver = new BroadcastReceiver() { @Override public void onReceive(final Context context, final Intent proxy_intent) {
		final PendingIntent reply_action = proxy_intent.getParcelableExtra(EXTRA_REPLY_ACTION);
		final String result_key = proxy_intent.getStringExtra(EXTRA_RESULT_KEY), reply_prefix = proxy_intent.getStringExtra(EXTRA_REPLY_PREFIX);
		final Uri data = proxy_intent.getData(); final Bundle results = RemoteInput.getResultsFromIntent(proxy_intent);
		final CharSequence input = results != null ? results.getCharSequence(result_key) : null;
		if (data == null || reply_action == null || result_key == null || input == null) return;	// Should never happen
		final CharSequence text;
		if (reply_prefix != null) {
			text = reply_prefix + input;
			results.putCharSequence(result_key, text);
			RemoteInput.addResultsToIntent(new RemoteInput[]{ new RemoteInput.Builder(result_key).build() }, proxy_intent, results);
		} else text = input;
		final ArrayList<CharSequence> input_history = SDK_INT >= VERSION_CODES.N ? proxy_intent.getCharSequenceArrayListExtra(EXTRA_REMOTE_INPUT_HISTORY) : null;
		final String part = data.getSchemeSpecificPart();
		try {
			final Intent input_data = addTargetPackageAndWakeUp(reply_action);
			input_data.setClipData(proxy_intent.getClipData());

			reply_action.send(mContext, 0, input_data, (pendingIntent, intent, _result_code, _result_data, _result_extras) -> {
				if (BuildConfig.DEBUG) Log.d(TAG, "Reply sent: " + intent.toUri(0));
				if (SDK_INT >= VERSION_CODES.N) {
					final CharSequence[] inputs;
					if (input_history != null) {
						input_history.add(0, text);
						inputs = input_history.toArray(new CharSequence[0]);
					} else inputs = new CharSequence[] { text };
					final int id = Integer.parseInt(part);
					mController.recastNotification(id, n -> {
						final Bundle extras = n.extras;
						extras.putCharSequenceArray(EXTRA_REMOTE_INPUT_HISTORY, inputs);
					});
					markRead(id);
				}
			}, null);
		} catch (final PendingIntent.CanceledException e) {
			Log.w(TAG, "Reply action is already cancelled: " + part);
			abortBroadcast();
		}
	} };

	private final BroadcastReceiver mZoomReceiver = new BroadcastReceiver() { @Override public void onReceive(final Context context, final Intent proxy_intent) {
		final String action = proxy_intent.getAction();
		final Uri data = proxy_intent.getData(); final Bundle results = RemoteInput.getResultsFromIntent(proxy_intent);
		final String key = data.getSchemeSpecificPart();
		// final Bundle addition = new Bundle();
		mController.recastNotification(Integer.parseInt(key), n -> {
			final Bundle extras = n.extras;
			if (BuildConfig.DEBUG) Log.d(TAG, "bitmap " + extras.getParcelable(Notification.EXTRA_PICTURE));
			if (TEMPLATE_MESSAGING.equals(extras.getString(Notification.EXTRA_TEMPLATE))) {
				final String path = extras.getString(WeChatDecorator.EXTRA_PICTURE_PATH);
				final BitmapFactory.Options options = new BitmapFactory.Options();
				options.inPreferredConfig = SDK_INT >= VERSION_CODES.O ? Bitmap.Config.HARDWARE : Bitmap.Config.ARGB_8888;
				// extras.putString(Notification.EXTRA_TEMPLATE, TEMPLATE_BIG_PICTURE);
				extras.putParcelable(Notification.EXTRA_PICTURE, BitmapFactory.decodeFile(path, options));
				// extras.putCharSequence(Notification.EXTRA_SUMMARY_TEXT, text);
				extras.putString(Notification.EXTRA_TEMPLATE, TEMPLATE_BIG_PICTURE);
			} else {
				extras.putString(Notification.EXTRA_TEMPLATE, TEMPLATE_MESSAGING); // TODO
			}
			if (BuildConfig.DEBUG) Log.d(TAG, "bitmap " + extras.getParcelable(Notification.EXTRA_PICTURE));
		});
	} };

	/** @param id the notification id */
	void markRead(final int id) {
		final PendingIntent action = mMarkReadPendingIntents.remove(id);
		if (action == null) return;
		try {
			action.send(mContext, 0, addTargetPackageAndWakeUp(action));
		} catch (final PendingIntent.CanceledException e) {
			Log.w(TAG, "Mark-read action is already cancelled: " + id);
		}
	}

	/** Ensure the PendingIntent works even if WeChat is stopped or background-restricted. */
	@NonNull private static Intent addTargetPackageAndWakeUp(final PendingIntent action) {
		return new Intent().addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES).setPackage(action.getCreatorPackage());
	}

	static void flatIntoExtras(final MessagingStyle messaging, final Bundle extras) {
		final Person user = messaging.getUser();
		if (user != null) {
			extras.putCharSequence(EXTRA_SELF_DISPLAY_NAME, user.getName());
			if (SDK_INT >= VERSION_CODES.P) extras.putParcelable(Notification.EXTRA_MESSAGING_PERSON, toAndroidPerson(user));	// Not included in NotificationCompat
		}
		if (messaging.getConversationTitle() != null) extras.putCharSequence(EXTRA_CONVERSATION_TITLE, messaging.getConversationTitle());
		final List<Message> messages = messaging.getMessages();
		// Log.d(TAG, "messages " + messages.size());
		if (! messages.isEmpty()) extras.putParcelableArray(EXTRA_MESSAGES, getBundleArrayForMessages(messages));
		//if (! mHistoricMessages.isEmpty()) extras.putParcelableArray(Notification.EXTRA_HISTORIC_MESSAGES, MessagingBuilder.getBundleArrayForMessages(mHistoricMessages));
		extras.putBoolean(EXTRA_IS_GROUP_CONVERSATION, messaging.isGroupConversation());
	}

	private static Bundle[] getBundleArrayForMessages(final List<Message> messages) {
		final int N = messages.size();
		final Bundle[] bundles = new Bundle[N];
		for (int i = 0; i < N; i ++) bundles[i] = toBundle(messages.get(i));
		return bundles;
	}

	private static Bundle toBundle(final Message message) {
		final Bundle bundle = new Bundle();
		bundle.putCharSequence(KEY_TEXT, message.getText());
		// Log.d(TAG, "message.text " + message.getText());
		bundle.putLong(KEY_TIMESTAMP, message.getTimestamp());		// Must be included even for 0
		final Person sender = message.getPerson();
		if (sender != null) {
			bundle.putCharSequence(KEY_SENDER, sender.getName());	// Legacy listeners need this
			if (SDK_INT >= VERSION_CODES.P) bundle.putParcelable(KEY_SENDER_PERSON, toAndroidPerson(sender));
		}
		if (message.getDataMimeType() != null) bundle.putString(KEY_DATA_MIME_TYPE, message.getDataMimeType());
		if (message.getDataUri() != null) bundle.putParcelable(KEY_DATA_URI, message.getDataUri());
		if (SDK_INT >= VERSION_CODES.O && ! message.getExtras().isEmpty()) bundle.putBundle(KEY_EXTRAS_BUNDLE, message.getExtras());
		//if (message.isRemoteInputHistory()) bundle.putBoolean(KEY_REMOTE_INPUT_HISTORY, message.isRemoteInputHistory());
		return bundle;
	}

	@RequiresApi(VERSION_CODES.P) @SuppressLint("RestrictedApi") private static android.app.Person toAndroidPerson(final Person user) {
		return user.toAndroidPerson();
	}

	interface Controller { void recastNotification(int id, WeChatDecorator.ModifyNotification... modifies); }

	MessagingBuilder(final Context context, final Context packageContext, /* final SharedPreferences preferences,  */final Controller controller) {
		mContext = context;
		actionReply = packageContext.getString(R.string.action_reply);
		actionZoom = packageContext.getString(R.string.action_zoom);
		// mPreferences = preferences;
		mController = controller;
		mUserSelf = buildPersonFromProfile(packageContext.getString(R.string.self_display_name));

		{
			final IntentFilter filter = new IntentFilter(ACTION_REPLY); filter.addAction(ACTION_MENTION); filter.addDataScheme(SCHEME_ID);
			context.registerReceiver(mReplyReceiver, filter);
		}
		{
			final IntentFilter filter = new IntentFilter(ACTION_ZOOM); filter.addDataScheme(SCHEME_ID);
			context.registerReceiver(mZoomReceiver, filter);
		}
	}

	private static Person buildPersonFromProfile(final String selfDisplayName) {
		return new Person.Builder().setName(selfDisplayName).build();
	}

	void close() {
		try { mContext.unregisterReceiver(mReplyReceiver); } catch (final RuntimeException ignored) {}
		try { mContext.unregisterReceiver(mZoomReceiver); } catch (final RuntimeException ignored) {}
	}

	private final Context mContext;
	private final String actionReply, actionZoom;
	// private final SharedPreferences mPreferences;
	private final Controller mController;
	private final Person mUserSelf;
	private final Map<Integer/* notification id */, PendingIntent> mMarkReadPendingIntents = new ArrayMap<>();
	private static final String TAG = WeChatDecorator.TAG;
}
