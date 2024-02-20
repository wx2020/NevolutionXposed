package notxx.xposed;

import android.graphics.drawable.Icon;
import android.util.Log;

import java.util.Queue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicReference;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

// 诊断回调
public interface Diagnose {
	static final String TAG = "WeChat.Diagnose";

	// 诊断钩子
	public static void hook(XC_LoadPackage.LoadPackageParam loadPackageParam, String targetClassName, Diagnose diagnose) {
		final Queue<ClassLoader> loaders = new LinkedBlockingQueue<>();
		final AtomicReference<Class> targetRef = new AtomicReference<>();
		Class clazz = android.app.Notification.Builder.class;
		Log.d(TAG, "hook clazz " + clazz);
		XposedHelpers.findAndHookMethod(clazz, "setLargeIcon", Icon.class, new XC_MethodHook() {
			@Override
			protected void afterHookedMethod(MethodHookParam param) {
				if (targetRef.get() != null) return;
				// Log.d(TAG, "method " + param.method + " " + loaders);
				for (ClassLoader loader : loaders) {
					// Log.d(TAG, "loader " + loader);
					try {
						Class target = XposedHelpers.findClassIfExists(targetClassName, loader);
						if (target == null) {
							Log.d(TAG, "cannot find " + targetClassName + " with " + loader);
							continue;
						}
						Log.d(TAG, "target " + target);
						// Class ni = XposedHelpers.findClass("com.tencent.mm.booter.notification.NotificationItem", loader);
						targetRef.set(target);
						if (diagnose != null) diagnose.perform(loadPackageParam, loader, target);
					} catch (Exception ex) { Log.d(TAG, "find " + targetClassName + " failed with " + loader); }
				}
			}
		});
		clazz = ClassLoader.class; // 
		// Log.d(TAG, "clazz " + clazz);
		XposedBridge.hookAllConstructors(clazz, new XC_MethodHook() {
			@Override
			protected void afterHookedMethod(MethodHookParam param) {
				ClassLoader cl = (ClassLoader)param.thisObject;
				// Log.d(TAG, "ClassLoader " + cl);
				loaders.add(cl);
			}
		});
	}

    public void perform(final XC_LoadPackage.LoadPackageParam loadPackageParam, final ClassLoader loader, final Class target);

}
