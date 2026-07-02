package com.nevolution.xposed

import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam

/**
 * Xposed 模块入口类 — 当 Zygote 加载模块时，框架会回调 handleLoadPackage。
 */
class Main : IXposedHookLoadPackage {

    companion object {
        /** 日志标签，用于过滤 XposedBridge.log 输出 */
        private const val TAG = "Main"
    }

    @Throws(Throwable::class)
    override fun handleLoadPackage(lpparam: LoadPackageParam) {
        // 仅 Hook 微信主进程，避免影响微信的 push 子进程
        if (lpparam.packageName == PACKAGE_WECHAT && lpparam.processName == PROCESS_WECHAT) {
            XLog.d(TAG, "HookWeChat(): ${lpparam.packageName} process: ${lpparam.processName}")
            HookWeChat.hook(lpparam)
        } else {
            // 其他进程跳过，不作处理
        }
    }

}
