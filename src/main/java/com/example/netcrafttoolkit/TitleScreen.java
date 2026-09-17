package com.example.netcrafttoolkit;

import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.Slot;

/** 称号 GUI 客户端界面：展示槽只读，称号点击通过网络包交给服务端。 */
public class TitleScreen extends AbstractContainerScreen<TitleMenu> {

    private static final ResourceLocation CHEST_TEXTURE =
            new ResourceLocation("minecraft", "textures/gui/container/generic_54.png");

    public TitleScreen(TitleMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title);
        this.imageWidth = 176;
        this.imageHeight = 168;
        // 3 行展示区结束后再显示玩家物品栏标题，避免文字压到槽位。
        this.inventoryLabelY = 74;
        this.titleLabelX = 8;
        this.titleLabelY = 6;
    }

    @Override
    protected void renderBg(GuiGraphics graphics, float partialTick, int mouseX, int mouseY) {
        RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
        graphics.blit(CHEST_TEXTURE, leftPos, topPos, 0, 0, imageWidth, imageHeight);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        // 只拦截称号操作槽，避免进入原版容器点击流程。
        for (Slot slot : menu.slots) {
            int id = slot.index;
            if (!isActionSlot(id)) {
                continue;
            }

            int x = leftPos + slot.x;
            int y = topPos + slot.y;
            if (mouseX >= x && mouseX < x + 16 && mouseY >= y && mouseY < y + 16) {
                if (id >= 9 && id <= 17) {
                    if (button != 0 && button != 1) {
                        return true;
                    }
                    ModNetwork.sendToServer(new TitleActionPacket(id, button == 1));
                    return true;
                }

                if (button == 0 || button == 1) {
                    ModNetwork.sendToServer(new TitleActionPacket(id, false));
                }
                return true;
            }
        }

        return super.mouseClicked(mouseX, mouseY, button);
    }

    private static boolean isActionSlot(int id) {
        return (id >= 9 && id <= 17) || id == 20 || id == 24;
    }
}
