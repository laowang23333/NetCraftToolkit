package com.example.netcrafttoolkit;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * 客户端称号 GUI 点击 -> 服务端操作包。
 *
 * 客户端只发送：
 * - slotId
 * - 是否右键
 *
 * 真正的称号修改仍然只在服务端执行。
 */
public final class TitleActionPacket {

    private final int slotId;
    private final boolean rightClick;

    public TitleActionPacket(int slotId, boolean rightClick) {
        this.slotId = slotId;
        this.rightClick = rightClick;
    }

    public static void encode(
            TitleActionPacket packet,
            FriendlyByteBuf buf
    ) {
        buf.writeVarInt(packet.slotId);
        buf.writeBoolean(packet.rightClick);
    }

    public static TitleActionPacket decode(
            FriendlyByteBuf buf
    ) {
        return new TitleActionPacket(
                buf.readVarInt(),
                buf.readBoolean()
        );
    }

    public static void handle(
            TitleActionPacket packet,
            Supplier<NetworkEvent.Context> supplier
    ) {
        NetworkEvent.Context context = supplier.get();

        context.enqueueWork(() -> {
            ServerPlayer player = context.getSender();

            if (player == null) {
                NetCraftToolkit.LOGGER.warn(
                        "[NetCraftToolkit] TitleActionPacket 收到时 sender=null"
                );
                return;
            }

            AbstractContainerMenu menu =
                    player.containerMenu;

            NetCraftToolkit.LOGGER.info(
                    "[NetCraftToolkit] 收到称号点击包: player={}, slot={}, rightClick={}, menu={}",
                    player.getGameProfile().getName(),
                    packet.slotId,
                    packet.rightClick,
                    menu.getClass().getName()
            );

            if (menu instanceof TitleMenu titleMenu) {
                titleMenu.handleTitleAction(
                        packet.slotId,
                        packet.rightClick
                );
            } else {
                NetCraftToolkit.LOGGER.warn(
                        "[NetCraftToolkit] 称号点击包被拒绝：当前菜单不是 TitleMenu，player={}, menu={}",
                        player.getGameProfile().getName(),
                        menu.getClass().getName()
                );
            }
        });

        context.setPacketHandled(true);
    }
}
