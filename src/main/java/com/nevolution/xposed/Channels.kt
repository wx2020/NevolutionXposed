package com.nevolution.xposed

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.provider.Settings
import android.os.Build.VERSION.SDK_INT
import android.os.Build.VERSION_CODES
import androidx.annotation.RequiresApi

/**
 * 通知渠道管理器。
 * 为群聊和私聊分别创建独立渠道，便于用户在系统设置中分别控制通知行为。
 */
class Channels {
    /**
     * 初始化通知渠道：基于微信原有 "新消息" 渠道克隆出 "群消息通知" 和 "私聊消息通知"。
     */
    @RequiresApi(api = VERSION_CODES.O)
    fun init(nm: NotificationManager) {
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

    /**
     * 根据通知是否为群聊，动态修改 mChannelId 指向对应渠道。
     */
    fun process(n: Notification) {
        n.setChannelId(if (n.isGroupConversation) {
            CHANNEL_GROUP_MESSAGE
        } else {
            CHANNEL_PRIVATE_MESSAGE
        })
    }
}

/** 通过反射设置 Notification 的 mChannelId 字段（Android 8+ 私有字段）。 */
fun Notification.setChannelId(value: String) { this.set("mChannelId", value) }

/**
 * 克隆 NotificationChannel，保留原渠道的几乎所有属性（重要性、分组、描述、锁屏可见性、声音、免打扰、灯光、角标、震动）。
 */
@RequiresApi(api = VERSION_CODES.O)
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
