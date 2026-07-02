package com.nevolution.xposed

import android.app.Notification
import android.app.Notification.EXTRA_CONVERSATION_TITLE
import android.app.Notification.EXTRA_TEXT
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
/** 微信通知中发送者与消息内容的默认分隔符 */
private const val SENDER_SEPARATOR = ": "

/** 每个会话保留的最大历史消息条数 */
private const val THREAD_MAX = 10

/** 简化的行数据：发送者名与消息正文 */
typealias Line = Pair<String?, String>

/**
 * 消息处理器。
 * 负责解析微信通知的 ticker/text/title，构建消息历史 [Crumb] 列表，
 * 并填充 Notification.extras 以呈现 MessagingStyle 样式。
 */
class Messages {
    companion object {
        /** 匹配微信未读计数标记 "[N条]" */
        private val UNREAD_REGEX = "^\\[(\\d{1,4})条\\]".toRegex()
        /** 匹配撤回通知文本，可选提取撤回者 */
        private val RECALL_REGEX = "(\"(?<recaller>[^\"]+)\" )?撤回了?一条消息".toRegex()

        /** 发送者 Person 对象缓存（避免重复构造，上限 100） */
        private val personCache = LruCache<String, Person>(100)
        /** 通知 ID → 消息历史列表 的 LRU 缓存（上限 100 条通知） */
        private val threadCache = LruCache<Int, MutableList<Crumb>>(100)

        /**
         * 尝试从通知文本中提取未读条数 [N条]，并设置 n.number（用于显示角标数字）。
         */
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

    /**
     * 内部解析：判断通知类型（普通消息 / 撤回消息），并提取发送者与正文。
     *
     * 逻辑判断顺序：
     * 1. tickerText == null → 撤回消息（RECALL）
     * 2. 其他 → 普通消息（MESSAGE），通过 tickerText 分割出发送者和内容
     */
    private fun process_internal(n: Notification) {
        val tickerText = n.tickerText; val text = n.text; val title = n.title
        Log.d(TAG, "0?? tickerText: $tickerText text: $text title: $title")
        if (text == null || title == null) {
            // 缺少必要字段，无法解析，跳过
        } else if (tickerText == null) {
            // ticker 为空 → 判定为撤回消息
            n.type = MessageType.RECALL
            val recall_match = RECALL_REGEX.find(text)
            if (recall_match != null) {
                val group = recall_match.groups[2]
                n.content = recall_match.groups[0]?.value
                if (group != null) {
                    n.person = group.value       // "某某" 撤回了
                } else {
                    n.person = title              // 自己撤回，title 即发送者
                }
            } else {
                Log.d(TAG, "1??")
            }
            parse_unread(n, text)
        } else {
            // ticker 不为空 → 普通消息
            n.type = MessageType.MESSAGE
            val pos = text.indexOf(tickerText.toString())
            if (tickerText.length + pos == text.length) {
                // ticker 是 text 的后缀部分：形如 "text: ticker"
                val r = tickerText.toString().split(SENDER_SEPARATOR, ignoreCase = false, limit = 2)
                if (tickerText.startsWith(title.toString(), SENDER_SEPARATOR)) {
                    // ticker 以 "title: " 开头，title 即发送者
                    n.person = title
                    n.content = r[1]
                } else {
                    // 否则从 ticker 中提取发送者
                    n.person = r[0]
                    n.content = r[1]
                }
            } else if ("$title$SENDER_SEPARATOR$text" == tickerText) {
                // ticker 恰好等于 "title: text"
                n.person = title
                n.content = text
            } else {
                Log.d(TAG, "2?？")
            }
            parse_unread(n, text)
        }
    }

    /**
     * 查找或创建一个 Person 对象。
     * - 优先从 personCache 获取
     * - 若缓存未命中或 fail 检测返回 true，则通过 build 构造新对象
     */
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

    /**
     * 构造 MessagingStyle 所需的 Bundle 数据并写入 extras。
     * 包含：会话标题、参与者、消息历史数组、群聊标记、模板名称。
     */
    @RequiresApi(VERSION_CODES.P)
    private fun export_full_conversation(extras: Bundle, participant: Person, thread: List<Crumb>) {
        @Suppress("DEPRECATION")
        extras.putCharSequence(EXTRA_SELF_DISPLAY_NAME, "你")
        extras.putParcelable(Notification.EXTRA_MESSAGING_PERSON, participant)
        extras.putCharSequence(EXTRA_CONVERSATION_TITLE, participant.name)
        if (!thread.isEmpty()) {
            val out = mutableListOf<Parcelable>()
            // 将每条 Crumb 序列化为 Bundle 后加入数组
            thread.forEach { it.export(Pair(it.senderName?.toString(), it.content?.toString() ?: ""), out) }
            extras.putParcelableArray(EXTRA_MESSAGES, out.toTypedArray())
        }
        // 如果存在与当前参与者不同的发送者，则标记为群聊
        val first = thread.find { it.senderName != participant.name }
        if (first != null) extras.isGroupConversation = true
        extras.putString(EXTRA_TEMPLATE, Notification.MessagingStyle::class.java.name)
    }

    /**
     * 公开入口：处理一条微信通知。
     *
     * 流程：
     * 1. process_internal 解析类型/发送者/内容
     * 2. 若成功解析，将消息追加到 threadCache 对应列表
     * 3. 若 Android >= P，调用 export_full_conversation 写入 MessagingStyle 数据
     */
    fun process(id: Int, n: Notification) {
        process_internal(n)
        if (n.type == null) return                     // 解析失败，跳过
        val participantCs = n.title ?: return          // title 作为会话参与者
        val participant = participantCs.toString()
        var thread = threadCache.get(id)
        if (thread == null) {
            thread = mutableListOf<Crumb>()
            threadCache.put(id, thread)
        }
        // 确定时间戳
        val timestamp = if (n.`when` > 0) n.`when` else System.currentTimeMillis()
        val senderName = n.person ?: participantCs     // 发送者名，缺省时用 title
        val content = n.content ?: n.tickerText ?: n.text ?: return
        val line: Line = Pair(senderName?.toString(), content.toString())
        synchronized(thread) {
            if (n.type == MessageType.RECALL) {
                // 撤回消息
                val crumb = Crumb(timestamp, n)
                crumb.isRecall = true
                crumb.content = n.content ?: "撤回一条消息"
                thread.add(crumb)
                Log.d(TAG, "add(recall) ${crumb.senderName} ${crumb.content} (thread=${thread.size})")
            } else {
                // 普通消息
                val crumb = Crumb(timestamp, n)
                thread.add(crumb)
                if (SDK_INT >= VERSION_CODES.P)
                    crumb.senderPerson = find_person(line.first ?: participant)
                if (line.second != "[消息]") crumb.content = line.second
                Log.d(TAG, "add(...) ${crumb.senderName} ${crumb.content} (thread=${thread.size})")
            }
            // 控制历史数量不超过 THREAD_MAX
            while (thread.size > THREAD_MAX) thread.removeAt(0)
        }
        // 如果条件满足，写入 MessagingStyle 数据
        if (SDK_INT >= VERSION_CODES.P) {
            val participantPerson = find_person(participant, { it?.icon == null }, {
                Person.Builder().setIcon(n.getLargeIcon()).setName(participant).build()
            })
            export_full_conversation(n.extras, participantPerson, thread)
        }
    }
}

/**
 * 单条消息快照（Crumb）。
 * 保存从 Notification 中提取的各个字段，同时记录 recall/isRecall 等额外状态。
 */
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

    /** 渲染文本：撤回消息添加 ↩️ 前缀 */
    fun text() = if (isRecall) { "↩️ $content" } else { content }

    /**
     * 将当前消息导出为 Notification.MessagingStyle 所需的 Bundle 格式。
     * 同时将 remoteInputHistory 中的每条文本作为独立消息追加。
     */
    fun export(line: Line, output: MutableList<Parcelable>) {
        val bundle = Bundle()
        bundle.putCharSequence(KEY_TEXT, text())
        bundle.putLong(KEY_TIMESTAMP, timestamp)
        if (senderName != null) bundle.putCharSequence(KEY_SENDER, senderName)
        if (SDK_INT >= VERSION_CODES.P && senderPerson != null) bundle.putParcelable(KEY_SENDER_PERSON, senderPerson)
        if (dataMimeType != null) bundle.putString(KEY_DATA_MIME_TYPE, dataMimeType)
        if (dataUri != null) bundle.putParcelable(KEY_DATA_URI, dataUri)
        output.add(bundle)
        // 将 RemoteInput 历史作为附加消息输出
        remoteInputHistory?.forEach { text ->
            val bundle = Bundle()
            bundle.putCharSequence(KEY_TEXT, text)
            bundle.putLong(KEY_TIMESTAMP, timestamp)
            bundle.putCharSequence(KEY_SENDER, null)
            output.add(bundle)
        }
    }
}

/** 发送者校验谓词：检测 Person 是否需要重建 */
typealias PersonPredicate = (Person?) -> Boolean
/** Person 构造器 */
typealias PersonBuilder = () -> Person

/** 消息类型 */
enum class MessageType {
    /** 普通消息 */
    MESSAGE,
    /** 撤回消息 */
    RECALL,
}

/**
 * 检测 CharSequence 是否以 "$needle1$needle2" 开头。
 * 用于判断 tickerText 格式 "title: xxx"。
 */
fun CharSequence.startsWith(needle1: String, needle2: String): Boolean {
    val index1 = this.indexOf(needle1)
    if (index1 != 0) return false
    val start = needle1.length
    val index2 = this.indexOf(needle2, start)
    return index2 == start
}

/** 将文本转为带删除线样式的 SpannableString */
fun CharSequence.strike(): SpannableString {
    val s = SpannableString(this)
    s.setSpan(android.text.style.StrikethroughSpan(), 0, s.length - 1, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    return s
}

/** 将文本转为带下划线样式的 SpannableString */
fun CharSequence.underline(): SpannableString {
    val s = SpannableString(this)
    s.setSpan(android.text.style.UnderlineSpan(), 0, s.length - 1, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    return s
}
