package com.nevolution.xposed

import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam

class Main : IXposedHookLoadPackage {

    companion object {
        private const val TAG = "Main"
    }

    @Throws(Throwable::class)
    override fun handleLoadPackage(lpparam: LoadPackageParam) {
        if (lpparam.packageName == PACKAGE_WECHAT && lpparam.processName == PROCESS_WECHAT) {
            XLog.d(TAG, "HookWeChat(): ${lpparam.packageName} process: ${lpparam.processName}")
            HookWeChat.hook(lpparam)
        } else {
            // XLog.d(TAG, "skip: ${lpparam.packageName} process: ${lpparam.processName}")
        }
    }

}
