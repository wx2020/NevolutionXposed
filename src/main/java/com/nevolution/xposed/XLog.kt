package com.nevolution.xposed

import android.util.Log
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import java.lang.reflect.Method

/**
 * 日志工具类，统一通过 XposedBridge.log 输出。
 * 所有日志行以 [NevolutionXposed] 前缀标记，方便在 Logcat 中过滤。
 */
object XLog {
    @JvmStatic fun d(tag: String, message: String?) {
        XposedBridge.log("[NevolutionXposed]  $tag  $message")
    }

    fun i(tag: String, message: String?) {
        XposedBridge.log("[NevolutionXposed]  $tag  $message")
    }

    fun e(tag: String, message: String?, throwable: Throwable?) {
        i(tag, message)
        XposedBridge.log(throwable)
    }

    /**
     * 在 Xposed 方法 Hook 回调中输出详细的调用信息（方法签名、参数、返回值、异常）。
     * @param stackTrace 是否额外输出调用栈
     */
    fun XC_MethodHook.MethodHookParam.logMethod(tag: String, stackTrace: Boolean = false) {
        d(tag, "╔═══════════════════════════════════════════════════════")
        d(tag, method.toString())
        d(tag, "${method.name} called with ${args.contentDeepToString()}")
        if (stackTrace) {
            d(tag, Log.getStackTraceString(Throwable()))
        }
        if (hasThrowable()) {
            e(tag, "${method.name} thrown", throwable)
        } else if (method is Method && (method as Method).returnType != Void.TYPE) {
            d(tag, "${method.name} return $result")
        }
        d(tag, "╚═══════════════════════════════════════════════════════")
    }
}
