package com.example.netcrafttoolkit;

import com.mojang.logging.LogUtils;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.registries.ForgeRegistries;
import org.slf4j.Logger;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

public class NetCraftAttributeManager {

    private static final Logger LOGGER = LogUtils.getLogger();

    private static final Map<String, Attribute> SUPPORTED_ATTRIBUTES =
            new LinkedHashMap<>();

    /*
     * 配置文件里的名称 -> Minecraft 原版 Attribute
     */
    static {
        SUPPORTED_ATTRIBUTES.put(
                "max_health",
                Attributes.MAX_HEALTH
        );

        SUPPORTED_ATTRIBUTES.put(
                "attack_damage",
                Attributes.ATTACK_DAMAGE
        );

        SUPPORTED_ATTRIBUTES.put(
                "movement_speed",
                Attributes.MOVEMENT_SPEED
        );

        SUPPORTED_ATTRIBUTES.put(
                "armor",
                Attributes.ARMOR
        );

        SUPPORTED_ATTRIBUTES.put(
                "attack_speed",
                Attributes.ATTACK_SPEED
        );

        SUPPORTED_ATTRIBUTES.put(
                "knockback_resistance",
                Attributes.KNOCKBACK_RESISTANCE
        );

        SUPPORTED_ATTRIBUTES.put(
                "follow_range",
                Attributes.FOLLOW_RANGE
        );
    }

    /*
     * 每一种 NetCraft 生物第一次出现时，
     * 保存它真正的原始 Attribute。
     *
     * key:
     *
     * netcraft:xxx
     *
     * value:
     *
     * max_health -> 20
     * attack_damage -> 5
     * ...
     */
    private final Map<String, Map<String, Double>> baseValues =
            new LinkedHashMap<>();

    /*
     * 防止同一个实体反复初始化。
     */
    private final Map<Integer, Boolean> initializedEntities =
            new java.util.HashMap<>();

    public NetCraftAttributeManager() {
    }

    /*
     * ============================================================
     * EntityJoin
     * ============================================================
     */

    @SubscribeEvent(priority = EventPriority.NORMAL)
    public void onEntityJoin(
            EntityJoinLevelEvent event
    ) {

        if (event.getLevel().isClientSide()) {
            return;
        }

        if (!(event.getEntity()
                instanceof LivingEntity living)) {

            return;
        }

        if (!isNetCraftEntity(living)) {
            return;
        }

        rememberBaseValues(living);
        applyConfiguredValues(living);
    }

    /*
     * ============================================================
     * 判断 NetCraft 生物
     * ============================================================
     */

    public boolean isNetCraftEntity(
            LivingEntity entity
    ) {

        ResourceLocation id =
                ForgeRegistries.ENTITY_TYPES.getKey(
                        entity.getType()
                );

        return id != null
                && "netcraft".equalsIgnoreCase(
                id.getNamespace()
        );
    }

    /*
     * ============================================================
     * 获取实体 ID
     * ============================================================
     */

    public String getEntityId(
            LivingEntity entity
    ) {

        ResourceLocation id =
                ForgeRegistries.ENTITY_TYPES.getKey(
                        entity.getType()
                );

        if (id == null) {
            return null;
        }

        return id.toString();
    }

    /*
     * ============================================================
     * 保存原始 Attribute
     * ============================================================
     */

    public void rememberBaseValues(
            LivingEntity entity
    ) {

        String entityId =
                getEntityId(entity);

        if (entityId == null) {
            return;
        }

        Map<String, Double> base =
                baseValues.computeIfAbsent(
                        entityId,
                        ignored -> new LinkedHashMap<>()
                );

        for (Map.Entry<String, Attribute> entry :
                SUPPORTED_ATTRIBUTES.entrySet()) {

            String key =
                    entry.getKey();

            Attribute attribute =
                    entry.getValue();

            /*
             * 已经记录过就不重新记录。
             */
            if (base.containsKey(key)) {
                continue;
            }

            AttributeInstance instance =
                    entity.getAttribute(attribute);

            if (instance == null) {
                continue;
            }

            double value =
                    instance.getBaseValue();

            if (Double.isNaN(value)
                    || Double.isInfinite(value)) {

                continue;
            }

            base.put(
                    key,
                    value
            );
        }
    }

    /*
     * ============================================================
     * 应用配置
     * ============================================================
     */

    public void applyConfiguredValues(
            LivingEntity entity
    ) {

        if (!isNetCraftEntity(entity)) {
            return;
        }

        String entityId =
                getEntityId(entity);

        if (entityId == null) {
            return;
        }

        NetCraftConfig config =
                NetCraftToolkit.getConfig();

        if (config == null) {
            return;
        }

        Map<String, Double> configured =
                config.getEntityValues()
                        .get(entityId);

        if (configured == null
                || configured.isEmpty()) {

            return;
        }

        /*
         * 第一次应用之前必须保存原始值。
         */
        rememberBaseValues(entity);

        /*
         * 保存修改前最大生命。
         */
        float oldMaxHealth =
                entity.getMaxHealth();

        float oldHealth =
                entity.getHealth();

        double healthRatio =
                1.0D;

        if (oldMaxHealth > 0.0F) {

            healthRatio =
                    oldHealth / oldMaxHealth;

            if (healthRatio < 0.0D) {
                healthRatio = 0.0D;
            }

            if (healthRatio > 1.0D) {
                healthRatio = 1.0D;
            }
        }

        boolean changed =
                false;

        /*
         * 逐个设置 Attribute。
         */
        for (Map.Entry<String, Attribute> entry :
                SUPPORTED_ATTRIBUTES.entrySet()) {

            String key =
                    entry.getKey();

            Attribute attribute =
                    entry.getValue();

            Double value =
                    configured.get(key);

            if (value == null) {
                continue;
            }

            /*
             * -1 表示保持原值。
             */
            if (value < 0.0D) {
                continue;
            }

            if (Double.isNaN(value)
                    || Double.isInfinite(value)) {

                continue;
            }

            AttributeInstance instance =
                    entity.getAttribute(attribute);

            if (instance == null) {
                continue;
            }

            /*
             * 直接设置 BaseValue。
             *
             * 不使用 add。
             */
            instance.setBaseValue(value);

            changed = true;
        }

        if (!changed) {
            return;
        }

        /*
         * ========================================================
         * 最大生命变化
         * ========================================================
         *
         * 例如：
         *
         * 原来：
         *
         * 最大血量 100
         * 当前血量 40
         *
         * 比例 = 40%
         *
         * 修改：
         *
         * 最大血量 1000
         *
         * 最后：
         *
         * 当前血量 400
         */
        float newMaxHealth =
                entity.getMaxHealth();

        if (newMaxHealth > 0.0F) {

            float newHealth =
                    (float) (
                            newMaxHealth
                                    * healthRatio
                    );

            if (newHealth < 0.0F) {
                newHealth = 0.0F;
            }

            if (newHealth > newMaxHealth) {
                newHealth = newMaxHealth;
            }

            entity.setHealth(
                    newHealth
            );
        }

        initializedEntities.put(
                entity.getId(),
                true
        );
    }

    /*
     * ============================================================
     * 恢复原始 Attribute
     * ============================================================
     *
     * 热加载时使用。
     */

    public void restoreBaseValues(
            LivingEntity entity
    ) {

        String entityId =
                getEntityId(entity);

        if (entityId == null) {
            return;
        }

        Map<String, Double> base =
                baseValues.get(entityId);

        if (base == null) {
            return;
        }

        for (Map.Entry<String, Attribute> entry :
                SUPPORTED_ATTRIBUTES.entrySet()) {

            String key =
                    entry.getKey();

            Attribute attribute =
                    entry.getValue();

            Double value =
                    base.get(key);

            if (value == null
                    || value < 0.0D) {

                continue;
            }

            AttributeInstance instance =
                    entity.getAttribute(attribute);

            if (instance == null) {
                continue;
            }

            instance.setBaseValue(
                    value
            );
        }
    }

    /*
     * ============================================================
     * 热加载全部实体
     * ============================================================
     */

    public void reloadAllEntities() {

        if (NetCraftToolkit.getConfig() == null) {
            return;
        }

        var server =
                net.minecraftforge.server.ServerLifecycleHooks
                        .getCurrentServer();

        if (server == null) {
            return;
        }

        /*
         * 这个操作必须在 Minecraft 主线程执行。
         */
        server.execute(() -> {

            int count = 0;

            for (var level :
                    server.getAllLevels()) {

                for (var entity :
                        level.getEntities().getAll()) {

                    if (!(entity
                            instanceof LivingEntity living)) {

                        continue;
                    }

                    if (!isNetCraftEntity(living)) {
                        continue;
                    }

                    /*
                     * 先恢复原值。
                     */
                    restoreBaseValues(
                            living
                    );

                    /*
                     * 再重新应用配置。
                     */
                    applyConfiguredValues(
                            living
                    );

                    count++;
                }
            }

            LOGGER.info(
                    "[NetCraftToolkit] 已重新应用 {} 个 NetCraft 生物属性",
                    count
            );
        });
    }

    /*
     * ============================================================
     * 手动设置一个属性
     * ============================================================
     */

    public boolean setAttribute(
            LivingEntity entity,
            String key,
            double value
    ) {

        if (entity == null
                || key == null) {

            return false;
        }

        Attribute attribute =
                SUPPORTED_ATTRIBUTES.get(
                        key.toLowerCase(Locale.ROOT)
                );

        if (attribute == null) {
            return false;
        }

        if (Double.isNaN(value)
                || Double.isInfinite(value)
                || value < 0.0D) {

            return false;
        }

        AttributeInstance instance =
                entity.getAttribute(attribute);

        if (instance == null) {
            return false;
        }

        rememberBaseValues(entity);

        instance.setBaseValue(
                value
        );

        return true;
    }

    /*
     * ============================================================
     * 获取原始值
     * ============================================================
     */

    public Double getBaseValue(
            LivingEntity entity,
            String key
    ) {

        String entityId =
                getEntityId(entity);

        if (entityId == null
                || key == null) {

            return null;
        }

        Map<String, Double> values =
                baseValues.get(entityId);

        if (values == null) {
            return null;
        }

        return values.get(
                key.toLowerCase(Locale.ROOT)
        );
    }

    /*
     * ============================================================
     * 清理
     * ============================================================
     */

    public void clear() {

        baseValues.clear();
        initializedEntities.clear();
    }

    /*
     * ============================================================
     * Getter
     * ============================================================
     */

    public Map<String, Map<String, Double>>
    getBaseValues() {

        return baseValues;
    }

    public static Map<String, Attribute>
    getSupportedAttributes() {

        return SUPPORTED_ATTRIBUTES;
    }
}
