package com.example.netcrafttoolkit;

import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.item.Item;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.SimpleMenuProvider;

import java.util.ArrayList;
import java.util.List;

/**
 * 玩家称号 GUI。
 * 左键 = 主称号；右键 = 副称号；点击清除按钮取消对应称号。
 */
public class TitleMenu extends ChestMenu {

    private static final int SIZE = 27;
    private static final int FIRST_TITLE_SLOT = 9;
    private static final int LAST_TITLE_SLOT = 17;
    private static final int CLEAR_MAIN_SLOT = 20;
    private static final int CLEAR_SUB_SLOT = 24;

    private final ServerPlayer player;
    private final TitleManager manager;
    private final SimpleContainer titleContainer;
    private final List<String> slotTitleIds = new ArrayList<>();

    public TitleMenu(int containerId, Inventory inventory, ServerPlayer player, TitleManager manager) {
        this(new SimpleContainer(SIZE), containerId, inventory, player, manager);
    }

    private TitleMenu(SimpleContainer container, int containerId, Inventory inventory,
                      ServerPlayer player, TitleManager manager) {
        super(MenuType.GENERIC_9x3, containerId, inventory, container, 3);
        this.player = player;
        this.manager = manager;
        this.titleContainer = container;
        rebuild();
    }

    public static void open(ServerPlayer player, TitleManager manager) {
        player.openMenu(new SimpleMenuProvider(
                (id, inventory, p) -> new TitleMenu(id, inventory, player, manager),
                Component.literal("我的称号")
        ));
    }

    private void rebuild() {
        slotTitleIds.clear();
        for (int i = 0; i < SIZE; i++) titleContainer.setItem(i, ItemStack.EMPTY);

        ItemStack main = new ItemStack(Items.GOLD_INGOT);
        main.setHoverName(Component.literal("主称号：")
                .append(manager.getMainTitle(player.getUUID()) == null
                        ? Component.literal("未设置")
                        : TitleManager.parseText(manager.getTitleText(manager.getMainTitle(player.getUUID())))));
        titleContainer.setItem(2, main);

        ItemStack sub = new ItemStack(Items.AMETHYST_SHARD);
        sub.setHoverName(Component.literal("副称号：")
                .append(manager.getSubTitle(player.getUUID()) == null
                        ? Component.literal("未设置")
                        : TitleManager.parseText(manager.getTitleText(manager.getSubTitle(player.getUUID())))));
        titleContainer.setItem(6, sub);

        int slot = FIRST_TITLE_SLOT;
        int index = 0;
        for (String id : manager.getOwnedTitles(player.getUUID())) {
            String text = manager.getTitleText(id);
            if (text == null) continue;
            if (slot > LAST_TITLE_SLOT) break;
            ItemStack stack = new ItemStack(index % 2 == 0 ? Items.NAME_TAG : Items.ENCHANTED_BOOK);
            if (id.equals(manager.getMainTitle(player.getUUID())) || id.equals(manager.getSubTitle(player.getUUID()))) {
                EnchantmentHelper.setEnchantments(java.util.Map.of(Enchantments.UNBREAKING, 1), stack);
            }
            stack.setHoverName(TitleManager.parseText(text));
            stack.setHoverName(stack.getHoverName().copy().append(Component.literal("  [" + id + "]")));
            titleContainer.setItem(slot, stack);
            slotTitleIds.add(id);
            slot++;
            index++;
        }

        ItemStack clearMain = new ItemStack(Items.BARRIER);
        clearMain.setHoverName(Component.literal("清除主称号"));
        titleContainer.setItem(CLEAR_MAIN_SLOT, clearMain);

        ItemStack clearSub = new ItemStack(Items.BARRIER);
        clearSub.setHoverName(Component.literal("清除副称号"));
        titleContainer.setItem(CLEAR_SUB_SLOT, clearSub);
    }

    @Override
    public void clicked(int slotId, int dragType, ClickType clickType, Player clickedPlayer) {
        if (clickedPlayer != player) {
            return;
        }

        if (slotId >= FIRST_TITLE_SLOT && slotId <= LAST_TITLE_SLOT) {
            int index = slotId - FIRST_TITLE_SLOT;
            if (index >= 0 && index < slotTitleIds.size()) {
                String id = slotTitleIds.get(index);
                if (clickType == ClickType.PICKUP) {
                    manager.setMainTitle(player.getUUID(), id);
                    player.sendSystemMessage(Component.literal("已设置主称号：")
                            .append(TitleManager.parseText(manager.getTitleText(id))));
                } else if (clickType == ClickType.PICKUP_ALL) {
                    manager.setSubTitle(player.getUUID(), id);
                    player.sendSystemMessage(Component.literal("已设置副称号：")
                            .append(TitleManager.parseText(manager.getTitleText(id))));
                }
                rebuild();
                broadcastChanges();
                return;
            }
        }

        if (slotId == CLEAR_MAIN_SLOT && clickType == ClickType.PICKUP) {
            manager.clearMainTitle(player.getUUID());
            player.sendSystemMessage(Component.literal("已清除主称号。"));
            rebuild();
            broadcastChanges();
            return;
        }

        if (slotId == CLEAR_SUB_SLOT && clickType == ClickType.PICKUP) {
            manager.clearSubTitle(player.getUUID());
            player.sendSystemMessage(Component.literal("已清除副称号。"));
            rebuild();
            broadcastChanges();
            return;
        }

        super.clicked(slotId, dragType, clickType, clickedPlayer);
    }

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        return ItemStack.EMPTY;
    }
}
