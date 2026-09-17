package com.example.netcrafttoolkit;

import com.mojang.logging.LogUtils;
import net.minecraft.server.MinecraftServer;
import org.slf4j.Logger;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * NetCraft 装备数值热加载器。
 *
 * 不再修改 world/serverconfig/netcraft-server.toml 的文本内容。
 *
 * 工作方式：
 * 1. 读取 NetCraft Toolkit 的 [equipment."..."] 配置。
 * 2. 通过反射取得 NetCraft 1.4.18 的 EquipmentStatConfig.VALUES。
 * 3. 直接调用 Forge ConfigValue.set(...) 修改运行时配置。
 * 4. 调用 ConfigValue.save() 持久化。
 * 5. 再由 NetCraftToolkit 刷新已经存在的实体。
 *
 * 因此修改 netcrafttoolkit.toml 后，不需要服务器重启。
 */
public final class EquipmentOverrideWriter {

    private static final Logger LOGGER = LogUtils.getLogger();

    private static final String CONFIG_CLASS =
            "com.jiufeng.netcraft.config.EquipmentStatConfig";

    private static final String GROUP_CLASS =
            CONFIG_CLASS + "$Group";

    private static final String CLASS_TYPE_CLASS =
            CONFIG_CLASS + "$ClassType";

    private static final String SLOT_CLASS =
            CONFIG_CLASS + "$Slot";

    private static final String FIELD_CLASS =
            CONFIG_CLASS + "$Field";

    /**
     * NetCraft 装备注册名格式：
     *
     * weapon_knight_t1_mhand
     * weapon_mage_t3_hand
     * equipment_knight_t1_1
     * weapon_knight_legend1_mhand
     */
    private static final Pattern EQUIPMENT_ID_PATTERN = Pattern.compile(
            "^(?:weapon|equipment)_(knight|archer|mage|summoner|dragonknight|wararcher)_(t\\d+|legend\\d+)_(mhand|hand|[1-4])$",
            Pattern.CASE_INSENSITIVE
    );

    private EquipmentOverrideWriter() {
    }

    /**
     * 把 Toolkit 的装备覆盖值直接应用到 NetCraft 运行时配置。
     */
    public static void applyToWorld(
            MinecraftServer server,
            NetCraftConfig config
    ) {
        if (server == null || config == null) {
            return;
        }

        Map<String, Map<String, Double>> overrides =
                config.getEquipmentOverrides();

        if (overrides == null || overrides.isEmpty()) {
            LOGGER.info(
                    "[NetCraftToolkit] 没有装备覆盖配置，跳过 NetCraft 装备热加载。"
            );
            return;
        }

        try {
            Class<?> configClass = Class.forName(CONFIG_CLASS);
            Class<?> groupClass = Class.forName(GROUP_CLASS);
            Class<?> classTypeClass = Class.forName(CLASS_TYPE_CLASS);
            Class<?> slotClass = Class.forName(SLOT_CLASS);
            Class<?> fieldClass = Class.forName(FIELD_CLASS);
            Class<?> configKeyClass = Class.forName(
                    CONFIG_CLASS + "$ConfigKey"
            );

            Map<?, ?> values = getValuesMap(configClass);
            if (values == null) {
                LOGGER.error(
                        "[NetCraftToolkit] 无法取得 NetCraft EquipmentStatConfig.VALUES。"
                );
                return;
            }

            enableEquipmentOverride(configClass);

            Constructor<?> configKeyConstructor = configKeyClass.getConstructor(
                    groupClass,
                    classTypeClass,
                    slotClass,
                    fieldClass
            );

            int changed = 0;
            int skipped = 0;

            for (Map.Entry<String, Map<String, Double>> equipmentEntry
                    : overrides.entrySet()) {

                String equipmentId = equipmentEntry.getKey();
                Map<String, Double> properties = equipmentEntry.getValue();

                if (properties == null || properties.isEmpty()) {
                    continue;
                }

                Matcher matcher = EQUIPMENT_ID_PATTERN.matcher(
                        equipmentId == null ? "" : equipmentId
                );

                if (!matcher.matches()) {
                    LOGGER.debug(
                            "[NetCraftToolkit] 跳过无法映射到 NetCraft EquipmentStatConfig 的装备：{}",
                            equipmentId
                    );
                    skipped++;
                    continue;
                }

                String className = matcher.group(1).toUpperCase(Locale.ROOT);
                String tierName = matcher.group(2).toLowerCase(Locale.ROOT);
                String slotCode = matcher.group(3).toLowerCase(Locale.ROOT);

                Object group = Enum.valueOf(
                        asEnumClass(groupClass),
                        groupName(tierName)
                );

                Object classType = Enum.valueOf(
                        asEnumClass(classTypeClass),
                        className
                );

                Object slot = Enum.valueOf(
                        asEnumClass(slotClass),
                        slotName(slotCode)
                );

                for (Map.Entry<String, Double> property
                        : properties.entrySet()) {

                    String propertyName = property.getKey();
                    Double number = property.getValue();

                    if (number == null || number < 0D) {
                        // -1 = 保留 NetCraft 原值
                        continue;
                    }

                    String fieldName = fieldName(propertyName);
                    if (fieldName == null) {
                        LOGGER.debug(
                                "[NetCraftToolkit] 跳过未知装备属性：{}.{}",
                                equipmentId,
                                propertyName
                        );
                        skipped++;
                        continue;
                    }

                    Object field = Enum.valueOf(
                            asEnumClass(fieldClass),
                            fieldName
                    );

                    Object configKey = configKeyConstructor.newInstance(
                            group,
                            classType,
                            slot,
                            field
                    );

                    Object configValue = values.get(configKey);

                    if (configValue == null) {
                        LOGGER.warn(
                                "[NetCraftToolkit] NetCraft 没有对应装备配置项：{} / {}",
                                equipmentId,
                                propertyName
                        );
                        skipped++;
                        continue;
                    }

                    int intValue = toInteger(number);

                    if (setConfigValue(configValue, intValue)) {
                        saveConfigValue(configValue);
                        changed++;

                        LOGGER.info(
                                "[NetCraftToolkit] 装备热加载：{} -> {} = {}",
                                equipmentId,
                                propertyName,
                                intValue
                        );
                    } else {
                        skipped++;
                    }
                }
            }

            LOGGER.info(
                    "[NetCraftToolkit] NetCraft 装备热加载完成：修改 {} 项，跳过 {} 项。",
                    changed,
                    skipped
            );

        } catch (ClassNotFoundException e) {
            LOGGER.warn(
                    "[NetCraftToolkit] 未找到 NetCraft EquipmentStatConfig，装备热加载未执行。请确认 NetCraft 1.4.18 已加载。"
            );
        } catch (Throwable e) {
            LOGGER.error(
                    "[NetCraftToolkit] NetCraft 装备热加载失败。",
                    e
            );
        }
    }

    /** 读取 EquipmentStatConfig 中私有的 VALUES。 */
    private static Map<?, ?> getValuesMap(Class<?> configClass)
            throws ReflectiveOperationException {

        Field valuesField = configClass.getDeclaredField("VALUES");
        valuesField.setAccessible(true);

        Object value = valuesField.get(null);
        if (!(value instanceof Map<?, ?> map)) {
            return null;
        }

        return map;
    }

    /** 开启 NetCraft 的装备覆盖总开关。 */
    private static void enableEquipmentOverride(Class<?> configClass)
            throws ReflectiveOperationException {

        Field field = configClass.getDeclaredField("ENABLE_OVERRIDE");
        field.setAccessible(true);

        Object configValue = field.get(null);
        if (configValue == null) {
            return;
        }

        if (!setConfigValue(configValue, Boolean.TRUE)) {
            LOGGER.warn(
                    "[NetCraftToolkit] 无法开启 NetCraft enableEquipmentStatOverride。"
            );
            return;
        }

        saveConfigValue(configValue);
    }

    /**
     * 反射调用 Forge ConfigValue.set(...).
     * 不把 Forge ConfigValue 写进本类类型签名，避免版本耦合。
     */
    private static boolean setConfigValue(
            Object configValue,
            Object value
    ) {
        try {
            Method setMethod = findOneArgumentMethod(
                    configValue.getClass(),
                    "set"
            );

            if (setMethod == null) {
                LOGGER.warn(
                        "[NetCraftToolkit] 找不到 ConfigValue.set(...)：{}",
                        configValue.getClass().getName()
                );
                return false;
            }

            setMethod.setAccessible(true);
            setMethod.invoke(configValue, value);
            return true;

        } catch (Throwable e) {
            LOGGER.error(
                    "[NetCraftToolkit] ConfigValue.set(...) 调用失败。",
                    e
            );
            return false;
        }
    }

    /** 持久化 ConfigValue。 */
    private static void saveConfigValue(Object configValue) {
        try {
            Method saveMethod = findNoArgumentMethod(
                    configValue.getClass(),
                    "save"
            );

            if (saveMethod == null) {
                return;
            }

            saveMethod.setAccessible(true);
            saveMethod.invoke(configValue);

        } catch (Throwable e) {
            LOGGER.warn(
                    "[NetCraftToolkit] ConfigValue.save() 调用失败。",
                    e
            );
        }
    }

    private static Method findOneArgumentMethod(
            Class<?> type,
            String name
    ) {
        Class<?> current = type;

        while (current != null) {
            for (Method method : current.getDeclaredMethods()) {
                if (method.getName().equals(name)
                        && method.getParameterCount() == 1) {
                    return method;
                }
            }
            current = current.getSuperclass();
        }

        return null;
    }

    private static Method findNoArgumentMethod(
            Class<?> type,
            String name
    ) {
        Class<?> current = type;

        while (current != null) {
            for (Method method : current.getDeclaredMethods()) {
                if (method.getName().equals(name)
                        && method.getParameterCount() == 0) {
                    return method;
                }
            }
            current = current.getSuperclass();
        }

        return null;
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static Class<? extends Enum> asEnumClass(Class<?> type) {
        return (Class<? extends Enum>) type;
    }

    private static String groupName(String tierName) {
        if (tierName.startsWith("legend")) {
            return "LEGEND" + tierName.substring("legend".length());
        }

        return "TIER" + tierName.substring(1);
    }

    private static String slotName(String slotCode) {
        return switch (slotCode) {
            case "mhand" -> "MAINHAND";
            case "hand" -> "OFFHAND";
            case "1" -> "HELMET";
            case "2" -> "CHESTPLATE";
            case "3" -> "LEGGINGS";
            case "4" -> "BOOTS";
            default -> throw new IllegalArgumentException(
                    "Unknown NetCraft equipment slot: " + slotCode
            );
        };
    }

    private static String fieldName(String propertyName) {
        if (propertyName == null) {
            return null;
        }

        return switch (propertyName.trim().toLowerCase(Locale.ROOT)) {
            case "meleedamage" -> "MELEE_DAMAGE";
            case "rangeddamage" -> "RANGED_DAMAGE";
            case "magicdamage" -> "MAGIC_DAMAGE";
            case "physicaldefense" -> "PHYSICAL_DEFENSE";
            case "magicdefense" -> "MAGIC_DEFENSE";
            case "health" -> "HEALTH";
            case "armor" -> "ARMOR";
            default -> null;
        };
    }

    private static int toInteger(double value) {
        if (value >= Integer.MAX_VALUE) {
            return Integer.MAX_VALUE;
        }

        if (value <= Integer.MIN_VALUE) {
            return Integer.MIN_VALUE;
        }

        return (int) Math.round(value);
    }
}
