package com.example.netcrafttoolkit;

import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.Slot;

/**
 * 玩家称号 GUI。
 *
 * 这版只处理两个实际问题：
 * 1. “物品栏”标题下移一点。
 * 2. 点击/悬停使用 Minecraft 已经计算好的实际 Slot。
 *
 * 不再自己用屏幕坐标换算，避免 FCL/Android GUI 缩放导致点击偏移。
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

    private static final int CONTAINER_HEIGHT = 71;
    private static final int PLAYER_INVENTORY_TEXTURE_Y = 126;
    private static final int PLAYER_INVENTORY_HEIGHT = 96;

    public TitleScreen(
            TitleMenu menu,
            Inventory inventory,
            Component title
    ) {
        super(menu, inventory, title);

        this.imageWidth = 176;
        this.imageHeight = 168;

        // 只下移“物品栏”文字，其他 GUI 坐标不动。
        this.inventoryLabelY = 73;

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
        this.renderBackground(graphics);

        RenderSystem.setShaderColor(
                1.0F,
                1.0F,
                1.0F,
                1.0F
        );

        // 3 排箱子区域。
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

        // 玩家物品栏 + 快捷栏区域。
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
     * 强制补绘 Slot 提示。
     *
     * 正常情况下 AbstractContainerScreen 会自己绘制 tooltip。
     * 这里再明确绘制一次称号槽提示，确保 FCL/Android 环境下
     * 鼠标/触摸悬停时仍然能看到称号名称。
     */
    @Override
    public void render(
            GuiGraphics graphics,
            int mouseX,
            int mouseY,
            float partialTick
    ) {
        super.render(
                graphics,
                mouseX,
                mouseY,
                partialTick
        );

        Slot hovered = null;

        for (Slot slot : menu.slots) {
            if (isActionSlot(slot.index)
                    && isHovering(slot, mouseX, mouseY)
                    && slot.hasItem()) {
                hovered = slot;
                break;
            }
        }

        if (hovered != null) {
            graphics.renderTooltip(
                    this.font,
                    hovered.getItem(),
                    mouseX,
                    mouseY
            );
        }
    }

    /**
     * 直接使用 AbstractContainerScreen 的实际 Slot 命中检测。
     *
     * 不使用 mouseX/mouseY + leftPos/topPos 自己计算，
     * 因此 GUI 缩放不会让点击位置和 Slot 错位。
     */
    @Override
    public boolean mouseClicked(
            double mouseX,
            double mouseY,
            int button
    ) {
        if (button != 0 && button != 1) {
            return super.mouseClicked(
                    mouseX,
                    mouseY,
                    button
            );
        }

        for (Slot slot : menu.slots) {
            if (!isActionSlot(slot.index)) {
                continue;
            }

            if (!isHovering(slot, mouseX, mouseY)) {
                continue;
            }

            NetCraftToolkit.LOGGER.info(
                    "[NetCraftToolkit] 客户端称号点击: slot={}, button={}, hasItem={}",
                    slot.index,
                    button,
                    slot.hasItem()
            );

            ModNetwork.sendToServer(
                    new TitleActionPacket(
                            slot.index,
                            button == 1
                    )
            );

            // 明确消费这个点击，不让原版物品搬运流程继续。
            return true;
        }

        return super.mouseClicked(
                mouseX,
                mouseY,
                button
        );
    }

    private static boolean isActionSlot(int slotId) {
        return (slotId >= FIRST_TITLE_SLOT
                && slotId <= LAST_TITLE_SLOT)
                || slotId == CLEAR_MAIN_SLOT
                || slotId == CLEAR_SUB_SLOT;
    }
}
