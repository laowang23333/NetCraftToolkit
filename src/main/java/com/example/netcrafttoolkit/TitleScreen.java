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
 * 玩家称号 GUI 客户端界面。
 *
 * 视觉：
 * - 使用 3 行箱子 GUI 的标准高度 166，避免把 generic_54 的第 4~6 排
 *   当成称号 GUI 的空槽显示出来。
 *
 * 点击：
 * - 不再自己计算屏幕像素坐标。
 * - 直接接管 AbstractContainerScreen 已经识别出的 Slot 点击。
 * - 左键 = 主称号，右键 = 副称号。
 * - 清除主/副称号也走同一个网络包。
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

        // 3 行箱子 + 玩家背包/快捷栏的标准 GUI 高度。
        this.imageWidth = 176;
        this.imageHeight = 166;

        // ChestMenu 3 行时，玩家物品栏标题位于这里。
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
        RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);

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
     * 接管已经被 AbstractContainerScreen 定位到的槽位点击。
     *
     * 这样不再依赖 mouseX/mouseY 与 GUI 缩放之间的换算，
     * 手机端/FCL 的 GUI 缩放也不会导致点击坐标偏移。
     */
    @Override
    protected void slotClicked(
            Slot slot,
            int slotId,
            int mouseButton,
            ClickType clickType
    ) {
        if (slot != null
                && isActionSlot(slotId)
                && (mouseButton == 0 || mouseButton == 1)) {

            ModNetwork.sendToServer(
                    new TitleActionPacket(slotId, mouseButton == 1)
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
        return (slotId >= FIRST_TITLE_SLOT && slotId <= LAST_TITLE_SLOT)
                || slotId == CLEAR_MAIN_SLOT
                || slotId == CLEAR_SUB_SLOT;
    }
}
