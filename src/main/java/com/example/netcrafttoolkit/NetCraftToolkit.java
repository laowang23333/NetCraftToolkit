package com.example.netcrafttoolkit;

import com.mojang.logging.LogUtils;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.server.ServerStartingEvent;
import net.minecraftforge.fml.common.Mod;
import org.slf4j.Logger;

@Mod(NetCraftToolkit.MOD_ID)
public class NetCraftToolkit {

    public static final String MOD_ID = "netcrafttoolkit";

    private static final Logger LOGGER = LogUtils.getLogger();

    private static NetCraftConfig config;

    public NetCraftToolkit() {
        LOGGER.info("[NetCraftToolkit] Loading...");

        config = new NetCraftConfig();

        MinecraftForge.EVENT_BUS.register(config);

        LOGGER.info("[NetCraftToolkit] Loaded.");
    }

    @net.minecraftforge.eventbus.api.SubscribeEvent
    public void onServerStarting(ServerStartingEvent event) {
        LOGGER.info("[NetCraftToolkit] Server starting.");

        config.init(event.getServer());
        config.load();

        LOGGER.info("[NetCraftToolkit] Configuration loaded.");
    }

    public static NetCraftConfig getConfig() {
        return config;
    }
}
