package com.example.netcrafttoolkit;

import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ScreenEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * 旧称号点击兜底事件。
 *
 * v10 起称号 GUI 已完全改为 Minecraft 原版 Container 点击链路，
 * 因此这里不再自行处理鼠标点击，只保留类以兼容现有工程文件。
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
        // 故意留空：不拦截、不重复发送任何称号点击。
    }
}
