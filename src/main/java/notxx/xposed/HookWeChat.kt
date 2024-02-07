package notxx.xposed

import android.app.Notification
import android.app.Notification.Action
import android.app.NotificationManager
import android.app.PendingIntent
import android.os.Build.VERSION.SDK_INT
import android.os.Build.VERSION_CODES
import android.content.Context
import android.util.Log
import android.util.LruCache

import java.util.concurrent.atomic.AtomicReference

import android.app.AndroidAppHelper
import de.robv.android.xposed.callbacks.XC_LoadPackage

import notxx.wechat.Channels
import notxx.wechat.Messages
import notxx.wechat.Proxy
import notxx.wechat.RecastAction
import notxx.wechat.title
import notxx.wechat.isRecast
import notxx.xposed.hook.Auto as ForAuto
import notxx.xposed.hook.FileOutputStream as ForFileOutputStream

object HookWeChat {
	private const val TAG = "HookWeChat"
	private const val PRIMARY_COLOR = 0xFF33B332.toInt()

	private val cache = LruCache<Int, Notification>(100)
	private var nmRef = AtomicReference<NotificationManager>()
	private val forFOS = ForFileOutputStream()
	private val forAuto = ForAuto()
	private val messages = Messages()
	private val contextRef = AtomicReference<Context>()
	private var proxyRef = AtomicReference<Proxy>()
	private val proxy: Proxy
		get() = proxyRef.get()!!
	private val channels = Channels()

	private fun cancel(id: Int) {
		val nm = nmRef.get()
		if (nm == null) {
			Log.d(TAG, "cancel($id) with null NM")
		} else {
			nm.cancel(null, id)
		}
	}

	private fun recast(id: Int, action: RecastAction) {
		val nm = nmRef.get()
		if (nm == null) {
			Log.d(TAG, "recast($id) with null NM")
		} else {
			val n = cache.get(id)
			if (n != null) {
				action(n)
				n.isRecast = true
				Log.d(TAG, "recast $id ${n.title}")
				nm.notify(null, id, n)
			} else {
				Log.d(TAG, "can not recast $id, so cancel it")
				nm.cancel(null, id)
			}
		}
	}

	fun hook(lpparam: XC_LoadPackage.LoadPackageParam) {
		val cl = lpparam.classLoader
		var clazz = cl.findClassIfExists("android.app.NotificationManager")
		XLog.d(TAG, "NM clazz: $clazz process: ${lpparam.processName}")
		clazz?.hookMethod("notify", String::class.java, Integer.TYPE, Notification::class.java) {
			doBefore {
				init(thisObject as NotificationManager)
				val tag = args[0] as String?
				val id = args[1] as Int
				val n = args[2] as Notification
				// Log.d(TAG, "before apply $nm $tag $id $n process: ${lpparam.processName}")
				process(tag, id, n)
			}
		}
		clazz = cl.findClassIfExists("android.app.ContextImpl")
		XLog.d(TAG, "ContextImpl clazz: $clazz process: ${lpparam.processName}")
		clazz?.hookAllMethods("createAppContext") {
			doAfter {
				init(result as Context)
			}
		}
		forAuto.hook(lpparam)
		forFOS.hook(lpparam)
	}

	private fun init(context: Context) {
		if (!contextRef.compareAndSet(null, context)) return
		Log.d(TAG, "init($context)")
		proxyRef.compareAndSet(null, Proxy(context, ::cancel, ::recast))
	}

	private fun init(nm: NotificationManager) {
		if (!nmRef.compareAndSet(null, nm)) return
		Log.d(TAG, "init($nm)")
		// 维护会话渠道
		if (SDK_INT >= VERSION_CODES.O) 
			channels.init(nm)
	}

	private fun process(tag: String?, id: Int, n: Notification) {
		cache.put(id, n)
		// mWeChatTargetingO
		// cache
		if (SDK_INT >= VERSION_CODES.O)
			Log.d(TAG, "channel: ${n.channelId}")
		// emoji
		// channel
		n.color = PRIMARY_COLOR
		val actions = mutableListOf<Action>()
		// 更新会话
		val conversation = messages.conversation(n)
		if (n.isRecast == true) {
			messages.recast(id, n, conversation)
		} else if (conversation != null) {
			messages.process(id, n, conversation)
			proxy.process(id, n, conversation, actions)
		} else {
			messages.process(id, n) // TODO
		}
		if (SDK_INT >= VERSION_CODES.N && actions.size > 0)
			n.actions = actions.toTypedArray()
		
		// 显示图片
		// 显示会话
		// 选择会话渠道
		channels.process(n)
	}
}