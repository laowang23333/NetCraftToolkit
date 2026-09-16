package com.example.netcrafttoolkit;

import com.mojang.logging.LogUtils;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.level.Level;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.event.entity.living.LivingDropsEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.server.ServerLifecycleHooks;
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
     * NetCraft 生物允许修改的原版属性。
     *
     * 注意：
     * 这里全部使用 Minecraft 原版 Attribute。
     * 不直接引用 NetCraft 的任何 Java 类。
     */
    private static final LinkedHashMap<String, Attribute> ATTRIBUTES =
            new LinkedHashMap<>();

    static {
        ATTRIBUTES.put("max_health", Attributes.MAX_HEALTH);
        ATTRIBUTES.put("attack_damage", Attributes.ATTACK_DAMAGE);
        ATTRIBUTES.put("movement_speed", Attributes.MOVEMENT_SPEED);
        ATTRIBUTES.put("armor", Attributes.ARMOR);
        ATTRIBUTES.put("attack_speed", Attributes.ATTACK_SPEED);
        ATTRIBUTES.put("knockback_resistance", Attributes.KNOCKBACK_RESISTANCE);
        ATTRIBUTES.put("follow_range", Attributes.FOLLOW_RANGE);
    }

    /*
     * 玩家/生物实际使用的配置值。
     *
     * key:
     *   netcraft:entity_xxx
     *
     * value:
     *   属性名 -> 数值
     */
    private final Map<String, Map<String, Double>> entityValues =
            new LinkedHashMap<>();

    /*
     * 生物第一次被发现时记录的原版 Attribute。
     *
     * 作用：
     * 配置热加载时先恢复原始值，
     * 再重新应用配置。
     *
     * 防止：
     *
     * 100
     *   -> 配置 200
     *   -> 热加载再 +200
     *   -> 变成 300
     *
     * 我们这里采用：
     *
     * 原始 100
     *   -> 配置 200
     *   -> 热加载恢复 100
     *   -> 再设置 200
     */
    private final Map<String, Map<String, Double>> BASE_VALUES =
            new HashMap<>();

    /*
     * 自定义掉落。
     */
    private final Map<String, List<DropEntry>> drops =
            new LinkedHashMap<>();

    /*
     * 是否完全替换原版掉落。
     */
    private final Map<String, Boolean> dropReplace =
            new HashMap<>();

    private Path configPath;

    private MinecraftServer server;

    private final ExecutorService watcher = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "NetCraftToolkit-ConfigWatcher");
        thread.setDaemon(true);
        return thread;
    });

    private volatile boolean watching = false;

    public NetCraftConfig() {
    }

    /*
     * ============================================================
     * 掉落数据
     * ============================================================
     */

    public static class DropEntry {

        public final String itemId;
        public final int minCount;
        public final int maxCount;
        public final double chance;

        public DropEntry(
                String itemId,
                int minCount,
                int maxCount,
                double chance
        ) {
            this.itemId = itemId;
            this.minCount = minCount;
            this.maxCount = maxCount;
            this.chance = chance;
        }

        public int rollCount(RandomSource random) {
            if (maxCount <= minCount) {
                return minCount;
            }

            return minCount
                    + random.nextInt(maxCount - minCount + 1);
        }
    }

    /*
     * ============================================================
     * 初始化
     * ============================================================
     */

    public void init(MinecraftServer server) {
        this.server = server;

        this.configPath = server.getServerDirectory()
                .toPath()
                .resolve("config")
                .resolve(CONFIG_DIR)
                .resolve(CONFIG_FILE);

        try {
            Files.createDirectories(configPath.getParent());
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
     * 加载
     * ============================================================
     */

    public synchronized void load() {

        if (configPath == null) {
            LOGGER.warn("[NetCraftToolkit] configPath 尚未初始化");
            return;
        }

        try {

            if (!Files.exists(configPath)) {

                scanNetCraftTypes();

                save();

                return;
            }

            List<String> lines = Files.readAllLines(
                    configPath,
                    StandardCharsets.UTF_8
            );

            entityValues.clear();
            drops.clear();
            dropReplace.clear();

            String currentSection = null;

            for (String rawLine : lines) {

                String line = rawLine.trim();

                if (line.isEmpty()) {
                    continue;
                }

                if (line.startsWith("#")) {
                    continue;
                }

                /*
                 * [netcraft:xxx]
                 */
                if (line.startsWith("[")
                        && line.endsWith("]")) {

                    currentSection = line.substring(
                            1,
                            line.length() - 1
                    ).trim();

                    if (!currentSection
                            .toLowerCase(Locale.ROOT)
                            .startsWith(NAMESPACE + ":")) {

                        currentSection = null;
                    } else {

                        entityValues.computeIfAbsent(
                                currentSection,
                                k -> new LinkedHashMap<>()
                        );
                    }

                    continue;
                }

                if (currentSection == null) {
                    continue;
                }

                int equal = line.indexOf('=');

                if (equal <= 0) {
                    continue;
                }

                String key = line.substring(
                        0,
                        equal
                ).trim();

                String value = line.substring(
                        equal + 1
                ).trim();

                /*
                 * 去掉行尾注释。
                 *
                 * drops 比较特殊，
                 * 所以只对普通数值做处理。
                 */
                if (!key.equals("drops")) {

                    int comment = value.indexOf('#');

                    if (comment >= 0) {
                        value = value
                                .substring(0, comment)
                                .trim();
                    }
                }

                /*
                 * drops_replace
                 */
                if (key.equals("drops_replace")) {

                    dropReplace.put(
                            currentSection,
                            Boolean.parseBoolean(value)
                    );

                    continue;
                }

                /*
                 * drops
                 */
                if (key.equals("drops")) {

                    List<DropEntry> parsed =
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
                 * Attribute
                 */
                if (!ATTRIBUTES.containsKey(key)) {
                    continue;
                }

                try {

                    double number =
                            Double.parseDouble(value);

                    entityValues
                            .get(currentSection)
                            .put(key, number);

                } catch (NumberFormatException ignored) {

                    LOGGER.warn(
                            "[NetCraftToolkit] 无法解析数值: {} = {}",
                            key,
                            value
                    );
                }
            }

            /*
             * 配置加载完后，
             * 再扫描一次 NetCraft 注册表。
             *
             * 这样新版本 NetCraft 新增的实体
             * 也可以自动进入配置。
             */
            scanNetCraftTypes();

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
     * 扫描 NetCraft EntityType
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

            /*
             * 只认 netcraft 命名空间。
             *
             * 不 import NetCraft。
             */
            if (!NAMESPACE.equalsIgnoreCase(
                    id.getNamespace())) {

                continue;
            }

            EntityType<?> type =
                    ForgeRegistries.ENTITY_TYPES.getValue(id);

            if (type == null) {
                continue;
            }

            /*
             * 我们只处理 LivingEntity。
             *
             * 投射物、环境实体等不改 Attribute。
             */
            if (!isLivingEntityType(type)) {
                continue;
            }

            count++;

            String entityId = id.toString();

            Map<String, Double> base =
                    readDefaultAttributes(type);

            BASE_VALUES.putIfAbsent(
                    entityId,
                    new LinkedHashMap<>(base)
            );

            if (!entityValues.containsKey(entityId)) {

                Map<String, Double> values =
                        new LinkedHashMap<>(base);

                entityValues.put(
                        entityId,
                        values
                );

                added++;
            } else {

                Map<String, Double> values =
                        entityValues.get(entityId);

                /*
                 * 如果以后 Minecraft/NetCraft
                 * 新增 Attribute，则自动补进去。
                 */
                for (String key : ATTRIBUTES.keySet()) {

                    values.putIfAbsent(
                            key,
                            base.getOrDefault(key, -1.0)
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

    private boolean isLivingEntityType(
            EntityType<?> type
    ) {

        try {

            Class<?> entityClass =
                    type.getBaseClass();

            return LivingEntity.class
                    .isAssignableFrom(entityClass);

        } catch (Throwable ignored) {

            /*
             * 某些 Forge/Mohist 环境下
             * getBaseClass 可能表现不同。
             *
             * 这里不让扫描失败。
             */
            return true;
        }
    }

    /*
     * ============================================================
     * 读取原版默认 Attribute
     * ============================================================
     */

    private Map<String, Double> readDefaultAttributes(
            EntityType<?> type
    ) {

        Map<String, Double> result =
                new LinkedHashMap<>();

        /*
         * 这里只初始化为 -1。
         *
         * 真正实体生成后，
         * 我们会从 AttributeInstance
         * 再取得一次真实 base value。
         */
        for (String key : ATTRIBUTES.keySet()) {

            result.put(key, -1.0);
        }

        return result;
    }

    /*
     * ============================================================
     * EntityJoinLevelEvent
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

        /*
         * 第一次遇到实体时，
         * 记录它真实的原始 Attribute。
         */
        rememberBaseValues(living);

        /*
         * 应用配置。
         */
        applyTo(living);
    }

    /*
     * ============================================================
     * 判断是否 NetCraft
     * ============================================================
     */

    public boolean isNetCraftEntity(
            LivingEntity living
    ) {

        ResourceLocation key =
                ForgeRegistries.ENTITY_TYPES.getKey(
                        living.getType()
                );

        return key != null
                && NAMESPACE.equalsIgnoreCase(
                key.getNamespace()
        );
    }

    /*
     * ============================================================
     * 保存实体原始 Attribute
     * ============================================================
     */

    private void rememberBaseValues(
            LivingEntity living
    ) {

        ResourceLocation id =
                ForgeRegistries.ENTITY_TYPES.getKey(
                        living.getType()
                );

        if (id == null) {
            return;
        }

        String entityId =
                id.toString();

        Map<String, Double> base =
                BASE_VALUES.computeIfAbsent(
                        entityId,
                        k -> new LinkedHashMap<>()
                );

        for (Map.Entry<String, Attribute> entry :
                ATTRIBUTES.entrySet()) {

            String key = entry.getKey();
            Attribute attribute = entry.getValue();

            if (base.containsKey(key)
                    && base.get(key) != null
                    && base.get(key) > 0) {

                continue;
            }

            AttributeInstance instance =
                    living.getAttribute(attribute);

            if (instance == null) {
                base.put(key, -1.0);
                continue;
            }

            base.put(
                    key,
                    instance.getBaseValue()
            );
        }
    }

    /*
     * ============================================================
     * 应用属性
     * ============================================================
     */

    public void applyTo(
            LivingEntity living
    ) {

        if (!isNetCraftEntity(living)) {
            return;
        }

        ResourceLocation id =
                ForgeRegistries.ENTITY_TYPES.getKey(
                        living.getType()
                );

        if (id == null) {
            return;
        }

        String entityId =
                id.toString();

        Map<String, Double> values =
                entityValues.get(entityId);

        if (values == null) {
            return;
        }

        /*
         * 第一次应用之前先保存真实原值。
         */
        rememberBaseValues(living);

        Map<String, Double> base =
                BASE_VALUES.get(entityId);

        /*
         * 记录当前生命比例。
         *
         * 例如：
         *
         * 原最大生命 100
         * 当前生命 50
         *
         * 配置修改最大生命 1000
         *
         * 最后生命 = 500
         *
         * 而不是直接变成 1000。
         */
        float oldHealth =
                living.getHealth();

        float oldMax =
                living.getMaxHealth();

        double healthRatio = 1.0;

        if (oldMax > 0.0f) {

            healthRatio =
                    oldHealth / oldMax;

            if (healthRatio < 0.0) {
                healthRatio = 0.0;
            }

            if (healthRatio > 1.0) {
                healthRatio = 1.0;
            }
        }

        boolean changed = false;

        for (Map.Entry<String, Attribute> entry :
                ATTRIBUTES.entrySet()) {

            String key =
                    entry.getKey();

            Attribute attribute =
                    entry.getValue();

            Double configured =
                    values.get(key);

            if (configured == null) {
                continue;
            }

            /*
             * -1 = 不修改。
             */
            if (configured < 0.0) {
                continue;
            }

            AttributeInstance instance =
                    living.getAttribute(attribute);

            if (instance == null) {
                continue;
            }

            /*
             * 每次直接设置 BASE_VALUE。
             *
             * 不使用 +=。
             */
            instance.setBaseValue(
                    configured
            );

            changed = true;
        }

        if (!changed) {
            return;
        }

        /*
         * 最大生命变化后，
         * 按比例恢复当前生命。
         */
        float newMax =
                living.getMaxHealth();

        if (newMax > 0.0f) {

            float newHealth =
                    (float) (
                            newMax * healthRatio
                    );

            if (newHealth < 0.0f) {
                newHealth = 0.0f;
            }

            if (newHealth > newMax) {
                newHealth = newMax;
            }

            living.setHealth(
                    newHealth
            );
        }
    }

    /*
     * ============================================================
     * 掉落
     * ============================================================
     */

    @SubscribeEvent(priority = EventPriority.NORMAL)
    public void onLivingDrops(
            LivingDropsEvent event
    ) {

        if (event.getEntity()
                .level()
                .isClientSide()) {

            return;
        }

        LivingEntity entity =
                event.getEntity();

        if (!isNetCraftEntity(entity)) {
            return;
        }

        ResourceLocation id =
                ForgeRegistries.ENTITY_TYPES.getKey(
                        entity.getType()
                );

        if (id == null) {
            return;
        }

        String entityId =
                id.toString();

        List<DropEntry> entries =
                drops.get(entityId);

        if (entries == null
                || entries.isEmpty()) {

            return;
        }

        boolean replace =
                dropReplace.getOrDefault(
                        entityId,
                        true
                );

        /*
         * true：
         * 完全替换原版掉落。
         */
        if (replace) {
            event.getDrops().clear();
        }

        RandomSource random =
                entity.level().getRandom();

        int spawned = 0;

        for (DropEntry entry : entries) {

            if (entry.chance <= 0.0) {
                continue;
            }

            if (random.nextDouble()
                    > entry.chance) {

                continue;
            }

            ResourceLocation itemId;

            try {

                itemId =
                        ResourceLocation.parse(
                                entry.itemId
                        );

            } catch (Throwable t) {

                LOGGER.warn(
                        "[NetCraftToolkit] 非法掉落物ID: {}",
                        entry.itemId
                );

                continue;
            }

            Item item =
                    ForgeRegistries.ITEMS
                            .getValue(itemId);

            if (item == null) {

                LOGGER.warn(
                        "[NetCraftToolkit] 找不到掉落物: {}",
                        entry.itemId
                );

                continue;
            }

            int count =
                    entry.rollCount(random);

            if (count <= 0) {
                continue;
            }

            ItemStack stack =
                    new ItemStack(
                            item,
                            count
                    );

            double x =
                    entity.getX()
                            + (random.nextDouble() - 0.5)
                            * 0.8;

            double y =
                    entity.getY()
                            + 0.5;

            double z =
                    entity.getZ()
                            + (random.nextDouble() - 0.5)
                            * 0.8;

            ItemEntity itemEntity =
                    new ItemEntity(
                            entity.level(),
                            x,
                            y,
                            z,
                            stack
                    );

            itemEntity.setDefaultPickUpDelay();

            event.getDrops().add(
                    itemEntity
            );

            spawned++;
        }

        if (spawned > 0) {

            LOGGER.debug(
                    "[NetCraftToolkit] {} 生成 {} 项自定义掉落",
                    entityId,
                    spawned
            );
        }
    }

    /*
     * ============================================================
     * 掉落解析
     *
     * 格式：
     *
     * drops = [
     *   minecraft:diamond|1|3|0.5,
     *   netcraft:item_xxx|1|1|1.0
     * ]
     *
     * ============================================================
     */

    private List<DropEntry> parseDrops(
            String value
    ) {

        List<DropEntry> result =
                new ArrayList<>();

        if (value == null) {
            return result;
        }

        String text =
                value.trim();

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

        text = text.trim();

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

                    max = min;

                    chance =
                            Double.parseDouble(
                                    fields[2].trim()
                            );
                }

                if (min < 0) {
                    min = 0;
                }

                if (max < min) {
                    max = min;
                }

                if (chance < 0.0) {
                    chance = 0.0;
                }

                if (chance > 1.0) {
                    chance = 1.0;
                }

                result.add(
                        new DropEntry(
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
                    "# 修改保存后会自动热加载\n"
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

            for (String entityId : ids) {

                out.append("\n");
                out.append("[")
                        .append(entityId)
                        .append("]\n");

                Map<String, Double> values =
                        entityValues.get(entityId);

                for (String key :
                        ATTRIBUTES.keySet()) {

                    double value =
                            values.getOrDefault(
                                    key,
                                    -1.0
                            );

                    out.append(key)
                            .append(" = ")
                            .append(formatDouble(value))
                            .append("\n");
                }

                List<DropEntry> entityDrops =
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

                        DropEntry d =
                                entityDrops.get(i);

                        out.append(d.itemId)
                                .append("|")
                                .append(d.minCount)
                                .append("|")
                                .append(d.maxCount)
                                .append("|")
                                .append(formatDouble(d.chance));
                    }

                    out.append("]\n");

                    out.append("drops_replace = ")
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

                try (WatchService watchService =
                             FileSystems
                                     .getDefault()
                                     .newWatchService()) {

                    directory.register(
                            watchService,
                            StandardWatchEventKinds.ENTRY_MODIFY,
                            StandardWatchEventKinds.ENTRY_CREATE
                    );

                    while (watching
                            && !Thread.currentThread()
                            .isInterrupted()) {

                        WatchKey key;

                        try {
                            key =
                                    watchService.take();
                        } catch (InterruptedException e) {
                            Thread.currentThread()
                                    .interrupt();
                            break;
                        }

                        boolean changed =
                                false;

                        for (WatchEvent<?> event :
                                key.pollEvents()) {

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
                         * 防止编辑器连续写入
                         * 导致读到半截文件。
                         */
                        try {
                            Thread.sleep(400);
                        } catch (InterruptedException e) {
                            Thread.currentThread()
                                    .interrupt();
                            break;
                        }

                        LOGGER.info(
                                "[NetCraftToolkit] 检测到配置修改，开始热加载..."
                        );

                        load();

                        /*
                         * Attribute 修改必须回到
                         * Minecraft 主线程。
                         */
                        MinecraftServer current =
                                server;

                        if (current != null) {

                            current.execute(() -> {

                                for (var level :
                                        current.getAllLevels()) {

                                    for (var entity :
                                            level.getEntities()
                                                    .getAll()) {

                                        if (entity
                                                instanceof LivingEntity living
                                                && isNetCraftEntity(living)) {

                                            rememberBaseValues(
                                                    living
                                            );

                                            applyTo(
                                                    living
                                            );
                                        }
                                    }
                                }

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

    @SubscribeEvent
    public void onServerStopping(
            ServerStoppingEvent event
    ) {

        watching = false;

        watcher.shutdownNow();

        server = null;
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

    public Map<String, List<DropEntry>>
    getDrops() {

        return Collections.unmodifiableMap(
                drops
        );
    }

    public Path getConfigPath() {
        return configPath;
    }
}
