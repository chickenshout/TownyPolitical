// 文件名: VaultEconomyService.java
// 结构位置: top/chickenshout/townypolitical/economy/VaultEconomyService.java
package top.chickenshout.townypolitical.economy;

import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.economy.EconomyResponse;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin; // 仅用于类型提示，实际传入的是 TownyPolitical 实例
import top.chickenshout.townypolitical.TownyPolitical;


import java.util.UUID;
import java.util.logging.Level;

public class VaultEconomyService implements EconomyService {

    private Economy vaultEconomy = null;
    private final JavaPlugin plugin; // 使用 JavaPlugin 类型以保持一定的通用性，但实际传入的是 TownyPolitical

    public VaultEconomyService(JavaPlugin plugin) {
        if (plugin == null) {
            throw new IllegalArgumentException("Plugin instance cannot be null for VaultEconomyService.");
        }
        this.plugin = plugin;
    }

    @Override
    public boolean initialize() {
        if (plugin.getServer().getPluginManager().getPlugin("Vault") == null) {
            plugin.getLogger().warning("Vault plugin not found! Economy features via Vault will be disabled.");
            return false;
        }
        RegisteredServiceProvider<Economy> rsp = plugin.getServer().getServicesManager().getRegistration(Economy.class);
        if (rsp == null) {
            plugin.getLogger().warning("No economy plugin provider found through Vault! Economy features will be disabled.");
            return false;
        }
        vaultEconomy = rsp.getProvider();
        if (vaultEconomy != null) {
            plugin.getLogger().info("Successfully hooked into Vault economy provider: " + vaultEconomy.getName());
            return true;
        } else {
            // This case should ideally not be reached if rsp was not null, but as a safeguard.
            plugin.getLogger().warning("Failed to get Vault economy provider instance, though Vault is present. Economy features disabled.");
            return false;
        }
    }

    @Override
    public boolean isEnabled() {
        return vaultEconomy != null;
    }

    private OfflinePlayer getOfflinePlayerEnsured(UUID playerUniqueId) {
        if (playerUniqueId == null) {
            throw new IllegalArgumentException("Player UUID cannot be null.");
        }
        return Bukkit.getOfflinePlayer(playerUniqueId);
    }

    private void ensurePlayerArgNotNull(OfflinePlayer player) {
        if (player == null) {
            throw new IllegalArgumentException("OfflinePlayer cannot be null.");
        }
    }

    private void ensureAmountPositive(double amount) {
        if (amount <= 0) {
            throw new IllegalArgumentException("Amount must be positive.");
        }
    }


    @Override
    public double getBalance(UUID playerUniqueId) {
        if (!isEnabled()) return 0.0;
        OfflinePlayer player = getOfflinePlayerEnsured(playerUniqueId);
        return vaultEconomy.getBalance(player);
    }

    @Override
    public double getBalance(OfflinePlayer player) {
        ensurePlayerArgNotNull(player);
        if (!isEnabled()) return 0.0;
        return vaultEconomy.getBalance(player);
    }

    @Override
    public boolean hasEnough(UUID playerUniqueId, double amount) {
        if (amount < 0) throw new IllegalArgumentException("Amount cannot be negative.");
        if (!isEnabled()) return false; // If eco is off, assume they don't have enough for plugin operations
        OfflinePlayer player = getOfflinePlayerEnsured(playerUniqueId);
        return vaultEconomy.has(player, amount);
    }

    @Override
    public boolean hasEnough(OfflinePlayer player, double amount) {
        ensurePlayerArgNotNull(player);
        if (amount < 0) throw new IllegalArgumentException("Amount cannot be negative.");
        if (!isEnabled()) return false;
        return vaultEconomy.has(player, amount);
    }

    @Override
    public boolean withdraw(UUID playerUniqueId, double amount) {
        ensureAmountPositive(amount);
        if (!isEnabled()) return false;
        OfflinePlayer player = getOfflinePlayerEnsured(playerUniqueId);
        if (!createPlayerAccount(player)) { // Ensure account exists
            plugin.getLogger().warning("Failed to ensure Vault account exists for " + player.getName() + " (UUID: " + player.getUniqueId() + ") during withdrawal attempt.");
            return false;
        }
        EconomyResponse response = vaultEconomy.withdrawPlayer(player, amount);
        if (!response.transactionSuccess()) {
            plugin.getLogger().finer("Vault withdrawal failed for " + player.getName() + " (" + amount + "): " + response.errorMessage);
        }
        return response.transactionSuccess();
    }

    @Override
    public boolean withdraw(OfflinePlayer player, double amount) {
        ensurePlayerArgNotNull(player);
        ensureAmountPositive(amount);
        if (!isEnabled()) return false;
        if (!createPlayerAccount(player)) {
            plugin.getLogger().warning("Failed to ensure Vault account exists for " + player.getName() + " during withdrawal attempt.");
            return false;
        }
        EconomyResponse response = vaultEconomy.withdrawPlayer(player, amount);
        if (!response.transactionSuccess()) {
            plugin.getLogger().finer("Vault withdrawal failed for " + player.getName() + " (" + amount + "): " + response.errorMessage);
        }
        return response.transactionSuccess();
    }

    @Override
    public boolean deposit(UUID playerUniqueId, double amount) {
        ensureAmountPositive(amount);
        if (!isEnabled()) return false;
        OfflinePlayer player = getOfflinePlayerEnsured(playerUniqueId);
        if (!createPlayerAccount(player)) {
            plugin.getLogger().warning("Failed to ensure Vault account exists for " + player.getName() + " (UUID: " + player.getUniqueId() + ") during deposit attempt.");
            return false;
        }
        EconomyResponse response = vaultEconomy.depositPlayer(player, amount);
        if (!response.transactionSuccess()) {
            plugin.getLogger().finer("Vault deposit failed for " + player.getName() + " (" + amount + "): " + response.errorMessage);
        }
        return response.transactionSuccess();
    }

    @Override
    public boolean deposit(OfflinePlayer player, double amount) {
        ensurePlayerArgNotNull(player);
        ensureAmountPositive(amount);
        if (!isEnabled()) return false;
        if (!createPlayerAccount(player)) {
            plugin.getLogger().warning("Failed to ensure Vault account exists for " + player.getName() + " during deposit attempt.");
            return false;
        }
        EconomyResponse response = vaultEconomy.depositPlayer(player, amount);
        if (!response.transactionSuccess()) {
            plugin.getLogger().finer("Vault deposit failed for " + player.getName() + " (" + amount + "): " + response.errorMessage);
        }
        return response.transactionSuccess();
    }

    @Override
    public String getCurrencyNameSingular() {
        if (!isEnabled() || vaultEconomy.currencyNameSingular() == null || vaultEconomy.currencyNameSingular().isEmpty()) {
            return "货币"; // Default fallback
        }
        return vaultEconomy.currencyNameSingular();
    }

    @Override
    public String getCurrencyNamePlural() {
        if (!isEnabled() || vaultEconomy.currencyNamePlural() == null || vaultEconomy.currencyNamePlural().isEmpty()) {
            return "货币"; // Default fallback
        }
        return vaultEconomy.currencyNamePlural();
    }

    @Override
    public String format(double amount) {
        if (!isEnabled()) {
            return String.format("%.2f", amount); // Simple format if Vault is not available
        }
        try {
            return vaultEconomy.format(amount);
        } catch (AbstractMethodError ame) { // Some very old/broken eco plugins might not implement this
            plugin.getLogger().log(Level.WARNING, "The hooked economy plugin ("+vaultEconomy.getName()+") does not properly implement currency formatting. Using default.", ame);
            return String.format("%.2f %s", amount, getCurrencyNamePlural());
        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "Error formatting currency from Vault economy provider: " + vaultEconomy.getName(), e);
            return String.format("%.2f %s", amount, getCurrencyNamePlural()); // Fallback formatting
        }
    }

    @Override
    public boolean createPlayerAccount(UUID playerUniqueId) {
        if (!isEnabled()) return false;
        return createPlayerAccount(getOfflinePlayerEnsured(playerUniqueId)); // 调用下面的修正方法
    }

    @Override
    public boolean createPlayerAccount(OfflinePlayer player) {
        ensurePlayerArgNotNull(player);
        if (!isEnabled()) return false;

        // 如果账户已存在，则认为创建是“成功”的，因为目标已达到
        if (vaultEconomy.hasAccount(player)) {
            return true;
        }

        // 调用 Vault API 的 createPlayerAccount，它返回 boolean
        boolean success = vaultEconomy.createPlayerAccount(player);

        if (success) {
            return true;
        } else {
            // 如果创建失败，再检查一次账户是否真的不存在（有些插件可能在失败时也可能创建了账户，或者返回false表示已存在）
            if (vaultEconomy.hasAccount(player)) {
                // plugin.getLogger().finer("Vault's createPlayerAccount for " + player.getName() + " returned false, but account already exists. Considering it a success.");
                return true; // 如果账户已经存在，即使create返回false，我们也认为操作目的达到了
            }
            plugin.getLogger().finer("Failed to create Vault account for " + player.getName() + " (UUID: " + player.getUniqueId() + ") using vaultEconomy.createPlayerAccount(). It returned false and account does not exist.");
            return false;
        }
    }

    @Override
    public String getProviderName() {
        if (vaultEconomy != null) {
            return "Vault (via " + vaultEconomy.getName() + ")";
        }
        if (plugin.getServer().getPluginManager().getPlugin("Vault") != null) {
            return "Vault (No Provider Hooked)";
        }
        return "Vault (Not Found)";
    }

    /**
     * (Internal use for debugging or specific cases) Gets the raw Vault Economy object.
     * This should NOT be regularly used by other parts of the plugin to maintain abstraction.
     * @return The raw Economy object, or null if not initialized.
     */
    public Economy getRawVaultEconomy() {
        return vaultEconomy;
    }


}