// 文件名: TownyHookListener.java
// 结构位置: top/chickenshout/townypolitical/listeners/TownyHookListener.java
package top.chickenshout.townypolitical.listeners;

import com.palmergames.bukkit.towny.TownyAPI;
import com.palmergames.bukkit.towny.event.DeleteNationEvent;
import com.palmergames.bukkit.towny.event.NewNationEvent;
import com.palmergames.bukkit.towny.event.RenameNationEvent;
// 使用你提供的更准确的居民事件
import com.palmergames.bukkit.towny.event.TownAddResidentEvent;
import com.palmergames.bukkit.towny.event.TownRemoveResidentEvent;

import com.palmergames.bukkit.towny.exceptions.NotRegisteredException;
import com.palmergames.bukkit.towny.object.Nation;
import com.palmergames.bukkit.towny.object.Resident;
import com.palmergames.bukkit.towny.object.Town;

import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import top.chickenshout.townypolitical.TownyPolitical;
import top.chickenshout.townypolitical.managers.ElectionManager;
import top.chickenshout.townypolitical.managers.NationManager;

import java.util.UUID;
import java.util.logging.Level;

public class TownyHookListener implements Listener {

    private final TownyPolitical plugin;
    private final NationManager nationManager;
    private final ElectionManager electionManager;

    public TownyHookListener(TownyPolitical plugin) {
        this.plugin = plugin;
        this.nationManager = plugin.getNationManager();
        this.electionManager = plugin.getElectionManager();

        if (this.nationManager == null || this.electionManager == null) {
            plugin.getLogger().severe("TownyHookListener could not be initialized: NationManager or ElectionManager is null!");
            return;
        }

        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        plugin.getLogger().info("TownyHookListener registered successfully.");
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onNewNation(NewNationEvent event) {
        Nation nation = event.getNation();
        if (nation != null) {
            plugin.getLogger().info("[TownyHook] New nation created: " + nation.getName() + " (UUID: " + nation.getUUID() + ")");
            nationManager.onNationCreate(nation);
            electionManager.scheduleNextElectionForNation(nation.getUUID());
        } else {
            plugin.getLogger().warning("[TownyHook] NewNationEvent triggered with a null nation object.");
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDeleteNation(DeleteNationEvent event) {
        UUID nationUUID = event.getNationUUID();
        String nationName = event.getNationName();
        if (nationUUID != null) {
            plugin.getLogger().info("[TownyHook] Nation deleted: " + nationName + " (UUID: " + nationUUID + ")");
            nationManager.onNationDelete(nationUUID);
            electionManager.onNationDeleted(nationUUID);
        } else {
            plugin.getLogger().warning("[TownyHook] DeleteNationEvent triggered with a null nation UUID for nation name: " + nationName);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onRenameNation(RenameNationEvent event) {
        Nation nation = event.getNation();
        String oldName = event.getOldName();
        if (nation != null) {
            plugin.getLogger().info("[TownyHook] Nation '" + oldName + "' (UUID: " + nation.getUUID() + ") renamed to '" + nation.getName() + "'. Political data is UUID-based.");
        } else {
            plugin.getLogger().warning("[TownyHook] RenameNationEvent triggered with a null nation object for old name: " + oldName);
        }
    }

    /**
     * 当一个玩家被添加为一个城镇的居民时触发。
     * 这直接关系到该玩家是否成为该城镇所属国家的公民。
     * @param event TownAddResidentEvent 事件对象
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTownAddResident(TownAddResidentEvent event) {
        Resident resident = event.getResident();
        Town town = event.getTown();
        try {
            if (town.hasNation()) {
                Nation nation = town.getNation();
                plugin.getLogger().info("[TownyHook] Player " + resident.getName() + " added to town " + town.getName() +
                        ", becoming a citizen of nation " + nation.getName() + ". Political eligibility might change.");
                // 如果需要，可以在此通知 ElectionManager 或其他模块公民身份的变化
                // electionManager.onCitizenAddedToNation(resident, nation);
            } else {
                plugin.getLogger().finer("[TownyHook] Player " + resident.getName() + " added to town " + town.getName() + " (town is not in a nation).");
            }
        } catch (NotRegisteredException e) {
            plugin.getLogger().log(Level.WARNING, "[TownyHook] Error processing TownAddResidentEvent for " + resident.getName() + " in town " + town.getName() + ": " + e.getMessage(), e);
        }
    }

    /**
     * 当一个玩家从一个城镇的居民中被移除时触发。
     * 这直接关系到该玩家是否失去该城镇所属国家的公民身份。
     * @param event TownRemoveResidentEvent 事件对象
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTownRemoveResident(TownRemoveResidentEvent event) {
        Resident resident = event.getResident();
        Town town = event.getTown(); // 在此事件中，town 对象仍然有效，代表玩家离开的那个城镇
        try {
            // 检查该城镇在玩家被移除时是否属于一个国家
            if (town.hasNation()) {
                Nation nation = town.getNation(); // 获取该城镇当前所属的国家
                plugin.getLogger().info("[TownyHook] Player " + resident.getName() + " removed from town " + town.getName() +
                        ", potentially losing citizenship of nation " + nation.getName() + ". Political eligibility might change.");
                // electionManager.onCitizenRemovedFromNation(resident, nation);
            } else {
                plugin.getLogger().finer("[TownyHook] Player " + resident.getName() + " removed from town " + town.getName() + " (town was not in a nation).");
            }
        } catch (NotRegisteredException e) {
            // 这种情况可能发生，例如，如果国家在玩家离开城镇的同一tick被删除。
            plugin.getLogger().log(Level.WARNING, "[TownyHook] Error processing TownRemoveResidentEvent for " + resident.getName() + " from town " + town.getName() + " (Nation or Town might have been unregistered): " + e.getMessage());
        } catch (NullPointerException npe) {
            plugin.getLogger().log(Level.SEVERE, "[TownyHook] Critical error: Town object became null during TownRemoveResidentEvent for resident " + resident.getName(), npe);
        }
    }
}