package com.example.netcrafttoolkit;

import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * NetCraft 反射兼容层。
 *
 * 不直接 import NetCraft 的 Java 类，
 * 避免 NetCraft 不存在时导致本 Mod 无法启动。
 */
public final class NetCraftReflection {

    private static final Logger LOGGER = LogUtils.getLogger();

    private static final Map<String, Class<?>> CLASS_CACHE =
            new ConcurrentHashMap<>();

    private static final Map<String, Method> METHOD_CACHE =
            new ConcurrentHashMap<>();

    private static final Map<String, Field> FIELD_CACHE =
            new ConcurrentHashMap<>();

    private static final Map<String, Boolean> FAILED =
            new ConcurrentHashMap<>();

    private NetCraftReflection() {
    }

    /**
     * NetCraft PlayerStatsCapability。
     */
    private static final String PLAYER_STATS_CAPABILITY =
            "com.jiufeng.netcraft.capability.PlayerStatsCapability";

    /**
     * 查找类。
     */
    public static Class<?> findClass(
            String className
    ) {

        if (className == null
                || className.isBlank()) {

            return null;
        }

        Class<?> cached =
                CLASS_CACHE.get(className);

        if (cached != null) {
            return cached;
        }

        if (FAILED.containsKey(
                "class:" + className
        )) {
            return null;
        }

        try {

            Class<?> clazz =
                    Class.forName(
                            className,
                            false,
                            NetCraftReflection.class
                                    .getClassLoader()
                    );

            CLASS_CACHE.put(
                    className,
                    clazz
            );

            return clazz;

        } catch (Throwable e) {

            FAILED.put(
                    "class:" + className,
                    true
            );

            return null;
        }
    }

    /**
     * 在类及其父类中寻找方法。
     */
    public static Method findMethod(
            Class<?> clazz,
            String name,
            int parameterCount
    ) {

        if (clazz == null
                || name == null) {

            return null;
        }

        String key =
                "method:"
                        + clazz.getName()
                        + ":"
                        + name
                        + ":"
                        + parameterCount;

        Method cached =
                METHOD_CACHE.get(key);

        if (cached != null) {
            return cached;
        }

        if (FAILED.containsKey(key)) {
            return null;
        }

        Class<?> current = clazz;

        while (current != null) {

            try {

                for (Method method :
                        current.getDeclaredMethods()) {

                    if (!method.getName()
                            .equals(name)) {

                        continue;
                    }

                    if (method.getParameterCount()
                            != parameterCount) {

                        continue;
                    }

                    try {
                        method.setAccessible(true);
                    } catch (Throwable ignored) {
                    }

                    METHOD_CACHE.put(
                            key,
                            method
                    );

                    return method;
                }

            } catch (Throwable ignored) {
            }

            current =
                    current.getSuperclass();
        }

        FAILED.put(
                key,
                true
        );

        return null;
    }

    /**
     * 在类及其父类中寻找字段。
     */
    public static Field findField(
            Class<?> clazz,
            String name
    ) {

        if (clazz == null
                || name == null) {

            return null;
        }

        String key =
                "field:"
                        + clazz.getName()
                        + ":"
                        + name;

        Field cached =
                FIELD_CACHE.get(key);

        if (cached != null) {
            return cached;
        }

        if (FAILED.containsKey(key)) {
            return null;
        }

        Class<?> current = clazz;

        while (current != null) {

            try {

                Field field =
                        current.getDeclaredField(
                                name
                        );

                try {
                    field.setAccessible(true);
                } catch (Throwable ignored) {
                }

                FIELD_CACHE.put(
                        key,
                        field
                );

                return field;

            } catch (Throwable ignored) {
            }

            current =
                    current.getSuperclass();
        }

        FAILED.put(
                key,
                true
        );

        return null;
    }

    /**
     * 无参数方法调用。
     */
    public static Object invokeNoArgs(
            Object target,
            String methodName
    ) {

        if (target == null
                || methodName == null) {

            return null;
        }

        Method method =
                findMethod(
                        target.getClass(),
                        methodName,
                        0
                );

        if (method == null) {
            return null;
        }

        try {

            return method.invoke(
                    target
            );

        } catch (Throwable e) {

            warnOnce(
                    "invoke:"
                            + target.getClass().getName()
                            + ":"
                            + methodName,
                    "调用 NetCraft 方法失败: "
                            + methodName
            );

            return null;
        }
    }

    /**
     * 指定参数调用。
     */
    public static Object invoke(
            Object target,
            String methodName,
            Object... args
    ) {

        if (target == null
                || methodName == null) {

            return null;
        }

        int count =
                args == null
                        ? 0
                        : args.length;

        Method method =
                findMethod(
                        target.getClass(),
                        methodName,
                        count
                );

        if (method == null) {
            return null;
        }

        try {

            return method.invoke(
                    target,
                    args
            );

        } catch (Throwable e) {

            warnOnce(
                    "invoke:"
                            + target.getClass().getName()
                            + ":"
                            + methodName
                            + ":"
                            + count,
                    "调用 NetCraft 方法失败: "
                            + methodName
            );

            return null;
        }
    }

    /**
     * 获取字段。
     */
    public static Object getField(
            Object target,
            String fieldName
    ) {

        if (target == null
                || fieldName == null) {

            return null;
        }

        Field field =
                findField(
                        target.getClass(),
                        fieldName
                );

        if (field == null) {
            return null;
        }

        try {

            return field.get(target);

        } catch (Throwable e) {

            warnOnce(
                    "field:"
                            + target.getClass().getName()
                            + ":"
                            + fieldName,
                    "读取 NetCraft 字段失败: "
                            + fieldName
            );

            return null;
        }
    }

    /**
     * 设置字段。
     */
    public static boolean setField(
            Object target,
            String fieldName,
            Object value
    ) {

        if (target == null
                || fieldName == null) {

            return false;
        }

        Field field =
                findField(
                        target.getClass(),
                        fieldName
                );

        if (field == null) {
            return false;
        }

        try {

            field.set(
                    target,
                    value
            );

            return true;

        } catch (Throwable e) {

            warnOnce(
                    "setfield:"
                            + target.getClass().getName()
                            + ":"
                            + fieldName,
                    "设置 NetCraft 字段失败: "
                            + fieldName
            );

            return false;
        }
    }

    /**
     * 尝试寻找 PlayerStatsCapability。
     *
     * 这里不直接依赖 NetCraft，
     * 因此 NetCraft 没安装时也不会崩。
     */
    public static Class<?> findPlayerStatsCapability() {

        return findClass(
                PLAYER_STATS_CAPABILITY
        );
    }

    /**
     * 尝试通过实体的 getCapability 获取能力对象。
     *
     * Forge 的 LivingEntity 实际继承自 ICapabilityProvider，
     * getCapability 通常有两个参数：
     *
     * Capability
     * Direction
     *
     * 第二个参数允许传 null。
     */
    public static Object getCapability(
            Object entity,
            Object capability
    ) {

        if (entity == null
                || capability == null) {

            return null;
        }

        Method method =
                findMethod(
                        entity.getClass(),
                        "getCapability",
                        2
                );

        if (method == null) {
            return null;
        }

        try {

            Object result =
                    method.invoke(
                            entity,
                            capability,
                            null
                    );

            /*
             * Forge Capability 通常返回 LazyOptional。
             *
             * 不直接 import Forge Capability API，
             * 用反射兼容。
             */
            if (result == null) {
                return null;
            }

            Object present =
                    invokeNoArgs(
                            result,
                            "isPresent"
                    );

            if (present instanceof Boolean
                    && (Boolean) present) {

                return invokeNoArgs(
                        result,
                        "orElseThrow"
                );
            }

            return null;

        } catch (Throwable e) {

            warnOnce(
                    "getcapability:"
                            + entity.getClass().getName(),
                    "读取 NetCraft Capability 失败"
            );

            return null;
        }
    }

    /**
     * 尝试从实体寻找 PlayerStats。
     *
     * 这是兼容入口。
     *
     * 如果未来根据你的 NetCraft 实际源码确认
     * Capability 获取方式不同，只需要修改这里。
     */
    public static Object findPlayerStats(
            Object entity
    ) {

        if (entity == null) {
            return null;
        }

        Class<?> capabilityClass =
                findPlayerStatsCapability();

        if (capabilityClass == null) {
            return null;
        }

        /*
         * 先寻找可能的静态 Capability 字段。
         */
        Object capability =
                findStaticCapabilityField(
                        capabilityClass
                );

        if (capability == null) {
            return null;
        }

        return getCapability(
                entity,
                capability
        );
    }

    /**
     * 寻找 Capability 类相关的静态字段。
     */
    private static Object findStaticCapabilityField(
            Class<?> capabilityClass
    ) {

        Class<?> current =
                capabilityClass;

        while (current != null) {

            try {

                for (Field field :
                        current.getDeclaredFields()) {

                    int modifiers =
                            field.getModifiers();

                    if (!Modifier.isStatic(
                            modifiers
                    )) {
                        continue;
                    }

                    /*
                     * Capability 字段通常是 Capability<T>。
                     */
                    if (!field.getType()
                            .getName()
                            .contains("Capability")) {

                        continue;
                    }

                    try {
                        field.setAccessible(true);
                    } catch (Throwable ignored) {
                    }

                    Object value =
                            field.get(null);

                    if (value != null) {
                        return value;
                    }
                }

            } catch (Throwable ignored) {
            }

            current =
                    current.getSuperclass();
        }

        return null;
    }

    /**
     * 获取物理防御。
     */
    public static Double getPhysicalDefense(
            Object stats
    ) {

        if (stats == null) {
            return null;
        }

        Object value =
                invokeNoArgs(
                        stats,
                        "getPhysicalDefense"
                );

        return toDouble(value);
    }

    /**
     * 设置物理防御。
     */
    public static boolean setPhysicalDefense(
            Object stats,
            double value
    ) {

        if (stats == null) {
            return false;
        }

        Method method =
                findMethod(
                        stats.getClass(),
                        "setPhysicalDefense",
                        1
                );

        if (method == null) {
            return false;
        }

        try {

            Class<?> type =
                    method.getParameterTypes()[0];

            Object converted =
                    convertNumber(
                            value,
                            type
                    );

            method.invoke(
                    stats,
                    converted
            );

            return true;

        } catch (Throwable e) {

            warnOnce(
                    "setphysicaldefense:"
                            + stats.getClass().getName(),
                    "设置 NetCraft 物理防御失败"
            );

            return false;
        }
    }

    /**
     * 获取附魔防御值。
     */
    public static Double getEnchantmentDefenseValue(
            Object stats,
            int level
    ) {

        if (stats == null) {
            return null;
        }

        Method method =
                findMethod(
                        stats.getClass(),
                        "getEnchantmentDefenseValue",
                        1
                );

        if (method == null) {
            return null;
        }

        try {

            Object value =
                    method.invoke(
                            stats,
                            level
                    );

            return toDouble(value);

        } catch (Throwable e) {

            warnOnce(
                    "enchantmentdefense:"
                            + stats.getClass().getName(),
                    "读取 NetCraft 附魔防御值失败"
            );

            return null;
        }
    }

    /**
     * 尝试获取 Boss HatredManager。
     */
    public static Object getHatredManager(
            Object boss
    ) {

        if (boss == null) {
            return null;
        }

        return invokeNoArgs(
                boss,
                "getHatredManager"
        );
    }

    /**
     * 获取 HatredManager 中可能的 Map 数据。
     *
     * 因为不同 NetCraft 版本的具体方法可能不同，
     * 这里不会假设固定方法名。
     */
    public static Map<?, ?> findFirstMap(
            Object object
    ) {

        if (object == null) {
            return null;
        }

        Class<?> current =
                object.getClass();

        while (current != null) {

            try {

                for (Method method :
                        current.getDeclaredMethods()) {

                    if (method.getParameterCount()
                            != 0) {
                        continue;
                    }

                    if (!Map.class
                            .isAssignableFrom(
                                    method.getReturnType()
                            )) {

                        continue;
                    }

                    try {
                        method.setAccessible(true);
                    } catch (Throwable ignored) {
                    }

                    Object result =
                            method.invoke(
                                    object
                            );

                    if (result instanceof Map<?, ?> map) {
                        return map;
                    }

                }

            } catch (Throwable ignored) {
            }

            current =
                    current.getSuperclass();
        }

        return null;
    }

    /**
     * 数字转换。
     */
    public static Double toDouble(
            Object value
    ) {

        if (!(value instanceof Number number)) {
            return null;
        }

        double result =
                number.doubleValue();

        if (Double.isNaN(result)
                || Double.isInfinite(result)) {

            return null;
        }

        return result;
    }

    /**
     * 数字类型转换。
     */
    private static Object convertNumber(
            double value,
            Class<?> type
    ) {

        if (type == double.class
                || type == Double.class) {

            return value;
        }

        if (type == float.class
                || type == Float.class) {

            return (float) value;
        }

        if (type == long.class
                || type == Long.class) {

            return (long) value;
        }

        if (type == int.class
                || type == Integer.class) {

            return (int) value;
        }

        if (type == short.class
                || type == Short.class) {

            return (short) value;
        }

        if (type == byte.class
                || type == Byte.class) {

            return (byte) value;
        }

        return value;
    }

    /**
     * 一次性警告。
     *
     * 避免 NetCraft 某个版本没有某方法时，
     * 每 tick 刷屏。
     */
    private static void warnOnce(
            String key,
            String message
    ) {

        if (FAILED.putIfAbsent(
                "warn:" + key,
                true
        ) == null) {

            LOGGER.warn(
                    "[NetCraftToolkit] {}",
                    message
            );
        }
    }

    /**
     * 清空缓存。
     */
    public static void clearCaches() {

        CLASS_CACHE.clear();
        METHOD_CACHE.clear();
        FIELD_CACHE.clear();
        FAILED.clear();
    }
}
