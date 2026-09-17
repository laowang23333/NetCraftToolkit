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
 * 玩家称号 GUI。
 *
 * 重点：
 * FCL/Android 某些输入层会把 Screen 收到的 mouseX/mouseY
 * 当成窗口像素坐标，而 Minecraft GUI 的 Slot 坐标是
 * GUI-scaled 坐标。两者不一致时会同时导致：
 *
 * 1. 点击称号完全没反应；
 * 2. 悬停 tooltip 完全不出现。
 *
 * 因此这里统一从 Minecraft MouseHandler 取得原始窗口坐标，
 * 再按照当前 Window 的 GUI 缩放比例转换成 Minecraft GUI 坐标。
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

        // 只把“物品栏”标题下移一点。
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

        // 不信任 FCL 传进来的 mouseX/mouseY，使用真实窗口鼠标位置。
        double[] guiMouse = getGuiMouse();
        double guiX = guiMouse[0];
        double guiY = guiMouse[1];

        for (Slot slot : menu.slots) {
            if (!isActionSlot(slot.index) || !slot.hasItem()) {
                continue;
            }

            if (isInsideSlot(slot, guiX, guiY)) {
                graphics.renderTooltip(
                        this.font,
                        slot.getItem(),
                        (int) guiX,
                        (int) guiY
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
            double[] guiMouse = getGuiMouse();
            double guiX = guiMouse[0];
            double guiY = guiMouse[1];

            for (Slot slot : menu.slots) {
                if (!isActionSlot(slot.index)) {
                    continue;
                }

                if (!isInsideSlot(slot, guiX, guiY)) {
                    continue;
                }

                NetCraftToolkit.LOGGER.info(
                        "[NetCraftToolkit] 称号GUI点击命中: slot={}, button={}, guiMouse=({},{}), screenMouse=({},{}), hasItem={}",
                        slot.index,
                        button,
                        guiX,
                        guiY,
                        mouseX,
                        mouseY,
                        slot.hasItem()
                );

                ModNetwork.sendToServer(
                        new TitleActionPacket(
                                slot.index,
                                button == 1
                        )
                );

                return true;
            }
        }

        return super.mouseClicked(mouseX, mouseY, button);
    }

    /**
     * 将窗口像素坐标转换为 Minecraft 当前 GUI-scaled 坐标。
     *
     * 例如 1536x691 窗口、GUI 宽度 512 时：
     * rawX 750 -> GUI X 250。
     *
     * 这正是 FCL/Android 输入层容易出错的地方。
     */
    private double[] getGuiMouse() {
        Minecraft minecraft = Minecraft.getInstance();

        double rawX = minecraft.mouseHandler.xpos();
        double rawY = minecraft.mouseHandler.ypos();

        double windowWidth =
                minecraft.getWindow().getWidth();
        double windowHeight =
                minecraft.getWindow().getHeight();

        double guiWidth =
                minecraft.getWindow().getGuiScaledWidth();
        double guiHeight =
                minecraft.getWindow().getGuiScaledHeight();

        if (windowWidth <= 0 || windowHeight <= 0) {
            return new double[] { rawX, rawY };
        }

        return new double[] {
                rawX * guiWidth / windowWidth,
                rawY * guiHeight / windowHeight
        };
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
