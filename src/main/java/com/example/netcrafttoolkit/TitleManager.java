package com.example.netcrafttoolkit;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * NetCraftToolkit 称号核心管理器。
 *
 * 当前这一版先完成核心：
 *
 * 1. 主称号 / 副称号。
 * 2. 玩家称号持有列表。
 * 3. 称号选择状态。
 * 4. 自定义称号文本持久化。
 * 5. O|uuid|称号ID / T|uuid|主称号ID / S|uuid|副称号ID，兼容旧 uuid|文本 格式。
 * 6. §0-§f、§k-§o、§r。
 * 7. &0-&f、&k-&o、&r。
 * 8. &#RRGGBB / &x&R&R&G&G&B&B。
 * 9. §x§R§R§G§G§B§B。
 * 10. <gradient:#RRGGBB:#RRGGBB>文字</gradient> 渐变。
 *
 * 注意：
 * 这一层不依赖 Bukkit，也不依赖 NetCraft。
 * 后面的 TitleCommands / TitleEvents 只调用这里。
 */
public class TitleManager {

    private static final String TITLE_FILE = "netcrafttoolkit-titles.txt";
    private static final String CUSTOM_TITLE_FILE = "netcrafttoolkit-custom-titles.txt";

    private static final Pattern GRADIENT_PATTERN = Pattern.compile(
            "(?i)<gradient:((?:#[0-9a-f]{6})(?::#[0-9a-f]{6})*)>(.*?)</gradient>"
    );

    private MinecraftServer server;
    private Path titleFile;
    private Path customTitleFile;

    /**
     * 玩家拥有的称号文本。
     *
     * UUID -> 称号文本列表
     */
    private final Map<UUID, LinkedHashSet<String>> playerTitles =
            new LinkedHashMap<>();

    /**
     * 玩家当前主称号。
     */
    private final Map<UUID, String> mainTitles =
            new LinkedHashMap<>();

    /**
     * 玩家当前副称号。
     */
    private final Map<UUID, String> subTitles =
            new LinkedHashMap<>();

    /**
     * 自定义称号注册表。
     *
     * titleId -> 称号文本
     */
    private final Map<String, String> customTitles =
            new LinkedHashMap<>();

    /**
     * 初始化称号文件。
     */
    public void init(MinecraftServer server) {
        this.server = server;

        Path world =
                server.getWorldPath(LevelResource.ROOT);

        this.titleFile =
                world.resolve(TITLE_FILE);

        this.customTitleFile =
                world.resolve(CUSTOM_TITLE_FILE);

        load();
    }

    /**
     * 读取全部称号数据。
     */
    public synchronized void load() {
        playerTitles.clear();
        mainTitles.clear();
        subTitles.clear();
        customTitles.clear();

        if (titleFile != null && Files.exists(titleFile)) {
            try {
                for (String raw : Files.readAllLines(
                        titleFile,
                        StandardCharsets.UTF_8
                )) {
                    parseTitleLine(raw);
                }
            } catch (IOException e) {
                NetCraftToolkit.LOGGER.error(
                        "[NetCraftToolkit] 读取称号文件失败: {}",
                        titleFile,
                        e
                );
            }
        }

        if (customTitleFile != null
                && Files.exists(customTitleFile)) {

            try {
                for (String raw : Files.readAllLines(
                        customTitleFile,
                        StandardCharsets.UTF_8
                )) {
                    parseCustomTitleLine(raw);
                }
            } catch (IOException e) {
                NetCraftToolkit.LOGGER.error(
                        "[NetCraftToolkit] 读取自定义称号注册表失败: {}",
                        customTitleFile,
                        e
                );
            }
        }

        if (server != null) {
            for (ServerPlayer player :
                    server.getPlayerList().getPlayers()) {

                applyName(player);
            }
        }
    }

    /**
     * 保存称号数据。
     */
    public synchronized void save() {
        if (titleFile == null) {
            return;
        }

        try {
            Path parent = titleFile.getParent();

            if (parent != null) {
                Files.createDirectories(parent);
            }

            List<String> lines =
                    new ArrayList<>();

            /*
             * O = 玩家拥有的全部称号。
             *
             * 这是持有列表的持久化数据，不能只保存当前主/副称号。
             * 否则一个没有装备的称号重启后就会从 GUI 中消失。
             */
            for (Map.Entry<UUID, LinkedHashSet<String>> entry :
                    playerTitles.entrySet()) {

                UUID uuid = entry.getKey();
                for (String titleId : entry.getValue()) {
                    if (titleId == null || titleId.isBlank()) {
                        continue;
                    }

                    lines.add(
                            "O|"
                                    + uuid
                                    + "|"
                                    + escapeLine(titleId)
                    );
                }
            }

            /*
             * T = 主称号。
             */
            for (Map.Entry<UUID, String> entry :
                    mainTitles.entrySet()) {

                if (entry.getValue() == null
                        || entry.getValue().isBlank()) {
                    continue;
                }

                lines.add(
                        "T|"
                                + entry.getKey()
                                + "|"
                                + escapeLine(entry.getValue())
                );
            }

            /*
             * S = 副称号。
             */
            for (Map.Entry<UUID, String> entry :
                    subTitles.entrySet()) {

                if (entry.getValue() == null
                        || entry.getValue().isBlank()) {
                    continue;
                }

                lines.add(
                        "S|"
                                + entry.getKey()
                                + "|"
                                + escapeLine(entry.getValue())
                );
            }

            /*
             * 兼容旧格式：
             *
             * uuid|文本
             *
             * 这里不主动写旧格式，只负责读取。
             */
            Files.write(
                    titleFile,
                    lines,
                    StandardCharsets.UTF_8
            );

        } catch (IOException e) {
            NetCraftToolkit.LOGGER.error(
                    "[NetCraftToolkit] 保存称号文件失败: {}",
                    titleFile,
                    e
            );
        }

        saveCustomTitles();
    }

    /**
     * 保存自定义称号注册表。
     */
    private synchronized void saveCustomTitles() {
        if (customTitleFile == null) {
            return;
        }

        try {
            Path parent =
                    customTitleFile.getParent();

            if (parent != null) {
                Files.createDirectories(parent);
            }

            List<String> lines =
                    new ArrayList<>();

            for (Map.Entry<String, String> entry :
                    customTitles.entrySet()) {

                lines.add(
                        entry.getKey()
                                + "|"
                                + escapeLine(entry.getValue())
                );
            }

            Files.write(
                    customTitleFile,
                    lines,
                    StandardCharsets.UTF_8
            );

        } catch (IOException e) {
            NetCraftToolkit.LOGGER.error(
                    "[NetCraftToolkit] 保存自定义称号注册表失败: {}",
                    customTitleFile,
                    e
            );
        }
    }

    /**
     * 解析玩家称号文件。
     */
    private void parseTitleLine(String raw) {
        if (raw == null) {
            return;
        }

        String line =
                raw.trim();

        if (line.isEmpty()
                || line.startsWith("#")) {
            return;
        }

        String[] parts =
                line.split("\\|", 3);

        try {
            /*
             * 新格式：
             *
             * O|uuid|称号ID
             * T|uuid|主称号ID
             * S|uuid|副称号ID
             */
            if (parts.length >= 3
                    && ("O".equalsIgnoreCase(parts[0])
                    || "T".equalsIgnoreCase(parts[0])
                    || "S".equalsIgnoreCase(parts[0]))) {

                UUID uuid =
                        UUID.fromString(parts[1].trim());

                String title =
                        unescapeLine(parts[2]);

                if (title.isBlank()) {
                    return;
                }

                // O 只表示“拥有”，不改变当前主/副称号。
                addOwnedTitle(uuid, title);

                if ("T".equalsIgnoreCase(parts[0])) {
                    mainTitles.put(uuid, title);
                } else if ("S".equalsIgnoreCase(parts[0])) {
                    subTitles.put(uuid, title);
                }

                return;
            }

            /*
             * 旧兼容格式：
             *
             * uuid|文本
             */
            if (parts.length >= 2) {

                UUID uuid =
                        UUID.fromString(parts[0].trim());

                String title =
                        unescapeLine(parts[1]);

                if (title.isBlank()) {
                    return;
                }

                addOwnedTitle(uuid, title);

                /*
                 * 旧格式只有一个称号，
                 * 没有主/副之分时作为主称号。
                 */
                mainTitles.putIfAbsent(
                        uuid,
                        title
                );
            }

        } catch (Exception ignored) {
            /*
             * 单行损坏不能影响整个称号系统。
             */
        }
    }

    /**
     * 解析自定义称号注册表。
     *
     * 格式：
     *
     * custom_xxx|称号文本
     */
    private void parseCustomTitleLine(String raw) {
        if (raw == null) {
            return;
        }

        String line =
                raw.trim();

        if (line.isEmpty()
                || line.startsWith("#")) {
            return;
        }

        String[] parts =
                line.split("\\|", 2);

        if (parts.length < 2) {
            return;
        }

        String id =
                parts[0].trim();

        String text =
                unescapeLine(parts[1]);

        if (id.isEmpty()
                || text.isBlank()) {
            return;
        }

        customTitles.put(
                id,
                text
        );
    }

    /**
     * 注册自定义称号。
     */
    public synchronized void registerCustomTitle(
            String id,
            String text
    ) {
        if (id == null
                || id.isBlank()
                || text == null
                || text.isBlank()) {
            return;
        }

        customTitles.put(
                id.trim(),
                text
        );

        saveCustomTitles();
    }

    /**
     * 删除自定义称号注册。
     */
    public synchronized void unregisterCustomTitle(
            String id
    ) {
        if (id == null) {
            return;
        }

        customTitles.remove(
                id.trim()
        );

        saveCustomTitles();
    }

    /**
     * 获取所有自定义称号。
     */
    public synchronized Map<String, String> getCustomTitles() {
        return Collections.unmodifiableMap(
                new LinkedHashMap<>(customTitles)
        );
    }

    /**
     * 给玩家添加称号。
     */
    public synchronized void giveTitle(
            UUID uuid,
            String title
    ) {
        if (uuid == null
                || title == null
                || title.isBlank()) {
            return;
        }

        addOwnedTitle(
                uuid,
                title
        );

        /*
         * 第一个获得的称号自动成为主称号。
         */
        if (!mainTitles.containsKey(uuid)) {
            mainTitles.put(
                    uuid,
                    title
            );
        }

        save();

        refreshPlayer(uuid);
    }

    /**
     * 删除玩家拥有的某个称号。
     */
    public synchronized boolean removeTitle(
            UUID uuid,
            String title
    ) {
        if (uuid == null
                || title == null) {
            return false;
        }

        LinkedHashSet<String> titles =
                playerTitles.get(uuid);

        if (titles == null) {
            return false;
        }

        boolean removed =
                titles.remove(title);

        if (!removed) {
            return false;
        }

        if (title.equals(
                mainTitles.get(uuid)
        )) {
            mainTitles.remove(uuid);

            String next =
                    titles.stream()
                            .findFirst()
                            .orElse(null);

            if (next != null) {
                mainTitles.put(
                        uuid,
                        next
                );
            }
        }

        if (title.equals(
                subTitles.get(uuid)
        )) {
            subTitles.remove(uuid);
        }

        if (titles.isEmpty()) {
            playerTitles.remove(uuid);
        }

        save();

        refreshPlayer(uuid);

        return true;
    }

    /**
     * 设置主称号。
     *
     * 称号必须属于玩家。
     */
    public synchronized boolean setMainTitle(
            UUID uuid,
            String title
    ) {
        if (!ownsTitle(uuid, title)) {
            return false;
        }

        mainTitles.put(
                uuid,
                title
        );

        /*
         * 主副称号不能重复。
         */
        if (title.equals(
                subTitles.get(uuid)
        )) {
            subTitles.remove(uuid);
        }

        save();

        refreshPlayer(uuid);

        return true;
    }

    /**
     * 设置副称号。
     */
    public synchronized boolean setSubTitle(
            UUID uuid,
            String title
    ) {
        if (!ownsTitle(uuid, title)) {
            return false;
        }

        subTitles.put(
                uuid,
                title
        );

        /*
         * 主副称号不能重复。
         */
        if (title.equals(
                mainTitles.get(uuid)
        )) {
            mainTitles.remove(uuid);
        }

        save();

        refreshPlayer(uuid);

        return true;
    }

    /**
     * 清除主称号。
     */
    public synchronized void clearMainTitle(
            UUID uuid
    ) {
        if (uuid == null) {
            return;
        }

        mainTitles.remove(uuid);

        save();

        refreshPlayer(uuid);
    }

    /**
     * 清除副称号。
     */
    public synchronized void clearSubTitle(
            UUID uuid
    ) {
        if (uuid == null) {
            return;
        }

        subTitles.remove(uuid);

        save();

        refreshPlayer(uuid);
    }

    /**
     * 获取玩家所有称号。
     */
    public synchronized List<String> getOwnedTitles(
            UUID uuid
    ) {
        LinkedHashSet<String> titles =
                playerTitles.get(uuid);

        if (titles == null) {
            return Collections.emptyList();
        }

        return List.copyOf(titles);
    }

    /**
     * 获取主称号。
     */
    public synchronized String getMainTitle(
            UUID uuid
    ) {
        return mainTitles.get(uuid);
    }

    /**
     * 获取副称号。
     */
    public synchronized String getSubTitle(
            UUID uuid
    ) {
        return subTitles.get(uuid);
    }

    /**
     * 判断玩家是否拥有称号。
     */
    public synchronized boolean ownsTitle(
            UUID uuid,
            String title
    ) {
        if (uuid == null
                || title == null) {
            return false;
        }

        LinkedHashSet<String> titles =
                playerTitles.get(uuid);

        return titles != null
                && titles.contains(title);
    }

    /** 根据称号 ID 获取当前显示文本。 */
    public synchronized String getTitleText(String titleId) {
        if (titleId == null || titleId.isBlank()) {
            return null;
        }

        NetCraftConfig config = NetCraftToolkit.getConfig();
        if (config != null) {
            String text = config.getTitleDefinitions().get(titleId);
            if (text != null) {
                return text;
            }
        }

        String custom = customTitles.get(titleId);
        if (custom != null) {
            return custom;
        }

        // 兼容旧数据：如果存储的是原始文本而不是 ID，则直接返回。
        return titleId;
    }

    /** 获取当前全部可用称号定义。 */
    public synchronized Map<String, String> getDefinitions() {
        Map<String, String> result = new LinkedHashMap<>();
        NetCraftConfig config = NetCraftToolkit.getConfig();
        if (config != null) {
            result.putAll(config.getTitleDefinitions());
        }
        result.putAll(customTitles);
        return result;
    }

    /** 配置热重载后刷新在线玩家的聊天名和 Tab 名。 */
    public synchronized void syncDefinitions(Map<String, String> definitions) {
        if (definitions == null) {
            return;
        }

        if (server != null) {
            for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                applyName(player);
                player.refreshTabListName();
            }
        }
    }

    /**
     * 玩家重新进入世界后重新应用称号。
     *
     * 这样不会依赖客户端缓存，登录、重生、跨维度后都能立即恢复。
     */
    @SubscribeEvent
    public void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            refreshPlayer(player.getUUID());
        }
    }

    @SubscribeEvent
    public void onPlayerRespawn(PlayerEvent.PlayerRespawnEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            refreshPlayer(player.getUUID());
        }
    }

    @SubscribeEvent
    public void onPlayerChangedDimension(PlayerEvent.PlayerChangedDimensionEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            refreshPlayer(player.getUUID());
        }
    }

    /** 获取玩家当前称号显示文本（主 + 副）。 */
    private synchronized MutableComponent buildTitlePrefix(UUID uuid) {
        MutableComponent result = Component.empty();
        String mainId = mainTitles.get(uuid);
        String subId = subTitles.get(uuid);
        boolean has = false;

        if (mainId != null && !mainId.isBlank()) {
            String text = getTitleText(mainId);
            if (text != null && !text.isBlank()) {
                result.append(parseText(text));
                has = true;
            }
        }

        if (subId != null && !subId.isBlank()) {
            String text = getTitleText(subId);
            if (text != null && !text.isBlank()) {
                if (has) result.append(Component.literal(" "));
                result.append(parseText(text));
                has = true;
            }
        }

        return result;
    }

    /** 玩家称号前缀，用于聊天显示名。 */
    @SubscribeEvent
    public void onNameFormat(PlayerEvent.NameFormat event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }

        MutableComponent prefix = buildTitlePrefix(player.getUUID());
        if (prefix.getString().isEmpty()) {
            return;
        }

        prefix.append(Component.literal(" "));
        prefix.append(player.getName());
        event.setDisplayname(prefix);
    }

    /** 玩家称号前缀，用于 Tab 列表显示名。 */
    @SubscribeEvent
    public void onTabListNameFormat(PlayerEvent.TabListNameFormat event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }

        MutableComponent prefix = buildTitlePrefix(player.getUUID());
        if (prefix.getString().isEmpty()) {
            event.setDisplayName(null);
            return;
        }

        prefix.append(Component.literal(" "));
        prefix.append(player.getName());
        event.setDisplayName(prefix);
    }

    /**
     * 刷新玩家名称显示。
     *
     * 这里直接使用 Minecraft 原生 customName，
     * 因此不用额外做客户端网络包。
     */
    public synchronized void applyName(
            ServerPlayer player
    ) {
        if (player == null) {
            return;
        }

        MutableComponent prefix = buildTitlePrefix(player.getUUID());
        boolean hasTitle = !prefix.getString().isEmpty();

        if (hasTitle) {
            MutableComponent name = prefix.copy();
            name.append(Component.literal(" "));
            name.append(player.getName());
            player.setCustomName(name);
            player.setCustomNameVisible(true);

            // NameFormat 是聊天显示名的刷新入口；不调用它时，
            // 主/副称号切换后聊天可能继续使用旧的缓存名称，直到重进服务器。
            player.refreshDisplayName();

            // Tab 列表通过 Forge 的 TabListNameFormat 事件动态提供显示名。
            player.refreshTabListName();
        } else {
            player.setCustomName(null);
            player.setCustomNameVisible(false);
            player.refreshDisplayName();
            player.refreshTabListName();
        }
    }

    /**
     * 根据 UUID 刷新在线玩家。
     */
    private void refreshPlayer(
            UUID uuid
    ) {
        if (server == null
                || uuid == null) {
            return;
        }

        ServerPlayer player =
                server.getPlayerList()
                        .getPlayer(uuid);

        if (player != null) {
            applyName(player);
        }
    }

    /**
     * 加入玩家称号列表。
     */
    private void addOwnedTitle(
            UUID uuid,
            String title
    ) {
        playerTitles
                .computeIfAbsent(
                        uuid,
                        ignored ->
                                new LinkedHashSet<>()
                )
                .add(title);
    }

    /**
     * 文本转 Component。
     *
     * 支持：
     *
     * §0-§9 §a-§f
     * §k-§o §r
     * &0-&f
     * &k-&o
     * &#RRGGBB
     * &x&R&R&G&G&B&B
     * §x§R§R§G§G§B§B
     * <gradient:#RRGGBB:#RRGGBB>文本</gradient>
     */
    public static Component parseText(
            String input
    ) {
        if (input == null
                || input.isEmpty()) {
            return Component.empty();
        }

        String text =
                input
                        .replace("\\u00A7", "§")
                        .replace("&x&", "§x§");

        return parseSegments(
                text,
                Style.EMPTY
        );
    }

    /**
     * 递归解析普通颜色和渐变。
     */
    private static MutableComponent parseSegments(
            String input,
            Style baseStyle
    ) {
        MutableComponent result =
                Component.empty();

        Matcher matcher =
                GRADIENT_PATTERN.matcher(input);

        int cursor = 0;

        while (matcher.find()) {

            if (matcher.start() > cursor) {

                result.append(
                        parseLegacy(
                                input.substring(
                                        cursor,
                                        matcher.start()
                                ),
                                baseStyle
                        )
                );
            }

            List<Integer> colors =
                    parseGradientColors(
                            matcher.group(1)
                    );

            result.append(
                    gradientComponent(
                            matcher.group(2),
                            colors,
                            baseStyle
                    )
            );

            cursor =
                    matcher.end();
        }

        if (cursor < input.length()) {

            result.append(
                    parseLegacy(
                            input.substring(cursor),
                            baseStyle
                    )
            );
        }

        return result;
    }

    /**
     * 解析 Minecraft 颜色字符。
     */
    private static MutableComponent parseLegacy(
            String input,
            Style initialStyle
    ) {
        MutableComponent result =
                Component.empty();

        Style style =
                initialStyle;

        StringBuilder plain =
                new StringBuilder();

        for (int i = 0;
             i < input.length();
             i++) {

            char c =
                    input.charAt(i);

            if ((c == '§' || c == '&')
                    && i + 1 < input.length()) {

                /*
                 * &#RRGGBB
                 */
                if (input.charAt(i + 1) == '#'
                        && i + 7 < input.length()) {

                    String hex =
                            input.substring(
                                    i + 2,
                                    i + 8
                            );

                    if (isHex(hex)) {

                        flushPlain(
                                result,
                                plain,
                                style
                        );

                        style =
                                style.withColor(
                                        Integer.parseInt(
                                                hex,
                                                16
                                        )
                                );

                        i += 7;
                        continue;
                    }
                }

                /*
                 * §x§R§R§G§G§B§B
                 */
                if (Character.toLowerCase(
                        input.charAt(i + 1)
                ) == 'x') {

                    String hex =
                            readLegacyHex(
                                    input,
                                    i
                            );

                    if (hex != null) {

                        flushPlain(
                                result,
                                plain,
                                style
                        );

                        style =
                                style.withColor(
                                        Integer.parseInt(
                                                hex,
                                                16
                                        )
                                );

                        i += 13;
                        continue;
                    }
                }

                char code =
                        Character.toLowerCase(
                                input.charAt(i + 1)
                        );

                ChatFormatting formatting =
                        ChatFormatting.getByCode(
                                code
                        );

                if (formatting != null) {

                    flushPlain(
                            result,
                            plain,
                            style
                    );

                    if (formatting.isColor()) {

                        style =
                                style.withColor(
                                        formatting
                                );

                    } else if (
                            formatting == ChatFormatting.RESET
                    ) {

                        style =
                                Style.EMPTY;

                    } else {

                        style =
                                applyFormat(
                                        style,
                                        formatting
                                );
                    }

                    i++;
                    continue;
                }
            }

            plain.append(c);
        }

        flushPlain(
                result,
                plain,
                style
        );

        return result;
    }

    /**
     * 将当前缓冲区里的普通文本按照当前样式追加到结果组件，并清空缓冲区。
     */
    private static void flushPlain(
            MutableComponent result,
            StringBuilder plain,
            Style style
    ) {
        if (plain.length() == 0) {
            return;
        }

        result.append(
                Component.literal(plain.toString())
                        .withStyle(style)
        );

        plain.setLength(0);
    }

    /**
     * 应用粗体、斜体、下划线等格式。
     */
    private static Style applyFormat(
            Style style,
            ChatFormatting formatting
    ) {
        return switch (formatting) {
            case OBFUSCATED ->
                    style.withObfuscated(true);
            case BOLD ->
                    style.withBold(true);
            case STRIKETHROUGH ->
                    style.withStrikethrough(true);
            case UNDERLINE ->
                    style.withUnderlined(true);
            case ITALIC ->
                    style.withItalic(true);
            default ->
                    style;
        };
    }

    /**
     * 生成渐变 Component。
     */
    private static MutableComponent gradientComponent(
            String text,
            List<Integer> colors,
            Style baseStyle
    ) {
        MutableComponent result =
                Component.empty();

        if (text == null
                || text.isEmpty()
                || colors.isEmpty()) {
            return result;
        }

        int length =
                text.codePointCount(
                        0,
                        text.length()
                );

        if (length <= 1) {

            int color =
                    colors.get(0);

            result.append(
                    Component.literal(text)
                            .setStyle(
                                    baseStyle.withColor(
                                            color
                                    )
                            )
            );

            return result;
        }

        int index = 0;

        for (int offset = 0;
             offset < text.length();) {

            int codePoint =
                    text.codePointAt(offset);

            int color =
                    interpolateGradient(
                            colors,
                            length,
                            index
                    );

            String character =
                    new String(
                            Character.toChars(
                                    codePoint
                            )
                    );

            result.append(
                    Component.literal(
                            character
                    ).setStyle(
                            baseStyle.withColor(
                                    color
                            )
                    )
            );

            offset +=
                    Character.charCount(
                            codePoint
                    );

            index++;
        }

        return result;
    }

    /**
     * 多色渐变插值。
     */
    private static int interpolateGradient(
            List<Integer> colors,
            int length,
            int index
    ) {
        if (colors.size() == 1) {
            return colors.get(0);
        }

        double position =
                (double) index
                        / Math.max(
                        1,
                        length - 1
                );

        double scaled =
                position
                        * (colors.size() - 1);

        int left =
                (int) Math.floor(scaled);

        int right =
                Math.min(
                        colors.size() - 1,
                        left + 1
                );

        double local =
                scaled - left;

        int c1 =
                colors.get(left);

        int c2 =
                colors.get(right);

        int r =
                interpolate(
                        (c1 >> 16) & 0xFF,
                        (c2 >> 16) & 0xFF,
                        local
                );

        int g =
                interpolate(
                        (c1 >> 8) & 0xFF,
                        (c2 >> 8) & 0xFF,
                        local
                );

        int b =
                interpolate(
                        c1 & 0xFF,
                        c2 & 0xFF,
                        local
                );

        return (r << 16)
                | (g << 8)
                | b;
    }

    private static int interpolate(
            int a,
            int b,
            double t
    ) {
        return (int) Math.round(
                a + (b - a) * t
        );
    }

    /**
     * 解析渐变颜色列表。
     */
    private static List<Integer> parseGradientColors(
            String value
    ) {
        List<Integer> result =
                new ArrayList<>();

        if (value == null) {
            return result;
        }

        String[] parts =
                value.split(":");

        for (String part : parts) {

            String hex =
                    part.trim();

            if (hex.startsWith("#")) {
                hex =
                        hex.substring(1);
            }

            if (isHex(hex)
                    && hex.length() == 6) {

                result.add(
                        Integer.parseInt(
                                hex,
                                16
                        )
                );
            }
        }

        return result;
    }

    /**
     * 读取 §x§R§R§G§G§B§B。
     */
    private static String readLegacyHex(
            String input,
            int start
    ) {
        if (start + 13 >= input.length()) {
            return null;
        }

        if (Character.toLowerCase(
                input.charAt(start + 1)
        ) != 'x') {
            return null;
        }

        StringBuilder hex =
                new StringBuilder(6);

        int pos =
                start + 2;

        for (int i = 0; i < 6; i++) {

            if (pos >= input.length()
                    || input.charAt(pos) != '§') {
                return null;
            }

            pos++;

            if (pos >= input.length()) {
                return null;
            }

            char c =
                    input.charAt(pos);

            if (!isHexCharacter(c)) {
                return null;
            }

            hex.append(c);
            pos++;
        }

        return hex.toString();
    }

    private static boolean isHex(
            String value
    ) {
        if (value == null
                || value.length() != 6) {
            return false;
        }

        for (int i = 0;
             i < value.length();
             i++) {

            if (!isHexCharacter(
                    value.charAt(i)
            )) {
                return false;
            }
        }

        return true;
    }

    private static boolean isHexCharacter(
            char c
    ) {
        return (c >= '0' && c <= '9')
                || (c >= 'a' && c <= 'f')
                || (c >= 'A' && c <= 'F');
    }

    /**
     * TOML/文本文件里对换行和分隔符做简单转义。
     */
    private static String escapeLine(
            String text
    ) {
        return text
                .replace("\\", "\\\\")
                .replace("\r", "\\r")
                .replace("\n", "\\n");
    }

    private static String unescapeLine(
            String text
    ) {
        return text
                .replace("\\n", "\n")
                .replace("\\r", "\r")
                .replace("\\\\", "\\");
    }

    /**
     * 服务器停止时保存。
     */
    @SubscribeEvent
    public void onServerStopping(
            ServerStoppingEvent event
    ) {
        save();
    }

    /**
     * 服务器停止后清理。
     */
    public synchronized void clear() {
        server = null;
        titleFile = null;
        customTitleFile = null;
        playerTitles.clear();
        mainTitles.clear();
        subTitles.clear();
        customTitles.clear();
    }
}
