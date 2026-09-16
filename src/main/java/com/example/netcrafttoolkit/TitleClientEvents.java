package com.example.netcrafttoolkit;

import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderNameTagEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * 客户端称号头顶显示。
 *
 * 服务端已经把带称号的 customName 同步给客户端，
 * 这里直接把它作为玩家头顶名称标签绘制，避免只改聊天/Tab而头顶不显示。
 */
@Mod.EventBusSubscriber(
        modid = NetCraftToolkit.MOD_ID,
        bus = Mod.EventBusSubscriber.Bus.FORGE,
        value = Dist.CLIENT
)
public final class TitleClientEvents {

    private TitleClientEvents() {
    }

    @SubscribeEvent
    public static void onRenderNameTag(RenderNameTagEvent event) {
        if (!(event.getEntity() instanceof Player player)) {
            return;
        }

        if (!player.isCustomNameVisible() || !player.hasCustomName()) {
            return;
        }

        Component customName = player.getCustomName();
        if (customName != null && !customName.getString().isBlank()) {
            event.setContent(customName);
        }
    }
}
