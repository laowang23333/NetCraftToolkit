package com.example.netcrafttoolkit;

import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;

/** 真正使用 assets/netcrafttoolkit/textures/gui/title_menu.png 的客户端称号界面。 */
public class TitleScreen extends AbstractContainerScreen<TitleMenu> {

    private static final ResourceLocation TEXTURE =
            new ResourceLocation(NetCraftToolkit.MOD_ID, "textures/gui/title_menu.png");

    public TitleScreen(TitleMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title);
        this.imageWidth = 512;
        this.imageHeight = 320;
        this.inventoryLabelY = 305;
        this.titleLabelX = 0;
        this.titleLabelY = 0;
    }

    @Override
    protected void init() {
        super.init();
        this.leftPos = (this.width - this.imageWidth) / 2;
        this.topPos = Math.max(4, (this.height - this.imageHeight) / 2);
    }

    @Override
    protected void renderBg(GuiGraphics graphics, float partialTick, int mouseX, int mouseY) {
        RenderSystem.enableBlend();
        graphics.blit(TEXTURE, leftPos, topPos, 0, 0, imageWidth, imageHeight, imageWidth, imageHeight);
        RenderSystem.disableBlend();
    }

    @Override
    protected void renderLabels(GuiGraphics graphics, int mouseX, int mouseY) {
        // 标题、按钮和说明已经绘制在自定义贴图中，避免重复文字。
    }
}
