// 文件名: NationManager.java
// 结构位置: top/chickenshout/townypolitical/managers/NationManager.java
package top.chickenshout.townypolitical.managers;

import com.palmergames.bukkit.towny.Towny;
import com.palmergames.bukkit.towny.TownyAPI; // 正确的API导入
import com.palmergames.bukkit.towny.exceptions.NotRegisteredException;
import com.palmergames.bukkit.towny.exceptions.TownyException; // 确保这个导入正确
import com.palmergames.bukkit.towny.object.Nation;
import com.palmergames.bukkit.towny.object.Resident;
import com.palmergames.bukkit.towny.object.economy.Account; // Towny 银行账户对象

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import top.chickenshout.townypolitical.TownyPolitical;
import top.chickenshout.townypolitical.data.NationPolitics;
import top.chickenshout.townypolitical.economy.EconomyService;
import top.chickenshout.townypolitical.enums.ElectionType;
import top.chickenshout.townypolitical.enums.GovernmentType;
import top.chickenshout.townypolitical.utils.MessageManager;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.stream.Collectors;
// List, ArrayList, Optional, HashMap, Collectors, ConcurrentHashMap, Level 已在 PartyManager 中确认导入
// 这里根据实际使用再确认

public class NationManager {
    private final TownyPolitical plugin;
    private final MessageManager messageManager;
    private final EconomyService economyService;

    private final Map<UUID, NationPolitics> nationPoliticsMap;
    private final File nationsDataFolder;
    private static final String NATION_POLITICS_FILE_EXTENSION = ".yml";

    public NationManager(TownyPolitical plugin) {
        this.plugin = plugin;
        this.messageManager = plugin.getMessageManager();
        this.economyService = plugin.getEconomyService();

        this.nationPoliticsMap = new ConcurrentHashMap<>();

        this.nationsDataFolder = new File(plugin.getDataFolder(), "nations_politics");
        if (!nationsDataFolder.exists()) {
            if (!nationsDataFolder.mkdirs()) {
                plugin.getLogger().severe("Could not create nations_politics data folder!");
            }
        }
        loadNationPoliticsData();
    }

    public NationPolitics getNationPolitics(UUID nationUUID) {
        if (nationUUID == null) {
            plugin.getLogger().warning("getNationPolitics called with null nationUUID.");
            return null;
        }
        return nationPoliticsMap.computeIfAbsent(nationUUID, uuid -> {
            plugin.getLogger().info("No existing politics data for nation " + uuid + ". Creating default entry.");
            NationPolitics newNationPolitics = new NationPolitics(uuid);
            saveNationPolitics(newNationPolitics);
            return newNationPolitics;
        });
    }

    public NationPolitics getNationPolitics(Nation nation) {
        if (nation == null) {
            plugin.getLogger().warning("getNationPolitics called with null Nation object.");
            return null;
        }
        return getNationPolitics(nation.getUUID());
    }

    public boolean setGovernmentType(Nation nation, GovernmentType newGovType, OfflinePlayer initiator) {
        if (nation == null || newGovType == null || initiator == null) {
            plugin.getLogger().warning("setGovernmentType called with null parameters. Nation: " + nation + ", NewGovType: " + newGovType + ", Initiator: " + initiator);
            return false;
        }

        NationPolitics politics = getNationPolitics(nation);
        if (politics == null) {
            plugin.getLogger().severe("Failed to get/create NationPolitics for nation " + nation.getName() + " during setGovernmentType.");
            return false;
        }

        Resident townyResident = TownyAPI.getInstance().getResident(initiator.getUniqueId());

        if (townyResident == null || !nation.isKing(townyResident)) {
            if (initiator.isOnline() && initiator.getPlayer() != null) {
                messageManager.sendMessage(initiator.getPlayer(), "nation-set-government-fail-not-leader");
            }
            return false;
        }

        if (politics.getGovernmentType() == newGovType) {
            if (initiator.isOnline() && initiator.getPlayer() != null) {
                messageManager.sendMessage(initiator.getPlayer(), "nation-set-government-fail-same",
                        "nation_name", nation.getName(),
                        "government_type", newGovType.getDisplayName());
            }
            return false;
        }

        double cost = plugin.getConfig().getDouble("nation.government_change_cost." + newGovType.name().toLowerCase(),
                plugin.getConfig().getDouble("nation.government_change_cost.default", 2500.0));

        if (plugin.getConfig().getBoolean("economy.use_towny_nation_bank", true) && cost > 0) {
            // 移除了 isTownyEconomyEnabled() 检查，直接尝试操作
            Account nationAccount = nation.getAccount(); // TownyAPI.getInstance().getNationAccount(nation) 不存在
            if (!nationAccount.canPayFromHoldings(cost)) {
                if (initiator.isOnline() && initiator.getPlayer() != null) {
                    messageManager.sendMessage(initiator.getPlayer(), "error-nation-not-enough-money",
                            "amount", economyService.format(cost));
                }
                return false;
            }
            nationAccount.withdraw(cost, "TownyPolitical: Government Change to " + newGovType.getDisplayName());
        } else if (economyService.isEnabled() && cost > 0) {
            if (!economyService.hasEnough(initiator.getUniqueId(), cost)) {
                if (initiator.isOnline() && initiator.getPlayer() != null) messageManager.sendMessage(initiator.getPlayer(), "error-not-enough-money", "amount", economyService.format(cost));
                return false;
            }
            if (!economyService.withdraw(initiator.getUniqueId(), cost)) {
                if (initiator.isOnline() && initiator.getPlayer() != null) messageManager.sendMessage(initiator.getPlayer(), "error-economy-transaction-failed", "action", "更改政体 (个人账户)");
                return false;
            }
        }

        GovernmentType oldGovType = politics.getGovernmentType();
        politics.setGovernmentType(newGovType);
        politics.clearAllElectionCompletionTimes();
        politics.setPrimeMinisterUUID(null);
        if (newGovType != GovernmentType.CONSTITUTIONAL_MONARCHY) {
            politics.setTitularMonarchUUID(null);
        }
        saveNationPolitics(politics);

        if (initiator.isOnline() && initiator.getPlayer() != null) {
            messageManager.sendMessage(initiator.getPlayer(), "nation-set-government-success",
                    "nation_name", nation.getName(),
                    "government_type", newGovType.getDisplayName());
            if (cost > 0) {
                messageManager.sendMessage(initiator.getPlayer(), "nation-set-government-cost-paid",
                        "amount", economyService.format(cost));
            }
        }

        String broadcastKey = "nation-government-changed-broadcast";
        if (newGovType == GovernmentType.ABSOLUTE_MONARCHY) {
            broadcastKey = "nation-set-government-absolute-monarchy-warning";
        }
        // 修正广播消息的构建
        String formattedBroadcastMessage = messageManager.getFormattedPrefix() +
                messageManager.getMessage(broadcastKey,
                        "nation_name", nation.getName(),
                        "new_type", newGovType.getDisplayName(),
                        "old_type", oldGovType.getDisplayName()
                );
        Bukkit.broadcastMessage(formattedBroadcastMessage);

        ElectionManager em = plugin.getElectionManager(); // <--- 动态获取
        if (em != null) {
            em.onGovernmentChange(nation, oldGovType, newGovType);
        } else {
            plugin.getLogger().severe("[NationManager] Critical: ElectionManager was null when trying to notify onGovernmentChange for nation: " + nation.getName());
        }
        return true;
    }

    public Collection<NationPolitics> getAllNationPolitics() {
        return Collections.unmodifiableCollection(nationPoliticsMap.values());
    }

    public void loadNationPoliticsData() {
        nationPoliticsMap.clear();
        if (!nationsDataFolder.exists()) {
            plugin.getLogger().info("Nations_politics data folder not found. No nation politics data loaded.");
            return;
        }

        File[] nationFiles = nationsDataFolder.listFiles((dir, name) -> name.toLowerCase().endsWith(NATION_POLITICS_FILE_EXTENSION));
        if (nationFiles == null || nationFiles.length == 0) {
            plugin.getLogger().info("No nation politics data files found.");
            return;
        }

        for (File nationFile : nationFiles) {
            YamlConfiguration nationConfig = new YamlConfiguration();
            try {
                nationConfig.load(nationFile); // Ensure this is used for loading
                UUID nationUUID = UUID.fromString(nationConfig.getString("nationUUID"));
                GovernmentType governmentType = GovernmentType.fromString(nationConfig.getString("governmentType", GovernmentType.PARLIAMENTARY_REPUBLIC.name()))
                        .orElse(GovernmentType.PARLIAMENTARY_REPUBLIC);

                NationPolitics politics = new NationPolitics(nationUUID, governmentType);

                if (nationConfig.isConfigurationSection("lastElectionCompletionTimes")) {
                    ConfigurationSection timesSection = nationConfig.getConfigurationSection("lastElectionCompletionTimes");
                    for (String typeStr : timesSection.getKeys(false)) {
                        try {
                            ElectionType electionType = ElectionType.fromString(typeStr).orElse(null);
                            if (electionType == ElectionType.PARLIAMENTARY || electionType == ElectionType.PRESIDENTIAL) {
                                politics.setLastElectionCompletionTime(electionType, timesSection.getLong(typeStr));
                            }
                        } catch (IllegalArgumentException e) {
                            plugin.getLogger().warning("Skipping invalid ElectionType '" + typeStr + "' in lastElectionCompletionTimes for nation " + nationUUID + " from file " + nationFile.getName());
                        }
                    }
                }
                if (nationConfig.contains("titularMonarchUUID") && nationConfig.getString("titularMonarchUUID") != null  && !nationConfig.getString("titularMonarchUUID").isEmpty()) {
                    try { politics.setTitularMonarchUUID(UUID.fromString(nationConfig.getString("titularMonarchUUID"))); } catch (IllegalArgumentException e) {  plugin.getLogger().warning("Skipping invalid titularMonarchUUID for nation " + nationUUID + " from file " + nationFile.getName());}
                }
                if (nationConfig.contains("primeMinisterUUID") && nationConfig.getString("primeMinisterUUID") != null && !nationConfig.getString("primeMinisterUUID").isEmpty()) {
                    try { politics.setPrimeMinisterUUID(UUID.fromString(nationConfig.getString("primeMinisterUUID"))); } catch (IllegalArgumentException e) {  plugin.getLogger().warning("Skipping invalid primeMinisterUUID for nation " + nationUUID + " from file " + nationFile.getName());}
                }

                if (nationConfig.isConfigurationSection("parliamentarySeatsWon")) { // 旧的席位分配结果
                    ConfigurationSection seatsWonSection = nationConfig.getConfigurationSection("parliamentarySeatsWon");
                    Map<UUID, Integer> seatsWon = new HashMap<>();
                    for (String partyUuidStr : seatsWonSection.getKeys(false)) {
                        try {
                            seatsWon.put(UUID.fromString(partyUuidStr), seatsWonSection.getInt(partyUuidStr));
                        } catch (IllegalArgumentException e) { /* log */ }
                    }
                    politics.setParliamentarySeatsWonByParty(seatsWon);
                }
                if (nationConfig.isConfigurationSection("parliamentaryMembers")) { // 任命的议员
                    ConfigurationSection membersSection = nationConfig.getConfigurationSection("parliamentaryMembers");
                    for (String partyUuidStr : membersSection.getKeys(false)) {
                        try {
                            UUID partyUUID = UUID.fromString(partyUuidStr);
                            List<UUID> mpList = membersSection.getStringList(partyUuidStr).stream()
                                    .map(UUID::fromString)
                                    .collect(Collectors.toList());
                            politics.getParliamentaryMembersByPartyInternal().put(partyUUID, mpList); // 直接放入
                        } catch (IllegalArgumentException e) {
                            plugin.getLogger().warning("Skipping invalid parliamentary member entry for party " + partyUuidStr + " in nation " + nationUUID + " from file " + nationFile.getName());
                        }
                    }
                }

                nationPoliticsMap.put(nationUUID, politics);
            } catch (IOException | InvalidConfigurationException | IllegalArgumentException e) {
                plugin.getLogger().log(Level.SEVERE, "Failed to load nation politics from file: " + nationFile.getName(), e);
                moveCorruptedFile(nationFile, "nation_politics_");
            }
        }
        plugin.getLogger().info("Loaded politics data for " + nationPoliticsMap.size() + " nations.");

        if (TownyAPI.getInstance() != null) {
            for (Nation nation : TownyAPI.getInstance().getNations()) { // Can throw NotRegisteredException if Towny is not fully loaded
                getNationPolitics(nation.getUUID());
            }
            plugin.getLogger().info("Synchronized nation politics data with current Towny nations.");
        }


    }

    public void saveNationPolitics(NationPolitics politics) {
        if (politics == null) return;
        File nationFile = new File(nationsDataFolder, politics.getNationUUID().toString() + NATION_POLITICS_FILE_EXTENSION);
        YamlConfiguration nationConfig = new YamlConfiguration();

        nationConfig.set("nationUUID", politics.getNationUUID().toString());
        nationConfig.set("governmentType", politics.getGovernmentType().name());

        ConfigurationSection timesSection = nationConfig.createSection("lastElectionCompletionTimes");
        // **修正**: 假设 NationPolitics 有一个方法 getRawLastElectionCompletionTimes() 返回原始Map
        // 或者确保 lastElectionCompletionTimes 字段不是 private
        // 为了代码通过，假设我们修改 NationPolitics.java 添加:
        // public Map<ElectionType, Long> getInternalLastElectionCompletionTimes() { return this.lastElectionCompletionTimes; }
        // 另一种方式是在NationPolitics中提供一个forEach 方法
        if (politics.getLastElectionCompletionTimesEntries() != null) {
            for (Map.Entry<ElectionType, Long> entry : politics.getLastElectionCompletionTimesEntries()) {
                timesSection.set(entry.getKey().name(), entry.getValue());
            }
        }


        politics.getTitularMonarchUUID().ifPresent(uuid -> nationConfig.set("titularMonarchUUID", uuid.toString()));
        politics.getPrimeMinisterUUID().ifPresent(uuid -> nationConfig.set("primeMinisterUUID", uuid.toString()));
        if (!politics.getTitularMonarchUUID().isPresent()) nationConfig.set("titularMonarchUUID", null);
        if (!politics.getPrimeMinisterUUID().isPresent()) nationConfig.set("primeMinisterUUID", null);
        if (politics.getParliamentarySeatsWonByPartyInternal() != null && !politics.getParliamentarySeatsWonByPartyInternal().isEmpty()) {
            ConfigurationSection seatsWonSection = nationConfig.createSection("parliamentarySeatsWon");
            politics.getParliamentarySeatsWonByPartyInternal().forEach((partyId, seats) -> seatsWonSection.set(partyId.toString(), seats));
        } else {
            nationConfig.set("parliamentarySeatsWon", null); // 确保移除旧数据
        }
        if (politics.getParliamentaryMembersByPartyInternal() != null && !politics.getParliamentaryMembersByPartyInternal().isEmpty()) {
            ConfigurationSection membersSection = nationConfig.createSection("parliamentaryMembers");
            politics.getParliamentaryMembersByPartyInternal().forEach((partyId, mpList) -> {
                if (mpList != null && !mpList.isEmpty()) {
                    membersSection.set(partyId.toString(), mpList.stream().map(UUID::toString).collect(Collectors.toList()));
                }
            });
        } else {
            nationConfig.set("parliamentaryMembers", null); // 确保移除旧数据
        }

        try {
            nationConfig.save(nationFile);
        } catch (IOException e) {
            plugin.getLogger().log(Level.SEVERE, "Could not save nation politics data for UUID: " + politics.getNationUUID(), e);
        }
    }

    private void moveCorruptedFile(File file, String prefix) {
        if (file == null || !file.exists()) return;
        File corruptedFolder = new File(nationsDataFolder.getParentFile(), "corrupted_data");
        if (!corruptedFolder.exists()) corruptedFolder.mkdirs();
        File newFile = new File(corruptedFolder, prefix + file.getName() + "_" + System.currentTimeMillis() + ".yml_disabled");
        try {
            Files.move(file.toPath(), newFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
            plugin.getLogger().warning("Moved corrupted file " + file.getName() + " to " + newFile.getPath());
        } catch (IOException ex) {
            plugin.getLogger().log(Level.SEVERE, "Could not move corrupted file " + file.getName(), ex);
        }
    }


    public void saveAllNationPoliticsData() {
        plugin.getLogger().info("Saving politics data for all " + nationPoliticsMap.size() + " nations...");
        for (NationPolitics politics : nationPoliticsMap.values()) {
            saveNationPolitics(politics);
        }
        plugin.getLogger().info("All nation politics data saved.");
    }

    public void onNationCreate(Nation nation) {
        if (nation == null) return;
        plugin.getLogger().info("Towny nation created: " + nation.getName() + ". Initializing political data...");
        getNationPolitics(nation.getUUID());
    }

    public void onNationDelete(UUID nationUUID) {
        if (nationUUID == null) return;
        plugin.getLogger().info("Towny nation with UUID " + nationUUID + " deleted. Removing political data...");
        NationPolitics removed = nationPoliticsMap.remove(nationUUID);
        if (removed != null) {
            File nationFile = new File(nationsDataFolder, nationUUID.toString() + NATION_POLITICS_FILE_EXTENSION);
            if (nationFile.exists()) {
                if (!nationFile.delete()) {
                    plugin.getLogger().warning("Could not delete nation politics data file: " + nationFile.getName());
                } else {
                    plugin.getLogger().info("Successfully deleted nation politics data file for: " + nationUUID);
                }
            }
        }
        ElectionManager em = plugin.getElectionManager(); // <--- 动态获取
        if (em != null) {
            em.onNationDeleted(nationUUID);
        } else {
            plugin.getLogger().severe("[NationManager] Critical: ElectionManager was null when trying to notify onNationDeleted for UUID: " + nationUUID);
        }
    }

    public void reloadNationConfigAndData() {
        loadNationPoliticsData();
        plugin.getLogger().info("Nation politics data reloaded.");
    }

    public void shutdown() {
        saveAllNationPoliticsData();
    }

    public double applyGovernmentCostModifier(Nation nation, double baseCost) {
        if (nation == null) return baseCost;
        NationPolitics politics = getNationPolitics(nation.getUUID());
        if (politics != null && politics.getGovernmentType().isAbsoluteMonarchy()) { // 使用 isAbsoluteMonarchy()
            double multiplier = plugin.getConfig().getDouble("nation.absolute_monarchy.cost_multiplier", 1.25);
            return baseCost * multiplier;
        }
        return baseCost;
    }
}