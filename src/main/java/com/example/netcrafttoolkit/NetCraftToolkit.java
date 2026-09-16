package com.example.netcrafttoolkit;

import com.mojang.logging.LogUtils;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.server.ServerStartingEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.slf4j.Logger;

@Mod(NetCraftToolkit.MOD_ID)
public class NetCraftToolkit {

    public static final String MOD_ID = "netcrafttoolkit";
    public static final Logger LOGGER = LogUtils.getLogger();

    private static NetCraftConfig config;
    private static NetCraftAttributeManager attributeManager;
    private static NetCraftDropManager dropManager;
    private static TitleManager titleManager;
    private static TitleCommands titleCommands;

    public NetCraftToolkit() {
        LOGGER.info("========================================");
        LOGGER.info("[NetCraftToolkit] Loading...");
        LOGGER.info("========================================");

        config = new NetCraftConfig();
        attributeManager = new NetCraftAttributeManager();
        dropManager = new NetCraftDropManager();
        titleManager = new TitleManager();
        titleCommands = new TitleCommands(titleManager);

        MinecraftForge.EVENT_BUS.register(config);
        MinecraftForge.EVENT_BUS.register(attributeManager);
        MinecraftForge.EVENT_BUS.register(dropManager);
        MinecraftForge.EVENT_BUS.register(titleManager);
        MinecraftForge.EVENT_BUS.register(titleCommands);
        MinecraftForge.EVENT_BUS.register(this);

        LOGGER.info("[NetCraftToolkit] Managers registered.");
        LOGGER.info("[NetCraftToolkit] Loaded.");
    }

    @SubscribeEvent
    public void onServerStarting(ServerStartingEvent event) {
        LOGGER.info("[NetCraftToolkit] Server starting...");

        config.init(event.getServer());
        config.load();
        config.startWatching();

        titleManager.init(event.getServer());

        if (attributeManager != null) {
            attributeManager.reloadAllEntities();
        }

        LOGGER.info("[NetCraftToolkit] Configuration and title system loaded.");
    }

    @SubscribeEvent
    public void onServerStopping(ServerStoppingEvent event) {
        LOGGER.info("[NetCraftToolkit] Server stopping...");

        if (titleManager != null) {
            titleManager.save();
        }
        if (config != null) config.stopWatching();
        if (attributeManager != null) attributeManager.clear();
        if (dropManager != null) dropManager.clear();
        if (titleManager != null) titleManager.clear();

        LOGGER.info("[NetCraftToolkit] Shutdown complete.");
    }

    public static NetCraftConfig getConfig() {
        return config;
    }

    public static NetCraftAttributeManager getAttributeManager() {
        return attributeManager;
    }

    public static NetCraftDropManager getDropManager() {
        return dropManager;
    }

    public static TitleManager getTitleManager() {
        return titleManager;
    }
}
