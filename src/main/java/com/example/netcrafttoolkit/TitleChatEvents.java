package com.example.netcrafttoolkit;

import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.event.server.ServerStartingEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

import java.lang.reflect.Array;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**

* NetCraftToolkit

* 

* Mohist 服务器聊天称号适配。

* 

* 聊天链路：

* 

* Mohist

* ↓

* Bukkit AsyncPlayerChatEvent

* ↓

* HIGHEST

* ↓

* NetCraftToolkit

* ↓

* TitleManager 获取称号

* ↓

* 转换颜色 / 渐变

* ↓

* AsyncPlayerChatEvent.setFormat()

* 

* 最终：

* 

* <称号 玩家名> 消息

* 

* 支持：

* 

* 1. 普通文字

* 2. §0-§f

* 3. &0-&f

* 4. &#RRGGBB

* 5. &x&R&R&G&G&B&B

* 6. §x§R§R§G§G§B§B

* 7. "gradient:#RRGGBB:#RRGGBB" (gradient:#RRGGBB:#RRGGBB)文字</gradient>
     */
     public class TitleChatEvents {
  
  private final TitleManager titleManager;
  
  /**
  
  * 防止服务器启动阶段重复注册。
    */
    private volatile boolean bukkitChatHooked = false;
  
  /**
  
  * 保存动态代理对象，避免被垃圾回收。
    */
    private Object bukkitListenerProxy;
    private Object bukkitExecutorProxy;
  
  /**
  
  * 渐变格式：
  * 
  * "gradient:#0047FF:#00FFFF:#FFFFFF" (gradient:#0047FF:#00FFFF:#FFFFFF)文字</gradient>
    /
    private static final Pattern GRADIENT_PATTERN =
    Pattern.compile(
    "(?i)"gradient:((?:#[0-9a-f]{6})(?::#[0-9a-f]{6})*)" (gradient:((?:#[0-9a-f]{6})(?::#[0-9a-f]{6})*))(.?)</gradient>",
    Pattern.DOTALL
    );
  
  public TitleChatEvents(
  TitleManager titleManager
  ) {
  this.titleManager = titleManager;
  }
  
  /**
  
  * 第一次尝试挂接 Bukkit 聊天。
    */
    @SubscribeEvent
    public void onServerStarting(
    ServerStartingEvent event
    ) {
    tryHookBukkitChat();
    }
  
  /**
  
  * ServerStartedEvent 再尝试一次。
  * 
  * 某些 Mohist 版本在 ServerStartingEvent 时
  * Bukkit 环境还没有完全初始化。
    */
    @SubscribeEvent
    public void onServerStarted(
    ServerStartedEvent event
    ) {
    tryHookBukkitChat();
    }
  
  /**
  
  * 通过反射注册 Bukkit AsyncPlayerChatEvent。
  
  * 
  
  * 不直接引用 Bukkit API，
  
  * 避免 Forge 开发环境因为没有 Bukkit 依赖而无法编译。
    */
    private synchronized void tryHookBukkitChat() {
    
    if (bukkitChatHooked) {
    return;
    }
    
    try {
    
     Class<?> bukkitClass =
         Class.forName(
                 "org.bukkit.Bukkit"
         );

 Class<?> eventClass =
         Class.forName(
                 "org.bukkit.event.player.AsyncPlayerChatEvent"
         );

 Class<?> eventBaseClass =
         Class.forName(
                 "org.bukkit.event.Event"
         );

 Class<?> listenerClass =
         Class.forName(
                 "org.bukkit.event.Listener"
         );

 Class<?> eventPriorityClass =
         Class.forName(
                 "org.bukkit.event.EventPriority"
         );

 Class<?> eventExecutorClass =
         Class.forName(
                 "org.bukkit.plugin.EventExecutor"
         );

 Class<?> pluginManagerClass =
         Class.forName(
                 "org.bukkit.plugin.PluginManager"
         );

 Class<?> pluginClass =
         Class.forName(
                 "org.bukkit.plugin.Plugin"
         );

 /*
  * 获取 Bukkit PluginManager。
  */
 Object pluginManager =
         bukkitClass
                 .getMethod(
                         "getPluginManager"
                 )
                 .invoke(null);

 /*
  * Forge Mod 不是 Bukkit Plugin。
  *
  * 因此需要借用一个已经启用的 Bukkit Plugin
  * 作为 Event 注册所有者。
  */
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
  * HIGHEST：
  *
  * 尽量排在其他普通聊天格式化器后面。
  */
 Object highest =
         Enum.valueOf(
                 (Class<? extends Enum>)
                         eventPriorityClass
                                 .asSubclass(Enum.class),
                 "HIGHEST"
         );

 /*
  * Bukkit Listener 动态代理。
  */
 InvocationHandler listenerHandler =
         (proxy, method, args) ->
                 defaultProxyReturn(method);

 /*
  * Bukkit EventExecutor 动态代理。
  */
 InvocationHandler executorHandler =
         (proxy, method, args) -> {

             if ("execute".equals(
                     method.getName()
             )
                     && args != null
                     && args.length >= 2
                     && eventBaseClass.isInstance(
                     args[1]
             )) {

                 handleBukkitChat(
                         args[1]
                 );
             }

             return defaultProxyReturn(
                     method
             );
         };

 ClassLoader bukkitClassLoader =
         listenerClass.getClassLoader();

 if (bukkitClassLoader == null) {

     bukkitClassLoader =
             TitleChatEvents.class
                     .getClassLoader();
 }

 /*
  * 创建 Listener。
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
  * 创建 EventExecutor。
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
  * 获取 Bukkit：
  *
  * registerEvent(
  *     Event.class,
  *     Listener,
  *     EventPriority,
  *     EventExecutor,
  *     Plugin,
  *     boolean
  * )
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

 /*
  * 注册 AsyncPlayerChatEvent。
  *
  * ignoreCancelled = false
  *
  * 即使其他插件取消聊天事件，
  * 我们仍然可以收到事件。
  */
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
                         .getMethod(
                                 "getName"
                         )
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
  
  * 找一个已经启用的 Bukkit Plugin。
    */
    private Object findEnabledPlugin(
    Object pluginManager,
    Class<?> pluginManagerClass,
Class<?> pluginClass
    ) throws Exception {
    
    Method getPlugins =
    pluginManagerClass.getMethod(
    "getPlugins"
    );
    
    Object plugins =
    getPlugins.invoke(
    pluginManager
    );
    
    if (plugins == null
    || !plugins.getClass().isArray()) {
    
     return null;
    
    }
    
    int length =
    Array.getLength(
    plugins
    );
    
    for (int i = 0; i < length; i++) {
    
     Object plugin =
         Array.get(
                 plugins,
                 i
         );

 if (plugin == null
         || !pluginClass.isInstance(
         plugin
 )) {

     continue;
 }

 try {

     Object enabled =
             pluginClass
                     .getMethod(
                             "isEnabled"
                     )
                     .invoke(
                             plugin
                     );

     if (Boolean.TRUE.equals(
             enabled
     )) {

         return plugin;
     }

 } catch (Throwable ignored) {

     /*
      * 当前 Plugin 获取失败，
      * 继续尝试下一个。
      */
 }
    
    }
    
    return null;
    }
  
  /**
  
  * Bukkit AsyncPlayerChatEvent。
    */
    private void handleBukkitChat(
    Object event
    ) {
    
    try {
    
     /*
  * 获取 Bukkit Player。
  */
 Method getPlayer =
         event.getClass()
                 .getMethod(
                         "getPlayer"
                 );

 Object bukkitPlayer =
         getPlayer.invoke(
                 event
         );

 if (bukkitPlayer == null) {
     return;
 }

 /*
  * 获取玩家 UUID。
  */
 UUID uuid =
         (UUID)
                 bukkitPlayer
                         .getClass()
                         .getMethod(
                                 "getUniqueId"
                         )
                         .invoke(
                                 bukkitPlayer
                         );

 if (uuid == null
         || titleManager == null) {

     return;
 }

 /*
  * 获取主称号 ID。
  */
 String mainTitle =
         titleManager.getMainTitle(
                 uuid
         );

 /*
  * 获取副称号 ID。
  */
 String subTitle =
         titleManager.getSubTitle(
                 uuid
         );

 /*
  * 没有称号：
  *
  * 完全不修改原聊天。
  */
 if ((mainTitle == null
         || mainTitle.isBlank())
         && (subTitle == null
         || subTitle.isBlank())) {

     return;
 }

 /*
  * 获取真正的称号文本。
  */
 StringBuilder title =
         new StringBuilder();

 appendTitle(
         title,
         mainTitle
 );

 appendTitle(
         title,
         subTitle
 );

 /*
  * 称号 ID 存在，
  * 但最终没有有效文本。
  */
 if (title.length() == 0) {
     return;
 }

 /*
  * 把称号格式转换成 Bukkit 可以识别的
  * § 颜色格式。
  */
 String legacyTitle =
         convertToLegacy(
                 title.toString()
         );

 /*
  * Bukkit 的 String.format()
  * 会把 % 当成格式占位符。
  *
  * 所以称号里的 % 必须变成 %%。
  */
 legacyTitle =
         legacyTitle.replace(
                 "%",
                 "%%"
         );

 /*
  * 最终格式：
  *
  * <称号 玩家名> 消息
  *
  * %1$s = 玩家名
  * %2$s = 消息
  */
 String format =
         "<"
                 + legacyTitle
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
  
  * 添加称号。
  
  * 
  
  * getMainTitle / getSubTitle 返回的是称号 ID，
  
  * getTitleText 才返回实际显示文本。
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
  
  * 将 TitleManager 的称号文本
  
  * 转换为 Bukkit 聊天能够识别的颜色格式。
  
  * 
  
  * 支持：
  
  * 
  
  * "gradient:#RRGGBB:#RRGGBB" (gradient:#RRGGBB:#RRGGBB)文字</gradient>
  
  * &#RRGGBB
  
  * &x&R&R&G&G&B&B
  
  * §x§R§R§G§G§B§B
  
  * &0-&f
  
  * §0-§f
  
  * &k-&o
  
  * §k-§o
  
  * &r / §r
    */
    private String convertToLegacy(
    String input
    ) {
    
    if (input == null
    || input.isEmpty()) {
    
     return "";
    
    }
    
    /*
    
    * 先处理渐变。
      */
      String result =
      convertGradients(
      input
      );
    
    /*
    
    * &x&R&R&G&G&B&B
    * 
    * 转换成：
    * 
    * §x§R§R§G§G§B§B
      */
      result =
      result.replace(
      "&x&",
      "§x§"
      );
    
    /*
    
    * &#RRGGBB
    * 
    * 转换成：
    * 
    * §x§R§R§G§G§B§B
      */
      result =
      convertAmpersandHex(
      result
      );
    
    /*
    
    * 其他 & 颜色代码。
    * 
    * Bukkit 可以直接识别 §。
      */
      result =
      convertAmpersandCodes(
      result
      );
    
    return result;
    }
  
  /**
  
  * 处理：
  
  * 
  
  * "gradient:#RRGGBB:#RRGGBB" (gradient:#RRGGBB:#RRGGBB)文字</gradient>
  
  * 
  
  * 生成：
  
  * 
  
  * §x§R§R§G§G§B§B文...
    */
    private String convertGradients(
    String input
    ) {
    
    Matcher matcher =
    GRADIENT_PATTERN.matcher(
    input
    );
    
    StringBuffer output =
    new StringBuffer();
    
    while (matcher.find()) {
    
     String colorString =
         matcher.group(1);

 String text =
         matcher.group(2);

 int[] colors =
         parseGradientColors(
                 colorString
         );

 String replacement;

 if (colors.length == 0
         || text == null
         || text.isEmpty()) {

     replacement =
             text == null
                     ? ""
                     : text;

 } else {

     replacement =
             createGradientLegacy(
                     text,
                     colors
             );
 }

 matcher.appendReplacement(
         output,
         Matcher.quoteReplacement(
                 replacement
         )
 );
    
    }
    
    matcher.appendTail(
    output
    );
    
    return output.toString();
    }
  
  /**
  
  * 解析：
  
  * 
  
  * #0047FF:#00FFFF:#FFFFFF
    */
    private int[] parseGradientColors(
    String value
    ) {
    
    if (value == null
    || value.isBlank()) {
    
     return new int[0];
    
    }
    
    String[] parts =
    value.split(":");
    
    int[] colors =
    new int[parts.length];
    
    int count = 0;
    
    for (String part : parts) {
    
     if (part == null) {
     continue;
 }

 String hex =
         part.trim();

 if (hex.startsWith("#")) {
     hex =
             hex.substring(1);
 }

 if (hex.length() != 6
         || !isHex(hex)) {

     continue;
 }

 try {

     colors[count++] =
             Integer.parseInt(
                     hex,
                     16
             );

 } catch (NumberFormatException ignored) {
 }
    
    }
    
    if (count == colors.length) {
    return colors;
    }
    
    int[] result =
    new int[count];
    
    System.arraycopy(
    colors,
    0,
    result,
    0,
    count
    );
    
    return result;
    }
  
  /**
  
  * 生成渐变的 §x 十六进制颜色。
  
  * 
  
  * 例如：
  
  * 
  
  * §x§0§0§4§7§F§F雾
  
  * §x§0§0§C§0§F§F雨
  
  * ...
    */
    private String createGradientLegacy(
    String text,
    int[] colors
    ) {
    
    if (text == null
    || text.isEmpty()) {
    
     return "";
    
    }
    
    if (colors.length == 0) {
    return text;
    }
    
    /*
    
    * 按 Unicode code point 计算，
    * 避免中文/特殊字符被 UTF-16 surrogate 拆开。
      */
      int length =
      text.codePointCount(
      0,
      text.length()
      );
    
    if (length <= 0) {
    return "";
    }
    
    StringBuilder result =
    new StringBuilder();
    
    int index = 0;
    
    for (int offset = 0;
    offset < text.length();) {
    
     int codePoint =
         text.codePointAt(
                 offset
         );

 String character =
         new String(
                 Character.toChars(
                         codePoint
                 )
         );

 /*
  * 与 TitleManager 的渐变算法保持一致：
  *
  * 字符中心位置取样。
  */
 double position =
         (index + 0.5D)
                 / Math.max(
                         1.0D,
                         length
                 );

 position =
         Math.max(
                 0.0D,
                 Math.min(
                         1.0D,
                         position
                 )
         );

 double scaled =
         position
                 * (colors.length - 1);

 int left =
         (int)
                 Math.floor(
                         scaled
                 );

 int right =
         Math.min(
                 colors.length - 1,
                 left + 1
         );

 double local =
         scaled - left;

 /*
  * SmoothStep。
  */
 local =
         local
                 * local
                 * (3.0D - 2.0D * local);

 int color =
         interpolateColor(
                 colors[left],
                 colors[right],
                 local
         );

 result.append(
         toMinecraftHex(
                 color
         )
 );

 result.append(
         character
 );

 offset +=
         Character.charCount(
                 codePoint
         );

 index++;
    
    }
    
    return result.toString();
    }
  
  /**
  
  * 两个 RGB 颜色插值。
    */
    private int interpolateColor(
    int color1,
    int color2,
    double t
    ) {
    
    int r1 =
    (color1 >> 16) & 0xFF;
    
    int g1 =
    (color1 >> 8) & 0xFF;
    
    int b1 =
    color1 & 0xFF;
    
    int r2 =
    (color2 >> 16) & 0xFF;
    
    int g2 =
    (color2 >> 8) & 0xFF;
    
    int b2 =
    color2 & 0xFF;
    
    int r =
    interpolate(
    r1,
    r2,
    t
    );
    
    int g =
    interpolate(
    g1,
    g2,
    t
    );
    
    int b =
    interpolate(
    b1,
    b2,
    t
    );
    
    return (r << 16)
    | (g << 8)
    | b;
    }
  
  /**
  
  * 单通道插值。
    */
    private int interpolate(
    int a,
    int b,
    double t
    ) {
    
    return (int)
    Math.round(
    a + (b - a) * t
    );
    }
  
  /**
  
  * RGB → Minecraft §x 格式。
  
  * 
  
  * 例如：
  
  * 
  
  * 0047FF
  
  * 
  
  * →
  
  * 
  
  * §x§0§0§4§7§F§F
    */
    private String toMinecraftHex(
    int color
    ) {
    
    String hex =
    String.format(
    "%06X",
    color & 0xFFFFFF
    );
    
    StringBuilder result =
    new StringBuilder(
    "§x"
    );
    
    for (int i = 0;
    i < hex.length();
    i++) {
    
     result.append('§');
 result.append(
         hex.charAt(i)
 );
    
    }
    
    return result.toString();
    }
  
  /**
  
  * &#RRGGBB
  
  * 
  
  * 转：
  
  * 
  
  * §x§R§R§G§G§B§B
    */
    private String convertAmpersandHex(
    String input
    ) {
    
    StringBuilder result =
    new StringBuilder();
    
    for (int i = 0;
    i < input.length();) {
    
     if (input.charAt(i) == '&'
         && i + 7 < input.length()
         && input.charAt(i + 1) == '#') {

     String hex =
             input.substring(
                     i + 2,
                     i + 8
             );

     if (isHex(hex)) {

         result.append(
                 toMinecraftHex(
                         Integer.parseInt(
                                 hex,
                                 16
                         )
                 )
         );

         i += 8;
         continue;
     }
 }

 result.append(
         input.charAt(i)
 );

 i++;
    
    }
    
    return result.toString();
    }
  
  /**
  
  * &a / &b / &l / &r 等
  
  * 转成 §a / §b / §l / §r。
    */
    private String convertAmpersandCodes(
    String input
    ) {
    
    StringBuilder result =
    new StringBuilder();
    
    for (int i = 0;
    i < input.length();) {
    
     char c =
         input.charAt(i);

 if (c == '&'
         && i + 1 < input.length()) {

     char code =
             input.charAt(
                     i + 1
             );

     if (isLegacyCode(code)) {

         result.append('§');
         result.append(
                 Character.toLowerCase(
                         code
                 )
         );

         i += 2;
         continue;
     }
 }

 result.append(c);
 i++;
    
    }
    
    return result.toString();
    }
  
  /**
  
  * 判断 Minecraft legacy code。
    */
    private boolean isLegacyCode(
    char code
    ) {
    
    char c =
    Character.toLowerCase(
    code
    );
    
    return (c >= '0' && c <= '9')
    || (c >= 'a' && c <= 'f')
    || (c >= 'k' && c <= 'o')
    || c == 'r';
    }
  
  /**
  
  * 判断十六进制字符。
    */
    private boolean isHex(
    String value
    ) {
    
    if (value == null
    || value.length() != 6) {
    
     return false;
    
    }
    
    for (int i = 0;
    i < value.length();
    i++) {
    
     char c =
         value.charAt(i);

 boolean valid =
         (c >= '0' && c <= '9')
                 || (c >= 'a' && c <= 'f')
                 || (c >= 'A' && c <= 'F');

 if (!valid) {
     return false;
 }
    
    }
    
    return true;
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
