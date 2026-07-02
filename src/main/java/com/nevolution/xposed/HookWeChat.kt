package com.nevolution.xposed

import android.app.Notification
import android.app.NotificationManager
import android.os.Build.VERSION.SDK_INT
import android.os.Build.VERSION_CODES
import android.util.Log

import de.robv.android.xposed.callbacks.XC_LoadPackage

/**
 * 微信通知 Hook 核心类。
 * 通过 Xposed 拦截 NotificationManager.notify()，将微信的 BigText 通知转换为 MessagingStyle。
 */
object HookWeChat {
    private const val TAG = "HookWeChat"
    /** 通知栏主题色 — Nevolution 品牌绿色 */
    private const val PRIMARY_COLOR = 0xFF33B332.toInt()

    private val messages = Messages()
    private val channels = Channels()
    /** 标识通知渠道是否已初始化，避免重复创建 */
    private var channelsInitialized = false

    /**
     * 确保群聊/私聊通知渠道存在（Android 8+ 必需）。
     * 仅在首次调用时执行一次。
     */
    private fun ensureChannels(nm: NotificationManager) {
        if (SDK_INT >= VERSION_CODES.O && !channelsInitialized) {
            channelsInitialized = true
            channels.init(nm)
        }
    }

    /**
     * 入口方法：Hook NotificationManager.notify()。
     * 在 notify 调用前拦截，处理通知内容后再放行。
     */
    fun hook(lpparam: XC_LoadPackage.LoadPackageParam) {
        val cl = lpparam.classLoader
        var clazz = cl.findClassIfExists("android.app.NotificationManager")
        XLog.d(TAG, "NM clazz: $clazz process: ${lpparam.processName}")
        clazz?.hookMethod("notify", String::class.java, Integer.TYPE, Notification::class.java) {
            doBefore {
                // 在 notify 执行之前拦截，提取 tag / id / notification
                val tag = args[0] as String?
                val id = args[1] as Int
                val n = args[2] as Notification
                ensureChannels(thisObject as NotificationManager)
                process(tag, id, n)
            }
        }
    }

    /**
     * 处理单条通知：修改颜色、解析消息、分配渠道。
     */
    private fun process(tag: String?, id: Int, n: Notification) {
        Log.d(TAG, "process: $tag")
        if (SDK_INT >= VERSION_CODES.O)
            Log.d(TAG, "channel: ${n.channelId}")
        n.color = PRIMARY_COLOR             // 覆盖通知为 Nevolution 绿色主题
        messages.process(id, n)             // 解析消息内容，构造 MessagingStyle
        channels.process(n)                 // 按群聊/私聊分配通知渠道
    }
}
