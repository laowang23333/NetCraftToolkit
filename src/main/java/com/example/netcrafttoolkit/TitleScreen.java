package com.example.netcrafttoolkit;

import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.Slot;

/**
 * 玩家称号 GUI 客户端界面。
 *
 * 关键点：
 * - 使用与 TitleMenu 完全匹配的 3 行箱子尺寸。
 * - 称号/清除槽的点击由客户端直接发送 TitleActionPacket 到服务端。
 * - 不让 AbstractContainerScreen 的原版点击流程处理称号展示槽。
 */
public class TitleScreen extends AbstractContainerScreen<TitleMenu> {

    private static final ResourceLocation CHEST_TEXTURE =
            new ResourceLocation("minecraft", "textures/gui/container/generic_54.png");

    private static final int FIRST_TITLE_SLOT = 9;
    private static final int LAST_TITLE_SLOT = 17;
    private static final int CLEAR_MAIN_SLOT = 20;
    private static final int CLEAR_SUB_SLOT = 24;

    public TitleScreen(TitleMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title);

        // 3 行箱子菜单的标准尺寸。
        this.imageWidth = 176;

        // 使用 generic_54.png 完整 222px 高度，覆盖称号区、玩家背包和热键栏，
        // 避免之前背景在玩家背包上方提前结束导致底部“断一截”。
        this.imageHeight = 222;
        this.inventoryLabelY = 132;
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
        RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);

        // 绘制完整 generic_54 背景。
        graphics.blit(
                CHEST_TEXTURE,
                leftPos,
                topPos,
                0,
                0,
                imageWidth,
                imageHeight
        );
    }

    /**
     * 称号 GUI 的真正点击入口。
     *
     * Minecraft 的 AbstractContainerScreen 会把点击发送给原版
     * ContainerMenu#clicked()。我们的展示槽全部 LockedSlot，因此
     * 原版流程不会改变称号状态。
     *
     * 所以这里在客户端先截获称号槽点击，再通过网络包让服务端
     * 修改 TitleManager。
     */
    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 0 || button == 1) {
            int slotId = findClickedActionSlot(mouseX, mouseY);

            if (slotId >= 0) {
                ModNetwork.sendToServer(
                        new TitleActionPacket(slotId, button == 1)
                );
                return true;
            }
        }

        return super.mouseClicked(mouseX, mouseY, button);
    }

    /**
     * 根据 GUI 坐标找到称号操作槽。
     * 不直接写死像素位置，而是读取 TitleMenu 中 Slot 的坐标，
     * 这样 GUI 偏移/缩放变化时仍然跟槽位同步。
     */
    private int findClickedActionSlot(double mouseX, double mouseY) {
        for (int slotId = FIRST_TITLE_SLOT; slotId <= LAST_TITLE_SLOT; slotId++) {
            if (isInsideSlot(slotId, mouseX, mouseY)) {
                return slotId;
            }
        }

        if (isInsideSlot(CLEAR_MAIN_SLOT, mouseX, mouseY)) {
            return CLEAR_MAIN_SLOT;
        }

        if (isInsideSlot(CLEAR_SUB_SLOT, mouseX, mouseY)) {
            return CLEAR_SUB_SLOT;
        }

        return -1;
    }

    private boolean isInsideSlot(int slotId, double mouseX, double mouseY) {
        if (slotId < 0 || slotId >= menu.slots.size()) {
            return false;
        }

        Slot slot = menu.getSlot(slotId);

        double x = leftPos + slot.x;
        double y = topPos + slot.y;

        return mouseX >= x
                && mouseX < x + 16
                && mouseY >= y
                && mouseY < y + 16;
    }
}
