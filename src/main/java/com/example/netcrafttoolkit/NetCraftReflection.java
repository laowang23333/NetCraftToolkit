package com.example.netcrafttoolkit;

import net.minecraft.world.entity.Entity;
import org.slf4j.Logger;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * NetCraft 1.4.18 反射适配层。
 *
 * 作用：
 *
 * 1. 不直接 import NetCraft 类。
 * 2. 通过反射调用 NetCraft API。
 * 3. NetCraft 不存在时不会导致本 Mod 启动崩溃。
 * 4. 为 PlayerStatsCapability / PlayerStats 提供统一访问。
 * 5. 为 BossBase / HatredManager 等 NetCraft 内部对象预留访问接口。
 *
 * 当前针对：
 *
 * NetCraft 1.4.18
 *
 * 主要真实类：
 *
 * com.jiufeng.netcraft.capability.PlayerStatsCapability
 *
 * PlayerStats Capability：
 *
 * getMeleeDamage()
 * setMeleeDamage(int)
 * getRangedDamage()
 * setRangedDamage(int)
 * getMagicDamage()
 * setMagicDamage(int)
 * getPhysicalDamage()
 * setPhysicalDamage(int)
 * getMeleeDefense()
 * setMeleeDefense(int)
 * getRangedDefense()
 * setRangedDefense(int)
 * getPhysicalDefense()
 * setPhysicalDefense(float)
 * getMagicDefense()
 * setMagicDefense(float)
 * getCriticalChance()
 * setCriticalChance(float)
 * getCriticalMultiplier()
 * setCriticalMultiplier(float)
 * getDodgeChance()
 * setDodgeChance(float)
 */
public final class NetCraftReflection {

    /**
     * NetCraft PlayerStatsCapability。
     */
    private static final String PLAYER_STATS_CAPABILITY_CLASS =
            "com.jiufeng.netcraft.capability.PlayerStatsCapability";

    /**
     * NetCraft PlayerStats 可能的类名。
     *
     * 不强制依赖这个类名。
     *
     * 实际上 Capability#getCapability() 返回的对象
     * 才是我们最终操作的对象。
     */
    private static final String[] PLAYER_STATS_CLASS_NAMES = {
            "com.jiufeng.netcraft.capability.PlayerStats",
            "com.jiufeng.netcraft.capability.PlayerStatsCapability$PlayerStats"
    };

    /**
     * 反射缓存。
     */
    private static final Map<String, Class<?>> CLASS_CACHE =
            new ConcurrentHashMap<>();

    private static final Map<String, Method> METHOD_CACHE =
            new ConcurrentHashMap<>();

    private static final Map<String, Field> FIELD_CACHE =
            new ConcurrentHashMap<>();

    /**
     * 防止重复打印 NetCraft 不存在的错误。
     */
    private static final Map<String, Boolean> WARNED =
            new ConcurrentHashMap<>();

    private NetCraftReflection() {
    }

    // =========================================================
    // 基础反射
    // =========================================================

    /**
     * 查找类。
     */
    public static Class<?> findClass(String className) {

        if (className == null || className.isBlank()) {
            return null;
        }

        Class<?> cached = CLASS_CACHE.get(className);

        if (cached != null) {
            return cached;
        }

        try {

            Class<?> clazz = Class.forName(
                    className,
                    false,
                    NetCraftReflection.class.getClassLoader()
            );

            CLASS_CACHE.put(className, clazz);

            return clazz;

        } catch (Throwable ignored) {

            /*
             * 有些 Mod 类由 Minecraft/Forge 自己的 ClassLoader 加载。
             *
             * 再尝试当前线程 ClassLoader。
             */
            try {

                ClassLoader loader =
                        Thread.currentThread().getContextClassLoader();

                if (loader != null) {

                    Class<?> clazz = Class.forName(
                            className,
                            false,
                            loader
                    );

                    CLASS_CACHE.put(className, clazz);

                    return clazz;
                }

            } catch (Throwable ignoredAgain) {
                // ignore
            }

            return null;
        }
    }

    /**
     * 查找方法。
     *
     * 不要求参数类型完全匹配。
     *
     * 只要：
     *
     * 方法名一致
     * 参数数量一致
     *
     * 就会进一步检查参数兼容性。
     */
    public static Method findMethod(
            Class<?> clazz,
            String methodName,
            int parameterCount
    ) {

        if (clazz == null ||
                methodName == null ||
                methodName.isBlank()) {

            return null;
        }

        String key =
                clazz.getName()
                        + "#"
                        + methodName
                        + "#"
                        + parameterCount;

        Method cached = METHOD_CACHE.get(key);

        if (cached != null) {
            return cached;
        }

        Class<?> current = clazz;

        while (current != null) {

            for (Method method : current.getDeclaredMethods()) {

                if (!method.getName().equals(methodName)) {
                    continue;
                }

                if (method.getParameterCount() != parameterCount) {
                    continue;
                }

                try {
                    method.setAccessible(true);
                } catch (Throwable ignored) {
                }

                METHOD_CACHE.put(key, method);

                return method;
            }

            current = current.getSuperclass();
        }

        /*
         * 再检查 public 方法。
         */
        try {

            for (Method method : clazz.getMethods()) {

                if (!method.getName().equals(methodName)) {
                    continue;
                }

                if (method.getParameterCount() != parameterCount) {
                    continue;
                }

                try {
                    method.setAccessible(true);
                } catch (Throwable ignored) {
                }

                METHOD_CACHE.put(key, method);

                return method;
            }

        } catch (Throwable ignored) {
        }

        return null;
    }

    /**
     * 查找字段。
     */
    public static Field findField(
            Class<?> clazz,
            String fieldName
    ) {

        if (clazz == null ||
                fieldName == null ||
                fieldName.isBlank()) {

            return null;
        }

        String key =
                clazz.getName()
                        + "#FIELD#"
                        + fieldName;

        Field cached = FIELD_CACHE.get(key);

        if (cached != null) {
            return cached;
        }

        Class<?> current = clazz;

        while (current != null) {

            try {

                Field field =
                        current.getDeclaredField(fieldName);

                try {
                    field.setAccessible(true);
                } catch (Throwable ignored) {
                }

                FIELD_CACHE.put(key, field);

                return field;

            } catch (NoSuchFieldException ignored) {
                current = current.getSuperclass();
            } catch (Throwable ignored) {
                break;
            }
        }

        /*
         * public 字段。
         */
        try {

            Field field =
                    clazz.getField(fieldName);

            try {
                field.setAccessible(true);
            } catch (Throwable ignored) {
            }

            FIELD_CACHE.put(key, field);

            return field;

        } catch (Throwable ignored) {
            return null;
        }
    }

    /**
     * 调用无参数方法。
     */
    public static Object invokeNoArgs(
            Object target,
            String methodName
    ) {

        if (target == null ||
                methodName == null ||
                methodName.isBlank()) {

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

            return method.invoke(target);

        } catch (Throwable ignored) {

            return null;
        }
    }

    /**
     * 调用方法。
     */
    public static Object invoke(
            Object target,
            String methodName,
            Object... arguments
    ) {

        if (target == null ||
                methodName == null ||
                methodName.isBlank()) {

            return null;
        }

        int count =
                arguments == null
                        ? 0
                        : arguments.length;

        Method method =
                findCompatibleMethod(
                        target.getClass(),
                        methodName,
                        arguments
                );

        if (method == null) {
            return null;
        }

        try {

            return method.invoke(
                    target,
                    arguments == null
                            ? new Object[0]
                            : arguments
            );

        } catch (Throwable ignored) {

            return null;
        }
    }

    /**
     * 查找参数兼容的方法。
     */
    private static Method findCompatibleMethod(
            Class<?> clazz,
            String methodName,
            Object[] arguments
    ) {

        int count =
                arguments == null
                        ? 0
                        : arguments.length;

        String key =
                clazz.getName()
                        + "#"
                        + methodName
                        + "#compatible#"
                        + count;

        Method cached =
                METHOD_CACHE.get(key);

        if (cached != null) {
            return cached;
        }

        Class<?> current = clazz;

        while (current != null) {

            for (Method method : current.getDeclaredMethods()) {

                if (!method.getName().equals(methodName)) {
                    continue;
                }

                Class<?>[] parameterTypes =
                        method.getParameterTypes();

                if (parameterTypes.length != count) {
                    continue;
                }

                if (!areArgumentsCompatible(
                        parameterTypes,
                        arguments
                )) {
                    continue;
                }

                try {
                    method.setAccessible(true);
                } catch (Throwable ignored) {
                }

                METHOD_CACHE.put(key, method);

                return method;
            }

            current = current.getSuperclass();
        }

        try {

            for (Method method : clazz.getMethods()) {

                if (!method.getName().equals(methodName)) {
                    continue;
                }

                Class<?>[] parameterTypes =
                        method.getParameterTypes();

                if (parameterTypes.length != count) {
                    continue;
                }

                if (!areArgumentsCompatible(
                        parameterTypes,
                        arguments
                )) {
                    continue;
                }

                try {
                    method.setAccessible(true);
                } catch (Throwable ignored) {
                }

                METHOD_CACHE.put(key, method);

                return method;
            }

        } catch (Throwable ignored) {
        }

        return null;
    }

    /**
     * 检查参数类型。
     */
    private static boolean areArgumentsCompatible(
            Class<?>[] parameterTypes,
            Object[] arguments
    ) {

        if (parameterTypes.length == 0) {
            return true;
        }

        if (arguments == null ||
                arguments.length != parameterTypes.length) {

            return false;
        }

        for (int i = 0; i < parameterTypes.length; i++) {

            Object argument =
                    arguments[i];

            Class<?> parameter =
                    parameterTypes[i];

            if (argument == null) {

                if (parameter.isPrimitive()) {
                    return false;
                }

                continue;
            }

            if (parameter.isPrimitive()) {

                if (!primitiveCompatible(
                        parameter,
                        argument.getClass()
                )) {

                    return false;
                }

            } else if (!parameter.isAssignableFrom(
                    argument.getClass()
            )) {

                return false;
            }
        }

        return true;
    }

    /**
     * Java 基本类型兼容检查。
     */
    private static boolean primitiveCompatible(
            Class<?> primitive,
            Class<?> wrapper
    ) {

        if (primitive == int.class) {
            return wrapper == Integer.class;
        }

        if (primitive == long.class) {
            return wrapper == Long.class ||
                    wrapper == Integer.class;
        }

        if (primitive == float.class) {
            return wrapper == Float.class ||
                    wrapper == Double.class ||
                    wrapper == Integer.class;
        }

        if (primitive == double.class) {
            return wrapper == Double.class ||
                    wrapper == Float.class ||
                    wrapper == Integer.class;
        }

        if (primitive == short.class) {
            return wrapper == Short.class ||
                    wrapper == Integer.class;
        }

        if (primitive == byte.class) {
            return wrapper == Byte.class ||
                    wrapper == Integer.class;
        }

        if (primitive == boolean.class) {
            return wrapper == Boolean.class;
        }

        if (primitive == char.class) {
            return wrapper == Character.class;
        }

        return false;
    }

    /**
     * 读取字段。
     */
    public static Object getField(
            Object target,
            String fieldName
    ) {

        if (target == null) {
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

        } catch (Throwable ignored) {

            return null;
        }
    }

    /**
     * 修改字段。
     */
    public static boolean setField(
            Object target,
            String fieldName,
            Object value
    ) {

        if (target == null) {
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

            field.set(target, value);

            return true;

        } catch (Throwable ignored) {

            return false;
        }
    }

    // =========================================================
    // LazyOptional
    // =========================================================

    /**
     * 从 Forge LazyOptional 中取出实际对象。
     *
     * 不直接 import LazyOptional，
     * 避免反射层和 Forge API 产生不必要耦合。
     */
    public static Object resolveLazyOptional(
            Object optional
    ) {

        if (optional == null) {
            return null;
        }

        /*
         * 先判断 isPresent()。
         */
        Object present =
                invokeNoArgs(
                        optional,
                        "isPresent"
                );

        if (present instanceof Boolean &&
                !((Boolean) present)) {

            return null;
        }

        /*
         * Forge LazyOptional 具有 orElse / get 等访问方式。
         */
        Object result =
                invokeNoArgs(
                        optional,
                        "get"
                );

        if (result != null) {
            return result;
        }

        result =
                invoke(
                        optional,
                        "orElse",
                        new Object[]{null}
                );

        return result;
    }

    // =========================================================
    // NetCraft PlayerStats
    // =========================================================

    /**
     * 获取 NetCraft PlayerStatsCapability 类。
     */
    public static Class<?> getPlayerStatsCapabilityClass() {

        return findClass(
                PLAYER_STATS_CAPABILITY_CLASS
        );
    }

    /**
     * 获取玩家 NetCraft Stats。
     *
     * 这里不直接依赖 Player 类型，
     * 因此 Entity / Player 都可以传入。
     */
    public static Object findPlayerStats(
            Entity entity
    ) {

        if (entity == null) {
            return null;
        }

        Class<?> capabilityClass =
                getPlayerStatsCapabilityClass();

        if (capabilityClass == null) {

            return null;
        }

        /*
         * NetCraft 1.4.18 的真实入口：
         *
         * PlayerStatsCapability.get(Player)
         *
         * 这是静态方法。
         *
         * 由于 Entity 可能不是 Player，
         * 我们只在参数类型兼容时调用。
         */
        Method getMethod =
                findStaticCompatibleMethod(
                        capabilityClass,
                        "get",
                        entity
                );

        if (getMethod != null) {

            try {

                Object optional =
                        getMethod.invoke(
                                null,
                                entity
                        );

                Object stats =
                        resolveLazyOptional(
                                optional
                        );

                if (stats != null) {
                    return stats;
                }

            } catch (Throwable ignored) {
            }
        }

        /*
         * 如果 get(Player) 没有匹配成功，
         * 再尝试直接扫描 capability 类里的
         * static Capability 字段。
         */
        Object capability =
                findCapabilityField(
                        capabilityClass
                );

        if (capability != null) {

            Object optional =
                    getCapability(
                            entity,
                            capability
                    );

            Object stats =
                    resolveLazyOptional(
                            optional
                    );

            if (stats != null) {
                return stats;
            }
        }

        return null;
    }

    /**
     * 查找静态兼容方法。
     */
    private static Method findStaticCompatibleMethod(
            Class<?> clazz,
            String methodName,
            Object argument
    ) {

        if (clazz == null) {
            return null;
        }

        Class<?> current = clazz;

        while (current != null) {

            for (Method method :
                    current.getDeclaredMethods()) {

                if (!method.getName().equals(methodName)) {
                    continue;
                }

                if (method.getParameterCount() != 1) {
                    continue;
                }

                if (!Modifier.isStatic(
                        method.getModifiers()
                )) {
                    continue;
                }

                Class<?> parameter =
                        method.getParameterTypes()[0];

                if (argument == null) {

                    if (parameter.isPrimitive()) {
                        continue;
                    }

                } else if (!parameter.isAssignableFrom(
                        argument.getClass()
                )) {

                    continue;
                }

                try {
                    method.setAccessible(true);
                } catch (Throwable ignored) {
                }

                return method;
            }

            current =
                    current.getSuperclass();
        }

        return null;
    }

    /**
     * 查找 NetCraft Capability 字段。
     *
     * 优先寻找名字中带：
     *
     * PLAYER_STATS
     * PLAYERSTAT
     * STATS
     */
    private static Object findCapabilityField(
            Class<?> capabilityClass
    ) {

        if (capabilityClass == null) {
            return null;
        }

        Field fallback = null;

        Class<?> current =
                capabilityClass;

        while (current != null) {

            for (Field field :
                    current.getDeclaredFields()) {

                /*
                 * 必须是 static。
                 */
                if (!Modifier.isStatic(
                        field.getModifiers()
                )) {
                    continue;
                }

                Class<?> type =
                        field.getType();

                /*
                 * Capability 类型。
                 */
                if (!type.getName().equals(
                        "net.minecraftforge.common.capabilities.Capability"
                )) {
                    continue;
                }

                String name =
                        field.getName()
                                .toUpperCase();

                if (name.contains("PLAYER_STATS") ||
                        name.contains("PLAYERSTAT") ||
                        name.equals("STATS") ||
                        name.contains("PLAYER")) {

                    try {
                        field.setAccessible(true);

                        Object value =
                                field.get(null);

                        if (value != null) {
                            return value;
                        }

                    } catch (Throwable ignored) {
                    }
                }

                if (fallback == null) {
                    fallback = field;
                }
            }

            current =
                    current.getSuperclass();
        }

        /*
         * 如果没有明确名字，
         * 只有一个 Capability 字段时使用它。
         */
        if (fallback != null) {

            try {

                fallback.setAccessible(true);

                return fallback.get(null);

            } catch (Throwable ignored) {
            }
        }

        return null;
    }

    /**
     * 通过 Capability 获取对象。
     *
     * 对应 Forge：
     *
     * entity.getCapability(capability, null)
     *
     * Forge 文档也明确说明第二个参数可以传 null，
     * 表示不指定方向 / 从对象自身请求 Capability。
     */
    public static Object getCapability(
            Entity entity,
            Object capability
    ) {

        if (entity == null ||
                capability == null) {

            return null;
        }

        Method method =
                findCompatibleMethod(
                        entity.getClass(),
                        "getCapability",
                        new Object[]{
                                capability,
                                null
                        }
                );

        if (method == null) {
            return null;
        }

        try {

            return method.invoke(
                    entity,
                    capability,
                    null
            );

        } catch (Throwable ignored) {

            return null;
        }
    }

    // =========================================================
    // PlayerStats 通用数值访问
    // =========================================================

    /**
     * 读取数值。
     */
    private static Number readNumber(
            Object target,
            String methodName
    ) {

        if (target == null) {
            return null;
        }

        Object value =
                invokeNoArgs(
                        target,
                        methodName
                );

        if (value instanceof Number) {
            return (Number) value;
        }

        return null;
    }

    /**
     * 设置 int。
     */
    private static boolean writeInt(
            Object target,
            String methodName,
            int value
    ) {

        if (target == null) {
            return false;
        }

        Object result =
                invoke(
                        target,
                        methodName,
                        value
                );

        /*
         * setter 一般返回 void，
         * invoke() 返回 null 也代表成功。
         *
         * 因此这里必须通过方法存在性重新判断。
         */
        Method method =
                findCompatibleMethod(
                        target.getClass(),
                        methodName,
                        new Object[]{value}
                );

        return method != null;
    }

    /**
     * 设置 float。
     */
    private static boolean writeFloat(
            Object target,
            String methodName,
            float value
    ) {

        if (target == null) {
            return false;
        }

        invoke(
                target,
                methodName,
                value
        );

        Method method =
                findCompatibleMethod(
                        target.getClass(),
                        methodName,
                        new Object[]{value}
                );

        return method != null;
    }

    // =========================================================
    // Damage
    // =========================================================

    public static Integer getMeleeDamage(
            Object stats
    ) {

        Number value =
                readNumber(
                        stats,
                        "getMeleeDamage"
                );

        return value == null
                ? null
                : value.intValue();
    }

    public static boolean setMeleeDamage(
            Object stats,
            int value
    ) {

        return writeInt(
                stats,
                "setMeleeDamage",
                value
        );
    }

    public static Integer getRangedDamage(
            Object stats
    ) {

        Number value =
                readNumber(
                        stats,
                        "getRangedDamage"
                );

        return value == null
                ? null
                : value.intValue();
    }

    public static boolean setRangedDamage(
            Object stats,
            int value
    ) {

        return writeInt(
                stats,
                "setRangedDamage",
                value
        );
    }

    public static Integer getMagicDamage(
            Object stats
    ) {

        Number value =
                readNumber(
                        stats,
                        "getMagicDamage"
                );

        return value == null
                ? null
                : value.intValue();
    }

    public static boolean setMagicDamage(
            Object stats,
            int value
    ) {

        return writeInt(
                stats,
                "setMagicDamage",
                value
        );
    }

    public static Integer getPhysicalDamage(
            Object stats
    ) {

        Number value =
                readNumber(
                        stats,
                        "getPhysicalDamage"
                );

        return value == null
                ? null
                : value.intValue();
    }

    public static boolean setPhysicalDamage(
            Object stats,
            int value
    ) {

        return writeInt(
                stats,
                "setPhysicalDamage",
                value
        );
    }

    // =========================================================
    // Defense
    // =========================================================

    public static Integer getMeleeDefense(
            Object stats
    ) {

        Number value =
                readNumber(
                        stats,
                        "getMeleeDefense"
                );

        return value == null
                ? null
                : value.intValue();
    }

    public static boolean setMeleeDefense(
            Object stats,
            int value
    ) {

        return writeInt(
                stats,
                "setMeleeDefense",
                value
        );
    }

    public static Integer getRangedDefense(
            Object stats
    ) {

        Number value =
                readNumber(
                        stats,
                        "getRangedDefense"
                );

        return value == null
                ? null
                : value.intValue();
    }

    public static boolean setRangedDefense(
            Object stats,
            int value
    ) {

        return writeInt(
                stats,
                "setRangedDefense",
                value
        );
    }

    public static Float getPhysicalDefense(
            Object stats
    ) {

        Number value =
                readNumber(
                        stats,
                        "getPhysicalDefense"
                );

        return value == null
                ? null
                : value.floatValue();
    }

    public static boolean setPhysicalDefense(
            Object stats,
            float value
    ) {

        return writeFloat(
                stats,
                "setPhysicalDefense",
                value
        );
    }

    public static Float getMagicDefense(
            Object stats
    ) {

        Number value =
                readNumber(
                        stats,
                        "getMagicDefense"
                );

        return value == null
                ? null
                : value.floatValue();
    }

    public static boolean setMagicDefense(
            Object stats,
            float value
    ) {

        return writeFloat(
                stats,
                "setMagicDefense",
                value
        );
    }

    // =========================================================
    // Critical / Dodge
    // =========================================================

    public static Float getCriticalChance(
            Object stats
    ) {

        Number value =
                readNumber(
                        stats,
                        "getCriticalChance"
                );

        return value == null
                ? null
                : value.floatValue();
    }

    public static boolean setCriticalChance(
            Object stats,
            float value
    ) {

        return writeFloat(
                stats,
                "setCriticalChance",
                value
        );
    }

    public static Float getCriticalMultiplier(
            Object stats
    ) {

        Number value =
                readNumber(
                        stats,
                        "getCriticalMultiplier"
                );

        return value == null
                ? null
                : value.floatValue();
    }

    public static boolean setCriticalMultiplier(
            Object stats,
            float value
    ) {

        return writeFloat(
                stats,
                "setCriticalMultiplier",
                value
        );
    }

    public static Float getDodgeChance(
            Object stats
    ) {

        Number value =
                readNumber(
                        stats,
                        "getDodgeChance"
                );

        return value == null
                ? null
                : value.floatValue();
    }

    public static boolean setDodgeChance(
            Object stats,
            float value
    ) {

        return writeFloat(
                stats,
                "setDodgeChance",
                value
        );
    }

    // =========================================================
    // BossBase
    // =========================================================

    /**
     * 获取 BossBase 的 hatred manager。
     *
     * 这里不直接依赖 BossBase 类，
     * 只要求对象本身存在 getHatredManager()。
     */
    public static Object getHatredManager(
            Object bossBase
    ) {

        if (bossBase == null) {
            return null;
        }

        return invokeNoArgs(
                bossBase,
                "getHatredManager"
        );
    }

    /**
     * Boss 基础伤害。
     */
    public static Integer getBossBaseDamage(
            Object bossBase
    ) {

        Number value =
                readNumber(
                        bossBase,
                        "getBaseDamage"
                );

        return value == null
                ? null
                : value.intValue();
    }

    /**
     * 设置 Boss 基础伤害。
     */
    public static boolean setBossBaseDamage(
            Object bossBase,
            int value
    ) {

        return writeInt(
                bossBase,
                "setBaseDamage",
                value
        );
    }

    /**
     * Boss 基础防御。
     */
    public static Integer getBossBaseDefense(
            Object bossBase
    ) {

        Number value =
                readNumber(
                        bossBase,
                        "getBaseDefense"
                );

        return value == null
                ? null
                : value.intValue();
    }

    /**
     * 设置 Boss 基础防御。
     */
    public static boolean setBossBaseDefense(
            Object bossBase,
            int value
    ) {

        return writeInt(
                bossBase,
                "setBaseDefense",
                value
        );
    }

    /**
     * Boss 实际伤害。
     */
    public static Integer getBossActualDamage(
            Object bossBase
    ) {

        Number value =
                readNumber(
                        bossBase,
                        "getActualDamage"
                );

        return value == null
                ? null
                : value.intValue();
    }

    /**
     * Boss 攻击伤害。
     */
    public static Integer getBossAttackDamage(
            Object bossBase
    ) {

        Number value =
                readNumber(
                        bossBase,
                        "getAttackDamage"
                );

        return value == null
                ? null
                : value.intValue();
    }

    /**
     * Boss 伤害减免比例。
     */
    public static Float getDamageReductionRatio(
            Object bossBase
    ) {

        Number value =
                readNumber(
                        bossBase,
                        "getDamageReductionRatio"
                );

        return value == null
                ? null
                : value.floatValue();
    }

    /**
     * Boss 近战减免比例。
     */
    public static Float getBossMeleeReductionRatio(
            Object bossBase
    ) {

        Number value =
                readNumber(
                        bossBase,
                        "getBossMeleeReductionRatio"
                );

        return value == null
                ? null
                : value.floatValue();
    }

    // =========================================================
    // Map 工具
    // =========================================================

    /**
     * 在对象及其字段中寻找第一个 Map。
     *
     * 后面分析 HatredManager 等结构时使用。
     */
    @SuppressWarnings("unchecked")
    public static Map<Object, Object> findFirstMap(
            Object target
    ) {

        if (target == null) {
            return null;
        }

        if (target instanceof Map<?, ?>) {
            return (Map<Object, Object>) target;
        }

        Class<?> current =
                target.getClass();

        while (current != null) {

            for (Field field :
                    current.getDeclaredFields()) {

                try {

                    field.setAccessible(true);

                    Object value =
                            field.get(target);

                    if (value instanceof Map<?, ?>) {

                        return (Map<Object, Object>) value;
                    }

                } catch (Throwable ignored) {
                }
            }

            current =
                    current.getSuperclass();
        }

        return null;
    }

    // =========================================================
    // 数值工具
    // =========================================================

    public static int intValue(
            Object value,
            int defaultValue
    ) {

        if (value instanceof Number) {
            return ((Number) value).intValue();
        }

        return defaultValue;
    }

    public static float floatValue(
            Object value,
            float defaultValue
    ) {

        if (value instanceof Number) {
            return ((Number) value).floatValue();
        }

        return defaultValue;
    }

    public static double doubleValue(
            Object value,
            double defaultValue
    ) {

        if (value instanceof Number) {
            return ((Number) value).doubleValue();
        }

        return defaultValue;
    }

    // =========================================================
    // 兼容辅助
    // =========================================================

    /**
     * 尝试调用：
     *
     * getEnchantmentDefenseValue(int)
     *
     * 这个方法是否存在由真实 NetCraft 版本决定。
     */
    public static Float getEnchantmentDefenseValue(
            Object target,
            int level
    ) {

        if (target == null) {
            return null;
        }

        Object value =
                invoke(
                        target,
                        "getEnchantmentDefenseValue",
                        level
                );

        if (value instanceof Number) {

            return ((Number) value)
                    .floatValue();
        }

        return null;
    }

    /**
     * 尝试获取对象类。
     */
    public static String getClassName(
            Object object
    ) {

        if (object == null) {
            return null;
        }

        return object.getClass().getName();
    }

    /**
     * 判断 NetCraft 是否已经安装/加载。
     */
    public static boolean isNetCraftLoaded() {

        return getPlayerStatsCapabilityClass() != null;
    }

    /**
     * 清理反射缓存。
     */
    public static void clearCaches() {

        CLASS_CACHE.clear();
        METHOD_CACHE.clear();
        FIELD_CACHE.clear();
        WARNED.clear();
    }

    /**
     * 记录一次警告。
     *
     * 防止每个实体每个 Tick 都刷日志。
     */
    public static void warnOnce(
            Logger logger,
            String key,
            String message
    ) {

        if (logger == null ||
                key == null ||
                message == null) {

            return;
        }

        if (WARNED.putIfAbsent(
                key,
                Boolean.TRUE
        ) == null) {

            logger.warn(
                    "[NetCraftToolkit] {}",
                    message
            );
        }
    }
}
