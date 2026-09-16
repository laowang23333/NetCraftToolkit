package com.example.netcrafttoolkit;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * NetCraft 生物属性管理器。
 *
 * 负责：
 *
 * 1. 识别 netcraft: 生物。
 * 2. 保存生物原始 Minecraft 属性。
 * 3. 根据 NetCraftConfig 覆写属性。
 * 4. 支持服务器启动后重新应用。
 * 5. 支持配置热重载。
 * 6. 自动识别 NetCraft BossBase。
 * 7. 通过反射调用 BossBase 的真实 API。
 *
 * NetCraft 1.4.18 已确认：
 *
 * BossBase:
 *
 * getBaseDamage()
 * setBaseDamage(int)
 * getBaseDefense()
 * setBaseDefense(int)
 * getMeleeDefense()
 * getRangedDefense()
 * getMagicDefense()
 * getDamageReductionRatio()
 * getBossMeleeReductionRatio()
 * getHatredManager()
 */
public class NetCraftAttributeManager {

    /**
     * 支持的 Minecraft 原生属性。
     */
    private static final Attribute[] SUPPORTED_ATTRIBUTES = {
            Attributes.MAX_HEALTH,
            Attributes.ATTACK_DAMAGE,
            Attributes.MOVEMENT_SPEED,
            Attributes.ARMOR,
            Attributes.ATTACK_SPEED,
            Attributes.KNOCKBACK_RESISTANCE,
            Attributes.FOLLOW_RANGE
    };

    /**
     * 保存实体原始属性。
     *
     * UUID -> 属性 -> 原始值
     */
    private final Map<UUID, Map<Attribute, Double>> baseValues =
            new HashMap<>();

    /**
     * 已初始化的实体。
     */
    private final Map<UUID, Boolean> initializedEntities =
            new HashMap<>();

    /**
     * 防止重复刷日志。
     */
    private final Map<String, Boolean> warned =
            new HashMap<>();

    // =========================================================
    // Entity Join
    // =========================================================

    /**
     * 生物进入世界时处理。
     */
    @SubscribeEvent
    public void onEntityJoin(
            EntityJoinLevelEvent event
    ) {

        if (event == null) {
            return;
        }

        if (event.getLevel().isClientSide()) {
            return;
        }

        Entity entity =
                event.getEntity();

        if (!(entity instanceof LivingEntity livingEntity)) {
            return;
        }

        if (!isNetCraftEntity(entity)) {
            return;
        }

        /*
         * 第一次看到这个实体时保存原始属性。
         */
        rememberBaseValues(livingEntity);

        /*
         * 应用配置。
         */
        applyConfiguredValues(livingEntity);
    }

    // =========================================================
    // NetCraft 判断
    // =========================================================

    /**
     * 判断实体是否来自 NetCraft。
     */
    public boolean isNetCraftEntity(
            Entity entity
    ) {

        if (entity == null) {
            return false;
        }

        ResourceLocation id =
                BuiltInRegistries.ENTITY_TYPE
                        .getKey(entity.getType());

        if (id == null) {
            return false;
        }

        return "netcraft".equals(
                id.getNamespace()
        );
    }

    /**
     * 获取实体 ID。
     */
    public String getEntityId(
            Entity entity
    ) {

        if (entity == null) {
            return null;
        }

        ResourceLocation id =
                BuiltInRegistries.ENTITY_TYPE
                        .getKey(entity.getType());

        return id == null
                ? null
                : id.toString();
    }

    // =========================================================
    // 原始属性保存
    // =========================================================

    /**
     * 保存实体原始 Minecraft 属性。
     */
    public void rememberBaseValues(
            LivingEntity entity
    ) {

        if (entity == null) {
            return;
        }

        UUID uuid =
                entity.getUUID();

        if (initializedEntities.containsKey(uuid)) {
            return;
        }

        Map<Attribute, Double> values =
                new HashMap<>();

        for (Attribute attribute :
                SUPPORTED_ATTRIBUTES) {

            AttributeInstance instance =
                    entity.getAttribute(attribute);

            if (instance == null) {
                continue;
            }

            values.put(
                    attribute,
                    instance.getBaseValue()
            );
        }

        baseValues.put(
                uuid,
                values
        );

        initializedEntities.put(
                uuid,
                Boolean.TRUE
        );
    }

    // =========================================================
    // 配置应用
    // =========================================================

    /**
     * 应用当前配置。
     */
    public void applyConfiguredValues(
            LivingEntity entity
    ) {

        if (entity == null) {
            return;
        }

        if (!isNetCraftEntity(entity)) {
            return;
        }

        NetCraftConfig config =
                NetCraftToolkit.getConfig();

        if (config == null) {
            return;
        }

        String entityId =
                getEntityId(entity);

        if (entityId == null) {
            return;
        }

        /*
         * 从 NetCraftConfig 动态读取实体配置。
         *
         * 兼容：
         *
         * getEntityAttributes(String)
         * getAttributes(String)
         * getEntityConfig(String)
         * getMobAttributes(String)
         */
        Object configObject =
                findEntityConfig(
                        config,
                        entityId
                );

        if (configObject == null) {
            return;
        }

        /*
         * Minecraft 原生属性。
         */
        applyMinecraftAttributes(
                entity,
                configObject
        );

        /*
         * NetCraft BossBase。
         */
        applyBossBaseValues(
                entity,
                configObject
        );
    }

    /**
     * 查找实体配置。
     *
     * 不直接依赖 NetCraftConfig 某一个方法名称。
     */
    private Object findEntityConfig(
            NetCraftConfig config,
            String entityId
    ) {

        String[] methodNames = {
                "getEntityAttributes",
                "getAttributes",
                "getEntityConfig",
                "getMobAttributes",
                "getMobConfig"
        };

        for (String methodName :
                methodNames) {

            Object value =
                    invokeConfigMethod(
                            config,
                            methodName,
                            entityId
                    );

            if (value != null) {
                return value;
            }
        }

        return null;
    }

    /**
     * 调用 Config 方法。
     */
    private Object invokeConfigMethod(
            Object config,
            String methodName,
            String entityId
    ) {

        if (config == null) {
            return null;
        }

        try {

            Method method =
                    config.getClass()
                            .getMethod(
                                    methodName,
                                    String.class
                            );

            method.setAccessible(true);

            return method.invoke(
                    config,
                    entityId
            );

        } catch (Throwable ignored) {

            return null;
        }
    }

    // =========================================================
    // Minecraft 属性
    // =========================================================

    /**
     * 应用 Minecraft 原生属性。
     */
    private void applyMinecraftAttributes(
            LivingEntity entity,
            Object configObject
    ) {

        applyAttribute(
                entity,
                Attributes.MAX_HEALTH,
                configObject,
                "max_health",
                "maxHealth",
                "health"
        );

        applyAttribute(
                entity,
                Attributes.ATTACK_DAMAGE,
                configObject,
                "attack_damage",
                "attackDamage",
                "damage"
        );

        applyAttribute(
                entity,
                Attributes.MOVEMENT_SPEED,
                configObject,
                "movement_speed",
                "movementSpeed",
                "speed"
        );

        applyAttribute(
                entity,
                Attributes.ARMOR,
                configObject,
                "armor"
        );

        applyAttribute(
                entity,
                Attributes.ATTACK_SPEED,
                configObject,
                "attack_speed",
                "attackSpeed"
        );

        applyAttribute(
                entity,
                Attributes.KNOCKBACK_RESISTANCE,
                configObject,
                "knockback_resistance",
                "knockbackResistance"
        );

        applyAttribute(
                entity,
                Attributes.FOLLOW_RANGE,
                configObject,
                "follow_range",
                "followRange"
        );
    }

    /**
     * 设置单个 Minecraft 属性。
     */
    private void applyAttribute(
            LivingEntity entity,
            Attribute attribute,
            Object configObject,
            String... keys
    ) {

        Double value =
                readDouble(
                        configObject,
                        keys
                );

        /*
         * null = 没有配置。
         */
        if (value == null) {
            return;
        }

        /*
         * 负数作为禁用值。
         *
         * 和 NetCraftConfig 当前约定保持一致。
         */
        if (value < 0D) {
            return;
        }

        AttributeInstance instance =
                entity.getAttribute(attribute);

        if (instance == null) {
            return;
        }

        /*
         * 最大生命值改变时保持生命比例。
         */
        if (attribute == Attributes.MAX_HEALTH) {

            double oldMax =
                    entity.getMaxHealth();

            double oldHealth =
                    entity.getHealth();

            instance.setBaseValue(value);

            double newMax =
                    entity.getMaxHealth();

            if (oldMax > 0D &&
                    newMax > 0D) {

                double ratio =
                        oldHealth / oldMax;

                float newHealth =
                        (float) (
                                newMax * ratio
                        );

                entity.setHealth(
                        Math.max(
                                1.0F,
                                Math.min(
                                        newHealth,
                                        entity.getMaxHealth()
                                )
                        )
                );

            } else {

                entity.setHealth(
                        entity.getMaxHealth()
                );
            }

            return;
        }

        instance.setBaseValue(value);
    }

    // =========================================================
    // BossBase
    // =========================================================

    /**
     * 判断是否为 NetCraft BossBase。
     *
     * 不直接 import BossBase。
     *
     * 通过父类层级名称判断。
     */
    public boolean isBossBase(
            Entity entity
    ) {

        if (entity == null) {
            return false;
        }

        Class<?> clazz =
                entity.getClass();

        while (clazz != null) {

            if ("com.jiufeng.netcraft.entity.BossBase"
                    .equals(clazz.getName())) {

                return true;
            }

            clazz =
                    clazz.getSuperclass();
        }

        return false;
    }

    /**
     * 应用 BossBase 属性。
     */
    private void applyBossBaseValues(
            LivingEntity entity,
            Object configObject
    ) {

        if (!isBossBase(entity)) {
            return;
        }

        /*
         * NetCraft 1.4.18 的 BossBase：
         *
         * setBaseDamage(int)
         * setBaseDefense(int)
         */
        Double damage =
                readDouble(
                        configObject,
                        "base_damage",
                        "baseDamage",
                        "boss_damage",
                        "bossDamage"
                );

        if (damage != null &&
                damage >= 0D) {

            invokeBossSetter(
                    entity,
                    "setBaseDamage",
                    damage.intValue()
            );
        }

        Double defense =
                readDouble(
                        configObject,
                        "base_defense",
                        "baseDefense",
                        "boss_defense",
                        "bossDefense"
                );

        if (defense != null &&
                defense >= 0D) {

            invokeBossSetter(
                    entity,
                    "setBaseDefense",
                    defense.intValue()
            );
        }
    }

    /**
     * 调用 BossBase setter。
     */
    private boolean invokeBossSetter(
            Object boss,
            String methodName,
            int value
    ) {

        try {

            Method method =
                    boss.getClass()
                            .getMethod(
                                    methodName,
                                    int.class
                            );

            method.setAccessible(true);

            method.invoke(
                    boss,
                    value
            );

            return true;

        } catch (Throwable ignored) {

            /*
             * 子类没有 public 方法时，
             * 向父类继续寻找。
             */
            Class<?> current =
                    boss.getClass();

            while (current != null) {

                try {

                    Method method =
                            current.getDeclaredMethod(
                                    methodName,
                                    int.class
                            );

                    method.setAccessible(true);

                    method.invoke(
                            boss,
                            value
                    );

                    return true;

                } catch (Throwable ignoredAgain) {

                    current =
                            current.getSuperclass();
                }
            }
        }

        return false;
    }

    // =========================================================
    // 配置读取
    // =========================================================

    /**
     * 从配置对象读取 double。
     *
     * 支持 Map 和普通 Java 对象。
     */
    @SuppressWarnings("unchecked")
    private Double readDouble(
            Object configObject,
            String... keys
    ) {

        if (configObject == null) {
            return null;
        }

        /*
         * Map。
         */
        if (configObject instanceof Map<?, ?> map) {

            for (String key : keys) {

                Object value =
                        map.get(key);

                Double result =
                        toDouble(value);

                if (result != null) {
                    return result;
                }
            }
        }

        /*
         * 普通对象 getter。
         */
        for (String key : keys) {

            String getter =
                    "get"
                            + Character.toUpperCase(
                                    key.charAt(0)
                            )
                            + key.substring(1);

            try {

                Method method =
                        configObject.getClass()
                                .getMethod(
                                        getter
                                );

                Object value =
                        method.invoke(
                                configObject
                        );

                Double result =
                        toDouble(value);

                if (result != null) {
                    return result;
                }

            } catch (Throwable ignored) {
            }
        }

        /*
         * boolean/字段式结构也尝试读取字段。
         */
        for (String key : keys) {

            try {

                var field =
                        configObject.getClass()
                                .getDeclaredField(
                                        key
                                );

                field.setAccessible(true);

                Object value =
                        field.get(
                                configObject
                        );

                Double result =
                        toDouble(value);

                if (result != null) {
                    return result;
                }

            } catch (Throwable ignored) {
            }
        }

        return null;
    }

    /**
     * 转数字。
     */
    private Double toDouble(
            Object value
    ) {

        if (value instanceof Number number) {

            return number.doubleValue();
        }

        if (value instanceof String string) {

            try {

                return Double.parseDouble(
                        string.trim()
                );

            } catch (NumberFormatException ignored) {
            }
        }

        return null;
    }

    // =========================================================
    // 全量重载
    // =========================================================

    /**
     * 重新处理当前服务器中的全部 NetCraft 生物。
     */
    public void reloadAllEntities() {

        NetCraftConfig config =
                NetCraftToolkit.getConfig();

        if (config == null) {
            return;
        }

        MinecraftServer server =
                config.getServer();

        if (server == null) {

            return;
        }

        for (ServerLevel level :
                server.getAllLevels()) {

            for (Entity entity :
                    level.getAllEntities()) {

                if (!(entity instanceof LivingEntity livingEntity)) {
                    continue;
                }

                if (!isNetCraftEntity(entity)) {
                    continue;
                }

                /*
                 * 如果实体第一次被发现，
                 * 先保存原始属性。
                 */
                rememberBaseValues(
                        livingEntity
                );

                /*
                 * 应用新配置。
                 */
                applyConfiguredValues(
                        livingEntity
                );
            }
        }

        NetCraftToolkit.LOGGER.info(
                "[NetCraftToolkit] Reloaded NetCraft entities."
        );
    }

    // =========================================================
    // 恢复
    // =========================================================

    /**
     * 恢复实体原始 Minecraft 属性。
     */
    public void restoreBaseValues(
            LivingEntity entity
    ) {

        if (entity == null) {
            return;
        }

        UUID uuid =
                entity.getUUID();

        Map<Attribute, Double> values =
                baseValues.get(uuid);

        if (values == null) {
            return;
        }

        for (Map.Entry<Attribute, Double> entry :
                values.entrySet()) {

            AttributeInstance instance =
                    entity.getAttribute(
                            entry.getKey()
                    );

            if (instance == null) {
                continue;
            }

            instance.setBaseValue(
                    entry.getValue()
            );
        }

        /*
         * BossBase 也恢复。
         *
         * 这里只恢复 Minecraft 原生属性。
         * BossBase 的原始值以后可以单独缓存。
         */
    }

    // =========================================================
    // 手动设置
    // =========================================================

    /**
     * 手动设置属性。
     */
    public boolean setAttribute(
            LivingEntity entity,
            Attribute attribute,
            double value
    ) {

        if (entity == null ||
                attribute == null) {

            return false;
        }

        AttributeInstance instance =
                entity.getAttribute(
                        attribute
                );

        if (instance == null) {
            return false;
        }

        instance.setBaseValue(
                value
        );

        return true;
    }

    /**
     * 获取当前基础属性值。
     */
    public Double getAttribute(
            LivingEntity entity,
            Attribute attribute
    ) {

        if (entity == null ||
                attribute == null) {

            return null;
        }

        AttributeInstance instance =
                entity.getAttribute(
                        attribute
                );

        if (instance == null) {
            return null;
        }

        return instance.getBaseValue();
    }

    // =========================================================
    // 缓存
    // =========================================================

    /**
     * 清理缓存。
     */
    public void clear() {

        baseValues.clear();
        initializedEntities.clear();
        warned.clear();
    }

    /**
     * 删除指定实体缓存。
     */
    public void remove(
            UUID uuid
    ) {

        if (uuid == null) {
            return;
        }

        baseValues.remove(uuid);
        initializedEntities.remove(uuid);
    }

    /**
     * 获取缓存实体数量。
     */
    public int size() {

        return baseValues.size();
    }
}
