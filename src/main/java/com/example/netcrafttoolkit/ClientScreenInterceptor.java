package com.example.netcrafttoolkit;

import net.minecraft.client.gui.screens.Screen;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ScreenEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

@Mod.EventBusSubscriber(modid = NetCraftToolkit.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE, value = Dist.CLIENT)
public class ClientScreenInterceptor {

    /** netcraft 炉石菜单的 Screen 类名（用字符串比较，避免直接 import 到非 public 类） */
    private static final String TARGET_SCREEN = "com.jiufeng.netcraft.client.gui.MenuScreen";

    @SubscribeEvent
    public static void onScreenOpening(ScreenEvent.Opening event) {
        Screen current = event.getScreen();
        if (current == null) return;
        if (!TARGET_SCREEN.equals(current.getClass().getName())) return;

        // 用 setNewScreen 替换（而不是 cancel + setScreen，更安全，不会触发两次 removed）
        event.setNewScreen(new MyCustomHearthstoneScreen());
    }
}
