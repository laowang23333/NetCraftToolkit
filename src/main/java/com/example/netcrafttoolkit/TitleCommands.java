package com.example.netcrafttoolkit;

import com.mojang.brigadier.arguments.StringArgumentType;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

/** 称号指令：管理指令仅 OP 可用，玩家自己的称号通过 /mytitle GUI 管理。 */
public class TitleCommands {

    private final TitleManager manager;

    public TitleCommands(TitleManager manager) {
        this.manager = manager;
    }

    @SubscribeEvent
    public void onRegisterCommands(RegisterCommandsEvent event) {
        event.getDispatcher().register(
                Commands.literal("mytitle")
                        .executes(ctx -> {
                            if (!(ctx.getSource().getEntity() instanceof ServerPlayer player)) {
                                return 0;
                            }
                            TitleMenu.open(player);
                            return 1;
                        })
        );

        event.getDispatcher().register(
                Commands.literal("gifttitle")
                        .requires(source -> source.hasPermission(2))
                        .then(Commands.argument("player", EntityArgument.player())
                                .then(Commands.argument("title", StringArgumentType.word())
                                        .suggests((ctx, builder) -> {
                                            for (String id : manager.getDefinitions().keySet()) builder.suggest(id);
                                            return builder.buildFuture();
                                        })
                                        .executes(ctx -> {
                                            ServerPlayer target = EntityArgument.getPlayer(ctx, "player");
                                            String id = StringArgumentType.getString(ctx, "title");
                                            if (!manager.getDefinitions().containsKey(id)) {
                                                ctx.getSource().sendFailure(Component.literal("不存在的称号ID: " + id));
                                                return 0;
                                            }
                                            manager.giveTitle(target.getUUID(), id);
                                            ctx.getSource().sendSuccess(() -> Component.literal("已授予 " + target.getGameProfile().getName() + " 称号：" + id), true);
                                            target.sendSystemMessage(Component.literal("获得称号：").append(TitleManager.parseText(manager.getTitleText(id))));
                                            return 1;
                                        })
                                )
                        )
        );

        event.getDispatcher().register(
                Commands.literal("removetitle")
                        .requires(source -> source.hasPermission(2))
                        .then(Commands.argument("player", EntityArgument.player())
                                .then(Commands.argument("title", StringArgumentType.word())
                                        .suggests((ctx, builder) -> {
                                            try {
                                                ServerPlayer target = EntityArgument.getPlayer(ctx, "player");
                                                for (String id : manager.getOwnedTitles(target.getUUID())) builder.suggest(id);
                                            } catch (Exception ignored) { }
                                            return builder.buildFuture();
                                        })
                                        .executes(ctx -> {
                                            ServerPlayer target = EntityArgument.getPlayer(ctx, "player");
                                            String id = StringArgumentType.getString(ctx, "title");
                                            if (!manager.removeTitle(target.getUUID(), id)) {
                                                ctx.getSource().sendFailure(Component.literal("该玩家没有这个称号：" + id));
                                                return 0;
                                            }
                                            ctx.getSource().sendSuccess(() -> Component.literal("已移除 " + target.getGameProfile().getName() + " 的称号：" + id), true);
                                            return 1;
                                        })
                                )
                        )
        );
    }
}
