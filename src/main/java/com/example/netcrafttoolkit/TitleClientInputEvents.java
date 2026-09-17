package com.example.netcrafttoolkit;

import net.minecraft.client.gui.screens.Screen;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ScreenEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * 称号 GUI 的 Forge ScreenEvent 输入兜底。
 *
 * 正常情况下 TitleScreen.slotClicked 会处理点击。
 * 如果某些客户端输入层没有把点击完整交给 Screen，则尝试从
 * ScreenEvent.MouseButtonPressed.Post 再执行一次称号操作。
 */
@Mod.EventBusSubscriber(
        modid = NetCraftToolkit.MOD_ID,
        bus = Mod.EventBusSubscriber.Bus.FORGE,
        value = Dist.CLIENT
)
public final class TitleClientInputEvents {

    private TitleClientInputEvents() {
    }

    @SubscribeEvent
    public static void onMouseButtonPressed(ScreenEvent.MouseButtonPressed.Post event) {
        Screen screen = event.getScreen();
        if (!(screen instanceof TitleScreen titleScreen)) {
            return;
        }

        int button = event.getButton();
        if (button != 0 && button != 1) {
            return;
        }

        // 正常 slotClicked 已经处理时，不重复发包。
        if (event.wasHandled()) {
            return;
        }

        if (titleScreen.handleExternalClick(
                event.getMouseX(),
                event.getMouseY(),
                button
        )) {
            // Post 事件允许通过结果强制把这次点击视为已处理。
            event.setResult(net.minecraftforge.eventbus.api.Event.Result.ALLOW);
        }
    }
}
