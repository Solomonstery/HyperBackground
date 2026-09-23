package com.ciallo.hyperbackground.util

import com.ciallo.hyperbackground.HookRuntime
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.util.Collections
import java.util.WeakHashMap
import java.util.concurrent.ConcurrentHashMap

/**
 * libxposed 102 的 Kotlin 化 hook 工具层。
 *
 * 取代 de.robv 风格的 XposedHelpers/XposedBridge：hook 点用尾 lambda 表达
 * before/after，反射工具以扩展函数形式提供。
 */
private val ADDITIONAL_FIELDS: MutableMap<Any, MutableMap<String, Any?>> =
    Collections.synchronizedMap(WeakHashMap())

private data class MethodKey(val type: Class<*>, val name: String, val parameters: List<Class<*>?>)
private data class FieldKey(val type: Class<*>, val name: String)
private data class Lookup<T>(val member: T?)
private val exactMethods = ConcurrentHashMap<MethodKey, Lookup<Method>>()
private val compatibleMethods = ConcurrentHashMap<MethodKey, Lookup<Method>>()
private val fields = ConcurrentHashMap<FieldKey, Lookup<Field>>()

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

/** 直接按已解析出的 [Method] 挂载：用于 Resources.getDrawable 这类同名同参数量、无法按签名唯一确定的重载。 */
fun hookMethod(
    method: Method,
    before: (HookRuntime.LegacyHookParam.() -> Unit)? = null,
    after: (HookRuntime.LegacyHookParam.() -> Unit)? = null,
) {
    HookRuntime.hook(method, wrap(before, after))
}

fun Any.callMethod(name: String, vararg args: Any?): Any? {
    val method = findCompatibleMethod(javaClass, name, args)
    return try {
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

/** 读取不可见字段（如厂商 Header 的 id/groupId），字段不存在或类型不符时返回 null 而不抛异常。 */
fun Any.getLongFieldOrNull(name: String): Long? = try {
    findField(javaClass, name).getLong(this)
} catch (_: Throwable) {
    null
}

/** 读取不可见 int 字段，失败返回 null。 */
fun Any.getIntFieldOrNull(name: String): Int? = try {
    findField(javaClass, name).getInt(this)
} catch (_: Throwable) {
    null
}

fun Any.setLongField(name: String, value: Long) {
    findField(javaClass, name).setLong(this, value)
}

fun Any.setIntField(name: String, value: Int) {
    findField(javaClass, name).setInt(this, value)
}

fun Any.setObjectField(name: String, value: Any?) {
    findField(javaClass, name).set(this, value)
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
    val key = MethodKey(type, name, parameterTypes.toList())
    return exactMethods.getOrPut(key) { Lookup(searchMethod(type, name, parameterTypes)) }.member
        ?: throw IllegalStateException(NoSuchMethodException("${type.name}#$name"))
}

private fun searchMethod(type: Class<*>, name: String, parameterTypes: Array<out Class<*>>): Method? {
    var current: Class<*>? = type
    while (current != null) {
        try {
            return current.getDeclaredMethod(name, *parameterTypes).apply { isAccessible = true }
        } catch (_: NoSuchMethodException) {
        }
        current = current.superclass
    }
    return null
}

private fun findCompatibleMethod(type: Class<*>, name: String, args: Array<out Any?>): Method {
    val key = MethodKey(type, name, args.map { it?.javaClass })
    return compatibleMethods.getOrPut(key) { Lookup(searchCompatibleMethod(type, name, args)) }.member
        ?: throw IllegalStateException(NoSuchMethodException("${type.name}#$name"))
}

private fun searchCompatibleMethod(type: Class<*>, name: String, args: Array<out Any?>): Method? {
    var current: Class<*>? = type
    while (current != null) {
        for (method in current.declaredMethods) {
            if (method.name != name || method.parameterCount != args.size) continue
            val parameterTypes = method.parameterTypes
            var compatible = true
            for (i in args.indices) {
                if (if (args[i] == null) parameterTypes[i].isPrimitive
                    else !boxed(parameterTypes[i]).isInstance(args[i])) {
                    compatible = false
                    break
                }
            }
            if (compatible) return method.apply { isAccessible = true }
        }
        current = current.superclass
    }
    return null
}

private fun boxed(type: Class<*>): Class<*> = when (type) {
    java.lang.Boolean.TYPE -> Boolean::class.javaObjectType
    java.lang.Byte.TYPE -> Byte::class.javaObjectType
    Character.TYPE -> Char::class.javaObjectType
    java.lang.Short.TYPE -> Short::class.javaObjectType
    Integer.TYPE -> Int::class.javaObjectType
    java.lang.Long.TYPE -> Long::class.javaObjectType
    java.lang.Float.TYPE -> Float::class.javaObjectType
    java.lang.Double.TYPE -> Double::class.javaObjectType
    else -> type
}

internal fun findField(type: Class<*>, name: String): Field {
    return fields.getOrPut(FieldKey(type, name)) { Lookup(searchField(type, name)) }.member
        ?: throw NoSuchFieldException(name)
}

private fun searchField(type: Class<*>, name: String): Field? {
    var current: Class<*>? = type
    while (current != null) {
        try {
            return current.getDeclaredField(name).apply { isAccessible = true }
        } catch (_: NoSuchFieldException) {
        }
        current = current.superclass
    }
    return null
}
