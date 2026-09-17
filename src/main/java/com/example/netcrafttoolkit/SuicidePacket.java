package com.example.netcrafttoolkit;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

public class SuicidePacket {

    public SuicidePacket() {}

    public static void encode(SuicidePacket msg, FriendlyByteBuf buf) {
        // 无数据
    }

    public static SuicidePacket decode(FriendlyByteBuf buf) {
        return new SuicidePacket();
    }

    public static void handle(SuicidePacket msg, Supplier<NetworkEvent.Context> ctx) {
        NetworkEvent.Context context = ctx.get();
        context.enqueueWork(() -> {
            ServerPlayer player = context.getSender();
            if (player != null) {
                player.kill();
            }
        });
        context.setPacketHandled(true);
    }
}
