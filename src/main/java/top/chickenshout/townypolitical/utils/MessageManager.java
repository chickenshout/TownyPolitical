// 文件名: MessageManager.java
// 结构位置: top/chickenshout/townypolitical/utils/MessageManager.java
package top.chickenshout.townypolitical.utils;

import net.md_5.bungee.api.ChatMessageType;
import net.md_5.bungee.api.chat.TextComponent;
import org.bukkit.ChatColor;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.logging.Level;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MessageManager {

    private final JavaPlugin plugin;
    private final Map<String, String> messages = new HashMap<>();
    private String prefix;
    private FileConfiguration messagesConfig;
    private final File messagesFile;
    private final String defaultMessagesFileName = "messages_zh_CN.yml"; // 默认中文消息文件名

    private static final Pattern HEX_PATTERN = Pattern.compile("&#([A-Fa-f0-9]{6})");

    public MessageManager(JavaPlugin plugin) {
        this.plugin = plugin;
        this.messagesFile = new File(plugin.getDataFolder(), "messages.yml"); // 将在插件数据文件夹中创建 messages.yml
        // prefix的初始化移到loadMessages之后，因为它依赖于从messagesConfig读取
        // loadMessages(); // 在构造时调用加载，确保 prefix 和 messages 填充
    }

    /**
     * 加载消息文件。如果文件不存在，则从JAR中复制默认的中文消息文件。
     * 也会加载插件消息前缀。
     * 这个方法应该在插件 onEnable 早期被主插件类调用，或由构造函数调用。
     * 为了确保MessageManager实例在主插件的managers map中已经存在，
     * 最好由主插件的onEnable调用，而不是构造函数。
     * 如果由构造函数调用，需要确保plugin.getDataFolder()已可用。
     */
    public void loadMessages() {
        if (!plugin.getDataFolder().exists()) {
            plugin.getDataFolder().mkdirs();
        }

        if (!messagesFile.exists()) {
            plugin.getLogger().info("messages.yml not found, creating from default " + defaultMessagesFileName + "...");
            plugin.saveResource(defaultMessagesFileName, false); // 保存 messages_zh_CN.yml 到插件文件夹
            File defaultSavedFile = new File(plugin.getDataFolder(), defaultMessagesFileName);
            if (defaultSavedFile.exists()) {
                // 将其重命名为 messages.yml
                if (!defaultSavedFile.renameTo(messagesFile)) {
                    plugin.getLogger().warning("Could not rename " + defaultMessagesFileName + " to messages.yml. Please rename it manually.");
                    // 如果重命名失败，尝试直接加载 messages_zh_CN.yml 以避免 messagesConfig 为 null
                    if (defaultSavedFile.exists()) {
                        this.messagesConfig = YamlConfiguration.loadConfiguration(defaultSavedFile);
                    } else {
                        plugin.getLogger().severe("Default messages file " + defaultMessagesFileName + " also not found after failing to rename. Messages might not load correctly.");
                        this.messagesConfig = new YamlConfiguration(); // 创建一个空的，避免NPE
                    }
                } else {
                    this.messagesConfig = YamlConfiguration.loadConfiguration(messagesFile);
                    plugin.getLogger().info("Successfully created messages.yml from defaults.");
                }
            } else {
                plugin.getLogger().severe("Default messages file (" + defaultMessagesFileName + ") could not be saved or found. Messages will likely be missing.");
                this.messagesConfig = new YamlConfiguration();
            }
        } else {
            this.messagesConfig = YamlConfiguration.loadConfiguration(messagesFile);
        }

        // 从插件内部加载默认值以确保所有键都存在，并允许用户看到所有可配置的消息
        try (InputStream defaultConfigStream = plugin.getResource(defaultMessagesFileName)) {
            if (defaultConfigStream != null) {
                YamlConfiguration internalDefaultConfig = YamlConfiguration.loadConfiguration(new InputStreamReader(defaultConfigStream, StandardCharsets.UTF_8));
                messagesConfig.addDefaults(internalDefaultConfig); // 将JAR内部的默认值添加到内存中的配置对象
                messagesConfig.options().copyDefaults(true); // 如果messages.yml缺少键，则从默认值复制
                messagesConfig.save(messagesFile); // 将合并后的配置（包括新增的默认键）保存回messages.yml
            } else {
                plugin.getLogger().severe("Critical: Default messages file (" + defaultMessagesFileName + ") not found in JAR. This should not happen.");
            }
        } catch (IOException e) {
            plugin.getLogger().log(Level.SEVERE, "Could not save or load messages.yml with defaults.", e);
        }

        // 将所有消息加载到内存的 messages Map 中
        messages.clear();
        for (String key : messagesConfig.getKeys(true)) { // true表示获取所有深层嵌套的键
            if (messagesConfig.isString(key)) {
                messages.put(key, messagesConfig.getString(key));
            }
        }

        // 加载并设置插件前缀
        this.prefix = translateColors(getRawMessage("plugin-prefix", "&8[&eTowny&6Political&8] &r")); // 从加载后的messages Map获取
        plugin.getLogger().info("Messages loaded. Total messages: " + messages.size() + ". Prefix set to: '" + this.prefix + ChatColor.RESET + "'");
    }


    /**
     * 获取原始消息字符串，不进行颜色转换或占位符替换。
     * @param key 消息的键
     * @param defaultValue 如果找不到键，则返回的默认值
     * @return 消息字符串
     */
    public String getRawMessage(String key, String defaultValue) {
        return messages.getOrDefault(key, defaultValue);
    }

    /**
     * 获取格式化后的消息 (仅颜色代码转换)。
     * @param key 消息的键
     * @return 格式化后的消息。如果找不到键，返回一个包含键名的错误提示。
     */
    public String getMessage(String key) {
        String message = messages.get(key);
        if (message == null) {
            plugin.getLogger().warning("Missing message key: '" + key + "' in messages.yml. Please add it or run /<yourplugin> reload messages.");
            return translateColors("&c[MissingMsg: " + key + "]");
        }
        return translateColors(message);
    }

    /**
     * 获取格式化后的消息，并替换占位符。
     * @param key 消息的键
     * @param placeholders 占位符及其替换值，成对出现，例如 "player", playerName, "amount", amountValue
     * @return 格式化并替换占位符后的消息。如果找不到键，返回一个包含键名的错误提示。
     */
    public String getMessage(String key, Object... placeholders) {
        String messageFormat = messages.get(key);
        if (messageFormat == null) {
            plugin.getLogger().warning("Missing message key: '" + key + "' (with placeholders) in messages.yml.");
            return translateColors("&c[MissingMsg: " + key + "]");
        }
        if (placeholders.length % 2 != 0) {
            plugin.getLogger().warning("Invalid placeholders for message key: '" + key + "'. Must be key-value pairs. Placeholders array length: " + placeholders.length);
            return translateColors(messageFormat); // 返回未替换的但已颜色化的消息
        }
        String result = messageFormat;
        for (int i = 0; i < placeholders.length; i += 2) {
            if (placeholders[i] == null || placeholders[i+1] == null) {
                plugin.getLogger().finer("Placeholder key or value is null for message key: '" + key + "' at index " + i);
                continue;
            }
            result = result.replace("%" + placeholders[i].toString() + "%", placeholders[i+1].toString());
        }
        return translateColors(result);
    }

    /**
     * 发送带前缀的消息给玩家或控制台。
     * @param recipient 接收者
     * @param key 消息键
     */
    public void sendMessage(CommandSender recipient, String key) {
        if (recipient == null) return;
        recipient.sendMessage(this.prefix + getMessage(key));
    }

    /**
     * 发送带前缀的消息给玩家或控制台，并替换占位符。
     * @param recipient 接收者
     * @param key 消息键
     * @param placeholders 占位符
     */
    public void sendMessage(CommandSender recipient, String key, Object... placeholders) {
        if (recipient == null) return;
        recipient.sendMessage(this.prefix + getMessage(key, placeholders));
    }

    /**
     * 发送不带前缀的原始消息给玩家或控制台。
     * @param recipient 接收者
     * @param key 消息键
     */
    public void sendRawMessage(CommandSender recipient, String key) {
        if (recipient == null) return;
        recipient.sendMessage(getMessage(key));
    }

    /**
     * 发送不带前缀的原始消息给玩家或控制台，并替换占位符。
     * @param recipient 接收者
     * @param key 消息键
     * @param placeholders 占位符
     */
    public void sendRawMessage(CommandSender recipient, String key, Object... placeholders) {
        if (recipient == null) return;
        recipient.sendMessage(getMessage(key, placeholders));
    }

    /**
     * 发送ActionBar消息给玩家 (如果玩家在线且API支持)。
     * @param player 玩家
     * @param key 消息键
     * @param placeholders 占位符
     */
    public void sendActionBar(Player player, String key, Object... placeholders) {
        if (player == null || !player.isOnline()) return;
        try {
            // 使用 net.md_5.bungee.api.ChatMessageType 和 TextComponent
            player.spigot().sendMessage(ChatMessageType.ACTION_BAR,
                    TextComponent.fromLegacyText(getMessage(key, placeholders)));
        } catch (Throwable t) {
            // 如果 ActionBar 发送失败 (例如，旧版本服务器或API不兼容)，则降级为普通聊天消息
            sendMessage(player, key, placeholders);
            plugin.getLogger().finer("ActionBar not supported or failed to send, fell back to chat message: " + t.getMessage());
        }
    }


    /**
     * 转换字符串中的颜色代码，包括标准 & 代码和十六进制 &#RRGGBB 代码。
     * @param text 要转换的文本
     * @return 转换颜色后的文本，如果输入为null则返回空字符串。
     */
    public static String translateColors(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        // 处理 &#RRGGBB 格式的十六进制颜色
        Matcher matcher = HEX_PATTERN.matcher(text);
        StringBuffer buffer = new StringBuffer(text.length() + 4 * 8); // 预估长度
        while (matcher.find()) {
            String group = matcher.group(1); // RRGGBB 部分
            // 构建 ChatColor.COLOR_CHAR + "x" + (c_c + r) + (c_c + r) ... 结构
            matcher.appendReplacement(buffer, ChatColor.COLOR_CHAR + "x"
                    + ChatColor.COLOR_CHAR + group.charAt(0) + ChatColor.COLOR_CHAR + group.charAt(1)
                    + ChatColor.COLOR_CHAR + group.charAt(2) + ChatColor.COLOR_CHAR + group.charAt(3)
                    + ChatColor.COLOR_CHAR + group.charAt(4) + ChatColor.COLOR_CHAR + group.charAt(5)
            );
        }
        matcher.appendTail(buffer);

        // 处理传统的 & 颜色代码
        return ChatColor.translateAlternateColorCodes('&', buffer.toString());
    }

    /**
     * 获取插件消息的原始前缀字符串 (未转换颜色)。
     * @return 原始前缀
     */
    public String getRawPrefix() {
        return getRawMessage("plugin-prefix", "&8[&eTowny&6Political&8] &r"); // 直接从messages map取，若不存在则用硬编码默认值
    }

    /**
     * 获取经过颜色转换的插件消息前缀。
     * @return 带颜色的前缀字符串
     */
    public String getFormattedPrefix() {
        return this.prefix; // prefix 字段在 loadMessages 时已经转换过颜色了
    }


    /**
     * 重新加载消息文件。
     * 这会清空现有消息缓存，从文件重新加载，并合并默认值。
     */
    public void reloadMessages() {
        plugin.getLogger().info("Reloading messages...");
        loadMessages(); // 直接调用 loadMessages 即可，它包含了所有逻辑
        plugin.getLogger().info("Messages reloaded.");
    }
}