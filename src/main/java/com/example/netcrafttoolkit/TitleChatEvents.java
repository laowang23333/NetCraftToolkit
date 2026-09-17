package com.example.netcrafttoolkit;

import net.minecraftforge.event.server.ServerStartingEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

import java.lang.reflect.Array;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.UUID;

/**

* NetCraftToolkit 聊天称号适配。

* 

* Mohist 下玩家聊天实际会经过 Bukkit 的 AsyncPlayerChatEvent。

* 因此这里不再依赖 Forge ServerChatEvent，而是在服务器启动后

* 通过反射向 Bukkit PluginManager 注册 AsyncPlayerChatEvent。

* 

* 不直接依赖 Bukkit API，这样 Forge 开发环境仍然可以正常编译。
  */
  public class TitleChatEvents {
  
  private final TitleManager titleManager;
  
  /** 防止服务器启动事件重复注册。 */
  private volatile boolean bukkitChatHooked = false;
  
  /** 保存反射代理，避免代理对象没有强引用时被回收。 */
  private Object bukkitListenerProxy;
  private Object bukkitExecutorProxy;
  
  public TitleChatEvents(TitleManager titleManager) {
  this.titleManager = titleManager;
  }
  
  /**
  
  * Mohist 服务器启动后，尝试挂接 Bukkit AsyncPlayerChatEvent。
    */
    @SubscribeEvent
    public void onServerStarting(ServerStartingEvent event) {
    tryHookBukkitChat();
    }
  
  /**
  
  * 某些 Mohist 版本在 ServerStartingEvent 时 Bukkit 插件还没有全部就绪。
  * ServerStartedEvent 再尝试一次，成功后由 bukkitChatHooked 防止重复注册。
    */
    @SubscribeEvent
    public void onServerStarted(ServerStartedEvent event) {
    tryHookBukkitChat();
    }
  
  /**
  
  * 通过反射注册 Bukkit AsyncPlayerChatEvent。
  
  * 
  
  * 等价于 Bukkit 插件里的：
  
  * 
  
  * @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
  
  * public void onChat(AsyncPlayerChatEvent event) { ... }
    */
    private synchronized void tryHookBukkitChat() {
    if (bukkitChatHooked) {
    return;
    }
    
    try {
    Class<?> bukkitClass =
    Class.forName("org.bukkit.Bukkit");
    
     Class<?> eventClass =
         Class.forName(
                 "org.bukkit.event.player.AsyncPlayerChatEvent"
         );

 Class<?> eventBaseClass =
         Class.forName("org.bukkit.event.Event");

 Class<?> listenerClass =
         Class.forName("org.bukkit.event.Listener");

 Class<?> eventPriorityClass =
         Class.forName("org.bukkit.event.EventPriority");

 Class<?> eventExecutorClass =
         Class.forName("org.bukkit.plugin.EventExecutor");

 Class<?> pluginManagerClass =
         Class.forName("org.bukkit.plugin.PluginManager");

 Class<?> pluginClass =
         Class.forName("org.bukkit.plugin.Plugin");

 Object pluginManager =
         bukkitClass
                 .getMethod("getPluginManager")
                 .invoke(null);

 Object plugin =
         findEnabledPlugin(
                 pluginManager,
                 pluginManagerClass,
                 pluginClass
         );

 if (plugin == null) {
     NetCraftToolkit.LOGGER.warn(
             "[NetCraftToolkit] Bukkit chat hook skipped: no enabled Bukkit plugin found."
     );
     return;
 }

 /*
  * EventPriority.HIGHEST
  */
 Object highest =
         Enum.valueOf(
                 (Class<? extends Enum>)
                         eventPriorityClass.asSubclass(Enum.class),
                 "HIGHEST"
         );

 /*
  * Listener 只是 Bukkit 的标记接口。
  */
 InvocationHandler listenerHandler =
         (proxy, method, args) ->
                 defaultProxyReturn(method);

 /*
  * 真正执行聊天处理的 EventExecutor。
  */
 InvocationHandler executorHandler =
         (proxy, method, args) -> {

             if ("execute".equals(method.getName())
                     && args != null
                     && args.length >= 2
                     && eventBaseClass.isInstance(args[1])) {

                 handleBukkitChat(args[1]);
             }

             return defaultProxyReturn(method);
         };

 ClassLoader bukkitClassLoader =
         listenerClass.getClassLoader();

 if (bukkitClassLoader == null) {
     bukkitClassLoader =
             TitleChatEvents.class.getClassLoader();
 }

 /*
  * 创建 Bukkit Listener。
  */
 bukkitListenerProxy =
         Proxy.newProxyInstance(
                 bukkitClassLoader,
                 new Class<?>[]{
                         listenerClass
                 },
                 listenerHandler
         );

 /*
  * 创建 Bukkit EventExecutor。
  */
 bukkitExecutorProxy =
         Proxy.newProxyInstance(
                 bukkitClassLoader,
                 new Class<?>[]{
                         eventExecutorClass
                 },
                 executorHandler
         );

 /*
  * Bukkit：
  *
  * registerEvent(
  *     Event.class,
  *     Listener,
  *     EventPriority,
  *     EventExecutor,
  *     Plugin,
  *     ignoreCancelled
  * )
  *
  * ignoreCancelled=false
  * 保证即使前面的聊天插件取消了事件，
  * 我们仍然可以观察到它。
  */
 Method registerEvent =
         pluginManagerClass.getMethod(
                 "registerEvent",
                 Class.class,
                 listenerClass,
                 eventPriorityClass,
                 eventExecutorClass,
                 pluginClass,
                 boolean.class
         );

 registerEvent.invoke(
         pluginManager,
         eventClass,
         bukkitListenerProxy,
         highest,
         bukkitExecutorProxy,
         plugin,
         false
 );

 bukkitChatHooked = true;

 String pluginName =
         String.valueOf(
                 plugin.getClass()
                         .getMethod("getName")
                         .invoke(plugin)
         );

 NetCraftToolkit.LOGGER.info(
         "[NetCraftToolkit] Bukkit AsyncPlayerChatEvent hook registered at HIGHEST (owner: {}).",
         pluginName
 );
    
    } catch (Throwable throwable) {
    
     NetCraftToolkit.LOGGER.error(
         "[NetCraftToolkit] Failed to hook Bukkit AsyncPlayerChatEvent.",
         throwable
 );
    
    }
    }
  
  /**
  
  * 找一个当前已经启用的 Bukkit 插件，
  
  * 作为 Bukkit EventExecutor 的注册所有者。
  
  * 
  
  * NetCraftToolkit 本身是 Forge Mod，
  
  * 并不是 Bukkit Plugin，
  
  * 所以不能直接把 Forge Mod 实例传给 Bukkit PluginManager。
    */
    private Object findEnabledPlugin(
    Object pluginManager,
    Class<?> pluginManagerClass,
Class<?> pluginClass
    ) throws Exception {
    
    Method getPlugins =
    pluginManagerClass.getMethod("getPlugins");
    
    Object plugins =
    getPlugins.invoke(pluginManager);
    
    if (plugins == null
    || !plugins.getClass().isArray()) {
    return null;
    }
    
    int length =
    Array.getLength(plugins);
    
    for (int i = 0; i < length; i++) {
    
     Object plugin =
         Array.get(plugins, i);

 if (plugin == null
         || !pluginClass.isInstance(plugin)) {
     continue;
 }

 try {

     Object enabled =
             pluginClass
                     .getMethod("isEnabled")
                     .invoke(plugin);

     if (Boolean.TRUE.equals(enabled)) {
         return plugin;
     }

 } catch (Throwable ignored) {
     // 当前插件读取失败，继续寻找下一个插件。
 }
    
    }
    
    return null;
    }
  
  /**
  
  * Bukkit AsyncPlayerChatEvent 的实际处理。
  
  * 
  
  * 最终格式：
  
  * 
  
  * <称号 玩家名> 消息
  
  * 
  
  * %1$s = 玩家名
  
  * %2$s = 玩家消息
    */
    private void handleBukkitChat(Object event) {
    
    try {
    
     Method getPlayer =
         event.getClass()
                 .getMethod("getPlayer");

 Object bukkitPlayer =
         getPlayer.invoke(event);

 if (bukkitPlayer == null) {
     return;
 }

 UUID uuid =
         (UUID)
                 bukkitPlayer.getClass()
                         .getMethod("getUniqueId")
                         .invoke(bukkitPlayer);

 if (uuid == null
         || titleManager == null) {
     return;
 }

 /*
  * 获取当前主称号 ID。
  */
 String mainTitle =
         titleManager.getMainTitle(uuid);

 /*
  * 获取当前副称号 ID。
  */
 String subTitle =
         titleManager.getSubTitle(uuid);

 StringBuilder title =
         new StringBuilder();

 /*
  * 主称号。
  */
 appendTitle(
         title,
         mainTitle
 );

 /*
  * 副称号。
  */
 appendTitle(
         title,
         subTitle
 );

 /*
  * 没有称号：
  * 不修改 Bukkit 原本聊天。
  */
 if (title.length() == 0) {
     return;
 }

 /*
  * Bukkit 聊天 format 使用 String.format。
  *
  * 所以称号文本中的 % 必须转成 %%。
  */
 String titleText =
         escapeFormatPercent(
                 title.toString()
         );

 /*
  * 支持：
  *
  * &a
  * &b
  * &c
  * ...
  *
  * 以及原本已经存在的 §。
  */
 titleText =
         titleText.replace(
                 '&',
                 '§'
         );

 /*
  * 最终：
  *
  * <称号 玩家名> 消息
  */
 String format =
         "<"
                 + titleText
                 + " %1$s> %2$s";

 Method setFormat =
         event.getClass()
                 .getMethod(
                         "setFormat",
                         String.class
                 );

 setFormat.invoke(
         event,
         format
 );

 NetCraftToolkit.LOGGER.debug(
         "[NetCraftToolkit] Applied chat title for {}: {}",
         uuid,
         title.toString()
 );
    
    } catch (Throwable throwable) {
    
     NetCraftToolkit.LOGGER.error(
         "[NetCraftToolkit] Failed to apply Bukkit chat title.",
         throwable
 );
    
    }
    }
  
  /**
  
  * 根据称号 ID 获取真正显示文本。
    */
    private void appendTitle(
    StringBuilder result,
    String titleId
    ) {
    
    if (titleId == null
    || titleId.isBlank()) {
    return;
    }
    
    String text =
    titleManager.getTitleText(
    titleId
    );
    
    if (text == null
    || text.isBlank()) {
    return;
    }
    
    if (result.length() > 0) {
    result.append(' ');
    }
    
    result.append(text);
    }
  
  /**
  
  * 防止称号里的 % 破坏 Bukkit String.format。
    */
    private String escapeFormatPercent(
    String text
    ) {
    return text.replace(
    "%",
    "%%"
    );
    }
  
  /**
  
  * 动态代理默认返回值。
    */
    private Object defaultProxyReturn(
    Method method
    ) {
    
    Class<?> returnType =
    method.getReturnType();
    
    if (!returnType.isPrimitive()) {
    return null;
    }
    
    if (returnType == boolean.class) {
    return false;
    }
    
    if (returnType == char.class) {
    return '\0';
    }
    
    if (returnType == byte.class) {
    return (byte) 0;
    }
    
    if (returnType == short.class) {
    return (short) 0;
    }
    
    if (returnType == int.class) {
    return 0;
    }
    
    if (returnType == long.class) {
    return 0L;
    }
    
    if (returnType == float.class) {
    return 0F;
    }
    
    if (returnType == double.class) {
    return 0D;
    }
    
    return null;
    }
    }
