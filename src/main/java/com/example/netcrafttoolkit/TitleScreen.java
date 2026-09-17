package com.example.netcrafttoolkit;

import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;

/**
 * 玩家称号 GUI 客户端界面。
 *
 * 这里故意不拦截 mouseClicked / slotClicked。
 * Minecraft 原版的 AbstractContainerScreen 点击流程会正常把
 * 容器点击发送到服务端，而 TitleMenu.clicked() 已经负责真正的
 * 主/副称号处理。
 *
 * 背景也按原版 ContainerScreen 的方式裁剪 generic_54.png：
 * 只显示 3 排箱子区域 + 玩家背包区域，
 * 不再把 generic_54 的 6 排箱子纹理直接画进 3 排菜单。
 */
public class TitleScreen extends AbstractContainerScreen<TitleMenu> {

    private static final ResourceLocation CHEST_TEXTURE =
            new ResourceLocation(
                    "minecraft",
                    "textures/gui/container/generic_54.png"
            );

    /** 3 排容器本体：3 * 18 + 17。 */
    private static final int CONTAINER_HEIGHT = 71;

    /** generic_54.png 中玩家背包区域的起始 Y。 */
    private static final int PLAYER_INVENTORY_TEXTURE_Y = 126;

    /** 玩家背包 + 快捷栏区域高度。 */
    private static final int PLAYER_INVENTORY_HEIGHT = 96;

    public TitleScreen(
            TitleMenu menu,
            Inventory inventory,
            Component title
    ) {
        super(menu, inventory, title);

        /*
         * 3 排 ChestMenu 的标准尺寸。
         * 168 = 114 + 3 * 18
         */
        this.imageWidth = 176;
        this.imageHeight = 168;

        /*
         * ChestScreen 3 排时玩家物品栏标题的标准位置。
         */
        this.inventoryLabelY = 61;

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
        /*
         * 1.20.1 的 AbstractContainerScreen 背景流程中，
         * 自定义容器背景需要先画屏幕暗背景。
         * 这样也消除“GUI did not draw the dark background layer”警告。
         */
        this.renderBackground(graphics);

        RenderSystem.setShaderColor(
                1.0F,
                1.0F,
                1.0F,
                1.0F
        );

        /*
         * 第一段：
         * generic_54 左上角只取 3 排箱子需要的 176x71。
         *
         * 注意这里显式指定 256x256 纹理尺寸，
         * 因为 generic_54.png 是 256x256 的整张 GUI 贴图。
         */
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

        /*
         * 第二段：
         * 从 generic_54 的 y=126 开始取玩家背包区域，
         * 接在 3 排容器背景后面。
         *
         * 这样屏幕上实际显示的是：
         *
         *   3 排称号槽
         *   玩家物品栏
         *   快捷栏
         *
         * 而不是把 6 排箱子纹理硬塞进来。
         */
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

    /*
     * 这里没有重写 mouseClicked() 或 slotClicked()。
     *
     * 点击会走 Minecraft 原版 AbstractContainerScreen -> 菜单点击
     * 流程，最终由 TitleMenu.clicked() 在服务端处理：
     *
     * 左键称号 = 主称号
     * 右键称号 = 副称号
     * 清除主/副称号同样由 TitleMenu 处理
     *
     * 不再从客户端自行发送 TitleActionPacket，
     * 避免手机/FCL 触摸输入被我们自己的点击拦截逻辑吞掉。
     */
}
