package com.nevolution.xposed

import android.app.Notification
import android.app.Notification.EXTRA_IS_GROUP_CONVERSATION
import android.app.Notification.EXTRA_REMOTE_INPUT_HISTORY
import android.app.Notification.EXTRA_TEXT
import android.app.Notification.EXTRA_TITLE
import android.os.Bundle

/** 微信主进程包名 */
const val PACKAGE_WECHAT = "com.tencent.mm"
/** 微信主进程名（与包名一致） */
const val PROCESS_WECHAT = "com.tencent.mm"

/** 通知渠道 ID — 微信默认新消息渠道 */
const val CHANNEL_NEW_MESSAGE = "message_channel_new_id"
/** 通知渠道 ID — 群消息 */
const val CHANNEL_GROUP_MESSAGE = "message_channel_group_message"
/** 通知渠道 ID — 私聊消息 */
const val CHANNEL_PRIVATE_MESSAGE = "message_channel_private_message"
const val KEY_TEXT = "text"
const val KEY_TIMESTAMP = "time"
const val KEY_SENDER = "sender"
const val KEY_SENDER_PERSON = "sender_person"
const val KEY_DATA_MIME_TYPE = "type"
const val KEY_DATA_URI = "uri"
const val KEY_EXTRAS_BUNDLE = "extras"

/** 通过 Notification.extras 读写 "android.text" */
var Notification.text
    get() = this.extras.getCharSequence(EXTRA_TEXT)
    set(value) = this.extras.putCharSequence(EXTRA_TEXT, value)
/** 通过 Notification.extras 读写 "android.title" */
var Notification.title
    get() = this.extras.getCharSequence(EXTRA_TITLE)
    set(value) = this.extras.putCharSequence(EXTRA_TITLE, value)
/** 通过 Notification.extras 读写远程输入历史 */
var Notification.remoteInputHistory
    get() = this.extras.getCharSequenceArray(EXTRA_REMOTE_INPUT_HISTORY)
    set(value) = this.extras.putCharSequenceArray(EXTRA_REMOTE_INPUT_HISTORY, value)
/** 通过 Notification.extras 读写群聊标记 */
var Notification.isGroupConversation
    get() = this.extras.isGroupConversation
    set(value) { this.extras.isGroupConversation = value }
/**
 * 消息类型 — 使用 Xposed 附加实例字段存储，
 * 避免污染 Notification 原始字段。
 */
var Notification.type: MessageType?
    get() = this.getAdditional<MessageType>("type")
    set(value) { this.setAdditional<MessageType>("type", value) }
/** 发送者名称 — 使用 Xposed 附加实例字段存储 */
var Notification.person: CharSequence?
    get() = this.getAdditional<CharSequence>("person")
    set(value) { this.setAdditional<CharSequence>("person", value) }
/** 消息正文 — 使用 Xposed 附加实例字段存储 */
var Notification.content: CharSequence?
    get() = this.getAdditional<CharSequence>("content")
    set(value) { this.setAdditional<CharSequence>("content", value) }

/** Bundle 扩展属性：方便读写群聊标记 */
var Bundle.isGroupConversation: Boolean
    get() = this.getBoolean(EXTRA_IS_GROUP_CONVERSATION, false)
    set(value) = this.putBoolean(EXTRA_IS_GROUP_CONVERSATION, value)
