package com.nevolution.xposed

import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import java.lang.reflect.Method

// ========== 反射调用封装 ==========

/** 通过反射调用实例方法 */
fun Any.callMethod(methodName: String, vararg args: Any): Any? =
    XposedHelpers.callMethod(this, methodName, *args)

fun Any.callMethod(methodName: String, parameterTypes: Array<Class<*>>, vararg args: Any): Any? =
    XposedHelpers.callMethod(this, methodName, parameterTypes, *args)

/** 通过反射调用静态方法 */
fun Class<*>.callStaticMethod(methodName: String, vararg args: Any): Any? =
    XposedHelpers.callStaticMethod(this, methodName, *args)

fun Class<*>.callStaticMethod(
    methodName: String,
    parameterTypes: Array<Class<*>>,
    vararg args: Any
): Any? = XposedHelpers.callStaticMethod(this, methodName, parameterTypes, *args)

// ========== Hook DSL 类型别名 ==========

typealias HookAction = XC_MethodHook.MethodHookParam.() -> Unit
typealias ReplaceAction = XC_MethodHook.MethodHookParam.() -> Any?
typealias HookCallback = HookContext.() -> Unit

// ========== 便捷 Hook 函数 ==========

/** 查找并 Hook 指定类的指定方法 */
fun Class<*>.hookMethod(methodName: String, vararg parameterTypes: Class<*>, callback: HookCallback) =
    XposedHelpers.findAndHookMethod(this, methodName, *parameterTypes, MethodHook(callback))

/** 查找并 Hook 指定类的构造函数 */
fun Class<*>.hookConstructor(vararg parameterTypes: Class<*>, callback: HookCallback) =
    XposedHelpers.findAndHookConstructor(this, *parameterTypes, MethodHook(callback))

/** Hook 指定类的所有构造函数 */
fun Class<*>.hookAllConstructors(callback: HookCallback) =
    XposedBridge.hookAllConstructors(this, MethodHook(callback))

/** 通过类名查找并 Hook 方法 */
fun hookMethod(className: String, classLoader: ClassLoader, methodName: String, vararg parameterTypes: Class<*>, callback: HookCallback) =
    XposedHelpers.findAndHookMethod(className, classLoader, methodName, *parameterTypes, MethodHook(callback))

fun hookConstructor(className: String, classLoader: ClassLoader, methodName: String, vararg parameterTypes: Class<*>, callback: HookCallback) =
    XposedHelpers.findAndHookConstructor(className, classLoader, methodName, *parameterTypes, MethodHook(callback))

/** Hook 指定 Method 对象 */
fun Method.hook(callback: HookCallback) = XposedBridge.hookMethod(this, MethodHook(callback))

/** Hook 指定类中所有同名方法（含重载） */
fun Class<*>.hookAllMethods(methodName: String, callback: HookCallback) =
    XposedBridge.hookAllMethods(this, methodName, MethodHook(callback))

// ========== 自定义 Hook 回调封装 ==========

/**
 * MethodHook 封装了 doBefore / doAfter / replace 三路回调。
 *
 * 用法示例：
 * ```
 * clazz.hookMethod("foo", String::class.java) {
 *     doBefore { Log.d("TAG", "before: ${args[0]}") }
 *     doAfter  { Log.d("TAG", "after, result=$result") }
 * }
 * ```
 */
class MethodHook(callback: HookCallback) : XC_MethodHook() {
    private val context = HookContext().apply(callback)

    override fun beforeHookedMethod(param: MethodHookParam) {
        super.beforeHookedMethod(param)

        // 若定义了 replace，则直接替换返回值并跳过原方法
        context.replaceAction?.let {
            try {
                param.result = it.invoke(param)
            } catch (t: Throwable) {
                param.throwable = t
            }
            return
        }

        context.beforeAction?.invoke(param)
    }

    override fun afterHookedMethod(param: MethodHookParam) {
        super.afterHookedMethod(param)
        context.afterAction?.invoke(param)
    }

}

/** 承载 doBefore / doAfter / replace 三路回调的上下文容器 */
class HookContext() {
    internal var beforeAction: HookAction? = null
        private set

    internal var afterAction: HookAction? = null
        private set

    internal var replaceAction: ReplaceAction? = null
        private set

    fun doBefore(action: HookAction) {
        this.beforeAction = action
    }

    fun doAfter(action: HookAction) {
        this.afterAction = action
    }

    fun replace(action: ReplaceAction) {
        this.replaceAction = action
    }
}

// ========== 反射创建实例 ==========

fun Class<*>.newInstance(vararg args: Any): Any = XposedHelpers.newInstance(this, *args)

fun Class<*>.newInstance(parameterTypes: Array<Class<*>>, vararg args: Any): Any =
    XposedHelpers.newInstance(this, parameterTypes, *args)

// ========== ClassLoader 查找类 ==========

fun ClassLoader.findClass(className: String): Class<*> = XposedHelpers.findClass(className, this)
fun ClassLoader.findClassIfExists(className: String): Class<*>? = XposedHelpers.findClassIfExists(className, this)

// ========== 字段读写（带运算符重载） ==========

/** 运算符重载：通过反射读字段，如 `val x: String = obj["fieldName"]` */
inline operator fun <reified T> Any.get(name: String): T = getField(name, T::class.java)

/** 运算符重载：通过反射写字段，如 `obj["fieldName"] = value` */
inline operator fun <reified T> Any.set(name: String, value: T?) = setField(name, value, T::class.java)

/**
 * 通过反射读取任意类型的字段（支持基本类型自动拆箱）。
 * 如果 this 是 Class<*> 则读静态字段，否则读实例字段。
 */
fun <T> Any.getField(name: String, fieldClazz: Class<T>): T {
    val obj = if (this is Class<*>) null else this
    val thisClass = if (this is Class<*>) this else this.javaClass
    val field = findField(thisClass, name)

    val value = when (fieldClazz) {
        Boolean::class.java -> field.getBoolean(obj)
        Byte::class.java -> field.getByte(obj)
        Char::class.java -> field.getChar(obj)
        Double::class.java -> field.getDouble(obj)
        Float::class.java -> field.getFloat(obj)
        Int::class.java -> field.getInt(obj)
        Long::class.java -> field.getLong(obj)
        Short::class.java -> field.getShort(obj)
        else -> field.get(obj)
    }
    @Suppress("UNCHECKED_CAST")
    return value as T
}

/**
 * 通过反射写入任意类型的字段（支持基本类型自动装箱）。
 * 如果 this 是 Class<*> 则写静态字段，否则写实例字段。
 */
fun <T> Any.setField(name: String, value: T?, fieldClass: Class<T>) {
    val obj = if (this is Class<*>) null else this
    val thisClass = if (this is Class<*>) this else this.javaClass

    val field = findField(thisClass, name)

    when (fieldClass) {
        Boolean::class.java -> field.setBoolean(obj, value as Boolean)
        Byte::class.java -> field.setByte(obj, value as Byte)
        Char::class.java -> field.setChar(obj, value as Char)
        Double::class.java -> field.setDouble(obj, value as Double)
        Float::class.java -> field.setFloat(obj, value as Float)
        Int::class.java -> field.setInt(obj, value as Int)
        Long::class.java -> field.setLong(obj, value as Long)
        Short::class.java -> field.setShort(obj, value as Short)
        else -> @Suppress("UNCHECKED_CAST") field.set(obj, value as T)
    }
}

/** 查找字段（递归遍历父类） */
fun findField(clazz: Class<*>, fieldName: String) = XposedHelpers.findField(clazz, fieldName)

// ========== Xposed 附加实例字段 ==========

/** 读取 Xposed 附加实例字段 */
@Suppress("UNCHECKED_CAST")
fun <T> Any.getAdditional(key: String): T? = XposedHelpers.getAdditionalInstanceField(this, key) as T?
/** 写入 Xposed 附加实例字段 */
@Suppress("UNCHECKED_CAST")
fun <T> Any.setAdditional(key: String, value: T?): T? = XposedHelpers.setAdditionalInstanceField(this, key, value) as T?
