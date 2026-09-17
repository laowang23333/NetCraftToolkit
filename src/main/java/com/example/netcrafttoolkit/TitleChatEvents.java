package com.example.netcrafttoolkit;

import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.ServerChatEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

/**
 * NetCraftToolkit
 *
 * Mohist 服务器聊天称号适配。
 *
 * 用于解决：
 *
 * 单机：
 *     <称号 玩家名> 消息
 *
 * 正常。
 *
 * Mohist 服务器：
 *     <玩家名> 消息
 *
 * 不显示称号的问题。
 */
public class TitleChatEvents {

    private final TitleManager titleManager;

    public TitleChatEvents(TitleManager titleManager) {
        this.titleManager = titleManager;
    }

    /**
     * 服务器聊天事件。
     */
    @SubscribeEvent
    public void onServerChat(ServerChatEvent event) {

        if (event == null) {
            return;
        }

        ServerPlayer player = event.getPlayer();

        if (player == null) {
            return;
        }

        if (titleManager == null) {
            return;
        }

        /*
         * 当前玩家 UUID。
         */
        java.util.UUID uuid = player.getUUID();

        /*
         * 获取主称号。
         */
        String mainTitle = titleManager.getMainTitle(uuid);

        /*
         * 获取副称号。
         */
        String subTitle = titleManager.getSubTitle(uuid);

        /*
         * 没有主称号，也没有副称号。
         *
         * 不接管聊天，让 Mohist 原本的聊天系统处理。
         */
        if ((mainTitle == null || mainTitle.isBlank())
                && (subTitle == null || subTitle.isBlank())) {

            return;
        }

        /*
         * 创建聊天前缀。
         *
         * 注意：
         *
         * getMainTitle() / getSubTitle()
         * 返回的是称号 ID，
         * 不是最终显示文本。
         *
         * 所以必须经过 getTitleText()
         * 转换成真正的称号文字。
         */
        MutableComponent titlePrefix =
                Component.empty();

        boolean hasTitle = false;

        /*
         * 主称号。
         */
        if (mainTitle != null && !mainTitle.isBlank()) {

            String titleText =
                    titleManager.getTitleText(mainTitle);

            if (titleText != null && !titleText.isBlank()) {

                titlePrefix.append(
                        Component.literal(titleText)
                );

                hasTitle = true;
            }
        }

        /*
         * 副称号。
         */
        if (subTitle != null && !subTitle.isBlank()) {

            String titleText =
                    titleManager.getTitleText(subTitle);

            if (titleText != null && !titleText.isBlank()) {

                if (hasTitle) {
                    titlePrefix.append(
                            Component.literal(" ")
                    );
                }

                titlePrefix.append(
                        Component.literal(titleText)
                );

                hasTitle = true;
            }
        }

        /*
         * 称号 ID 存在，但是没有解析出有效文本。
         *
         * 继续使用 Mohist 原本聊天。
         */
        if (!hasTitle) {
            return;
        }

        /*
         * 接管本次聊天。
         */
        event.setCanceled(true);

        MinecraftServer server =
                player.getServer();

        if (server == null) {
            return;
        }

        /*
         * 构造显示名称：
         *
         * 称号 玩家名
         */
        MutableComponent displayName =
                titlePrefix.copy();

        displayName.append(
                Component.literal(" ")
        );

        displayName.append(
                player.getName()
        );

        /*
         * 构造最终聊天：
         *
         * <称号 玩家名> 消息
         */
        Component chatMessage =
                Component.translatable(
                        "chat.type.text",
                        displayName,
                        event.getMessage()
                );

        /*
         * 广播给服务器所有在线玩家。
         */
        server.getPlayerList().broadcastSystemMessage(
                chatMessage,
                false
        );
    }
}
