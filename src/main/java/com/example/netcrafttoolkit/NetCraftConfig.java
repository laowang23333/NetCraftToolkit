package com.example.netcrafttoolkit;

import com.mojang.logging.LogUtils;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraftforge.registries.ForgeRegistries;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class NetCraftConfig {

    private static final Logger LOGGER = LogUtils.getLogger();

    private static final String NAMESPACE = "netcraft";

    private static final String CONFIG_DIR = "netcrafttoolkit";
    private static final String CONFIG_FILE = "netcraft-attributes.toml";

    /*
     * ============================================================
     * 支持的 Minecraft 原版属性
     * ============================================================
     */

    private static final LinkedHashMap<String, Attribute> ATTRIBUTES =
            new LinkedHashMap<>();

    static {
        ATTRIBUTES.put("max_health", Attributes.MAX_HEALTH);
        ATTRIBUTES.put("attack_damage", Attributes.ATTACK_DAMAGE);
        ATTRIBUTES.put("movement_speed", Attributes.MOVEMENT_SPEED);
        ATTRIBUTES.put("armor", Attributes.ARMOR);
        ATTRIBUTES.put("attack_speed", Attributes.ATTACK_SPEED);
        ATTRIBUTES.put(
                "knockback_resistance",
                Attributes.KNOCKBACK_RESISTANCE
        );
        ATTRIBUTES.put(
                "follow_range",
                Attributes.FOLLOW_RANGE
        );
    }

    /*
     * ============================================================
     * 生物属性配置
     *
     * entityId
     *     ->
     * 属性名
     *     ->
     * 数值
     * ============================================================
     */

    private final Map<String, Map<String, Double>> entityValues =
            new LinkedHashMap<>();

    /*
     * ============================================================
     * 掉落配置
     *
     * 掉落已经交给 NetCraftDropManager。
     *
     * Config 这里只负责保存和读取数据。
     * ============================================================
     */

    private final Map<String, List<NetCraftDropManager.DropEntry>> drops =
            new LinkedHashMap<>();

    private final Map<String, Boolean> dropReplace =
            new HashMap<>();

    /*
     * ============================================================
     * 文件
     * ============================================================
     */

    private Path configPath;

    private MinecraftServer server;

    /*
     * ============================================================
     * 配置监听线程
     * ============================================================
     */

    private final ExecutorService watcher =
            Executors.newSingleThreadExecutor(r -> {

                Thread thread =
                        new Thread(
                                r,
                                "NetCraftToolkit-ConfigWatcher"
                        );

                thread.setDaemon(true);

                return thread;
            });

    private volatile boolean watching = false;

    /*
     * ============================================================
     * 构造
     * ============================================================
     */

    public NetCraftConfig() {
    }

    /*
     * ============================================================
     * 初始化
     * ============================================================
     */

    public void init(MinecraftServer server) {

        this.server = server;

        this.configPath =
                server.getServerDirectory()
                        .toPath()
                        .resolve("config")
                        .resolve(CONFIG_DIR)
                        .resolve(CONFIG_FILE);

        try {

            Files.createDirectories(
                    configPath.getParent()
            );

        } catch (IOException e) {

            LOGGER.error(
                    "[NetCraftToolkit] 无法创建配置目录: {}",
                    configPath,
                    e
            );
        }

        LOGGER.info(
                "[NetCraftToolkit] 配置文件: {}",
                configPath.toAbsolutePath()
        );
    }

    /*
     * ============================================================
     * 加载配置
     * ============================================================
     */

    public synchronized void load() {

        if (configPath == null) {

            LOGGER.warn(
                    "[NetCraftToolkit] configPath 尚未初始化"
            );

            return;
        }

        try {

            /*
             * 第一次运行：
             * 自动扫描 NetCraft 生物并生成配置。
             */
            if (!Files.exists(configPath)) {

                scanNetCraftTypes();

                save();

                syncDropManager();

                return;
            }

            List<String> lines =
                    Files.readAllLines(
                            configPath,
                            StandardCharsets.UTF_8
                    );

            entityValues.clear();
            drops.clear();
            dropReplace.clear();

            String currentSection = null;

            for (String rawLine : lines) {

                if (rawLine == null) {
                    continue;
                }

                String line =
                        rawLine.trim();

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
                 * ==================================================
                 * Section
                 *
                 * [netcraft:xxx]
                 * ==================================================
                 */

                if (line.startsWith("[")
                        && line.endsWith("]")) {

                    currentSection =
                            line.substring(
                                    1,
                                    line.length() - 1
                            ).trim();

                    if (!currentSection
                            .toLowerCase(Locale.ROOT)
                            .startsWith(
                                    NAMESPACE + ":"
                            )) {

                        currentSection = null;

                    } else {

                        entityValues.computeIfAbsent(
                                currentSection,
                                ignored ->
                                        new LinkedHashMap<>()
                        );
                    }

                    continue;
                }

                if (currentSection == null) {
                    continue;
                }

                /*
                 * 找 =
                 */
                int equal =
                        line.indexOf('=');

                if (equal <= 0) {
                    continue;
                }

                String key =
                        line.substring(
                                0,
                                equal
                        ).trim();

                String value =
                        line.substring(
                                equal + 1
                        ).trim();

                /*
                 * ==================================================
                 * drops
                 * ==================================================
                 */

                if ("drops".equalsIgnoreCase(key)) {

                    List<NetCraftDropManager.DropEntry>
                            parsed =
                            parseDrops(value);

                    if (!parsed.isEmpty()) {

                        drops.put(
                                currentSection,
                                parsed
                        );
                    }

                    continue;
                }

                /*
                 * ==================================================
                 * drops_replace
                 * ==================================================
                 */

                if ("drops_replace"
                        .equalsIgnoreCase(key)) {

                    dropReplace.put(
                            currentSection,
                            parseBoolean(value)
                    );

                    continue;
                }

                /*
                 * ==================================================
                 * 属性
                 * ==================================================
                 */

                if (!ATTRIBUTES.containsKey(key)) {
                    continue;
                }

                /*
                 * 去掉普通属性的行尾注释。
                 */
                int comment =
                        value.indexOf('#');

                if (comment >= 0) {

                    value =
                            value.substring(
                                    0,
                                    comment
                            ).trim();
                }

                try {

                    double number =
                            Double.parseDouble(
                                    value
                            );

                    if (Double.isNaN(number)
                            || Double.isInfinite(number)) {

                        continue;
                    }

                    entityValues
                            .get(currentSection)
                            .put(
                                    key,
                                    number
                            );

                } catch (NumberFormatException e) {

                    LOGGER.warn(
                            "[NetCraftToolkit] 无法解析属性 {} = {}",
                            key,
                            value
                    );
                }
            }

            /*
             * 扫描当前实际存在的 NetCraft EntityType。
             *
             * 新增实体会自动补进内存。
             */
            scanNetCraftTypes();

            /*
             * 把掉落配置交给 DropManager。
             */
            syncDropManager();

            LOGGER.info(
                    "[NetCraftToolkit] 配置加载完成: {} 个生物, {} 个掉落配置",
                    entityValues.size(),
                    drops.size()
            );

        } catch (Exception e) {

            LOGGER.error(
                    "[NetCraftToolkit] 配置加载失败",
                    e
            );
        }
    }

    /*
     * ============================================================
     * 扫描 NetCraft 生物
     * ============================================================
     */

    private void scanNetCraftTypes() {

        int count = 0;
        int added = 0;

        for (ResourceLocation id :
                ForgeRegistries.ENTITY_TYPES.getKeys()) {

            if (id == null) {
                continue;
            }

            if (!NAMESPACE.equalsIgnoreCase(
                    id.getNamespace()
            )) {
                continue;
            }

            EntityType<?> type =
                    ForgeRegistries.ENTITY_TYPES.getValue(
                            id
                    );

            if (type == null) {
                continue;
            }

            /*
             * 只处理 LivingEntity。
             */
            if (!isLivingEntityType(type)) {
                continue;
            }

            count++;

            String entityId =
                    id.toString();

            if (!entityValues.containsKey(entityId)) {

                Map<String, Double> values =
                        new LinkedHashMap<>();

                /*
                 * -1 = 不修改。
                 */
                for (String key :
                        ATTRIBUTES.keySet()) {

                    values.put(
                            key,
                            -1.0D
                    );
                }

                entityValues.put(
                        entityId,
                        values
                );

                added++;

            } else {

                /*
                 * 如果以后增加新的属性，
                 * 自动补一个 -1。
                 */
                Map<String, Double> values =
                        entityValues.get(
                                entityId
                        );

                for (String key :
                        ATTRIBUTES.keySet()) {

                    values.putIfAbsent(
                            key,
                            -1.0D
                    );
                }
            }
        }

        LOGGER.info(
                "[NetCraftToolkit] NetCraft 生物扫描完成: {} 个，新增 {} 个",
                count,
                added
        );
    }

    /*
     * ============================================================
     * 判断 EntityType 是否属于 LivingEntity
     * ============================================================
     */

    private boolean isLivingEntityType(
            EntityType<?> type
    ) {

        try {

            Class<?> entityClass =
                    type.getBaseClass();

            return LivingEntity.class
                    .isAssignableFrom(
                            entityClass
                    );

        } catch (Throwable ignored) {

            /*
             * 某些混合端环境如果这里异常，
             * 不让整个配置加载失败。
             */
            return true;
        }
    }

    /*
     * ============================================================
     * 掉落配置同步
     * ============================================================
     */

    private void syncDropManager() {

        NetCraftDropManager manager =
                NetCraftToolkit.getDropManager();

        if (manager == null) {
            return;
        }

        manager.clear();

        for (Map.Entry<String,
                List<NetCraftDropManager.DropEntry>> entry :
                drops.entrySet()) {

            String entityId =
                    entry.getKey();

            List<NetCraftDropManager.DropEntry>
                    entries =
                    entry.getValue();

            boolean replace =
                    dropReplace.getOrDefault(
                            entityId,
                            true
                    );

            manager.setDropConfig(
                    entityId,
                    replace,
                    entries
            );
        }

        LOGGER.info(
                "[NetCraftToolkit] 掉落配置已同步: {} 个",
                drops.size()
        );
    }

    /*
     * ============================================================
     * 解析掉落
     *
     * 格式：
     *
     * drops = [
     *   minecraft:diamond|1|3|0.5,
     *   minecraft:emerald|1|1|1.0
     * ]
     *
     * 也支持：
     *
     * minecraft:diamond|1|0.5
     *
     * 表示：
     * 数量固定 1
     * 概率 50%
     * ============================================================
     */

    private List<NetCraftDropManager.DropEntry>
    parseDrops(String value) {

        List<NetCraftDropManager.DropEntry>
                result =
                new ArrayList<>();

        if (value == null) {
            return result;
        }

        String text =
                value.trim();

        /*
         * 去掉数组的 [ ]
         */
        if (text.startsWith("[")) {

            text =
                    text.substring(1);
        }

        if (text.endsWith("]")) {

            text =
                    text.substring(
                            0,
                            text.length() - 1
                    );
        }

        text =
                text.trim();

        if (text.isEmpty()) {
            return result;
        }

        /*
         * 每一个掉落用逗号分隔。
         */
        String[] entries =
                text.split(",");

        for (String raw :
                entries) {

            String entry =
                    raw.trim();

            if (entry.isEmpty()) {
                continue;
            }

            /*
             * 如果使用了引号，
             * 自动去掉。
             */
            if (entry.startsWith("\"")
                    && entry.endsWith("\"")
                    && entry.length() >= 2) {

                entry =
                        entry.substring(
                                1,
                                entry.length() - 1
                        );
            }

            String[] fields =
                    entry.split("\\|");

            if (fields.length < 3) {

                LOGGER.warn(
                        "[NetCraftToolkit] 掉落格式错误: {}",
                        entry
                );

                continue;
            }

            String itemId =
                    fields[0].trim();

            if (itemId.isEmpty()) {
                continue;
            }

            try {

                int min;
                int max;
                double chance;

                if (fields.length >= 4) {

                    min =
                            Integer.parseInt(
                                    fields[1].trim()
                            );

                    max =
                            Integer.parseInt(
                                    fields[2].trim()
                            );

                    chance =
                            Double.parseDouble(
                                    fields[3].trim()
                            );

                } else {

                    min =
                            Integer.parseInt(
                                    fields[1].trim()
                            );

                    max =
                            min;

                    chance =
                            Double.parseDouble(
                                    fields[2].trim()
                            );
                }

                /*
                 * 数量最少 0。
                 */
                if (min < 0) {
                    min = 0;
                }

                if (max < min) {
                    max = min;
                }

                /*
                 * 概率限制在 0~1。
                 */
                if (chance < 0.0D) {
                    chance = 0.0D;
                }

                if (chance > 1.0D) {
                    chance = 1.0D;
                }

                result.add(
                        new NetCraftDropManager.DropEntry(
                                itemId,
                                min,
                                max,
                                chance
                        )
                );

            } catch (NumberFormatException e) {

                LOGGER.warn(
                        "[NetCraftToolkit] 掉落数字解析失败: {}",
                        entry
                );
            }
        }

        return result;
    }

    /*
     * ============================================================
     * Boolean
     * ============================================================
     */

    private boolean parseBoolean(
            String value
    ) {

        if (value == null) {
            return false;
        }

        String normalized =
                value.trim()
                        .toLowerCase(
                                Locale.ROOT
                        );

        return normalized.equals("true")
                || normalized.equals("yes")
                || normalized.equals("1")
                || normalized.equals("on");
    }

    /*
     * ============================================================
     * 保存配置
     * ============================================================
     */

    public synchronized void save() {

        if (configPath == null) {
            return;
        }

        try {

            Files.createDirectories(
                    configPath.getParent()
            );

            StringBuilder out =
                    new StringBuilder();

            out.append(
                    "# ==================================================\n"
            );

            out.append(
                    "# NetCraft Toolkit 生物配置\n"
            );

            out.append(
                    "# ==================================================\n"
            );

            out.append(
                    "# -1 = 不修改\n"
            );

            out.append(
                    "# 属性会直接设置为指定 BaseValue\n"
            );

            out.append(
                    "# 掉落概率：1.0 = 100%，0.5 = 50%\n"
            );

            out.append(
                    "# 掉落格式：物品ID|最小数量|最大数量|概率\n"
            );

            out.append(
                    "# ==================================================\n\n"
            );

            List<String> ids =
                    new ArrayList<>(
                            entityValues.keySet()
                    );

            Collections.sort(ids);

            for (String entityId :
                    ids) {

                out.append("\n");

                out.append("[")
                        .append(entityId)
                        .append("]\n");

                Map<String, Double> values =
                        entityValues.get(
                                entityId
                        );

                if (values == null) {
                    continue;
                }

                for (String key :
                        ATTRIBUTES.keySet()) {

                    double value =
                            values.getOrDefault(
                                    key,
                                    -1.0D
                            );

                    out.append(key)
                            .append(" = ")
                            .append(
                                    formatDouble(
                                            value
                                    )
                            )
                            .append("\n");
                }

                List<NetCraftDropManager.DropEntry>
                        entityDrops =
                        drops.get(entityId);

                if (entityDrops != null
                        && !entityDrops.isEmpty()) {

                    out.append("\n");

                    out.append("drops = [");

                    for (int i = 0;
                         i < entityDrops.size();
                         i++) {

                        if (i > 0) {
                            out.append(", ");
                        }

                        NetCraftDropManager.DropEntry
                                drop =
                                entityDrops.get(i);

                        out.append(
                                        drop.itemId()
                                )
                                .append("|")
                                .append(
                                        drop.minCount()
                                )
                                .append("|")
                                .append(
                                        drop.maxCount()
                                )
                                .append("|")
                                .append(
                                        formatDouble(
                                                drop.chance()
                                        )
                                );
                    }

                    out.append("]\n");

                    out.append(
                                    "drops_replace = "
                            )
                            .append(
                                    dropReplace.getOrDefault(
                                            entityId,
                                            true
                                    )
                            )
                            .append("\n");
                }
            }

            Files.writeString(
                    configPath,
                    out.toString(),
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE
            );

            LOGGER.info(
                    "[NetCraftToolkit] 配置已保存: {}",
                    configPath
            );

        } catch (IOException e) {

            LOGGER.error(
                    "[NetCraftToolkit] 保存配置失败",
                    e
            );
        }
    }

    /*
     * ============================================================
     * Double 格式化
     * ============================================================
     */

    private String formatDouble(
            double value
    ) {

        if (Double.isNaN(value)
                || Double.isInfinite(value)) {

            return "-1.0";
        }

        if (value == Math.rint(value)) {

            return String.format(
                    Locale.ROOT,
                    "%.1f",
                    value
            );
        }

        return String.valueOf(value);
    }

    /*
     * ============================================================
     * 热加载
     * ============================================================
     */

    public void startWatching() {

        if (watching) {
            return;
        }

        if (configPath == null) {
            return;
        }

        watching = true;

        watcher.submit(() -> {

            try {

                Path directory =
                        configPath.getParent();

                if (directory == null) {
                    return;
                }

                Files.createDirectories(
                        directory
                );

                try (
                        WatchService watchService =
                                FileSystems
                                        .getDefault()
                                        .newWatchService()
                ) {

                    directory.register(
                            watchService,
                            StandardWatchEventKinds
                                    .ENTRY_MODIFY,
                            StandardWatchEventKinds
                                    .ENTRY_CREATE
                    );

                    while (
                            watching
                                    && !Thread.currentThread()
                                    .isInterrupted()
                    ) {

                        WatchKey key;

                        try {

                            key =
                                    watchService.take();

                        } catch (
                                InterruptedException e
                        ) {

                            Thread.currentThread()
                                    .interrupt();

                            break;
                        }

                        boolean changed =
                                false;

                        for (
                                WatchEvent<?> event :
                                key.pollEvents()
                        ) {

                            Object context =
                                    event.context();

                            if (!(context
                                    instanceof Path changedPath)) {

                                continue;
                            }

                            if (changedPath
                                    .getFileName()
                                    .equals(
                                            configPath
                                                    .getFileName()
                                    )) {

                                changed = true;
                            }
                        }

                        key.reset();

                        if (!changed) {
                            continue;
                        }

                        /*
                         * 防止手机/编辑器保存文件时，
                         * WatchService 过早触发。
                         */
                        try {

                            Thread.sleep(400);

                        } catch (
                                InterruptedException e
                        ) {

                            Thread.currentThread()
                                    .interrupt();

                            break;
                        }

                        LOGGER.info(
                                "[NetCraftToolkit] 检测到配置修改，开始热加载..."
                        );

                        /*
                         * 重新读取配置。
                         */
                        load();

                        /*
                         * Minecraft 对实体的修改必须回主线程。
                         */
                        MinecraftServer currentServer =
                                server;

                        if (currentServer != null) {

                            currentServer.execute(() -> {

                                NetCraftAttributeManager
                                        manager =
                                        NetCraftToolkit
                                                .getAttributeManager();

                                if (manager == null) {
                                    return;
                                }

                                manager.reloadAllEntities();

                                LOGGER.info(
                                        "[NetCraftToolkit] 热加载应用完成"
                                );
                            });
                        }
                    }
                }

            } catch (Throwable t) {

                LOGGER.error(
                        "[NetCraftToolkit] 配置监听线程异常",
                        t
                );

            } finally {

                watching = false;
            }
        });
    }

    /*
     * ============================================================
     * 停服
     * ============================================================
     */

    public void stopWatching() {

        watching = false;

        watcher.shutdownNow();
    }

    /*
     * ============================================================
     * Getter
     * ============================================================
     */

    public Map<String, Map<String, Double>>
    getEntityValues() {

        return Collections.unmodifiableMap(
                entityValues
        );
    }

    public Map<String,
            List<NetCraftDropManager.DropEntry>>
    getDrops() {

        return Collections.unmodifiableMap(
                drops
        );
    }

    public Map<String, Boolean>
    getDropReplace() {

        return Collections.unmodifiableMap(
                dropReplace
        );
    }

    public Path getConfigPath() {

        return configPath;
    }

    public MinecraftServer getServer() {

        return server;
    }

    public static Map<String, Attribute>
    getSupportedAttributes() {

        return Collections.unmodifiableMap(
                ATTRIBUTES
        );
    }
}
