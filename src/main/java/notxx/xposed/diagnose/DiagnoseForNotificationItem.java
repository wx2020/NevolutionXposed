package notxx.xposed.diagnose;

import android.util.Log;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

import notxx.xposed.Diagnose;

public class DiagnoseForNotificationItem {
	static final String TAG = "WeChat.Diagnose";

	// 诊断钩子
	public static void hook(XC_LoadPackage.LoadPackageParam loadPackageParam) {
		Diagnose.hook(loadPackageParam, "com.tencent.mm.booter.notification.NotificationItem", DiagnoseForNotificationItem::hookNotificationItem);
	}

	// 抓com.tencent.mm.booter.notification.NotificationItem的构造函数
	private static void hookNotificationItem(final XC_LoadPackage.LoadPackageParam loadPackageParam, ClassLoader loader, Class target) {
	}
}