// 文件名: EconomyService.java
// 结构位置: top/chickenshout/townypolitical/economy/EconomyService.java
package top.chickenshout.townypolitical.economy;

import org.bukkit.OfflinePlayer; // Bukkit API for player context

import java.util.UUID;

/**
 * 经济服务接口。
 * 定义了插件与服务器经济系统交互所需的核心方法。
 * 具体的实现将由例如 VaultEconomyService 提供，以适配不同的经济插件。
 */
public interface EconomyService {

    /**
     * 初始化经济服务。
     * 通常在插件启用时调用，用于挂钩到实际的经济插件。
     * @return 如果成功初始化并连接到经济插件，则返回 true，否则返回 false。
     */
    boolean initialize();

    /**
     * 检查经济服务是否已成功初始化并可用。
     * @return 如果可用则返回 true，否则返回 false。
     */
    boolean isEnabled();

    /**
     * 获取指定玩家的余额。
     * @param playerUniqueId 玩家的UUID。
     * @return 玩家的余额；如果玩家账户不存在或发生错误，通常返回0.0。
     * @throws IllegalArgumentException 如果 playerUniqueId 为 null。
     */
    double getBalance(UUID playerUniqueId);

    /**
     * 获取指定玩家的余额。
     * @param player OfflinePlayer 对象，代表一个玩家（可能在线也可能不在线）。
     * @return 玩家的余额；如果玩家账户不存在或发生错误，通常返回0.0。
     * @throws IllegalArgumentException 如果 player 为 null。
     */
    double getBalance(OfflinePlayer player);

    /**
     * 检查指定玩家是否有足够的资金。
     * @param playerUniqueId 玩家的UUID。
     * @param amount 需要检查的金额。
     * @return 如果玩家有足够的资金，则返回 true；否则返回 false。
     * @throws IllegalArgumentException 如果 playerUniqueId 为 null 或 amount 小于0。
     */
    boolean hasEnough(UUID playerUniqueId, double amount);

    /**
     * 检查指定玩家是否有足够的资金。
     * @param player OfflinePlayer 对象。
     * @param amount 需要检查的金额。
     * @return 如果玩家有足够的资金，则返回 true；否则返回 false。
     * @throws IllegalArgumentException 如果 player 为 null 或 amount 小于0。
     */
    boolean hasEnough(OfflinePlayer player, double amount);

    /**
     * 从指定玩家账户中取款。
     * @param playerUniqueId 玩家的UUID。
     * @param amount 要取款的金额 (必须大于0)。
     * @return 如果交易成功，则返回 true；否则返回 false。
     * @throws IllegalArgumentException 如果 playerUniqueId 为 null 或 amount 小于等于0。
     */
    boolean withdraw(UUID playerUniqueId, double amount);

    /**
     * 从指定玩家账户中取款。
     * @param player OfflinePlayer 对象。
     * @param amount 要取款的金额 (必须大于0)。
     * @return 如果交易成功，则返回 true；否则返回 false。
     * @throws IllegalArgumentException 如果 player 为 null 或 amount 小于等于0。
     */
    boolean withdraw(OfflinePlayer player, double amount);

    /**
     * 向指定玩家账户存款。
     * @param playerUniqueId 玩家的UUID。
     * @param amount 要存款的金额 (必须大于0)。
     * @return 如果交易成功，则返回 true；否则返回 false。
     * @throws IllegalArgumentException 如果 playerUniqueId 为 null 或 amount 小于等于0。
     */
    boolean deposit(UUID playerUniqueId, double amount);

    /**
     * 向指定玩家账户存款。
     * @param player OfflinePlayer 对象。
     * @param amount 要存款的金额 (必须大于0)。
     * @return 如果交易成功，则返回 true；否则返回 false。
     * @throws IllegalArgumentException 如果 player 为 null 或 amount 小于等于0。
     */
    boolean deposit(OfflinePlayer player, double amount);

    /**
     * 获取货币名称的单数形式 (例如 "Dollar", "金币")。
     * @return 货币单数名称；如果经济系统未启用或无法获取，则返回一个默认值。
     */
    String getCurrencyNameSingular();

    /**
     * 获取货币名称的复数形式 (例如 "Dollars", "金币")。
     * @return 货币复数名称；如果经济系统未启用或无法获取，则返回一个默认值。
     */
    String getCurrencyNamePlural();

    /**
     * 格式化给定的货币金额，通常会添加货币符号和适当的小数位数。
     * (例如, 1234.5 -> "$1,234.50" 或 "1234.50 金币")
     * @param amount 要格式化的金额。
     * @return 格式化后的货币字符串；如果经济系统未启用，则返回金额的简单字符串形式。
     */
    String format(double amount);

    /**
     * 尝试为指定玩家创建一个经济账户（如果账户尚不存在且经济插件支持此操作）。
     * 许多经济插件会在首次交易时自动创建账户。
     * @param playerUniqueId 玩家的UUID。
     * @return 如果账户已存在或成功创建，则返回true；否则返回 false。
     * @throws IllegalArgumentException 如果 playerUniqueId 为 null。
     */
    boolean createPlayerAccount(UUID playerUniqueId);

    /**
     * 尝试为指定玩家创建一个经济账户。
     * @param player OfflinePlayer 对象。
     * @return 如果账户已存在或成功创建，则返回true；否则返回 false。
     * @throws IllegalArgumentException 如果 player 为 null。
     */
    boolean createPlayerAccount(OfflinePlayer player);

    /**
     * 获取当前经济服务提供者的名称。
     * 例如 "Vault (via Essentials Economy)", "TheNewEconomy", 等。
     * @return 经济提供者的名称，如果未初始化或无法确定则返回一个默认字符串。
     */
    String getProviderName();
}