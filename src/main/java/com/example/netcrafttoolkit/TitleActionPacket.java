package com.example.netcrafttoolkit;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * 旧称号点击数据包兼容类。
 *
 * 当前 GUI 已改为完全使用 Minecraft 原版 Container 点击包，
 * 因此正常情况下不会再发送 TitleActionPacket。
 * 保留解码/处理入口，避免旧网络注册代码导致编译失败。
 */
public final class TitleActionPacket {

    private final int slotId;
    private final boolean rightClick;

    public TitleActionPacket(int slotId, boolean rightClick) {
        this.slotId = slotId;
        this.rightClick = rightClick;
    }

    public static void encode(TitleActionPacket packet, FriendlyByteBuf buf) {
        buf.writeVarInt(packet.slotId);
        buf.writeBoolean(packet.rightClick);
    }

    public static TitleActionPacket decode(FriendlyByteBuf buf) {
        return new TitleActionPacket(buf.readVarInt(), buf.readBoolean());
    }

    public static void handle(
            TitleActionPacket packet,
            Supplier<NetworkEvent.Context> supplier
    ) {
        NetworkEvent.Context context = supplier.get();

        context.enqueueWork(() -> {
            ServerPlayer player = context.getSender();
            if (player == null) {
                return;
            }

            AbstractContainerMenu menu = player.containerMenu;
            if (menu instanceof TitleMenu titleMenu) {
                // 仅作为旧客户端的兼容入口，实际新 GUI 不会走这里。
                titleMenu.handleTitleAction(packet.slotId, packet.rightClick);
            }
        });

        context.setPacketHandled(true);
    }
}
