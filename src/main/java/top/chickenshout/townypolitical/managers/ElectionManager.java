// 文件名: ElectionManager.java
// 结构位置: top/chickenshout/townypolitical/managers/ElectionManager.java
package top.chickenshout.townypolitical.managers;

import com.palmergames.bukkit.towny.TownyAPI;
import com.palmergames.bukkit.towny.exceptions.NotRegisteredException;
import com.palmergames.bukkit.towny.object.Nation;
import com.palmergames.bukkit.towny.object.Resident;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;
import top.chickenshout.townypolitical.TownyPolitical;
import top.chickenshout.townypolitical.data.Party;
import top.chickenshout.townypolitical.data.PartyMember;
import top.chickenshout.townypolitical.data.NationPolitics;
import top.chickenshout.townypolitical.elections.Candidate;
import top.chickenshout.townypolitical.elections.Election;
import top.chickenshout.townypolitical.enums.ElectionStatus;
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

public class ElectionManager {
    private final TownyPolitical plugin;
    private final MessageManager messageManager;
    private final PartyManager partyManager;
    private final NationManager nationManager;

    // <ElectionUUID, Election> - 主存储，所有活动和最近结束的选举
    private final Map<UUID, Election> electionsById;
    // <TaskKey (String, e.g., contextId_type), BukkitTask> - 存储周期性调度任务
    private final Map<String, BukkitTask> scheduledCycleTasks;
    // <ElectionUUID, BukkitTask> - 存储选举阶段推进任务 (登记结束->投票，投票结束->计票，以及结果公示后的归档任务)
    private final Map<UUID, BukkitTask> scheduledPhaseTasks;


    private final File activeElectionsDataFolder;
    private final File archivedElectionsDataFolder;
    private static final String ELECTION_FILE_EXTENSION = ".yml";

    public ElectionManager(TownyPolitical plugin) {

        this.plugin = plugin;
        this.messageManager = plugin.getMessageManager();
        this.partyManager = plugin.getPartyManager();
        this.nationManager = plugin.getNationManager();

        this.electionsById = new ConcurrentHashMap<>();
        this.scheduledCycleTasks = new ConcurrentHashMap<>();
        this.scheduledPhaseTasks = new ConcurrentHashMap<>();

        File baseElectionsFolder = new File(plugin.getDataFolder(), "elections");
        if (!baseElectionsFolder.exists()) {
            if (!baseElectionsFolder.mkdirs()) {
                plugin.getLogger().severe("Could not create base elections data folder!");
            }
        }

        this.activeElectionsDataFolder = new File(baseElectionsFolder, "active");
        if (!activeElectionsDataFolder.exists()) {
            if (!activeElectionsDataFolder.mkdirs()){
                plugin.getLogger().severe("Could not create active elections data folder!");
            }
        }

        this.archivedElectionsDataFolder = new File(baseElectionsFolder, "archived");
        if (!archivedElectionsDataFolder.exists()) {
            if (!archivedElectionsDataFolder.mkdirs()){
                plugin.getLogger().severe("Could not create archived elections data folder!");
            }
        }

        loadActiveElections(); // 加载进行中的选举并恢复其状态和任务
        scheduleNextElectionsForAllValidContexts(); // 为所有国家和政党（如果配置）安排周期性选举
    }

    /**
     * 在插件启动时，为所有需要周期性选举的国家和政党安排下一次选举。
     */
    public void scheduleNextElectionsForAllValidContexts() {
        if (TownyAPI.getInstance() == null) { // 确保TownyAPI可用
            plugin.getLogger().warning("[ElectionManager] TownyAPI not available during scheduleNextElectionsForAllValidContexts. Skipping nation election scheduling.");
        } else {
            plugin.getLogger().info("[ElectionManager] Performing initial scan and scheduling for all nation elections...");
            for (Nation nation : TownyAPI.getInstance().getNations()) {
                scheduleNextElectionForNation(nation.getUUID());
            }
        }

        if (plugin.getConfig().getLong("party.leader_election.auto_schedule_interval_days", 0) > 0) {
            plugin.getLogger().info("[ElectionManager] Performing initial scan and scheduling for party leader elections...");
            for (Party party : partyManager.getAllParties()) {
                scheduleNextPartyLeaderElection(party.getPartyId());
            }
        }
        plugin.getLogger().info("[ElectionManager] Initial election scheduling scan complete.");
    }

    /**
     * 为指定国家安排下一次所需类型的选举（议会/总统）。
     * 此方法会检查国家政体、上次选举完成时间、配置间隔，并创建Bukkit任务。
     * @param nationUUID 国家的UUID
     */
    public void scheduleNextElectionForNation(UUID nationUUID) {
        Nation nation = TownyAPI.getInstance().getNation(nationUUID);
        if (nation == null) {
            plugin.getLogger().warning("[ElectionManager] scheduleNextElectionForNation: Tried to schedule for non-existent nation UUID: " + nationUUID);
            return;
        }

        NationPolitics politics = nationManager.getNationPolitics(nation);
        if (politics == null) {
            plugin.getLogger().warning("[ElectionManager] scheduleNextElectionForNation: Could not get/create politics for nation " + nation.getName() + ".");
            return;
        }
        GovernmentType govType = politics.getGovernmentType();

        List<ElectionType> typesToSchedule = new ArrayList<>();
        if (govType.hasParliament()) typesToSchedule.add(ElectionType.PARLIAMENTARY);
        if (govType.hasDirectPresidentialElection()) typesToSchedule.add(ElectionType.PRESIDENTIAL);

        if (typesToSchedule.isEmpty()) {
            // 清理可能存在的旧调度任务，因为当前政体不再需要选举
            cancelScheduledCycleTask(nation.getUUID().toString() + "_" + ElectionType.PARLIAMENTARY.name());
            cancelScheduledCycleTask(nation.getUUID().toString() + "_" + ElectionType.PRESIDENTIAL.name());
            return;
        }

        for (ElectionType typeToSchedule : typesToSchedule) {
            String taskKey = nationUUID.toString() + "_" + typeToSchedule.name();
            boolean isActiveOrPending = electionsById.values().stream().anyMatch(e ->
                    e.getContextId().equals(nationUUID) &&
                            e.getType() == typeToSchedule &&
                            (e.getStatus() != ElectionStatus.FINISHED && e.getStatus() != ElectionStatus.CANCELLED)
            );

            if (isActiveOrPending) {
                plugin.getLogger().finer("[ElectionManager] Nation " + nation.getName() + " already has an active/pending " + typeToSchedule.getDisplayName() + ". Cancelling any duplicate cycle task with key: " + taskKey);
                cancelScheduledCycleTask(taskKey); // 如果有活跃选举，就不应该再有这个key的周期任务
                continue;
            }

            long electionIntervalTicks = getConfiguredElectionIntervalTicks(govType, typeToSchedule);
            if (electionIntervalTicks <= 0) {
                plugin.getLogger().finer("[ElectionManager] " + typeToSchedule.getDisplayName() + " interval not configured or zero for " + nation.getName() + ". Automatic election disabled for this type.");
                cancelScheduledCycleTask(taskKey); // 确保没有残留任务
                continue;
            }

            long lastCompletionTime = politics.getLastElectionCompletionTime(typeToSchedule);
            long currentTime = System.currentTimeMillis();
            long nextScheduledTimeMillis;

            if (lastCompletionTime == 0L) {
                nextScheduledTimeMillis = currentTime + (electionIntervalTicks * 50L);
                plugin.getLogger().info("[ElectionManager] No previous " + typeToSchedule.getDisplayName() + " completion time for " + nation.getName() + ". Scheduling first cycle.");
            } else {
                nextScheduledTimeMillis = lastCompletionTime + (electionIntervalTicks * 50L);
            }

            long delayMillis = nextScheduledTimeMillis - currentTime;
            final ElectionType finalType = typeToSchedule; // For lambda

            if (delayMillis <= 0) { // 选举已到期或过期
                plugin.getLogger().info("[ElectionManager] " + finalType.getDisplayName() + " for " + nation.getName() + " is due or overdue. Attempting to start it.");
                Election createdElection = startNationElection(nationUUID, finalType, true);
                if (createdElection == null) {
                    plugin.getLogger().warning("[ElectionManager] Failed to start overdue scheduled " + finalType.getDisplayName() + " for " + nation.getName() + ". It might be re-attempted on next global scan or event.");
                }
                cancelScheduledCycleTask(taskKey); // 此周期的任务已尝试执行
                continue;
            }

            long delayTicks = delayMillis / 50L;
            cancelScheduledCycleTask(taskKey); // 清除旧任务，安排新任务
            plugin.getLogger().info("[ElectionManager] Scheduling next " + finalType.getDisplayName() + " for nation " + nation.getName() + " in approx. " +
                    String.format("%.2f hours (%.2f minutes)", delayTicks / 72000.0, delayTicks / 1200.0) + ". TaskKey: " + taskKey);

            BukkitTask task = new BukkitRunnable() {
                @Override
                public void run() {
                    plugin.getLogger().info("[ElectionManager] Scheduled cycle task now running for " + finalType.getDisplayName() + " in " + nation.getName() + " (TaskKey: " + taskKey + ")");
                    scheduledCycleTasks.remove(taskKey); // 任务已执行，从map中移除
                    startNationElection(nationUUID, finalType, true); // 启动选举
                }
            }.runTaskLater(plugin, Math.max(1, delayTicks)); // 确保至少延迟1 tick
            scheduledCycleTasks.put(taskKey, task);
        }
    }

    /**
     * 为指定政党安排下一次党魁选举。
     * @param partyId 政党的UUID
     */
    public void scheduleNextPartyLeaderElection(UUID partyId) {
        Party party = partyManager.getParty(partyId);
        if (party == null) {
            plugin.getLogger().warning("[ElectionManager] scheduleNextPartyLeaderElection: Tried for non-existent party UUID: " + partyId);
            return;
        }

        long intervalDays = plugin.getConfig().getLong("party.leader_election.auto_schedule_interval_days", 0);
        if (intervalDays <= 0) {
            return; // 禁用自动调度
        }

        String taskKey = partyId.toString() + "_" + ElectionType.PARTY_LEADER.name();
        boolean isActiveOrPending = electionsById.values().stream().anyMatch(e ->
                e.getContextId().equals(partyId) &&
                        e.getType() == ElectionType.PARTY_LEADER &&
                        (e.getStatus() != ElectionStatus.FINISHED && e.getStatus() != ElectionStatus.CANCELLED)
        );

        if (isActiveOrPending) {
            plugin.getLogger().finer("[ElectionManager] Party " + party.getName() + " already has an active/pending leader election. Cancelling any duplicate cycle task.");
            cancelScheduledCycleTask(taskKey);
            return;
        }

        int minMembers = plugin.getConfig().getInt("party.leader_election.min_members_to_auto_elect", 5);
        if (party.getOfficialMemberIds().size() < minMembers) {
            plugin.getLogger().finer("[ElectionManager] Party " + party.getName() + " has " + party.getOfficialMemberIds().size() + "/" + minMembers + " members. Automatic leader election skipped.");
            cancelScheduledCycleTask(taskKey);
            return;
        }

        long intervalTicks = intervalDays * 24 * 60 * 60 * 20;
        long lastCompletionTime = party.getLastLeaderElectionTime();
        long currentTime = System.currentTimeMillis();
        long nextScheduledTimeMillis = (lastCompletionTime == 0L) ? (currentTime + intervalTicks * 50L) : (lastCompletionTime + intervalTicks * 50L);
        long delayMillis = nextScheduledTimeMillis - currentTime;

        if (delayMillis <= 0) {
            plugin.getLogger().info("[ElectionManager] Party Leader election for " + party.getName() + " is due. Attempting to start.");
            startPartyLeaderElection(partyId, true);
            cancelScheduledCycleTask(taskKey);
            return;
        }

        long delayTicks = delayMillis / 50L;
        cancelScheduledCycleTask(taskKey);
        plugin.getLogger().info("[ElectionManager] Scheduling next leader election for party " + party.getName() + " in approx. " +
                String.format("%.2f hours (%.2f minutes)", delayTicks / 72000.0, delayTicks / 1200.0) + ". TaskKey: " + taskKey);

        BukkitTask task = new BukkitRunnable() {
            @Override
            public void run() {
                plugin.getLogger().info("[ElectionManager] Scheduled cycle task now running for party leader election in " + party.getName() + " (TaskKey: " + taskKey + ")");
                scheduledCycleTasks.remove(taskKey);
                startPartyLeaderElection(partyId, true);
            }
        }.runTaskLater(plugin, Math.max(1, delayTicks));
        scheduledCycleTasks.put(taskKey, task);
    }
    /**
     * 启动一个国家的指定类型选举。
     * @param nationUUID 国家UUID
     * @param electionType 选举类型 (PARLIAMENTARY 或 PRESIDENTIAL)
     * @param isScheduledCall 是否由周期性调度器调用 (用于控制一些日志和行为)
     * @return 如果成功启动，返回创建的Election对象；否则返回null。
     */
    public Election startNationElection(UUID nationUUID, ElectionType electionType, boolean isScheduledCall) {
        Nation nation = TownyAPI.getInstance().getNation(nationUUID);
        if (nation == null) {
            plugin.getLogger().warning("[ElectionManager] startNationElection: Attempted for non-existent nation UUID: " + nationUUID);
            return null;
        }

        Election existingActive = getActiveElection(nationUUID, electionType);
        if (existingActive != null) {
            if (!isScheduledCall) { // 仅手动启动时提示，自动调度时如果发现活跃则静默跳过（已在schedule方法中处理）
                messageManager.sendMessage(Bukkit.getConsoleSender(), "election-already-active-nation", "nation", nation.getName(), "type", electionType.getDisplayName());
            }
            plugin.getLogger().finer("[ElectionManager] startNationElection: Election " + electionType.getDisplayName() + " for " + nation.getName() + " is already active (Status: " + existingActive.getStatus() + ").");
            return existingActive;
        }

        NationPolitics politics = nationManager.getNationPolitics(nation);
        if (politics == null) {
            plugin.getLogger().severe("[ElectionManager] startNationElection: Failed to get NationPolitics for " + nation.getName());
            return null;
        }
        GovernmentType govType = politics.getGovernmentType();

        if (electionType == ElectionType.PARLIAMENTARY && !govType.hasParliament()) {
            if (!isScheduledCall) plugin.getLogger().info("[ElectionManager] Nation " + nation.getName() + " (" + govType.getDisplayName() + ") does not support parliamentary elections.");
            return null;
        }
        if (electionType == ElectionType.PRESIDENTIAL && !govType.hasDirectPresidentialElection()) {
            if (!isScheduledCall) plugin.getLogger().info("[ElectionManager] Nation " + nation.getName() + " (" + govType.getDisplayName() + ") does not support direct presidential elections.");
            return null;
        }

        // 检查是否有足够的政党或候选人潜力 (可配置)
        if (electionType == ElectionType.PARLIAMENTARY) {
            int minParties = plugin.getConfig().getInt("elections.nation_election_schedule.parliamentary.min_participating_parties", 1);
            if (partyManager.getAllParties().size() < minParties) { // 简化检查：总政党数，更精确的是检查有多少政党愿意在该国参选
                plugin.getLogger().info("[ElectionManager] Not enough parties (" + partyManager.getAllParties().size() + "/" + minParties + ") to start parliamentary election in " + nation.getName());
                if (!isScheduledCall) messageManager.sendMessage(Bukkit.getConsoleSender(), "election-start-fail-not-enough-parties", "nation", nation.getName(), "min_parties", String.valueOf(minParties));
                // 重新安排到下一个周期
                scheduleNextElectionForNation(nationUUID);
                return null;
            }
        }
        // 总统选举的最小候选人潜力检查可以在登记阶段后，如果无人报名则取消。

        UUID electionId = UUID.randomUUID();
        Election election = new Election(electionId, nationUUID, electionType);
        election.setNationGovernmentTypeCache(govType); // 缓存启动时的政体

        long registrationDurationTicks = getConfiguredDurationTicks("registration_duration_seconds", 24 * 3600); // 默认24小时
        long votingDurationTicks = getConfiguredDurationTicks("voting_duration_seconds", 48 * 3600);     // 默认48小时
        long currentTime = System.currentTimeMillis();

        election.setStartTime(currentTime); // 选举活动（登记阶段）的开始时间
        election.setRegistrationEndTime(currentTime + registrationDurationTicks * 50L);
        election.setEndTime(election.getRegistrationEndTime() + votingDurationTicks * 50L); // 投票截止时间
        election.setStatus(ElectionStatus.REGISTRATION);

        electionsById.put(electionId, election);
        saveElectionState(election); // 保存初始状态

        String electionStartKey = electionType == ElectionType.PARLIAMENTARY ? "election-registration-start-parliament" : "election-registration-start-presidential";
        broadcastToNation(nation, electionStartKey, "nation_name", nation.getName());
        plugin.getLogger().info("[ElectionManager] " + electionType.getDisplayName() + " registration started for nation: " + nation.getName() + " (ID: " + electionId + ")");

        // 安排任务：登记截止后推进到投票阶段
        cancelScheduledPhaseTask(electionId); // 清除可能存在的旧同ID阶段任务
        BukkitTask phaseTask = new BukkitRunnable() {
            @Override
            public void run() {
                plugin.getLogger().info("[ElectionManager] Phase task running: advancing election " + electionId + " to voting.");
                scheduledPhaseTasks.remove(electionId); // 任务已执行
                advanceElectionToVoting(electionId);
            }
        }.runTaskLater(plugin, Math.max(1, registrationDurationTicks));
        scheduledPhaseTasks.put(electionId, phaseTask);

        return election;
    }

    /**
     * 启动一个政党的党魁选举。
     * @param partyId 政党UUID
     * @param isScheduledCall 是否由周期性调度器调用
     * @return 如果成功启动，返回创建的Election对象；否则返回null。
     */
    public Election startPartyLeaderElection(UUID partyId, boolean isScheduledCall) {
        Party party = partyManager.getParty(partyId);
        if (party == null) {
            plugin.getLogger().warning("[ElectionManager] startPartyLeaderElection: Attempted for non-existent party UUID: " + partyId);
            return null;
        }

        Election existingActive = getActiveElection(partyId, ElectionType.PARTY_LEADER);
        if (existingActive != null) {
            if (!isScheduledCall) {
                messageManager.sendMessage(Bukkit.getConsoleSender(), "election-already-active-party", "party", party.getName());
            }
            plugin.getLogger().finer("[ElectionManager] startPartyLeaderElection: Party " + party.getName() + " already has an active leader election (Status: " + existingActive.getStatus() + ").");
            return existingActive;
        }

        int minMembers = plugin.getConfig().getInt("party.leader_election.min_members_to_auto_elect", 5);
        if (party.getOfficialMemberIds().size() < minMembers) {
            plugin.getLogger().info("[ElectionManager] Party " + party.getName() + " has " + party.getOfficialMemberIds().size() + "/" + minMembers + " members. Leader election not started.");
            if (!isScheduledCall) messageManager.sendMessage(Bukkit.getConsoleSender(), "party-leader-election-fail-min-members", "party", party.getName(), "min", String.valueOf(minMembers));
            // 重新安排到下一个周期 (如果这是自动调度的一部分)
            if (isScheduledCall) scheduleNextPartyLeaderElection(partyId);
            return null;
        }

        UUID electionId = UUID.randomUUID();
        Election election = new Election(electionId, partyId, ElectionType.PARTY_LEADER);

        long registrationDurationTicks = getConfiguredDurationTicksForParty("registration_duration_seconds", 12 * 3600); // 默认12小时
        long votingDurationTicks = getConfiguredDurationTicksForParty("voting_duration_seconds", 24 * 3600);     // 默认24小时
        long currentTime = System.currentTimeMillis();

        election.setStartTime(currentTime);
        election.setRegistrationEndTime(currentTime + registrationDurationTicks * 50L);
        election.setEndTime(election.getRegistrationEndTime() + votingDurationTicks * 50L);
        election.setStatus(ElectionStatus.REGISTRATION);

        electionsById.put(electionId, election);
        saveElectionState(election);

        broadcastToPartyMembers(party, "election-registration-start-party-leader", "party_name", party.getName());
        plugin.getLogger().info("[ElectionManager] Party Leader election registration started for party: " + party.getName() + " (ID: " + electionId + ")");

        cancelScheduledPhaseTask(electionId);
        BukkitTask phaseTask = new BukkitRunnable() {
            @Override
            public void run() {
                plugin.getLogger().info("[ElectionManager] Phase task running: advancing party leader election " + electionId + " to voting.");
                scheduledPhaseTasks.remove(electionId);
                advanceElectionToVoting(electionId);
            }
        }.runTaskLater(plugin, Math.max(1, registrationDurationTicks));
        scheduledPhaseTasks.put(electionId, phaseTask);
        return election;
    }

    /**
     * 将指定ID的选举从登记阶段推进到投票阶段。
     * 由 scheduledPhaseTasks 中的任务调用。
     * @param electionId 选举的UUID
     */
    public void advanceElectionToVoting(UUID electionId) {
        Election election = electionsById.get(electionId);
        if (election == null) {
            plugin.getLogger().warning("[ElectionManager] advanceElectionToVoting: Election with ID " + electionId + " not found in active map.");
            scheduledPhaseTasks.remove(electionId); // 清理无效任务引用
            return;
        }
        if (election.getStatus() != ElectionStatus.REGISTRATION) {
            plugin.getLogger().info("[ElectionManager] advanceElectionToVoting: Election " + electionId + " is not in REGISTRATION status (current: " + election.getStatus() + "). Aborting advancement.");
            // 如果状态不是登记中（例如已被取消或手动推进），则不再执行
            return;
        }

        // 确保当前时间确实晚于或等于登记截止时间
        if (System.currentTimeMillis() < election.getRegistrationEndTime()) {
            plugin.getLogger().warning("[ElectionManager] advanceElectionToVoting: Attempted to advance election " + electionId + " to voting prematurely. Current time: " + System.currentTimeMillis() + ", Reg End: " + election.getRegistrationEndTime() + ". Rescheduling.");
            cancelScheduledPhaseTask(electionId); // 取消当前（可能错误的）任务
            long newDelayTicks = Math.max(1, (election.getRegistrationEndTime() - System.currentTimeMillis()) / 50L);
            BukkitTask phaseTask = new BukkitRunnable() {
                @Override public void run() {
                    scheduledPhaseTasks.remove(electionId);
                    advanceElectionToVoting(electionId);
                }
            }.runTaskLater(plugin, newDelayTicks);
            scheduledPhaseTasks.put(electionId, phaseTask);
            return;
        }

        if (election.getCandidates().isEmpty()) {
            String contextName = getContextName(election.getContextId(), election.getType());
            messageManager.sendMessage(Bukkit.getConsoleSender(), "election-no-candidates", "election_type", election.getType().getDisplayName(), "context", contextName);
            cancelElection(election, "没有候选人参与"); // cancelElection会处理后续，包括从electionsById移除和归档
            return;
        }

        election.setStatus(ElectionStatus.VOTING);
        // 注意：election.setStartTime() 在这里指的是整个选举活动的开始，而不是投票阶段的开始。
        // 如果需要精确的投票阶段开始时间，可以在 Election 对象中添加一个 voteStartTime 字段。
        // 当前 endTime 已经是投票的截止时间。
        saveElectionState(election);

        String votingStartKey = "";
        if (election.getType() == ElectionType.PARLIAMENTARY) votingStartKey = "election-voting-start-parliament";
        else if (election.getType() == ElectionType.PRESIDENTIAL) votingStartKey = "election-voting-start-presidential";
        else if (election.getType() == ElectionType.PARTY_LEADER) votingStartKey = "election-voting-start-party-leader";

        broadcastToContext(election, votingStartKey, "context_name", getContextName(election.getContextId(), election.getType()));
        plugin.getLogger().info("[ElectionManager] " + election.getType().getDisplayName() + " voting started for: " + getContextName(election.getContextId(), election.getType()) + " (ID: " + electionId + ")");

        long votingDurationRemainingTicks = (election.getEndTime() - System.currentTimeMillis()) / 50L;
        if (votingDurationRemainingTicks <= 0) { // 投票时间已过或刚好到期
            plugin.getLogger().info("[ElectionManager] Voting duration for election " + electionId + " has already passed. Finishing immediately.");
            finishElection(electionId);
            return;
        }

        cancelScheduledPhaseTask(electionId); // 清除旧的（可能是登记结束）任务
        BukkitTask phaseTask = new BukkitRunnable() {
            @Override
            public void run() {
                plugin.getLogger().info("[ElectionManager] Phase task running: finishing election " + electionId + ".");
                scheduledPhaseTasks.remove(electionId);
                finishElection(electionId);
            }
        }.runTaskLater(plugin, Math.max(1, votingDurationRemainingTicks));
        scheduledPhaseTasks.put(electionId, phaseTask);
    }

    /**
     * 结束指定ID的选举，进行计票、结果判定、宣布结果、更新Towny领袖、归档并安排下一次。
     * 由 scheduledPhaseTasks 中的任务调用，或管理员命令强制调用（需额外处理）。
     * @param electionId 选举的UUID
     */
    public void finishElection(UUID electionId) {
        Election election = electionsById.get(electionId);
        if (election == null) {
            plugin.getLogger().warning("[ElectionManager] finishElection: Election with ID " + electionId + " not found.");
            scheduledPhaseTasks.remove(electionId); // 清理无效任务引用
            scheduledPhaseTasks.remove(electionId + "_archive");
            return;
        }

        // 允许从 AWAITING_TIE_RESOLUTION 状态完成 (例如管理员处理后)
        if (election.getStatus() != ElectionStatus.VOTING && election.getStatus() != ElectionStatus.AWAITING_TIE_RESOLUTION) {
            plugin.getLogger().info("[ElectionManager] finishElection: Election " + electionId + " is not in VOTING or AWAITING_TIE_RESOLUTION status (current: " + election.getStatus() + "). Aborting finish.");
            return;
        }

        // 确保当前时间确实晚于或等于投票截止时间 (除非是特殊状态如平票处理后)
        if (election.getStatus() == ElectionStatus.VOTING && System.currentTimeMillis() < election.getEndTime()) {
            plugin.getLogger().warning("[ElectionManager] finishElection: Attempted to finish election " + electionId + " (Voting) prematurely. Rescheduling.");
            cancelScheduledPhaseTask(electionId); // 取消当前（可能错误的）任务
            long newDelayTicks = Math.max(1, (election.getEndTime() - System.currentTimeMillis()) / 50L);
            BukkitTask phaseTask = new BukkitRunnable() {
                @Override public void run() {
                    scheduledPhaseTasks.remove(electionId);
                    finishElection(electionId);
                }
            }.runTaskLater(plugin, newDelayTicks);
            scheduledPhaseTasks.put(electionId, phaseTask);
            return;
        }

        plugin.getLogger().info("[ElectionManager] Finishing " + election.getType().getDisplayName() + " (ID: " + election.getElectionId() + ") for context: " + getContextName(election.getContextId(), election.getType()));
        election.setStatus(ElectionStatus.COUNTING); // 进入计票状态
        // saveElectionState(election); // 可选：保存计票中状态，如果计票复杂且耗时

        determineElectionResults(election); // **核心：计算结果, 更新内部winner字段, 更新NationPolitics/Party的上次完成时间, 同步Towny King**

        // 如果 determineElectionResults 中因为平票等原因将状态设置为 AWAITING_TIE_RESOLUTION，则不继续
        if (election.getStatus() == ElectionStatus.AWAITING_TIE_RESOLUTION) {
            plugin.getLogger().info("[ElectionManager] Election " + electionId + " is now AWAITING_TIE_RESOLUTION. Finish process paused.");
            saveElectionState(election); // 保存此状态
            // 此时不安排归档或下一次选举，等待管理员处理或决选逻辑（如果实现）
            return;
        }

        election.setStatus(ElectionStatus.FINISHED); // 最终状态
        saveElectionState(election); // 保存包含最终结果和状态的选举数据

        // 结果公示期后归档
        long displayDurationTicks = plugin.getConfig().getLong("elections.results_display_duration_seconds", 43200) * 20L;
        final String archiveTaskKey = electionId.toString() + "_archive";
        cancelScheduledPhaseTask(UUID.fromString(archiveTaskKey)); // 清除可能存在的旧归档任务

        if (displayDurationTicks > 0) {
            BukkitTask archiveTask = new BukkitRunnable() {
                @Override
                public void run() {
                    scheduledPhaseTasks.remove(archiveTaskKey);
                    Election currentElectionState = electionsById.get(electionId); // 重新获取以防状态改变
                    if (currentElectionState != null && currentElectionState.getStatus() == ElectionStatus.FINISHED) {
                        archiveElection(currentElectionState);
                        electionsById.remove(electionId); // 从活跃列表移除
                    } else {
                        plugin.getLogger().warning("[ElectionManager] Archive task ran for election " + electionId + " but its state was not FINISHED. Archival skipped.");
                    }
                }
            }.runTaskLater(plugin, displayDurationTicks);
            scheduledPhaseTasks.put(UUID.fromString(archiveTaskKey), archiveTask);
        } else { // 无公示期，立即归档
            archiveElection(election);
            electionsById.remove(electionId);
        }

        // 安排下一次选举 (对于国家级周期性选举或配置了自动周期的党内选举)
        if (election.getType() == ElectionType.PARLIAMENTARY || election.getType() == ElectionType.PRESIDENTIAL) {
            UUID nationUUID = election.getContextId();
            Nation nationContext = TownyAPI.getInstance().getNation(nationUUID);
            if (nationContext != null) {
                plugin.getLogger().info("[ElectionManager] Triggering reschedule for nation " + nationContext.getName() + " after completion of " + election.getType().getDisplayName() + " (ID: " + electionId + ")");
                scheduleNextElectionForNation(nationUUID);
            }
        } else if (election.getType() == ElectionType.PARTY_LEADER) {
            Party partyContext = partyManager.getParty(election.getContextId());
            if (partyContext != null && plugin.getConfig().getLong("party.leader_election.auto_schedule_interval_days", 0) > 0) {
                plugin.getLogger().info("[ElectionManager] Triggering reschedule for party " + partyContext.getName() + " after leader election completion (ID: " + electionId + ")");
                scheduleNextPartyLeaderElection(partyContext.getPartyId());
            }
        }
    }

    /**
     * 取消一个正在进行的选举。
     * @param election 要取消的选举对象
     * @param reason 取消原因，会向相关方通知
     */
    public void cancelElection(Election election, String reason) {
        if (election == null || election.getStatus() == ElectionStatus.FINISHED || election.getStatus() == ElectionStatus.CANCELLED) {
            return; // 已经结束或取消的，无法再次取消
        }
        String contextName = getContextName(election.getContextId(), election.getType());
        plugin.getLogger().info("[ElectionManager] Cancelling " + election.getType().getDisplayName() + " (ID: " + election.getElectionId() + ") for " + contextName + ". Reason: " + reason);

        // 取消所有相关的阶段性调度任务
        cancelScheduledPhaseTask(election.getElectionId()); // 取消可能的投票结束任务或登记结束任务
        cancelScheduledPhaseTask(UUID.fromString(election.getElectionId() + "_archive")); // 取消可能的归档任务

        election.setStatus(ElectionStatus.CANCELLED);
        updateLastCompletionTime(election); // 取消的选举也更新时间戳 (或不更新，取决于是否想它尽快重试)
        // 当前 updateLastCompletionTime 实现是 status == FINISHED 才更新，所以取消的不更新。
        // 如果希望取消后也算一个周期，需要调整 updateLastCompletionTime
        saveElectionState(election); // 保存取消状态

        // 决定是否立即归档或保留在活跃列表一段时间
        // archiveElection(election); // 可选：也归档取消的选举
        electionsById.remove(election.getElectionId()); // 立即从活跃列表移除

        broadcastToContext(election, "election-cancelled",
                "context_name", contextName,
                "election_type", election.getType().getDisplayName(),
                "reason", reason);

        // 既然选举被取消了，应该尝试重新调度下一个周期
        if (election.getType() == ElectionType.PARLIAMENTARY || election.getType() == ElectionType.PRESIDENTIAL) {
            scheduleNextElectionForNation(election.getContextId());
        } else if (election.getType() == ElectionType.PARTY_LEADER) {
            scheduleNextPartyLeaderElection(election.getContextId());
        }
    }

    // --- 结果判定与宣布 ---

    /**
     * 核心方法：计算选举结果，更新Election对象的winner字段，
     * 并调用 updateLastCompletionTime 和 applyElectionResultsToTowny。
     * @param election 需要判定结果的选举对象 (其状态应为 COUNTING)
     */
    private void determineElectionResults(Election election) {
        GovernmentType govTypeForNationElection = election.getNationGovernmentTypeCache().orElse(null);
        if (election.getType() != ElectionType.PARTY_LEADER && govTypeForNationElection == null) {
            // 对于国家选举，政体缓存是必需的，因为选举规则依赖它
            // 如果没有，尝试获取当前政体，但这可能与选举开始时的政体不同
            Nation nationCtx = TownyAPI.getInstance().getNation(election.getContextId());
            if (nationCtx != null) {
                govTypeForNationElection = nationManager.getNationPolitics(nationCtx).getGovernmentType();
                election.setNationGovernmentTypeCache(govTypeForNationElection); // 更新缓存
                plugin.getLogger().warning("Nation government type cache was missing for election " + election.getElectionId() + "; using current type: " + govTypeForNationElection.getDisplayName());
            } else {
                plugin.getLogger().severe("Cannot determine election results for nation election " + election.getElectionId() + ": Nation context not found and government type cache is missing.");
                election.setStatus(ElectionStatus.CANCELLED); // 无法处理，取消
                // updateLastCompletionTime(election); // 取消的不更新
                // applyElectionResultsToTowny(election); // 无结果可应用
                return;
            }
        }

        List<Candidate> candidates = new ArrayList<>(election.getCandidates()); // 获取副本进行操作
        // 确保候选人名称缓存被填充，以便日志和消息中使用
        candidates.forEach(c -> {
            if (c.getPlayerNameCache() == null) c.setPlayerNameCache(c.getResolvedPlayerName());
            if (c.getPartyUUID() != null && c.getPartyNameCache() == null) {
                Party p = partyManager.getParty(c.getPartyUUID());
                if (p != null) c.setPartyNameCache(p.getName());
            }
        });
        candidates.sort(Comparator.comparingInt(Candidate::getVotes).reversed()); // 按票数降序

        if (candidates.isEmpty() && election.getType() != ElectionType.PARLIAMENTARY) {
            election.setWinnerPlayerUUID(null); // 清除可能存在的旧获胜者
            plugin.getLogger().info("No candidates in " + election.getType() + " election: " + election.getElectionId() + ". No winner determined.");
        } else {
            switch (election.getType()) {
                case PRESIDENTIAL:
                case PARTY_LEADER:
                    handleSingleWinnerElection(election, candidates);
                    break;
                case PARLIAMENTARY:
                    Map<UUID, Integer> partyTotalVotes = new HashMap<>();
                    for (Candidate c : candidates) {
                        if (c.getPartyUUID() != null) {
                            partyTotalVotes.merge(c.getPartyUUID(), c.getVotes(), Integer::sum);
                        }
                    }

                    if (partyTotalVotes.isEmpty() && !candidates.isEmpty()) { // 有独立候选人但没有政党获得选票
                        plugin.getLogger().info("Parliamentary election " + election.getElectionId() + " had candidates but no party received votes (e.g. only independents ran and config disallows them seats).");
                        election.setWinnerPartyUUID(null);
                        election.setPartySeatDistribution(new HashMap<>());
                    } else if (partyTotalVotes.isEmpty() && candidates.isEmpty()){
                        plugin.getLogger().info("No parties or candidates received votes in parliamentary election: " + election.getElectionId());
                        election.setWinnerPartyUUID(null);
                        election.setPartySeatDistribution(new HashMap<>());
                    }
                    else {
                        int totalParliamentSeats = getConfiguredTotalParliamentSeats(election.getContextId(), govTypeForNationElection);
                        double representationThresholdPercent = plugin.getConfig().getDouble("elections.parliament.representation_threshold_percent", 0.0);
                        long totalVotesCastInElection = partyTotalVotes.values().stream().mapToLong(Integer::intValue).sum();

                        Map<UUID, Integer> eligiblePartyVotes = partyTotalVotes.entrySet().stream()
                                .filter(entry -> {
                                    if (totalVotesCastInElection == 0 && entry.getValue() > 0) return true; // 如果只有这个党有票
                                    if (totalVotesCastInElection == 0) return false;
                                    double percentage = (double) entry.getValue() * 100.0 / totalVotesCastInElection;
                                    return percentage >= representationThresholdPercent;
                                })
                                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));

                        if(eligiblePartyVotes.isEmpty() && !partyTotalVotes.isEmpty()){
                            plugin.getLogger().info("No parties met the representation threshold of " + representationThresholdPercent + "% in parliamentary election: " + election.getElectionId());
                            election.setWinnerPartyUUID(null);
                            election.setPartySeatDistribution(new HashMap<>());
                        } else if (eligiblePartyVotes.isEmpty() && partyTotalVotes.isEmpty()){
                            // This case should be caught by partyTotalVotes.isEmpty() above
                            plugin.getLogger().info("No eligible parties and no total votes in parliamentary election: " + election.getElectionId());
                            election.setWinnerPartyUUID(null);
                            election.setPartySeatDistribution(new HashMap<>());
                        }
                        else {
                            Map<UUID, Integer> seatDistribution = calculateSeatsLargestRemainderHare(eligiblePartyVotes, totalParliamentSeats);
                            election.setPartySeatDistribution(seatDistribution);

                            UUID majorityPartyUUID = seatDistribution.entrySet().stream()
                                    .filter(entry -> entry.getValue() > 0) // 必须获得至少一个席位
                                    .max(Map.Entry.comparingByValue())
                                    .map(Map.Entry::getKey)
                                    .orElse(null);
                            election.setWinnerPartyUUID(majorityPartyUUID);

                            if (majorityPartyUUID != null) {
                                Party p = partyManager.getParty(majorityPartyUUID);
                                String partyName = p != null ? p.getName() : "ID: " + majorityPartyUUID.toString().substring(0,6);
                                plugin.getLogger().info("Majority party in " + getContextName(election.getContextId(), election.getType()) + " is " + partyName + " with " + seatDistribution.get(majorityPartyUUID) + " seats.");
                            } else {
                                plugin.getLogger().info("No majority party determined (no party won seats or tie for zero seats) in " + getContextName(election.getContextId(), election.getType()) + " parliamentary election.");
                            }
                        }
                    }
                    break;
            }
        }
        // 这两个方法依赖选举结果的计算，所以放在 determineElectionResults 的末尾
        updateLastCompletionTime(election); // 更新完成时间戳 (如果选举是FINISHED)
        applyElectionResultsToTowny(election); // 应用结果到Towny（如设置国王）
    }

    private void handleSingleWinnerElection(Election election, List<Candidate> sortedCandidates) {
        if (sortedCandidates.isEmpty()) {
            election.setWinnerPlayerUUID(null);
            plugin.getLogger().info("No candidates in single-winner election: " + election.getElectionId() + ". No winner determined.");
            return;
        }

        List<Candidate> leadingCandidates = election.getLeadingCandidates();

        if (leadingCandidates.isEmpty()){ // Should not happen if sortedCandidates is not empty
            election.setWinnerPlayerUUID(null);
            plugin.getLogger().warning("leadingCandidates was empty despite sortedCandidates having entries for election: " + election.getElectionId());
            return;
        }

        if (leadingCandidates.size() == 1) {
            election.setWinnerPlayerUUID(leadingCandidates.get(0).getPlayerUUID());
            plugin.getLogger().info("Winner of " + election.getType() + " election " + election.getElectionId() + " is " + leadingCandidates.get(0).getResolvedPlayerName());
        } else { // 平票 (leadingCandidates.size() > 1)
            plugin.getLogger().info("Tie detected in " + election.getType() + " election " + election.getElectionId() + " between: " +
                    leadingCandidates.stream().map(Candidate::getResolvedPlayerName).collect(Collectors.joining(", ")));

            String tieBreakingMethod = plugin.getConfig().getString("elections.tie_breaking_method", "RANDOM").toUpperCase();
            UUID winnerUUID = null;
            switch (tieBreakingMethod) {
                case "RE_ELECTION":
                    plugin.getLogger().warning("RE_ELECTION tie-breaking not yet implemented for election " + election.getElectionId() + ". Setting status to AWAITING_TIE_RESOLUTION.");
                    election.setStatus(ElectionStatus.AWAITING_TIE_RESOLUTION); // 标记需要处理
                    // TODO: Implement re-election logic (create new election with these candidates)
                    // For now, no winner is set, and the election finish process will pause.
                    break;
                case "ADMIN_DECIDES":
                    plugin.getLogger().warning("ADMIN_DECIDES tie-breaking required for election " + election.getElectionId() + ". Setting status to AWAITING_TIE_RESOLUTION.");
                    election.setStatus(ElectionStatus.AWAITING_TIE_RESOLUTION);
                    broadcastToAdmins("election-tie-admin-decision-needed",
                            "election_id", election.getElectionId().toString(),
                            "type", election.getType().getDisplayName(),
                            "context", getContextName(election.getContextId(),election.getType()),
                            "candidates", leadingCandidates.stream().map(Candidate::getResolvedPlayerName).collect(Collectors.joining(", "))
                    );
                    // TODO: Need admin command to set winner for an AWAITING_TIE_RESOLUTION election.
                    break;
                case "RANDOM":
                default:
                    winnerUUID = leadingCandidates.get(new Random().nextInt(leadingCandidates.size())).getPlayerUUID();
                    election.setWinnerPlayerUUID(winnerUUID);
                    plugin.getLogger().info("Randomly selected " + Bukkit.getOfflinePlayer(winnerUUID).getName() + " as winner for election " + election.getElectionId() + " due to tie.");
                    break;
            }
        }
    }

    private Map<UUID, Integer> calculateSeatsLargestRemainderHare(Map<UUID, Integer> partyVotes, int totalSeats) {
        Map<UUID, Integer> seatDistribution = new HashMap<>();
        if (partyVotes.isEmpty() || totalSeats <= 0) {
            plugin.getLogger().finer("Cannot calculate seats: partyVotes empty or totalSeats zero/negative.");
            return seatDistribution;
        }

        long totalVoteCount = partyVotes.values().stream().mapToLong(Integer::intValue).sum();
        if (totalVoteCount == 0) {
            plugin.getLogger().finer("Cannot calculate seats: totalVoteCount is zero.");
            return seatDistribution; // 没有有效投票
        }

        double quota = (double) totalVoteCount / totalSeats;
        if (quota <= 0) { // Avoid division by zero or issues if totalSeats is > totalVoteCount leading to tiny quota
            plugin.getLogger().warning("Quota is zero or negative in seat calculation. Total votes: " + totalVoteCount + ", Total seats: " + totalSeats + ". Cannot allocate seats proportionally.");
            // Fallback: give all seats to the party with most votes if quota is invalid? Or return empty.
            // For now, return empty if quota is not sensible.
            if (totalSeats > 0 && !partyVotes.isEmpty()) { // Attempt to give to highest vote if quota fails
                UUID topParty = partyVotes.entrySet().stream().max(Map.Entry.comparingByValue()).get().getKey();
                seatDistribution.put(topParty, totalSeats);
                plugin.getLogger().warning("Due to invalid quota, all " + totalSeats + " seats awarded to party with most votes: " + topParty);
                return seatDistribution;
            }
            return seatDistribution;
        }

        Map<UUID, Double> partyRemainders = new HashMap<>();
        int seatsAutomaticallyAllocated = 0;

        for (Map.Entry<UUID, Integer> entry : partyVotes.entrySet()) {
            UUID partyId = entry.getKey();
            int votes = entry.getValue();
            int seats = (int) (votes / quota);
            seatDistribution.put(partyId, seats);
            partyRemainders.put(partyId, votes - (seats * quota)); // Remainder based on votes
            seatsAutomaticallyAllocated += seats;
        }

        int remainingSeats = totalSeats - seatsAutomaticallyAllocated;
        if (remainingSeats > 0 && !partyRemainders.isEmpty()) {
            List<Map.Entry<UUID, Double>> sortedRemainders = partyRemainders.entrySet().stream()
                    .sorted(Map.Entry.<UUID, Double>comparingByValue().reversed()
                            .thenComparing((e1, e2) -> {
                                if (e1.getValue().equals(e2.getValue())) {
                                    return partyVotes.get(e2.getKey()).compareTo(partyVotes.get(e1.getKey())); // Tie-break by total votes
                                }
                                return 0;
                            }))
                    .collect(Collectors.toList());

            for (int i = 0; i < remainingSeats && i < sortedRemainders.size(); i++) {
                UUID partyIdToGetSeat = sortedRemainders.get(i).getKey();
                seatDistribution.merge(partyIdToGetSeat, 1, Integer::sum);
            }
        }
        return seatDistribution;
    }
    /**
     * 将选举结果（新总统或总理）应用到Towny国家的King设置上。
     * 由 determineElectionResults 调用。
     * @param election 已完成并判定出结果的选举对象
     */
    private void applyElectionResultsToTowny(Election election) {
        if (election.getStatus() != ElectionStatus.FINISHED && election.getStatus() != ElectionStatus.AWAITING_TIE_RESOLUTION) { // AWAITING_TIE_RESOLUTION means no winner yet
            // Only apply if truly finished with a winner
            if (election.getStatus() != ElectionStatus.AWAITING_TIE_RESOLUTION) {
                plugin.getLogger().finer("applyElectionResultsToTowny: Election " + election.getElectionId() + " not in a state to apply Towny King (Status: " + election.getStatus() + ")");
            }
            return;
        }
        if (election.getType() == ElectionType.PARTY_LEADER) return; // 党魁选举不影响Towny King

        Nation nation = TownyAPI.getInstance().getNation(election.getContextId());
        if (nation == null) {
            plugin.getLogger().warning("applyElectionResultsToTowny: Nation context " + election.getContextId() + " not found for election " + election.getElectionId());
            return;
        }

        NationPolitics politics = nationManager.getNationPolitics(nation);
        GovernmentType currentGovType = politics.getGovernmentType(); // 使用当前的政体

        OfflinePlayer newTownyKingCandidate = null;
        String townyKingRoleTitle = "";

        // 更新总理头衔（不直接改变Towny King，除非政体规定）
        if (election.getType() == ElectionType.PARLIAMENTARY && election.getWinnerPartyUUID().isPresent()) {
            Party winningParty = partyManager.getParty(election.getWinnerPartyUUID().get());
            if (winningParty != null && winningParty.getLeader().isPresent()) {
                OfflinePlayer majorityLeader = Bukkit.getOfflinePlayer(winningParty.getLeader().get().getPlayerId());
                politics.setPrimeMinisterUUID(majorityLeader.getUniqueId());
                plugin.getLogger().info("Set " + majorityLeader.getName() + " as Prime Minister (titular) for " + nation.getName() + " after parliamentary election.");
            }
        } else if (election.getType() == ElectionType.PRESIDENTIAL && election.getWinnerPlayerUUID().isPresent()) {
            // 在总统制，总统也是政府首脑，可以认为兼任“总理”角色
            if (currentGovType == GovernmentType.PRESIDENTIAL_REPUBLIC) {
                politics.setPrimeMinisterUUID(election.getWinnerPlayerUUID().get());
            }
            // 半总统制的总理任命在 applyElectionResultsToTowny 之外通过命令处理，或在议会选举后根据多数党自动设置（如果逻辑如此）
        }
        // 此处保存一次 politics，因为总理UUID可能已更新
        nationManager.saveNationPolitics(politics);


        // 决定谁将成为Towny King
        if (election.getType() == ElectionType.PRESIDENTIAL && election.getWinnerPlayerUUID().isPresent()) {
            if (currentGovType == GovernmentType.PRESIDENTIAL_REPUBLIC || currentGovType == GovernmentType.SEMI_PRESIDENTIAL_REPUBLIC) {
                newTownyKingCandidate = Bukkit.getOfflinePlayer(election.getWinnerPlayerUUID().get());
                townyKingRoleTitle = messageManager.getRawMessage("role-president", "总统");
            }
        } else if (election.getType() == ElectionType.PARLIAMENTARY && politics.getPrimeMinisterUUID().isPresent()) { // 使用 politics 中已更新的总理
            if (currentGovType == GovernmentType.PARLIAMENTARY_REPUBLIC || currentGovType == GovernmentType.CONSTITUTIONAL_MONARCHY) {
                newTownyKingCandidate = Bukkit.getOfflinePlayer(politics.getPrimeMinisterUUID().get());
                townyKingRoleTitle = messageManager.getRawMessage("role-prime-minister", "总理");
            }
        }


        if (newTownyKingCandidate != null && newTownyKingCandidate.getName() != null) {
            Resident newKingResident = TownyAPI.getInstance().getResident(newTownyKingCandidate.getUniqueId());
            if (newKingResident != null && newKingResident.hasNation() && newKingResident.getNationOrNull().equals(nation)) {
                try {
                    boolean kingChanged = false;
                    if (nation.hasKing()) {
                        if (!nation.getKing().equals(newKingResident)) {
                            kingChanged = true;
                        }
                    } else { // Nation had no king
                        kingChanged = true;
                    }

                    if (kingChanged) {
                        String oldKingName = nation.hasKing() ? nation.getKing().getName() : messageManager.getRawMessage("none-indicator", "无");
                        nation.setKing(newKingResident);
                        TownyAPI.getInstance().getDataSource().saveNation(nation); // 持久化Towny Nation对象
                        plugin.getLogger().info("Set " + newTownyKingCandidate.getName() + " (as " + townyKingRoleTitle + ") as the new Towny King of " + nation.getName() + ". Previous king: " + oldKingName);

                        String kingChangeMessage = messageManager.getFormattedPrefix() +
                                messageManager.getMessage("nation-new-towny-king",
                                        "nation_name", nation.getName(),
                                        "king_name", newTownyKingCandidate.getName(),
                                        "role_title", townyKingRoleTitle);
                        Bukkit.broadcastMessage(kingChangeMessage);
                    } else {
                        plugin.getLogger().info(townyKingRoleTitle + " " + newTownyKingCandidate.getName() + " is already the Towny King of " + nation.getName() + ". No change to Towny leadership.");
                    }
                } catch (Exception e) {
                    plugin.getLogger().log(Level.SEVERE, "Error setting Towny King for nation " + nation.getName() + " to " + newTownyKingCandidate.getName(), e);
                }
            } else {
                plugin.getLogger().warning("Cannot set " + newTownyKingCandidate.getName() + " as Towny King of " + nation.getName() + ". Target is not a resident of this nation, does not exist in Towny, or name is null.");
            }
        } else if (election.getType() == ElectionType.PARLIAMENTARY &&
                (currentGovType == GovernmentType.PARLIAMENTARY_REPUBLIC || currentGovType == GovernmentType.CONSTITUTIONAL_MONARCHY) &&
                !politics.getPrimeMinisterUUID().isPresent()) {
            plugin.getLogger().warning("Parliamentary election for " + nation.getName() + " did not result in a Prime Minister, so Towny King cannot be set through this election.");
        }
    }


    /**
     * 在选举成功结束（状态为FINISHED）后，更新对应上下文（国家或政党）的上次选举完成时间戳。
     * 由 determineElectionResults 调用。
     * @param election 已完成的选举对象
     */
    private void updateLastCompletionTime(Election election) {
        if (election.getStatus() != ElectionStatus.FINISHED) {
            // plugin.getLogger().finer("updateLastCompletionTime: Election " + election.getElectionId() + " not FINISHED (Status: " + election.getStatus() + "). Timestamp not updated.");
            return; // 只为成功完成的选举更新时间戳
        }

        long completionTime = System.currentTimeMillis(); // 使用当前时间作为完成时间

        if (election.getType() == ElectionType.PARLIAMENTARY || election.getType() == ElectionType.PRESIDENTIAL) {
            Nation nation = TownyAPI.getInstance().getNation(election.getContextId());
            if (nation != null) {
                NationPolitics politics = nationManager.getNationPolitics(nation);
                politics.setLastElectionCompletionTime(election.getType(), completionTime);
                nationManager.saveNationPolitics(politics); // 保存更新后的 NationPolitics
                plugin.getLogger().info("Recorded " + election.getType().getDisplayName() + " completion time (" + completionTime + ") for nation " + nation.getName());
            } else {
                plugin.getLogger().warning("updateLastCompletionTime: Could not find nation " + election.getContextId() + " for election " + election.getElectionId());
            }
        } else if (election.getType() == ElectionType.PARTY_LEADER) {
            Party party = partyManager.getParty(election.getContextId());
            if (party != null) {
                party.setLastLeaderElectionTime(completionTime);
                partyManager.saveParty(party); // 保存更新后的 Party
                plugin.getLogger().info("Recorded " + election.getType().getDisplayName() + " completion time (" + completionTime + ") for party " + party.getName());
            } else {
                plugin.getLogger().warning("updateLastCompletionTime: Could not find party " + election.getContextId() + " for election " + election.getElectionId());
            }
        }
    }

    /**
     * 向特定选举上下文（国家的所有公民或政党的所有成员）广播消息。
     * @param election 相关的选举对象，用于确定上下文和类型
     * @param messageKey 消息文件中的键
     * @param placeholders 占位符
     */
    private void broadcastToContext(Election election, String messageKey, Object... placeholders) {
        if (election == null) return;
        if (election.getType() == ElectionType.PARLIAMENTARY || election.getType() == ElectionType.PRESIDENTIAL) {
            Nation nation = TownyAPI.getInstance().getNation(election.getContextId());
            if (nation != null) broadcastToNation(nation, messageKey, placeholders);
        } else if (election.getType() == ElectionType.PARTY_LEADER) {
            Party party = partyManager.getParty(election.getContextId());
            if (party != null) broadcastToPartyMembers(party, messageKey, placeholders);
        }
    }

    /**
     * 向指定国家的所有在线居民广播消息（带插件前缀）。
     * @param nation 目标国家
     * @param messageKey 消息键
     * @param placeholders 占位符
     */
    private void broadcastToNation(Nation nation, String messageKey, Object... placeholders) {
        if (nation == null) return;
        String message = messageManager.getMessage(messageKey, placeholders); // 获取已处理占位符的消息体
        String prefixedMessage = messageManager.getFormattedPrefix() + message; // 添加前缀
        for (Resident resident : nation.getResidents()) {
            if (resident.isOnline()) {
                Player player = Bukkit.getPlayer(resident.getUUID());
                if (player != null) {
                    player.sendMessage(prefixedMessage);
                }
            }
        }
        plugin.getLogger().info("[Nation Broadcast to " + nation.getName() + "] " + message); // 控制台日志记录不带前缀的消息体
    }

    /**
     * 向指定政党的所有在线正式成员广播消息（带插件前缀）。
     * @param party 目标政党
     * @param messageKey 消息键
     * @param placeholders 占位符
     */
    private void broadcastToPartyMembers(Party party, String messageKey, Object... placeholders) {
        if (party == null) return;
        String message = messageManager.getMessage(messageKey, placeholders);
        String prefixedMessage = messageManager.getFormattedPrefix() + message;
        for(UUID memberId : party.getOfficialMemberIds()){
            OfflinePlayer offlineP = Bukkit.getOfflinePlayer(memberId);
            if(offlineP.isOnline()){
                Player p = offlineP.getPlayer();
                if(p != null) p.sendMessage(prefixedMessage);
            }
        }
        plugin.getLogger().info("[Party Broadcast to " + party.getName() + "] " + message);
    }

    /**
     * 向具有特定权限的在线管理员广播消息（带插件前缀）。
     * @param messageKey 消息键
     * @param placeholders 占位符
     */
    private void broadcastToAdmins(String messageKey, Object... placeholders) {
        String message = messageManager.getMessage(messageKey, placeholders);
        String prefixedMessage = messageManager.getFormattedPrefix() + message;
        plugin.getServer().getOnlinePlayers().stream()
                .filter(p -> p.hasPermission("townypolitical.admin.notifications"))
                .forEach(p -> p.sendMessage(prefixedMessage));
        plugin.getLogger().info("[Admin Notification] " + message);
    }

    // --- Helpers & Utils ---
    /**
     * 取消一个周期性选举调度任务。
     * @param taskKey 任务的唯一键 (例如 contextId + "_" + typeName)
     */
    private void cancelScheduledCycleTask(String taskKey) {
        BukkitTask existingTask = scheduledCycleTasks.remove(taskKey);
        if (existingTask != null) {
            try {
                if (!existingTask.isCancelled()) existingTask.cancel();
            } catch (Exception e) {
                plugin.getLogger().finer("Minor error cancelling cycle task " + taskKey + ": " + e.getMessage());
            }
        }
    }

    /**
     * 取消一个选举的阶段性推进任务（包括可能的归档任务）。
     * @param electionId 选举的UUID，作为任务键或任务键的一部分。
     */
    private void cancelScheduledPhaseTask(UUID electionId) {
        if (electionId == null) return;
        BukkitTask mainPhaseTask = scheduledPhaseTasks.remove(electionId);
        if (mainPhaseTask != null) {
            try {
                if (!mainPhaseTask.isCancelled()) mainPhaseTask.cancel();
            } catch (Exception e) { /* ignore */ }
        }
        BukkitTask archiveTask = scheduledPhaseTasks.remove(electionId.toString() + "_archive"); // 归档任务的key约定
        if (archiveTask != null) {
            try {
                if (!archiveTask.isCancelled()) archiveTask.cancel();
            } catch (Exception e) { /* ignore */ }
        }
    }

    /**
     * 通过选举ID查找一个当前活跃（或在公示期）的选举。
     * @param electionId 选举UUID
     * @return Optional<Election>
     */
    public Election findElectionById(UUID electionId) {
        return electionsById.get(electionId);
    }

    /**
     * 获取指定上下文和类型的当前活跃选举（非FINISHED或CANCELLED）。
     * @param contextUUID 上下文ID (Nation/Party)
     * @param type 选举类型
     * @return Optional<Election>
     */
    public Election getActiveElection(UUID contextUUID, ElectionType type) {
        if (contextUUID == null || type == null) return null;
        return electionsById.values().stream()
                .filter(e -> e.getContextId().equals(contextUUID) &&
                        e.getType() == type &&
                        e.getStatus() != ElectionStatus.FINISHED &&
                        e.getStatus() != ElectionStatus.CANCELLED)
                .findFirst().orElse(null);
    }

    /**
     * 获取指定上下文所有活跃的选举。
     * @param contextUUID 上下文ID
     * @return 活跃选举列表
     */
    public List<Election> getAllActiveElectionsForContext(UUID contextUUID) {
        if (contextUUID == null) return Collections.emptyList();
        return electionsById.values().stream()
                .filter(e -> e.getContextId().equals(contextUUID) &&
                        e.getStatus() != ElectionStatus.FINISHED &&
                        e.getStatus() != ElectionStatus.CANCELLED)
                .collect(Collectors.toList());
    }

    /**
     * 获取特定上下文（如国家或政党）的易读名称。
     * @param contextId 上下文UUID
     * @param type 选举类型，用于判断上下文是国家还是政党
     * @return 上下文的名称，如果找不到则返回默认字符串。
     */
    public String getContextName(UUID contextId, ElectionType type) {
        if (contextId == null || type == null) return "未知上下文";
        if (type == ElectionType.PARLIAMENTARY || type == ElectionType.PRESIDENTIAL) {
            Nation nation = TownyAPI.getInstance().getNation(contextId);
            return nation != null ? nation.getName() : "未知国家 (ID: " + contextId.toString().substring(0,6) + ")";
        } else if (type == ElectionType.PARTY_LEADER) {
            Party party = partyManager.getParty(contextId);
            return party != null ? party.getName() : "未知政党 (ID: " + contextId.toString().substring(0,6) + ")";
        }
        return "未知上下文类型";
    }

    // --- Configuration Getters ---
    private long getConfiguredElectionIntervalTicks(GovernmentType govType, ElectionType electionType) {
        String path = "elections.nation_election_schedule.";
        if (electionType == ElectionType.PARLIAMENTARY && (govType != null && govType.hasParliament())) {
            path += "parliamentary.interval_days";
        } else if (electionType == ElectionType.PRESIDENTIAL && (govType != null && govType.hasDirectPresidentialElection())) {
            path += "presidential.interval_days";
        } else {
            return 0; // 此政体不支持此类型选举, 或 govType 为 null (不应发生)
        }
        double days = plugin.getConfig().getDouble(path, 0); // 默认0天，表示不自动调度
        if (days <= 0) return 0;
        return (long) (days * 24 * 60 * 60 * 20); // days to ticks
    }

    private long getConfiguredDurationTicks(String pathSuffixFromElectionNode, long defaultValueSeconds) {
        // elections.registration_duration_seconds
        // elections.voting_duration_seconds
        return plugin.getConfig().getLong("elections." + pathSuffixFromElectionNode, defaultValueSeconds) * 20L;
    }

    private long getConfiguredDurationTicksForParty(String pathSuffixFromPartyLeaderNode, long defaultValueSeconds) {
        // party.leader_election.registration_duration_seconds
        // party.leader_election.voting_duration_seconds
        return plugin.getConfig().getLong("party.leader_election." + pathSuffixFromPartyLeaderNode, defaultValueSeconds) * 20L;
    }

    private int getConfiguredTotalParliamentSeats(UUID nationUUID, GovernmentType govType) {
        // 未来可以为不同国家或政体类型配置不同席位数
        // String nationSpecificPath = "nations." + nationUUID.toString() + ".parliament_seats";
        // if (plugin.getConfig().contains(nationSpecificPath)) return plugin.getConfig().getInt(nationSpecificPath);
        return plugin.getConfig().getInt("elections.parliament.total_seats", 100);
    }
    // --- Data Persistence ---

    /**
     * 从磁盘加载所有活跃（未归档）的选举数据到内存中。
     * 同时会尝试恢复这些选举未完成的阶段性调度任务。
     */
    public void loadActiveElections() {
        electionsById.clear(); // 清空内存中的旧数据
        plugin.getLogger().info("[ElectionManager] Loading active elections data from disk...");
        if (!activeElectionsDataFolder.exists() || !activeElectionsDataFolder.isDirectory()) {
            plugin.getLogger().warning("[ElectionManager] Active elections data folder not found. No elections loaded.");
            return;
        }

        File[] electionFiles = activeElectionsDataFolder.listFiles((dir, name) -> name.toLowerCase().endsWith(ELECTION_FILE_EXTENSION));
        if (electionFiles == null || electionFiles.length == 0) {
            plugin.getLogger().info("[ElectionManager] No active election files found to load.");
            return;
        }

        int loadedCount = 0;
        for (File electionFile : electionFiles) {
            YamlConfiguration config = new YamlConfiguration();
            try {
                config.load(electionFile); // 从文件加载数据

                UUID electionId = UUID.fromString(config.getString("electionId"));
                UUID contextId = UUID.fromString(config.getString("contextId"));
                ElectionType type = ElectionType.fromString(config.getString("type"))
                        .orElseThrow(() -> new IllegalArgumentException("Invalid ElectionType in file " + electionFile.getName()));

                Election election = new Election(electionId, contextId, type); // 使用包含ID的构造

                election.setStatus(ElectionStatus.fromString(config.getString("status", "NONE"))
                        .orElse(ElectionStatus.NONE));
                if (config.contains("nationGovernmentTypeCache")) {
                    GovernmentType.fromString(config.getString("nationGovernmentTypeCache"))
                            .ifPresent(election::setNationGovernmentTypeCache);
                }
                election.setStartTime(config.getLong("startTime"));
                election.setEndTime(config.getLong("endTime"));
                election.setRegistrationEndTime(config.getLong("registrationEndTime"));

                if (config.isConfigurationSection("candidates")) {
                    ConfigurationSection candidatesSection = config.getConfigurationSection("candidates");
                    for (String candidateUuidStr : candidatesSection.getKeys(false)) {
                        try {
                            UUID playerUUID = UUID.fromString(candidateUuidStr);
                            UUID partyUUID = config.contains("candidates." + candidateUuidStr + ".partyUUID") ?
                                    UUID.fromString(config.getString("candidates." + candidateUuidStr + ".partyUUID")) : null;
                            Candidate candidate = new Candidate(playerUUID, partyUUID);
                            candidate.setVotes(config.getInt("candidates." + candidateUuidStr + ".votes"));
                            if (config.contains("candidates." + candidateUuidStr + ".playerNameCache")) {
                                candidate.setPlayerNameCache(config.getString("candidates." + candidateUuidStr + ".playerNameCache"));
                            }
                            if (config.contains("candidates." + candidateUuidStr + ".partyNameCache")) {
                                candidate.setPartyNameCache(config.getString("candidates." + candidateUuidStr + ".partyNameCache"));
                            }
                            election.addCandidate(candidate);
                        } catch (IllegalArgumentException e) {
                            plugin.getLogger().warning("[ElectionManager] Skipping invalid candidate entry in " + electionFile.getName() + ": " + e.getMessage());
                        }
                    }
                }

                if (config.isList("voters")) {
                    config.getStringList("voters").forEach(voterUuidStr -> {
                        try { election.getVotersInternal().add(UUID.fromString(voterUuidStr)); } // 需要一个内部访问voters的方法
                        catch (IllegalArgumentException e) {plugin.getLogger().warning("[ElectionManager] Skipping invalid voter UUID in " + electionFile.getName());}
                    });
                }

                if (config.contains("winnerPlayerUUID")) election.setWinnerPlayerUUID(UUID.fromString(config.getString("winnerPlayerUUID")));
                if (config.contains("winnerPartyUUID")) election.setWinnerPartyUUID(UUID.fromString(config.getString("winnerPartyUUID")));

                if (config.isConfigurationSection("partySeatDistribution")) {
                    Map<UUID, Integer> seatDist = new HashMap<>();
                    ConfigurationSection seatSection = config.getConfigurationSection("partySeatDistribution");
                    for (String partyUuidStr : seatSection.getKeys(false)) {
                        try { seatDist.put(UUID.fromString(partyUuidStr), seatSection.getInt(partyUuidStr));}
                        catch (IllegalArgumentException e) {plugin.getLogger().warning("[ElectionManager] Skipping invalid party UUID in seat distribution for " + electionFile.getName());}
                    }
                    election.setPartySeatDistribution(seatDist); // Election的setter会处理clear和putAll
                }

                // 如果选举已经结束或取消，则归档并跳过添加到 activeElections
                if (election.getStatus() == ElectionStatus.FINISHED || election.getStatus() == ElectionStatus.CANCELLED) {
                    archiveElectionFile(electionFile, election.getElectionId().toString() + "_" + election.getType().name() + ELECTION_FILE_EXTENSION);
                    plugin.getLogger().info("[ElectionManager] Archived previously concluded " + election.getType() + " (ID: " + election.getElectionId() + ") for " + getContextName(election.getContextId(), election.getType()));
                    continue; // 不加入活跃列表，也不恢复任务
                }

                electionsById.put(electionId, election); // 使用 electionId 作为 key
                loadedCount++;
                plugin.getLogger().info("[ElectionManager] Loaded active " + type + " (ID: " + electionId + ") for " + getContextName(contextId, type) + " with status " + election.getStatus());

                resumeScheduledTasksForElection(election); // 恢复此选举的阶段性任务

            } catch (IOException | InvalidConfigurationException | IllegalArgumentException e) {
                plugin.getLogger().log(Level.SEVERE, "Failed to load election from file: " + electionFile.getName(), e);
                moveCorruptedFile(electionFile, "election_load_error_");
            }
        }
        plugin.getLogger().info("[ElectionManager] Successfully loaded " + loadedCount + " active elections.");
    }

    /**
     * 保存指定选举的当前状态到磁盘文件。
     * @param election 要保存的选举对象
     */
    public void saveElectionState(Election election) {
        if (election == null) {
            plugin.getLogger().warning("[ElectionManager] Attempted to save a null election state.");
            return;
        }
        File electionFile = new File(activeElectionsDataFolder, election.getElectionId().toString() + ELECTION_FILE_EXTENSION);
        YamlConfiguration config = new YamlConfiguration();

        config.set("electionId", election.getElectionId().toString());
        config.set("contextId", election.getContextId().toString());
        config.set("type", election.getType().name());
        election.getNationGovernmentTypeCache().ifPresent(govType -> config.set("nationGovernmentTypeCache", govType.name()));
        config.set("status", election.getStatus().name());
        config.set("startTime", election.getStartTime());
        config.set("endTime", election.getEndTime());
        config.set("registrationEndTime", election.getRegistrationEndTime());

        if (!election.getCandidates().isEmpty()) {
            for (Candidate candidate : election.getCandidates()) {
                String path = "candidates." + candidate.getPlayerUUID().toString();
                config.set(path + ".votes", candidate.getVotes());
                if (candidate.getPartyUUID() != null) { // partyUUID是UUID不是Optional<UUID>
                    config.set(path + ".partyUUID", candidate.getPartyUUID().toString());
                }
                if (candidate.getPlayerNameCache() != null) {
                    config.set(path + ".playerNameCache", candidate.getPlayerNameCache());
                }
                if (candidate.getPartyNameCache() != null) {
                    config.set(path + ".partyNameCache", candidate.getPartyNameCache());
                }
            }
        }

        // 直接获取 voters set 来保存，因为 Election.getVoters() 返回的是副本
        if (!election.getVotersInternal().isEmpty()) { // 需要 Election.getVotersInternal()
            config.set("voters", election.getVotersInternal().stream().map(UUID::toString).collect(Collectors.toList()));
        }


        election.getWinnerPlayerUUID().ifPresent(uuid -> config.set("winnerPlayerUUID", uuid.toString()));
        election.getWinnerPartyUUID().ifPresent(uuid -> config.set("winnerPartyUUID", uuid.toString()));
        if(!election.getWinnerPlayerUUID().isPresent()) config.set("winnerPlayerUUID", null); // Ensure null is saved if not present
        if(!election.getWinnerPartyUUID().isPresent()) config.set("winnerPartyUUID", null);


        // 直接获取 partySeatDistribution map 来保存
        if (election.getPartySeatDistributionInternal() != null && !election.getPartySeatDistributionInternal().isEmpty()) { // 需要 Election.getPartySeatDistributionInternal()
            election.getPartySeatDistributionInternal().forEach((partyUUID, seats) ->
                    config.set("partySeatDistribution." + partyUUID.toString(), seats));
        }


        try {
            config.save(electionFile);
            plugin.getLogger().finer("Saved election state for ID: " + election.getElectionId() + ", Status: " + election.getStatus());
        } catch (IOException e) {
            plugin.getLogger().log(Level.SEVERE, "Could not save election state for ID: " + election.getElectionId(), e);
        }
    }

    /**
     * 当一个选举结束后，将其数据文件从活跃文件夹移动到归档文件夹。
     * @param election 已结束的选举对象
     */
    public void archiveElection(Election election) {
        if (election == null) return;
        if (election.getStatus() != ElectionStatus.FINISHED && election.getStatus() != ElectionStatus.CANCELLED) {
            plugin.getLogger().finer("archiveElection: Election " + election.getElectionId() + " not FINISHED or CANCELLED. Archival skipped.");
            return;
        }
        plugin.getLogger().info("Archiving " + election.getType() + " (ID: " + election.getElectionId() + ") for " + getContextName(election.getContextId(), election.getType()));
        // 确保最后状态已保存到活跃文件夹
        saveElectionState(election);
        // 从活跃文件夹移动到归档文件夹
        File activeFile = new File(activeElectionsDataFolder, election.getElectionId().toString() + ELECTION_FILE_EXTENSION);
        if (activeFile.exists()) {
            // 文件名可以加上时间戳或类型以更好地区分归档文件
            String archiveFileName = election.getElectionId().toString() + "_" + election.getType().name().toLowerCase() + "_" + election.getStatus().name().toLowerCase() + "_" + System.currentTimeMillis() + ELECTION_FILE_EXTENSION;
            archiveElectionFile(activeFile, archiveFileName);
        } else {
            plugin.getLogger().warning("archiveElection: Active file for election " + election.getElectionId() + " not found. Cannot archive.");
        }
    }

    private void archiveElectionFile(File electionFileToMove, String archiveFileName) {
        if (electionFileToMove == null || !electionFileToMove.exists()) return;
        File targetArchiveFile = new File(archivedElectionsDataFolder, archiveFileName);
        try {
            Files.move(electionFileToMove.toPath(), targetArchiveFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
            plugin.getLogger().info("Archived election file: " + electionFileToMove.getName() + " to " + targetArchiveFile.getName());
        } catch (IOException e) {
            plugin.getLogger().log(Level.WARNING, "Could not archive election file: " + electionFileToMove.getName() + ". It might be reloaded as active if not manually removed.", e);
            // 如果移动失败，可以考虑重命名以防止被重新加载为活跃，或者记录更严重的错误
            // boolean renamed = electionFileToMove.renameTo(new File(electionFileToMove.getParentFile(), electionFileToMove.getName() + ".archival_failed"));
            // if (!renamed) plugin.getLogger().severe("FAILED TO EVEN RENAME UNARCHIVED FILE: " + electionFileToMove.getName());
        }
    }

    /**
     * 当插件启动时，为从磁盘加载的、仍在进行中的选举恢复其阶段性调度任务。
     * @param election 从磁盘加载的选举对象
     */
    private void resumeScheduledTasksForElection(Election election) {
        if (election == null) return;
        long currentTime = System.currentTimeMillis();
        plugin.getLogger().info("Resuming scheduled tasks for election: " + election.getElectionId() + " (Status: " + election.getStatus() + ")");

        cancelScheduledPhaseTask(election.getElectionId()); // 清除任何可能残留的旧任务

        if (election.getStatus() == ElectionStatus.REGISTRATION) {
            if (currentTime < election.getRegistrationEndTime()) {
                long delayTicks = (election.getRegistrationEndTime() - currentTime) / 50L;
                BukkitTask phaseTask = new BukkitRunnable() {
                    @Override
                    public void run() {
                        scheduledPhaseTasks.remove(election.getElectionId());
                        advanceElectionToVoting(election.getElectionId());
                    }
                }.runTaskLater(plugin, Math.max(1, delayTicks));
                scheduledPhaseTasks.put(election.getElectionId(), phaseTask);
                plugin.getLogger().info("Rescheduled task to advance election " + election.getElectionId() + " to VOTING stage in " + delayTicks + " ticks.");
            } else { // 登记时间已过，立即尝试推进
                plugin.getLogger().info("Registration time for loaded election " + election.getElectionId() + " has passed. Advancing to voting now.");
                advanceElectionToVoting(election.getElectionId());
            }
        } else if (election.getStatus() == ElectionStatus.VOTING) {
            if (currentTime < election.getEndTime()) {
                long delayTicks = (election.getEndTime() - currentTime) / 50L;
                BukkitTask phaseTask = new BukkitRunnable() {
                    @Override
                    public void run() {
                        scheduledPhaseTasks.remove(election.getElectionId());
                        finishElection(election.getElectionId());
                    }
                }.runTaskLater(plugin, Math.max(1, delayTicks));
                scheduledPhaseTasks.put(election.getElectionId(), phaseTask);
                plugin.getLogger().info("Rescheduled task to FINISH election " + election.getElectionId() + " in " + delayTicks + " ticks.");
            } else { // 投票时间已过，立即尝试结束
                plugin.getLogger().info("Voting time for loaded election " + election.getElectionId() + " has passed. Finishing now.");
                finishElection(election.getElectionId());
            }
        } else if (election.getStatus() == ElectionStatus.AWAITING_TIE_RESOLUTION) {
            plugin.getLogger().info("Loaded election " + election.getElectionId() + " is AWAITING_TIE_RESOLUTION. No phase task resumed, requires admin action.");
        } else if (election.getStatus() == ElectionStatus.PENDING_START) {
            if (currentTime < election.getStartTime()) {
                long delayTicks = (election.getStartTime() - currentTime) / 50L;
                BukkitTask phaseTask = new BukkitRunnable() {
                    @Override public void run() {
                        scheduledPhaseTasks.remove(election.getElectionId());
                        // 当到达开始时间，通常是进入登记阶段
                        Election current = electionsById.get(election.getElectionId());
                        if(current != null && current.getStatus() == ElectionStatus.PENDING_START){
                            current.setStatus(ElectionStatus.REGISTRATION);
                            saveElectionState(current);
                            // 通知等逻辑（如果需要）
                            plugin.getLogger().info("Election " + current.getElectionId() + " has now started (REGISTRATION).");
                            // 安排下一个阶段任务
                            long regDurationTicks = (current.getRegistrationEndTime() - System.currentTimeMillis()) / 50L;
                            if(regDurationTicks > 0){
                                BukkitTask regEndTask = new BukkitRunnable() {
                                    @Override public void run() {
                                        scheduledPhaseTasks.remove(current.getElectionId());
                                        advanceElectionToVoting(current.getElectionId());
                                    }
                                }.runTaskLater(plugin, regDurationTicks);
                                scheduledPhaseTasks.put(current.getElectionId(), regEndTask);
                            } else {
                                advanceElectionToVoting(current.getElectionId()); // 登记时间也过了
                            }
                        }
                    }
                }.runTaskLater(plugin, Math.max(1,delayTicks));
                scheduledPhaseTasks.put(election.getElectionId(), phaseTask);
                plugin.getLogger().info("Rescheduled task for PENDING_START election " + election.getElectionId() + " to begin registration in " + delayTicks + " ticks.");
            } else { // 开始时间已过
                plugin.getLogger().info("Start time for PENDING_START election " + election.getElectionId() + " has passed. Setting to REGISTRATION and advancing.");
                election.setStatus(ElectionStatus.REGISTRATION);
                saveElectionState(election);
                resumeScheduledTasksForElection(election); // 重新调用以处理新的REGISTRATION状态
            }
        }
    }

    private void moveCorruptedFile(File file, String prefix) {
        if (file == null || !file.exists()) return;
        File corruptedFolder = new File(activeElectionsDataFolder.getParentFile(), "corrupted_data"); // 在 elections/corrupted_data
        if (!corruptedFolder.exists()) {
            if(!corruptedFolder.mkdirs()) {
                plugin.getLogger().severe("Could not create corrupted_data folder for elections!");
                return;
            }
        }
        File newFile = new File(corruptedFolder, prefix + file.getName() + "_" + System.currentTimeMillis() + ".yml_CORRUPTED");
        try {
            Files.move(file.toPath(), newFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
            plugin.getLogger().warning("Moved corrupted election file " + file.getName() + " to " + newFile.getPath());
        } catch (IOException ex) {
            plugin.getLogger().log(Level.SEVERE, "Could not move corrupted election file " + file.getName() + " after load failure.", ex);
        }
    }


    // --- Event Handlers (called by other managers or listeners) ---
    /**
     * 当国家政体变更时调用。
     * 会取消与该国旧政体相关的选举调度，并根据新政体重新安排。
     * 任何正在进行的、与新政体不兼容的选举将被取消。
     * @param nation 发生政体变更的国家
     * @param oldType 旧政体
     * @param newType 新政体
     */
    public void onGovernmentChange(Nation nation, GovernmentType oldType, GovernmentType newType) {
        if (nation == null) return;
        plugin.getLogger().info("[ElectionManager] Government changed for " + nation.getName() + " from " + oldType.getDisplayName() + " to " + newType.getDisplayName() + ". Updating election schedules and active elections.");

        // 1. 取消该国所有现有的周期性选举调度任务
        cancelScheduledCycleTask(nation.getUUID().toString() + "_" + ElectionType.PARLIAMENTARY.name());
        cancelScheduledCycleTask(nation.getUUID().toString() + "_" + ElectionType.PRESIDENTIAL.name());

        // 2. 处理该国当前活跃的选举
        List<Election> activeNationElections = getAllActiveElectionsForContext(nation.getUUID());
        for (Election activeElection : activeNationElections) {
            boolean compatible = false;
            if (activeElection.getType() == ElectionType.PARLIAMENTARY && newType.hasParliament()) {
                compatible = true;
            } else if (activeElection.getType() == ElectionType.PRESIDENTIAL && newType.hasDirectPresidentialElection()) {
                compatible = true;
            }

            if (!compatible) {
                cancelElection(activeElection, "国家政体已变更为 " + newType.getDisplayName() + "，不再支持此类选举。");
            } else {
                // 选举可以继续，但更新其政体缓存以反映最新情况（尽管选举规则应基于开始时的政体）
                activeElection.setNationGovernmentTypeCache(newType);
                saveElectionState(activeElection);
                plugin.getLogger().info("Active " + activeElection.getType().getDisplayName() + " for " +nation.getName()+ " (ID: " + activeElection.getElectionId() + ") will continue under new government type: " + newType.getDisplayName() + " (rules based on start still apply).");
            }
        }

        // 3. 清除该国上次选举完成时间记录，因为政体变了，选举周期可能也变了
        NationPolitics politics = nationManager.getNationPolitics(nation);
        if (politics != null) {
            politics.clearAllElectionCompletionTimes();
            nationManager.saveNationPolitics(politics); // 保存清除后的状态
        }

        // 4. 根据新政体重新安排所有必要的选举周期
        scheduleNextElectionForNation(nation.getUUID());
    }

    /**
     * 当一个Towny国家被删除时调用。
     * 会取消并删除与该国相关的所有选举数据和调度任务。
     * @param nationUUID 被删除国家的UUID
     */
    public void onNationDeleted(UUID nationUUID) {
        if (nationUUID == null) return;
        plugin.getLogger().info("[ElectionManager] Nation with UUID " + nationUUID + " deleted. Cleaning up related elections and schedules.");

        // 1. 取消该国所有周期性调度任务
        cancelScheduledCycleTask(nationUUID.toString() + "_" + ElectionType.PARLIAMENTARY.name());
        cancelScheduledCycleTask(nationUUID.toString() + "_" + ElectionType.PRESIDENTIAL.name());

        // 2. 取消并移除所有与该国相关的活跃选举
        List<Election> electionsToRemove = electionsById.values().stream()
                .filter(e -> e.getContextId().equals(nationUUID) &&
                        (e.getType() == ElectionType.PARLIAMENTARY || e.getType() == ElectionType.PRESIDENTIAL))
                .collect(Collectors.toList());

        for (Election election : electionsToRemove) {
            plugin.getLogger().info("Cancelling and removing election " + election.getElectionId() + " (Type: " + election.getType() + ") due to nation deletion.");
            cancelScheduledPhaseTask(election.getElectionId()); // 取消阶段任务
            cancelScheduledPhaseTask(UUID.fromString(election.getElectionId() + "_archive")); // 取消归档任务
            electionsById.remove(election.getElectionId()); // 从内存移除
            // 删除对应的活跃选举文件
            File electionFile = new File(activeElectionsDataFolder, election.getElectionId().toString() + ELECTION_FILE_EXTENSION);
            if (electionFile.exists()) {
                if (!electionFile.delete()) {
                    plugin.getLogger().warning("Could not delete active election data file: " + electionFile.getName() + " for deleted nation.");
                }
            }
        }
    }

    /**
     * 当一个政党解散时调用。
     * 会从所有活跃选举中移除该党的候选人。
     * @param disbandedParty 已解散的政党对象
     */
    public void onPartyDisband(Party disbandedParty) {
        if (disbandedParty == null) return;
        UUID partyId = disbandedParty.getPartyId();
        plugin.getLogger().info("[ElectionManager] Party " + disbandedParty.getName() + " (ID: " + partyId + ") disbanded. Removing its candidates from active elections.");

        // 1. 取消该党的党魁选举调度任务
        cancelScheduledCycleTask(partyId.toString() + "_" + ElectionType.PARTY_LEADER.name());

        // 2. 处理与该党相关的活跃选举 (通常是党魁选举，或者作为候选人党派的选举)
        List<Election> electionsToModify = new ArrayList<>();
        // 查找该党作为上下文的选举（党魁选举）
        electionsById.values().stream()
                .filter(e -> e.getContextId().equals(partyId) && e.getType() == ElectionType.PARTY_LEADER)
                .forEach(electionsToModify::add);

        for (Election election : electionsToModify) {
            plugin.getLogger().info("Cancelling party leader election " + election.getElectionId() + " for disbanded party " + disbandedParty.getName());
            cancelElection(election, "政党已解散"); // cancelElection 会处理移除和归档
        }

        // 从所有其他类型的活跃选举中移除该党的候选人
        for (Election election : electionsById.values()) { // 遍历所有活跃选举
            boolean changed = false;
            List<UUID> candidatesToRemove = new ArrayList<>();
            for (Candidate candidate : election.getCandidates()) {
                if (partyId.equals(candidate.getPartyUUID())) { // 如果候选人属于这个解散的党
                    candidatesToRemove.add(candidate.getPlayerUUID());
                }
            }
            for (UUID candidatePlayerId : candidatesToRemove) {
                if (election.removeCandidate(candidatePlayerId)) {
                    changed = true;
                    OfflinePlayer op = Bukkit.getOfflinePlayer(candidatePlayerId);
                    plugin.getLogger().info("Removed candidate " + (op.getName() != null ? op.getName() : candidatePlayerId) +
                            " from election " + election.getElectionId() + " (Type: " + election.getType() +
                            ") because their party " + disbandedParty.getName() + " disbanded.");
                }
            }
            if (changed) {
                // 如果选举仍在登记或投票阶段，且移除候选人后没有候选人了，则取消选举
                if ((election.getStatus() == ElectionStatus.REGISTRATION || election.getStatus() == ElectionStatus.VOTING) &&
                        election.getCandidates().isEmpty()) {
                    plugin.getLogger().info("Election " + election.getElectionId() + " now has no candidates after party disband. Cancelling election.");
                    cancelElection(election, "所有候选人因其政党解散而退出");
                } else {
                    saveElectionState(election); // 保存候选人列表的变更
                }
            }
        }
    }

    /**
     * 插件关闭时调用，用于保存数据和清理任务。
     */
    public void shutdown() {
        plugin.getLogger().info("[ElectionManager] Shutting down...");
        // 1. 取消所有周期性调度任务
        for (String taskKey : new HashSet<>(scheduledCycleTasks.keySet())) { // Iterate over a copy
            cancelScheduledCycleTask(taskKey);
        }
        scheduledCycleTasks.clear();
        plugin.getLogger().info("[ElectionManager] All cycle tasks cancelled.");

        // 2. 取消所有阶段性任务 (包括归档任务)
        for (UUID electionIdKey : new HashSet<>(scheduledPhaseTasks.keySet())) {
            // 这里的 key 可能是 electionId 或者 electionId + "_archive"
            // cancelScheduledPhaseTask 会尝试取消两者
            cancelScheduledPhaseTask(electionIdKey); // 这会尝试取消 electionId 和 electionId_archive
        }
        scheduledPhaseTasks.clear();
        plugin.getLogger().info("[ElectionManager] All phase tasks cancelled.");

        // 3. 保存所有当前活跃的选举状态
        plugin.getLogger().info("[ElectionManager] Saving all active election states ("+ electionsById.size() +")...");
        for (Election election : electionsById.values()) {
            saveElectionState(election);
        }
        plugin.getLogger().info("[ElectionManager] ElectionManager shutdown complete.");
    }

    // 需要在 Election.java 中添加以下方法来允许 ElectionManager 修改内部集合（或提供更细致的API）
    // public Set<UUID> getVotersInternal() { return this.voters; }
    // public Map<UUID, Integer> getPartySeatDistributionInternal() { return this.partySeatDistribution; }
    // 这样做是为了在 loadActiveElections 时可以直接填充集合，而不是通过 addVoter/setPartySeatDistribution
    // 这稍微破坏了 Election 的封装，但简化了加载逻辑。或者，Election 提供批量添加方法。
}