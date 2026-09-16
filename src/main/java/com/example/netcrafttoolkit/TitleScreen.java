package com.example.netcrafttoolkit;

import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;

/** 使用 Minecraft 原生三行箱子界面，不再加载自定义称号贴图。 */
public class TitleScreen extends AbstractContainerScreen<TitleMenu> {

    private static final ResourceLocation CHEST_TEXTURE =
            new ResourceLocation("minecraft", "textures/gui/container/generic_54.png");

    public TitleScreen(TitleMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title);
        this.imageWidth = 176;
        this.imageHeight = 166;
        this.inventoryLabelY = 74;
        this.titleLabelX = 8;
        this.titleLabelY = 6;
    }

    @Override
    protected void renderBg(GuiGraphics graphics, float partialTick, int mouseX, int mouseY) {
        RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
        graphics.blit(CHEST_TEXTURE, leftPos, topPos, 0, 0, imageWidth, imageHeight);
    }
}
