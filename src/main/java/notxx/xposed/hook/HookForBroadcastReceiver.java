package com.oasisfeng.nevo.decorators.wechat;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

import com.oasisfeng.nevo.xposed.BuildConfig;

public class HookForBroadcastReceiver {
	private static final String TAG = "WeChat.HFBR";

	public static void hook(final XC_LoadPackage.LoadPackageParam loadPackageParam) {
		XposedBridge.hookAllConstructors(BroadcastReceiver.class, new XC_MethodHook() {
			@Override
			protected void beforeHookedMethod(MethodHookParam param) {
				Class<?> clazz = param.thisObject.getClass();
				String name = clazz.getCanonicalName();
				if (name == null) { // anonymous inner class
				// 	Log.d(TAG, "null this: " + clazz);
					return;
				}
				switch (name) {
					case "com.tencent.mm.plugin.auto.service.MMAutoMessageHeardReceiver":
						// Log.d(TAG, "auto message this: " + clazz);
						Log.d(TAG, "MMAutoMessageHeardReceiver: " + param.args.length);
					break;
					case "com.tencent.mm.plugin.auto.service.MMAutoMessageReplyReceiver":
						// Log.d(TAG, "auto message this: " + clazz);
						Log.d(TAG, "MMAutoMessageReplyReceiver: " + param.args.length);
					break;
					case "com.tencent.mm.booter.NotifyReceiver":
						Log.d(TAG, "notify receiver this: " + clazz);
					break;
					default:
						// Log.d(TAG, "clazz this: " + clazz.getCanonicalName());
					break;
				}
			}
		});
	}
}