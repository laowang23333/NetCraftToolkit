package com.example.netcrafttoolkit;

import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.Slot;

/**
 * 玩家称号 GUI。
 *
 * GUI：
 * - 使用原版 generic_54.png 的 3 排箱子区域 + 玩家背包区域。
 * - “物品栏”标题下移一点，避免压在第三排称号槽上。
 *
 * 点击：
 * - 不依赖屏幕坐标。
 * - 直接使用 AbstractContainerScreen 已经识别出的实际 Slot。
 * - 称号槽左键 -> 主称号。
 * - 称号槽右键 -> 副称号。
 * - 清除主/副称号按钮左右键均发送操作。
 *
 * 这对 FCL/Android 的 GUI 缩放尤其重要：
 * 不再自己用 mouseX/mouseY 计算槽位。
 */
public class TitleScreen extends AbstractContainerScreen<TitleMenu> {

    private static final ResourceLocation CHEST_TEXTURE =
            new ResourceLocation(
                    "minecraft",
                    "textures/gui/container/generic_54.png"
            );

    private static final int FIRST_TITLE_SLOT = 9;
    private static final int LAST_TITLE_SLOT = 17;
    private static final int CLEAR_MAIN_SLOT = 20;
    private static final int CLEAR_SUB_SLOT = 24;

    /** 3 排 ChestMenu 的容器区域。 */
    private static final int CONTAINER_HEIGHT = 71;

    /** generic_54.png 中玩家物品栏区域的纹理 Y。 */
    private static final int PLAYER_INVENTORY_TEXTURE_Y = 126;

    /** 玩家物品栏 + 快捷栏区域。 */
    private static final int PLAYER_INVENTORY_HEIGHT = 96;

    public TitleScreen(
            TitleMenu menu,
            Inventory inventory,
            Component title
    ) {
        super(menu, inventory, title);

        // 3 排 ChestMenu 的标准整体高度。
        this.imageWidth = 176;
        this.imageHeight = 168;

        // 原来 61 会压在第三排称号槽上；下移到 70。
        this.inventoryLabelY = 70;

        this.titleLabelX = 8;
        this.titleLabelY = 6;
    }

    @Override
    protected void renderBg(
            GuiGraphics graphics,
            float partialTick,
            int mouseX,
            int mouseY
    ) {
        // 自定义容器需要主动绘制暗背景。
        this.renderBackground(graphics);

        RenderSystem.setShaderColor(
                1.0F,
                1.0F,
                1.0F,
                1.0F
        );

        /*
         * 只取 generic_54 的前三排箱子区域。
         * 绝不把整张 6 排箱子贴图直接画进来。
         */
        graphics.blit(
                CHEST_TEXTURE,
                leftPos,
                topPos,
                0,
                0,
                imageWidth,
                CONTAINER_HEIGHT,
                256,
                256
        );

        /*
         * 接上 generic_54 的玩家物品栏/快捷栏区域。
         */
        graphics.blit(
                CHEST_TEXTURE,
                leftPos,
                topPos + CONTAINER_HEIGHT,
                0,
                PLAYER_INVENTORY_TEXTURE_Y,
                imageWidth,
                PLAYER_INVENTORY_HEIGHT,
                256,
                256
        );
    }

    /**
     * Minecraft 已经根据实际 GUI 缩放找到了 Slot 后，
     * 在这里接管称号操作。
     *
     * 这是点击修复的关键：不再依赖自己计算 mouseX/mouseY。
     */
    @Override
    protected void slotClicked(
            Slot slot,
            int slotId,
            int mouseButton,
            ClickType clickType
    ) {
        if (isActionSlot(slotId)
                && (mouseButton == 0 || mouseButton == 1)) {

            ModNetwork.sendToServer(
                    new TitleActionPacket(
                            slotId,
                            mouseButton == 1
                    )
            );

            return;
        }

        super.slotClicked(
                slot,
                slotId,
                mouseButton,
                clickType
        );
    }

    private static boolean isActionSlot(int slotId) {
        return (slotId >= FIRST_TITLE_SLOT
                && slotId <= LAST_TITLE_SLOT)
                || slotId == CLEAR_MAIN_SLOT
                || slotId == CLEAR_SUB_SLOT;
    }
}
