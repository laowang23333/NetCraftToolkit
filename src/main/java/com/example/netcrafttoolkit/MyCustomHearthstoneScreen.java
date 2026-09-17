package com.example.netcrafttoolkit;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.slf4j.Logger;

import java.lang.reflect.Method;

public class MyCustomHearthstoneScreen extends Screen {

    private static final Logger LOGGER = NetCraftToolkit.LOGGER;

    // netcraft 自定义包类名（字符串形式，避免编译期依赖）
    private static final String PKT_DAILY_TASKS       = "com.jiufeng.netcraft.network.OpenDailyTasksPacket";
    private static final String PKT_ARMOR_UNLOCK      = "com.jiufeng.netcraft.network.OpenArmorUnlockPacket";
    private static final String PKT_PORTABLE_BACKPACK = "com.jiufeng.netcraft.network.OpenPortableBackpackPacket";
    private static final String PKT_TELEPORT_SPAWN    = "com.jiufeng.netcraft.network.TeleportToSpawnPacket";

    private static final String MOD_MESSAGES = "com.jiufeng.netcraft.network.ModMessages";

    public MyCustomHearthstoneScreen() {
        super(Component.literal("炉石菜单"));
    }

    @Override
    protected void init() {
        super.init();

        int cx = this.width / 2;
        int bw = 200;   // 按钮宽
        int bh = 20;    // 按钮高
        int gap = 5;    // 按钮间距

        int total = 9 * bh + 8 * gap;
        int startY = Math.max(35, (this.height - total) / 2);

        int y = startY;

        // 1. 每日任务
        this.addRenderableWidget(Button.builder(
                Component.literal("每日任务"),
                b -> sendPacket(PKT_DAILY_TASKS))
                .bounds(cx - bw / 2, y, bw, bh).build());
        y += bh + gap;

        // 2. 装备解锁
        this.addRenderableWidget(Button.builder(
                Component.literal("装备解锁"),
                b -> sendPacket(PKT_ARMOR_UNLOCK))
                .bounds(cx - bw / 2, y, bw, bh).build());
        y += bh + gap;

        // 3. 称号管理
        this.addRenderableWidget(Button.builder(
                Component.literal("称号管理"),
                b -> {
                    sendCommand("mytitle");
                    this.onClose();
                })
                .bounds(cx - bw / 2, y, bw, bh).build());
        y += bh + gap;

        // 4. 便携背包
        this.addRenderableWidget(Button.builder(
                Component.literal("便携背包"),
                b -> sendPacket(PKT_PORTABLE_BACKPACK))
                .bounds(cx - bw / 2, y, bw, bh).build());
        y += bh + gap;

        // 5. 交易行
        this.addRenderableWidget(Button.builder(
                Component.literal("交易行"),
                b -> {
                    sendCommand("ah");
                    this.onClose();
                })
                .bounds(cx - bw / 2, y, bw, bh).build());
        y += bh + gap;

        // 6. 领地
        this.addRenderableWidget(Button.builder(
                Component.literal("领地"),
                b -> {
                    sendCommand("res tp");
                    this.onClose();
                })
                .bounds(cx - bw / 2, y, bw, bh).build());
        y += bh + gap;

        // 7. 主城
        this.addRenderableWidget(Button.builder(
                Component.literal("主城"),
                b -> {
                    sendPacket(PKT_TELEPORT_SPAWN);
                    this.onClose();
                })
                .bounds(cx - bw / 2, y, bw, bh).build());
        y += bh + gap;

        // 8. 自杀：发自定义网络包，玩家手敲聊天栏没法触发
        this.addRenderableWidget(Button.builder(
                Component.literal("自杀"),
                b -> {
                    ModNetwork.sendToServer(new SuicidePacket());
                    this.onClose();
                })
                .bounds(cx - bw / 2, y, bw, bh).build());
        y += bh + gap;

        // 9. 关闭
        this.addRenderableWidget(Button.builder(
                Component.literal("关闭"),
                b -> this.onClose())
                .bounds(cx - bw / 2, y, bw, bh).build());
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        this.renderBackground(graphics);
        super.render(graphics, mouseX, mouseY, partialTick);
        graphics.drawCenteredString(this.font,
                Component.literal("§6炉石菜单"),
                this.width / 2, 20, 0xFFFFFF);
    }

    @Override
    public void onClose() {
        if (this.minecraft != null && this.minecraft.player != null) {
            this.minecraft.player.closeContainer();
        }
        super.onClose();
    }

    private static void sendPacket(String packetClassName) {
        try {
            Class<?> packetCls = Class.forName(packetClassName);
            Object packet = packetCls.getDeclaredConstructor().newInstance();

            Class<?> modMessagesCls = Class.forName(MOD_MESSAGES);
            Method sendToServer = modMessagesCls.getMethod("sendToServer", Object.class);
            sendToServer.invoke(null, packet);

            LOGGER.info("[NetCraftToolkit] 已发送 NetCraft 包: {}", packetClassName);
        } catch (Throwable t) {
            LOGGER.error("[NetCraftToolkit] 发送 NetCraft 包失败: {}", packetClassName, t);
        }
    }

    private void sendCommand(String cmd) {
        if (this.minecraft == null || this.minecraft.player == null) return;
        try {
            this.minecraft.player.connection.sendCommand(cmd);
            LOGGER.info("[NetCraftToolkit] 已执行指令: /{}", cmd);
        } catch (Throwable t) {
            LOGGER.error("[NetCraftToolkit] 执行指令失败: /{}", cmd, t);
        }
    }
}
