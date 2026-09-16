package com.example.netcrafttoolkit;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.event.entity.living.LivingDropsEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

/**
 * NetCraft 生物掉落管理器。
 *
 * 当前职责：
 * 1. 保存每个 NetCraft 生物的自定义掉落配置
 * 2. 处理 LivingDropsEvent
 * 3. 根据概率生成掉落物
 * 4. 支持最小/最大数量
 * 5. 支持是否替换原版/NetCraft原有掉落
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
     * 兼容旧调用方式。
     *
     * 如果其他旧代码仍然直接传入 DropConfig，
     * 这里会自动拆开为当前 setDropConfig 的三个参数。
     */
    public void setDropConfig(
            String entityId,
            DropConfig config
    ) {
        if (config == null) {
            removeDropConfig(entityId);
            return;
        }

        setDropConfig(
                entityId,
                config.replaceDrops(),
                config.entries()
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
     * 处理生物死亡掉落。
     */
    @SubscribeEvent(priority = EventPriority.LOWEST)
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

        NetCraftToolkit.LOGGER.info(
                "[NetCraftToolkit] Drop event: entity={}, configured={}, existingDrops={}",
                entityId,
                config != null,
                event.getDrops().size()
        );

        if (config == null || config.entries().isEmpty()) {
            if (config == null) {
                NetCraftToolkit.LOGGER.info(
                        "[NetCraftToolkit] No custom drop config for {}.",
                        entityId
                );
            }
            return;
        }

        NetCraftToolkit.LOGGER.info(
                "[NetCraftToolkit] Custom drops matched: entity={}, replace={}, entries={}",
                entityId,
                config.replaceDrops(),
                config.entries().size()
        );

        /*
         * 先验证配置里至少有一个可以实际生成的物品。
         *
         * 这样 replace = true 时，如果物品 ID 写错，
         * 不会先把原版掉落清空，最后变成“什么都不掉”。
         */
        boolean hasValidItem = false;

        for (DropEntry entry : config.entries()) {
            if (entry == null
                    || entry.itemId() == null
                    || entry.itemId().isBlank()
                    || entry.chance() <= 0.0D) {
                continue;
            }

            if (findItem(entry.itemId()) != null) {
                hasValidItem = true;
                break;
            }
        }

        if (!hasValidItem) {
            NetCraftToolkit.LOGGER.warn(
                    "[NetCraftToolkit] No valid custom drop item for {}. Original drops were kept.",
                    entityId
            );
            return;
        }

        /*
         * 如果配置为替换原有掉落，
         * 只有确认至少有一个有效自定义物品后才清空原始掉落。
         */
        if (config.replaceDrops()) {
            int originalCount = event.getDrops().size();
            event.getDrops().clear();
            NetCraftToolkit.LOGGER.info(
                    "[NetCraftToolkit] Original drops cleared for {}: {} -> 0",
                    entityId,
                    originalCount
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
                NetCraftToolkit.LOGGER.info(
                        "[NetCraftToolkit] Drop skipped: entity={}, item={}, chance={}",
                        entityId, entry.itemId(), chance
                );
                continue;
            }

            if (chance < 1.0D) {
                if (ThreadLocalRandom.current().nextDouble() >= chance) {
                    NetCraftToolkit.LOGGER.info(
                            "[NetCraftToolkit] Drop chance failed: entity={}, item={}, chance={}",
                            entityId, entry.itemId(), chance
                    );
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
                        "[NetCraftToolkit] Unknown drop item for {}: {}",
                        entityId,
                        entry.itemId()
                );
                continue;
            }

            ItemStack stack = new ItemStack(item, amount);

            spawnDrop(entity, stack, event);

            NetCraftToolkit.LOGGER.info(
                    "[NetCraftToolkit] Custom drop added: entity={}, item={}, amount={}, eventDropsNow={}",
                    entityId,
                    entry.itemId(),
                    amount,
                    event.getDrops().size()
            );
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
            ItemStack stack,
            LivingDropsEvent event
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

        event.getDrops().add(itemEntity);
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
}
