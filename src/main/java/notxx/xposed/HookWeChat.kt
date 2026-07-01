package notxx.xposed

import android.app.Notification
import android.app.NotificationManager
import android.os.Build.VERSION.SDK_INT
import android.os.Build.VERSION_CODES
import android.util.Log

import de.robv.android.xposed.callbacks.XC_LoadPackage

import notxx.wechat.Channels
import notxx.wechat.Messages

object HookWeChat {
	private const val TAG = "HookWeChat"
	private const val PRIMARY_COLOR = 0xFF33B332.toInt()

	private val messages = Messages()
	private val channels = Channels()
	private var channelsInitialized = false

	private fun ensureChannels(nm: NotificationManager) {
		if (SDK_INT >= VERSION_CODES.O && !channelsInitialized) {
			channelsInitialized = true
			channels.init(nm)
		}
	}

	fun hook(lpparam: XC_LoadPackage.LoadPackageParam) {
		val cl = lpparam.classLoader
		var clazz = cl.findClassIfExists("android.app.NotificationManager")
		XLog.d(TAG, "NM clazz: $clazz process: ${lpparam.processName}")
		clazz?.hookMethod("notify", String::class.java, Integer.TYPE, Notification::class.java) {
			doBefore {
				val tag = args[0] as String?
				val id = args[1] as Int
				val n = args[2] as Notification
				ensureChannels(thisObject as NotificationManager)
				process(tag, id, n)
			}
		}
	}

	private fun process(tag: String?, id: Int, n: Notification) {
		Log.d(TAG, "process: $tag")
		if (SDK_INT >= VERSION_CODES.O)
			Log.d(TAG, "channel: ${n.channelId}")
		n.color = PRIMARY_COLOR
		messages.process(id, n)
		channels.process(n)
	}
}
