package com.example.netcrafttoolkit;

import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.DefaultAttributes;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.registries.ForgeRegistries;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.regex.Matcher;
import java.util.Comparator;
import java.lang.reflect.Method;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * NetCraft Toolkit 配置管理器。
 *
 * 功能：
 * 1. 自动扫描 NetCraft 生物。
 * 2. 自动生成 world/serverconfig/netcrafttoolkit.toml。
 * 3. 读取 Boss / Elite / Mob 配置。
 * 4. 读取 Minecraft 原生属性修改。
 * 5. 读取 BossBase 的 base_damage / base_defense。
 * 6. 读取紧凑型掉落配置。
 *
 * 掉落格式：
 *
 * [boss."netcraft:xxx".drops]
 * replace = true
 * items = "[minecraft:diamond|1|10|0.5,netcraft:wup|10|25|1]"
 *
 * 其中：
 *
 * [物品ID|最小数量|最大数量|概率]
 *
 * replace = true
 *     清除该生物全部原始物品掉落，只使用这里配置的掉落。
 *
 * replace = false
 *     保留该生物全部原始物品掉落，并追加这里配置的掉落。
 */
public class NetCraftConfig {

    private static final String CONFIG_FILE_NAME = "netcrafttoolkit.toml";

    private MinecraftServer server;
    private Path configFile;

    private ScheduledExecutorService watcher;

    /**
     * 生物属性。
     *
     * entityId ->
     *     attributeName -> value
     */
    private final Map<String, Map<String, Double>> entityAttributes =
            new LinkedHashMap<>();

    /**
     * 生物分类。
     *
     * entityId -> boss / elite / mob
     */
    private final Map<String, String> entityCategories =
            new LinkedHashMap<>();

    /**
     * 生物中文名称。
     *
     * entityId -> 中文名
     */
    private final Map<String, String> entityNames =
            new LinkedHashMap<>();

    /**
     * 掉落配置。
     *
     * entityId -> DropConfig
     */
    private final Map<String, NetCraftDropManager.DropConfig> dropConfigs =
            new LinkedHashMap<>();

    /**
     * 装备覆盖配置。
     *
     * 这里暂时保存原始配置文本结构。
     */
    private final Map<String, Map<String, Double>> equipmentOverrides =
            new LinkedHashMap<>();

    private volatile long lastModified = -1L;

    private volatile boolean generatedInitialConfig = false;

    /**
     * 初始化。
     */
    public void init(MinecraftServer server) {
        this.server = server;

        Path serverDirectory = server.getServerDirectory().toPath();

        /*
         * 配置文件改为当前世界专属的 serverconfig。
         *
         * world/serverconfig/netcrafttoolkit.toml
         */
        Path worldPath = server.getWorldPath(LevelResource.ROOT);
        Path newConfigFile = worldPath
                .resolve("serverconfig")
                .resolve(CONFIG_FILE_NAME);

        /*
         * 兼容旧版本：
         * 如果服务器根目录还有旧的 netcrafttoolkit.toml，
         * 而新的 world/serverconfig 中还没有，就自动迁移。
         * 不覆盖已经存在的新配置。
         */
        Path oldConfigFile = serverDirectory.resolve(CONFIG_FILE_NAME);

        try {
            if (!Files.exists(newConfigFile)
                    && Files.exists(oldConfigFile)) {

                Files.createDirectories(newConfigFile.getParent());

                try {
                    Files.move(
                            oldConfigFile,
                            newConfigFile,
                            StandardCopyOption.REPLACE_EXISTING
                    );
                } catch (IOException moveException) {
                    /*
                     * 某些服务器面板/文件系统可能不允许跨文件系统移动，
                     * 这里退回复制，然后删除旧文件。
                     */
                    Files.copy(
                            oldConfigFile,
                            newConfigFile,
                            StandardCopyOption.REPLACE_EXISTING
                    );
                    Files.deleteIfExists(oldConfigFile);
                }

                NetCraftToolkit.LOGGER.info(
                        "[NetCraftToolkit] Migrated config: {} -> {}",
                        oldConfigFile.toAbsolutePath(),
                        newConfigFile.toAbsolutePath()
                );
            }
        } catch (IOException e) {
            NetCraftToolkit.LOGGER.error(
                    "[NetCraftToolkit] Failed to migrate old config file.",
                    e
            );
        }

        this.configFile = newConfigFile;

        NetCraftToolkit.LOGGER.info(
                "[NetCraftToolkit] Config file: {}",
                configFile.toAbsolutePath()
        );
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
        if (server == null || configFile == null) {
            NetCraftToolkit.LOGGER.error(
                    "[NetCraftToolkit] Cannot load config: server is null."
            );
            return;
        }

        try {
            if (!Files.exists(configFile)) {
                NetCraftToolkit.LOGGER.info(
                        "[NetCraftToolkit] Config does not exist. Generating..."
                );

                generateDefaultConfig();

                generatedInitialConfig = true;
            }

            parseConfig();

            lastModified = Files.getLastModifiedTime(configFile).toMillis();

            syncDropManager();

            NetCraftToolkit.LOGGER.info(
                    "[NetCraftToolkit] Configuration loaded successfully. Entities: {}, drops: {}",
                    entityAttributes.size(),
                    dropConfigs.size()
            );

        } catch (Exception e) {
            NetCraftToolkit.LOGGER.error(
                    "[NetCraftToolkit] Failed to load configuration.",
                    e
            );
        }
    }

    /**
     * 开启热重载。
     */
    public synchronized void startWatching() {
        if (watcher != null && !watcher.isShutdown()) {
            return;
        }

        if (configFile == null) {
            return;
        }

        watcher = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread thread = new Thread(r, "NetCraftToolkit-ConfigWatcher");
            thread.setDaemon(true);
            return thread;
        });

        watcher.scheduleAtFixedRate(
                this::checkForChanges,
                2,
                2,
                TimeUnit.SECONDS
        );

        NetCraftToolkit.LOGGER.info(
                "[NetCraftToolkit] Config watcher started."
        );
    }

    /**
     * 停止热重载。
     */
    public synchronized void stopWatching() {
        if (watcher != null) {
            watcher.shutdownNow();
            watcher = null;
        }
    }

    @SubscribeEvent
    public void onServerStopping(ServerStoppingEvent event) {
        stopWatching();
    }

    /**
     * 检查配置文件是否发生变化。
     */
    private void checkForChanges() {
        try {
            if (configFile == null || !Files.exists(configFile)) {
                return;
            }

            long modified =
                    Files.getLastModifiedTime(configFile).toMillis();

            if (lastModified <= 0L) {
                lastModified = modified;
                return;
            }

            if (modified != lastModified) {
                lastModified = modified;

                NetCraftToolkit.LOGGER.info(
                        "[NetCraftToolkit] Configuration changed. Reloading..."
                );

                /*
                 * 配置监听器运行在独立线程。
                 *
                 * 先重新读取配置并同步掉落配置，然后把已有实体的属性
                 * 重新应用操作提交到 Minecraft 主线程。
                 *
                 * 这样修改 TOML 后，已经存在世界里的 NetCraft 生物
                 * 也会立即使用新的属性，不需要击杀后重新生成。
                 */
                parseConfig();

                syncDropManager();

                MinecraftServer currentServer = this.server;
                if (currentServer != null) {
                    currentServer.execute(() -> {
                        try {
                            NetCraftAttributeManager attributeManager =
                                    NetCraftToolkit.getAttributeManager();

                            if (attributeManager != null) {
                                attributeManager.reloadAllEntities();
                            }

                            NetCraftToolkit.LOGGER.info(
                                    "[NetCraftToolkit] Existing NetCraft entities updated after hot reload."
                            );

                        } catch (Throwable reloadError) {
                            NetCraftToolkit.LOGGER.error(
                                    "[NetCraftToolkit] Failed to update existing entities after hot reload.",
                                    reloadError
                            );
                        }
                    });
                }

                NetCraftToolkit.LOGGER.info(
                        "[NetCraftToolkit] Configuration hot reloaded."
                );
            }

        } catch (Exception e) {
            NetCraftToolkit.LOGGER.error(
                    "[NetCraftToolkit] Failed while checking config changes.",
                    e
            );
        }
    }

    /**
     * 解析整个配置文件。
     *
     * 这是一个针对本项目配置格式编写的轻量 TOML 解析器，
     * 不依赖第三方 TOML 库。
     */
    private synchronized void parseConfig() throws IOException {
        entityAttributes.clear();
        entityCategories.clear();
        entityNames.clear();
        dropConfigs.clear();
        equipmentOverrides.clear();

        if (!Files.exists(configFile)) {
            return;
        }

        List<String> lines = Files.readAllLines(
                configFile,
                StandardCharsets.UTF_8
        );

        String currentSection = "";

        for (String rawLine : lines) {
            if (rawLine == null) {
                continue;
            }

            String line = rawLine.trim();

            if (line.isEmpty()) {
                continue;
            }

            if (line.startsWith("#")) {
                continue;
            }

            /*
             * 去掉行尾注释。
             *
             * 但字符串里的 # 不处理。
             */
            line = removeTrailingComment(line).trim();

            if (line.isEmpty()) {
                continue;
            }

            /*
             * [boss."netcraft:xxx"]
             *
             * [boss."netcraft:xxx".drops]
             *
             * [equipment."xxx"]
             */
            if (line.startsWith("[") && line.endsWith("]")) {
                currentSection = line.substring(
                        1,
                        line.length() - 1
                ).trim();

                continue;
            }

            /*
             * [[...]]
             *
             * 新格式不再使用掉落数组。
             *
             * 如果用户旧配置里还有这种格式，
             * 这里直接忽略，不让它破坏新配置。
             */
            if (line.startsWith("[[") && line.endsWith("]]")) {
                currentSection = "";
                continue;
            }

            int equalsIndex = findEqualsOutsideQuotes(line);

            if (equalsIndex < 0) {
                continue;
            }

            String key = line.substring(
                    0,
                    equalsIndex
            ).trim();

            String value = line.substring(
                    equalsIndex + 1
            ).trim();

            if (key.isEmpty()) {
                continue;
            }

            parseProperty(
                    currentSection,
                    key,
                    value
            );
        }
    }

    /**
     * 解析单个配置属性。
     */
    private void parseProperty(
            String section,
            String key,
            String value
    ) {
        if (section == null || section.isBlank()) {
            return;
        }

        /*
         * 掉落配置必须先判断。
         *
         * [boss."netcraft:xxx".drops] 同时满足 isEntitySection()，
         * 如果先判断普通实体区段，replace/items 就会被当成普通属性，
         * 导致掉落配置根本不会进入 parseDropProperty()。
         */
        if (section.endsWith(".drops")) {
            parseDropProperty(
                    section,
                    key,
                    value
            );

            return;
        }

        /*
         * Boss / Elite / Mob 生物配置。
         */
        if (isEntitySection(section)) {
            parseEntityProperty(
                    section,
                    key,
                    value
            );

            return;
        }

        /*
         * 装备配置。
         */
        if (section.startsWith("equipment.")) {
            parseEquipmentProperty(
                    section,
                    key,
                    value
            );
        }
    }

    /**
     * 判断是否为：
     *
     * boss."netcraft:xxx"
     * elite."netcraft:xxx"
     * mob."netcraft:xxx"
     */
    private boolean isEntitySection(String section) {
        return section.startsWith("boss.")
                || section.startsWith("elite.")
                || section.startsWith("mob.");
    }

    /**
     * 解析 Boss / Elite / Mob 属性。
     */
    private void parseEntityProperty(
            String section,
            String key,
            String value
    ) {
        String category = getSectionCategory(section);

        if (category == null) {
            return;
        }

        String entityId = extractEntityId(section);

        if (entityId == null || entityId.isBlank()) {
            return;
        }

        /*
         * 只读取拥有可读取标准属性的 NetCraft 生物。
         *
         * Boss 技能实体如果没有 DefaultAttributes 数据，
         * 不进入本工具的属性/掉落配置系统。
         */
        if (!isReadableNetCraftEntity(entityId)) {
            return;
        }

        entityCategories.put(
                entityId,
                category
        );

        /*
         * drops 不属于普通属性。
         */
        if ("drops".equalsIgnoreCase(key)) {
            return;
        }

        Double number = parseDouble(value);

        if (number == null) {
            return;
        }

        entityAttributes
                .computeIfAbsent(
                        entityId,
                        id -> new LinkedHashMap<>()
                )
                .put(
                        key,
                        number
                );
    }

    /**
     * 解析掉落。
     *
     * 新格式：
     *
     * [boss."netcraft:xxx".drops]
     *
     * replace = true
     *
     * items = "[minecraft:diamond|1|10|0.5,netcraft:wup|10|25|1]"
     */
    private void parseDropProperty(
            String section,
            String key,
            String value
    ) {
        if (!section.endsWith(".drops")) {
            return;
        }

        String entityId = extractEntityIdFromDropSection(section);

        if (entityId == null || entityId.isBlank()) {
            return;
        }

        /*
         * 只允许 NetCraft 生物。
         */
        if (!entityId.startsWith("netcraft:")) {
            return;
        }

        /*
         * 没有标准属性数据的技能/Boss 实体不读取掉落配置。
         */
        if (!isReadableNetCraftEntity(entityId)) {
            return;
        }

        NetCraftDropManager.DropConfig oldConfig =
                dropConfigs.get(entityId);

        boolean replace = false;

        List<NetCraftDropManager.DropEntry> entries =
                new ArrayList<>();

        if (oldConfig != null) {
            replace = oldConfig.replaceDrops();
            entries.addAll(oldConfig.entries());
        }

        /*
         * replace = true / false
         */
        if ("replace".equalsIgnoreCase(key)) {
            Boolean parsed = parseBoolean(value);

            if (parsed != null) {
                replace = parsed;
            }

            dropConfigs.put(
                    entityId,
                    new NetCraftDropManager.DropConfig(
                            replace,
                            entries
                    )
            );

            return;
        }

        /*
         * items = "[item|min|max|chance,...]"
         */
        if ("items".equalsIgnoreCase(key)) {
            entries.clear();

            String cleanValue = stripQuotes(value);

            entries.addAll(
                    parseCompactDropItems(
                            cleanValue,
                            entityId
                    )
            );

            dropConfigs.put(
                    entityId,
                    new NetCraftDropManager.DropConfig(
                            replace,
                            entries
                    )
            );
        }
    }

    /**
     * 从：
     *
     * boss."netcraft:xxx".drops
     *
     * 提取：
     *
     * netcraft:xxx
     */
    private String extractEntityIdFromDropSection(
            String section
    ) {
        return extractEntityId(section);
    }

    /**
     * 从：
     *
     * boss."netcraft:xxx"
     *
     * boss."netcraft:xxx".drops
     *
     * 提取实体 ID。
     */
    private String extractEntityId(String section) {
        if (section == null) {
            return null;
        }

        int firstQuote = section.indexOf('"');

        if (firstQuote < 0) {
            return null;
        }

        int secondQuote = section.indexOf(
                '"',
                firstQuote + 1
        );

        if (secondQuote < 0) {
            return null;
        }

        return section.substring(
                firstQuote + 1,
                secondQuote
        ).trim();
    }

    /**
     * 获取：
     *
     * boss / elite / mob
     */
    private String getSectionCategory(String section) {
        if (section.startsWith("boss.")) {
            return "boss";
        }

        if (section.startsWith("elite.")) {
            return "elite";
        }

        if (section.startsWith("mob.")) {
            return "mob";
        }

        return null;
    }

    /**
     * 解析紧凑型掉落：
     *
     * [minecraft:diamond|1|10|0.5]
     *
     * 或：
     *
     * [minecraft:diamond|1|10|0.5,netcraft:wup|10|25|1]
     *
     * 每一项：
     *
     * itemId|min|max|chance
     */
    private List<NetCraftDropManager.DropEntry> parseCompactDropItems(
            String value,
            String entityId
    ) {
        List<NetCraftDropManager.DropEntry> result =
                new ArrayList<>();

        if (value == null) {
            return result;
        }

        value = value.trim();

        if (value.isEmpty()) {
            return result;
        }

        /*
         * 去掉整个字符串外面的引号。
         */
        value = stripQuotes(value).trim();

        if (value.isEmpty()) {
            return result;
        }

        /*
         * 支持：
         *
         * [diamond|1|10|0.5,netcraft:wup|10|25|1]
         *
         * 也支持：
         *
         * [diamond|1|10|0.5],[netcraft:wup|10|25|1]
         */
        List<String> entries =
                splitCompactDropEntries(value);

        for (String rawEntry : entries) {
            if (rawEntry == null) {
                continue;
            }

            String entry = rawEntry.trim();

            if (entry.isEmpty()) {
                continue;
            }

            /*
             * 去掉单项两边的 []。
             */
            if (entry.startsWith("[")
                    && entry.endsWith("]")) {

                entry = entry.substring(
                        1,
                        entry.length() - 1
                ).trim();
            } else {
                if (entry.startsWith("[")) {
                    entry = entry.substring(1).trim();
                }

                if (entry.endsWith("]")) {
                    entry = entry.substring(
                            0,
                            entry.length() - 1
                    ).trim();
                }
            }

            if (entry.isEmpty()) {
                continue;
            }

            String[] parts =
                    entry.split("\\|", -1);

            if (parts.length != 4) {
                NetCraftToolkit.LOGGER.warn(
                        "[NetCraftToolkit] Invalid drop entry for {}: [{}]",
                        entityId,
                        entry
                );

                continue;
            }

            String itemId =
                    parts[0].trim();

            if (itemId.isEmpty()) {
                NetCraftToolkit.LOGGER.warn(
                        "[NetCraftToolkit] Empty item id for {}.",
                        entityId
                );

                continue;
            }

            Integer minCount =
                    parseInteger(parts[1]);

            Integer maxCount =
                    parseInteger(parts[2]);

            Double chance =
                    parseDouble(parts[3]);

            if (minCount == null
                    || maxCount == null
                    || chance == null) {

                NetCraftToolkit.LOGGER.warn(
                        "[NetCraftToolkit] Invalid drop numbers for {}: [{}]",
                        entityId,
                        entry
                );

                continue;
            }

            /*
             * 数量至少 1。
             */
            minCount = Math.max(
                    1,
                    minCount
            );

            maxCount = Math.max(
                    1,
                    maxCount
            );

            /*
             * 防止反过来写。
             */
            if (maxCount < minCount) {
                int temp = minCount;
                minCount = maxCount;
                maxCount = temp;
            }

            /*
             * 概率限制在 0~1。
             */
            chance = Math.max(
                    0.0D,
                    Math.min(
                            1.0D,
                            chance
                    )
            );

            /*
             * 验证物品 ID 格式。
             *
             * 这里不要求物品一定存在，
             * 因为 NetCraft 自定义物品可能在后续注册阶段处理。
             */
            try {
                ResourceLocation.parse(itemId);
            } catch (Exception e) {
                NetCraftToolkit.LOGGER.warn(
                        "[NetCraftToolkit] Invalid item id for {}: {}",
                        entityId,
                        itemId
                );

                continue;
            }

            result.add(
                    new NetCraftDropManager.DropEntry(
                            itemId,
                            minCount,
                            maxCount,
                            chance
                    )
            );
        }

        return result;
    }

    /**
     * 分割紧凑掉落列表。
     *
     * 例如：
     *
     * [minecraft:diamond|1|10|0.5,netcraft:wup|10|25|1]
     *
     * 会变成：
     *
     * minecraft:diamond|1|10|0.5
     * netcraft:wup|10|25|1
     */
    private List<String> splitCompactDropEntries(
            String value
    ) {
        List<String> result =
                new ArrayList<>();

        if (value == null || value.isBlank()) {
            return result;
        }

        String text = value.trim();

        /*
         * 情况一：
         *
         * [diamond|1|10|0.5],[wup|10|25|1]
         */
        if (text.contains("],["))
        {
            String[] parts =
                    text.split("\\],\\[");

            for (String part : parts) {
                String clean = part.trim();

                if (!clean.startsWith("[")) {
                    clean = "[" + clean;
                }

                if (!clean.endsWith("]")) {
                    clean = clean + "]";
                }

                result.add(clean);
            }

            return result;
        }

        /*
         * 情况二：
         *
         * [diamond|1|10|0.5,wup|10|25|1]
         *
         * 这是我们主要支持的格式。
         *
         * 外层 [] 只是整个 items 字符串的容器，
         * 内部逗号才是掉落项分隔符。
         */
        if (text.startsWith("[")
                && text.endsWith("]")) {

            String inner =
                    text.substring(
                            1,
                            text.length() - 1
                    ).trim();

            if (inner.isEmpty()) {
                return result;
            }

            String[] parts =
                    inner.split(",");

            for (String part : parts) {
                String clean = part.trim();

                if (!clean.isEmpty()) {
                    result.add(clean);
                }
            }

            return result;
        }

        /*
         * 情况三：
         *
         * diamond|1|10|0.5,wup|10|25|1
         */
        String[] parts =
                text.split(",");

        Collections.addAll(
                result,
                parts
        );

        return result;
    }

    /**
     * 解析装备配置。
     *
     * 目前只保存配置数据，不直接调用 NetCraft。
     * 后续 EquipmentOverrideWriter / Reflection 层负责应用。
     */
    private void parseEquipmentProperty(
            String section,
            String key,
            String value
    ) {
        String equipmentId =
                extractEquipmentId(section);

        if (equipmentId == null
                || equipmentId.isBlank()) {
            return;
        }

        Double number =
                parseDouble(value);

        if (number == null) {
            return;
        }

        equipmentOverrides
                .computeIfAbsent(
                        equipmentId,
                        id -> new LinkedHashMap<>()
                )
                .put(
                        key,
                        number
                );
    }

    /**
     * 提取 equipment 配置中的 ID。
     */
    private String extractEquipmentId(
            String section
    ) {
        int firstQuote =
                section.indexOf('"');

        if (firstQuote < 0) {
            return null;
        }

        int secondQuote =
                section.indexOf(
                        '"',
                        firstQuote + 1
                );

        if (secondQuote < 0) {
            return null;
        }

        return section.substring(
                firstQuote + 1,
                secondQuote
        ).trim();
    }

    /**
     * 同步掉落配置到 DropManager。
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
                NetCraftDropManager.DropConfig> entry
                : dropConfigs.entrySet()) {

            NetCraftDropManager.DropConfig config =
                    entry.getValue();

            if (config == null) {
                continue;
            }

            manager.setDropConfig(
                    entry.getKey(),
                    config.replaceDrops(),
                    config.entries()
            );
        }

        NetCraftToolkit.LOGGER.info(
                "[NetCraftToolkit] Drop configurations synchronized: {}",
                dropConfigs.size()
        );
    }

    /**
     * 生成默认配置。
     *
     * 只生成 NetCraft 生物。
     *
     * 不会把 Minecraft 原版僵尸、骷髅等写进来。
     */
    private void generateDefaultConfig()
            throws IOException {

        StringBuilder out =
                new StringBuilder();

        out.append("# ============================================================\n");
        out.append("# NetCraft Toolkit\n");
        out.append("# 自动生成配置文件\n");
        out.append("# Minecraft 1.20.1 / Forge 47.4.13\n");
        out.append("# ============================================================\n\n");

        out.append("# ================================================================\n");
        out.append("# BOSS\n");
        out.append("# ================================================================\n\n");

        generateCategoryConfig(
                out,
                "boss"
        );

        out.append("# ================================================================\n");
        out.append("# 精英\n");
        out.append("# ================================================================\n\n");

        generateCategoryConfig(
                out,
                "elite"
        );

        out.append("# ================================================================\n");
        out.append("# 小怪\n");
        out.append("# ================================================================\n\n");

        generateCategoryConfig(
                out,
                "mob"
        );

        generateEquipmentConfig(out);

        Files.createDirectories(
                configFile.getParent()
        );

        Files.writeString(
                configFile,
                out.toString(),
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE
        );

        NetCraftToolkit.LOGGER.info(
                "[NetCraftToolkit] Default config generated."
        );
    }

    /**
     * 生成某一个分类。
     */
    private void generateCategoryConfig(
            StringBuilder out,
            String category
    ) {
        for (var entry : ForgeRegistries.ENTITY_TYPES.getEntries()) {

            ResourceLocation id =
                    entry.getKey().location();

            /*
             * 只扫描 NetCraft。
             */
            if (!"netcraft".equals(
                    id.getNamespace()
            )) {
                continue;
            }

            EntityType<?> type =
                    entry.getValue();

            if (type == null) {
                continue;
            }

            /*
             * 只生成拥有标准属性、且默认最大生命值 >= 100 的实体。
             * 低于 100 血的 NetCraft 生物不写入配置。
             */
            if (!hasReadableStandardAttributes(type)) {
                continue;
            }

            double defaultMaxHealth =
                    getDefaultMaxHealth(type);

            if (defaultMaxHealth < 100.0D) {
                continue;
            }

            /*
             * 判断分类。
             * BOSS / 精英会分别写在各自标题下面，
             * 不会全部挤在配置文件最前面。
             */
            String detectedCategory =
                    detectEntityCategory(
                            type
                    );

            if (!category.equalsIgnoreCase(
                    detectedCategory
            )) {
                continue;
            }

            String entityId =
                    id.toString();

            String chineseName =
                    getEntityChineseName(
                            id,
                            type
                    );

            entityNames.put(
                    entityId,
                    chineseName
            );

            entityCategories.put(
                    entityId,
                    category
            );

            out.append("# ------------------------------------------------------------\n");
            out.append("# 【")
                    .append(chineseName)
                    .append("】\n");
            out.append("# 注册名：")
                    .append(entityId)
                    .append("\n");
            out.append("# ------------------------------------------------------------\n");

            out.append("[")
                    .append(category)
                    .append(".\"")
                    .append(entityId)
                    .append("\"]\n\n");

            writeEntityAttributes(
                    out,
                    type
            );

            out.append("\n");

            /*
             * Boss / Elite / Mob 每个生物都单独拥有 drops。
             */
            out.append("[")
                    .append(category)
                    .append(".\"")
                    .append(entityId)
                    .append("\".drops]\n");

            out.append("# true  = 替换这个生物的全部原始物品掉落\n");
            out.append("# false = 保留全部原始物品掉落，并追加下面的自定义掉落\n");
            out.append("replace = false\n");

            out.append("# 格式：\n");
            out.append("# [物品ID|最小数量|最大数量|概率]\n");
            out.append("# 多个掉落用英文逗号分隔\n");
            out.append("# 例如：\n");
            out.append("# items = \"[minecraft:diamond|1|10|0.5,netcraft:wup|10|25|1]\"\n");
            out.append("items = \"\"\n\n");
        }
    }

    /**
     * 判断实体是否拥有可读取的标准属性数据。
     *
     * 没有 DefaultAttributes 的实体通常是纯技能/特效实体，
     * 这类实体不应该进入 NetCraft Toolkit 的属性和掉落配置。
     */
    private boolean hasReadableStandardAttributes(
            EntityType<?> type
    ) {
        if (type == null) {
            return false;
        }

        try {
            return DefaultAttributes.hasSupplier(type);
        } catch (Exception ignored) {
            return false;
        }
    }

    /**
     * 根据注册名判断该 NetCraft 生物是否拥有可读取的标准属性。
     */
    private boolean isReadableNetCraftEntity(
            String entityId
    ) {
        if (entityId == null
                || entityId.isBlank()
                || !entityId.startsWith("netcraft:")) {
            return false;
        }

        try {
            ResourceLocation id = ResourceLocation.tryParse(entityId);

            if (id == null
                    || !"netcraft".equals(id.getNamespace())) {
                return false;
            }

            EntityType<?> type =
                    ForgeRegistries.ENTITY_TYPES.getValue(id);

            return hasReadableStandardAttributes(type);

        } catch (Exception ignored) {
            return false;
        }
    }

    /**
     * 读取实体默认最大生命值。
     * 只用于生成配置时筛选 >= 100 血的 NetCraft 生物。
     */
    private double getDefaultMaxHealth(
            EntityType<?> type
    ) {
        try {
            @SuppressWarnings("unchecked")
            EntityType<? extends net.minecraft.world.entity.LivingEntity> livingType =
                    (EntityType<? extends net.minecraft.world.entity.LivingEntity>) (EntityType<?>) type;

            AttributeSupplier supplier =
                    DefaultAttributes.getSupplier(livingType);

            if (supplier == null) {
                return 0.0D;
            }

            return supplier.getValue(Attributes.MAX_HEALTH);

        } catch (Exception ignored) {
            return 0.0D;
        }
    }

    /**
     * 写入一个生物的属性。
     */
    private void writeEntityAttributes(
            StringBuilder out,
            EntityType<?> type
    ) {
        try {
            AttributeSupplier supplier = null;

            /*
             * 1.20.1 中 EntityType 本身没有 getAttributes()。
             * 默认属性由 DefaultAttributes 统一保存。
             */
            if (DefaultAttributes.hasSupplier(type)) {
                @SuppressWarnings("unchecked")
                EntityType<? extends net.minecraft.world.entity.LivingEntity> livingType =
                        (EntityType<? extends net.minecraft.world.entity.LivingEntity>) (EntityType<?>) type;
                supplier = DefaultAttributes.getSupplier(livingType);
            }

            if (supplier == null) {
                return;
            }

            writeAttribute(
                    out,
                    "max_health",
                    "最大生命值",
                    Attributes.MAX_HEALTH,
                    supplier
            );

            writeAttribute(
                    out,
                    "attack_damage",
                    "攻击伤害",
                    Attributes.ATTACK_DAMAGE,
                    supplier
            );

            writeAttribute(
                    out,
                    "movement_speed",
                    "移动速度",
                    Attributes.MOVEMENT_SPEED,
                    supplier
            );

            writeAttribute(
                    out,
                    "armor",
                    "护甲值",
                    Attributes.ARMOR,
                    supplier
            );

            writeAttribute(
                    out,
                    "attack_speed",
                    "攻击速度",
                    Attributes.ATTACK_SPEED,
                    supplier
            );

            writeAttribute(
                    out,
                    "knockback_resistance",
                    "击退抗性",
                    Attributes.KNOCKBACK_RESISTANCE,
                    supplier
            );

            writeAttribute(
                    out,
                    "follow_range",
                    "跟随范围",
                    Attributes.FOLLOW_RANGE,
                    supplier
            );

        } catch (Exception e) {
            NetCraftToolkit.LOGGER.debug(
                    "[NetCraftToolkit] Failed to read default attributes.",
                    e
            );

            return;
        }

        /*
         * BossBase 专属属性。
         *
         * 这里不直接导入 NetCraft 类，
         * 防止 NetCraft 没有安装时导致类加载崩溃。
         */
        if (isBossEntity(type)) {
            out.append("\n");

            out.append("# BossBase 基础伤害\n");
            out.append("# NetCraft 默认值需要从 BossBase 实例读取\n");
            out.append("base_damage = -1\n");

            out.append("\n");

            out.append("# BossBase 基础防御\n");
            out.append("# NetCraft 默认值需要从 BossBase 实例读取\n");
            out.append("base_defense = -1\n");
        }
    }

    /**
     * 写入一个 Minecraft 原生属性。
     */
    private void writeAttribute(
            StringBuilder out,
            String configName,
            String chineseName,
            Attribute attribute,
            AttributeSupplier supplier
    ) {
        try {
            double value =
                    supplier.getValue(attribute);

            out.append("# ")
                    .append(chineseName)
                    .append("\n");

            out.append("# NetCraft 默认值：")
                    .append(value)
                    .append("\n");

            out.append(configName)
                    .append(" = ")
                    .append(value)
                    .append("\n\n");

        } catch (Exception ignored) {
            /*
             * 该实体没有这个属性时不输出。
             */
        }
    }

    /**
     * 判断实体是否为 BossBase。
     *
     * 不直接 import NetCraft。
     */
    private boolean isBossEntity(
            EntityType<?> type
    ) {
        try {
            Class<?> entityClass =
                    type.getBaseClass();

            while (entityClass != null) {
                String name =
                        entityClass.getName();

                if (name.endsWith("BossBase")) {
                    return true;
                }

                entityClass =
                        entityClass.getSuperclass();
            }

        } catch (Exception ignored) {
        }

        return false;
    }

    /**
     * 尝试判断实体分类。
     *
     * 优先根据类名。
     */
    private String detectEntityCategory(
            EntityType<?> type
    ) {
        try {
            Class<?> clazz =
                    type.getBaseClass();

            while (clazz != null) {
                String name =
                        clazz.getSimpleName()
                                .toLowerCase(
                                        Locale.ROOT
                                );

                if (name.contains("boss")) {
                    return "boss";
                }

                if (name.contains("elite")) {
                    return "elite";
                }

                clazz =
                        clazz.getSuperclass();
            }

        } catch (Exception ignored) {
        }

        /*
         * 如果不是 Boss / Elite，
         * 默认归入 Mob。
         */
        return "mob";
    }

    /**
     * 获取 NetCraft 中文名称。
     *
     * 优先读取：
     *
     * assets/netcraft/lang/zh_cn.json
     *
     * 如果读不到，则使用 EntityType 的描述 ID。
     */
    private String getEntityChineseName(
            ResourceLocation id,
            EntityType<?> type
    ) {
        try {
            String translationKey =
                    type.getDescriptionId();

            String fromLanguage =
                    readChineseNameFromLanguage(
                            translationKey
                    );

            if (fromLanguage != null
                    && !fromLanguage.isBlank()) {

                return fromLanguage;
            }

        } catch (Exception ignored) {
        }

        try {
            String path =
                    id.getPath();

            if (path != null
                    && !path.isBlank()) {

                return path;
            }

        } catch (Exception ignored) {
        }

        return id.toString();
    }

    /**
     * 从 NetCraft zh_cn.json 读取中文名称。
     */
    private String readChineseNameFromLanguage(
            String translationKey
    ) {
        try {
            /*
             * NetCraft 的中文语言文件属于客户端 assets 资源：
             * assets/netcraft/lang/zh_cn.json
             *
             * 这里不能使用 server.getResourceManager() 去读取。
             * Dedicated Server 的 ResourceManager 主要管理 data/datapack
             * 资源，因此会出现有时能读到、有时完全读不到中文名的情况。
             *
             * 直接通过当前 Mod ClassLoader 读取安装在 mods 目录中的
             * NetCraft JAR 资源，不需要把 NetCraft JAR 加入本项目依赖。
             */
            try (var inputStream =
                         NetCraftConfig.class
                                 .getClassLoader()
                                 .getResourceAsStream(
                                         "assets/netcraft/lang/zh_cn.json"
                                 )) {

                if (inputStream == null) {
                    return null;
                }

                String json =
                        new String(
                                inputStream.readAllBytes(),
                                StandardCharsets.UTF_8
                        );

                return extractJsonStringValue(
                        json,
                        translationKey
                );
            }

        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 从简单 JSON 对象中读取指定 key。
     *
     * 不依赖 Gson，避免额外依赖。
     */
    private String extractJsonStringValue(
            String json,
            String key
    ) {
        if (json == null
                || key == null) {
            return null;
        }

        String search =
                "\"" + key + "\"";

        int keyIndex =
                json.indexOf(search);

        if (keyIndex < 0) {
            return null;
        }

        int colon =
                json.indexOf(
                        ':',
                        keyIndex + search.length()
                );

        if (colon < 0) {
            return null;
        }

        int firstQuote =
                json.indexOf(
                        '"',
                        colon + 1
                );

        if (firstQuote < 0) {
            return null;
        }

        StringBuilder result =
                new StringBuilder();

        boolean escaped = false;

        for (int i = firstQuote + 1;
             i < json.length();
             i++) {

            char c =
                    json.charAt(i);

            if (escaped) {
                switch (c) {
                    case 'n' -> result.append('\n');
                    case 'r' -> result.append('\r');
                    case 't' -> result.append('\t');
                    case '"' -> result.append('"');
                    case '\\' -> result.append('\\');
                    default -> result.append(c);
                }

                escaped = false;
                continue;
            }

            if (c == '\\') {
                escaped = true;
                continue;
            }

            if (c == '"') {
                return result.toString();
            }

            result.append(c);
        }

        return null;
    }

    /**
     * 自动读取 NetCraft 全部可覆盖的装备。
     *
     * 不把 NetCraft JAR 加入编译依赖：
     * 运行时通过反射读取 EquipmentStatConfig 的真实默认值。
     * 工具类物品不匹配 weapon/equipment 装备 ID，因此不会生成。
     */
    private void generateEquipmentConfig(
            StringBuilder out
    ) {
        out.append("# ================================================================\n");
        out.append("# 装备\n");
        out.append("# ================================================================\n");
        out.append("# 自动读取 NetCraft 装备注册名和默认数值。工具类物品不生成。\n");
        out.append("# 数值后面的“NetCraft默认值”是读取到的原始默认值。\n");
        out.append("# 直接修改等号左边的数值即可覆盖对应属性。\n\n");

        Pattern pattern = Pattern.compile(
                "^(?:weapon|equipment)_(knight|archer|mage|summoner|dragonknight|wararcher)_(t\\d+|legend\\d+)_(?:[a-z]+_)?(mhand|hand|[1-4])$"
        );

        List<String> ids = new ArrayList<>();

        for (var entry : ForgeRegistries.ITEMS.getEntries()) {
            ResourceLocation id = entry.getKey().location();

            if (id == null || !"netcraft".equals(id.getNamespace())) {
                continue;
            }

            String path = id.getPath();

            if (path == null || !pattern.matcher(path).matches()) {
                continue;
            }

            ids.add(path);
        }

        ids.sort(Comparator.comparingInt(this::equipmentSortTier)
                .thenComparingInt(this::equipmentSortClass)
                .thenComparingInt(this::equipmentSortSlot)
                .thenComparing(s -> s));

        for (String id : ids) {
            writeEquipmentEntry(out, id, pattern);
        }

        if (ids.isEmpty()) {
            out.append("# 未读取到 NetCraft 装备。请确认 NetCraft 已加载。\n\n");
        }
    }

    /** 写入一个装备的完整配置。 */
    private void writeEquipmentEntry(
            StringBuilder out,
            String id,
            Pattern pattern
    ) {
        Matcher matcher = pattern.matcher(id);

        if (!matcher.matches()) {
            return;
        }

        String classType = matcher.group(1);
        String tierName = matcher.group(2);
        String slotCode = matcher.group(3);

        String displayName = getItemChineseName("netcraft:" + id);

        out.append("# ------------------------------------------------------------\n");
        out.append("# 【").append(displayName).append("】\n");
        out.append("# 物品注册ID：netcraft:").append(id).append("\n");
        out.append("# 职业：").append(equipmentClassChineseName(classType))
                .append("    品阶：").append(equipmentTierDisplayName(tierName))
                .append("    部位：").append(equipmentSlotChineseName(slotCode)).append("\n");
        out.append("# ------------------------------------------------------------\n");
        out.append("[equipment.\"").append(id).append("\"]\n");

        Map<String, Double> defaults = readNetCraftEquipmentDefaults(
                classType,
                tierName,
                slotCode
        );

        writeEquipmentValue(out, "meleeDamage", "近战伤害", defaults.get("meleeDamage"));
        writeEquipmentValue(out, "rangedDamage", "远程伤害", defaults.get("rangedDamage"));
        writeEquipmentValue(out, "magicDamage", "魔法伤害", defaults.get("magicDamage"));
        writeEquipmentValue(out, "physicalDefense", "物理防御", defaults.get("physicalDefense"));
        writeEquipmentValue(out, "magicDefense", "魔法防御", defaults.get("magicDefense"));
        writeEquipmentValue(out, "health", "额外生命值", defaults.get("health"));
        writeEquipmentValue(out, "armor", "护甲值", defaults.get("armor"));

        out.append("\n");
    }

    /** 通过反射读取 NetCraft EquipmentStatConfig 的真实默认值。 */
    @SuppressWarnings({"rawtypes", "unchecked"})
    private Map<String, Double> readNetCraftEquipmentDefaults(
            String classType,
            String tierName,
            String slotCode
    ) {
        Map<String, Double> values = new LinkedHashMap<>();

        String[] fields = {
                "MELEE_DAMAGE",
                "RANGED_DAMAGE",
                "MAGIC_DAMAGE",
                "PHYSICAL_DEFENSE",
                "MAGIC_DEFENSE",
                "HEALTH",
                "ARMOR"
        };

        String[] keys = {
                "meleeDamage",
                "rangedDamage",
                "magicDamage",
                "physicalDefense",
                "magicDefense",
                "health",
                "armor"
        };

        try {
            Class<?> configClass = Class.forName(
                    "com.jiufeng.netcraft.config.EquipmentStatConfig"
            );
            Class<?> groupClass = Class.forName(
                    "com.jiufeng.netcraft.config.EquipmentStatConfig$Group"
            );
            Class<?> classClass = Class.forName(
                    "com.jiufeng.netcraft.config.EquipmentStatConfig$ClassType"
            );
            Class<?> slotClass = Class.forName(
                    "com.jiufeng.netcraft.config.EquipmentStatConfig$Slot"
            );
            Class<?> fieldClass = Class.forName(
                    "com.jiufeng.netcraft.config.EquipmentStatConfig$Field"
            );

            Method getMethod = configClass.getMethod(
                    "get",
                    groupClass,
                    classClass,
                    slotClass,
                    fieldClass
            );

            Object group = Enum.valueOf(
                    (Class) groupClass,
                    equipmentGroupName(tierName)
            );
            Object clazz = Enum.valueOf(
                    (Class) classClass,
                    classType.toUpperCase(Locale.ROOT)
            );
            Object slot = Enum.valueOf(
                    (Class) slotClass,
                    equipmentSlotEnumName(slotCode)
            );

            for (int i = 0; i < fields.length; i++) {
                Object field = Enum.valueOf(
                        (Class) fieldClass,
                        fields[i]
                );

                Object result = getMethod.invoke(
                        null,
                        group,
                        clazz,
                        slot,
                        field
                );

                if (result instanceof Number number) {
                    values.put(keys[i], number.doubleValue());
                }
            }
        } catch (Throwable e) {
            NetCraftToolkit.LOGGER.warn(
                    "[NetCraftToolkit] 读取 NetCraft 装备默认值失败: {}",
                    tierName + "." + classType + "." + slotCode,
                    e
            );
        }

        for (String key : keys) {
            values.putIfAbsent(key, 0D);
        }

        return values;
    }

    private void writeEquipmentValue(
            StringBuilder out,
            String key,
            String chineseName,
            Double value
    ) {
        double number = value == null ? 0D : value;

        out.append(key)
                .append(" = ")
                .append(formatConfigNumber(number))
                .append(" # ")
                .append(chineseName)
                .append("（NetCraft默认值：")
                .append(formatConfigNumber(number))
                .append("）\n");
    }

    private String getItemChineseName(String itemId) {
        try {
            var item = ForgeRegistries.ITEMS.getValue(
                    ResourceLocation.tryParse(itemId)
            );

            if (item != null) {
                String name = readChineseNameFromLanguage(
                        item.getDescriptionId()
                );

                if (name != null && !name.isBlank()) {
                    return name;
                }
            }
        } catch (Exception ignored) {
        }

        ResourceLocation id = ResourceLocation.tryParse(itemId);
        return id == null ? itemId : id.getPath();
    }

    private String equipmentGroupName(String tierName) {
        if (tierName.startsWith("legend")) {
            return "LEGEND" + tierName.substring("legend".length());
        }
        return "TIER" + tierName.substring(1);
    }

    private String equipmentSlotEnumName(String slotCode) {
        return switch (slotCode) {
            case "mhand" -> "MAINHAND";
            case "hand" -> "OFFHAND";
            case "1" -> "HELMET";
            case "2" -> "CHESTPLATE";
            case "3" -> "LEGGINGS";
            case "4" -> "BOOTS";
            default -> "MAINHAND";
        };
    }

    private String equipmentClassChineseName(String classType) {
        return switch (classType) {
            case "knight" -> "骑士";
            case "archer" -> "弓箭手";
            case "mage" -> "法师";
            case "summoner" -> "召唤师";
            case "dragonknight" -> "龙骑士";
            case "wararcher" -> "战争弓手";
            default -> classType;
        };
    }

    private String equipmentTierDisplayName(String tierName) {
        return tierName.toUpperCase(Locale.ROOT);
    }

    private String equipmentSlotChineseName(String slotCode) {
        return switch (slotCode) {
            case "mhand" -> "主手";
            case "hand" -> "副手";
            case "1" -> "头盔";
            case "2" -> "胸甲";
            case "3" -> "护腿";
            case "4" -> "靴子";
            default -> slotCode;
        };
    }

    private int equipmentSortTier(String id) {
        Matcher m = Pattern.compile("_(t\\d+|legend\\d+)_").matcher(id);
        if (!m.find()) return 999;
        String value = m.group(1);
        if (value.startsWith("legend")) {
            return 100 + Integer.parseInt(value.substring(6));
        }
        return Integer.parseInt(value.substring(1));
    }

    private int equipmentSortClass(String id) {
        String[] classes = {
                "knight", "archer", "mage", "summoner", "dragonknight", "wararcher"
        };
        for (int i = 0; i < classes.length; i++) {
            if (id.contains("_" + classes[i] + "_")) return i;
        }
        return 999;
    }

    private int equipmentSortSlot(String id) {
        if (id.endsWith("_mhand")) return 0;
        if (id.endsWith("_hand")) return 1;
        if (id.endsWith("_1")) return 2;
        if (id.endsWith("_2")) return 3;
        if (id.endsWith("_3")) return 4;
        if (id.endsWith("_4")) return 5;
        return 999;
    }

    private String formatConfigNumber(double value) {
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            return "0";
        }
        if (value == Math.rint(value)) {
            return Long.toString((long) value);
        }
        return Double.toString(value);
    }

    /**
     * 获取生物属性配置。
     */
    public Map<String, Double> getEntityAttributes(
            String entityId
    ) {
        Map<String, Double> map =
                entityAttributes.get(entityId);

        if (map == null) {
            return Collections.emptyMap();
        }

        return Collections.unmodifiableMap(map);
    }

    /**
     * 兼容旧调用名称。
     */
    public Map<String, Double> getEntityConfig(
            String entityId
    ) {
        return getEntityAttributes(entityId);
    }

    /**
     * 兼容旧调用名称。
     */
    public Map<String, Double> getAttributes(
            String entityId
    ) {
        return getEntityAttributes(entityId);
    }

    /**
     * 兼容旧调用名称。
     */
    public Map<String, Double> getMobAttributes(
            String entityId
    ) {
        return getEntityAttributes(entityId);
    }

    /**
     * 获取分类。
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
    public Map<String, NetCraftDropManager.DropConfig>
    getDropConfigs() {
        return Collections.unmodifiableMap(
                dropConfigs
        );
    }

    /**
     * 获取单个生物掉落配置。
     */
    public NetCraftDropManager.DropConfig getDropConfig(
            String entityId
    ) {
        return dropConfigs.get(entityId);
    }

    /**
     * 获取装备覆盖配置。
     */
    public Map<String, Map<String, Double>>
    getEquipmentOverrides() {
        return Collections.unmodifiableMap(
                equipmentOverrides
        );
    }

    /**
     * 兼容旧代码。
     */
    public Map<String, Map<String, Double>>
    getEquipmentDefaults() {
        return Collections.emptyMap();
    }

    /**
     * 获取装备结构名称。
     */
    public List<String> getEquipmentStructNames() {
        return new ArrayList<>(
                equipmentOverrides.keySet()
        );
    }

    /**
     * 清空配置。
     */
    public synchronized void clear() {
        entityAttributes.clear();
        entityCategories.clear();
        entityNames.clear();
        dropConfigs.clear();
        equipmentOverrides.clear();

        generatedInitialConfig = false;
    }

    /**
     * 删除配置文件。
     */
    public synchronized void resetConfigFile() {
        if (configFile == null) {
            return;
        }

        try {
            Files.deleteIfExists(
                    configFile
            );

            NetCraftToolkit.LOGGER.info(
                    "[NetCraftToolkit] Config file deleted."
            );

        } catch (IOException e) {
            NetCraftToolkit.LOGGER.error(
                    "[NetCraftToolkit] Failed to delete config file.",
                    e
            );
        }
    }

    /**
     * 去除行尾注释。
     */
    private String removeTrailingComment(
            String line
    ) {
        boolean quoted = false;
        boolean escaped = false;

        for (int i = 0; i < line.length(); i++) {
            char c =
                    line.charAt(i);

            if (escaped) {
                escaped = false;
                continue;
            }

            if (c == '\\'
                    && quoted) {
                escaped = true;
                continue;
            }

            if (c == '"') {
                quoted = !quoted;
                continue;
            }

            if (c == '#'
                    && !quoted) {
                return line.substring(
                        0,
                        i
                );
            }
        }

        return line;
    }

    /**
     * 查找不在引号里的 =。
     */
    private int findEqualsOutsideQuotes(
            String line
    ) {
        boolean quoted = false;
        boolean escaped = false;

        for (int i = 0; i < line.length(); i++) {
            char c =
                    line.charAt(i);

            if (escaped) {
                escaped = false;
                continue;
            }

            if (c == '\\'
                    && quoted) {
                escaped = true;
                continue;
            }

            if (c == '"') {
                quoted = !quoted;
                continue;
            }

            if (c == '='
                    && !quoted) {
                return i;
            }
        }

        return -1;
    }

    /**
     * 去掉字符串两边的引号。
     */
    private String stripQuotes(
            String value
    ) {
        if (value == null) {
            return "";
        }

        String result =
                value.trim();

        if (result.length() >= 2
                && result.startsWith("\"")
                && result.endsWith("\"")) {

            result =
                    result.substring(
                            1,
                            result.length() - 1
                    );
        }

        return result;
    }

    /**
     * 解析整数。
     */
    private Integer parseInteger(
            String value
    ) {
        if (value == null) {
            return null;
        }

        try {
            return Integer.parseInt(
                    stripQuotes(value).trim()
            );

        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * 解析 double。
     */
    private Double parseDouble(
            String value
    ) {
        if (value == null) {
            return null;
        }

        try {
            return Double.parseDouble(
                    stripQuotes(value).trim()
            );

        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * 解析 boolean。
     */
    private Boolean parseBoolean(
            String value
    ) {
        if (value == null) {
            return null;
        }

        String clean =
                stripQuotes(value)
                        .trim()
                        .toLowerCase(
                                Locale.ROOT
                        );

        if ("true".equals(clean)) {
            return true;
        }

        if ("false".equals(clean)) {
            return false;
        }

        return null;
    }
}
