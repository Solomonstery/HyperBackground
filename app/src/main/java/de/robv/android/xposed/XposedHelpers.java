package de.robv.android.xposed;

import com.ciallo.hyperbackground.HookRuntime;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;

public final class XposedHelpers {
    private static final Map<Object, Map<String, Object>> ADDITIONAL_FIELDS =
            Collections.synchronizedMap(new WeakHashMap<>());

    private XposedHelpers() {}

    public static void findAndHookMethod(Class<?> type, String name, Object... parameterTypesAndCallback) {
        hook(type, name, parameterTypesAndCallback);
    }

    public static void findAndHookMethod(String className, ClassLoader loader, String name,
                                         Object... parameterTypesAndCallback) {
        try {
            hook(Class.forName(className, false, loader), name, parameterTypesAndCallback);
        } catch (ClassNotFoundException error) {
            throw new IllegalStateException(error);
        }
    }

    private static void hook(Class<?> type, String name, Object[] values) {
        if (values.length == 0 || !(values[values.length - 1] instanceof XC_MethodHook)) {
            throw new IllegalArgumentException("Missing hook callback");
        }
        Class<?>[] parameterTypes = new Class<?>[values.length - 1];
        for (int i = 0; i < parameterTypes.length; i++) parameterTypes[i] = (Class<?>) values[i];
        Method method = findMethod(type, name, parameterTypes);
        HookRuntime.hook(method, (XC_MethodHook) values[values.length - 1]);
    }

    public static Object callMethod(Object instance, String name, Object... args) {
        if (instance == null) return null;
        Method method = findCompatibleMethod(instance.getClass(), name, args);
        try {
            method.setAccessible(true);
            return method.invoke(instance, args);
        } catch (ReflectiveOperationException error) {
            throw new IllegalStateException(error);
        }
    }

    public static Object getObjectField(Object instance, String name) {
        try {
            Field field = findField(instance.getClass(), name);
            field.setAccessible(true);
            return field.get(instance);
        } catch (ReflectiveOperationException error) {
            throw new IllegalStateException(error);
        }
    }

    public static void setAdditionalInstanceField(Object instance, String key, Object value) {
        synchronized (ADDITIONAL_FIELDS) {
            ADDITIONAL_FIELDS.computeIfAbsent(instance, ignored -> new java.util.HashMap<>()).put(key, value);
        }
    }

    public static Object getAdditionalInstanceField(Object instance, String key) {
        synchronized (ADDITIONAL_FIELDS) {
            Map<String, Object> fields = ADDITIONAL_FIELDS.get(instance);
            return fields == null ? null : fields.get(key);
        }
    }

    public static Object removeAdditionalInstanceField(Object instance, String key) {
        synchronized (ADDITIONAL_FIELDS) {
            Map<String, Object> fields = ADDITIONAL_FIELDS.get(instance);
            return fields == null ? null : fields.remove(key);
        }
    }

    private static Method findMethod(Class<?> type, String name, Class<?>[] parameterTypes) {
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            try {
                Method method = current.getDeclaredMethod(name, parameterTypes);
                method.setAccessible(true);
                return method;
            } catch (NoSuchMethodException ignored) {}
        }
        throw new IllegalStateException(new NoSuchMethodException(type.getName() + '#' + name));
    }

    private static Method findCompatibleMethod(Class<?> type, String name, Object[] args) {
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            for (Method method : current.getDeclaredMethods()) {
                if (!method.getName().equals(name) || method.getParameterCount() != args.length) continue;
                Class<?>[] parameterTypes = method.getParameterTypes();
                boolean compatible = true;
                for (int i = 0; i < args.length; i++) {
                    if (args[i] != null && !boxed(parameterTypes[i]).isInstance(args[i])) {
                        compatible = false;
                        break;
                    }
                }
                if (compatible) return method;
            }
        }
        throw new IllegalStateException(new NoSuchMethodException(type.getName() + '#' + name));
    }

    private static Class<?> boxed(Class<?> type) {
        if (!type.isPrimitive()) return type;
        if (type == boolean.class) return Boolean.class;
        if (type == byte.class) return Byte.class;
        if (type == char.class) return Character.class;
        if (type == short.class) return Short.class;
        if (type == int.class) return Integer.class;
        if (type == long.class) return Long.class;
        if (type == float.class) return Float.class;
        if (type == double.class) return Double.class;
        return Void.class;
    }

    private static Field findField(Class<?> type, String name) throws NoSuchFieldException {
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            try { return current.getDeclaredField(name); } catch (NoSuchFieldException ignored) {}
        }
        throw new NoSuchFieldException(name);
    }
}
