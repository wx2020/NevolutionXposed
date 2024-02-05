package notxx.wechat

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.provider.Settings
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi

import notxx.xposed.set

private const val TAG = "WeChat.Channels"

class Channels {
	fun init(nm: NotificationManager) {
		if (Build.VERSION.SDK_INT >= 26) {
			val channel = nm.getNotificationChannel(CHANNEL_NEW_MESSAGE)
			val channelGM = nm.getNotificationChannel(CHANNEL_GROUP_MESSAGE)
			if (channelGM == null) {
				nm.createNotificationChannel(channel.clone(CHANNEL_GROUP_MESSAGE, "群消息通知"))
			}
			val channelPM = nm.getNotificationChannel(CHANNEL_PRIVATE_MESSAGE)
			if (channelPM == null) {
				nm.createNotificationChannel(channel.clone(CHANNEL_PRIVATE_MESSAGE, "私聊消息通知"))
			}
		}
	}

	fun process(n: Notification) {
		// Log.d(TAG, n.channelId)
		n.setChannelId(if (n.isGroupConversation) {
			CHANNEL_GROUP_MESSAGE
		} else {
			CHANNEL_PRIVATE_MESSAGE
		})
		// Log.d(TAG, n.channelId)
	}
}

fun Notification.setChannelId(value: String) { this.set("mChannelId", value) }

@RequiresApi(api = 26)
fun NotificationChannel.clone(id: String, name: String): NotificationChannel {
	val clone = NotificationChannel(id, name, this.importance)
	clone.group = this.group
	clone.description = this.description
	clone.lockscreenVisibility = this.lockscreenVisibility
	clone.setSound(this.sound ?: Settings.System.DEFAULT_NOTIFICATION_URI, this.audioAttributes)
	clone.setBypassDnd(this.canBypassDnd())
	clone.lightColor = this.lightColor
	clone.setShowBadge(this.canShowBadge())
	clone.vibrationPattern = this.vibrationPattern
	return clone
}