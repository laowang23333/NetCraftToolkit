package com.example.netcrafttoolkit;

import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.ArrayList;
import java.util.List;

/**
 * 称号自定义菜单。
 *
 * 服务端保存真实称号状态；客户端只接收菜单里的称号物品和文本，
 * 因此不需要额外的自定义网络包。
 */
public class TitleMenu extends AbstractContainerMenu {

    private static final int CHEST_SIZE = 27;
    private static final int FIRST_TITLE_SLOT = 9;
    private static final int LAST_TITLE_SLOT = 17;
    private static final int CLEAR_MAIN_SLOT = 20;
    private static final int CLEAR_SUB_SLOT = 24;
    private static final String TITLE_ID_TAG = "NetCraftTitleId";

    private final Container titleContainer;
    private final Inventory playerInventory;
    private final List<String> slotTitleIds = new ArrayList<>();

    public TitleMenu(int containerId, Inventory inventory) {
        this(new SimpleContainer(CHEST_SIZE), containerId, inventory);
    }

    private TitleMenu(Container container, int containerId, Inventory inventory) {
        super(ModMenus.TITLE_MENU, containerId);
        this.titleContainer = container;
        this.playerInventory = inventory;

        checkContainerSize(container, CHEST_SIZE);
        container.startOpen(inventory.player);

        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 9; col++) {
                addSlot(new Slot(container, col + row * 9, 8 + col * 18, 18 + row * 18));
            }
        }

        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 9; col++) {
                addSlot(new Slot(inventory, col + row * 9 + 9, 8 + col * 18, 84 + row * 18));
            }
        }

        addSlot(new Slot(inventory, 0, 8, 142));
        for (int col = 1; col < 9; col++) {
            addSlot(new Slot(inventory, col, 26 + (col - 1) * 18, 142));
        }

        rebuildFromServer();
    }

    public static void open(ServerPlayer player) {
        player.openMenu(new net.minecraft.world.SimpleMenuProvider(
                (id, inventory, ignored) -> new TitleMenu(id, inventory),
                Component.literal("我的称号")
        ));
    }

    /** 服务端构建称号物品；客户端菜单打开时没有 ServerPlayer，因此只保留服务端刷新逻辑。 */
    private void rebuildFromServer() {
        if (titleContainer == null) {
            return;
        }

        for (int i = 0; i < CHEST_SIZE; i++) {
            titleContainer.setItem(i, ItemStack.EMPTY);
        }
        slotTitleIds.clear();

        if (!(getPlayer() instanceof ServerPlayer player)) {
            return;
        }

        TitleManager manager = NetCraftToolkit.getTitleManager();
        if (manager == null) {
            return;
        }

        String mainId = manager.getMainTitle(player.getUUID());
        String subId = manager.getSubTitle(player.getUUID());

        ItemStack main = new ItemStack(Items.GOLD_INGOT);
        main.setHoverName(Component.literal("主称号：")
                .append(mainId == null
                        ? Component.literal("未设置")
                        : TitleManager.parseText(manager.getTitleText(mainId))));
        titleContainer.setItem(2, main);

        ItemStack sub = new ItemStack(Items.AMETHYST_SHARD);
        sub.setHoverName(Component.literal("副称号：")
                .append(subId == null
                        ? Component.literal("未设置")
                        : TitleManager.parseText(manager.getTitleText(subId))));
        titleContainer.setItem(6, sub);

        int slot = FIRST_TITLE_SLOT;
        int index = 0;
        for (String id : manager.getOwnedTitles(player.getUUID())) {
            String text = manager.getTitleText(id);
            if (text == null || text.isBlank()) {
                continue;
            }
            if (slot > LAST_TITLE_SLOT) {
                break;
            }

            ItemStack stack = new ItemStack(index % 2 == 0 ? Items.NAME_TAG : Items.ENCHANTED_BOOK);
            stack.getOrCreateTag().putString(TITLE_ID_TAG, id);
            stack.setHoverName(TitleManager.parseText(text));
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

    private Player getPlayer() {
        return playerInventory.player;
    }

    @Override
    public void clicked(int slotId, int dragType, ClickType clickType, Player player) {
        if (!(player instanceof ServerPlayer serverPlayer)) {
            return;
        }

        TitleManager manager = NetCraftToolkit.getTitleManager();
        if (manager == null) {
            return;
        }

        if (slotId >= FIRST_TITLE_SLOT && slotId <= LAST_TITLE_SLOT) {
            ItemStack stack = getSlot(slotId).getItem();
            if (!stack.isEmpty() && stack.hasTag() && stack.getTag().contains(TITLE_ID_TAG)) {
                String id = stack.getTag().getString(TITLE_ID_TAG);
                boolean changed = false;

                if (clickType == ClickType.PICKUP) {
                    changed = manager.setMainTitle(serverPlayer.getUUID(), id);
                    if (changed) {
                        serverPlayer.sendSystemMessage(Component.literal("已设置主称号：")
                                .append(TitleManager.parseText(manager.getTitleText(id))));
                    }
                } else if (clickType == ClickType.PICKUP_ALL) {
                    changed = manager.setSubTitle(serverPlayer.getUUID(), id);
                    if (changed) {
                        serverPlayer.sendSystemMessage(Component.literal("已设置副称号：")
                                .append(TitleManager.parseText(manager.getTitleText(id))));
                    }
                }

                if (changed) {
                    rebuildFromServer();
                    broadcastChanges();
                }
                return;
            }
        }

        if (slotId == CLEAR_MAIN_SLOT && clickType == ClickType.PICKUP) {
            manager.clearMainTitle(serverPlayer.getUUID());
            serverPlayer.sendSystemMessage(Component.literal("已清除主称号。"));
            rebuildFromServer();
            broadcastChanges();
            return;
        }

        if (slotId == CLEAR_SUB_SLOT && clickType == ClickType.PICKUP) {
            manager.clearSubTitle(serverPlayer.getUUID());
            serverPlayer.sendSystemMessage(Component.literal("已清除副称号。"));
            rebuildFromServer();
            broadcastChanges();
            return;
        }

        super.clicked(slotId, dragType, clickType, player);
    }

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        return ItemStack.EMPTY;
    }

    @Override
    public void removed(Player player) {
        super.removed(player);
        titleContainer.stopOpen(player);
    }
}
