package notxx.xposed.diagnose;

import android.util.Log;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

import notxx.xposed.Diagnose;

public class DiagnoseForNotificationItem0 {
	static final String TAG = "WeChat.Diagnose";

	// 诊断钩子
	public static void hook(XC_LoadPackage.LoadPackageParam loadPackageParam) {
		Diagnose.hook(loadPackageParam, "com.tencent.mm.booter.notification.c", DiagnoseForNotificationItem0::hookNotificationItem0);
	}

	// 抓com.tencent.mm.booter.notification.c::a(NotificationItem)
	private static void hookNotificationItem0(final XC_LoadPackage.LoadPackageParam loadPackageParam, ClassLoader loader, Class target) {
		for (java.lang.reflect.Method method : target.getDeclaredMethods()) {
			Class[] types = method.getParameterTypes();
			if (types.length == 0/* || !android.graphics.Bitmap.class.equals(types[0])*/) continue;
			Log.d(TAG, "method " + method);
			XposedBridge.hookMethod(method, new XC_MethodHook() {
				@Override
				protected void afterHookedMethod(MethodHookParam param) {
					for (Object arg : param.args) Log.d(TAG, param.method + " " + arg);
				}
			});
		}
	}
}