package com.example.netcrafttoolkit;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.api.distmarker.OnlyIn;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/** 服务端打开普通 Screen 时，把玩家自己的称号数据发送到客户端。 */
public final class OpenTitleScreenPacket {
    private final List<String> ids;
    private final List<String> texts;
    private final String main;
    private final String sub;

    public OpenTitleScreenPacket(List<String> ids, List<String> texts, String main, String sub) {
        this.ids = new ArrayList<>(ids);
        this.texts = new ArrayList<>(texts);
        this.main = main == null ? "" : main;
        this.sub = sub == null ? "" : sub;
    }

    public static void encode(OpenTitleScreenPacket packet, FriendlyByteBuf buf) {
        int count = Math.min(Math.min(packet.ids.size(), packet.texts.size()), 64);
        buf.writeVarInt(count);
        for (int i = 0; i < count; i++) {
            buf.writeUtf(packet.ids.get(i), 256);
            buf.writeUtf(packet.texts.get(i), 4096);
        }
        buf.writeUtf(packet.main, 256);
        buf.writeUtf(packet.sub, 256);
    }

    public static OpenTitleScreenPacket decode(FriendlyByteBuf buf) {
        int count = Math.min(buf.readVarInt(), 64);
        List<String> ids = new ArrayList<>();
        List<String> texts = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            ids.add(buf.readUtf(256));
            texts.add(buf.readUtf(4096));
        }
        return new OpenTitleScreenPacket(ids, texts, buf.readUtf(256), buf.readUtf(256));
    }

    public static void handle(OpenTitleScreenPacket packet,
                              Supplier<NetworkEvent.Context> supplier) {
        NetworkEvent.Context context = supplier.get();
        context.enqueueWork(() -> openClient(packet));
        context.setPacketHandled(true);
    }

    @OnlyIn(Dist.CLIENT)
    private static void openClient(OpenTitleScreenPacket packet) {
        net.minecraft.client.Minecraft.getInstance().setScreen(
                new TitleScreen(packet.ids, packet.texts, packet.main, packet.sub)
        );
    }
}
