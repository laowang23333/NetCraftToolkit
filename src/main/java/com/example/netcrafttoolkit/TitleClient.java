package com.example.netcrafttoolkit;

import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.common.Mod;

/** 普通 Screen 不需要 MenuScreens.register。 */
@Mod.EventBusSubscriber(modid = NetCraftToolkit.MOD_ID, bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public final class TitleClient {
    private TitleClient() {}
}
