package com.oasisfeng.nevo.xposed;

import android.app.Notification;
import android.app.NotificationManager;
import android.content.Context;
import android.content.ContextWrapper;
import android.os.Build.VERSION_CODES;
import android.service.notification.NotificationListenerService;
import android.service.notification.NotificationListenerService.RankingMap;
import android.service.notification.StatusBarNotification;
import android.util.Log;
import android.widget.RemoteViews;

import androidx.annotation.Keep;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import notxx.xposed.DeviceSharedPreferences;

import com.oasisfeng.nevo.sdk.HookSupport;
import com.oasisfeng.nevo.sdk.NevoDecoratorService;
import com.oasisfeng.nevo.sdk.NevoDecoratorService.LocalDecorator;
import com.oasisfeng.nevo.sdk.NevoDecoratorService.SystemUIDecorator;
import com.oasisfeng.nevo.xposed.BuildConfig;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.XSharedPreferences;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

import static android.os.Build.VERSION.SDK_INT;


/**
 * hook and manupinate notifications.
 * 
 * @author notXX
 */
public class MainHook implements IXposedHookLoadPackage {
	private static final String TAG = "MainHook";

	private final XSharedPreferences pref = DeviceSharedPreferences.get(BuildConfig.APPLICATION_ID);
	private final NevoDecoratorService wechat = new com.oasisfeng.nevo.decorators.wechat.WeChatDecorator();

	private static void inspect(XC_LoadPackage.LoadPackageParam loadPackageParam, String className, String... methods) {
		try {
			final Class<?> clazz = XposedHelpers.findClass(className, loadPackageParam.classLoader);
			XposedBridge.log("inspect clazz: " + clazz + " " + loadPackageParam.packageName);
			Consumer<String> inspect = method -> {
				XposedBridge.hookAllMethods(clazz, method, new XC_MethodHook() {
					@Override
					protected void afterHookedMethod(MethodHookParam param) {
						Log.d("inspect.method", loadPackageParam.packageName + " " + param.method.getName());
						for (Object arg : param.args) {
							Log.d("inspect.method", loadPackageParam.packageName + " arg " + arg);
						}
						if (BuildConfig.DEBUG) Log.d(TAG, loadPackageParam.packageName + " " + Log.getStackTraceString(new Exception()));
					}
				});
			};
			for (String method : methods) {
				if (SDK_INT >= VERSION_CODES.O) inspect.accept(method);
			}
		} catch (XposedHelpers.ClassNotFoundError e) { /* XposedBridge.log("ContextImpl hook failed"); */ }
	}

	@Keep
	private static void inspectThen(XC_LoadPackage.LoadPackageParam loadPackageParam, String className, Consumer<Class<?>>... thens) {
		try {
			final Class<?> clazz = XposedHelpers.findClass(className, loadPackageParam.classLoader);
			XposedBridge.log("inspect clazz: " + clazz + " " + loadPackageParam.packageName);
			for (Consumer<Class<?>> then : thens) {
				if (SDK_INT >= VERSION_CODES.O) then.accept(clazz);
			}
		} catch (XposedHelpers.ClassNotFoundError e) { /* XposedBridge.log("ContextImpl hook failed"); */ }
	}

	@Override
	public void handleLoadPackage(XC_LoadPackage.LoadPackageParam loadPackageParam) {
		switch (loadPackageParam.packageName) {
			case "com.android.systemui":
			hookSystemUI(loadPackageParam);
			break;
			case "com.tencent.mm": // TODO
			hookWeChat(loadPackageParam);
		}
		/* inspect(loadPackageParam,
				"com.android.server.notification.NotificationManagerService",
				"getNotificationChannel",
				"deleteNotificationChannel",
				"deleteNotificationChannelGroup",
				"createNotificationChannels"); */
	}

	private void hookSystemUI(XC_LoadPackage.LoadPackageParam loadPackageParam) {
		AtomicReference<NotificationListenerService> nlsRef = new AtomicReference<>();
		final XC_MethodHook onNotificationPosted = new XC_MethodHook() { // 捕获通知到达
			@Override
			protected void beforeHookedMethod(MethodHookParam param) {
				StatusBarNotification sbn = (StatusBarNotification)param.args[0];
				// RankingMap rankingMap = (RankingMap)param.args[1];
				Log.d(TAG, "onNotificationPosted");
				onNotificationPosted(sbn);
			}
		}, onNotificationRemoved = new XC_MethodHook() { // 捕获通知移除
			@Override
			protected void beforeHookedMethod(MethodHookParam param) {
				StatusBarNotification sbn = (StatusBarNotification)param.args[0];
				// RankingMap rankingMap = (RankingMap)param.args[1];
				// NotificationStats stats = (NotificationStats)param.args[2];
				int reason = (int)param.args[3];
				Log.d(TAG, "onNotificationRemoved");
				onNotificationRemoved(sbn, reason);
			}
		}, nls = new XC_MethodHook() { // 捕获NotificationListenerService的具体实现
			@Override
			protected void afterHookedMethod(MethodHookParam param) {
				// XposedBridge.log("nls constructor " + param.thisObject);
				NotificationListenerService nls = (NotificationListenerService)param.thisObject;
				if (nlsRef.compareAndSet(null, nls)) {
					SystemUIDecorator.setNLS(nls);
				}
				try {
					final Class<?> clazz = nls.getClass();
					XposedBridge.log("NL clazz: " + clazz + " " + loadPackageParam.packageName);
					Method method = XposedHelpers.findMethodExact(clazz, "onNotificationPosted", 
							StatusBarNotification.class, RankingMap.class);
					Log.d(TAG, "method " + method);
					XposedBridge.hookMethod(method, onNotificationPosted);
					method = XposedHelpers.findMethodBestMatch(clazz, "onNotificationRemoved",  StatusBarNotification.class, RankingMap.class,
							XposedHelpers.findClass("android.service.notification.NotificationStats", loadPackageParam.classLoader), int.class);
					Log.d(TAG, "method " + method);
					XposedBridge.hookMethod(method, onNotificationRemoved);
				} catch (XposedHelpers.ClassNotFoundError e) { XposedBridge.log("NL hook failed "); }
			}
		};
		try {
			XposedBridge.hookAllConstructors(NotificationListenerService.class, nls);
		} catch (XposedHelpers.ClassNotFoundError e) { XposedBridge.log("NotificationListenerService hook failed "); }
		try {
			final Class<?> clazz = XposedHelpers.findClass("android.app.ContextImpl", loadPackageParam.classLoader);
			XposedBridge.log("CI clazz: " + clazz);
			AtomicReference<Context> ref = new AtomicReference<>();
			XposedBridge.hookAllMethods(clazz, "createAppContext", new XC_MethodHook() {
				@Override
				protected void afterHookedMethod(MethodHookParam param) {
					Context context = (Context)param.getResult();
					if (ref.compareAndSet(null, context)) {
						XposedBridge.log("onCreate " + context);
						onCreate(context);
					}
				}
			});
		} catch (XposedHelpers.ClassNotFoundError e) { XposedBridge.log("ContextImpl hook failed"); }
		/* try {
			HookSupport fix = new notxx.notification.MIUIBetaFixXposed();
			fix.hook(loadPackageParam);
		} catch (XposedHelpers.ClassNotFoundError e) { XposedBridge.log("fix hook failed"); } */
	}
	
	private void onCreate(Context context) {
		NevoDecoratorService.setAppContext(context);
	}

	private void onNotificationPosted(StatusBarNotification sbn) {
		if (XposedHelpers.getAdditionalInstanceField(sbn, "applied") != null) {
			Log.d(TAG, "skip " + sbn);
			return;
		}
		XposedHelpers.setAdditionalInstanceField(sbn, "applied", true);
	}

	private void onNotificationRemoved(StatusBarNotification sbn, int reason) {
	}

	private void hookWeChat(XC_LoadPackage.LoadPackageParam loadPackageParam) {
		if (!"com.tencent.mm".equals(loadPackageParam.processName)) return;
		try {
			final Class<?> clazz = XposedHelpers.findClass("android.app.NotificationManager", loadPackageParam.classLoader);
			XposedBridge.log("NM clazz: " + clazz);
			Method method = XposedHelpers.findMethodExact(clazz, "notify", String.class, int.class, Notification.class);
			XposedBridge.log("NM.notify: " + method);
			XposedBridge.hookMethod(method, new XC_MethodHook() {
				@Override
				protected void beforeHookedMethod(MethodHookParam param) {
					NotificationManager nm = (NotificationManager)param.thisObject;
					String tag = (String)param.args[0];
					int id = (int)param.args[1];
					Notification n = (Notification)param.args[2];
					Log.d(TAG, "before apply " + nm + " " + tag + " " + id);
					applyLocally(nm, tag, id, n);
				}
			});
		} catch (XposedHelpers.ClassNotFoundError e) { XposedBridge.log(this.wechat + " NotificationManager hook failed"); }
		try {
			AtomicReference<Context> ref = new AtomicReference<>();
			XposedHelpers.findAndHookMethod(ContextWrapper.class, "attachBaseContext", Context.class, new XC_MethodHook() {
				@Override
				protected void afterHookedMethod(MethodHookParam param) {
					Context context = (Context)param.args[0];
					if (ref.compareAndSet(null, context)) {
						NevoDecoratorService.setAppContext(context);
						LocalDecorator wechat = MainHook.this.wechat.getLocalDecorator("com.tencent.mm"); // TODO
						wechat.onCreate(pref);
						if (!wechat.isDisabled() && (wechat instanceof HookSupport)) {
							((HookSupport)wechat).hook(loadPackageParam); // TODO 没法用onCreate(XSharedPreferences)实现动态配置，需要搞定
						}
					}
				}
			});
		} catch (XposedHelpers.ClassNotFoundError e) { XposedBridge.log(this.wechat + " ContextWrapper hook failed"); }
		/* inspect(loadPackageParam,
				"com.android.server.notification.NotificationManagerService",
				"getNotificationChannel",
				"deleteNotificationChannel",
				"deleteNotificationChannelGroup",
				"createNotificationChannels"); */
	}

	// TODO
	private void applyLocally(NotificationManager nm, String tag, int id, Notification n) {
		if (XposedHelpers.getAdditionalInstanceField(n, "pre-applied") != null) {
			Log.d(TAG, "skip " + n);
			return;
		}
		XposedHelpers.setAdditionalInstanceField(n, "pre-applied", true);
		LocalDecorator.setNM(nm);
		LocalDecorator wechat = this.wechat.getLocalDecorator("com.tencent.mm"); // TODO
		if (!wechat.isDisabled()) wechat.apply(nm, tag, id, n);
	}
}
