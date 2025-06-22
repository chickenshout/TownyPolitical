// 文件名: BillManager.java
// 结构位置: top/chickenshout/townypolitical/managers/BillManager.java
package top.chickenshout.townypolitical.managers;

import com.palmergames.bukkit.towny.TownyAPI;
import com.palmergames.bukkit.towny.object.Nation;
import com.palmergames.bukkit.towny.object.Resident;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;
import top.chickenshout.townypolitical.TownyPolitical;
import top.chickenshout.townypolitical.data.Bill;
import top.chickenshout.townypolitical.data.NationPolitics; // 需要导入
import top.chickenshout.townypolitical.data.Party;
import top.chickenshout.townypolitical.elections.Election;
import top.chickenshout.townypolitical.enums.BillStatus;
import top.chickenshout.townypolitical.enums.ElectionType;
import top.chickenshout.townypolitical.enums.GovernmentType; // 需要导入
import top.chickenshout.townypolitical.enums.VoteChoice;
import top.chickenshout.townypolitical.utils.MessageManager;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.stream.Collectors;

public class BillManager {
    private final TownyPolitical plugin;
    private final MessageManager messageManager;
    private final NationManager nationManager;
    private final PartyManager partyManager; // 可能需要用于获取议员信息

    // <BillUUID, Bill> - 存储所有法案 (包括活跃和已处理的)
    private final Map<UUID, Bill> billsById;
    // <NationUUID, List<BillUUID>> - 方便按国家查找法案
    private final Map<UUID, List<UUID>> nationBillsIndex;
    // <BillUUID, BukkitTask> - 存储法案投票结束任务
    private final Map<UUID, BukkitTask> scheduledVoteEndTasks;

    private final File billsDataFolder;
    private static final String BILL_FILE_EXTENSION = ".yml";

    public BillManager(TownyPolitical plugin) {
        this.plugin = plugin;
        this.messageManager = plugin.getMessageManager();
        this.nationManager = plugin.getNationManager();
        this.partyManager = plugin.getPartyManager(); // 初始化

        this.billsById = new ConcurrentHashMap<>();
        this.nationBillsIndex = new ConcurrentHashMap<>();
        this.scheduledVoteEndTasks = new ConcurrentHashMap<>();

        this.billsDataFolder = new File(plugin.getDataFolder(), "bills");
        if (!billsDataFolder.exists()) {
            if (!billsDataFolder.mkdirs()) {
                plugin.getLogger().severe("Could not create bills data folder!");
            }
        }
        loadBills();
    }

    // --- 法案创建与提交流程 ---
    public Bill proposeBill(Player proposer, Nation targetNation, String title, String content) {
        if (proposer == null || targetNation == null || title == null || title.trim().isEmpty() || content == null || content.trim().isEmpty()) {
            messageManager.sendMessage(proposer, "bill-propose-fail-invalid-input");
            return null;
        }

        NationPolitics politics = nationManager.getNationPolitics(targetNation);
        if (politics == null) {
            messageManager.sendMessage(proposer, "error-nation-not-found", "nation", targetNation.getName()); // Or a more specific message
            return null;
        }
        GovernmentType govType = politics.getGovernmentType();
        Resident proposerResident = TownyAPI.getInstance().getResident(proposer.getUniqueId());

        // 权限检查：谁可以提案
        boolean canPropose = false;
        String proposerRole = "";

        switch (govType) {
            case ABSOLUTE_MONARCHY:
                if (targetNation.isKing(proposerResident)) {
                    canPropose = true;
                    proposerRole = "国王";
                }
                break;
            case PRESIDENTIAL_REPUBLIC:
                if (targetNation.isKing(proposerResident)) { // 总统是Towny King
                    canPropose = true;
                    proposerRole = "总统";
                }
                break;
            case PARLIAMENTARY_REPUBLIC:
            case CONSTITUTIONAL_MONARCHY:
                // 总理 (Towny King) 可以提案
                if (targetNation.isKing(proposerResident)) {
                    canPropose = true;
                    proposerRole = "总理";
                }
                break;
            case SEMI_PRESIDENTIAL_REPUBLIC:
                // 总统 (Towny King) 可以提案
                if (targetNation.isKing(proposerResident)) {
                    canPropose = true;
                    proposerRole = "总统";
                }
                // 总理也可以提案 (假设总理是NationPolitics中存储的)
                else if (politics.getPrimeMinisterUUID().isPresent() && politics.getPrimeMinisterUUID().get().equals(proposer.getUniqueId())) {
                    canPropose = true;
                    proposerRole = "总理";
                }
                break;
        }

        if (!canPropose) {
            messageManager.sendMessage(proposer, "bill-propose-fail-no-permission", "nation_name", targetNation.getName(), "government_type", govType.getDisplayName());
            return null;
        }

        UUID billId = UUID.randomUUID();
        Bill bill = new Bill(billId, targetNation.getUUID(), proposer.getUniqueId(), title, content);
        bill.setProposerNameCache(proposer.getName());

        billsById.put(billId, bill);
        nationBillsIndex.computeIfAbsent(targetNation.getUUID(), k -> new ArrayList<>()).add(billId);
        saveBill(bill);

        messageManager.sendMessage(proposer, "bill-propose-success", "title", bill.getTitle(), "nation_name", targetNation.getName());
        plugin.getLogger().info(proposerRole + " " + proposer.getName() + " proposed bill '" + bill.getTitle() + "' (ID: " + billId + ") in nation " + targetNation.getName());

        // 根据政体决定下一步
        handleBillProposalByGovernmentType(bill, politics, proposer);
        return bill;
    }

    private void handleBillProposalByGovernmentType(Bill bill, NationPolitics politics, Player proposer) {
        GovernmentType govType = politics.getGovernmentType();
        Nation nation = TownyAPI.getInstance().getNation(bill.getNationId());
        if (nation == null) return; // Should not happen

        switch (govType) {
            case ABSOLUTE_MONARCHY:
            case PRESIDENTIAL_REPUBLIC:
                // 国王或总统直接颁布
                enactBill(bill, proposer.getName() + " (作为" + (govType == GovernmentType.ABSOLUTE_MONARCHY ? "国王" : "总统") + ")");
                break;
            case PARLIAMENTARY_REPUBLIC:
            case CONSTITUTIONAL_MONARCHY:
                // 总理提案，提交议会投票
                if (politics.getGovernmentType().hasParliament()) {
                    startParliamentaryVote(bill, nation);
                } else { // 理论上不应发生，因为这些政体定义为有议会
                    plugin.getLogger().warning("Bill " + bill.getBillId() + " proposed under " + govType.getDisplayName() + " but hasParliament() is false. Auto-enacting.");
                    enactBill(bill, proposer.getName() + " (自动颁布，议会逻辑异常)");
                }
                break;
            case SEMI_PRESIDENTIAL_REPUBLIC:
                // 半总统制：
                // 如果是总统提案，可以选择直接颁布或交议会（简化：直接颁布）
                // 如果是总理提案，必须交议会投票
                if (nation.isKing(TownyAPI.getInstance().getResident(bill.getProposerId()))) { // 提案人是总统
                    // 简化：总统提案直接颁布
                    enactBill(bill, proposer.getName() + " (作为总统)");
                    // 或者，如果总统想交议会：
                    // startParliamentaryVote(bill, nation);
                    // messageManager.sendMessage(proposer, "bill-semi-presidential-president-choice-to-vote");
                } else { // 提案人是总理
                    if (politics.getGovernmentType().hasParliament()) {
                        startParliamentaryVote(bill, nation);
                    } else {
                        plugin.getLogger().warning("Bill " + bill.getBillId() + " (Semi-Pres) by PM but hasParliament() is false. Auto-enacting.");
                        enactBill(bill, proposer.getName() + " (自动颁布，议会逻辑异常)");
                    }
                }
                break;
        }
    }

    private void startParliamentaryVote(Bill bill, Nation nation) {
        bill.setStatus(BillStatus.VOTING);
        long votingDurationSeconds = plugin.getConfig().getLong("bills.parliament_vote_duration_seconds", 24 * 3600);
        bill.setVotingEndTimestamp(System.currentTimeMillis() + votingDurationSeconds * 1000L);
        saveBill(bill);

        plugin.getLogger().info("Parliamentary vote started for bill '" + bill.getTitle() + "' (ID: " + bill.getBillId() + ") in " + nation.getName());

        List<Resident> mps = getParliamentMembers(nation);
        if (!mps.isEmpty()) {
            messageManager.sendMessage(Bukkit.getConsoleSender(), "bill-parliament-vote-started-mps-notification", // 新消息键
                    "title", bill.getTitle(),
                    "nation_name", nation.getName(),
                    "bill_id", bill.getBillId().toString().substring(0,8)
            );
            for (Resident mpResident : mps) {
                if (mpResident.isOnline()) {
                    Player mpPlayer = Bukkit.getPlayer(mpResident.getUUID());
                    if (mpPlayer != null) {
                        messageManager.sendMessage(mpPlayer, "bill-parliament-vote-started-mp-personal", // 新消息键
                                "title", bill.getTitle(),
                                "nation_name", nation.getName(),
                                "bill_id", bill.getBillId().toString().substring(0,8)
                        );
                    }
                }
            }
        } else {
            // 如果没有明确的议员，则向全国广播投票开始，并提示所有公民可以投票 (如果这是回退逻辑)
            broadcastToNation(nation, "bill-parliament-vote-started-no-mps", // 新消息键
                    "title", bill.getTitle(),
                    "nation_name", nation.getName(),
                    "bill_id", bill.getBillId().toString().substring(0,8)
            );
        }


        BukkitTask voteEndTask = Bukkit.getScheduler().runTaskLater(plugin, () -> finishParliamentaryVote(bill.getBillId()), votingDurationSeconds * 20L);
        scheduledVoteEndTasks.put(bill.getBillId(), voteEndTask);
    }

    private void finishParliamentaryVote(UUID billId) {
        scheduledVoteEndTasks.remove(billId);
        Bill bill = billsById.get(billId);
        if (bill == null || bill.getStatus() != BillStatus.VOTING) {
            plugin.getLogger().warning("finishParliamentaryVote: Bill " + billId + " not found or not in voting status.");
            return;
        }

        Nation nation = TownyAPI.getInstance().getNation(bill.getNationId());
        if (nation == null) {
            plugin.getLogger().warning("finishParliamentaryVote: Nation " + bill.getNationId() + " not found for bill " + billId);
            bill.setStatus(BillStatus.CANCELLED);
            saveBill(bill);
            return;
        }

        int yeaVotes = bill.getYeaVotes();
        int nayVotes = bill.getNayVotes();
        int abstainVotes = bill.getAbstainVotes(); // 获取弃权票

        List<Resident> mps = getParliamentMembers(nation);
        int totalPossibleVoters = mps.size();
        if (totalPossibleVoters == 0) { // 如果没有明确议员，则看实际投票人数
            boolean fallbackToAllCitizens = plugin.getConfig().getBoolean("bills.parliament_vote_fallback_all_citizens", true);
            if (fallbackToAllCitizens) {
                // 如果是全民投票，通过门槛可能不同，这里简化处理
                // 假设此时 totalPossibleVoters 是实际参与投票的人数（赞成+反对）
                totalPossibleVoters = yeaVotes + nayVotes; // 弃权不算在“有效”投票基数内来决定是否过半
            } else {
                plugin.getLogger().warning("Bill " + billId + " voting ended but no MPs defined and fallback to all citizens is disabled. Marking as rejected.");
                bill.setStatus(BillStatus.REJECTED_BY_PARLIAMENT);
                saveBill(bill);
                broadcastToNation(nation, "bill-parliament-vote-rejected-no-voters", "title", bill.getTitle(), "nation_name", nation.getName());
                return;
            }
        }


        // 从配置读取通过门槛类型
        String passThresholdType = plugin.getConfig().getString("bills.parliament_pass_threshold.type", "SIMPLE_MAJORITY_OF_VOTES_CAST").toUpperCase();
        double requiredPercentage = plugin.getConfig().getDouble("bills.parliament_pass_threshold.required_percentage_of_total_mps", 50.1); // 例如50.1% 表示过半数

        boolean passed = false;
        switch (passThresholdType) {
            case "SIMPLE_MAJORITY_OF_VOTES_CAST": // 赞成票 > 反对票 (忽略弃权)
                passed = yeaVotes > nayVotes;
                break;
            case "ABSOLUTE_MAJORITY_OF_VOTES_CAST": // 赞成票 > (反对票 + 弃权票)
                passed = yeaVotes > (nayVotes + abstainVotes);
                break;
            case "MAJORITY_OF_TOTAL_MPS": // 赞成票 >= (总议员数 * 百分比 / 100)
                if (totalPossibleVoters > 0) { // 避免除以0
                    passed = yeaVotes >= (totalPossibleVoters * requiredPercentage / 100.0);
                } else { // 没有议员，无法通过此规则
                    passed = false;
                }
                break;
            default: // 默认为简单多数
                passed = yeaVotes > nayVotes;
                break;
        }

        if (passed) {
            bill.setStatus(BillStatus.PASSED_BY_PARLIAMENT);
            messageManager.sendMessage(Bukkit.getConsoleSender(), "bill-vote-result-passed-parliament", "title", bill.getTitle(), "nation_name", nation.getName(), "yea", String.valueOf(yeaVotes), "nay", String.valueOf(nayVotes));
            enactBill(bill, "议会投票通过");
        } else {
            bill.setStatus(BillStatus.REJECTED_BY_PARLIAMENT);
            messageManager.sendMessage(Bukkit.getConsoleSender(), "bill-vote-result-rejected-parliament", "title", bill.getTitle(), "nation_name", nation.getName(), "yea", String.valueOf(yeaVotes), "nay", String.valueOf(nayVotes));
            broadcastToNation(nation, "bill-parliament-vote-rejected", "title", bill.getTitle(), "nation_name", nation.getName());
        }
        saveBill(bill);
    }

    public void enactBill(Bill bill, String enactedByInfo) {
        if (bill == null || bill.getStatus() == BillStatus.ENACTED || bill.getStatus() == BillStatus.REPEALED) {
            return;
        }
        bill.setStatus(BillStatus.ENACTED);
        bill.setEnactmentTimestamp(System.currentTimeMillis());
        saveBill(bill);

        Nation nation = TownyAPI.getInstance().getNation(bill.getNationId());
        String nationName = (nation != null) ? nation.getName() : "未知国家";

        plugin.getLogger().info("Bill '" + bill.getTitle() + "' (ID: " + bill.getBillId() + ") enacted in " + nationName + ". Enacted by: " + enactedByInfo);
        broadcastToNation(nation, "bill-enacted-broadcast", "title", bill.getTitle(), "nation_name", nationName);
    }

    // --- 投票逻辑 ---
    public boolean playerVoteOnBill(Player voter, Bill bill, VoteChoice choice) {
        if (voter == null || bill == null || choice == null) return false;

        if (bill.getStatus() != BillStatus.VOTING) {
            messageManager.sendMessage(voter, "bill-vote-fail-not-voting-stage");
            return false;
        }
        if (System.currentTimeMillis() >= bill.getVotingEndTimestamp()) {
            messageManager.sendMessage(voter, "bill-vote-fail-voting-closed");
            // 理论上此时任务应该已执行，状态已改变
            return false;
        }

        // 资格检查：谁能投票？
        // 简化：假设国家的所有公民都能对“议会”法案投票（虽然这不完全符合现实议会制）
        // 更真实的：只有该国的“议员”能投票。
        Nation nation = TownyAPI.getInstance().getNation(bill.getNationId());
        Resident resident = TownyAPI.getInstance().getResident(voter.getUniqueId());
        if (nation == null || resident == null || !resident.hasNation() || !resident.getNationOrNull().equals(nation)) {
            messageManager.sendMessage(voter, "bill-vote-fail-not-eligible-citizen", "nation_name", nation != null ? nation.getName() : "该国");
            return false;
        }
        // 资格检查：谁能投票？
        NationPolitics politics = nationManager.getNationPolitics(nation);
        boolean isEligibleToVote = false;

        if (politics != null && (politics.getGovernmentType() == GovernmentType.PARLIAMENTARY_REPUBLIC ||
                politics.getGovernmentType() == GovernmentType.CONSTITUTIONAL_MONARCHY ||
                politics.getGovernmentType() == GovernmentType.SEMI_PRESIDENTIAL_REPUBLIC)) {
            // 对于需要议会投票的政体
            List<Resident> mps = getParliamentMembers(nation);
            if (!mps.isEmpty()) { // 如果有明确的议员列表
                if (mps.stream().anyMatch(mp -> mp.getUUID().equals(voter.getUniqueId()))) {
                    isEligibleToVote = true;
                } else {
                    messageManager.sendMessage(voter, "bill-vote-fail-not-mp", "nation_name", nation.getName());
                    return false;
                }
            } else {
                // 如果 getParliamentMembers 返回空（例如没有选举结果），是否允许所有公民投票？
                // 这取决于你的设计决策。为了简化，如果配置允许，可以让所有公民投票。
                boolean fallbackToAllCitizens = plugin.getConfig().getBoolean("bills.parliament_vote_fallback_all_citizens", true);
                if (fallbackToAllCitizens) {
                    if (resident.hasNation() && resident.getNationOrNull().equals(nation)) {
                        isEligibleToVote = true;
                    } else {
                        messageManager.sendMessage(voter, "bill-vote-fail-not-eligible-citizen", "nation_name", nation.getName());
                        return false;
                    }
                } else {
                    messageManager.sendMessage(voter, "bill-vote-fail-no-mps-defined", "nation_name", nation.getName());
                    return false;
                }
            }
        } else {
            // 对于其他政体（如总统制、君主专制），法案通常不通过这种方式投票。
            // 但如果逻辑走到了这里，说明 proposeBill 那里可能没有正确处理直接颁布。
            // 或者这是管理员强制开启的投票。
            // 默认允许国家公民投票 (如果逻辑允许到这里)
            if (resident.hasNation() && resident.getNationOrNull().equals(nation)) {
                isEligibleToVote = true;
            } else {
                messageManager.sendMessage(voter, "bill-vote-fail-not-eligible-citizen", "nation_name", nation.getName());
                return false;
            }
        }

        if (!isEligibleToVote) { // 再次检查，理论上前面分支已处理
            messageManager.sendMessage(voter, "bill-vote-fail-not-eligible", "nation_name", nation.getName());
            return false;
        }


        if (bill.getVotes().containsKey(voter.getUniqueId())) {
            messageManager.sendMessage(voter, "bill-vote-fail-already-voted");
            return false;
        }

        bill.addVote(voter.getUniqueId(), choice);
        saveBill(bill);
        messageManager.sendMessage(voter, "bill-vote-success", "choice", choice.getDisplayName(), "title", bill.getTitle());
        return true;
    }


    // --- 数据获取 ---
    public Bill getBill(UUID billId) {
        return billsById.get(billId);
    }

    public List<Bill> getBillsForNation(UUID nationId) {
        List<UUID> ids = nationBillsIndex.getOrDefault(nationId, Collections.emptyList());
        return ids.stream().map(billsById::get).filter(Objects::nonNull).collect(Collectors.toList());
    }

    public List<Bill> getBillsForNationByStatus(UUID nationId, BillStatus status) {
        return getBillsForNation(nationId).stream()
                .filter(b -> b.getStatus() == status)
                .sorted(Comparator.comparingLong(Bill::getProposalTimestamp).reversed()) // 按提案时间降序
                .collect(Collectors.toList());
    }

    // --- 数据持久化 ---
    public void loadBills() {
        billsById.clear();
        nationBillsIndex.clear();
        scheduledVoteEndTasks.values().forEach(BukkitTask::cancel);
        scheduledVoteEndTasks.clear();

        if (!billsDataFolder.exists() || !billsDataFolder.isDirectory()) return;
        File[] billFiles = billsDataFolder.listFiles((dir, name) -> name.toLowerCase().endsWith(BILL_FILE_EXTENSION));
        if (billFiles == null || billFiles.length == 0) return;

        for (File billFile : billFiles) {
            YamlConfiguration config = YamlConfiguration.loadConfiguration(billFile);
            try {
                UUID billId = UUID.fromString(config.getString("billId"));
                UUID nationId = UUID.fromString(config.getString("nationId"));
                UUID proposerId = UUID.fromString(config.getString("proposerId"));
                String title = config.getString("title");
                String content = config.getString("content");

                Bill bill = new Bill(billId, nationId, proposerId, title, content);
                bill.setProposerNameCache(config.getString("proposerNameCache"));
                bill.setStatus(BillStatus.fromString(config.getString("status")).orElse(BillStatus.PROPOSED));
                bill.setProposalTimestamp(config.getLong("proposalTimestamp"));
                bill.setVotingEndTimestamp(config.getLong("votingEndTimestamp", 0));
                bill.setEnactmentTimestamp(config.getLong("enactmentTimestamp", 0));

                if (config.isConfigurationSection("votes")) {
                    ConfigurationSection votesSection = config.getConfigurationSection("votes");
                    for (String voterUUIDStr : votesSection.getKeys(false)) {
                        VoteChoice choice = VoteChoice.fromString(votesSection.getString(voterUUIDStr));
                        if (choice != null) {
                            bill.addVote(UUID.fromString(voterUUIDStr), choice);
                        }
                    }
                }

                billsById.put(billId, bill);
                nationBillsIndex.computeIfAbsent(nationId, k -> new ArrayList<>()).add(billId);

                // 恢复投票结束任务
                if (bill.getStatus() == BillStatus.VOTING && bill.getVotingEndTimestamp() > System.currentTimeMillis()) {
                    long delayTicks = (bill.getVotingEndTimestamp() - System.currentTimeMillis()) / 50L;
                    BukkitTask task = Bukkit.getScheduler().runTaskLater(plugin, () -> finishParliamentaryVote(billId), Math.max(1, delayTicks));
                    scheduledVoteEndTasks.put(billId, task);
                } else if (bill.getStatus() == BillStatus.VOTING && bill.getVotingEndTimestamp() <= System.currentTimeMillis()){
                    // 投票时间已过，立即处理
                    finishParliamentaryVote(billId);
                }

            } catch (IllegalArgumentException e) {
                plugin.getLogger().log(Level.WARNING, "Failed to load bill from file " + billFile.getName() + ": " + e.getMessage());
                moveCorruptedFile(billFile, "bill_load_error_");
            }
        }
        plugin.getLogger().info("Loaded " + billsById.size() + " bills.");
    }

    public void saveBill(Bill bill) {
        if (bill == null) return;
        File billFile = new File(billsDataFolder, bill.getBillId().toString() + BILL_FILE_EXTENSION);
        YamlConfiguration config = new YamlConfiguration();

        config.set("billId", bill.getBillId().toString());
        config.set("nationId", bill.getNationId().toString());
        config.set("proposerId", bill.getProposerId().toString());
        config.set("proposerNameCache", bill.getProposerNameCache());
        config.set("title", bill.getTitle());
        config.set("content", bill.getContent());
        config.set("status", bill.getStatus().name());
        config.set("proposalTimestamp", bill.getProposalTimestamp());
        config.set("votingEndTimestamp", bill.getVotingEndTimestamp());
        config.set("enactmentTimestamp", bill.getEnactmentTimestamp());

        if (!bill.getVotes().isEmpty()) {
            ConfigurationSection votesSection = config.createSection("votes");
            bill.getVotes().forEach((voterId, choice) -> votesSection.set(voterId.toString(), choice.name()));
        }
        try {
            config.save(billFile);
        } catch (IOException e) {
            plugin.getLogger().log(Level.SEVERE, "Could not save bill: " + bill.getTitle(), e);
        }
    }

    private void moveCorruptedFile(File file, String prefix) {
        // (与NationManager中类似)
        if (file == null || !file.exists()) return;
        File corruptedFolder = new File(billsDataFolder.getParentFile(), "corrupted_data");
        if (!corruptedFolder.exists()) corruptedFolder.mkdirs();
        File newFile = new File(corruptedFolder, prefix + file.getName() + "_" + System.currentTimeMillis() + ".yml_disabled");
        try {
            Files.move(file.toPath(), newFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
            plugin.getLogger().warning("Moved corrupted file " + file.getName() + " to " + newFile.getPath());
        } catch (IOException ex) {
            plugin.getLogger().log(Level.SEVERE, "Could not move corrupted file " + file.getName(), ex);
        }
    }

    public void shutdown() {
        plugin.getLogger().info("Saving all " + billsById.size() + " bills...");
        billsById.values().forEach(this::saveBill);
        scheduledVoteEndTasks.values().forEach(BukkitTask::cancel);
        scheduledVoteEndTasks.clear();
        plugin.getLogger().info("BillManager shutdown complete.");
    }

    // --- 辅助方法 ---
    private void broadcastToNation(Nation nation, String messageKey, Object... placeholders) {
        if (nation == null) return;
        String message = messageManager.getMessage(messageKey, placeholders);
        String prefixedMessage = messageManager.getFormattedPrefix() + message;
        for (Resident resident : nation.getResidents()) {
            if (resident.isOnline()) {
                Player player = Bukkit.getPlayer(resident.getUUID());
                if (player != null) {
                    player.sendMessage(prefixedMessage);
                }
            }
        }
        plugin.getLogger().info("[Bill Broadcast to " + nation.getName() + "] " + message);
    }

    public Collection<Bill> getAllBills() {
        return Collections.unmodifiableCollection(billsById.values());
    }

// ... (在 broadcastToNation 方法之后)

    /**
     * 获取有资格对特定国家法案进行投票的议员列表。
     * 这通常基于最近一次议会选举的结果。
     * @param nation 目标国家
     * @return 符合资格的议员的 Resident 对象列表 (如果无法确定则为空列表)
     */
    private List<Resident> getParliamentMembers(Nation nation) {
        if (nation == null) return Collections.emptyList();
        List<Resident> parliamentMembers = new ArrayList<>();

        // 依赖 ElectionManager 提供最近议会选举的结果和席位分配
        ElectionManager em = plugin.getElectionManager();
        if (em == null) {
            plugin.getLogger().warning("[BillManager] Cannot get parliament members: ElectionManager is null.");
            return Collections.emptyList();
        }

        Optional<Election> latestParliamentElectionOpt = em.getLatestFinishedElection(nation.getUUID(), ElectionType.PARLIAMENTARY);
        if (latestParliamentElectionOpt.isEmpty()) {
            plugin.getLogger().finer("[BillManager] No finished parliamentary election found for " + nation.getName() + " to determine MPs.");
            // 如果没有选举结果，可以考虑一个备用逻辑，例如国家所有官员都是议员，或者只有特定政党的领袖/管理员是。
            // 简化：如果没有选举结果，则没有明确的议员。
            return Collections.emptyList();
        }

        Election latestElection = latestParliamentElectionOpt.get();
        Map<UUID, Integer> seatDistribution = latestElection.getPartySeatDistribution();

        if (seatDistribution.isEmpty()) {
            plugin.getLogger().finer("[BillManager] Latest parliamentary election for " + nation.getName() + " has no seat distribution.");
            return Collections.emptyList();
        }

        // 从拥有席位的政党中获取代表 (例如，每个席位对应一个代表，或者政党领袖/管理员代表)
        // 这是一个简化的逻辑：假设政党领袖是该党在议会的主要代表。
        // 更复杂的可能需要按比例从党员中选出，或者每个政党指定其议员。
        for (Map.Entry<UUID, Integer> entry : seatDistribution.entrySet()) {
            if (entry.getValue() > 0) { // 政党至少有一个席位
                Party party = partyManager.getParty(entry.getKey());
                if (party != null) {
                    // 简化1: 只考虑政党领袖作为议员
                    party.getLeader().ifPresent(leaderMember -> {
                        Resident resident = TownyAPI.getInstance().getResident(leaderMember.getPlayerId());
                        if (resident != null && resident.hasNation() && resident.getNationOrNull().equals(nation)) { // 确保议员仍是该国公民
                            parliamentMembers.add(resident);
                        }
                    });
                    // 简化2: 考虑政党领袖和管理员
                    // party.getLeader().ifPresent(leaderMember -> { /* ... */ });
                    // party.getAdmins().forEach(adminMember -> {
                    //     Resident resident = TownyAPI.getInstance().getResident(adminMember.getPlayerId());
                    //     if (resident != null && resident.hasNation() && resident.getNationOrNull().equals(nation)) {
                    //        if (!parliamentMembers.stream().anyMatch(r -> r.getUUID().equals(resident.getUUID()))){ //避免重复添加
                    //             parliamentMembers.add(resident);
                    //        }
                    //     }
                    // });
                }
            }
        }
        if (parliamentMembers.isEmpty()){
            plugin.getLogger().finer("[BillManager] No specific MPs identified for " + nation.getName() + " based on party leaders with seats. Bill voting might be open to all citizens if fallback is used.");
        }
        return parliamentMembers;
    }
}