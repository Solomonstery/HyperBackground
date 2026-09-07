package com.ciallo.hyperbackground

import java.lang.reflect.Field
import java.lang.reflect.Method
import java.util.Collections
import java.util.WeakHashMap

/**
 * libxposed 102 的 Kotlin 化 hook 工具层。
 *
 * 取代 de.robv 风格的 XposedHelpers/XposedBridge：hook 点用尾 lambda 表达
 * before/after，反射工具以扩展函数形式提供。
 */
private val ADDITIONAL_FIELDS: MutableMap<Any, MutableMap<String, Any?>> =
    Collections.synchronizedMap(WeakHashMap())

fun log(message: String) {
    HookRuntime.log(message)
}

fun log(error: Throwable) {
    HookRuntime.log(error.toString(), error)
}

/** 按方法签名在类层级中查找并 hook。before/after 可选，尾 lambda 落在 after 上。 */
fun hookMethod(
    type: Class<*>,
    name: String,
    vararg parameterTypes: Class<*>,
    before: (HookRuntime.LegacyHookParam.() -> Unit)? = null,
    after: (HookRuntime.LegacyHookParam.() -> Unit)? = null,
) {
    HookRuntime.hook(findMethod(type, name, parameterTypes), wrap(before, after))
}

/** 按类名 + ClassLoader 查找并 hook，目标类不存在时抛出带原因的异常。 */
fun hookMethod(
    className: String,
    classLoader: ClassLoader,
    name: String,
    vararg parameterTypes: Class<*>,
    before: (HookRuntime.LegacyHookParam.() -> Unit)? = null,
    after: (HookRuntime.LegacyHookParam.() -> Unit)? = null,
) {
    try {
        hookMethod(
            Class.forName(className, false, classLoader),
            name,
            *parameterTypes,
            before = before,
            after = after,
        )
    } catch (error: ClassNotFoundException) {
        throw IllegalStateException(error)
    }
}

fun Any.callMethod(name: String, vararg args: Any?): Any? {
    val method = findCompatibleMethod(javaClass, name, args)
    return try {
        method.isAccessible = true
        method.invoke(this, *args)
    } catch (error: ReflectiveOperationException) {
        throw IllegalStateException(error)
    }
}

fun Any.getObjectField(name: String): Any? = try {
    findField(javaClass, name).get(this)
} catch (error: ReflectiveOperationException) {
    throw IllegalStateException(error)
}

fun Any.setAdditionalInstanceField(key: String, value: Any?) {
    synchronized(ADDITIONAL_FIELDS) {
        ADDITIONAL_FIELDS.getOrPut(this) { HashMap() }[key] = value
    }
}

fun Any.getAdditionalInstanceField(key: String): Any? = synchronized(ADDITIONAL_FIELDS) {
    ADDITIONAL_FIELDS[this]?.get(key)
}

fun Any.removeAdditionalInstanceField(key: String): Any? = synchronized(ADDITIONAL_FIELDS) {
    ADDITIONAL_FIELDS[this]?.remove(key)
}

private fun wrap(
    before: (HookRuntime.LegacyHookParam.() -> Unit)?,
    after: (HookRuntime.LegacyHookParam.() -> Unit)?,
): HookRuntime.LegacyMethodHook = object : HookRuntime.LegacyMethodHook() {
    override fun before(param: HookRuntime.LegacyHookParam) {
        before?.invoke(param)
    }

    override fun after(param: HookRuntime.LegacyHookParam) {
        after?.invoke(param)
    }
}

private fun findMethod(type: Class<*>, name: String, parameterTypes: Array<out Class<*>>): Method {
    var current: Class<*>? = type
    while (current != null) {
        try {
            return current.getDeclaredMethod(name, *parameterTypes).apply { isAccessible = true }
        } catch (_: NoSuchMethodException) {
        }
        current = current.superclass
    }
    throw IllegalStateException(NoSuchMethodException("${type.name}#$name"))
}

private fun findCompatibleMethod(type: Class<*>, name: String, args: Array<out Any?>): Method {
    var current: Class<*>? = type
    while (current != null) {
        for (method in current.declaredMethods) {
            if (method.name != name || method.parameterCount != args.size) continue
            val parameterTypes = method.parameterTypes
            var compatible = true
            for (i in args.indices) {
                if (args[i] != null && !boxed(parameterTypes[i]).isInstance(args[i])) {
                    compatible = false
                    break
                }
            }
            if (compatible) return method
        }
        current = current.superclass
    }
    throw IllegalStateException(NoSuchMethodException("${type.name}#$name"))
}

private fun boxed(type: Class<*>): Class<*> = when (type) {
    java.lang.Boolean.TYPE -> Boolean::class.java
    java.lang.Byte.TYPE -> Byte::class.java
    java.lang.Character.TYPE -> Char::class.java
    java.lang.Short.TYPE -> Short::class.java
    Integer.TYPE -> Int::class.java
    java.lang.Long.TYPE -> Long::class.java
    java.lang.Float.TYPE -> Float::class.java
    java.lang.Double.TYPE -> Double::class.java
    else -> type
}

internal fun findField(type: Class<*>, name: String): Field {
    var current: Class<*>? = type
    while (current != null) {
        try {
            return current.getDeclaredField(name).apply { isAccessible = true }
        } catch (_: NoSuchFieldException) {
        }
        current = current.superclass
    }
    throw NoSuchFieldException(name)
}
