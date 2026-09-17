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

* NetCraftToolkit 聊天称号适配。

* 

* Mohist 1.20.1 下使用 Bukkit AsyncPlayerChatEvent

* 接管最终聊天格式。

* 

* 最终格式：

* 

* <称号 玩家名> 消息

* 

* 支持：

* "gradient:#RRGGBB:#RRGGBB" (gradient:#RRGGBB:#RRGGBB)文字</gradient>

* &#RRGGBB

* &x&R&R&G&G&B&B

* &a ~ &f

* &k ~ &o

* &r
  */
  public class TitleChatEvents {
  
  private final TitleManager titleManager;
  
  /**
  
  * 防止重复注册 Bukkit 聊天事件。
    */
    private volatile boolean bukkitChatHooked = false;
  
  /**
  
  * 保存动态代理引用。
    */
    private Object bukkitListenerProxy;
    private Object bukkitExecutorProxy;
  
  /**
  
  * "gradient:#RRGGBB:#RRGGBB" (gradient:#RRGGBB:#RRGGBB)文字</gradient>
  * 
  * 支持多个颜色节点。
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
  
  * Forge 服务器启动阶段尝试注册 Bukkit 聊天事件。
    */
    @SubscribeEvent
    public void onServerStarting(
    ServerStartingEvent event
    ) {
    tryHookBukkitChat();
    }
  
  /**
  
  * ServerStartedEvent 再尝试一次。
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
  
  * 不直接引用 Bukkit API。
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
  * Bukkit PluginManager。
  */
 Object pluginManager =
         bukkitClass
                 .getMethod(
                         "getPluginManager"
                 )
                 .invoke(null);

 /*
  * Forge Mod 本身不是 Bukkit Plugin。
  *
  * 借用一个已经启用的 Bukkit Plugin
  * 作为事件注册 owner。
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
  * HIGHEST。
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
                 defaultProxyReturn(
                         method
                 );

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

 ClassLoader classLoader =
         listenerClass.getClassLoader();

 if (classLoader == null) {

     classLoader =
             TitleChatEvents.class
                     .getClassLoader();
 }

 /*
  * 创建 Bukkit Listener。
  */
 bukkitListenerProxy =
         Proxy.newProxyInstance(
                 classLoader,
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
                 classLoader,
                 new Class<?>[]{
                         eventExecutorClass
                 },
                 executorHandler
         );

 /*
  * registerEvent(
  *     Event,
  *     Listener,
  *     EventPriority,
  *     EventExecutor,
  *     Plugin,
  *     ignoreCancelled
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
  * 注册聊天事件。
  *
  * ignoreCancelled = false
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
                         .invoke(
                                 plugin
                         )
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
  
  * 找到一个已经启用的 Bukkit Plugin。
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
    
    for (int i = 0;
    i < length;
    i++) {
    
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
      * 当前插件读取失败，
      * 继续寻找其他插件。
      */
 }
    
    }
    
    return null;
    }
  
  /**
  
  * 处理 Bukkit AsyncPlayerChatEvent。
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
  * 获取 UUID。
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
  * 主称号 ID。
  */
 String mainTitle =
         titleManager.getMainTitle(
                 uuid
         );

 /*
  * 副称号 ID。
  */
 String subTitle =
         titleManager.getSubTitle(
                 uuid
         );

 /*
  * 没有主、副称号。
  *
  * 不修改原聊天。
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

 if (title.length() == 0) {
     return;
 }

 /*
  * 转换：
  *
  * <gradient:...>
  *
  * →
  *
  * §x§R§R§G§G§B§B
  */
 String legacyTitle =
         convertToLegacy(
                 title.toString()
         );

 /*
  * Bukkit 使用 String.format。
  *
  * 所以称号文本里的 %
  * 必须转成 %%。
  */
 legacyTitle =
         legacyTitle.replace(
                 "%",
                 "%%"
         );

 /*
  * 最终：
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
  
  * 转换称号格式。
    */
    private String convertToLegacy(
    String input
    ) {
    
    if (input == null
    || input.isEmpty()) {
    
     return "";
    
    }
    
    /*
    
    * 先转换 "gradient:..." (gradient:...)。
      */
      String result =
      convertGradients(
      input
      );
    
    /*
    
    * &x&R&R&G&G&B&B
    * 
    * →
    * 
    * §x§R§R§G§G§B§B
      */
      result =
      convertAmpersandX(
      result
      );
    
    /*
    
    * &#RRGGBB
    * 
    * →
    * 
    * §x§R§R§G§G§B§B
      */
      result =
      convertAmpersandHex(
      result
      );
    
    /*
    
    * &a / &b / &l / &r 等。
      */
      result =
      convertAmpersandCodes(
      result
      );
    
    return result;
    }
  
  /**
  
  * 转换：
  
  * 
  
  * "gradient:#RRGGBB:#RRGGBB" (gradient:#RRGGBB:#RRGGBB)文字</gradient>
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
  
  * 创建渐变颜色。
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
  * 当前字符在渐变中的位置。
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

 /*
  * 映射到颜色节点。
  */
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
  
  * RGB 颜色插值。
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
  
  * RGB 转 Minecraft §x 格式。
  
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
  
  * 转换：
  
  * 
  
  * &x&R&R&G&G&B&B
  
  * 
  
  * →
  
  * 
  
  * §x§R§R§G§G§B§B
    */
    private String convertAmpersandX(
    String input
    ) {
    
    StringBuilder result =
    new StringBuilder();
    
    for (int i = 0;
    i < input.length();) {
    
     if (i + 13 < input.length()
         && input.charAt(i) == '&'
         && Character.toLowerCase(
         input.charAt(i + 1)
 ) == 'x') {

     StringBuilder hex =
             new StringBuilder();

     boolean valid = true;

     int position =
             i + 2;

     for (int j = 0; j < 6; j++) {

         if (position + 1 >= input.length()
                 || input.charAt(position) != '&') {

             valid = false;
             break;
         }

         char value =
                 input.charAt(
                         position + 1
                 );

         if (!isHexChar(value)) {

             valid = false;
             break;
         }

         hex.append(value);

         position += 2;
     }

     if (valid
             && hex.length() == 6) {

         result.append(
                 toMinecraftHex(
                         Integer.parseInt(
                                 hex.toString(),
                                 16
                         )
                 )
         );

         i = position;
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
  
  * 转换：
  
  * 
  
  * &#RRGGBB
    */
    private String convertAmpersandHex(
    String input
    ) {
    
    StringBuilder result =
    new StringBuilder();
    
    for (int i = 0;
    i < input.length();) {
    
     if (i + 7 < input.length()
         && input.charAt(i) == '&'
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
  
  * 转换：
  
  * 
  
  * &a
  
  * &b
  
  * &l
  
  * &r
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
  
  * Minecraft Legacy Code。
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
  
  * 判断十六进制字符串。
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
    
     if (!isHexChar(
         value.charAt(i)
 )) {

     return false;
 }
    
    }
    
    return true;
    }
  
  /**
  
  * 判断单个十六进制字符。
    */
    private boolean isHexChar(
    char c
    ) {
    
    return (c >= '0' && c <= '9')
    || (c >= 'a' && c <= 'f')
    || (c >= 'A' && c <= 'F');
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
