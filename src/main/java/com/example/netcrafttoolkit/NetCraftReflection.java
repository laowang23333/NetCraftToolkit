package com.example.netcrafttoolkit;

import com.mojang.logging.LogUtils;
import net.minecraft.world.entity.LivingEntity;
import org.slf4j.Logger;

import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public final class NetCraftReflection {

    private static final Logger LOGGER =
            LogUtils.getLogger();

    private NetCraftReflection() {
    }

    /*
     * ============================================================
     * 反射缓存
     * ============================================================
     *
     * 所有 Class / Method / Field 都缓存。
     *
     * 这样战斗过程中不会每次伤害都 Class.forName。
     */

    private static final Map<String, Optional<Class<?>>> CLASS_CACHE =
            new ConcurrentHashMap<>();

    private static final Map<String, Optional<Method>> METHOD_CACHE =
            new ConcurrentHashMap<>();

    private static final Map<String, Optional<Field>> FIELD_CACHE =
            new ConcurrentHashMap<>();

    /*
     * 某个功能失败后不再疯狂重试。
     */
    private static final Set<String> FAILED =
            ConcurrentHashMap.newKeySet();

    /*
     * ============================================================
     * 基础 Class 查找
     * ============================================================
     */

    public static Class<?> findClass(
            String className
    ) {

        if (className == null
                || className.isEmpty()) {

            return null;
        }

        Optional<Class<?>> cached =
                CLASS_CACHE.get(className);

        if (cached != null) {
            return cached.orElse(null);
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
                    Optional.of(clazz)
            );

            return clazz;

        } catch (Throwable ignored) {

            CLASS_CACHE.put(
                    className,
                    Optional.empty()
            );

            return null;
        }
    }

    /*
     * ============================================================
     * NetCraft PlayerStatsCapability
     * ============================================================
     *
     * 目标：
     *
     * com.jiufeng.netcraft.capability.PlayerStatsCapability
     *
     * 这里不 import。
     */

    public static Object resolvePlayerStatsCapability(
            LivingEntity living
    ) {

        if (living == null) {
            return null;
        }

        Class<?> capabilityClass =
                findClass(
                        "com.jiufeng.netcraft.capability.PlayerStatsCapability"
                );

        if (capabilityClass == null) {

            disableOnce(
                    "PlayerStatsCapability",
                    "找不到 PlayerStatsCapability"
            );

            return null;
        }

        /*
         * 这里不直接假设 Capability 的静态字段
         * 一定叫什么名字。
         *
         * 我们尝试寻找：
         *
         * 静态 Capability 字段
         * 类型符合
         */

        Object capability =
                findStaticCapabilityField(
                        capabilityClass
                );

        if (capability == null) {

            disableOnce(
                    "PlayerStatsCapability",
                    "找不到 PlayerStatsCapability 的 Capability 字段"
            );

            return null;
        }

        /*
         * Forge Capability 获取：
         *
         * living.getCapability(capability, null)
         *
         * 为了不直接引用 Capability<T>，
         * 这里继续反射。
         */

        try {

            Method getCapability =
                    findMethod(
                            living.getClass(),
                            "getCapability",
                            2
                    );

            if (getCapability == null) {

                disableOnce(
                        "getCapability",
                        "找不到 LivingEntity#getCapability"
                );

                return null;
            }

            Object lazyOptional =
                    getCapability.invoke(
                            living,
                            capability,
                            null
                    );

            if (lazyOptional == null) {
                return null;
            }

            /*
             * LazyOptional#orElse(T)
             */
            Method orElse =
                    findMethod(
                            lazyOptional.getClass(),
                            "orElse",
                            1
                    );

            if (orElse != null) {

                return orElse.invoke(
                        lazyOptional,
                        new Object[]{null}
                );
            }

            /*
             * 如果实现类找不到，
             * 尝试父类。
             */
            Method[] methods =
                    lazyOptional
                            .getClass()
                            .getMethods();

            for (Method method : methods) {

                if (!method.getName()
                        .equals("orElse")) {

                    continue;
                }

                if (method.getParameterCount()
                        != 1) {

                    continue;
                }

                method.setAccessible(true);

                return method.invoke(
                        lazyOptional,
                        new Object[]{null}
                );
            }

        } catch (Throwable t) {

            disableOnce(
                    "getPlayerStatsCapability",
                    "读取 PlayerStatsCapability 失败: "
                            + t.getClass().getSimpleName()
            );
        }

        return null;
    }

    /*
     * ============================================================
     * 读取物理防御
     * ============================================================
     */

    public static Double getPhysicalDefense(
            LivingEntity living
    ) {

        Object capability =
                resolvePlayerStatsCapability(
                        living
                );

        if (capability == null) {
            return null;
        }

        Method getter =
                findCompatibleMethod(
                        capability.getClass(),
                        "getPhysicalDefense",
                        0
                );

        if (getter == null) {

            disableOnce(
                    "getPhysicalDefense",
                    "找不到 getPhysicalDefense()"
            );

            return null;
        }

        try {

            getter.setAccessible(true);

            Object value =
                    getter.invoke(
                            capability
                    );

            if (value instanceof Number number) {

                return number.doubleValue();
            }

        } catch (Throwable t) {

            disableOnce(
                    "getPhysicalDefense",
                    "读取物理防御失败: "
                            + t.getClass().getSimpleName()
            );
        }

        return null;
    }

    /*
     * ============================================================
     * 修改物理防御
     * ============================================================
     */

    public static boolean setPhysicalDefense(
            LivingEntity living,
            double value
    ) {

        Object capability =
                resolvePlayerStatsCapability(
                        living
                );

        if (capability == null) {
            return false;
        }

        Method setter =
                findCompatibleMethod(
                        capability.getClass(),
                        "setPhysicalDefense",
                        1
                );

        if (setter == null) {

            disableOnce(
                    "setPhysicalDefense",
                    "找不到 setPhysicalDefense(...)"
            );

            return false;
        }

        try {

            setter.setAccessible(true);

            Class<?> type =
                    setter.getParameterTypes()[0];

            setter.invoke(
                    capability,
                    convertNumber(
                            value,
                            type
                    )
            );

            return true;

        } catch (Throwable t) {

            disableOnce(
                    "setPhysicalDefense",
                    "写入物理防御失败: "
                            + t.getClass().getSimpleName()
            );

            return false;
        }
    }

    /*
     * ============================================================
     * 附魔防御公式
     * ============================================================
     *
     * 目标：
     *
     * com.jiufeng.netcraft.api.PlayerBaseStats
     *
     * getEnchantmentDefenseValue(int)
     *
     * 这里不直接依赖 PlayerBaseStats。
     */

    public static Double getEnchantmentDefenseValue(
            Object playerBaseStats,
            int enchantmentLevel
    ) {

        if (playerBaseStats == null) {
            return null;
        }

        Method method =
                findCompatibleMethod(
                        playerBaseStats.getClass(),
                        "getEnchantmentDefenseValue",
                        1
                );

        if (method == null) {

            disableOnce(
                    "getEnchantmentDefenseValue",
                    "找不到 getEnchantmentDefenseValue(int)"
            );

            return null;
        }

        try {

            method.setAccessible(true);

            Object value =
                    method.invoke(
                            playerBaseStats,
                            enchantmentLevel
                    );

            if (value instanceof Number number) {

                return number.doubleValue();
            }

        } catch (Throwable t) {

            disableOnce(
                    "getEnchantmentDefenseValue",
                    "调用附魔防御公式失败: "
                            + t.getClass().getSimpleName()
            );
        }

        return null;
    }

    /*
     * ============================================================
     * 通用无参方法
     * ============================================================
     *
     * 后面 BossBase / HatredManager 等可以直接用。
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
                findCompatibleMethod(
                        target.getClass(),
                        methodName,
                        0
                );

        if (method == null) {
            return null;
        }

        try {

            method.setAccessible(true);

            return method.invoke(
                    target
            );

        } catch (Throwable t) {

            disableOnce(
                    "method:" + target.getClass().getName()
                            + "#" + methodName,
                    "调用失败: "
                            + t.getClass().getSimpleName()
            );

            return null;
        }
    }

    /*
     * ============================================================
     * Boss HatredManager
     * ============================================================
     *
     * 目标：
     *
     * BossBase#getHatredManager()
     *
     * 返回对象后，
     * 后面的战斗统计模块再读取 Map。
     */

    public static Object getHatredManager(
            LivingEntity boss
    ) {

        if (boss == null) {
            return null;
        }

        /*
         * 不要求 BossBase 的准确类名。
         *
         * 直接从实体类及父类向上寻找：
         *
         * getHatredManager()
         */

        Class<?> current =
                boss.getClass();

        while (current != null
                && current != Object.class) {

            Method method =
                    findCompatibleMethod(
                            current,
                            "getHatredManager",
                            0
                    );

            if (method != null) {

                try {

                    method.setAccessible(true);

                    return method.invoke(
                            boss
                    );

                } catch (Throwable t) {

                    disableOnce(
                            "getHatredManager",
                            "Boss仇恨管理器读取失败: "
                                    + t.getClass()
                                    .getSimpleName()
                    );

                    return null;
                }
            }

            current =
                    current.getSuperclass();
        }

        return null;
    }

    /*
     * ============================================================
     * 读取 Boss 战斗统计
     * ============================================================
     *
     * 根据你截图中的方案：
     *
     * getHatredManager()
     *       ↓
     * 找所有无参返回 Map 的方法
     *       ↓
     * 只接受 Map<String/UUID, Double>
     *
     * 不把方法名写死。
     */

    public static Map<Object, Double>
    readCombatantIds(
            LivingEntity boss
    ) {

        Object hatredManager =
                getHatredManager(
                        boss
                );

        if (hatredManager == null) {
            return Collections.emptyMap();
        }

        Map<Object, Double> result =
                new LinkedHashMap<>();

        Class<?> current =
                hatredManager.getClass();

        while (current != null
                && current != Object.class) {

            for (Method method :
                    current.getDeclaredMethods()) {

                if (method.getParameterCount()
                        != 0) {

                    continue;
                }

                /*
                 * 必须返回 Map。
                 */
                if (!Map.class.isAssignableFrom(
                        method.getReturnType()
                )) {

                    continue;
                }

                try {

                    method.setAccessible(true);

                    Object value =
                            method.invoke(
                                    hatredManager
                            );

                    if (!(value instanceof Map<?, ?> map)) {
                        continue;
                    }

                    /*
                     * 只接受 value 是 Number。
                     *
                     * NetCraft 当前设计里是 Double。
                     */
                    for (Map.Entry<?, ?> entry :
                            map.entrySet()) {

                        if (!(entry.getValue()
                                instanceof Number number)) {

                            continue;
                        }

                        result.put(
                                entry.getKey(),
                                number.doubleValue()
                        );
                    }

                    /*
                     * 找到符合条件的 Map 后结束。
                     */
                    if (!result.isEmpty()) {
                        return result;
                    }

                } catch (Throwable ignored) {
                    /*
                     * 某个 Map 方法访问失败，
                     * 继续尝试下一个。
                     */
                }
            }

            current =
                    current.getSuperclass();
        }

        return result;
    }

    /*
     * ============================================================
     * 静态 Capability 字段寻找
     * ============================================================
     */

    private static Object findStaticCapabilityField(
            Class<?> capabilityClass
    ) {

        /*
         * 先检查 capabilityClass 自己。
         */
        Class<?> current =
                capabilityClass;

        while (current != null
                && current != Object.class) {

            for (Field field :
                    current.getDeclaredFields()) {

                int modifiers =
                        field.getModifiers();

                if (!Modifier.isStatic(
                        modifiers
                )) {
                    continue;
                }

                try {

                    field.setAccessible(true);

                    Object value =
                            field.get(null);

                    if (value == null) {
                        continue;
                    }

                    /*
                     * Capability 的字段通常包含：
                     *
                     * net.minecraftforge.common.capabilities.Capability
                     *
                     * 不直接 import。
                     */
                    String typeName =
                            field.getType()
                                    .getName();

                    if (typeName.contains(
                            "Capability"
                    )) {

                        return value;
                    }

                } catch (Throwable ignored) {
                }
            }

            current =
                    current.getSuperclass();
        }

        return null;
    }

    /*
     * ============================================================
     * Method 查找
     * ============================================================
     */

    private static Method findMethod(
            Class<?> clazz,
            String name,
            int parameterCount
    ) {

        if (clazz == null) {
            return null;
        }

        String key =
                clazz.getName()
                        + "#"
                        + name
                        + "#"
                        + parameterCount;

        Optional<Method> cached =
                METHOD_CACHE.get(key);

        if (cached != null) {
            return cached.orElse(null);
        }

        Class<?> current =
                clazz;

        while (current != null
                && current != Object.class) {

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
                        Optional.of(method)
                );

                return method;
            }

            current =
                    current.getSuperclass();
        }

        METHOD_CACHE.put(
                key,
                Optional.empty()
        );

        return null;
    }

    /*
     * ============================================================
     * 更宽松的方法寻找
     * ============================================================
     */

    private static Method findCompatibleMethod(
            Class<?> clazz,
            String name,
            int parameterCount
    ) {

        if (clazz == null) {
            return null;
        }

        Method method =
                findMethod(
                        clazz,
                        name,
                        parameterCount
                );

        if (method != null) {
            return method;
        }

        /*
         * 再看 public inherited methods。
         */
        try {

            for (Method candidate :
                    clazz.getMethods()) {

                if (!candidate.getName()
                        .equals(name)) {

                    continue;
                }

                if (candidate
                        .getParameterCount()
                        != parameterCount) {

                    continue;
                }

                try {
                    candidate.setAccessible(true);
                } catch (Throwable ignored) {
                }

                return candidate;
            }

        } catch (Throwable ignored) {
        }

        return null;
    }

    /*
     * ============================================================
     * 数值类型转换
     * ============================================================
     */

    private static Object convertNumber(
            double value,
            Class<?> targetType
    ) {

        if (targetType == double.class
                || targetType == Double.class) {

            return value;
        }

        if (targetType == float.class
                || targetType == Float.class) {

            return (float) value;
        }

        if (targetType == long.class
                || targetType == Long.class) {

            return (long) value;
        }

        if (targetType == int.class
                || targetType == Integer.class) {

            return (int) value;
        }

        if (targetType == short.class
                || targetType == Short.class) {

            return (short) value;
        }

        if (targetType == byte.class
                || targetType == Byte.class) {

            return (byte) value;
        }

        return value;
    }

    /*
     * ============================================================
     * 降级日志
     * ============================================================
     *
     * 同一个功能只报一次。
     *
     * 避免服务器控制台每 tick 刷几千行错误。
     */

    private static void disableOnce(
            String key,
            String message
    ) {

        if (!FAILED.add(key)) {
            return;
        }

        LOGGER.warn(
                "[NetCraftToolkit] {}，该功能已自动降级。",
                message
        );
    }

    /*
     * ============================================================
     * 清理缓存
     * ============================================================
     *
     * 服务器重启时一般不需要，
     * 但方便以后热重载。
     */

    public static void clearCaches() {

        CLASS_CACHE.clear();
        METHOD_CACHE.clear();
        FIELD_CACHE.clear();
        FAILED.clear();
    }
}
