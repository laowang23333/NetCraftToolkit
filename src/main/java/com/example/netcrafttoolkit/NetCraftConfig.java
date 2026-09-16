package com.example.netcrafttoolkit;

import com.mojang.logging.LogUtils;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.attributes.DefaultAttributes;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;
import net.minecraftforge.fml.loading.FMLPaths;
import net.minecraftforge.registries.ForgeRegistries;
import org.slf4j.Logger;

import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.nio.file.FileSystems;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class NetCraftConfig {

    private static final Logger LOGGER =
            LogUtils.getLogger();

    private static final String TARGET_NAMESPACE =
            "netcraft";

    private static final String CONFIG_DIRECTORY =
            "netcrafttoolkit";

    private static final String CONFIG_FILE =
            "netcraft-attributes.toml";

    /*
     * ==============================
     * 生物属性
     * ==============================
     */
    private static final LinkedHashMap<String, String> ATTR_INFO =
            new LinkedHashMap<>();

    static {
        ATTR_INFO.put("max_health", "最大生命值");
        ATTR_INFO.put("attack_damage", "攻击伤害");
        ATTR_INFO.put("movement_speed", "移动速度");
        ATTR_INFO.put("armor", "护甲值");
        ATTR_INFO.put("attack_speed", "攻击速度");
        ATTR_INFO.put("knockback_resistance", "击退抗性");
        ATTR_INFO.put("follow_range", "跟随范围");
    }

    private static final LinkedHashMap<String, Attribute> ATTR_MAP =
            new LinkedHashMap<>();

    static {
        ATTR_MAP.put(
                "max_health",
                Attributes.MAX_HEALTH
        );

        ATTR_MAP.put(
                "attack_damage",
                Attributes.ATTACK_DAMAGE
        );

        ATTR_MAP.put(
                "movement_speed",
                Attributes.MOVEMENT_SPEED
        );

        ATTR_MAP.put(
                "armor",
                Attributes.ARMOR
        );

        ATTR_MAP.put(
                "attack_speed",
                Attributes.ATTACK_SPEED
        );

        ATTR_MAP.put(
                "knockback_resistance",
                Attributes.KNOCKBACK_RESISTANCE
        );

        ATTR_MAP.put(
                "follow_range",
                Attributes.FOLLOW_RANGE
        );
    }

    /*
     * ==============================
     * 装备属性
     * ==============================
     */
    private static final String[] EQUIP_STAT_ORDER = {
            "meleeDamage",
            "rangedDamage",
            "magicDamage",
            "physicalDefense",
            "magicDefense",
            "health",
            "armor"
    };

    private static final LinkedHashMap<String, String> EQUIP_STAT_CN =
            new LinkedHashMap<>();

    static {
        EQUIP_STAT_CN.put(
                "meleeDamage",
                "近战伤害"
        );

        EQUIP_STAT_CN.put(
                "rangedDamage",
                "远程伤害"
        );

        EQUIP_STAT_CN.put(
                "magicDamage",
                "魔法伤害"
        );

        EQUIP_STAT_CN.put(
                "physicalDefense",
                "物理防御"
        );

        EQUIP_STAT_CN.put(
                "magicDefense",
                "魔法防御"
        );

        EQUIP_STAT_CN.put(
                "health",
                "最大生命值"
        );

        EQUIP_STAT_CN.put(
                "armor",
                "护甲值"
        );
    }

    private static final Pattern TOOLTIP_ATTR_PATTERN =
            Pattern.compile(
                    "^([^\\d\\s:：+\\-]+)([+\\-]?\\d+(?:\\.\\d+)?)$"
            );

    /*
     * ==============================
     * 配置数据
     * ==============================
     */
    private Path configPath;

    private MinecraftServer currentServer;

    private final Map<String, Map<String, Double>>
            entityValues =
            new LinkedHashMap<>();

    private final Map<String, Map<String, Double>>
            defaults =
            new LinkedHashMap<>();

    private final Map<String, List<NetCraftDropManager.DropEntry>>
            drops =
            new LinkedHashMap<>();

    private final Map<String, Boolean>
            dropReplace =
            new LinkedHashMap<>();

    /*
     * 装备默认值。
     *
     * key：
     *
     * tier.job.slot
     */
    private final Map<String, Map<String, Integer>>
            equipmentDefaults =
            new LinkedHashMap<>();

    /*
     * 用户修改值。
     *
     * -1 = 不覆盖
     * >=0 = 覆盖
     */
    private final Map<String, Map<String, Integer>>
            equipmentOverrides =
            new LinkedHashMap<>();

    /*
     * 装备显示名称。
     */
    private final Map<String, String>
            equipmentStructNames =
            new LinkedHashMap<>();

    /*
     * ==============================
     * 文件监听
     * ==============================
     */
    private final ExecutorService watcher =
            Executors.newSingleThreadExecutor(
                    r -> {

                        Thread thread =
                                new Thread(
                                        r,
                                        "NetCraftToolkit-ConfigWatcher"
                                );

                        thread.setDaemon(true);

                        return thread;
                    }
            );

    /*
     * ==============================
     * 初始化
     * ==============================
     */
    public void init(
            MinecraftServer server
    ) {

        this.currentServer = server;

        if (server != null) {

            this.configPath =
                    server.getServerDirectory()
                            .toPath()
                            .resolve("config")
                            .resolve(CONFIG_DIRECTORY)
                            .resolve(CONFIG_FILE);

        } else {

            this.configPath =
                    FMLPaths.CONFIGDIR.get()
                            .resolve(CONFIG_DIRECTORY)
                            .resolve(CONFIG_FILE);
        }

        try {

            Files.createDirectories(
                    configPath.getParent()
            );

        } catch (IOException e) {

            LOGGER.error(
                    "[NetCraftToolkit] 创建配置目录失败",
                    e
            );
        }
    }

    /*
     * ==============================
     * 加载配置
     * ==============================
     */
    public synchronized void load() {

        if (configPath == null) {
            return;
        }

        try {

            if (!Files.exists(configPath)) {

                scanNetCraftTypes();

                save();

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
            equipmentOverrides.clear();

            String currentSection = null;

            boolean inEquipmentStat =
                    false;

            String curTier = null;
            String curJob = null;
            String curSlot = null;

            int equipmentOverrideCount = 0;

            for (String raw : lines) {

                String line =
                        raw.trim();

                if (
                        line.isEmpty()
                                || line.startsWith("#")
                ) {

                    continue;
                }

                /*
                 * ==========================
                 * Section
                 * ==========================
                 */
                if (
                        line.startsWith("[")
                                && line.endsWith("]")
                ) {

                    String section =
                            line.substring(
                                    1,
                                    line.length() - 1
                            ).trim();

                    currentSection =
                            section;

                    inEquipmentStat =
                            section.equals(
                                    "equipmentStat"
                            )
                                    || section.startsWith(
                                    "equipmentStat."
                            );

                    curTier = null;
                    curJob = null;
                    curSlot = null;

                    /*
                     * equipmentStat
                     */
                    if (inEquipmentStat) {

                        String[] parts =
                                section.split("\\.");

                        if (parts.length == 2) {

                            curTier =
                                    normalizeTier(
                                            parts[1]
                                    );

                        } else if (
                                parts.length == 3
                        ) {

                            curTier =
                                    normalizeTier(
                                            parts[1]
                                    );

                            curJob =
                                    parts[2];

                        } else if (
                                parts.length == 4
                        ) {

                            curTier =
                                    normalizeTier(
                                            parts[1]
                                    );

                            curJob =
                                    parts[2];

                            curSlot =
                                    parts[3];
                        }

                        continue;
                    }

                    /*
                     * 生物配置段。
                     */
                    entityValues.putIfAbsent(
                            section,
                            new LinkedHashMap<>()
                    );

                    continue;
                }

                int eq =
                        line.indexOf('=');

                if (eq < 0) {
                    continue;
                }

                String key =
                        line.substring(
                                0,
                                eq
                        ).trim();

                String value =
                        line.substring(
                                eq + 1
                        ).trim();

                /*
                 * 去掉行尾注释。
                 */
                int hash =
                        value.indexOf('#');

                if (hash >= 0) {

                    value =
                            value.substring(
                                    0,
                                    hash
                            ).trim();
                }

                /*
                 * ==========================
                 * 装备属性
                 * ==========================
                 */
                if (inEquipmentStat) {

                    /*
                     * 总开关不属于装备条目。
                     */
                    if (
                            key.equals(
                                    "enableEquipmentStatOverride"
                            )
                    ) {

                        continue;
                    }

                    if (
                            curTier == null
                                    || curJob == null
                                    || curSlot == null
                    ) {

                        continue;
                    }

                    try {

                        int val =
                                (int) Double.parseDouble(
                                        value
                                );

                        String mapKey =
                                curTier
                                        + "."
                                        + curJob
                                        + "."
                                        + curSlot;

                        Map<String, Integer> map =
                                equipmentOverrides
                                        .computeIfAbsent(
                                                mapKey,
                                                k -> createEmptyEquipmentMap()
                                        );

                        map.put(
                                key,
                                val
                        );

                        if (val >= 0) {
                            equipmentOverrideCount++;
                        }

                    } catch (
                            NumberFormatException ignored
                    ) {
                    }

                    continue;
                }

                if (currentSection == null) {
                    continue;
                }

                /*
                 * ==========================
                 * 掉落替换
                 * ==========================
                 */
                if (
                        key.equals(
                                "drops_replace"
                        )
                ) {

                    dropReplace.put(
                            currentSection,
                            "true".equalsIgnoreCase(
                                    value
                            )
                    );

                    continue;
                }

                /*
                 * ==========================
                 * 掉落
                 * ==========================
                 */
                if (
                        key.equals(
                                "drops"
                        )
                ) {

                    List<NetCraftDropManager.DropEntry>
                            entries =
                            parseDrops(value);

                    if (!entries.isEmpty()) {

                        drops.put(
                                currentSection,
                                entries
                        );
                    }

                    continue;
                }

                /*
                 * ==========================
                 * 生物属性
                 * ==========================
                 */
                try {

                    double number =
                            Double.parseDouble(
                                    value
                            );

                    entityValues
                            .computeIfAbsent(
                                    currentSection,
                                    k -> new LinkedHashMap<>()
                            )
                            .put(
                                    key,
                                    number
                            );

                } catch (
                        NumberFormatException ignored
                ) {
                }
            }

            /*
             * 同步掉落管理器。
             */
            syncDropManager();

            LOGGER.info(
                    "[NetCraftToolkit] 配置加载完成：{} 生物 / {} 掉落 / {} 装备覆盖（{} 条有效覆盖）",
                    entityValues.size(),
                    drops.size(),
                    equipmentOverrides.size(),
                    equipmentOverrideCount
            );

        } catch (Exception e) {

            LOGGER.error(
                    "[NetCraftToolkit] 加载配置失败",
                    e
            );
        }
    }

    /*
     * ==============================
     * 保存配置
     * ==============================
     */
    public synchronized void save() {

        if (configPath == null) {
            return;
        }

        try {

            Files.createDirectories(
                    configPath.getParent()
            );

            StringBuilder sb =
                    new StringBuilder();

            sb.append(
                    "# ================================================================\n"
            );

            sb.append(
                    "# NetCraft Toolkit 配置文件\n"
            );

            sb.append(
                    "# ================================================================\n"
            );

            sb.append(
                    "# 生物属性：\n"
            );

            sb.append(
                    "# - 修改对应数值即可覆盖 NetCraft 生物属性\n"
            );

            sb.append(
                    "# - 不需要修改的属性可以使用 -1\n\n"
            );

            /*
             * ==========================
             * 生物
             * ==========================
             */
            for (
                    Map.Entry<String, Map<String, Double>>
                            entry :
                            entityValues.entrySet()
            ) {

                String entityId =
                        entry.getKey();

                sb.append("\n[")
                        .append(entityId)
                        .append("]\n");

                Map<String, Double> values =
                        entry.getValue();

                Map<String, Double> defs =
                        defaults.getOrDefault(
                                entityId,
                                Collections.emptyMap()
                        );

                for (
                        String attr :
                        ATTR_INFO.keySet()
                ) {

                    double value =
                            values.getOrDefault(
                                    attr,
                                    defs.getOrDefault(
                                            attr,
                                            -1.0
                                    )
                            );

                    sb.append(attr)
                            .append(" = ")
                            .append(formatDouble(value))
                            .append("        # ")
                            .append(
                                    ATTR_INFO.get(attr)
                            )
                            .append("\n");
                }

                List<NetCraftDropManager.DropEntry>
                        dropList =
                        drops.get(entityId);

                if (
                        dropList != null
                                && !dropList.isEmpty()
                ) {

                    sb.append(
                            "drops = \""
                    );

                    boolean first = true;

                    for (
                            NetCraftDropManager.DropEntry drop :
                            dropList
                    ) {

                        if (!first) {
                            sb.append(",");
                        }

                        first = false;

                        sb.append(
                                drop.itemId
                        )
                                .append("|")
                                .append(drop.minCount)
                                .append("|")
                                .append(drop.maxCount)
                                .append("|")
                                .append(drop.chance);
                    }

                    sb.append("\"\n");

                    if (
                            dropReplace.getOrDefault(
                                    entityId,
                                    false
                            )
                    ) {

                        sb.append(
                                "drops_replace = true\n"
                        );
                    }
                }
            }

            /*
             * ==========================
             * 装备
             * ==========================
             */
            writeEquipmentStatSection(
                    sb
            );

            try (
                    Writer writer =
                            Files.newBufferedWriter(
                                    configPath,
                                    StandardCharsets.UTF_8
                            )
            ) {

                writer.write(
                        sb.toString()
                );
            }

            LOGGER.info(
                    "[NetCraftToolkit] 配置已保存：{}",
                    configPath.toAbsolutePath()
            );

        } catch (IOException e) {

            LOGGER.error(
                    "[NetCraftToolkit] 保存配置失败",
                    e
            );
        }
    }

    /*
     * ==============================
     * 装备配置写入
     * ==============================
     */
    private void writeEquipmentStatSection(
            StringBuilder sb
    ) {

        sb.append(
                "\n\n# ================================================================\n"
        );

        sb.append(
                "# NetCraft 装备属性覆盖\n"
        );

        sb.append(
                "# ================================================================\n"
        );

        sb.append(
                "# -1 = 不覆盖，使用 NetCraft 原值\n"
        );

        sb.append(
                "# >=0 = 覆盖为指定值\n"
        );

        sb.append(
                "# 修改后进入存档会同步写入 serverconfig\n"
        );

        sb.append(
                "# ================================================================\n\n"
        );

        sb.append(
                "[equipmentStat]\n"
        );

        sb.append(
                "enableEquipmentStatOverride = true\n"
        );

        Set<String> allKeys =
                new TreeSet<>(
                        tierComparator()
                );

        allKeys.addAll(
                equipmentDefaults.keySet()
        );

        allKeys.addAll(
                equipmentOverrides.keySet()
        );

        String lastTier = null;

        for (String key : allKeys) {

            String[] parts =
                    key.split("\\.");

            if (parts.length != 3) {
                continue;
            }

            String tier =
                    parts[0];

            String job =
                    parts[1];

            String slot =
                    parts[2];

            if (
                    !tier.equals(
                            lastTier
                    )
            ) {

                sb.append(
                        "\n# ================================================================\n"
                );

                sb.append(
                        "# "
                )
                        .append(
                                tierCn(tier)
                        )
                        .append(
                                "（"
                        )
                        .append(tier)
                        .append(
                                "）\n"
                        );

                sb.append(
                        "# ================================================================\n"
                );

                lastTier = tier;
            }

            String itemName =
                    equipmentStructNames.get(
                            key
                    );

            Map<String, Integer> defs =
                    equipmentDefaults.getOrDefault(
                            key,
                            Collections.emptyMap()
                    );

            Map<String, Integer> userVals =
                    equipmentOverrides.getOrDefault(
                            key,
                            Collections.emptyMap()
                    );

            sb.append(
                    "\n[equipmentStat."
            )
                    .append(tier)
                    .append(".")
                    .append(job)
                    .append(".")
                    .append(slot)
                    .append("]\n");

            if (
                    itemName != null
                            && !itemName.isEmpty()
            ) {

                sb.append(
                        "# "
                )
                        .append(itemName)
                        .append("\n");
            }

            for (
                    String stat :
                    EQUIP_STAT_ORDER
            ) {

                int defaultValue =
                        defs.getOrDefault(
                                stat,
                                0
                        );

                int userValue =
                        userVals.getOrDefault(
                                stat,
                                -1
                        );

                sb.append(stat)
                        .append(" = ")
                        .append(userValue)
                        .append("        # ")
                        .append(
                                EQUIP_STAT_CN.get(
                                        stat
                                )
                        )
                        .append(
                                "（默认: "
                        )
                        .append(defaultValue)
                        .append(
                                "）\n"
                        );
            }
        }
    }

    /*
     * ==============================
     * 扫描 NetCraft 生物
     * ==============================
     */
    public void scanNetCraftTypes() {

        defaults.clear();

        int total = 0;
        int added = 0;

        try {

            for (
                    ResourceLocation id :
                    ForgeRegistries.ENTITY_TYPES.getKeys()
            ) {

                if (
                        id == null
                                || !TARGET_NAMESPACE.equalsIgnoreCase(
                                id.getNamespace()
                        )
                ) {

                    continue;
                }

                EntityType<?> type =
                        ForgeRegistries.ENTITY_TYPES.getValue(
                                id
                        );

                if (type == null) {
                    continue;
                }

                total++;

                String key =
                        id.toString();

                Map<String, Double>
                        defaultAttrs =
                        readDefaultsSafely(
                                type
                        );

                defaults.put(
                        key,
                        defaultAttrs
                );

                Map<String, Double>
                        existing =
                        entityValues.computeIfAbsent(
                                key,
                                k -> {
                                    added++;
                                    return new LinkedHashMap<>();
                                }
                        );

                /*
                 * 新属性自动补进去。
                 */
                for (
                        String attr :
                        ATTR_INFO.keySet()
                ) {

                    if (
                            !existing.containsKey(
                                    attr
                            )
                    ) {

                        existing.put(
                                attr,
                                defaultAttrs.getOrDefault(
                                        attr,
                                        -1.0
                                )
                        );
                    }
                }
            }

        } catch (Throwable t) {

            LOGGER.error(
                    "[NetCraftToolkit] 扫描 NetCraft 生物失败",
                    t
            );
        }

        LOGGER.info(
                "[NetCraftToolkit] NetCraft 生物扫描完成：{} 个，新增 {} 条",
                total,
                added
        );

        /*
         * 同时扫描装备。
         */
        scanNetCraftEquipment();
    }

    /*
     * ==============================
     * 扫描 NetCraft 装备
     * ==============================
     */
    public void scanNetCraftEquipment() {

        equipmentDefaults.clear();

        equipmentStructNames.clear();

        if (
                !isNetCraftLoaded()
        ) {

            return;
        }

        int total = 0;
        int equipmentCount = 0;

        try {

            for (
                    Item item :
                    ForgeRegistries.ITEMS.getValues()
            ) {

                ResourceLocation id =
                        ForgeRegistries.ITEMS.getKey(
                                item
                        );

                if (
                        id == null
                                || !TARGET_NAMESPACE.equalsIgnoreCase(
                                id.getNamespace()
                        )
                ) {

                    continue;
                }

                total++;

                String itemId =
                        id.toString();

                String[] parsed =
                        parseEquipId(
                                itemId
                        );

                if (parsed == null) {
                    continue;
                }

                ItemStack stack =
                        new ItemStack(item);

                Map<String, Integer>
                        stats =
                        readEquipmentStats(
                                stack
                        );

                String displayName = "";

                try {

                    displayName =
                            stack.getHoverName()
                                    .getString();

                    if (
                            displayName.equals(
                                    itemId
                            )
                    ) {

                        displayName = "";
                    }

                } catch (Throwable ignored) {
                }

                String structKey =
                        parsed[0]
                                + "."
                                + parsed[1]
                                + "."
                                + parsed[2];

                equipmentStructNames.put(
                        structKey,
                        displayName
                );

                equipmentDefaults.put(
                        structKey,
                        stats
                );

                equipmentOverrides.computeIfAbsent(
                        structKey,
                        k -> createEmptyEquipmentMap()
                );

                equipmentCount++;
            }

        } catch (Throwable t) {

            LOGGER.error(
                    "[NetCraftToolkit] 扫描 NetCraft 装备失败",
                    t
            );
        }

        LOGGER.info(
                "[NetCraftToolkit] NetCraft 装备扫描完成：{} 个物品，{} 件装备",
                total,
                equipmentCount
        );
    }

    /*
     * ==============================
     * 装备 ID 解析
     * ==============================
     */
    private static String[] parseEquipId(
            String itemId
    ) {

        String path =
                itemId;

        int colon =
                itemId.indexOf(':');

        if (colon >= 0) {

            path =
                    itemId.substring(
                            colon + 1
                    );
        }

        /*
         * weapon_职业_tier_mhand
         * weapon_职业_tier_hand
         */
        if (
                path.startsWith(
                        "weapon_"
                )
        ) {

            String[] parts =
                    path.split("_");

            if (parts.length < 4) {
                return null;
            }

            String job =
                    parts[1];

            String tier =
                    normalizeTier(
                            parts[2]
                    );

            String hand =
                    parts[3];

            String slot;

            if (
                    hand.equalsIgnoreCase(
                            "mhand"
                    )
            ) {

                slot = "mainhand";

            } else if (
                    hand.equalsIgnoreCase(
                            "hand"
                    )
            ) {

                slot = "offhand";

            } else {

                return null;
            }

            return new String[]{
                    tier,
                    job,
                    slot
            };
        }

        /*
         * equipment_职业_tier_1~4
         */
        if (
                path.startsWith(
                        "equipment_"
                )
        ) {

            String[] parts =
                    path.split("_");

            if (parts.length < 4) {
                return null;
            }

            String job =
                    parts[1];

            String tier =
                    normalizeTier(
                            parts[2]
                    );

            String number =
                    parts[3];

            String slot;

            switch (number) {

                case "1":
                    slot = "helmet";
                    break;

                case "2":
                    slot = "chestplate";
                    break;

                case "3":
                    slot = "leggings";
                    break;

                case "4":
                    slot = "boots";
                    break;

                default:
                    return null;
            }

            return new String[]{
                    tier,
                    job,
                    slot
            };
        }

        return null;
    }

    /*
     * ==============================
     * 读取装备属性
     * ==============================
     */
    private Map<String, Integer> readEquipmentStats(
            ItemStack stack
    ) {

        Map<String, Integer> result =
                createZeroEquipmentMap();

        /*
         * 先读取 Minecraft AttributeModifier。
         */
        try {

            for (
                    EquipmentSlot slot :
                    EquipmentSlot.values()
            ) {

                var modifiers =
                        stack.getAttributeModifiers(
                                slot
                        );

                if (modifiers == null) {
                    continue;
                }

                for (
                        var entry :
                        modifiers.entries()
                ) {

                    Attribute attribute =
                            entry.getKey();

                    AttributeModifier modifier =
                            entry.getValue();

                    ResourceLocation id =
                            ForgeRegistries.ATTRIBUTES.getKey(
                                    attribute
                            );

                    if (id == null) {
                        continue;
                    }

                    String path =
                            id.getPath();

                    double amount =
                            modifier.getAmount();

                    if (
                            path.equals(
                                    "generic.max_health"
                            )
                    ) {

                        result.put(
                                "health",
                                (int) Math.round(
                                        amount
                                )
                        );

                    } else if (
                            path.equals(
                                    "generic.armor"
                            )
                    ) {

                        result.put(
                                "armor",
                                (int) Math.round(
                                        amount
                                )
                        );
                    }
                }
            }

        } catch (Throwable ignored) {
        }

        /*
         * 再读取 NetCraft 自己显示在 Lore / Tooltip
         * 里的属性。
         */
        try {

            List<Component> tooltip =
                    stack.getTooltipLines(
                            null,
                            TooltipFlag.Default.NORMAL
                    );

            for (
                    Component component :
                    tooltip
            ) {

                if (component == null) {
                    continue;
                }

                String text =
                        component.getString();

                if (
                        text == null
                                || text.isEmpty()
                ) {

                    continue;
                }

                text =
                        text.replaceAll(
                                "§.",
                                ""
                        ).trim();

                Matcher matcher =
                        TOOLTIP_ATTR_PATTERN.matcher(
                                text
                        );

                if (
                        matcher.matches()
                ) {

                    String name =
                            matcher.group(
                                    1
                            ).trim();

                    int value;

                    try {

                        value =
                                (int) Double.parseDouble(
                                        matcher.group(
                                                2
                                        )
                                );

                    } catch (
                            NumberFormatException e
                    ) {

                        continue;
                    }

                    String key =
                            cnToStatKey(
                                    name
                            );

                    if (key != null) {

                        result.put(
                                key,
                                Math.abs(value)
                        );
                    }

                    continue;
                }

                /*
                 * 兼容：
                 *
                 * +10 近战伤害
                 */
                if (
                        text.startsWith("+")
                                || text.startsWith("-")
                ) {

                    int space =
                            text.indexOf(' ');

                    if (space > 0) {

                        try {

                            int value =
                                    (int) Double.parseDouble(
                                            text.substring(
                                                    0,
                                                    space
                                            ).trim()
                                    );

                            String name =
                                    text.substring(
                                            space + 1
                                    ).trim();

                            String key =
                                    cnToStatKey(
                                            name
                                    );

                            if (key != null) {

                                result.put(
                                        key,
                                        Math.abs(value)
                                );
                            }

                        } catch (
                                NumberFormatException ignored
                        ) {
                        }
                    }
                }
            }

        } catch (Throwable ignored) {
        }

        return result;
    }

    /*
     * ==============================
     * 中文属性名转换
     * ==============================
     */
    private static String cnToStatKey(
            String name
    ) {

        switch (name) {

            case "近战伤害":
                return "meleeDamage";

            case "远程伤害":
                return "rangedDamage";

            case "魔法伤害":
                return "magicDamage";

            case "物理防御":
                return "physicalDefense";

            case "魔法防御":
                return "magicDefense";

            case "最大生命值":
                return "health";

            case "护甲值":
                return "armor";

            default:
                return null;
        }
    }

    /*
     * ==============================
     * 生物默认属性
     * ==============================
     */
    private Map<String, Double> readDefaultsSafely(
            EntityType<?> entityType
    ) {

        Map<String, Double> result =
                new LinkedHashMap<>();

        try {

            @SuppressWarnings("unchecked")
            EntityType<? extends LivingEntity>
                    livingType =
                    (EntityType<? extends LivingEntity>)
                            entityType;

            AttributeSupplier supplier =
                    DefaultAttributes.getSupplier(
                            livingType
                    );

            if (supplier != null) {

                for (
                        Map.Entry<String, Attribute>
                                entry :
                                ATTR_MAP.entrySet()
                ) {

                    if (
                            supplier.hasAttribute(
                                    entry.getValue()
                            )
                    ) {

                        result.put(
                                entry.getKey(),
                                supplier.getValue(
                                        entry.getValue()
                                )
                        );

                    } else {

                        result.put(
                                entry.getKey(),
                                -1.0
                        );
                    }
                }

                return result;
            }

        } catch (Throwable ignored) {
        }

        for (
                String attr :
                ATTR_INFO.keySet()
        ) {

            result.put(
                    attr,
                    -1.0
            );
        }

        return result;
    }

    /*
     * ==============================
     * 掉落解析
     * ==============================
     */
    private static List<NetCraftDropManager.DropEntry>
    parseDrops(
            String value
    ) {

        List<NetCraftDropManager.DropEntry>
                list =
                new ArrayList<>();

        if (value == null) {
            return list;
        }

        String text =
                value.trim();

        /*
         * 支持：
         *
         * ["minecraft:diamond|1|2|0.5"]
         *
         * 以及：
         *
         * "minecraft:diamond|1|2|0.5"
         */
        if (
                text.startsWith("[")
                        && text.endsWith("]")
        ) {

            text =
                    text.substring(
                            1,
                            text.length() - 1
                    );
        }

        text =
                text.replace(
                        "\"",
                        ""
                ).trim();

        if (text.isEmpty()) {
            return list;
        }

        for (
                String part :
                text.split(",")
        ) {

            part =
                    part.trim();

            if (part.isEmpty()) {
                continue;
            }

            String[] fields =
                    part.split("\\|");

            if (fields.length < 3) {

                LOGGER.warn(
                        "[NetCraftToolkit] 掉落条目字段不足：{}",
                        part
                );

                continue;
            }

            String itemId =
                    fields[0].trim();

            if (itemId.isEmpty()) {
                continue;
            }

            int minCount;
            int maxCount;
            double chance;

            try {

                if (fields.length >= 4) {

                    minCount =
                            Integer.parseInt(
                                    fields[1].trim()
                            );

                    maxCount =
                            Integer.parseInt(
                                    fields[2].trim()
                            );

                    chance =
                            Double.parseDouble(
                                    fields[3].trim()
                            );

                } else {

                    minCount =
                            Integer.parseInt(
                                    fields[1].trim()
                            );

                    maxCount =
                            minCount;

                    chance =
                            Double.parseDouble(
                                    fields[2].trim()
                            );
                }

            } catch (
                    NumberFormatException e
            ) {

                LOGGER.warn(
                        "[NetCraftToolkit] 掉落数字解析失败：{}",
                        part
                );

                continue;
            }

            minCount =
                    Math.max(
                            0,
                            minCount
                    );

            maxCount =
                    Math.max(
                            minCount,
                            maxCount
                    );

            chance =
                    Math.max(
                            0.0,
                            Math.min(
                                    1.0,
                                    chance
                            )
                    );

            list.add(
                    new NetCraftDropManager.DropEntry(
                            itemId,
                            minCount,
                            maxCount,
                            chance
                    )
            );
        }

        return list;
    }

    /*
     * ==============================
     * 掉落同步
     * ==============================
     */
    private void syncDropManager() {

        NetCraftDropManager manager =
                NetCraftToolkit.getDropManager();

        if (manager == null) {
            return;
        }

        manager.clear();

        for (
                Map.Entry<String, List<NetCraftDropManager.DropEntry>>
                        entry :
                        drops.entrySet()
        ) {

            boolean replace =
                    dropReplace.getOrDefault(
                            entry.getKey(),
                            false
                    );

            manager.setDropConfig(
                    entry.getKey(),
                    new NetCraftDropManager.DropConfig(
                            entry.getValue(),
                            replace
                    )
            );
        }
    }

    /*
     * ==============================
     * 热重载
     * ==============================
     */
    public void startWatching() {

        watcher.submit(() -> {

            try {

                if (configPath == null) {
                    return;
                }

                Path dir =
                        configPath.getParent();

                if (dir == null) {
                    return;
                }

                Files.createDirectories(
                        dir
                );

                try (
                        WatchService service =
                                FileSystems
                                        .getDefault()
                                        .newWatchService()
                ) {

                    dir.register(
                            service,
                            StandardWatchEventKinds.ENTRY_MODIFY,
                            StandardWatchEventKinds.ENTRY_CREATE
                    );

                    while (
                            !Thread.currentThread()
                                    .isInterrupted()
                    ) {

                        WatchKey key =
                                service.take();

                        boolean changed =
                                false;

                        for (
                                WatchEvent<?> event :
                                key.pollEvents()
                        ) {

                            Object context =
                                    event.context();

                            if (!(context instanceof Path path)) {
                                continue;
                            }

                            if (
                                    path.getFileName()
                                            .toString()
                                            .equals(
                                                    configPath
                                                            .getFileName()
                                                            .toString()
                                            )
                            ) {

                                changed = true;
                            }
                        }

                        key.reset();

                        if (!changed) {
                            continue;
                        }

                        /*
                         * 给文件写入留一点时间。
                         */
                        Thread.sleep(300);

                        load();

                        MinecraftServer server =
                                currentServer;

                        if (server != null) {

                            server.execute(() -> {

                                NetCraftAttributeManager manager =
                                        NetCraftToolkit
                                                .getAttributeManager();

                                if (manager != null) {

                                    manager.reloadAllEntities();
                                }

                                /*
                                 * 同步写入 NetCraft 装备 serverconfig。
                                 */
                                EquipmentOverrideWriter
                                        .applyToWorld(
                                                server,
                                                this
                                        );
                            });
                        }

                        LOGGER.info(
                                "[NetCraftToolkit] 配置热重载完成。"
                        );
                    }
                }

            } catch (
                    InterruptedException e
            ) {

                Thread.currentThread()
                        .interrupt();

            } catch (Exception e) {

                LOGGER.error(
                        "[NetCraftToolkit] 配置监听失败",
                        e
                );
            }
        });
    }

    /*
     * ==============================
     * 停止监听
     * ==============================
     */
    public void stopWatching() {

        watcher.shutdownNow();
    }

    /*
     * ==============================
     * NetCraft 是否存在
     * ==============================
     */
    private boolean isNetCraftLoaded() {

        try {

            return net.minecraftforge.fml.ModList
                    .get()
                    .isLoaded(
                            TARGET_NAMESPACE
                    );

        } catch (Throwable ignored) {

            return false;
        }
    }

    /*
     * ==============================
     * 装备空配置
     * ==============================
     */
    private static Map<String, Integer>
    createEmptyEquipmentMap() {

        Map<String, Integer> map =
                new LinkedHashMap<>();

        for (
                String stat :
                EQUIP_STAT_ORDER
        ) {

            map.put(
                    stat,
                    -1
            );
        }

        return map;
    }

    private static Map<String, Integer>
    createZeroEquipmentMap() {

        Map<String, Integer> map =
                new LinkedHashMap<>();

        for (
                String stat :
                EQUIP_STAT_ORDER
        ) {

            map.put(
                    stat,
                    0
            );
        }

        return map;
    }

    /*
     * ==============================
     * Tier 规范化
     * ==============================
     */
    private static String normalizeTier(
            String tier
    ) {

        if (tier == null) {
            return "";
        }

        String t =
                tier.toLowerCase(
                        Locale.ROOT
                );

        if (
                t.startsWith(
                        "legend"
                )
        ) {

            return t;
        }

        if (
                t.length() >= 2
                        && t.charAt(0) == 't'
                        && Character.isDigit(
                        t.charAt(1)
                )
        ) {

            return "tier"
                    + t.substring(1);
        }

        return t;
    }

    /*
     * ==============================
     * Tier 排序
     * ==============================
     */
    private static Comparator<String>
    tierComparator() {

        return (a, b) -> {

            Integer ia =
                    tierOrder(a);

            Integer ib =
                    tierOrder(b);

            if (
                    ia != null
                            && ib != null
            ) {

                return Integer.compare(
                        ia,
                        ib
                );
            }

            if (ia != null) {
                return -1;
            }

            if (ib != null) {
                return 1;
            }

            return a.compareTo(b);
        };
    }

    private static Integer tierOrder(
            String tier
    ) {

        String t =
                tier.toLowerCase(
                        Locale.ROOT
                );

        try {

            if (
                    t.startsWith(
                            "tier"
                    )
            ) {

                return Integer.parseInt(
                        t.substring(4)
                );
            }

            if (
                    t.startsWith(
                            "legend"
                    )
            ) {

                return 100
                        + Integer.parseInt(
                        t.substring(6)
                );
            }

        } catch (
                NumberFormatException ignored
        ) {
        }

        return null;
    }

    /*
     * ==============================
     * Tier 中文名称
     * ==============================
     */
    private static String tierCn(
            String tier
    ) {

        Integer order =
                tierOrder(tier);

        if (order == null) {
            return tier;
        }

        if (order < 100) {

            String[] cn = {
                    "",
                    "一",
                    "二",
                    "三",
                    "四",
                    "五",
                    "六",
                    "七",
                    "八",
                    "九",
                    "十"
            };

            if (
                    order >= 1
                            && order <= 10
            ) {

                return cn[order]
                        + "阶";
            }

            return tier;
        }

        String[] cn = {
                "",
                "一",
                "二",
                "三",
                "四",
                "五",
                "六",
                "七",
                "八",
                "九"
        };

        int number =
                order - 100;

        if (
                number >= 1
                        && number <= 9
        ) {

            return "传说"
                    + cn[number]
                    + "阶";
        }

        return tier;
    }

    /*
     * ==============================
     * 格式化数字
     * ==============================
     */
    private static String formatDouble(
            double value
    ) {

        if (
                value == Math.floor(value)
                        && !Double.isInfinite(value)
        ) {

            return Long.toString(
                    (long) value
            ) + ".0";
        }

        return String.valueOf(
                value
        );
    }

    /*
     * ==============================
     * 对外 API
     * ==============================
     */
    public Map<String, Map<String, Double>>
    getEntityValues() {

        return entityValues;
    }

    public Map<String, Map<String, Double>>
    getAttributes() {

        return entityValues;
    }

    public Map<String, List<NetCraftDropManager.DropEntry>>
    getDrops() {

        return drops;
    }

    public Map<String, Boolean>
    getDropReplace() {

        return dropReplace;
    }

    public Map<String, Map<String, Integer>>
    getEquipmentOverrides() {

        return equipmentOverrides;
    }

    public Map<String, Map<String, Integer>>
    getEquipmentDefaults() {

        return equipmentDefaults;
    }

    public Map<String, String>
    getEquipmentStructNames() {

        return equipmentStructNames;
    }

    public Path getConfigPath() {

        return configPath;
    }
}
