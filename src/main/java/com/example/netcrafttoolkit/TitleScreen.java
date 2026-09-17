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
 * 称号 GUI 客户端界面。
 *
 * 这版不再自己接管整套 mouseClicked 坐标流程：
 * - 点击交给 AbstractContainerScreen 的正常 Slot 流程，再由 slotClicked 拦截称号操作；
 * - Tooltip 直接使用 vanilla 当前 hoveredSlot；
 * - 额外输入路径由 TitleClientInputEvents 兜底。
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

        // “物品栏”下移一点，保持当前 GUI 外观。
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
        this.renderBackground(graphics);

        RenderSystem.setShaderColor(
                1.0F,
                1.0F,
                1.0F,
                1.0F
        );

        // 3 排容器区域。
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

        // 玩家背包 + 快捷栏区域。
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

    @Override
    public void render(
            GuiGraphics graphics,
            int mouseX,
            int mouseY,
            float partialTick
    ) {
        super.render(graphics, mouseX, mouseY, partialTick);

        // 直接使用 AbstractContainerScreen 当前已经判定好的 hoveredSlot。
        // 这样不会再因为 FCL/Android 的坐标换算导致 tooltip 命中失败。
        Slot hovered = this.hoveredSlot;
        if (hovered != null
                && isActionSlot(hovered.index)
                && hovered.hasItem()) {
            graphics.renderTooltip(
                    this.font,
                    hovered.getItem(),
                    mouseX,
                    mouseY
            );
        }
    }

    /**
     * 原版容器确认 Slot 点击后会进入这里。
     * 称号槽在这里直接改走自己的网络包，避免物品被原版容器拿走。
     */
    @Override
    protected void slotClicked(
            Slot slot,
            int slotId,
            int mouseButton,
            ClickType clickType
    ) {
        if (slot != null
                && isActionSlot(slot.index)
                && (mouseButton == 0 || mouseButton == 1)) {

            NetCraftToolkit.LOGGER.info(
                    "[NetCraftToolkit] 客户端称号点击: slot={}, button={}, clickType={}, screenMouse=({},{}), hoveredSlot={}",
                    slot.index,
                    mouseButton,
                    clickType,
                    this.minecraft != null ? this.minecraft.mouseHandler.xpos() : -1,
                    this.minecraft != null ? this.minecraft.mouseHandler.ypos() : -1,
                    this.hoveredSlot != null ? this.hoveredSlot.index : -1
            );

            ModNetwork.sendToServer(
                    new TitleActionPacket(
                            slot.index,
                            mouseButton == 1
                    )
            );
            return;
        }

        super.slotClicked(slot, slotId, mouseButton, clickType);
    }

    /**
     * 给 Forge ScreenEvent 兜底调用。
     * 优先使用 vanilla 已经判定的 hoveredSlot；若事件发生时 hoveredSlot 尚未更新，
     * 再使用事件提供的 GUI 坐标进行一次精确 Slot 命中。
     */
    public boolean handleExternalClick(
            double mouseX,
            double mouseY,
            int button
    ) {
        if (button != 0 && button != 1) {
            return false;
        }

        Slot slot = this.hoveredSlot;
        if (slot == null || !isActionSlot(slot.index)) {
            slot = findActionSlotAt(mouseX, mouseY);
        }

        if (slot == null) {
            return false;
        }

        NetCraftToolkit.LOGGER.info(
                "[NetCraftToolkit] Forge称号GUI点击兜底: slot={}, button={}, mouse=({},{}), hoveredSlot={}",
                slot.index,
                button,
                mouseX,
                mouseY,
                this.hoveredSlot != null ? this.hoveredSlot.index : -1
        );

        ModNetwork.sendToServer(
                new TitleActionPacket(
                        slot.index,
                        button == 1
                )
        );
        return true;
    }

    private Slot findActionSlotAt(
            double mouseX,
            double mouseY
    ) {
        for (Slot slot : this.menu.slots) {
            if (!isActionSlot(slot.index)) {
                continue;
            }

            double x = this.leftPos + slot.x;
            double y = this.topPos + slot.y;

            if (mouseX >= x
                    && mouseX < x + 16
                    && mouseY >= y
                    && mouseY < y + 16) {
                return slot;
            }
        }

        return null;
    }

    private static boolean isActionSlot(int slotId) {
        return (slotId >= FIRST_TITLE_SLOT
                && slotId <= LAST_TITLE_SLOT)
                || slotId == CLEAR_MAIN_SLOT
                || slotId == CLEAR_SUB_SLOT;
    }
}
