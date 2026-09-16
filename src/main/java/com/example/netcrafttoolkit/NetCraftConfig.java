package com.example.netcrafttoolkit;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.attributes.DefaultAttributes;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import org.slf4j.Logger;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * NetCraft Toolkit 配置管理器。
 *
 * 只服务 NetCraft。
 *
 * 配置文件：
 *
 * config/netcrafttoolkit/netcraft-attributes.toml
 *
 * 结构：
 *
 * Boss
 * ├─ 属性
 * └─ 掉落
 *
 * 精英
 * ├─ 属性
 * └─ 掉落
 *
 * 小怪
 * ├─ 属性
 * └─ 掉落
 *
 * 装备
 */
public class NetCraftConfig {

    private static final Logger LOGGER =
            LogUtils.getLogger();

    private static final String NETCRAFT =
            "netcraft";

    private static final String CONFIG_DIRECTORY =
            "netcrafttoolkit";

    private static final String CONFIG_FILE =
            "netcraft-attributes.toml";

    private MinecraftServer server;

    private Path configDirectory;

    private Path configFile;

    private Thread watcherThread;

    private final AtomicBoolean watcherRunning =
            new AtomicBoolean(false);

    private final AtomicBoolean reloadPending =
            new AtomicBoolean(false);

    /**
     * 生物属性。
     *
     * entity id ->
     * attribute name -> value
     */
    private final Map<
            String,
            Map<String, Double>>
            entityAttributes =
            new ConcurrentHashMap<>();

    /**
     * 生物分类。
     *
     * boss / elite / mob
     */
    private final Map<String, String>
            entityCategories =
            new ConcurrentHashMap<>();

    /**
     * 中文名称。
     */
    private final Map<String, String>
            entityNames =
            new ConcurrentHashMap<>();

    /**
     * 每个生物自己的掉落配置。
     */
    private final Map<
            String,
            NetCraftDropManager.DropConfig>
            dropConfigs =
            new ConcurrentHashMap<>();

    /**
     * 装备覆盖配置。
     */
    private final Map<
            String,
            Map<String, Double>>
            equipmentOverrides =
            new ConcurrentHashMap<>();

    /**
     * 初始化。
     */
    public void init(MinecraftServer server) {

        this.server = server;

        Path configRoot =
                server.getServerDirectory()
                        .resolve("config");

        this.configDirectory =
                configRoot.resolve(CONFIG_DIRECTORY);

        this.configFile =
                configDirectory.resolve(CONFIG_FILE);

        try {

            Files.createDirectories(
                    configDirectory
            );

        } catch (Exception e) {

            LOGGER.error(
                    "[NetCraftToolkit] 无法创建配置目录。",
                    e
            );
        }
    }

    public MinecraftServer getServer() {
        return server;
    }

    public Path getConfigFile() {
        return configFile;
    }

    /**
     * 加载配置。
     */
    public synchronized void load() {

        if (server == null ||
                configFile == null) {

            LOGGER.error(
                    "[NetCraftToolkit] 配置管理器尚未初始化。"
            );

            return;
        }

        try {

            if (!Files.exists(configFile)) {

                generateDefaultConfig();
            }

            readConfig();

            syncDropManager();

            LOGGER.info(
                    "[NetCraftToolkit] 已加载 {} 个 NetCraft 生物属性配置。",
                    entityAttributes.size()
            );

            LOGGER.info(
                    "[NetCraftToolkit] 已加载 {} 个 NetCraft 生物掉落配置。",
                    dropConfigs.size()
            );

        } catch (Exception e) {

            LOGGER.error(
                    "[NetCraftToolkit] 加载配置失败。",
                    e
            );
        }
    }

    /**
     * 启动热重载。
     */
    public synchronized void startWatching() {

        if (watcherRunning.get()) {

            return;
        }

        watcherRunning.set(true);

        watcherThread =
                new Thread(
                        this::watchLoop,
                        "NetCraftToolkit-ConfigWatcher"
                );

        watcherThread.setDaemon(true);

        watcherThread.start();

        LOGGER.info(
                "[NetCraftToolkit] 配置热重载监听已启动。"
        );
    }

    /**
     * 停止热重载。
     */
    public synchronized void stopWatching() {

        watcherRunning.set(false);

        if (watcherThread != null) {

            watcherThread.interrupt();

            watcherThread = null;
        }
    }

    /**
     * 配置监听线程。
     */
    private void watchLoop() {

        long lastModified = 0L;

        while (watcherRunning.get()) {

            try {

                Thread.sleep(1000L);

            } catch (InterruptedException e) {

                Thread.currentThread().interrupt();

                break;
            }

            try {

                if (!Files.exists(configFile)) {

                    continue;
                }

                long modified =
                        Files.getLastModifiedTime(
                                configFile
                        ).toMillis();

                if (modified <= lastModified) {

                    continue;
                }

                lastModified = modified;

                if (!reloadPending.compareAndSet(
                        false,
                        true
                )) {

                    continue;
                }

                MinecraftServer currentServer =
                        server;

                if (currentServer == null) {

                    reloadPending.set(false);

                    continue;
                }

                currentServer.execute(() -> {

                    try {

                        LOGGER.info(
                                "[NetCraftToolkit] 检测到配置文件变化，正在热重载。"
                        );

                        load();

                        if (NetCraftToolkit
                                .getAttributeManager()
                                != null) {

                            NetCraftToolkit
                                    .getAttributeManager()
                                    .reloadAllEntities();
                        }

                    } finally {

                        reloadPending.set(false);
                    }
                });

            } catch (Exception e) {

                LOGGER.error(
                        "[NetCraftToolkit] 配置热重载失败。",
                        e
                );
            }
        }
    }

    /**
     * ============================================================
     * 生成默认配置
     * ============================================================
     */
    private void generateDefaultConfig()
            throws Exception {

        List<EntityInfo> entities =
                scanNetCraftEntities();

        StringBuilder out =
                new StringBuilder();

        out.append(
                "# ============================================================\n"
        );

        out.append(
                "# NetCraft Toolkit\n"
        );

        out.append(
                "# NetCraft 生物属性 / 掉落配置\n"
        );

        out.append(
                "# Minecraft 1.20.1 / Forge 47.4.13\n"
        );

        out.append(
                "# ============================================================\n\n"
        );

        out.append(
                "# 本配置文件只服务 NetCraft。\n"
        );

        out.append(
                "# Minecraft 原版生物不会出现在这里。\n"
        );

        out.append(
                "#\n"
        );

        out.append(
                "# 属性数值：直接填写你想要的最终数值。\n"
        );

        out.append(
                "# -1：表示不修改该项。\n\n"
        );

        /*
         * =========================================================
         * Boss
         * =========================================================
         */

        appendCategoryHeader(
                out,
                "一、Boss"
        );

        for (EntityInfo entity : entities) {

            if (!"boss".equals(
                    entity.category
            )) {

                continue;
            }

            appendEntity(
                    out,
                    "boss",
                    entity
            );
        }

        /*
         * =========================================================
         * Elite
         * =========================================================
         */

        appendCategoryHeader(
                out,
                "二、精英"
        );

        for (EntityInfo entity : entities) {

            if (!"elite".equals(
                    entity.category
            )) {

                continue;
            }

            appendEntity(
                    out,
                    "elite",
                    entity
            );
        }

        /*
         * =========================================================
         * Mob
         * =========================================================
         */

        appendCategoryHeader(
                out,
                "三、小怪"
        );

        for (EntityInfo entity : entities) {

            if (!"mob".equals(
                    entity.category
            )) {

                continue;
            }

            appendEntity(
                    out,
                    "mob",
                    entity
            );
        }

        /*
         * =========================================================
         * Equipment
         * =========================================================
         */

        appendEquipmentSection(
                out
        );

        Files.writeString(
                configFile,
                out.toString(),
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE
        );

        LOGGER.info(
                "[NetCraftToolkit] 已生成默认 NetCraft 配置：{}",
                configFile
        );
    }

    /**
     * 分类标题。
     */
    private void appendCategoryHeader(
            StringBuilder out,
            String title
    ) {

        out.append(
                "\n# ============================================================\n"
        );

        out.append(
                "# "
        );

        out.append(title);

        out.append(
                "\n"
        );

        out.append(
                "# ============================================================\n\n"
        );
    }

    /**
     * 输出单个生物。
     */
    private void appendEntity(
            StringBuilder out,
            String category,
            EntityInfo entity
    ) {

        out.append(
                "# ------------------------------------------------------------\n"
        );

        out.append(
                "# 【"
        );

        out.append(
                entity.displayName
        );

        out.append(
                "】\n"
        );

        out.append(
                "# 注册名："
        );

        out.append(
                entity.id
        );

        out.append(
                "\n"
        );

        out.append(
                "# ------------------------------------------------------------\n\n"
        );

        out.append(
                "["
        );

        out.append(category);

        out.append(
                ".\""
        );

        out.append(
                entity.id
        );

        out.append(
                "\"]\n\n"
        );

        appendDoubleAttribute(
                out,
                "max_health",
                "最大生命值",
                entity.defaults.get(
                        "max_health"
                )
        );

        appendDoubleAttribute(
                out,
                "attack_damage",
                "攻击伤害",
                entity.defaults.get(
                        "attack_damage"
                )
        );

        appendDoubleAttribute(
                out,
                "movement_speed",
                "移动速度",
                entity.defaults.get(
                        "movement_speed"
                )
        );

        appendDoubleAttribute(
                out,
                "armor",
                "护甲值",
                entity.defaults.get(
                        "armor"
                )
        );

        appendDoubleAttribute(
                out,
                "attack_speed",
                "攻击速度",
                entity.defaults.get(
                        "attack_speed"
                )
        );

        appendDoubleAttribute(
                out,
                "knockback_resistance",
                "击退抗性",
                entity.defaults.get(
                        "knockback_resistance"
                )
        );

        appendDoubleAttribute(
                out,
                "follow_range",
                "跟随 / 索敌距离",
                entity.defaults.get(
                        "follow_range"
                )
        );

        /*
         * BossBase 专属。
         */
        if ("boss".equals(category)) {

            appendIntegerAttribute(
                    out,
                    "base_damage",
                    "NetCraft BossBase 基础伤害",
                    entity.baseDamage
            );

            appendIntegerAttribute(
                    out,
                    "base_defense",
                    "NetCraft BossBase 基础防御",
                    entity.baseDefense
            );
        }

        out.append(
                "# ============================================================\n"
        );

        out.append(
                "# 掉落物\n"
        );

        out.append(
                "# ============================================================\n"
        );

        out.append(
                "# replace = true：删除该生物全部 NetCraft 原掉落，只使用下面配置\n"
        );

        out.append(
                "# replace = false：保留该生物全部 NetCraft 原掉落，并增加下面配置\n"
        );

        out.append(
                "#\n"
        );

        out.append(
                "# 注意：这里修改的是这个生物的全部掉落事件。\n"
        );

        out.append(
                "# 不是只修改两个新增物品。\n\n"
        );

        out.append(
                "["
        );

        out.append(category);

        out.append(
                ".\""
        );

        out.append(entity.id);

        out.append(
                "\".drops]\n"
        );

        out.append(
                "replace = false\n\n"
        );

        out.append(
                "# 添加自定义掉落示例：\n"
        );

        out.append(
                "# [["
        );

        out.append(category);

        out.append(
                ".\""
        );

        out.append(entity.id);

        out.append(
                "\".drops.items]]\n"
        );

        out.append(
                "# item = \"minecraft:diamond\"\n"
        );

        out.append(
                "# min_count = 1\n"
        );

        out.append(
                "# max_count = 3\n"
        );

        out.append(
                "# chance = 1.0\n\n"
        );
    }

    /**
     * 输出小数属性。
     */
    private void appendDoubleAttribute(
            StringBuilder out,
            String key,
            String comment,
            Double defaultValue
    ) {

        double value =
                defaultValue == null
                        ? -1D
                        : defaultValue;

        out.append(
                "# "
        );

        out.append(comment);

        out.append(
                "\n"
        );

        out.append(
                "# NetCraft 默认值："
        );

        out.append(
                formatNumber(value)
        );

        out.append(
                "\n"
        );

        out.append(
                key
        );

        out.append(
                " = "
        );

        out.append(
                formatNumber(value)
        );

        out.append(
                "\n\n"
        );
    }

    /**
     * 输出整数属性。
     */
    private void appendIntegerAttribute(
            StringBuilder out,
            String key,
            String comment,
            int defaultValue
    ) {

        out.append(
                "# "
        );

        out.append(comment);

        out.append(
                "\n"
        );

        out.append(
                "# NetCraft 默认值："
        );

        if (defaultValue < 0) {

            out.append(
                    "未读取"
            );

        } else {

            out.append(
                    defaultValue
            );
        }

        out.append(
                "\n"
        );

        out.append(
                key
        );

        out.append(
                " = "
        );

        out.append(
                defaultValue
        );

        out.append(
                "\n\n"
        );
    }

    /**
     * 装备区域。
     */
    private void appendEquipmentSection(
            StringBuilder out
    ) {

        appendCategoryHeader(
                out,
                "四、NetCraft 装备"
        );

        out.append(
                "# 职业：\n"
        );

        out.append(
                "# knight / archer / mage / summoner / dragonknight / wararcher\n\n"
        );

        out.append(
                "# 位置：\n"
        );

        out.append(
                "# mainhand / offhand / helmet / chestplate / leggings / boots\n\n"
        );

        out.append(
                "# 属性：\n"
        );

        out.append(
                "# meleeDamage      = 近战伤害\n"
        );

        out.append(
                "# rangedDamage     = 远程伤害\n"
        );

        out.append(
                "# magicDamage      = 魔法伤害\n"
        );

        out.append(
                "# physicalDefense  = 物理防御\n"
        );

        out.append(
                "# magicDefense     = 魔法防御\n"
        );

        out.append(
                "# health           = 生命值\n"
        );

        out.append(
                "# armor            = 护甲\n"
        );

        out.append(
                "# -1 = 不修改\n\n"
        );

        out.append(
                "# 示例：\n"
        );

        out.append(
                "# [equipment.\"knight.t1.mainhand\"]\n"
        );

        out.append(
                "# meleeDamage = -1\n"
        );

        out.append(
                "# rangedDamage = -1\n"
        );

        out.append(
                "# magicDamage = -1\n"
        );

        out.append(
                "# physicalDefense = -1\n"
        );

        out.append(
                "# magicDefense = -1\n"
        );

        out.append(
                "# health = -1\n"
        );

        out.append(
                "# armor = -1\n"
        );
    }

    /**
     * ============================================================
     * 扫描 NetCraft 实体
     * ============================================================
     */
    private List<EntityInfo>
    scanNetCraftEntities() {

        List<EntityInfo> result =
                new ArrayList<>();

        for (Map.Entry<
                ResourceLocation,
                EntityType<?>>
                registryEntry :
                BuiltInRegistries
                        .ENTITY_TYPE
                        .entrySet()) {

            ResourceLocation id =
                    registryEntry.getKey();

            if (id == null ||
                    !NETCRAFT.equals(
                            id.getNamespace()
                    )) {

                continue;
            }

            EntityType<?> type =
                    registryEntry.getValue();

            if (type == null) {

                continue;
            }

            /*
             * 没有 LivingEntity 默认属性的：
             *
             * 弹幕
             * 箭
             * 投射物
             * 火焰领域
             *
             * 等不属于生物的实体全部跳过。
             */
            if (!DefaultAttributes.hasSupplier(
                    type
            )) {

                continue;
            }

            String idString =
                    id.toString();

            String category =
                    detectCategory(
                            idString
                    );

            String displayName =
                    getChineseEntityName(
                            idString,
                            type
                    );

            Map<String, Double> defaults =
                    readDefaultAttributes(
                            type
                    );

            int baseDamage = -1;
            int baseDefense = -1;

            if ("boss".equals(category)) {

                int[] bossValues =
                        readBossDefaults(
                                type
                        );

                baseDamage =
                        bossValues[0];

                baseDefense =
                        bossValues[1];
            }

            result.add(
                    new EntityInfo(
                            idString,
                            displayName,
                            category,
                            defaults,
                            baseDamage,
                            baseDefense
                    )
            );
        }

        result.sort(
                Comparator
                        .comparingInt(
                                e ->
                                        categoryOrder(
                                                e.category
                                        )
                        )
                        .thenComparing(
                                e -> e.displayName
                        )
                        .thenComparing(
                                e -> e.id
                        )
        );

        return result;
    }

    /**
     * NetCraft 1.4.18 的分类。
     *
     * 这是根据它实际的注册命名规则：
     *
     * entity_boss_
     * entity_elite_
     * entity_minion_
     *
     * 来分。
     */
    private String detectCategory(
            String id
    ) {

        String path =
                id.substring(
                        id.indexOf(':') + 1
                );

        if (path.startsWith(
                "entity_boss_"
        )) {

            return "boss";
        }

        if (path.startsWith(
                "entity_elite_"
        )) {

            return "elite";
        }

        if (path.startsWith(
                "entity_minion_"
        )) {

            return "mob";
        }

        /*
         * NetCraft 里其他 LivingEntity，
         * 不放进 Boss / Elite。
         *
         * 统一归小怪区。
         */
        return "mob";
    }

    private int categoryOrder(
            String category
    ) {

        if ("boss".equals(category)) {
            return 0;
        }

        if ("elite".equals(category)) {
            return 1;
        }

        return 2;
    }

    /**
     * 读取默认 Minecraft 属性。
     */
    @SuppressWarnings("unchecked")
    private Map<String, Double>
    readDefaultAttributes(
            EntityType<?> type
    ) {

        Map<String, Double> result =
                new LinkedHashMap<>();

        result.put(
                "max_health",
                -1D
        );

        result.put(
                "attack_damage",
                -1D
        );

        result.put(
                "movement_speed",
                -1D
        );

        result.put(
                "armor",
                -1D
        );

        result.put(
                "attack_speed",
                -1D
        );

        result.put(
                "knockback_resistance",
                -1D
        );

        result.put(
                "follow_range",
                -1D
        );

        try {

            AttributeSupplier supplier =
                    DefaultAttributes.getSupplier(
                            (EntityType<? extends LivingEntity>)
                                    type
                    );

            readAttribute(
                    result,
                    supplier,
                    "max_health",
                    Attributes.MAX_HEALTH
            );

            readAttribute(
                    result,
                    supplier,
                    "attack_damage",
                    Attributes.ATTACK_DAMAGE
            );

            readAttribute(
                    result,
                    supplier,
                    "movement_speed",
                    Attributes.MOVEMENT_SPEED
            );

            readAttribute(
                    result,
                    supplier,
                    "armor",
                    Attributes.ARMOR
            );

            readAttribute(
                    result,
                    supplier,
                    "attack_speed",
                    Attributes.ATTACK_SPEED
            );

            readAttribute(
                    result,
                    supplier,
                    "knockback_resistance",
                    Attributes.KNOCKBACK_RESISTANCE
            );

            readAttribute(
                    result,
                    supplier,
                    "follow_range",
                    Attributes.FOLLOW_RANGE
            );

        } catch (Throwable e) {

            LOGGER.warn(
                    "[NetCraftToolkit] 读取默认属性失败：{}",
                    type
            );
        }

        return result;
    }

    /**
     * 读取 AttributeSupplier 默认值。
     */
    private void readAttribute(
            Map<String, Double> result,
            AttributeSupplier supplier,
            String key,
            Attribute attribute
    ) {

        try {

            if (supplier.hasAttribute(
                    attribute
            )) {

                result.put(
                        key,
                        supplier.getValue(
                                attribute
                        )
                );
            }

        } catch (Throwable ignored) {
        }
    }

    /**
     * 读取 BossBase 默认值。
     *
     * 不直接依赖 NetCraft Java 类。
     *
     * 使用反射：
     *
     * getBaseDamage()
     * getBaseDefense()
     */
    private int[] readBossDefaults(
            EntityType<?> type
    ) {

        int baseDamage = -1;
        int baseDefense = -1;

        if (server == null) {

            return new int[]{
                    -1,
                    -1
            };
        }

        try {

            ServerLevel level =
                    server.overworld();

            if (level == null) {

                return new int[]{
                        -1,
                        -1
                };
            }

            Entity entity =
                    type.create(level);

            if (entity == null) {

                return new int[]{
                        -1,
                        -1
                };
            }

            Class<?> clazz =
                    entity.getClass();

            MethodResult damage =
                    invokeNoArgs(
                            clazz,
                            entity,
                            "getBaseDamage"
                    );

            MethodResult defense =
                    invokeNoArgs(
                            clazz,
                            entity,
                            "getBaseDefense"
                    );

            if (damage.success &&
                    damage.value instanceof Number) {

                baseDamage =
                        ((Number) damage.value)
                                .intValue();
            }

            if (defense.success &&
                    defense.value instanceof Number) {

                baseDefense =
                        ((Number) defense.value)
                                .intValue();
            }

            /*
             * 没有加入世界，所以不需要 remove。
             */

        } catch (Throwable e) {

            LOGGER.debug(
                    "[NetCraftToolkit] 读取 BossBase 默认值失败。",
                    e
            );
        }

        return new int[]{
                baseDamage,
                baseDefense
        };
    }

    /**
     * 反射调用无参数方法。
     */
    private MethodResult invokeNoArgs(
            Class<?> clazz,
            Object instance,
            String name
    ) {

        Class<?> current = clazz;

        while (current != null) {

            try {

                java.lang.reflect.Method method =
                        current.getDeclaredMethod(
                                name
                        );

                method.setAccessible(
                        true
                );

                return new MethodResult(
                        true,
                        method.invoke(
                                instance
                        )
                );

            } catch (
                    NoSuchMethodException e
            ) {

                current =
                        current.getSuperclass();

            } catch (Throwable e) {

                return new MethodResult(
                        false,
                        null
                );
            }
        }

        return new MethodResult(
                false,
                null
        );
    }

    /**
     * ============================================================
     * 中文名称
     * ============================================================
     */
    private String getChineseEntityName(
            String entityId,
            EntityType<?> type
    ) {

        /*
         * 第一优先：
         *
         * NetCraft 自己的 zh_cn.json。
         */
        String translated =
                readNetCraftChineseName(
                        entityId
                );

        if (translated != null &&
                !translated.isBlank()) {

            return translated;
        }

        /*
         * 第二优先：
         * Minecraft EntityType 描述。
         */
        try {

            Component component =
                    type.getDescription();

            if (component != null) {

                String text =
                        component.getString();

                if (text != null &&
                        !text.isBlank()) {

                    return text;
                }
            }

        } catch (Throwable ignored) {
        }

        /*
         * 最后使用 ID。
         */
        return prettifyId(
                entityId
        );
    }

    /**
     * 从 NetCraft 1.4.18 的
     * assets/netcraft/lang/zh_cn.json
     * 读取中文名称。
     */
    private String readNetCraftChineseName(
            String entityId
    ) {

        if (server == null) {

            return null;
        }

        try {

            ResourceLocation resource =
                    new ResourceLocation(
                            NETCRAFT,
                            "lang/zh_cn.json"
                    );

            Optional<net.minecraft.server.packs.resources.Resource>
                    optional =
                    server.getResourceManager()
                            .getResource(
                                    resource
                            );

            if (optional.isEmpty()) {

                return null;
            }

            try (
                    InputStream input =
                            optional.get()
                                    .open();

                    InputStreamReader reader =
                            new InputStreamReader(
                                    input,
                                    StandardCharsets.UTF_8
                            )
            ) {

                JsonObject json =
                        JsonParser.parseReader(
                                reader
                        ).getAsJsonObject();

                String path =
                        entityId.substring(
                                entityId.indexOf(':') + 1
                        );

                String key =
                        "entity."
                                + NETCRAFT
                                + "."
                                + path;

                JsonElement element =
                        json.get(key);

                if (element != null &&
                        element.isJsonPrimitive()) {

                    return element.getAsString();
                }
            }

        } catch (Throwable e) {

            LOGGER.debug(
                    "[NetCraftToolkit] 读取 NetCraft 中文语言文件失败。",
                    e
            );
        }

        return null;
    }

    private String prettifyId(
            String id
    ) {

        int colon =
                id.indexOf(':');

        if (colon >= 0) {

            id =
                    id.substring(
                            colon + 1
                    );
        }

        String[] parts =
                id.split("_");

        StringBuilder result =
                new StringBuilder();

        for (String part : parts) {

            if (part.isBlank()) {

                continue;
            }

            if (result.length() > 0) {

                result.append(' ');
            }

            result.append(
                    Character.toUpperCase(
                            part.charAt(0)
                    )
            );

            if (part.length() > 1) {

                result.append(
                        part.substring(1)
                );
            }
        }

        return result.toString();
    }

    /**
     * ============================================================
     * 读取配置
     * ============================================================
     */
    private synchronized void readConfig()
            throws Exception {

        entityAttributes.clear();
        entityCategories.clear();
        entityNames.clear();
        dropConfigs.clear();
        equipmentOverrides.clear();

        List<String> lines =
                Files.readAllLines(
                        configFile,
                        StandardCharsets.UTF_8
                );

        String currentCategory = null;

        String currentEntity = null;

        String currentSection = null;

        DropBuilder currentDrop = null;

        List<NetCraftDropManager.DropEntry>
                currentDropList = null;

        String currentEquipment = null;

        for (String raw : lines) {

            String line =
                    raw.trim();

            /*
             * 空行。
             */
            if (line.isEmpty()) {

                continue;
            }

            /*
             * 注释。
             */
            if (line.startsWith("#")) {

                continue;
            }

            /*
             * =====================================================
             * [[boss."xxx".drops.items]]
             * =====================================================
             */
            if (line.startsWith("[[") &&
                    line.endsWith("]]")) {

                ParsedSection section =
                        parseSection(
                                line.substring(
                                        2,
                                        line.length() - 2
                                )
                        );

                if (section == null) {

                    continue;
                }

                finishDrop(
                        currentDrop,
                        currentDropList
                );

                currentDrop =
                        new DropBuilder();

                currentDropList =
                        dropConfigs
                                .computeIfAbsent(
                                        section.entityId,
                                        ignored ->
                                                new NetCraftDropManager
                                                        .DropConfig(
                                                                false,
                                                                new ArrayList<>()
                                                        )
                                )
                                .entries();

                currentCategory =
                        section.category;

                currentEntity =
                        section.entityId;

                currentSection =
                        "drops.items";

                currentEquipment = null;

                continue;
            }

            /*
             * =====================================================
             * [section]
             * =====================================================
             */
            if (line.startsWith("[") &&
                    line.endsWith("]")) {

                finishDrop(
                        currentDrop,
                        currentDropList
                );

                currentDrop = null;
                currentDropList = null;

                ParsedSection section =
                        parseSection(
                                line.substring(
                                        1,
                                        line.length() - 1
                                )
                        );

                if (section == null) {

                    currentCategory = null;
                    currentEntity = null;
                    currentSection = null;
                    currentEquipment = null;

                    continue;
                }

                currentCategory =
                        section.category;

                currentEntity =
                        section.entityId;

                currentSection =
                        section.subSection;

                currentEquipment =
                        "equipment".equals(
                                section.category
                        )
                                ? section.entityId
                                : null;

                if (currentEntity != null &&
                        !"equipment".equals(
                                currentCategory
                        )) {

                    entityCategories.put(
                            currentEntity,
                            currentCategory
                    );

                    entityNames.put(
                            currentEntity,
                            getChineseEntityNameById(
                                    currentEntity
                            )
                    );
                }

                continue;
            }

            /*
             * key=value
             */
            int equals =
                    line.indexOf('=');

            if (equals < 0) {

                continue;
            }

            String key =
                    line.substring(
                            0,
                            equals
                    ).trim();

            String value =
                    line.substring(
                            equals + 1
                    ).trim();

            /*
             * 去掉行尾注释。
             */
            value =
                    stripInlineComment(
                            value
                    );

            /*
             * =====================================================
             * 掉落总开关
             * =====================================================
             */
            if ("drops".equals(
                    currentSection
            ) &&
                    "replace".equals(key) &&
                    currentEntity != null) {

                boolean replace =
                        Boolean.parseBoolean(
                                unquote(value)
                        );

                NetCraftDropManager.DropConfig
                        old =
                                dropConfigs.get(
                                        currentEntity
                                );

                List<NetCraftDropManager.DropEntry>
                        list =
                                old == null
                                        ? new ArrayList<>()
                                        : old.entries();

                dropConfigs.put(
                        currentEntity,
                        new NetCraftDropManager.DropConfig(
                                replace,
                                list
                        )
                );

                currentDropList =
                        list;

                continue;
            }

            /*
             * =====================================================
             * 掉落项目
             * =====================================================
             */
            if ("drops.items".equals(
                    currentSection
            ) &&
                    currentDrop != null) {

                switch (key) {

                    case "item" ->
                            currentDrop.item =
                                    unquote(value);

                    case "min_count" ->
                            currentDrop.minCount =
                                    parseInt(
                                            value,
                                            1
                                    );

                    case "max_count" ->
                            currentDrop.maxCount =
                                    parseInt(
                                            value,
                                            1
                                    );

                    case "chance" ->
                            currentDrop.chance =
                                    parseDouble(
                                            value,
                                            1.0D
                                    );

                    default -> {
                    }
                }

                continue;
            }

            /*
             * =====================================================
             * 装备
             * =====================================================
             */
            if ("equipment".equals(
                    currentSection
            ) &&
                    currentEquipment != null) {

                Double number =
                        parseNullableDouble(
                                value
                        );

                if (number != null) {

                    equipmentOverrides
                            .computeIfAbsent(
                                    currentEquipment,
                                    ignored ->
                                            new ConcurrentHashMap<>()
                            )
                            .put(
                                    key,
                                    number
                            );
                }

                continue;
            }

            /*
             * =====================================================
             * 生物属性
             * =====================================================
             */
            if (currentEntity != null &&
                    isEntityAttribute(key)) {

                Double number =
                        parseNullableDouble(
                                value
                        );

                if (number == null) {

                    continue;
                }

                entityAttributes
                        .computeIfAbsent(
                                currentEntity,
                                ignored ->
                                        new ConcurrentHashMap<>()
                        )
                        .put(
                                key,
                                number
                        );
            }
        }

        /*
         * 文件结束时最后一个掉落项目。
         */
        finishDrop(
                currentDrop,
                currentDropList
        );

        /*
         * 防止旧配置里出现非 NetCraft。
         */
        removeNonNetCraftEntries();
    }

    /**
     * 完成一个掉落项目。
     */
    private void finishDrop(
            DropBuilder builder,
            List<NetCraftDropManager.DropEntry> list
    ) {

        if (builder == null ||
                list == null) {

            return;
        }

        if (builder.item == null ||
                builder.item.isBlank()) {

            return;
        }

        int min =
                Math.max(
                        1,
                        builder.minCount
                );

        int max =
                Math.max(
                        min,
                        builder.maxCount
                );

        double chance =
                Math.max(
                        0D,
                        Math.min(
                                1D,
                                builder.chance
                        )
                );

        list.add(
                new NetCraftDropManager.DropEntry(
                        builder.item,
                        min,
                        max,
                        chance
                )
        );
    }

    /**
     * 删除所有非 NetCraft 配置。
     */
    private void removeNonNetCraftEntries() {

        entityAttributes.keySet()
                .removeIf(
                        id ->
                                !isNetCraftId(id)
                );

        entityCategories.keySet()
                .removeIf(
                        id ->
                                !isNetCraftId(id)
                );

        entityNames.keySet()
                .removeIf(
                        id ->
                                !isNetCraftId(id)
                );

        dropConfigs.keySet()
                .removeIf(
                        id ->
                                !isNetCraftId(id)
                );
    }

    private boolean isNetCraftId(
            String id
    ) {

        return id != null &&
                id.startsWith(
                        NETCRAFT + ":"
                );
    }

    /**
     * section 解析。
     */
    private ParsedSection parseSection(
            String text
    ) {

        String body =
                text.trim();

        /*
         * equipment."knight.t1.mainhand"
         */
        if (body.startsWith(
                "equipment."
        )) {

            String rest =
                    body.substring(
                            "equipment.".length()
                    );

            String id =
                    extractQuoted(
                            rest
                    );

            if (id == null) {

                id =
                        unquote(rest);
            }

            return new ParsedSection(
                    "equipment",
                    id,
                    "equipment"
            );
        }

        String[] categories = {
                "boss",
                "elite",
                "mob"
        };

        for (String category :
                categories) {

            String prefix =
                    category + ".";

            if (!body.startsWith(
                    prefix
            )) {

                continue;
            }

            String rest =
                    body.substring(
                            prefix.length()
                    );

            String entityId =
                    extractQuoted(
                            rest
                    );

            if (entityId == null) {

                return null;
            }

            int quoteEnd =
                    findClosingQuote(
                            rest
                    );

            String suffix =
                    quoteEnd >= 0
                            ? rest.substring(
                                    quoteEnd + 1
                            )
                            : "";

            if (suffix.startsWith(
                    ".drops.items"
            )) {

                return new ParsedSection(
                        category,
                        entityId,
                        "drops.items"
                );
            }

            if (suffix.startsWith(
                    ".drops"
            )) {

                return new ParsedSection(
                        category,
                        entityId,
                        "drops"
                );
            }

            return new ParsedSection(
                    category,
                    entityId,
                    "entity"
            );
        }

        return null;
    }

    /**
     * 获取引号中的字符串。
     */
    private String extractQuoted(
            String text
    ) {

        int start =
                text.indexOf('"');

        if (start < 0) {

            return null;
        }

        int end =
                text.indexOf(
                        '"',
                        start + 1
                );

        if (end < 0) {

            return null;
        }

        return text.substring(
                start + 1,
                end
        );
    }

    private int findClosingQuote(
            String text
    ) {

        int start =
                text.indexOf('"');

        if (start < 0) {

            return -1;
        }

        return text.indexOf(
                '"',
                start + 1
        );
    }

    /**
     * 同步掉落管理器。
     */
    private void syncDropManager() {

        NetCraftDropManager manager =
                NetCraftToolkit.getDropManager();

        if (manager == null) {

            return;
        }

        manager.clear();

        for (Map.Entry<
                String,
                NetCraftDropManager.DropConfig>
                entry :
                dropConfigs.entrySet()) {

            if (!isNetCraftId(
                    entry.getKey()
            )) {

                continue;
            }

            manager.setDropConfig(
                    entry.getKey(),
                    entry.getValue()
            );
        }
    }

    /**
     * ============================================================
     * 对外 API
     * ============================================================
     */

    public Map<String, Double>
    getEntityAttributes(
            String entityId
    ) {

        Map<String, Double> values =
                entityAttributes.get(
                        entityId
                );

        if (values == null) {

            return Collections.emptyMap();
        }

        return Collections.unmodifiableMap(
                values
        );
    }

    public Map<String, Double>
    getEntityConfig(
            String entityId
    ) {

        return getEntityAttributes(
                entityId
        );
    }

    public String getEntityCategory(
            String entityId
    ) {

        return entityCategories.getOrDefault(
                entityId,
                "mob"
        );
    }

    public String getEntityName(
            String entityId
    ) {

        return entityNames.getOrDefault(
                entityId,
                entityId
        );
    }

    public Map<String,
            NetCraftDropManager.DropConfig>
    getDropConfigs() {

        return Collections.unmodifiableMap(
                dropConfigs
        );
    }

    public NetCraftDropManager.DropConfig
    getDropConfig(
            String entityId
    ) {

        return dropConfigs.get(
                entityId
        );
    }

    public Map<String,
            Map<String, Double>>
    getEquipmentOverrides() {

        return Collections.unmodifiableMap(
                equipmentOverrides
        );
    }

    public Map<String,
            Map<String, Double>>
    getEquipmentDefaults() {

        return Collections.emptyMap();
    }

    public Set<String>
    getEquipmentStructNames() {

        return Collections.unmodifiableSet(
                equipmentOverrides.keySet()
        );
    }

    /**
     * ============================================================
     * 工具方法
     * ============================================================
     */

    private boolean isEntityAttribute(
            String key
    ) {

        return switch (key) {

            case "max_health",
                 "attack_damage",
                 "movement_speed",
                 "armor",
                 "attack_speed",
                 "knockback_resistance",
                 "follow_range",
                 "base_damage",
                 "base_defense" ->
                    true;

            default ->
                    false;
        };
    }

    private String stripInlineComment(
            String value
    ) {

        boolean quoted = false;

        for (int i = 0;
             i < value.length();
             i++) {

            char c =
                    value.charAt(i);

            if (c == '"') {

                quoted = !quoted;

                continue;
            }

            if (c == '#' &&
                    !quoted) {

                return value
                        .substring(
                                0,
                                i
                        )
                        .trim();
            }
        }

        return value;
    }

    private String unquote(
            String value
    ) {

        String result =
                value.trim();

        if (result.length() >= 2 &&
                result.startsWith("\"") &&
                result.endsWith("\"")) {

            return result.substring(
                    1,
                    result.length() - 1
            );
        }

        return result;
    }

    private int parseInt(
            String value,
            int fallback
    ) {

        try {

            return Integer.parseInt(
                    unquote(value)
            );

        } catch (Exception e) {

            return fallback;
        }
    }

    private double parseDouble(
            String value,
            double fallback
    ) {

        Double parsed =
                parseNullableDouble(
                        value
                );

        return parsed == null
                ? fallback
                : parsed;
    }

    private Double parseNullableDouble(
            String value
    ) {

        try {

            return Double.parseDouble(
                    unquote(value)
            );

        } catch (Exception e) {

            return null;
        }
    }

    private String formatNumber(
            double value
    ) {

        if (Double.isNaN(value) ||
                Double.isInfinite(value)) {

            return "-1.0";
        }

        if (value == Math.rint(value)) {

            return String.format(
                    Locale.ROOT,
                    "%.1f",
                    value
            );
        }

        String result =
                String.format(
                        Locale.ROOT,
                        "%.6f",
                        value
                );

        while (result.endsWith("0")) {

            result =
                    result.substring(
                            0,
                            result.length() - 1
                    );
        }

        if (result.endsWith(".")) {

            result += "0";
        }

        return result;
    }

    private String getChineseEntityNameById(
            String entityId
    ) {

        ResourceLocation id =
                ResourceLocation.tryParse(
                        entityId
                );

        if (id == null) {

            return entityId;
        }

        EntityType<?> type =
                BuiltInRegistries
                        .ENTITY_TYPE
                        .get(id);

        if (type == null) {

            return entityId;
        }

        return getChineseEntityName(
                entityId,
                type
        );
    }

    /**
     * 清理。
     */
    public void clear() {

        entityAttributes.clear();
        entityCategories.clear();
        entityNames.clear();
        dropConfigs.clear();
        equipmentOverrides.clear();
    }

    @SubscribeEvent
    public void onServerStopping(
            ServerStoppingEvent event
    ) {

        stopWatching();

        clear();
    }

    /**
     * 生物信息。
     */
    private static final class EntityInfo {

        private final String id;

        private final String displayName;

        private final String category;

        private final Map<String, Double>
                defaults;

        private final int baseDamage;

        private final int baseDefense;

        private EntityInfo(
                String id,
                String displayName,
                String category,
                Map<String, Double> defaults,
                int baseDamage,
                int baseDefense
        ) {

            this.id = id;
            this.displayName = displayName;
            this.category = category;
            this.defaults = defaults;
            this.baseDamage = baseDamage;
            this.baseDefense = baseDefense;
        }
    }

    /**
     * TOML section。
     */
    private static final class ParsedSection {

        private final String category;

        private final String entityId;

        private final String subSection;

        private ParsedSection(
                String category,
                String entityId,
                String subSection
        ) {

            this.category = category;
            this.entityId = entityId;
            this.subSection = subSection;
        }
    }

    /**
     * 临时掉落项目。
     */
    private static final class DropBuilder {

        private String item;

        private int minCount = 1;

        private int maxCount = 1;

        private double chance = 1.0D;
    }

    /**
     * 反射结果。
     */
    private static final class MethodResult {

        private final boolean success;

        private final Object value;

        private MethodResult(
                boolean success,
                Object value
        ) {

            this.success = success;
            this.value = value;
        }
    }
}
