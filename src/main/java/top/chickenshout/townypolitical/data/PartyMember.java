// 文件名: PartyMember.java
// 结构位置: top/chickenshout/townypolitical/data/PartyMember.java
package top.chickenshout.townypolitical.data;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import top.chickenshout.townypolitical.enums.PartyRole;

import java.util.Objects;
import java.util.UUID;

/**
 * 代表政党中的一个成员。
 * 存储玩家的UUID和其在党内的角色。
 */
public class PartyMember {
    private final UUID playerId;
    private PartyRole role;
    private transient OfflinePlayer offlinePlayerCache; // 缓存OfflinePlayer对象以减少查找
    private transient String playerNameCache; // 缓存玩家名称

    /**
     * 构造一个新的政党成员。
     * @param playerId 玩家的UUID
     * @param role 玩家在党内的角色
     * @throws IllegalArgumentException 如果playerId或role为null
     */
    public PartyMember(UUID playerId, PartyRole role) {
        if (playerId == null) {
            throw new IllegalArgumentException("Player ID cannot be null.");
        }
        if (role == null) {
            throw new IllegalArgumentException("Party role cannot be null.");
        }
        this.playerId = playerId;
        this.role = role;
    }

    /**
     * 获取成员的UUID。
     * @return 玩家UUID
     */
    public UUID getPlayerId() {
        return playerId;
    }

    /**
     * 获取成员在党内的角色。
     * @return 政党角色
     */
    public PartyRole getRole() {
        return role;
    }

    /**
     * 设置成员在党内的角色。
     * @param role 新的政党角色
     * @throws IllegalArgumentException 如果role为null
     */
    public void setRole(PartyRole role) {
        if (role == null) {
            throw new IllegalArgumentException("New party role cannot be null.");
        }
        this.role = role;
    }

    /**
     * 获取该成员的 OfflinePlayer 对象。
     * 会尝试从缓存中获取，如果缓存未命中则从 Bukkit 获取并缓存。
     * @return OfflinePlayer 对象
     */
    public OfflinePlayer getOfflinePlayer() {
        if (offlinePlayerCache == null) {
            offlinePlayerCache = Bukkit.getOfflinePlayer(playerId);
        }
        return offlinePlayerCache;
    }

    /**
     * 获取玩家的名称。
     * 优先使用缓存的名称，其次尝试通过OfflinePlayer获取，最后提供一个默认格式。
     * @return 玩家名称
     */
    public String getName() {
        if (playerNameCache != null) {
            return playerNameCache;
        }
        OfflinePlayer player = getOfflinePlayer();
        if (player != null && player.getName() != null) {
            this.playerNameCache = player.getName(); // 缓存获取到的名称
            return this.playerNameCache;
        }
        return "Player_" + playerId.toString().substring(0, 8); // 默认名称
    }

    /**
     * 设置玩家名称缓存。通常在加载数据或玩家上线时调用。
     * @param name 要缓存的玩家名称
     */
    public void setNameCache(String name) {
        this.playerNameCache = name;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        PartyMember that = (PartyMember) o;
        return playerId.equals(that.playerId); // 成员的唯一标识是玩家ID
    }

    @Override
    public int hashCode() {
        return Objects.hash(playerId);
    }

    @Override
    public String toString() {
        return "PartyMember{" +
                "playerId=" + playerId +
                ", name='" + getName() + '\'' + // 使用 getName() 以利用缓存
                ", role=" + role +
                '}';
    }
}