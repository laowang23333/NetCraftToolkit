package com.example.netcrafttoolkit;

import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.SimpleMenuProvider;

import java.util.ArrayList;
import java.util.List;

/**
 * 玩家称号 GUI。
 * 左键 = 主称号；右键 = 副称号；点击清除按钮取消对应称号。
 *
 * 点击完全走 Minecraft 原版 Container 点击链路：
 * 客户端只发送普通容器点击包，服务端在 clicked() 中处理称号操作。
 */
public class TitleMenu extends ChestMenu {

    private static final int SIZE = 27;
    private static final int FIRST_TITLE_SLOT = 9;
    private static final int LAST_TITLE_SLOT = 17;
    private static final int CLEAR_MAIN_SLOT = 20;
    private static final int CLEAR_SUB_SLOT = 24;

    private final Player player;
    private final TitleManager manager;
    private final SimpleContainer titleContainer;
    private final List<String> slotTitleIds = new ArrayList<>();

    public TitleMenu(int containerId, Inventory inventory) {
        this(new SimpleContainer(SIZE), containerId, inventory);
    }

    private TitleMenu(SimpleContainer container, int containerId, Inventory inventory) {
        super(ModMenus.TITLE_MENU, containerId, inventory, container, 3);
        this.player = inventory.player;
        this.manager = NetCraftToolkit.getTitleManager();
        this.titleContainer = container;

        // 这里故意不再替换成 LockedSlot。
        // 使用原版 Slot，让 Minecraft 正常生成/发送 Container 点击包。
        if (manager != null) {
            rebuild();
        }
    }

    public static void open(ServerPlayer player) {
        player.openMenu(new SimpleMenuProvider(
                (id, inventory, p) -> new TitleMenu(id, inventory),
                Component.literal("我的称号")
        ));
    }

    private void rebuild() {
        if (manager == null) {
            return;
        }

        slotTitleIds.clear();

        for (int i = 0; i < SIZE; i++) {
            titleContainer.setItem(i, ItemStack.EMPTY);
        }

        ItemStack main = new ItemStack(Items.GOLD_INGOT);
        main.setHoverName(Component.literal("主称号：")
                .append(manager.getMainTitle(player.getUUID()) == null
                        ? Component.literal("未设置")
                        : TitleManager.parseText(
                                manager.getTitleText(
                                        manager.getMainTitle(player.getUUID())
                                )
                        )));
        titleContainer.setItem(2, main);

        ItemStack sub = new ItemStack(Items.AMETHYST_SHARD);
        sub.setHoverName(Component.literal("副称号：")
                .append(manager.getSubTitle(player.getUUID()) == null
                        ? Component.literal("未设置")
                        : TitleManager.parseText(
                                manager.getTitleText(
                                        manager.getSubTitle(player.getUUID())
                                )
                        )));
        titleContainer.setItem(6, sub);

        int slot = FIRST_TITLE_SLOT;
        int index = 0;

        for (String id : manager.getOwnedTitles(player.getUUID())) {
            String text = manager.getTitleText(id);

            if (text == null) {
                continue;
            }

            if (slot > LAST_TITLE_SLOT) {
                break;
            }

            ItemStack stack = new ItemStack(
                    index % 2 == 0
                            ? Items.NAME_TAG
                            : Items.ENCHANTED_BOOK
            );

            if (id.equals(manager.getMainTitle(player.getUUID()))
                    || id.equals(manager.getSubTitle(player.getUUID()))) {

                EnchantmentHelper.setEnchantments(
                        java.util.Map.of(
                                Enchantments.UNBREAKING,
                                1
                        ),
                        stack
                );
            }

            stack.setHoverName(TitleManager.parseText(text));
            stack.setHoverName(
                    stack.getHoverName()
                            .copy()
                            .append(Component.literal("  [" + id + "]"))
            );

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

    /**
     * 服务端收到 Minecraft 原版 Container 点击后，在这里统一处理。
     *
     * 关键点：客户端不再需要 TitleActionPacket。
     * 对称号展示槽我们直接消费点击，不调用 super.clicked()，
     * 因此展示物不会真正被拿走。
     */
    @Override
    public void clicked(
            int slotId,
            int dragType,
            ClickType clickType,
            Player clickedPlayer
    ) {
        if (clickedPlayer != player) {
            return;
        }

        if (slotId >= 0 && slotId < SIZE) {
            if (!(clickedPlayer instanceof ServerPlayer)) {
                return;
            }

            if (manager == null) {
                return;
            }

            // 只有普通左/右键点击才执行称号操作。
            if (clickType != ClickType.PICKUP) {
                return;
            }

            if (slotId >= FIRST_TITLE_SLOT && slotId <= LAST_TITLE_SLOT) {
                int index = slotId - FIRST_TITLE_SLOT;

                if (index >= 0 && index < slotTitleIds.size()) {
                    String id = slotTitleIds.get(index);
                    boolean rightClick = dragType == 1;

                    boolean changed = rightClick
                            ? manager.setSubTitle(player.getUUID(), id)
                            : manager.setMainTitle(player.getUUID(), id);

                    NetCraftToolkit.LOGGER.info(
                            "[NetCraftToolkit] 原版Container称号点击: player={}, slot={}, titleId={}, rightClick={}, changed={}",
                            player.getGameProfile().getName(),
                            slotId,
                            id,
                            rightClick,
                            changed
                    );

                    if (changed) {
                        player.sendSystemMessage(
                                Component.literal(
                                        rightClick ? "已设置副称号：" : "已设置主称号："
                                ).append(
                                        TitleManager.parseText(manager.getTitleText(id))
                                )
                        );
                    }

                    rebuild();
                    broadcastChanges();
                }

                return;
            }

            if (slotId == CLEAR_MAIN_SLOT) {
                manager.clearMainTitle(player.getUUID());
                player.sendSystemMessage(Component.literal("已清除主称号。"));
                rebuild();
                broadcastChanges();
                return;
            }

            if (slotId == CLEAR_SUB_SLOT) {
                manager.clearSubTitle(player.getUUID());
                player.sendSystemMessage(Component.literal("已清除副称号。"));
                rebuild();
                broadcastChanges();
                return;
            }

            // 其它 GUI 槽也是展示/装饰槽，消费掉点击，禁止搬运。
            return;
        }

        // 玩家背包区域保持原版行为。
        super.clicked(slotId, dragType, clickType, clickedPlayer);
    }

    /**
     * 兼容旧版 TitleActionPacket 的入口。
     * 当前 GUI 已经完全走 Minecraft 原版 Container 点击链路，
     * 正常情况下不会再调用这个方法；保留它是为了避免旧代码导致编译失败。
     */
    public void handleTitleAction(int slotId, boolean rightClick) {
        if (manager == null) {
            return;
        }

        if (slotId >= FIRST_TITLE_SLOT && slotId <= LAST_TITLE_SLOT) {
            int index = slotId - FIRST_TITLE_SLOT;
            if (index < 0 || index >= slotTitleIds.size()) {
                return;
            }

            String id = slotTitleIds.get(index);
            boolean changed = rightClick
                    ? manager.setSubTitle(player.getUUID(), id)
                    : manager.setMainTitle(player.getUUID(), id);

            if (changed) {
                player.sendSystemMessage(
                        Component.literal(rightClick ? "已设置副称号：" : "已设置主称号：")
                                .append(TitleManager.parseText(manager.getTitleText(id)))
                );
            }

            rebuild();
            broadcastChanges();
            return;
        }

        if (slotId == CLEAR_MAIN_SLOT) {
            manager.clearMainTitle(player.getUUID());
            player.sendSystemMessage(Component.literal("已清除主称号。"));
            rebuild();
            broadcastChanges();
            return;
        }

        if (slotId == CLEAR_SUB_SLOT) {
            manager.clearSubTitle(player.getUUID());
            player.sendSystemMessage(Component.literal("已清除副称号。"));
            rebuild();
            broadcastChanges();
        }
    }

    /**
     * 关闭 GUI 时清空展示容器，绝不让展示物掉落到世界。
     */
    @Override
    public void removed(Player player) {
        titleContainer.clearContent();
        super.removed(player);
    }

    /**
     * 禁止 Shift+点击从称号 GUI 搬运物品。
     */
    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        if (index >= 0 && index < SIZE) {
            return ItemStack.EMPTY;
        }
        return super.quickMoveStack(player, index);
    }
}
