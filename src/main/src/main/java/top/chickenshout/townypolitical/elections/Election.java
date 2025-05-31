// 文件名: Election.java
// 结构位置: top/chickenshout/townypolitical/elections/Election.java
package top.chickenshout.townypolitical.elections;

import top.chickenshout.townypolitical.enums.ElectionStatus;
import top.chickenshout.townypolitical.enums.ElectionType;
import top.chickenshout.townypolitical.enums.GovernmentType;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * 代表一次具体的选举活动。
 * 存储选举的配置、状态、候选人、投票者和结果。
 */
public class Election {
    private final UUID electionId;         // 选举的唯一ID (由Manager分配)
    private final UUID contextId;          // 选举的上下文ID (例如 NationUUID 或 PartyUUID)
    private final ElectionType type;       // 选举类型 (议会, 总统, 党内领袖)
    private GovernmentType nationGovernmentTypeCache; // 国家选举时，缓存当时的国家政体 (重要，因为选举规则依赖它)

    private ElectionStatus status;
    private long startTime;                // 选举活动开始时间 (可能是登记开始，或直接投票开始)
    private long endTime;                  // 选举投票阶段结束时间
    private long registrationEndTime;      // 候选人登记截止时间 (如果适用)

    // 候选人列表 <PlayerUUID, Candidate>
    private final Map<UUID, Candidate> candidates;
    // 已投票的玩家UUID集合，防止重复投票
    private final Set<UUID> voters;

    // 结果相关
    private UUID winnerPlayerUUID;        // 总统选举或党魁选举的获胜者玩家UUID
    private UUID winnerPartyUUID;         // 议会选举中的多数党UUID
    // 议会选举席位分布 <PartyUUID, SeatsCount>
    private Map<UUID, Integer> partySeatDistribution;

    /**
     * 构造一个新的选举实例。
     * @param electionId 此选举的唯一ID (由Manager分配)
     * @param contextId 选举上下文ID (NationUUID或PartyUUID)
     * @param type 选举类型
     * @throws IllegalArgumentException 如果任一必要参数为null
     */
    public Election(UUID electionId, UUID contextId, ElectionType type) {
        if (electionId == null || contextId == null || type == null) {
            throw new IllegalArgumentException("Election ID, Context ID, and Type cannot be null.");
        }
        this.electionId = electionId;
        this.contextId = contextId;
        this.type = type;
        this.status = ElectionStatus.NONE; // 初始状态，通常由Manager在启动时设置为 PENDING_START 或 REGISTRATION
        this.candidates = new ConcurrentHashMap<>(); // 保证候选人列表的并发安全
        this.voters = Collections.synchronizedSet(new HashSet<>()); // 保证投票者集合的并发安全
        this.partySeatDistribution = new ConcurrentHashMap<>(); // 保证席位分布图的并发安全
    }

    // --- Getters ---
    public UUID getElectionId() {
        return electionId;
    }

    public UUID getContextId() {
        return contextId;
    }

    public ElectionType getType() {
        return type;
    }

    public Optional<GovernmentType> getNationGovernmentTypeCache() {
        return Optional.ofNullable(nationGovernmentTypeCache);
    }

    public ElectionStatus getStatus() {
        return status;
    }

    public long getStartTime() {
        return startTime;
    }

    public long getEndTime() {
        return endTime;
    }

    public long getRegistrationEndTime() {
        return registrationEndTime;
    }

    /**
     * 获取所有候选人的不可修改集合。
     * @return 候选人集合
     */
    public Collection<Candidate> getCandidates() {
        return Collections.unmodifiableCollection(candidates.values());
    }

    public Optional<Candidate> getCandidate(UUID playerUUID) {
        return Optional.ofNullable(candidates.get(playerUUID));
    }

    /**
     * 获取所有已投票玩家的UUID的不可修改集合。
     * @return 已投票者UUID集合
     */
    public Set<UUID> getVoters() {
        return Collections.unmodifiableSet(new HashSet<>(voters)); // 返回副本以保证外部不可修改原set
    }

    public Optional<UUID> getWinnerPlayerUUID() {
        return Optional.ofNullable(winnerPlayerUUID);
    }

    public Optional<UUID> getWinnerPartyUUID() {
        return Optional.ofNullable(winnerPartyUUID);
    }

    /**
     * 获取议会席位分布的不可修改Map。
     * @return 议会席位分布Map
     */
    public Map<UUID, Integer> getPartySeatDistribution() {
        return Collections.unmodifiableMap(new HashMap<>(partySeatDistribution)); // 返回副本
    }

    // --- Setters & Mutators ---
    public void setNationGovernmentTypeCache(GovernmentType nationGovernmentTypeCache) {
        this.nationGovernmentTypeCache = nationGovernmentTypeCache;
    }

    public void setStatus(ElectionStatus status) {
        if (status == null) throw new IllegalArgumentException("Election status cannot be null.");
        this.status = status;
    }

    public void setStartTime(long startTime) {
        this.startTime = startTime;
    }

    public void setEndTime(long endTime) {
        this.endTime = endTime;
    }

    public void setRegistrationEndTime(long registrationEndTime) {
        this.registrationEndTime = registrationEndTime;
    }

    /**
     * 添加一个候选人到选举中。
     * @param candidate 要添加的候选人对象
     * @return 如果成功添加返回true，如果候选人已存在或为null则返回false。
     */
    public boolean addCandidate(Candidate candidate) {
        if (candidate == null) {
            return false;
        }
        return candidates.putIfAbsent(candidate.getPlayerUUID(), candidate) == null;
    }

    /**
     * 从选举中移除一个候选人。
     * @param playerUUID 要移除的候选人的玩家UUID
     * @return 如果成功移除返回true，否则返回false。
     */
    public boolean removeCandidate(UUID playerUUID) {
        return candidates.remove(playerUUID) != null;
    }

    /**
     * 记录一次投票。
     * @param voterUUID 投票者UUID
     * @param candidateUUID 候选人UUID
     * @return 如果投票成功记录返回true；如果投票者已投票、候选人不存在或选举非投票状态，则返回false。
     */
    public boolean recordVote(UUID voterUUID, UUID candidateUUID) {
        if (voterUUID == null || candidateUUID == null) return false;
        if (this.status != ElectionStatus.VOTING) return false; // 只能在投票阶段投票
        if (voters.contains(voterUUID)) {
            return false; // 已经投过票
        }
        Candidate candidate = candidates.get(candidateUUID);
        if (candidate == null) {
            return false; // 候选人不存在
        }
        candidate.addVote();
        voters.add(voterUUID);
        return true;
    }

    public void setWinnerPlayerUUID(UUID winnerPlayerUUID) {
        this.winnerPlayerUUID = winnerPlayerUUID; // Nullable
    }

    public void setWinnerPartyUUID(UUID winnerPartyUUID) {
        this.winnerPartyUUID = winnerPartyUUID; // Nullable
    }

    /**
     * 设置议会席位分布。会替换现有的分布。
     * @param newPartySeatDistribution 新的席位分布Map，如果为null则清空。
     */
    public void setPartySeatDistribution(Map<UUID, Integer> newPartySeatDistribution) {
        this.partySeatDistribution.clear();
        if (newPartySeatDistribution != null) {
            this.partySeatDistribution.putAll(newPartySeatDistribution);
        }
    }

    /**
     * 清除所有投票记录和投票者信息，并将所有候选人的票数重置为0。
     * 通常在需要重新计票或选举作废时使用。
     */
    public void clearVotesAndVoters() {
        this.voters.clear();
        this.candidates.values().forEach(c -> c.setVotes(0));
    }

    // --- Logic Helpers ---

    public boolean isRegistrationOpen() {
        return status == ElectionStatus.REGISTRATION && System.currentTimeMillis() < registrationEndTime;
    }

    public boolean isVotingOpen() {
        return status == ElectionStatus.VOTING && System.currentTimeMillis() < endTime;
    }

    /**
     * 获取得票最多的候选人列表（可能存在平票）。
     * @return 得票最多的候选人列表 (不可修改)。如果无候选人或无投票，返回空列表。
     */
    public List<Candidate> getLeadingCandidates() {
        if (candidates.isEmpty()) {
            return Collections.emptyList();
        }
        int maxVotes = -1;
        // 找出最高票数
        for (Candidate c : candidates.values()) {
            if (c.getVotes() > maxVotes) {
                maxVotes = c.getVotes();
            }
        }
        if (maxVotes == -1) return Collections.emptyList(); // 没有有效投票或候选人（虽然前面检查了candidates.isEmpty()）

        final int finalMaxVotes = maxVotes; // for lambda
        return candidates.values().stream()
                .filter(c -> c.getVotes() == finalMaxVotes)
                .collect(Collectors.toUnmodifiableList());
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        Election election = (Election) o;
        return electionId.equals(election.electionId); // 选举的唯一标识是其ID
    }

    @Override
    public int hashCode() {
        return Objects.hash(electionId);
    }

    @Override
    public String toString() {
        return "Election{" +
                "electionId=" + electionId +
                ", contextId=" + contextId +
                ", type=" + type +
                ", status=" + status +
                ", candidates=" + candidates.size() +
                ", voters=" + voters.size() +
                (getWinnerPlayerUUID().map(uuid -> ", winnerPlayer=" + uuid.toString().substring(0,8)).orElse("")) +
                (getWinnerPartyUUID().map(uuid -> ", winnerParty=" + uuid.toString().substring(0,8)).orElse("")) +
                '}';
    }

    /**
     * (供 ElectionManager 加载数据时使用) 获取内部的投票者集合引用。
     * 警告：外部不应修改此集合，除非是在受控的加载过程中。
     * @return 内部投票者 Set
     */
    public Set<UUID> getVotersInternal() { // 改为 protected 或包可见
        return this.voters;
    }

    /**
     * (供 ElectionManager 加载数据时使用) 获取内部的席位分布Map引用。
     * 警告：外部不应修改此Map，除非是在受控的加载过程中。
     * @return 内部席位分布 Map
     */
    public Map<UUID, Integer> getPartySeatDistributionInternal() { // 改为 protected 或包可见
        return this.partySeatDistribution;
    }
}