// 文件名: NationPolitics.java
// 结构位置: top/chickenshout/townypolitical/data/NationPolitics.java
package top.chickenshout.townypolitical.data;

import top.chickenshout.townypolitical.enums.ElectionType;
import top.chickenshout.townypolitical.enums.GovernmentType;

import java.util.*;

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
    }

    public UUID getNationUUID() {
        return nationUUID;
    }

    public GovernmentType getGovernmentType() {
        return governmentType;
    }

    /**
     * 设置国家的新政体。
     * @param newGovernmentType 新的政体。必须提供且不能为null。
     * @throws IllegalArgumentException 如果 newGovernmentType 为 null。
     */
    public void setGovernmentType(GovernmentType newGovernmentType) {
        if (newGovernmentType == null) {
            throw new IllegalArgumentException("New GovernmentType cannot be null.");
        }
        this.governmentType = newGovernmentType;
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

    @Override
    public String toString() {
        return "NationPolitics{" +
                "nationUUID=" + nationUUID +
                ", governmentType=" + (governmentType != null ? governmentType.getDisplayName() : "null") +
                ", lastElectionCompletionTimes=" + lastElectionCompletionTimes +
                ", titularMonarch=" + (titularMonarchUUID != null ? titularMonarchUUID.toString().substring(0, Math.min(8, titularMonarchUUID.toString().length())) : "None") +
                ", primeMinister=" + (primeMinisterUUID != null ? primeMinisterUUID.toString().substring(0, Math.min(8, primeMinisterUUID.toString().length())) : "None") +
                '}';
    }

    /**
     * 获取上次选举完成时间的条目集视图，供持久化等内部操作使用。
     * @return 上次选举完成时间的条目集
     */
    public Set<Map.Entry<ElectionType, Long>> getLastElectionCompletionTimesEntries() {
        return lastElectionCompletionTimes.entrySet();
    }
}