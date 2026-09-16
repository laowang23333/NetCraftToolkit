package com.example.netcrafttoolkit;

import com.mojang.logging.LogUtils;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.server.ServerStartingEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.slf4j.Logger;

@Mod(NetCraftToolkit.MOD_ID)
public class NetCraftToolkit {

    public static final String MOD_ID = "netcrafttoolkit";

    /**
     * 全局日志。
     *
     * 设为 public，方便其他管理器输出日志。
     */
    public static final Logger LOGGER = LogUtils.getLogger();

    private static NetCraftConfig config;
    private static NetCraftAttributeManager attributeManager;
    private static NetCraftDropManager dropManager;

    public NetCraftToolkit() {

        LOGGER.info("========================================");
        LOGGER.info("[NetCraftToolkit] Loading...");
        LOGGER.info("========================================");

        /*
         * 创建配置管理器。
         */
        config = new NetCraftConfig();

        /*
         * 创建生物属性管理器。
         */
        attributeManager = new NetCraftAttributeManager();

        /*
         * 创建掉落管理器。
         */
        dropManager = new NetCraftDropManager();

        /*
         * 注册 Forge 事件。
         *
         * Config：
         * 负责服务器启动以及配置加载。
         *
         * AttributeManager：
         * 负责生物属性。
         *
         * DropManager：
         * 负责自定义掉落。
         */
        MinecraftForge.EVENT_BUS.register(config);
        MinecraftForge.EVENT_BUS.register(attributeManager);
        MinecraftForge.EVENT_BUS.register(dropManager);

        /*
         * 注册本类自己的服务器事件。
         */
        MinecraftForge.EVENT_BUS.register(this);

        LOGGER.info("[NetCraftToolkit] Event managers registered.");
        LOGGER.info("[NetCraftToolkit] Loaded.");
    }

    /**
     * 服务器启动。
     */
    @SubscribeEvent
    public void onServerStarting(ServerStartingEvent event) {

        LOGGER.info("[NetCraftToolkit] Server starting...");

        if (config == null) {
            LOGGER.error("[NetCraftToolkit] Config manager is null.");
            return;
        }

        /*
         * 初始化配置管理器。
         */
        config.init(event.getServer());

        /*
         * 加载配置。
         */
        config.load();

        LOGGER.info("[NetCraftToolkit] Configuration loaded.");

        /*
         * 重新应用已经加载的属性配置。
         *
         * 正常服务器启动时实体可能还没有大量生成，
         * 这里调用一次可以保证已经存在的实体也能被处理。
         */
        if (attributeManager != null) {
            attributeManager.reloadAllEntities(event.getServer());
        }

        LOGGER.info("[NetCraftToolkit] Server initialization complete.");
    }

    /**
     * 获取配置管理器。
     */
    public static NetCraftConfig getConfig() {
        return config;
    }

    /**
     * 获取属性管理器。
     */
    public static NetCraftAttributeManager getAttributeManager() {
        return attributeManager;
    }

    /**
     * 获取掉落管理器。
     */
    public static NetCraftDropManager getDropManager() {
        return dropManager;
    }
}
