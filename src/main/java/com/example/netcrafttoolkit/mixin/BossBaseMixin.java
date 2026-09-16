package com.example.netcrafttoolkit.mixin;

import com.example.netcrafttoolkit.NetCraftToolkit;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.Item;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 精准拦截 NetCraft BossBase 的直接散落掉落。
 *
 * 不创建 ItemEntity，也不删除已经存在的 ItemEntity。
 * replace=true 时直接在 spawnScatteredStacks() 入口返回。
 */
@Pseudo
@Mixin(targets = "com.jiufeng.netcraft.entity.BossBase")
public abstract class BossBaseMixin {

    @Inject(
            method = "spawnScatteredStacks",
            at = @At("HEAD"),
            cancellable = true,
            remap = false
    )
    private void netcrafttoolkit$blockScatteredDrops(
            ServerLevel level,
            Item item,
            int min,
            int max,
            RandomSource random,
            CallbackInfo ci
    ) {
        Entity entity = (Entity) (Object) this;

        if (NetCraftToolkit.getDropManager() != null
                && NetCraftToolkit.getDropManager().shouldBlockScatteredDrops(entity)) {

            NetCraftToolkit.LOGGER.info(
                    "[NetCraftToolkit] Mixin blocked NetCraft Boss direct drop: entity={}, item={}",
                    entity.getType().builtInRegistryHolder().key().location(),
                    net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(item)
            );

            ci.cancel();
        }
    }
}
