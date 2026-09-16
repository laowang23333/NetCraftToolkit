package com.example.netcrafttoolkit;

import net.minecraft.client.gui.screens.MenuScreens;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

/**
 * 客户端注册称号自定义 GUI。
 *
 * Forge 1.20.1 / 47.4.13 使用 FMLClientSetupEvent + MenuScreens.register
 * 注册 AbstractContainerScreen，而不是 RegisterMenuScreensEvent。
 */
@Mod.EventBusSubscriber(
        modid = NetCraftToolkit.MOD_ID,
        bus = Mod.EventBusSubscriber.Bus.MOD,
        value = Dist.CLIENT
)
public final class TitleClient {

    private TitleClient() {
    }

    @SubscribeEvent
    public static void clientSetup(FMLClientSetupEvent event) {
        event.enqueueWork(() ->
                MenuScreens.register(ModMenus.TITLE_MENU, TitleScreen::new)
        );
    }
}
