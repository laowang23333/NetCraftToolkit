package com.example.netcrafttoolkit;

import net.minecraft.world.inventory.MenuType;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.registries.RegisterEvent;

/** 称号系统自定义菜单类型注册。 */
@Mod.EventBusSubscriber(modid = NetCraftToolkit.MOD_ID, bus = Mod.EventBusSubscriber.Bus.MOD)
public final class ModMenus {

    public static MenuType<TitleMenu> TITLE_MENU;

    private ModMenus() {
    }

    @SubscribeEvent
    public static void register(RegisterEvent event) {
        event.register(
                net.minecraft.core.registries.Registries.MENU,
                helper -> {
                    TITLE_MENU = new MenuType<>(TitleMenu::new, net.minecraft.world.flag.FeatureFlags.DEFAULT_FLAGS);
                    helper.register(
                            new ResourceLocation(
                                    NetCraftToolkit.MOD_ID,
                                    "title_menu"
                            ),
                            TITLE_MENU
                    );
                }
        );
    }
}
