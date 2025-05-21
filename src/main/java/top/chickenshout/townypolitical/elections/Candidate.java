// 文件名: Candidate.java
// 结构位置: top/chickenshout/townypolitical/elections/Candidate.java
package top.chickenshout.townypolitical.elections;

import org.bukkit.Bukkit; // For fetching player name if cache is null
import org.bukkit.OfflinePlayer;

import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 代表选举中的一位候选人。
 */
public class Candidate {
    private final UUID playerUUID; // 候选人的玩家UUID
    private final UUID partyUUID;  // 候选人所属的政党UUID (可选，独立候选人可能没有)
    private final AtomicInteger votes;      // 获得的票数，使用AtomicInteger保证线程安全

    // 缓存信息，由 ElectionManager 填充和管理，不直接参与 equals/hashCode
    private transient String playerNameCache;
    private transient String partyNameCache;

    /**
     * 构造一个候选人。
     * @param playerUUID 候选人的玩家UUID
     * @param partyUUID 候选人所属政党的UUID，如果为独立候选人则为null
     * @throws IllegalArgumentException 如果playerUUID为null
     */
    public Candidate(UUID playerUUID, UUID partyUUID) {
        if (playerUUID == null) {
            throw new IllegalArgumentException("Player UUID for candidate cannot be null.");
        }
        this.playerUUID = playerUUID;
        this.partyUUID = partyUUID; // 可以为 null
        this.votes = new AtomicInteger(0);
    }

    public UUID getPlayerUUID() {
        return playerUUID;
    }

    public UUID getPartyUUID() { // 返回的是UUID，不是Party对象
        return partyUUID;
    }

    public int getVotes() {
        return votes.get();
    }

    /**
     * 为该候选人增加一票。
     */
    public void addVote() {
        this.votes.incrementAndGet();
    }

    /**
     * （慎用）直接设置票数，主要用于从存储加载或管理员修正。
     * @param count 票数
     */
    public void setVotes(int count) {
        if (count < 0) {
            this.votes.set(0);
        } else {
            this.votes.set(count);
        }
    }

    /**
     * 获取缓存的玩家名称。
     * @return 缓存的玩家名，可能为null。
     */
    public String getPlayerNameCache() {
        return playerNameCache;
    }

    /**
     * 获取玩家的实际名称。如果缓存不存在，则尝试从Bukkit获取。
     * @return 玩家名称，如果无法获取则返回一个默认标识。
     */
    public String getResolvedPlayerName() {
        if (playerNameCache != null) {
            return playerNameCache;
        }
        OfflinePlayer op = Bukkit.getOfflinePlayer(playerUUID);
        if (op != null && op.getName() != null) {
            this.playerNameCache = op.getName(); // 缓存结果
            return this.playerNameCache;
        }
        return "Player_" + playerUUID.toString().substring(0, 8);
    }


    /**
     * 设置玩家名称缓存。
     * @param playerNameCache 要缓存的玩家名。
     */
    public void setPlayerNameCache(String playerNameCache) {
        this.playerNameCache = playerNameCache;
    }

    /**
     * 获取缓存的政党名称。
     * @return 缓存的政党名，可能为null。
     */
    public String getPartyNameCache() {
        return partyNameCache;
    }

    /**
     * 设置政党名称缓存。
     * @param partyNameCache 要缓存的政党名。
     */
    public void setPartyNameCache(String partyNameCache) {
        this.partyNameCache = partyNameCache;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        Candidate candidate = (Candidate) o;
        // 在一次选举中，一个玩家只能是一个候选实体。
        // partyUUID 也应该被考虑，因为一个玩家可能代表不同党派参加不同选举（理论上，但我们的系统可能不支持）。
        // 但对于单个选举内的唯一性，playerUUID 足够了。
        return playerUUID.equals(candidate.playerUUID);
    }

    @Override
    public int hashCode() {
        return Objects.hash(playerUUID);
    }

    @Override
    public String toString() {
        return "Candidate{" +
                "playerUUID=" + playerUUID +
                (playerNameCache != null ? ", playerName='" + playerNameCache + '\'' : (", playerName='" + getResolvedPlayerName() + "'")) +
                (partyUUID != null ? ", partyUUID=" + partyUUID : "") +
                (partyNameCache != null ? ", partyName='" + partyNameCache + '\'' : "") +
                ", votes=" + votes.get() +
                '}';
    }
}