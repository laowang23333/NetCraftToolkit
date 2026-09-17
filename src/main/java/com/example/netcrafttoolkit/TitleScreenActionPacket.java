package com.example.netcrafttoolkit;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/** 普通 Screen 按钮 -> 服务端称号操作。 */
public final class TitleScreenActionPacket {
    public static final int SET_TITLE = 0;
    public static final int CLEAR_MAIN = 1;
    public static final int CLEAR_SUB = 2;

    private final int action;
    private final String titleId;
    private final boolean sub;

    public TitleScreenActionPacket(int action, String titleId, boolean sub) {
        this.action = action;
        this.titleId = titleId == null ? "" : titleId;
        this.sub = sub;
    }

    public static void encode(TitleScreenActionPacket packet, FriendlyByteBuf buf) {
        buf.writeVarInt(packet.action);
        buf.writeUtf(packet.titleId, 256);
        buf.writeBoolean(packet.sub);
    }

    public static TitleScreenActionPacket decode(FriendlyByteBuf buf) {
        return new TitleScreenActionPacket(
                buf.readVarInt(), buf.readUtf(256), buf.readBoolean()
        );
    }

    public static void handle(TitleScreenActionPacket packet,
                              Supplier<NetworkEvent.Context> supplier) {
        NetworkEvent.Context context = supplier.get();
        context.enqueueWork(() -> {
            ServerPlayer player = context.getSender();
            if (player == null) return;

            TitleManager manager = NetCraftToolkit.getTitleManager();
            if (manager == null) return;

            if (packet.action == SET_TITLE) {
                // 必须从玩家自己的持有列表中选择，防止客户端伪造称号。
                if (!manager.getOwnedTitles(player.getUUID()).contains(packet.titleId)) {
                    return;
                }
                if (packet.sub) {
                    manager.setSubTitle(player.getUUID(), packet.titleId);
                } else {
                    manager.setMainTitle(player.getUUID(), packet.titleId);
                }
            } else if (packet.action == CLEAR_MAIN) {
                manager.clearMainTitle(player.getUUID());
            } else if (packet.action == CLEAR_SUB) {
                manager.clearSubTitle(player.getUUID());
            }

            player.sendSystemMessage(net.minecraft.network.chat.Component.literal("称号设置已更新。"));
        });
        context.setPacketHandled(true);
    }
}
