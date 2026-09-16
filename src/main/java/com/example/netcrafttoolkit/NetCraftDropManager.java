package com.example.netcrafttoolkit;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.AABB;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.event.entity.living.LivingDropsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.util.ObfuscationReflectionHelper;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

/**
 * NetCraft 生物掉落管理器。
 *
 * 当前职责：
 * 1. 保存每个 NetCraft 生物的自定义掉落配置
 * 2. 处理 LivingDeathEvent / LivingDropsEvent / EntityJoinLevelEvent
 * 3. 拦截 NetCraft Boss 自己直接生成的死亡掉落
 * 4. 根据概率生成掉落物
 * 5. 支持最小/最大数量
 * 6. 支持是否替换原版/NetCraft原有掉落
 *
 * 注意：
 * 这个类不直接依赖 NetCraft 的 Java 类。
 * 后续由 NetCraftConfig 把配置数据交给这里。
 */
public class NetCraftDropManager {

    /**
     * entityId -> 掉落配置
     *
     * 例如：
     * netcraft:xxx -> DropConfig
     */
    private final Map<String, DropConfig> dropConfigs =
            new ConcurrentHashMap<>();

    /**
     * replace=true 时，Boss 死亡前就登记一个“死亡掉落拦截窗口”。
     *
     * 原因：NetCraft BossBase 的部分死亡掉落不是进入 LivingDropsEvent 的
     * capturedDrops，而是直接 ServerLevel.addFreshEntity(...)。
     * EntityJoinLevelEvent 才能在这些 ItemEntity 真正进入世界时拦截。
     */
    private final Map<UUID, SuppressionWindow> suppressionWindows =
            new ConcurrentHashMap<>();

    /**
     * Toolkit 自己生成的掉落物 UUID。
     *
     * 仅靠 PersistentData 标记在某些实体加入世界/模组处理链路中不够稳妥，
     * 因此再用 UUID 做一层绝对放行。
     */
    private final Set<UUID> customDropEntities =
            ConcurrentHashMap.newKeySet();

    /**
     * 自定义掉落的标记。
     *
     * replace=true 时我们需要直接把自定义物品加入世界，
     * 但它们也会触发 EntityJoinLevelEvent，所以必须标记后放行。
     */
    private static final String CUSTOM_DROP_TAG =
            "NetCraftToolkitCustomDrop";

    /**
     * NetCraft Boss 原始直接掉落的短暂拦截窗口。
     */
    private record SuppressionWindow(
            ServerLevel level,
            double x,
            double y,
            double z,
            long expireTick
    ) {
    }

    /**
     * 注册/更新一个生物的掉落配置。
     */
    public void setDropConfig(
            String entityId,
            boolean replaceDrops,
            List<DropEntry> entries
    ) {
        if (entityId == null || entityId.isBlank()) {
            return;
        }

        if (entries == null || entries.isEmpty()) {
            dropConfigs.remove(entityId);
            return;
        }

        List<DropEntry> copy = new ArrayList<>(entries);

        dropConfigs.put(
                entityId,
                new DropConfig(
                        replaceDrops,
                        Collections.unmodifiableList(copy)
                )
        );
    }

    /**
     * 删除一个生物的掉落配置。
     */
    public void removeDropConfig(String entityId) {
        if (entityId == null) {
            return;
        }

        dropConfigs.remove(entityId);
    }

    /**
     * 清空所有掉落配置。
     */
    public void clear() {
        dropConfigs.clear();
        suppressionWindows.clear();
    }

    /**
     * 获取一个生物的掉落配置。
     */
    public DropConfig getDropConfig(String entityId) {
        return dropConfigs.get(entityId);
    }

    /**
     * 判断是否存在掉落配置。
     */
    public boolean hasDropConfig(String entityId) {
        return dropConfigs.containsKey(entityId);
    }

    /**
     * 获取当前配置数量。
     */
    public int size() {
        return dropConfigs.size();
    }

    /**
     * 在 LivingDropsEvent 之前登记 Boss 的死亡掉落拦截。
     *
     * LivingDeathEvent 更早触发。这样即使 NetCraft Boss 在自己的死亡流程里
     * 直接调用 ServerLevel.addFreshEntity(ItemEntity)，我们也能在
     * EntityJoinLevelEvent 中把这批原始 ItemEntity 拦下来。
     */
    @SubscribeEvent
    public void onLivingDeath(LivingDeathEvent event) {
        if (event == null) {
            return;
        }

        LivingEntity entity = event.getEntity();

        if (entity == null || entity.level().isClientSide()) {
            return;
        }

        String entityId = getEntityId(entity);

        if (entityId == null || !entityId.startsWith("netcraft:")) {
            return;
        }

        DropConfig config = dropConfigs.get(entityId);

        if (config == null
                || !config.replaceDrops()
                || config.entries().isEmpty()) {
            return;
        }

        if (!(entity.level() instanceof ServerLevel level)) {
            return;
        }

        long expireTick = level.getGameTime() + 10L;

        suppressionWindows.put(
                entity.getUUID(),
                new SuppressionWindow(
                        level,
                        entity.getX(),
                        entity.getY(),
                        entity.getZ(),
                        expireTick
                )
        );

        NetCraftToolkit.LOGGER.info(
                "[NetCraftToolkit] Registered direct-drop suppression: entity={}, expireTick={}",
                entityId,
                expireTick
        );
    }

    /**
     * 拦截 NetCraft Boss 自己直接加入世界的原始 ItemEntity。
     *
     * 这里只处理 replace=true 的 Boss 死亡窗口，
     * 不会全局清理 ItemEntity。
     * 玩家丢出的物品通常带有 thrower UUID，因此明确放行。
     */
    /**
     * Forge 1.20.1 的 ItemEntity 有 thrower 字段，但没有公开 getThrower()。
     *
     * 使用 Forge 的 ObfuscationReflectionHelper 读取 SRG 字段名，
     * 避免直接调用不存在的 getThrower() 导致编译失败。
     */
    private boolean hasThrower(ItemEntity itemEntity) {
        try {
            UUID thrower = ObfuscationReflectionHelper.getPrivateValue(
                    ItemEntity.class,
                    itemEntity,
                    "f_31988_"
            );
            return thrower != null;
        } catch (Throwable ignored) {
            /*
             * 读取失败时不因为保护逻辑本身导致服务器崩溃。
             */
            return false;
        }
    }

    @SubscribeEvent
    public void onEntityJoinLevel(EntityJoinLevelEvent event) {
        if (event == null || event.getLevel().isClientSide()) {
            return;
        }

        Entity entity = event.getEntity();

        if (!(entity instanceof ItemEntity itemEntity)) {
            return;
        }

        /*
         * 自定义掉落由本管理器自己生成，必须放行。
         */
        if (itemEntity.getPersistentData().getBoolean(CUSTOM_DROP_TAG)
                || customDropEntities.contains(itemEntity.getUUID())) {
            return;
        }

        /*
         * 玩家丢出的物品不要拦。
         */
        if (hasThrower(itemEntity)) {
            return;
        }

        long now = event.getLevel().getGameTime();

        for (Map.Entry<UUID, SuppressionWindow> entry
                : suppressionWindows.entrySet()) {

            SuppressionWindow window = entry.getValue();

            if (window == null || now > window.expireTick()) {
                suppressionWindows.remove(entry.getKey(), window);
                continue;
            }

            if (window.level() != event.getLevel()) {
                continue;
            }

            /*
             * NetCraft Boss 的直接掉落是在 Boss 所在位置生成的。
             * 控制在 4 格半径内，避免影响远处正常物品。
             */
            double dx = itemEntity.getX() - window.x();
            double dy = itemEntity.getY() - window.y();
            double dz = itemEntity.getZ() - window.z();

            if (dx * dx + dy * dy + dz * dz > 16.0D) {
                continue;
            }

            event.setCanceled(true);

            NetCraftToolkit.LOGGER.info(
                    "[NetCraftToolkit] Blocked NetCraft direct death drop: item={}, pos=({}, {}, {})",
                    itemEntity.getItem().getItem(),
                    itemEntity.getX(),
                    itemEntity.getY(),
                    itemEntity.getZ()
            );

            return;
        }
    }

    /**
     * 兜底拦截 Boss 原始掉落。
     *
     * EntityJoinLevelEvent 在部分模组/特殊生成路径下可能无法可靠覆盖
     * 所有直接 addFreshEntity 的物品，因此这里再用服务器 Tick 做一次短窗口扫描。
     *
     * 只扫描刚刚死亡的 NetCraft 实体附近，不会全局删除物品。
     */
    @SubscribeEvent
    public void onServerTick(TickEvent.ServerTickEvent event) {
        if (event == null || event.phase != TickEvent.Phase.END) {
            return;
        }

        if (suppressionWindows.isEmpty()) {
            return;
        }

        for (Map.Entry<UUID, SuppressionWindow> entry : suppressionWindows.entrySet()) {
            SuppressionWindow window = entry.getValue();

            if (window == null) {
                suppressionWindows.remove(entry.getKey(), null);
                continue;
            }

            ServerLevel level = window.level();
            long now = level.getGameTime();

            if (now > window.expireTick()) {
                suppressionWindows.remove(entry.getKey(), window);
                continue;
            }

            AABB box = new AABB(
                    window.x() - 4.0D,
                    window.y() - 4.0D,
                    window.z() - 4.0D,
                    window.x() + 4.0D,
                    window.y() + 4.0D,
                    window.z() + 4.0D
            );

            List<ItemEntity> nearby = level.getEntitiesOfClass(
                    ItemEntity.class,
                    box,
                    itemEntity -> !itemEntity.getPersistentData().getBoolean(CUSTOM_DROP_TAG)
                            && !customDropEntities.contains(itemEntity.getUUID())
                            && !hasThrower(itemEntity)
            );

            for (ItemEntity itemEntity : nearby) {
                NetCraftToolkit.LOGGER.info(
                        "[NetCraftToolkit] Removed Boss original drop by tick sweep: item={}, pos=({}, {}, {})",
                        itemEntity.getItem().getItem(),
                        itemEntity.getX(),
                        itemEntity.getY(),
                        itemEntity.getZ()
                );
                itemEntity.discard();
            }
        }
    }

    /**
     * 处理生物死亡掉落。
     */
    @SubscribeEvent
    public void onLivingDrops(LivingDropsEvent event) {

        LivingEntity entity = event.getEntity();

        if (entity == null) {
            return;
        }

        /*
         * 只处理服务端。
         *
         * 掉落物本身属于服务器世界，
         * 客户端不应该重复生成。
         */
        if (entity.level().isClientSide()) {
            return;
        }

        String entityId = getEntityId(entity);

        if (entityId == null) {
            return;
        }

        /*
         * 我们只处理 NetCraft 命名空间。
         */
        if (!entityId.startsWith("netcraft:")) {
            return;
        }

        DropConfig config = dropConfigs.get(entityId);

        if (config == null || config.entries().isEmpty()) {
            return;
        }

        /*
         * 如果配置为替换原有掉落，
         * 先清掉事件当前的掉落。
         */
        if (config.replaceDrops()) {
            int before = event.getDrops().size();
            event.getDrops().clear();

            /*
             * 取消 Forge 最终把 capturedDrops 加入世界的步骤。
             * NetCraft 自己直接 addFreshEntity 的掉落则由
             * onEntityJoinLevel() 拦截。
             */
            event.setCanceled(true);

            NetCraftToolkit.LOGGER.info(
                    "[NetCraftToolkit] Original drops canceled: entity={}, existingDrops={}",
                    entityId,
                    before
            );
        }

        /*
         * 生成自定义掉落。
         */
        for (DropEntry entry : config.entries()) {

            if (entry == null) {
                continue;
            }

            if (entry.itemId() == null || entry.itemId().isBlank()) {
                continue;
            }

            /*
             * 概率范围：
             *
             * 1.0   = 100%
             * 0.5   = 50%
             * 0.1   = 10%
             */
            double chance = entry.chance();

            if (chance <= 0.0D) {
                continue;
            }

            if (chance < 1.0D) {
                if (ThreadLocalRandom.current().nextDouble() > chance) {
                    continue;
                }
            }

            /*
             * 最小数量不能小于 1。
             */
            int min = Math.max(1, entry.minCount());

            /*
             * 最大数量不能小于最小数量。
             */
            int max = Math.max(min, entry.maxCount());

            int amount;

            if (min == max) {
                amount = min;
            } else {
                amount = ThreadLocalRandom.current()
                        .nextInt(min, max + 1);
            }

            if (amount <= 0) {
                continue;
            }

            Item item = findItem(entry.itemId());

            if (item == null) {
                NetCraftToolkit.LOGGER.warn(
                        "[NetCraftToolkit] Unknown drop item: {}",
                        entry.itemId()
                );
                continue;
            }

            ItemStack stack = new ItemStack(item, amount);

            spawnDrop(entity, stack);
        }
    }

    /**
     * 根据物品 ID 获取 Item。
     */
    private Item findItem(String itemId) {

        try {
            ResourceLocation id = ResourceLocation.tryParse(itemId);

            if (id == null) {
                return null;
            }

            /*
             * 优先使用 Forge 注册表。
             */
            Item item = ForgeRegistries.ITEMS.getValue(id);

            if (item != null) {
                return item;
            }

            /*
             * 再使用 Minecraft 原生注册表。
             */
            return BuiltInRegistries.ITEM.get(id);

        } catch (Exception e) {

            NetCraftToolkit.LOGGER.warn(
                    "[NetCraftToolkit] Failed to find item: {}",
                    itemId,
                    e
            );

            return null;
        }
    }

    /**
     * 生成掉落物。
     */
    private void spawnDrop(
            LivingEntity entity,
            ItemStack stack
    ) {

        if (stack.isEmpty()) {
            return;
        }

        if (!(entity.level() instanceof ServerLevel level)) {
            return;
        }

        ItemEntity itemEntity = new ItemEntity(
                level,
                entity.getX(),
                entity.getY(),
                entity.getZ(),
                stack
        );

        /*
         * 给掉落物一点轻微随机运动，
         * 和 Minecraft 正常掉落物表现接近。
         */
        itemEntity.setDeltaMovement(
                (ThreadLocalRandom.current().nextDouble() - 0.5D) * 0.1D,
                ThreadLocalRandom.current().nextDouble() * 0.1D,
                (ThreadLocalRandom.current().nextDouble() - 0.5D) * 0.1D
        );

        /*
         * 这个 ItemEntity 是 Toolkit 自己生成的，
         * 必须跳过死亡原始掉落拦截器。
         */
        itemEntity.getPersistentData().putBoolean(
                CUSTOM_DROP_TAG,
                true
        );

        /*
         * UUID 白名单比 PersistentData 更直接：
         * 即使 NetCraft/Forge 后续处理了实体数据，自定义掉落也不会被 Tick 兜底误删。
         */
        UUID customDropUuid = itemEntity.getUUID();
        customDropEntities.add(customDropUuid);

        boolean added = level.addFreshEntity(itemEntity);

        if (!added) {
            customDropEntities.remove(customDropUuid);
        }

        if (!added) {
            NetCraftToolkit.LOGGER.warn(
                    "[NetCraftToolkit] Failed to spawn custom drop: item={}, amount={}",
                    stack.getItem(),
                    stack.getCount()
            );
        } else {
            NetCraftToolkit.LOGGER.info(
                    "[NetCraftToolkit] Custom drop spawned directly: item={}, amount={}",
                    stack.getItem(),
                    stack.getCount()
            );
        }
    }

    /**
     * 获取实体的注册 ID。
     */
    private String getEntityId(Entity entity) {

        try {
            ResourceLocation id =
                    ForgeRegistries.ENTITY_TYPES.getKey(entity.getType());

            if (id == null) {
                return null;
            }

            return id.toString();

        } catch (Exception e) {

            NetCraftToolkit.LOGGER.warn(
                    "[NetCraftToolkit] Failed to get entity id.",
                    e
            );

            return null;
        }
    }

    /**
     * 单个生物的掉落配置。
     */
    public record DropConfig(
            boolean replaceDrops,
            List<DropEntry> entries
    ) {
    }

    /**
     * 单个掉落物配置。
     *
     * itemId：
     * 例如 minecraft:diamond
     *
     * minCount：
     * 最少数量
     *
     * maxCount：
     * 最大数量
     *
     * chance：
     * 掉落概率，1.0 = 100%
     */
    public record DropEntry(
            String itemId,
            int minCount,
            int maxCount,
            double chance
    ) {
    }

    /**
     * NetCraft Boss 的一部分原版掉落不是通过 LivingDropsEvent 生成，
     * 而是在 BossBase.spawnScatteredStacks() 中直接创建 ItemEntity，
     * 随后调用 level.addFreshEntity()。
     *
     * EntityJoinLevelEvent 是该 ItemEntity 真正进入世界前的事件。
     * 因此这里直接取消事件，而不是等物品进入世界后再删除。
     *
     * 只有在之前登记过 Boss 死亡拦截窗口，并且 ItemEntity 位于对应 Boss
     * 死亡位置附近时才处理；Toolkit 自己生成的掉落通过 CUSTOM_DROP_TAG 放行。
     */
    @SubscribeEvent
    public void onEntityJoinLevel(EntityJoinLevelEvent event) {
        if (!(event.getEntity() instanceof ItemEntity itemEntity)) {
            return;
        }

        if (!(event.getLevel() instanceof ServerLevel serverLevel)) {
            return;
        }

        if (itemEntity.getPersistentData().getBoolean(CUSTOM_DROP_TAG)) {
            return;
        }

        cleanupExpiredSuppressions();

        for (BossDropSuppression suppression : activeSuppressions.values()) {
            if (!suppression.level().dimension().equals(serverLevel.dimension())) {
                continue;
            }

            if (suppression.expiresAt() < System.currentTimeMillis()) {
                continue;
            }

            double dx = itemEntity.getX() - suppression.x();
            double dy = itemEntity.getY() - suppression.y();
            double dz = itemEntity.getZ() - suppression.z();

            if ((dx * dx + dy * dy + dz * dz) <= 16.0D) {
                event.setCanceled(true);
                LOGGER.debug(
                        "Blocked NetCraft direct Boss drop before world insertion: item={}",
                        BuiltInRegistries.ITEM.getKey(itemEntity.getItem().getItem())
                );
                return;
            }
        }
    }

}
