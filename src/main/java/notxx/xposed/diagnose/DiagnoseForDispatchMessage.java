package notxx.xposed.diagnose;

import android.os.Bundle;
import android.os.Message;
import android.util.Log;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

import notxx.xposed.Diagnose;

public class DiagnoseForDispatchMessage {
	static final String TAG = "WeChat.Diagnose";

	// 诊断钩子
	public static void hook(XC_LoadPackage.LoadPackageParam loadPackageParam) {
		Diagnose.hook(loadPackageParam, "com.tencent.mm.sdk.platformtools.am", DiagnoseForDispatchMessage::hookDispatchMessage);
	}

	// 抓com.tencent.mm.sdk.platformtools.am::dispatchMessage(android.os.Message)
	private static void hookDispatchMessage(final XC_LoadPackage.LoadPackageParam loadPackageParam, ClassLoader loader, Class target) {
		XposedHelpers.findAndHookMethod(target, "dispatchMessage", Message.class, new XC_MethodHook() {
			@Override
			protected void afterHookedMethod(MethodHookParam param) {
				Message msg = (Message)param.args[0];
				Bundle data = msg.getData();
				Log.d(TAG, "data " + data);
				int msgType = data.getInt("notification.show.message.type", -1);
				Log.d(TAG, "msgType " + msgType);
				if (msgType == -1) return;
				Log.d(TAG, param.method.getName() + " " + data);
				// for (java.lang.reflect.Field field : ni.getDeclaredFields()) {
				// 	try {
				// 		field.setAccessible(true);
				// 		Log.d(TAG, "ni " + field.getName() + " " + field.getType() + " " + field.get(param.args[0]));
				// 	} catch (IllegalAccessException ex0) {
				// 		Log.d(TAG, "ni error " + field.getName() + " " + field.getType() + " ");
				// 	}
				// }
			}
		});
	}
}