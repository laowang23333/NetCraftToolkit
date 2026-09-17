package com.example.netcrafttoolkit;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/** 客户端称号 GUI 点击 -> 服务端的安全操作包。 */
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

    public static void handle(TitleActionPacket packet, Supplier<NetworkEvent.Context> supplier) {
        NetworkEvent.Context context = supplier.get();
        context.enqueueWork(() -> {
            ServerPlayer player = context.getSender();
            if (player == null) {
                return;
            }

            AbstractContainerMenu menu = player.containerMenu;
            if (menu instanceof TitleMenu titleMenu) {
                titleMenu.handleTitleAction(packet.slotId, packet.rightClick);
            }
        });
        context.setPacketHandled(true);
    }
}
