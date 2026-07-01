package com.nevolution.xposed

import android.app.Notification
import android.app.Notification.EXTRA_CONVERSATION_TITLE
import android.app.Notification.EXTRA_MESSAGES
import android.app.Notification.EXTRA_SELF_DISPLAY_NAME
import android.app.Notification.EXTRA_TEMPLATE
import android.app.Person
import android.os.Bundle
import android.os.Build.VERSION.SDK_INT
import android.os.Build.VERSION_CODES
import android.os.Parcelable
import android.text.SpannableString
import android.util.Log
import android.util.LruCache

import androidx.annotation.RequiresApi

private const val TAG = "WeChat.Messages"
private const val SENDER_SEPARATOR = ": "

private const val THREAD_MAX = 10

typealias Line = Pair<String?, String>

class Messages {
    companion object {
        private val UNREAD_REGEX = "^\\[(\\d{1,4})条\\]".toRegex()
        private val RECALL_REGEX = "(\"(?<recaller>[^\"]+)\" )?撤回了?一条消息".toRegex()

        private val personCache = LruCache<String, Person>(100)
        private val threadCache = LruCache<Int, MutableList<Crumb>>(100)

        private fun parse_unread(n: Notification, text: CharSequence) {
            val unread_match = UNREAD_REGEX.find(text)
            if (unread_match != null) {
                val group = unread_match.groups[1]
                if (group != null) {
                    n.number = Integer.parseInt(group.value)
                }
            }
        }
    }

    private fun process_internal(n: Notification) {
        val tickerText = n.tickerText; val text = n.text; val title = n.title
        Log.d(TAG, "0?? tickerText: $tickerText text: $text title: $title")
        if (text == null || title == null) {
            // ??
        } else if (tickerText == null) {
            n.type = MessageType.RECALL
            val recall_match = RECALL_REGEX.find(text)
            if (recall_match != null) {
                val group = recall_match.groups[2]
                n.content = recall_match.groups[0]?.value
                if (group != null) {
                    n.person = group.value
                } else {
                    n.person = title
                }
            } else {
                Log.d(TAG, "1??")
            }
            parse_unread(n, text)
        } else {
            n.type = MessageType.MESSAGE
            val pos = text.indexOf(tickerText.toString())
            if (tickerText.length + pos == text.length) {
                val r = tickerText.toString().split(SENDER_SEPARATOR, ignoreCase = false, limit = 2)
                if (tickerText.startsWith(title.toString(), SENDER_SEPARATOR)) {
                    n.person = title
                    n.content = r[1]
                } else {
                    n.person = r[0]
                    n.content = r[1]
                }
            } else if ("$title$SENDER_SEPARATOR$text" == tickerText) {
                n.person = title
                n.content = text
            } else {
                Log.d(TAG, "2?？")
            }
            parse_unread(n, text)
        }
    }

    @RequiresApi(VERSION_CODES.P)
    private fun find_person(key: String,
            fail: PersonPredicate = { false },
            build: PersonBuilder? = { Person.Builder().setName(key).build() }): Person {
        var person = personCache.get(key)
        if (person == null || fail(person)) {
            person = build?.invoke()
            personCache.put(key, person)
        }
        return person
    }

    @RequiresApi(VERSION_CODES.P)
    private fun export_full_conversation(extras: Bundle, participant: Person, thread: List<Crumb>) {
        @Suppress("DEPRECATION")
        extras.putCharSequence(EXTRA_SELF_DISPLAY_NAME, "你")
        extras.putParcelable(Notification.EXTRA_MESSAGING_PERSON, participant)
        extras.putCharSequence(EXTRA_CONVERSATION_TITLE, participant.name)
        if (!thread.isEmpty()) {
            val out = mutableListOf<Parcelable>()
            thread.forEach { it.export(Pair(it.senderName?.toString(), it.content?.toString() ?: ""), out) }
            extras.putParcelableArray(EXTRA_MESSAGES, out.toTypedArray())
        }
        val first = thread.find { it.senderName != participant.name }
        if (first != null) extras.isGroupConversation = true
        extras.putString(EXTRA_TEMPLATE, Notification.MessagingStyle::class.java.name)
    }

    fun process(id: Int, n: Notification) {
        process_internal(n)
        if (n.type == null) return
        val participantCs = n.title ?: return
        val participant = participantCs.toString()
        var thread = threadCache.get(id)
        if (thread == null) {
            thread = mutableListOf<Crumb>()
            threadCache.put(id, thread)
        }
        val timestamp = if (n.`when` > 0) n.`when` else System.currentTimeMillis()
        val senderName = n.person ?: participantCs
        val content = n.content ?: n.tickerText ?: n.text ?: return
        val line: Line = Pair(senderName?.toString(), content.toString())
        synchronized(thread) {
            if (n.type == MessageType.RECALL) {
                val crumb = Crumb(timestamp, n)
                crumb.isRecall = true
                crumb.content = n.content ?: "撤回一条消息"
                thread.add(crumb)
                Log.d(TAG, "add(recall) ${crumb.senderName} ${crumb.content} (thread=${thread.size})")
            } else {
                val crumb = Crumb(timestamp, n)
                thread.add(crumb)
                if (SDK_INT >= VERSION_CODES.P)
                    crumb.senderPerson = find_person(line.first ?: participant)
                if (line.second != "[消息]") crumb.content = line.second
                Log.d(TAG, "add(...) ${crumb.senderName} ${crumb.content} (thread=${thread.size})")
            }
            while (thread.size > THREAD_MAX) thread.removeAt(0)
        }
        if (SDK_INT >= VERSION_CODES.P) {
            val participantPerson = find_person(participant, { it?.icon == null }, {
                Person.Builder().setIcon(n.getLargeIcon()).setName(participant).build()
            })
            export_full_conversation(n.extras, participantPerson, thread)
        }
    }
}

class Crumb {
    var type: MessageType?
    var title: CharSequence?
    var tickerText: CharSequence?
    var text: CharSequence?
    var remoteInputHistory: Array<CharSequence>?
    var content: CharSequence?
    var isRecall = false

    var timestamp: Long
    var senderName: CharSequence? = null
    var senderPerson: Person? = null
    var dataMimeType: String? = null
    var dataUri: android.net.Uri? = null

    constructor(timestamp: Long, n: Notification) {
        this.timestamp = timestamp
        this.type = n.type
        this.title = n.title
        this.tickerText = n.tickerText
        this.text = n.text
        this.remoteInputHistory = n.remoteInputHistory
        this.senderName = n.person
        this.content = null
    }

    fun text() = if (isRecall) { "↩️ $content" } else { content }

    fun export(line: Line, output: MutableList<Parcelable>) {
        val bundle = Bundle()
        bundle.putCharSequence(KEY_TEXT, text())
        bundle.putLong(KEY_TIMESTAMP, timestamp)
        if (senderName != null) bundle.putCharSequence(KEY_SENDER, senderName)
        if (SDK_INT >= VERSION_CODES.P && senderPerson != null) bundle.putParcelable(KEY_SENDER_PERSON, senderPerson)
        if (dataMimeType != null) bundle.putString(KEY_DATA_MIME_TYPE, dataMimeType)
        if (dataUri != null) bundle.putParcelable(KEY_DATA_URI, dataUri)
        output.add(bundle)
        remoteInputHistory?.forEach { text ->
            val bundle = Bundle()
            bundle.putCharSequence(KEY_TEXT, text)
            bundle.putLong(KEY_TIMESTAMP, timestamp)
            bundle.putCharSequence(KEY_SENDER, null)
            output.add(bundle)
        }
    }
}

typealias PersonPredicate = (Person?) -> Boolean
typealias PersonBuilder = () -> Person

enum class MessageType {
    MESSAGE,
    RECALL,
}

fun CharSequence.startsWith(needle1: String, needle2: String): Boolean {
    val index1 = this.indexOf(needle1)
    if (index1 != 0) return false
    val start = needle1.length
    val index2 = this.indexOf(needle2, start)
    return index2 == start
}

fun CharSequence.strike(): SpannableString {
    val s = SpannableString(this)
    s.setSpan(android.text.style.StrikethroughSpan(), 0, s.length - 1, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    return s
}

fun CharSequence.underline(): SpannableString {
    val s = SpannableString(this)
    s.setSpan(android.text.style.UnderlineSpan(), 0, s.length - 1, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    return s
}
