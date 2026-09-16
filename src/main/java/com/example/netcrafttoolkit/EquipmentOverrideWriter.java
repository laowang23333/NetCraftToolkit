package com.example.netcrafttoolkit;

import com.mojang.logging.LogUtils;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class EquipmentOverrideWriter {

    private static final Logger LOGGER = LogUtils.getLogger();

    /**
     * 把 NetCraft Toolkit 的装备覆盖配置
     * 写入当前世界的 NetCraft serverconfig。
     *
     * 配置文件：
     *
     * world/serverconfig/netcraft-server.toml
     *
     * 规则：
     *
     * >= 0 ：覆盖 NetCraft 原值
     * -1   ：不覆盖，保留 NetCraft 原值
     *
     * 同时自动开启：
     *
     * enableEquipmentStatOverride = true
     */
    public static void applyToWorld(
            MinecraftServer server,
            NetCraftConfig config
    ) {

        if (server == null || config == null) {
            return;
        }

        /*
         * 获取当前世界目录。
         */
        Path worldPath = server.getWorldPath(
                LevelResource.ROOT
        );

        /*
         * NetCraft 服务端配置。
         */
        Path cfg = worldPath
                .resolve("serverconfig")
                .resolve("netcraft-server.toml");

        /*
         * 文件不存在时不创建。
         *
         * 因为 NetCraft 本身负责生成这个文件。
         */
        if (!Files.exists(cfg)) {

            LOGGER.warn(
                    "[NetCraftToolkit] 未找到 NetCraft serverconfig: {}",
                    cfg
            );

            return;
        }

        /*
         * 获取装备覆盖配置。
         */
        Map<String, Map<String, Integer>> overrides =
                config.getEquipmentOverrides();

        if (overrides == null || overrides.isEmpty()) {

            LOGGER.info(
                    "[NetCraftToolkit] 没有装备覆盖配置，跳过写入。"
            );

            return;
        }

        try {

            /*
             * 读取原始 TOML。
             */
            List<String> lines = Files.readAllLines(
                    cfg,
                    StandardCharsets.UTF_8
            );

            List<String> out =
                    new ArrayList<>(lines.size());

            String curTier = null;
            String curJob = null;
            String curSlot = null;

            boolean inEquipmentStat = false;

            int writeCount = 0;

            /*
             * 逐行处理。
             */
            for (String line : lines) {

                String trimmed = line.trim();

                /*
                 * ==============================
                 * TOML 段头
                 * ==============================
                 */
                if (
                        trimmed.startsWith("[")
                                && trimmed.endsWith("]")
                ) {

                    String sec =
                            trimmed.substring(
                                    1,
                                    trimmed.length() - 1
                            );

                    String[] parts =
                            sec.split("\\.");

                    /*
                     * 判断是否进入：
                     *
                     * [equipmentStat....]
                     */
                    inEquipmentStat =
                            parts.length >= 1
                                    && parts[0].equals(
                                    "equipmentStat"
                            );

                    curTier = null;
                    curJob = null;
                    curSlot = null;

                    if (inEquipmentStat) {

                        /*
                         * [equipmentStat.tier]
                         */
                        if (parts.length == 2) {

                            curTier = parts[1];
                        }

                        /*
                         * [equipmentStat.tier.job]
                         */
                        else if (parts.length == 3) {

                            curTier = parts[1];
                            curJob = parts[2];
                        }

                        /*
                         * [equipmentStat.tier.job.slot]
                         */
                        else if (parts.length == 4) {

                            curTier = parts[1];
                            curJob = parts[2];
                            curSlot = parts[3];
                        }
                    }

                    /*
                     * 段头原样保留。
                     */
                    out.add(line);

                    continue;
                }

                /*
                 * ==============================
                 * 总开关
                 * ==============================
                 *
                 * enableEquipmentStatOverride
                 */
                if (
                        inEquipmentStat
                                && trimmed.startsWith(
                                "enableEquipmentStatOverride"
                        )
                ) {

                    String indent =
                            getIndent(line);

                    out.add(
                            indent
                                    + "enableEquipmentStatOverride = true"
                    );

                    continue;
                }

                /*
                 * ==============================
                 * 装备属性
                 * ==============================
                 */
                if (
                        inEquipmentStat
                                && curTier != null
                                && curJob != null
                                && curSlot != null
                ) {

                    int eq =
                            trimmed.indexOf('=');

                    if (eq > 0) {

                        String key =
                                trimmed
                                        .substring(0, eq)
                                        .trim();

                        /*
                         * 对应配置键：
                         *
                         * tier.job.slot
                         */
                        String mapKey =
                                curTier
                                        + "."
                                        + curJob
                                        + "."
                                        + curSlot;

                        Map<String, Integer> values =
                                overrides.get(mapKey);

                        if (values != null) {

                            Integer value =
                                    values.get(key);

                            /*
                             * >= 0 才真正覆盖。
                             *
                             * -1 表示保留 NetCraft 原值。
                             */
                            if (
                                    value != null
                                            && value >= 0
                            ) {

                                String indent =
                                        getIndent(line);

                                out.add(
                                        indent
                                                + key
                                                + " = "
                                                + value
                                );

                                writeCount++;

                                continue;
                            }
                        }
                    }
                }

                /*
                 * 没有修改的行原样保留。
                 */
                out.add(line);
            }

            /*
             * 所有值都是 -1，
             * 不需要修改文件。
             */
            if (writeCount == 0) {

                LOGGER.info(
                        "[NetCraftToolkit] 没有需要写入的装备覆盖值（全部为 -1）。"
                );

                return;
            }

            /*
             * ==============================
             * 原子写入
             * ==============================
             *
             * 先写 .tmp
             * 再替换原文件。
             */
            Path tmp =
                    cfg.resolveSibling(
                            cfg.getFileName()
                                    .toString()
                                    + ".tmp"
                    );

            Files.write(
                    tmp,
                    out,
                    StandardCharsets.UTF_8
            );

            Files.move(
                    tmp,
                    cfg,
                    StandardCopyOption.REPLACE_EXISTING
            );

            LOGGER.info(
                    "[NetCraftToolkit] 已写入 NetCraft 装备覆盖，共 {} 项 -> {}",
                    writeCount,
                    cfg
            );

        } catch (IOException e) {

            LOGGER.error(
                    "[NetCraftToolkit] 写入 NetCraft serverconfig 失败",
                    e
            );
        }
    }

    /**
     * 获取一行前面的空格 / Tab 缩进。
     */
    private static String getIndent(String line) {

        int index = 0;

        while (
                index < line.length()
                        && Character.isWhitespace(
                        line.charAt(index)
                )
        ) {

            index++;
        }

        return line.substring(0, index);
    }
}
