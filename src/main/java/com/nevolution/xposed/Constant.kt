package com.nevolution.xposed

import android.app.Notification
import android.app.Notification.EXTRA_IS_GROUP_CONVERSATION
import android.app.Notification.EXTRA_REMOTE_INPUT_HISTORY
import android.app.Notification.EXTRA_TEXT
import android.app.Notification.EXTRA_TITLE
import android.os.Bundle

const val PACKAGE_WECHAT = "com.tencent.mm"
const val PROCESS_WECHAT = "com.tencent.mm"

const val CHANNEL_NEW_MESSAGE = "message_channel_new_id"
const val CHANNEL_GROUP_MESSAGE = "message_channel_group_message"
const val CHANNEL_PRIVATE_MESSAGE = "message_channel_private_message"
const val KEY_TEXT = "text"
const val KEY_TIMESTAMP = "time"
const val KEY_SENDER = "sender"
const val KEY_SENDER_PERSON = "sender_person"
const val KEY_DATA_MIME_TYPE = "type"
const val KEY_DATA_URI = "uri"
const val KEY_EXTRAS_BUNDLE = "extras"

var Notification.text
    get() = this.extras.getCharSequence(EXTRA_TEXT)
    set(value) = this.extras.putCharSequence(EXTRA_TEXT, value)
var Notification.title
    get() = this.extras.getCharSequence(EXTRA_TITLE)
    set(value) = this.extras.putCharSequence(EXTRA_TITLE, value)
var Notification.remoteInputHistory
    get() = this.extras.getCharSequenceArray(EXTRA_REMOTE_INPUT_HISTORY)
    set(value) = this.extras.putCharSequenceArray(EXTRA_REMOTE_INPUT_HISTORY, value)
var Notification.isGroupConversation
    get() = this.extras.isGroupConversation
    set(value) { this.extras.isGroupConversation = value }
var Notification.type: MessageType?
    get() = this.getAdditional<MessageType>("type")
    set(value) { this.setAdditional<MessageType>("type", value) }
var Notification.person: CharSequence?
    get() = this.getAdditional<CharSequence>("person")
    set(value) { this.setAdditional<CharSequence>("person", value) }
var Notification.content: CharSequence?
    get() = this.getAdditional<CharSequence>("content")
    set(value) { this.setAdditional<CharSequence>("content", value) }

var Bundle.isGroupConversation: Boolean
    get() = this.getBoolean(EXTRA_IS_GROUP_CONVERSATION, false)
    set(value) = this.putBoolean(EXTRA_IS_GROUP_CONVERSATION, value)
