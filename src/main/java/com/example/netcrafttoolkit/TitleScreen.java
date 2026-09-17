package com.example.netcrafttoolkit;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;

import java.util.ArrayList;
import java.util.List;

/**
 * 全新称号界面：不再使用 Container / Slot / AbstractContainerScreen。
 * 所有可操作元素都是原版 Button，专门绕开移动端/FCL 对容器槽点击的影响。
 */
public class TitleScreen extends Screen {
    private final List<String> titleIds;
    private final List<String> titleTexts;
    private final String mainTitle;
    private final String subTitle;

    private static final int ROW_HEIGHT = 25;
    private static final int TITLE_WIDTH = 118;
    private static final int ACTION_WIDTH = 42;

    public TitleScreen(List<String> titleIds, List<String> titleTexts,
                       String mainTitle, String subTitle) {
        super(Component.literal("我的称号"));
        this.titleIds = new ArrayList<>(titleIds);
        this.titleTexts = new ArrayList<>(titleTexts);
        this.mainTitle = mainTitle;
        this.subTitle = subTitle;
    }

    @Override
    protected void init() {
        super.init();
        rebuildButtons();
    }

    private void rebuildButtons() {
        this.clearWidgets();

        int rowCount = Math.min(titleIds.size(), titleTexts.size());
        int contentHeight = 48 + rowCount * ROW_HEIGHT + 35;
        int startY = Math.max(25, (this.height - contentHeight) / 2 + 28);
        int totalWidth = TITLE_WIDTH + ACTION_WIDTH * 2 + 8;
        int startX = (this.width - totalWidth) / 2;

        this.addRenderableWidget(Button.builder(
                Component.literal("§6主称号：").append(displayTitle(mainTitle)),
                b -> { }
        ).bounds(startX, startY - 42, 105, 20).build());

        this.addRenderableWidget(Button.builder(
                Component.literal("§5副称号：").append(displayTitle(subTitle)),
                b -> { }
        ).bounds(startX + 112, startY - 42, 105, 20).build());

        for (int i = 0; i < rowCount; i++) {
            final String id = titleIds.get(i);
            final String text = titleTexts.get(i);
            int y = startY + i * ROW_HEIGHT;

            Button titleButton = Button.builder(
                    displayTitle(text),
                    b -> sendAction(id, false)
            ).bounds(startX, y, TITLE_WIDTH, 20).build();
            titleButton.setTooltip(Tooltip.create(
                    Component.literal("点击设置为主称号\nID: " + id)
            ));
            this.addRenderableWidget(titleButton);

            Button mainButton = Button.builder(
                    Component.literal("主"),
                    b -> sendAction(id, false)
            ).bounds(startX + TITLE_WIDTH + 4, y, ACTION_WIDTH, 20).build();
            this.addRenderableWidget(mainButton);

            Button subButton = Button.builder(
                    Component.literal("副"),
                    b -> sendAction(id, true)
            ).bounds(startX + TITLE_WIDTH + ACTION_WIDTH + 8, y, ACTION_WIDTH, 20).build();
            subButton.setTooltip(Tooltip.create(
                    Component.literal("设置为副称号\nID: " + id)
            ));
            this.addRenderableWidget(subButton);
        }

        int bottomY = startY + rowCount * ROW_HEIGHT + 7;
        this.addRenderableWidget(Button.builder(
                Component.literal("清除主称号"),
                b -> sendClear(false)
        ).bounds(startX, bottomY, 80, 20).build());

        this.addRenderableWidget(Button.builder(
                Component.literal("清除副称号"),
                b -> sendClear(true)
        ).bounds(startX + 84, bottomY, 80, 20).build());

        this.addRenderableWidget(Button.builder(
                Component.literal("关闭"),
                b -> this.onClose()
        ).bounds(startX + 168, bottomY, 50, 20).build());
    }

    private Component displayTitle(String text) {
        if (text == null || text.isBlank()) {
            return Component.literal("未设置");
        }
        return TitleManager.parseText(text);
    }

    private void sendAction(String id, boolean rightClick) {
        ModNetwork.sendToServer(new TitleScreenActionPacket(
                TitleScreenActionPacket.SET_TITLE,
                id,
                rightClick
        ));
    }

    private void sendClear(boolean sub) {
        ModNetwork.sendToServer(new TitleScreenActionPacket(
                sub ? TitleScreenActionPacket.CLEAR_SUB : TitleScreenActionPacket.CLEAR_MAIN,
                "",
                false
        ));
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        this.renderBackground(graphics);
        super.render(graphics, mouseX, mouseY, partialTick);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
