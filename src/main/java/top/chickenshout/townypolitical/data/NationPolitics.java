// 文件名: NationPolitics.java
// 结构位置: top/chickenshout/townypolitical/data/NationPolitics.java
package top.chickenshout.townypolitical.data;

import top.chickenshout.townypolitical.enums.ElectionType;
import top.chickenshout.townypolitical.enums.GovernmentType;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 存储与Towny国家相关的政治信息。
 * 例如，国家的政体类型、上次选举时间、虚位君主和总理信息。
 * 这个对象通常与一个Towny的Nation对象通过UUID关联。
 */
public class NationPolitics {
    private final UUID nationUUID; // 对应 Towny Nation 的 UUID，这是主要的关联键
    private GovernmentType governmentType;
    // 存储国家级选举的上次完成时间 <ElectionType (PARLIAMENTARY/PRESIDENTIAL), Timestamp>
    private final Map<ElectionType, Long> lastElectionCompletionTimes;
    private UUID titularMonarchUUID = null; // 虚位君主的UUID (主要用于君主立宪制)
    private UUID primeMinisterUUID = null;  // 总理的UUID (主要用于半总统制、议会制、君主立宪制)
    // 存储议会构成 <PartyUUID, List<PlayerUUID_of_MP>>
    private final Map<UUID, List<UUID>> parliamentaryMembersByParty;
    // 存储议会选举后各党应有的席位数 <PartyUUID, Integer (seats won)>
    private final Map<UUID, Integer> parliamentarySeatsWonByParty;

    /**
     * 构造一个新的国家政治信息对象。
     * 政体默认为“议会制共和制”。
     *
     * @param nationUUID Towny国家的UUID。必须提供且不能为null。
     * @throws IllegalArgumentException 如果 nationUUID 为 null。
     */
    public NationPolitics(UUID nationUUID) {
        if (nationUUID == null) {
            throw new IllegalArgumentException("Nation UUID cannot be null.");
        }
        this.nationUUID = nationUUID;
        this.governmentType = GovernmentType.PARLIAMENTARY_REPUBLIC; // 默认政体
        this.lastElectionCompletionTimes = new EnumMap<>(ElectionType.class);
        this.titularMonarchUUID = null;
        this.primeMinisterUUID = null;
        this.parliamentaryMembersByParty = new ConcurrentHashMap<>(); // 初始化
        this.parliamentarySeatsWonByParty = new ConcurrentHashMap<>(); // 初始化
    }

    /**
     * 构造一个新的国家政治信息对象，并指定初始政体。
     *
     * @param nationUUID Towny国家的UUID。必须提供且不能为null。
     * @param initialGovernmentType 初始政体。必须提供且不能为null。
     * @throws IllegalArgumentException 如果 nationUUID 或 initialGovernmentType 为 null。
     */
    public NationPolitics(UUID nationUUID, GovernmentType initialGovernmentType) {
        if (nationUUID == null) {
            throw new IllegalArgumentException("Nation UUID cannot be null.");
        }
        if (initialGovernmentType == null) {
            throw new IllegalArgumentException("Initial GovernmentType cannot be null.");
        }
        this.nationUUID = nationUUID;
        this.governmentType = initialGovernmentType;
        this.lastElectionCompletionTimes = new EnumMap<>(ElectionType.class);
        this.titularMonarchUUID = null;
        this.primeMinisterUUID = null;
        this.parliamentaryMembersByParty = new ConcurrentHashMap<>(); // 初始化
        this.parliamentarySeatsWonByParty = new ConcurrentHashMap<>(); // 初始化
    }

    public UUID getNationUUID() {
        return nationUUID;
    }

    public GovernmentType getGovernmentType() {
        return governmentType;
    }


    public long getLastElectionCompletionTime(ElectionType type) {
        if (type != ElectionType.PARLIAMENTARY && type != ElectionType.PRESIDENTIAL) {
            // Or return 0L / throw exception if type is for party leader etc.
            return 0L;
        }
        return lastElectionCompletionTimes.getOrDefault(type, 0L);
    }

    public void setLastElectionCompletionTime(ElectionType type, long timestamp) {
        if (type != ElectionType.PARLIAMENTARY && type != ElectionType.PRESIDENTIAL) {
            return; // Silently ignore for non-national election types or throw error
        }
        if (timestamp < 0) {
            throw new IllegalArgumentException("Timestamp cannot be negative.");
        }
        lastElectionCompletionTimes.put(type, timestamp);
    }

    public void clearAllElectionCompletionTimes() {
        lastElectionCompletionTimes.clear();
    }

    // Getter for the raw map, e.g., for serialization by NationManager
    // NationManager will directly access the field for persistence to avoid issues with unmodifiable maps if we were to return one.
    // However, for a clean POJO, one might provide a get method returning an unmodifiable copy.
    // For simplicity and direct manager access for saving, we keep direct field access for NationManager.
    // public Map<ElectionType, Long> getAllLastElectionCompletionTimes() {
    //     return Collections.unmodifiableMap(new EnumMap<>(lastElectionCompletionTimes));
    // }


    public Optional<UUID> getTitularMonarchUUID() {
        return Optional.ofNullable(titularMonarchUUID);
    }

    public void setTitularMonarchUUID(UUID titularMonarchUUID) {
        this.titularMonarchUUID = titularMonarchUUID; // Nullable, allows removal
    }

    public Optional<UUID> getPrimeMinisterUUID() {
        return Optional.ofNullable(primeMinisterUUID);
    }

    public void setPrimeMinisterUUID(UUID primeMinisterUUID) {
        this.primeMinisterUUID = primeMinisterUUID; // Nullable, allows removal
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        NationPolitics that = (NationPolitics) o;
        return nationUUID.equals(that.nationUUID);
    }

    @Override
    public int hashCode() {
        return Objects.hash(nationUUID);
    }

    /**
     * 获取上次选举完成时间的条目集视图，供持久化等内部操作使用。
     * @return 上次选举完成时间的条目集
     */
    public Set<Map.Entry<ElectionType, Long>> getLastElectionCompletionTimesEntries() {
        return lastElectionCompletionTimes.entrySet();
    }

    // --- 新增 Getters and Setters for Parliament Composition ---

    /**
     * 获取指定政党在该国任命的议员UUID列表。
     * 返回的是一个副本。
     * @param partyUUID 政党ID
     * @return 议员UUID列表，如果该政党没有任命议员则为空列表。
     */
    public List<UUID> getParliamentaryMembersForParty(UUID partyUUID) {
        return new ArrayList<>(parliamentaryMembersByParty.getOrDefault(partyUUID, Collections.emptyList()));
    }

    /**
     * 获取该国所有议会成员（所有政党）。
     * @return 所有议员的UUID列表。
     */
    public List<UUID> getAllParliamentaryMembers() {
        List<UUID> allMps = new ArrayList<>();
        parliamentaryMembersByParty.values().forEach(allMps::addAll);
        return Collections.unmodifiableList(allMps);
    }

    /**
     * 设置一个政党的议员列表。会替换该政党现有的所有议员。
     * 通常由 PartyManager 在党魁执行任命命令时调用。
     * @param partyUUID 政党ID
     * @param memberPlayerUUIDs 新的议员UUID列表
     */
    public void setParliamentaryMembersForParty(UUID partyUUID, List<UUID> memberPlayerUUIDs) {
        if (partyUUID == null) return;
        if (memberPlayerUUIDs == null || memberPlayerUUIDs.isEmpty()) {
            parliamentaryMembersByParty.remove(partyUUID);
        } else {
            // 确保没有重复
            parliamentaryMembersByParty.put(partyUUID, new ArrayList<>(new HashSet<>(memberPlayerUUIDs)));
        }
    }

    /**
     * 清除所有政党的议员任命信息。
     * 通常在新的议会选举结束后调用。
     */
    public void clearAllParliamentaryMembers() {
        parliamentaryMembersByParty.clear();
    }

    /**
     * 获取该国议会各政党赢得的席位数。
     * 返回的是一个副本。
     * @return Map<PartyUUID, Integer>
     */
    public Map<UUID, Integer> getParliamentarySeatsWonByParty() {
        return new ConcurrentHashMap<>(parliamentarySeatsWonByParty);
    }

    /**
     * 设置议会选举后各政党赢得的席位数。
     * 通常由 ElectionManager 在议会选举结束后调用。
     * @param seatDistribution Map<PartyUUID, Integer>
     */
    public void setParliamentarySeatsWonByParty(Map<UUID, Integer> seatDistribution) {
        this.parliamentarySeatsWonByParty.clear();
        if (seatDistribution != null) {
            this.parliamentarySeatsWonByParty.putAll(seatDistribution);
        }
    }

    // 在 setGovernmentType 方法中，如果政体不再有议会，则清除议员信息
    public void setGovernmentType(GovernmentType newGovernmentType) {
        if (newGovernmentType == null) {
            throw new IllegalArgumentException("New GovernmentType cannot be null.");
        }
        this.governmentType = newGovernmentType;
        if (!newGovernmentType.hasParliament()) { // <--- 新增检查
            clearAllParliamentaryMembers();
            this.parliamentarySeatsWonByParty.clear();
        }
    }

    // 在 toString 中可以加入议员数量
    @Override
    public String toString() {
        int totalMps = parliamentaryMembersByParty.values().stream().mapToInt(List::size).sum();
        return "NationPolitics{" +
                "nationUUID=" + nationUUID +
                ", governmentType=" + (governmentType != null ? governmentType.getDisplayName() : "null") +
                ", lastElectionCompletionTimes=" + lastElectionCompletionTimes +
                ", titularMonarch=" + (titularMonarchUUID != null ? titularMonarchUUID.toString().substring(0, Math.min(8, titularMonarchUUID.toString().length())) : "None") +
                ", primeMinister=" + (primeMinisterUUID != null ? primeMinisterUUID.toString().substring(0, Math.min(8, primeMinisterUUID.toString().length())) : "None") +
                ", totalMPs=" + totalMps + // 新增
                '}';
    }

    // 供 NationManager 加载/保存数据使用
    public Map<UUID, List<UUID>> getParliamentaryMembersByPartyInternal() {
        return this.parliamentaryMembersByParty;
    }
    public Map<UUID, Integer> getParliamentarySeatsWonByPartyInternal() {
        return this.parliamentarySeatsWonByParty;
    }
}