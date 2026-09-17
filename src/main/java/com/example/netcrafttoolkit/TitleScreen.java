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
 * 1. 物品栏文字稍微下移。
 * 2. 背景严格按 3 排 ChestMenu 裁剪。
 * 3. 点击直接按实际 Slot 坐标处理并发送 TitleActionPacket。
 * 4. 悬停直接按实际 Slot 坐标绘制 ItemStack tooltip。
 *
 * 注意：这里不使用 AbstractContainerScreen.isHovering(Slot,...)
 * 因为 Forge 1.20.1 该方法的签名不是 Slot 版本。
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

        // “物品栏”下移一点。
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

        // 只绘制 3 排箱子区域。
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

        // 接上玩家背包区域。
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
        super.render(
                graphics,
                mouseX,
                mouseY,
                partialTick
        );

        /*
         * AbstractContainerScreen 的通用 tooltip 在部分 FCL
         * 环境下没有稳定命中我们的自定义 LockedSlot。
         * 这里使用实际 Slot 的 x/y 坐标补绘称号物品 tooltip。
         */
        for (Slot slot : menu.slots) {
            if (!isActionSlot(slot.index) || !slot.hasItem()) {
                continue;
            }

            if (isInsideSlot(slot, mouseX, mouseY)) {
                graphics.renderTooltip(
                        this.font,
                        slot.getItem(),
                        mouseX,
                        mouseY
                );
                break;
            }
        }
    }

    @Override
    public boolean mouseClicked(
            double mouseX,
            double mouseY,
            int button
    ) {
        if (button == 0 || button == 1) {
            for (Slot slot : menu.slots) {
                if (!isActionSlot(slot.index)) {
                    continue;
                }

                if (!isInsideSlot(slot, mouseX, mouseY)) {
                    continue;
                }

                NetCraftToolkit.LOGGER.info(
                        "[NetCraftToolkit] 客户端称号点击: slot={}, button={}, hasItem={}",
                        slot.index,
                        button,
                        slot.hasItem()
                );

                /*
                 * 这里直接发送服务端操作包。
                 * 不调用 super.mouseClicked，避免原版容器把称号物品
                 * 当普通物品进行拿取/交换。
                 */
                ModNetwork.sendToServer(
                        new TitleActionPacket(
                                slot.index,
                                button == 1
                        )
                );

                return true;
            }
        }

        return super.mouseClicked(
                mouseX,
                mouseY,
                button
        );
    }

    private boolean isInsideSlot(
            Slot slot,
            double mouseX,
            double mouseY
    ) {
        double x = leftPos + slot.x;
        double y = topPos + slot.y;

        return mouseX >= x
                && mouseX < x + 16
                && mouseY >= y
                && mouseY < y + 16;
    }

    private static boolean isActionSlot(int slotId) {
        return (slotId >= FIRST_TITLE_SLOT
                && slotId <= LAST_TITLE_SLOT)
                || slotId == CLEAR_MAIN_SLOT
                || slotId == CLEAR_SUB_SLOT;
    }
}
