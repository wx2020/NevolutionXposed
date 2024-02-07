package notxx.xposed.diagnose;

import android.util.Log;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

import notxx.xposed.Diagnose;

public class DiagnoseForLargeIcon {
	static final String TAG = "WeChat.Diagnose";

	// 诊断钩子
	public static void hook(XC_LoadPackage.LoadPackageParam loadPackageParam) {
		Diagnose.hook(loadPackageParam, "android.support.v4.app.s$c", DiagnoseForLargeIcon::hookSetLargeIcon);
	}

	// 抓android.support.v4.app.s$c::c(android.graphics.Bitmap) <- android.support.v4.app.NotificationCompat$Builder::setLargeIcon
	private static void hookSetLargeIcon(final XC_LoadPackage.LoadPackageParam loadPackageParam, ClassLoader loader, Class target) {
		for (java.lang.reflect.Method method : target.getDeclaredMethods()) {
			Class[] types = method.getParameterTypes();
			if (types.length != 1 || !android.graphics.Bitmap.class.equals(types[0])) {
				Log.d(TAG, "method " + method);
				continue;
			}
			XposedBridge.hookMethod(method, new XC_MethodHook() {
				@Override
				protected void afterHookedMethod(MethodHookParam param) {
					Log.d(TAG, param.method + " " + param.args[0]);
					Object stack = XposedHelpers.getAdditionalInstanceField(param.args[0], "stack");
					if (stack instanceof Exception) Log.d(TAG, "stack", (Exception)stack);
				}
			});
			XposedBridge.hookAllConstructors(android.graphics.Bitmap.class, new XC_MethodHook() {
				@Override
				protected void afterHookedMethod(MethodHookParam param) {
					XposedHelpers.setAdditionalInstanceField(param.thisObject, "stack", new Exception());
				}
			});
		}
	}
}