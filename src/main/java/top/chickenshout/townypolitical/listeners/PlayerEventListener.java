// 文件名: PlayerEventListener.java
// 结构位置: top/chickenshout/townypolitical/listeners/PlayerEventListener.java
package top.chickenshout.townypolitical.listeners;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import top.chickenshout.townypolitical.TownyPolitical;
import top.chickenshout.townypolitical.managers.PartyManager;
// import top.chickenshout.townypolitical.managers.SomeOtherManager; // If needed for other player events

/**
 * 监听通用的 Bukkit 玩家事件。
 * 例如，玩家加入/离开服务器，用于更新 PartyManager 中的玩家名称缓存等。
 */
public class PlayerEventListener implements Listener {

    private final TownyPolitical plugin;
    private final PartyManager partyManager;
    // private final SomeOtherManager someOtherManager;

    public PlayerEventListener(TownyPolitical plugin) {
        this.plugin = plugin;
        this.partyManager = plugin.getPartyManager();
        // this.someOtherManager = plugin.getSomeOtherManager();

        if (this.partyManager == null) {
            plugin.getLogger().severe("PlayerEventListener could not be initialized because PartyManager is null!");
            return;
        }

        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        plugin.getLogger().info("PlayerEventListener registered successfully.");
    }

    /**
     * 当玩家加入服务器时触发。
     * 主要用于通知 PartyManager 更新该玩家的名称缓存（如果该玩家是某政党成员）。
     * @param event PlayerJoinEvent 事件对象
     */
    @EventHandler(priority = EventPriority.MONITOR) // MONITOR 确保在其他插件（如权限插件）处理完后执行
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        plugin.getLogger().finer("[PlayerListener] Player " + player.getName() + " (UUID: " + player.getUniqueId() + ") joined the server.");

        // 通知 PartyManager 玩家上线
        if (partyManager != null) { // Double check, though constructor should prevent null
            partyManager.onPlayerJoinServer(player);
        }

        // 未来可扩展：
        // - 检查是否有待处理的政党邀请或选举通知，并发送给玩家。
        // - 加载该玩家特定的政治数据（如果不是全局加载的话）。
    }

    /**
     * 当玩家离开服务器时触发。
     * @param event PlayerQuitEvent 事件对象
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        plugin.getLogger().finer("[PlayerListener] Player " + player.getName() + " (UUID: " + player.getUniqueId() + ") left the server.");

        // 通知 PartyManager 玩家下线
        if (partyManager != null) {
            partyManager.onPlayerQuitServer(player);
        }

        // 未来可扩展：
        // - 清理与该玩家会话相关的缓存（例如，GUI打开状态等）。
    }
}