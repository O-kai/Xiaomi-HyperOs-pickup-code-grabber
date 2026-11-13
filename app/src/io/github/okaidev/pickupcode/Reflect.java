package io.github.okaidev.pickupcode;

import java.lang.reflect.Field;

/** Small reflection operations used by the supported data callbacks. No framework helper dependency. */
final class Reflect {
    static Class<?> findClass(String name, ClassLoader loader) throws ClassNotFoundException {
        return Class.forName(name, false, loader);
    }
    static Class<?> findClassIfExists(String name, ClassLoader loader) {
        try { return findClass(name, loader); } catch (ClassNotFoundException e) { return null; }
    }
    static Object getObjectField(Object receiver, String name) throws ReflectiveOperationException {
        for (Class<?> type = receiver.getClass(); type != null; type = type.getSuperclass()) {
            try {
                Field field = type.getDeclaredField(name);
                field.setAccessible(true);
                return field.get(receiver);
            } catch (NoSuchFieldException ignored) { }
        }
        throw new NoSuchFieldException(name);
    }
}
