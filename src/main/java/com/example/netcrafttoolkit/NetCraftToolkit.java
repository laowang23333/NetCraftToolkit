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

    /**
     * 全局日志。
     */
    public static final Logger LOGGER = LogUtils.getLogger();

    /**
     * 配置管理器。
     */
    private static NetCraftConfig config;

    /**
     * 生物属性管理器。
     */
    private static NetCraftAttributeManager attributeManager;

    /**
     * 掉落管理器。
     */
    private static NetCraftDropManager dropManager;

    /**
     * 称号管理器。
     */
    private static TitleManager titleManager;

    public NetCraftToolkit() {

        LOGGER.info("========================================");
        LOGGER.info("[NetCraftToolkit] Loading...");
        LOGGER.info("========================================");

        /*
         * 创建三个核心管理器。
         */
        config = new NetCraftConfig();
        attributeManager = new NetCraftAttributeManager();
        dropManager = new NetCraftDropManager();
        titleManager = new TitleManager();

        /*
         * 注册炉石菜单自定义网络包。
         */
        ModNetwork.register();

        /*
         * 注册配置事件。
         */
        MinecraftForge.EVENT_BUS.register(config);

        /*
         * 注册属性事件。
         */
        MinecraftForge.EVENT_BUS.register(attributeManager);

        /*
         * 注册掉落事件。
         */
        MinecraftForge.EVENT_BUS.register(dropManager);

        /*
         * 注册称号事件。
         */
        MinecraftForge.EVENT_BUS.register(titleManager);

        /*
         * 注册本类事件。
         */
        MinecraftForge.EVENT_BUS.register(this);

        LOGGER.info(
                "[NetCraftToolkit] Managers registered."
        );

        LOGGER.info(
                "[NetCraftToolkit] Loaded."
        );
    }

    /**
     * 服务器启动。
     */
    @SubscribeEvent
    public void onServerStarting(
            ServerStartingEvent event
    ) {

        LOGGER.info(
                "[NetCraftToolkit] Server starting..."
        );

        if (config == null) {

            LOGGER.error(
                    "[NetCraftToolkit] Config manager is null."
            );

            return;
        }

        /*
         * 初始化配置文件路径。
         */
        config.init(
                event.getServer()
        );

        /*
         * 读取配置。
         *
         * 如果配置文件不存在，
         * 会自动生成 NetCraft 生物配置。
         */
        config.load();

        /*
         * 初始化称号管理器。
         */
        if (titleManager != null) {
            titleManager.init(event.getServer());
        }

        /*
         * 启动配置文件监听。
         *
         * 修改：
         *
         * config/netcrafttoolkit/netcraft-attributes.toml
         *
         * 后会自动重新加载。
         */
        config.startWatching();

        /*
         * 服务器已经启动以后，
         * 重新处理当前已经存在的 NetCraft 生物。
         */
        if (attributeManager != null) {

            attributeManager.reloadAllEntities();
        }

        LOGGER.info(
                "[NetCraftToolkit] Configuration loaded."
        );

        LOGGER.info(
                "[NetCraftToolkit] Hot reload watcher started."
        );

        LOGGER.info(
                "[NetCraftToolkit] Server initialization complete."
        );
    }

    /**
     * 服务器停止。
     */
    @SubscribeEvent
    public void onServerStopping(
            ServerStoppingEvent event
    ) {

        LOGGER.info(
                "[NetCraftToolkit] Server stopping..."
        );

        /*
         * 停止配置监听线程。
         */
        if (config != null) {

            config.stopWatching();
        }

        /*
         * 清理属性管理器缓存。
         */
        if (attributeManager != null) {

            attributeManager.clear();
        }

        /*
         * 清理掉落配置。
         */
        if (dropManager != null) {

            dropManager.clear();
        }

        /*
         * 保存称号数据。
         */
        if (titleManager != null) {
            titleManager.save();
        }

        LOGGER.info(
                "[NetCraftToolkit] Shutdown complete."
        );
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
    public static NetCraftAttributeManager
    getAttributeManager() {

        return attributeManager;
    }

    /**
     * 获取掉落管理器。
     */
    public static NetCraftDropManager
    getDropManager() {

        return dropManager;
    }

    /**
     * 获取称号管理器。
     */
    public static TitleManager getTitleManager() {

        return titleManager;
    }
}
