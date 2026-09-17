package com.example.netcrafttoolkit;

import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.Slot;

/**
 * 称号 GUI 客户端界面。
 *
 * 注意：这个 Screen 不依赖 AbstractContainerScreen 的 hoveredSlot，
 * 也不依赖单一的 mouseX/mouseY 坐标来源。
 *
 * FCL/Android 可能在不同输入路径下给 Screen 的坐标与窗口像素坐标
 * 不一致，因此这里同时尝试：
 * 1. Screen 传入的 GUI 坐标；
 * 2. MouseHandler 的窗口像素坐标转换后的 GUI 坐标；
 * 3. Screen 坐标按窗口 -> GUI 比例转换后的坐标。
 *
 * 只要任意一套坐标命中实际 Slot，就执行操作。
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

        /*
         * Tooltip 同样使用多套坐标。
         * 只对真正的称号/清除槽显示，避免干扰玩家背包 tooltip。
         */
        Slot hovered = findActionSlot(
                mouseX,
                mouseY
        );

        if (hovered != null && hovered.hasItem()) {
            graphics.renderTooltip(
                    this.font,
                    hovered.getItem(),
                    mouseX,
                    mouseY
            );
        }
    }

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

        Slot slot = findActionSlot(
                mouseX,
                mouseY
        );

        if (slot != null) {
            NetCraftToolkit.LOGGER.info(
                    "[NetCraftToolkit] 称号GUI点击命中: slot={}, button={}, hasItem={}, screenMouse=({},{}), leftTop=({},{}), guiSize={}x{}",
                    slot.index,
                    button,
                    slot.hasItem(),
                    mouseX,
                    mouseY,
                    leftPos,
                    topPos,
                    Minecraft.getInstance().getWindow().getGuiScaledWidth(),
                    Minecraft.getInstance().getWindow().getGuiScaledHeight()
            );

            ModNetwork.sendToServer(
                    new TitleActionPacket(
                            slot.index,
                            button == 1
                    )
            );

            // 不交给原版容器搬运流程。
            return true;
        }

        /*
         * 这里非常重要：
         * 即使没有命中我们的 action slot，也保留原版点击流程，
         * 让玩家背包仍然可以正常操作。
         */
        return super.mouseClicked(
                mouseX,
                mouseY,
                button
        );
    }

    /**
     * 用多套可能的坐标系寻找真实 Slot。
     */
    private Slot findActionSlot(
            double screenMouseX,
            double screenMouseY
    ) {
        // 第一套：Screen 收到的坐标，通常已经是 GUI-scaled 坐标。
        Slot slot = findActionSlotAt(
                screenMouseX,
                screenMouseY
        );

        if (slot != null) {
            return slot;
        }

        Minecraft minecraft = Minecraft.getInstance();

        double windowWidth =
                minecraft.getWindow().getWidth();
        double windowHeight =
                minecraft.getWindow().getHeight();

        double guiWidth =
                minecraft.getWindow().getGuiScaledWidth();
        double guiHeight =
                minecraft.getWindow().getGuiScaledHeight();

        /*
         * 第二套：把 Screen 坐标当成窗口像素再缩放。
         * 这是 FCL 输入层最可能出现的情况。
         */
        if (windowWidth > 0 && windowHeight > 0) {
            slot = findActionSlotAt(
                    screenMouseX * guiWidth / windowWidth,
                    screenMouseY * guiHeight / windowHeight
            );

            if (slot != null) {
                return slot;
            }
        }

        /*
         * 第三套：直接读取 GLFW/Minecraft 当前窗口鼠标位置，
         * 再转换成 GUI-scaled 坐标。
         */
        double rawX = minecraft.mouseHandler.xpos();
        double rawY = minecraft.mouseHandler.ypos();

        if (windowWidth > 0 && windowHeight > 0) {
            slot = findActionSlotAt(
                    rawX * guiWidth / windowWidth,
                    rawY * guiHeight / windowHeight
            );

            if (slot != null) {
                return slot;
            }
        }

        /*
         * 第四套：有些输入层已经把 MouseHandler 坐标缩放过，
         * 直接再试一次。
         */
        return findActionSlotAt(
                rawX,
                rawY
        );
    }

    private Slot findActionSlotAt(
            double mouseX,
            double mouseY
    ) {
        for (Slot slot : menu.slots) {
            if (!isActionSlot(slot.index)) {
                continue;
            }

            double x = leftPos + slot.x;
            double y = topPos + slot.y;

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
