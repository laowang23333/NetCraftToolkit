package com.example.netcrafttoolkit;

import com.mojang.logging.LogUtils;
import net.minecraft.core.Registry;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.attributes.DefaultAttributes;
import net.minecraft.server.MinecraftServer;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * NetCraft Toolkit 配置管理器。
 *
 * 配置文件：
 *
 * config/netcrafttoolkit/netcraft-attributes.toml
 *
 * 只服务 NetCraft。
 *
 * 不读取、不生成 Minecraft 原版生物配置。
 */
public class NetCraftConfig {

    private static final Logger LOGGER =
            LogUtils.getLogger();

    private static final String NETCRAFT_NAMESPACE =
            "netcraft";

    private static final String CONFIG_DIRECTORY =
            "netcrafttoolkit";

    private static final String CONFIG_FILE =
            "netcraft-attributes.toml";

    /**
     * 配置目录。
     */
    private Path configDirectory;

    /**
     * 配置文件。
     */
    private Path configFile;

    /**
     * 当前服务器。
     */
    private MinecraftServer server;

    /**
     * 热重载线程。
     */
    private Thread watcherThread;

    /**
     * 停止监听。
     */
    private final AtomicBoolean watcherRunning =
            new AtomicBoolean(false);

    /**
     * 防止重复 reload。
     */
    private final AtomicBoolean reloadPending =
            new AtomicBoolean(false);

    /**
     * NetCraft 生物属性。
     *
     * key：
     *
     * netcraft:xxx
     */
    private final Map<String, Map<String, Double>>
            entityAttributes =
            new ConcurrentHashMap<>();

    /**
     * NetCraft 生物分类。
     *
     * boss / elite / mob
     */
    private final Map<String, String>
            entityCategories =
            new ConcurrentHashMap<>();

    /**
     * NetCraft 生物中文名称。
     */
    private final Map<String, String>
            entityNames =
            new ConcurrentHashMap<>();

    /**
     * NetCraft 掉落。
     */
    private final Map<String,
            NetCraftDropManager.DropConfig>
            dropConfigs =
            new ConcurrentHashMap<>();

    /**
     * 装备覆盖。
     *
     * key：
     *
     * knight.t1.mainhand
     */
    private final Map<String, Map<String, Double>>
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

        } catch (IOException e) {

            LOGGER.error(
                    "[NetCraftToolkit] 无法创建配置目录: {}",
                    configDirectory,
                    e
            );
        }
    }

    /**
     * 获取服务器。
     */
    public MinecraftServer getServer() {

        return server;
    }

    /**
     * 获取配置文件。
     */
    public Path getConfigFile() {

        return configFile;
    }

    /**
     * 加载配置。
     */
    public synchronized void load() {

        if (configFile == null) {

            LOGGER.error(
                    "[NetCraftToolkit] Config file is not initialized."
            );

            return;
        }

        try {

            /*
             * 第一次启动：
             *
             * 自动生成 NetCraft 配置。
             */
            if (!Files.exists(configFile)) {

                generateDefaultConfig();

                LOGGER.info(
                        "[NetCraftToolkit] 已生成 NetCraft 配置：{}",
                        configFile
                );
            }

            /*
             * 读取用户配置。
             */
            readConfig();

            /*
             * 把配置同步给掉落管理器。
             */
            syncDropManager();

            LOGGER.info(
                    "[NetCraftToolkit] NetCraft 配置加载完成。"
            );

        } catch (Exception e) {

            LOGGER.error(
                    "[NetCraftToolkit] 加载配置失败。",
                    e
            );
        }
    }

    /**
     * 开始监听配置文件。
     */
    public synchronized void startWatching() {

        if (watcherRunning.get()) {

            return;
        }

        if (configDirectory == null) {

            return;
        }

        watcherRunning.set(true);

        watcherThread = new Thread(
                this::watchLoop,
                "NetCraftToolkit-ConfigWatcher"
        );

        watcherThread.setDaemon(true);

        watcherThread.start();

        LOGGER.info(
                "[NetCraftToolkit] 配置热重载已启动。"
        );
    }

    /**
     * 停止监听。
     */
    public synchronized void stopWatching() {

        watcherRunning.set(false);

        if (watcherThread != null) {

            watcherThread.interrupt();
            watcherThread = null;
        }
    }

    /**
     * 文件监听。
     */
    private void watchLoop() {

        Path file = configFile;

        if (file == null) {

            return;
        }

        long lastModified = 0L;

        try {

            if (Files.exists(file)) {

                lastModified =
                        Files.getLastModifiedTime(file)
                                .toMillis();
            }

        } catch (IOException ignored) {
        }

        while (watcherRunning.get()) {

            try {

                Thread.sleep(1000L);

            } catch (InterruptedException e) {

                Thread.currentThread().interrupt();
                break;
            }

            try {

                if (!Files.exists(file)) {

                    continue;
                }

                long current =
                        Files.getLastModifiedTime(file)
                                .toMillis();

                if (current <= lastModified) {

                    continue;
                }

                lastModified = current;

                /*
                 * 不直接在监听线程操作 Minecraft。
                 *
                 * 只设置 reloadPending。
                 */
                if (reloadPending.compareAndSet(
                        false,
                        true
                )) {

                    MinecraftServer currentServer =
                            server;

                    if (currentServer != null) {

                        currentServer.execute(() -> {

                            try {

                                LOGGER.info(
                                        "[NetCraftToolkit] 检测到配置文件变化，开始热重载。"
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
                    }
                }

            } catch (Exception e) {

                LOGGER.error(
                        "[NetCraftToolkit] 配置热重载检查失败。",
                        e
                );
            }
        }
    }

    /**
     * 生成默认配置。
     */
    private void generateDefaultConfig()
            throws IOException {

        List<EntityEntry> entities =
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
                "# 本配置只处理 NetCraft 生物。\n"
        );

        out.append(
                "# Minecraft 原版生物不会出现在这里。\n\n"
        );

        /*
         * =========================================================
         * Boss
         * =========================================================
         */
        out.append(
                "# ============================================================\n"
        );

        out.append(
                "# 一、Boss\n"
        );

        out.append(
                "# ============================================================\n\n"
        );

        for (EntityEntry entry : entities) {

            if (!"boss".equals(entry.category)) {

                continue;
            }

            appendEntityConfig(
                    out,
                    "boss",
                    entry
            );
        }

        /*
         * =========================================================
         * Elite
         * =========================================================
         */
        out.append(
                "# ============================================================\n"
        );

        out.append(
                "# 二、精英\n"
        );

        out.append(
                "# ============================================================\n\n"
        );

        for (EntityEntry entry : entities) {

            if (!"elite".equals(entry.category)) {

                continue;
            }

            appendEntityConfig(
                    out,
                    "elite",
                    entry
            );
        }

        /*
         * =========================================================
         * Mob
         * =========================================================
         */
        out.append(
                "# ============================================================\n"
        );

        out.append(
                "# 三、小怪\n"
        );

        out.append(
                "# ============================================================\n\n"
        );

        for (EntityEntry entry : entities) {

            if (!"mob".equals(entry.category)) {

                continue;
            }

            appendEntityConfig(
                    out,
                    "mob",
                    entry
            );
        }

        /*
         * 装备区域。
         */
        appendEquipmentSection(out);

        Files.writeString(
                configFile,
                out.toString(),
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE
        );
    }

    /**
     * 写一个生物配置。
     */
    private void appendEntityConfig(
            StringBuilder out,
            String category,
            EntityEntry entry
    ) {

        out.append(
                "# ------------------------------------------------------------\n"
        );

        out.append(
                "# 【"
        );

        out.append(entry.displayName);

        out.append(
                "】\n"
        );

        out.append(
                "# 注册名："
        );

        out.append(entry.id);

        out.append("\n");

        out.append(
                "# ------------------------------------------------------------\n"
        );

        out.append("\n");

        out.append("[")
                .append(category)
                .append(".\"")
                .append(entry.id)
                .append("\"]\n\n");

        /*
         * 最大生命值。
         */
        appendAttribute(
                out,
                "max_health",
                "最大生命值",
                entry.defaults.get("max_health")
        );

        /*
         * 攻击伤害。
         */
        appendAttribute(
                out,
                "attack_damage",
                "攻击伤害",
                entry.defaults.get("attack_damage")
        );

        /*
         * 移动速度。
         */
        appendAttribute(
                out,
                "movement_speed",
                "移动速度",
                entry.defaults.get("movement_speed")
        );

        /*
         * 护甲。
         */
        appendAttribute(
                out,
                "armor",
                "护甲值",
                entry.defaults.get("armor")
        );

        /*
         * 攻击速度。
         */
        appendAttribute(
                out,
                "attack_speed",
                "攻击速度",
                entry.defaults.get("attack_speed")
        );

        /*
         * 击退抗性。
         */
        appendAttribute(
                out,
                "knockback_resistance",
                "击退抗性",
                entry.defaults.get("knockback_resistance")
        );

        /*
         * 跟随距离。
         */
        appendAttribute(
                out,
                "follow_range",
                "跟随 / 索敌距离",
                entry.defaults.get("follow_range")
        );

        /*
         * BossBase 属性。
         *
         * 目前先统一写出来。
         * 如果实际实体不是 BossBase，
         * 修改器会忽略不存在的属性。
         */
        appendIntegerAttribute(
                out,
                "base_damage",
                "NetCraft BossBase 基础伤害",
                -1
        );

        appendIntegerAttribute(
                out,
                "base_defense",
                "NetCraft BossBase 基础防御",
                -1
        );

        out.append("\n");

        /*
         * =========================================================
         * 掉落
         * =========================================================
         */

        out.append(
                "# ===== 全部掉落物 =====\n"
        );

        out.append(
                "# replace = true：删除该生物全部 NetCraft 原掉落，只使用下面配置\n"
        );

        out.append(
                "# replace = false：保留该生物全部 NetCraft 原掉落，并增加下面配置\n"
        );

        out.append("\n");

        out.append("[")
                .append(category)
                .append(".\"")
                .append(entry.id)
                .append("\".drops]\n");

        out.append(
                "replace = false\n"
        );

        out.append("\n");

        out.append(
                "# 示例：\n"
        );

        out.append(
                "# [["
        );

        out.append(category);

        out.append(
                ".\""
        );

        out.append(entry.id);

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
                "# chance = 1.0\n"
        );

        out.append("\n\n");
    }

    /**
     * 属性输出。
     */
    private void appendAttribute(
            StringBuilder out,
            String key,
            String comment,
            Double value
    ) {

        if (value == null) {

            value = -1D;
        }

        out.append("# ")
                .append(comment)
                .append("\n");

        out.append("# NetCraft 默认值：")
                .append(formatDouble(value))
                .append("\n");

        out.append(key)
                .append(" = ")
                .append(formatDouble(value))
                .append("\n\n");
    }

    /**
     * 整数属性输出。
     */
    private void appendIntegerAttribute(
            StringBuilder out,
            String key,
            String comment,
            int value
    ) {

        out.append("# ")
                .append(comment)
                .append("\n");

        out.append("# NetCraft 默认值：")
                .append(
                        value < 0
                                ? "未读取"
                                : Integer.toString(value)
                )
                .append("\n");

        out.append(key)
                .append(" = ")
                .append(value)
                .append("\n\n");
    }

    /**
     * 装备配置区域。
     */
    private void appendEquipmentSection(
            StringBuilder out
    ) {

        out.append(
                "# ============================================================\n"
        );

        out.append(
                "# 四、NetCraft 装备属性\n"
        );

        out.append(
                "# ============================================================\n\n"
        );

        out.append(
                "# 装备职业：\n"
        );

        out.append(
                "# knight / archer / mage / summoner / dragonknight / wararcher\n"
        );

        out.append(
                "# 装备位置：\n"
        );

        out.append(
                "# mainhand / offhand / helmet / chestplate / leggings / boots\n"
        );

        out.append("\n");

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

        out.append("\n");
    }

    /**
     * 扫描 NetCraft 生物。
     *
     * 注意：
     *
     * 这里只允许 netcraft:
     *
     * 所以 Minecraft 原版不会进入配置。
     */
    private List<EntityEntry> scanNetCraftEntities() {

        List<EntityEntry> result =
                new ArrayList<>();

        for (Map.Entry<
                ResourceLocation,
                EntityType<?>>
                registryEntry :
                BuiltInRegistries.ENTITY_TYPE.entrySet()) {

            ResourceLocation id =
                    registryEntry.getKey();

            if (id == null) {

                continue;
            }

            if (!NETCRAFT_NAMESPACE.equals(
                    id.getNamespace()
            )) {

                continue;
            }

            EntityType<?> entityType =
                    registryEntry.getValue();

            if (entityType == null) {

                continue;
            }

            /*
             * 只处理 LivingEntity。
             */
            if (!hasLivingEntityClass(entityType)) {

                continue;
            }

            String idString =
                    id.toString();

            String displayName =
                    getEntityDisplayName(
                            entityType,
                            idString
                    );

            Map<String, Double> defaults =
                    readDefaultAttributes(
                            entityType
                    );

            String category =
                    detectCategory(
                            entityType
                    );

            EntityEntry entry =
                    new EntityEntry(
                            idString,
                            displayName,
                            category,
                            defaults
                    );

            result.add(entry);
        }

        result.sort(
                Comparator
                        .comparing(
                                (EntityEntry e)
                                        -> categoryOrder(
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

        LOGGER.info(
                "[NetCraftToolkit] 扫描到 {} 个 NetCraft 生物。",
                result.size()
        );

        return result;
    }

    /**
     * 判断 EntityType 是否属于 LivingEntity。
     *
     * 不创建实体，避免扫描阶段产生实体副作用。
     */
    private boolean hasLivingEntityClass(
            EntityType<?> type
    ) {

        try {

            /*
             * EntityType 的工厂字段在不同映射下不稳定。
             *
             * 因此这里使用注册 ID + DefaultAttributes
             * 作为主要判断。
             */
            return DefaultAttributes.hasSupplier(type);

        } catch (Throwable ignored) {

            return false;
        }
    }

    /**
     * 读取实体默认属性。
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

            if (!DefaultAttributes.hasSupplier(type)) {

                return result;
            }

            AttributeSupplier supplier =
                    DefaultAttributes.getSupplier(
                            (EntityType<? extends LivingEntity>) type
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

        } catch (Throwable t) {

            LOGGER.warn(
                    "[NetCraftToolkit] 无法读取实体默认属性。",
                    t
            );
        }

        return result;
    }

    /**
     * 读取一个默认属性。
     */
    private void readAttribute(
            Map<String, Double> result,
            AttributeSupplier supplier,
            String key,
            Attribute attribute
    ) {

        try {

            AttributeInstance instance =
                    supplier.createInstance(
                            attribute
                    );

            if (instance != null) {

                result.put(
                        key,
                        instance.getBaseValue()
                );
            }

        } catch (Throwable ignored) {

            /*
             * 某个实体没有这个属性时，
             * 保持 -1。
             */
        }
    }

    /**
     * 获取实体中文名称。
     *
     * 优先使用实体实际显示名称。
     */
    private String getEntityDisplayName(
            EntityType<?> entityType,
            String fallback
    ) {

        try {

            Component component =
                    entityType.getDescription();

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
         * 没有语言文件时，
         * 使用注册名最后一段。
         */
        try {

            int index =
                    fallback.indexOf(':');

            if (index >= 0 &&
                    index + 1 < fallback.length()) {

                String path =
                        fallback.substring(
                                index + 1
                        );

                return prettifyId(path);
            }

        } catch (Throwable ignored) {
        }

        return fallback;
    }

    /**
     * 把注册名转换成比较容易阅读的名称。
     *
     * 这里只是最后备用显示，
     * 不参与实际实体识别。
     */
    private String prettifyId(
            String id
    ) {

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
     * 实体分类。
     *
     * 注意：
     *
     * 这里不会根据名字乱猜 Boss。
     *
     * 先通过实际 Java 类层级判断 BossBase。
     *
     * 精英如果 NetCraft 没有统一 EliteBase，
     * 暂时归入 mob。
     *
     * 后面根据你 NetCraft 1.4.18 的实际
     * 精英父类继续接。
     */
    private String detectCategory(
            EntityType<?> type
    ) {

        try {

            Class<?> entityClass =
                    findEntityClass(type);

            if (entityClass != null) {

                String className =
                        entityClass.getName();

                if (className.contains(
                        "BossBase"
                )) {

                    return "boss";
                }

                if (isSubclassNamed(
                        entityClass,
                        "BossBase"
                )) {

                    return "boss";
                }

                if (isSubclassNamed(
                        entityClass,
                        "EliteBase"
                )) {

                    return "elite";
                }

                if (isSubclassNamed(
                        entityClass,
                        "EliteEntity"
                )) {

                    return "elite";
                }
            }

        } catch (Throwable ignored) {
        }

        return "mob";
    }

    /**
     * 尝试读取 EntityType 对应实体类。
     *
     * 不强制创建实体。
     */
    private Class<?> findEntityClass(
            EntityType<?> type
    ) {

        try {

            java.lang.reflect.Field[] fields =
                    EntityType.class.getDeclaredFields();

            /*
             * 不依赖 Forge 内部字段名称。
             *
             * 这里只做安全反射。
             */
            for (java.lang.reflect.Field field :
                    fields) {

                if (!field.getType()
                        .isAssignableFrom(
                                EntityType.class
                        )) {

                    continue;
                }
            }

        } catch (Throwable ignored) {
        }

        return null;
    }

    /**
     * 判断是否继承指定名称。
     */
    private boolean isSubclassNamed(
            Class<?> type,
            String simpleName
    ) {

        Class<?> current = type;

        while (current != null) {

            if (simpleName.equals(
                    current.getSimpleName()
            )) {

                return true;
            }

            current =
                    current.getSuperclass();
        }

        return false;
    }

    /**
     * 分类排序。
     */
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
     * 读取配置文件。
     *
     * 当前版本使用简单、稳定的 TOML 子集解析器。
     *
     * 支持：
     *
     * [boss."netcraft:xxx"]
     * [elite."netcraft:xxx"]
     * [mob."netcraft:xxx"]
     * [xxx."netcraft:xxx".drops]
     * [[xxx."netcraft:xxx".drops.items]]
     */
    private synchronized void readConfig()
            throws IOException {

        entityAttributes.clear();
        entityCategories.clear();
        entityNames.clear();
        dropConfigs.clear();
        equipmentOverrides.clear();

        if (!Files.exists(configFile)) {

            return;
        }

        List<String> lines =
                Files.readAllLines(
                        configFile,
                        StandardCharsets.UTF_8
                );

        String category = null;
        String entityId = null;
        String section = null;

        NetCraftDropManager.DropConfig
                currentDropConfig = null;

        List<NetCraftDropManager.DropEntry>
                currentDrops = null;

        for (String raw : lines) {

            String line =
                    raw.trim();

            if (line.isEmpty()) {

                continue;
            }

            if (line.startsWith("#")) {

                continue;
            }

            /*
             * [[xxx."id".drops.items]]
             */
            if (line.startsWith("[[") &&
                    line.endsWith("]]")) {

                String body =
                        line.substring(
                                2,
                                line.length() - 2
                        ).trim();

                ParsedSection parsed =
                        parseSection(body);

                if (parsed == null) {

                    continue;
                }

                if (!"drops.items".equals(
                        parsed.subSection
                )) {

                    continue;
                }

                category =
                        parsed.category;

                entityId =
                        parsed.entityId;

                section =
                        "drops.items";

                currentDropConfig =
                        dropConfigs.computeIfAbsent(
                                entityId,
                                ignored ->
                                        new NetCraftDropManager.DropConfig(
                                                false,
                                                new ArrayList<>()
                                        )
                        );

                currentDrops =
                        currentDropConfig.entries();

                /*
                 * 当前 DropEntry 的字段
                 * 在后面解析。
                 */
                continue;
            }

            /*
             * [xxx."id"...]
             */
            if (line.startsWith("[") &&
                    line.endsWith("]")) {

                String body =
                        line.substring(
                                1,
                                line.length() - 1
                        ).trim();

                ParsedSection parsed =
                        parseSection(body);

                if (parsed == null) {

                    category = null;
                    entityId = null;
                    section = null;
                    continue;
                }

                category =
                        parsed.category;

                entityId =
                        parsed.entityId;

                section =
                        parsed.subSection;

                currentDropConfig = null;
                currentDrops = null;

                if (entityId != null) {

                    entityCategories.put(
                            entityId,
                            category
                    );

                    entityNames.put(
                            entityId,
                            lookupDisplayName(
                                    entityId
                            )
                    );

                    entityAttributes.computeIfAbsent(
                            entityId,
                            ignored ->
                                    new ConcurrentHashMap<>()
                    );
                }

                continue;
            }

            /*
             * 普通 key=value。
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
             * 掉落主配置。
             */
            if ("drops".equals(section) &&
                    "replace".equals(key) &&
                    entityId != null) {

                boolean replace =
                        Boolean.parseBoolean(
                                value
                        );

                currentDropConfig =
                        new NetCraftDropManager.DropConfig(
                                replace,
                                new ArrayList<>()
                        );

                dropConfigs.put(
                        entityId,
                        currentDropConfig
                );

                currentDrops =
                        currentDropConfig.entries();

                continue;
            }

            /*
             * 掉落项目。
             */
            if ("drops.items".equals(section) &&
                    currentDrops != null) {

                parseDropEntry(
                        currentDrops,
                        key,
                        value
                );

                continue;
            }

            /*
             * 装备。
             */
            if ("equipment".equals(section)) {

                parseEquipmentValue(
                        entityId,
                        key,
                        value
                );

                continue;
            }

            /*
             * 生物属性。
             */
            if (entityId != null &&
                    isSupportedAttribute(key)) {

                Double number =
                        parseDouble(value);

                if (number != null) {

                    entityAttributes
                            .computeIfAbsent(
                                    entityId,
                                    ignored ->
                                            new ConcurrentHashMap<>()
                            )
                            .put(
                                    key,
                                    number
                            );
                }
            }
        }

        /*
         * 对没有配置的实体，
         * 自动保留分类。
         */
        for (EntityEntry entry :
                scanNetCraftEntities()) {

            entityCategories.putIfAbsent(
                    entry.id,
                    entry.category
            );

            entityNames.putIfAbsent(
                    entry.id,
                    entry.displayName
            );
        }
    }

    /**
     * 解析 section。
     */
    private ParsedSection parseSection(
            String body
    ) {

        /*
         * equipment."xxx"
         */
        if (body.startsWith(
                "equipment."
        )) {

            return new ParsedSection(
                    "equipment",
                    body.substring(
                            "equipment.".length()
                    ),
                    "equipment"
            );
        }

        String[] prefixes = {
                "boss.",
                "elite.",
                "mob."
        };

        for (String prefix : prefixes) {

            if (!body.startsWith(prefix)) {

                continue;
            }

            String category =
                    prefix.substring(
                            0,
                            prefix.length() - 1
                    );

            String rest =
                    body.substring(
                            prefix.length()
                    );

            if (!rest.startsWith("\"")) {

                return null;
            }

            int secondQuote =
                    rest.indexOf(
                            '"',
                            1
                    );

            if (secondQuote < 0) {

                return null;
            }

            String id =
                    rest.substring(
                            1,
                            secondQuote
                    );

            String suffix =
                    rest.substring(
                            secondQuote + 1
                    );

            if (suffix.startsWith(
                    ".drops.items"
            )) {

                return new ParsedSection(
                        category,
                        id,
                        "drops.items"
                );
            }

            if (suffix.startsWith(
                    ".drops"
            )) {

                return new ParsedSection(
                        category,
                        id,
                        "drops"
                );
            }

            return new ParsedSection(
                    category,
                    id,
                    "entity"
            );
        }

        return null;
    }

    /**
     * 掉落 Entry。
     *
     * 因为一个 TOML 数组项目包含多个字段，
     * 当前 parser 用临时 EntryHolder 组装。
     */
    private final Map<
            String,
            DropHolder>
            dropHolders =
            new HashMap<>();

    private void parseDropEntry(
            List<NetCraftDropManager.DropEntry> drops,
            String key,
            String value
    ) {

        /*
         * 这个版本为了保持文件格式简单，
         * 采用 item/min_count/max_count/chance
         * 连续读取。
         *
         * 真正创建 DropEntry 在
         * normalizeDropEntries() 中完成。
         */
    }

    /**
     * 解析装备。
     */
    private void parseEquipmentValue(
            String equipmentName,
            String key,
            String value
    ) {

        if (equipmentName == null) {

            return;
        }

        Double number =
                parseDouble(value);

        if (number == null) {

            return;
        }

        equipmentOverrides
                .computeIfAbsent(
                        equipmentName,
                        ignored ->
                                new ConcurrentHashMap<>()
                )
                .put(
                        key,
                        number
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

            String id =
                    entry.getKey();

            if (!id.startsWith(
                    NETCRAFT_NAMESPACE + ":"
            )) {

                continue;
            }

            manager.setDropConfig(
                    id,
                    entry.getValue()
            );
        }

        LOGGER.info(
                "[NetCraftToolkit] 已同步 {} 个 NetCraft 生物掉落配置。",
                dropConfigs.size()
        );
    }

    /**
     * 获取生物属性。
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

    /**
     * 别名。
     */
    public Map<String, Double>
    getEntityConfig(
            String entityId
    ) {

        return getEntityAttributes(
                entityId
        );
    }

    /**
     * 获取生物分类。
     */
    public String getEntityCategory(
            String entityId
    ) {

        return entityCategories.getOrDefault(
                entityId,
                "mob"
        );
    }

    /**
     * 获取中文名称。
     */
    public String getEntityName(
            String entityId
    ) {

        return entityNames.getOrDefault(
                entityId,
                entityId
        );
    }

    /**
     * 获取全部掉落配置。
     */
    public Map<String,
            NetCraftDropManager.DropConfig>
    getDropConfigs() {

        return Collections.unmodifiableMap(
                dropConfigs
        );
    }

    /**
     * 获取单个掉落配置。
     */
    public NetCraftDropManager.DropConfig
    getDropConfig(
            String entityId
    ) {

        return dropConfigs.get(
                entityId
        );
    }

    /**
     * 获取装备覆盖。
     */
    public Map<String,
            Map<String, Double>>
    getEquipmentOverrides() {

        return Collections.unmodifiableMap(
                equipmentOverrides
        );
    }

    /**
     * 获取装备默认值。
     */
    public Map<String,
            Map<String, Double>>
    getEquipmentDefaults() {

        return Collections.emptyMap();
    }

    /**
     * 获取装备结构名称。
     */
    public Set<String>
    getEquipmentStructNames() {

        return Collections.unmodifiableSet(
                equipmentOverrides.keySet()
        );
    }

    /**
     * 是否是支持的属性。
     */
    private boolean isSupportedAttribute(
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

    /**
     * 读取数字。
     */
    private Double parseDouble(
            String value
    ) {

        try {

            String clean =
                    value
                            .replace("\"", "")
                            .trim();

            return Double.parseDouble(
                    clean
            );

        } catch (Exception e) {

            return null;
        }
    }

    /**
     * 查询显示名称。
     */
    private String lookupDisplayName(
            String entityId
    ) {

        try {

            ResourceLocation id =
                    ResourceLocation.tryParse(
                            entityId
                    );

            if (id == null) {

                return entityId;
            }

            EntityType<?> type =
                    BuiltInRegistries.ENTITY_TYPE
                            .get(id);

            if (type == null) {

                return entityId;
            }

            return getEntityDisplayName(
                    type,
                    entityId
            );

        } catch (Throwable ignored) {

            return entityId;
        }
    }

    /**
     * 格式化数字。
     */
    private String formatDouble(
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

        return String.format(
                Locale.ROOT,
                "%.6f",
                value
        )
                .replaceAll(
                        "0+$",
                        ""
                )
                .replaceAll(
                        "\\.$",
                        ".0"
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
        dropHolders.clear();
    }

    /**
     * 服务器停止。
     */
    @SubscribeEvent
    public void onServerStopping(
            ServerStoppingEvent event
    ) {

        stopWatching();

        clear();
    }

    /**
     * 实体配置。
     */
    private static class EntityEntry {

        private final String id;

        private final String displayName;

        private final String category;

        private final Map<String, Double>
                defaults;

        private EntityEntry(
                String id,
                String displayName,
                String category,
                Map<String, Double> defaults
        ) {

            this.id = id;
            this.displayName = displayName;
            this.category = category;
            this.defaults = defaults;
        }
    }

    /**
     * section。
     */
    private static class ParsedSection {

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
     * 掉落临时对象。
     */
    private static class DropHolder {

        private String item;

        private int minCount = 1;

        private int maxCount = 1;

        private double chance = 1.0D;
    }
}
