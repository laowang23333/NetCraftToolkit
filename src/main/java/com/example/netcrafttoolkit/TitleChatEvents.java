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
 * 作用：
 * 1. 单机环境原有 TitleManager 聊天显示不受影响。
 * 2. Mohist 服务器中，绕过 Bukkit/Mohist 默认的聊天名字格式。
 * 3. 有称号的玩家聊天时显示：
 *
 *    <称号 玩家名> 消息
 *
 * 4. 没有称号的玩家继续使用服务器原本的聊天系统。
 */
public class TitleChatEvents {

    private final TitleManager titleManager;

    public TitleChatEvents(TitleManager titleManager) {
        this.titleManager = titleManager;
    }

    /**
     * 处理服务器聊天。
     *
     * Mohist 的聊天流程可能不会采用 Forge
     * PlayerEvent.NameFormat 设置的 displayName，
     * 因此这里单独处理有称号玩家的聊天。
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
         * 取得当前玩家的称号前缀。
         *
         * buildTitlePrefix() 已经负责：
         * - 主称号
         * - 副称号
         * - 称号颜色
         * - 自定义称号
         * - 称号文本解析
         */
        MutableComponent titlePrefix =
                titleManager.buildTitlePrefix(player.getUUID());

        /*
         * 没有称号：
         *
         * 不取消事件。
         * 让 Mohist 原本的聊天系统继续处理。
         */
        if (titlePrefix == null || titlePrefix.getString().isBlank()) {
            return;
        }

        /*
         * 有称号：
         *
         * 我们接管这一次聊天。
         *
         * 如果只调用 event.setMessage()，
         * Mohist 仍然可能使用：
         *
         * <wuyutianming> 1
         *
         * 因为 setMessage() 修改的是聊天正文，
         * 并不能保证 Mohist 的 Bukkit 聊天格式会采用
         * Forge 的 PlayerEvent.NameFormat。
         *
         * 所以这里取消原聊天，再自己广播完整聊天组件。
         */
        event.setCanceled(true);

        MinecraftServer server = player.getServer();

        if (server == null) {
            return;
        }

        /*
         * 最终显示名称：
         *
         * <称号 玩家名>
         */
        MutableComponent displayName =
                titlePrefix.copy();

        displayName.append(Component.literal(" "));
        displayName.append(player.getName());

        /*
         * 使用 Minecraft 原版聊天组件：
         *
         * <显示名称> 消息
         *
         * 这里使用 chat.type.text，
         * 而不是手动拼接尖括号。
         *
         * 好处是：
         * - 保留 Minecraft 原版聊天格式
         * - 玩家名称 Component 的样式继续有效
         * - 称号 Component 的颜色继续有效
         * - 聊天正文 Component 的格式继续有效
         */
        Component chatMessage = Component.translatable(
                "chat.type.text",
                displayName,
                event.getMessage()
        );

        /*
         * 广播给服务器在线玩家。
         *
         * false：
         * 不使用 overlay（不会显示在快捷栏上方）。
         */
        server.getPlayerList().broadcastSystemMessage(
                chatMessage,
                false
        );
    }
}
