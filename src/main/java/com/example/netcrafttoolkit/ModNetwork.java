package com.example.netcrafttoolkit;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;

public class ModNetwork {
    private static final String PROTOCOL = "1";
    public static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
            new ResourceLocation(NetCraftToolkit.MOD_ID, "main"),
            () -> PROTOCOL, PROTOCOL::equals, PROTOCOL::equals
    );

    public static void register() {
        int id = 0;
        CHANNEL.registerMessage(id++, SuicidePacket.class,
                SuicidePacket::encode, SuicidePacket::decode, SuicidePacket::handle);
        // 保留旧包，避免用户已有源码引用造成编译问题。
        CHANNEL.registerMessage(id++, TitleActionPacket.class,
                TitleActionPacket::encode, TitleActionPacket::decode, TitleActionPacket::handle);
        CHANNEL.registerMessage(id++, OpenTitleScreenPacket.class,
                OpenTitleScreenPacket::encode, OpenTitleScreenPacket::decode, OpenTitleScreenPacket::handle);
        CHANNEL.registerMessage(id++, TitleScreenActionPacket.class,
                TitleScreenActionPacket::encode, TitleScreenActionPacket::decode, TitleScreenActionPacket::handle);
    }

    public static void sendToServer(Object packet) {
        CHANNEL.sendToServer(packet);
    }

    public static void sendToPlayer(ServerPlayer player, Object packet) {
        CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), packet);
    }
}
