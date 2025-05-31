// 文件名: Party.java
// 结构位置: top/chickenshout/townypolitical/data/Party.java
package top.chickenshout.townypolitical.data;

import top.chickenshout.townypolitical.enums.PartyRole;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * 代表一个政治党派。
 * 包含政党的名称、成员、领导人等信息。
 */
public class Party {
    private final UUID partyId; // 政党的唯一标识符，由Manager在创建时分配
    private String name; // 政党名称，可更改
    private final long creationTimestamp; // 政党创建时的时间戳
    private final Map<UUID, PartyMember> members; // 政党所有相关人员 (包括申请者) <PlayerUUID, PartyMember>
    private long lastLeaderElectionTime = 0L; // 上次党魁选举完成的时间戳

    /**
     * 构造一个新的政党。
     * 通常由 PartyManager 调用。
     *
     * @param partyId 政党的唯一ID
     * @param name 政党的名称
     * @param initialLeaderId 初始领导人的玩家UUID
     * @throws IllegalArgumentException 如果参数不合法
     */
    public Party(UUID partyId, String name, UUID initialLeaderId) {
        if (partyId == null) {
            throw new IllegalArgumentException("Party ID cannot be null.");
        }
        if (name == null || name.trim().isEmpty()) {
            throw new IllegalArgumentException("Party name cannot be null or empty.");
        }
        if (initialLeaderId == null) {
            throw new IllegalArgumentException("Initial leader ID cannot be null.");
        }

        this.partyId = partyId;
        this.name = name.trim();
        this.creationTimestamp = System.currentTimeMillis();
        this.members = new ConcurrentHashMap<>(); // 使用 ConcurrentHashMap 保证基本线程安全
        this.lastLeaderElectionTime = 0L;

        // 添加初始领导人
        PartyMember leader = new PartyMember(initialLeaderId, PartyRole.LEADER);
        this.members.put(initialLeaderId, leader);
    }

    // --- Getters ---
    public UUID getPartyId() {
        return partyId;
    }

    public String getName() {
        return name;
    }

    public long getCreationTimestamp() {
        return creationTimestamp;
    }

    public long getLastLeaderElectionTime() {
        return lastLeaderElectionTime;
    }

    /**
     * 获取政党指定角色的所有成员。
     * @param role 期望的角色
     * @return 符合该角色的成员列表 (不可修改)
     */
    public List<PartyMember> getMembersByRole(PartyRole role) {
        if (role == null) return Collections.emptyList();
        return members.values().stream()
                .filter(member -> member.getRole() == role)
                .collect(Collectors.toUnmodifiableList());
    }

    /**
     * 获取政党领袖。
     * @return 政党领袖的 PartyMember Optional，如果不存在则为空 (理论上不应发生)
     */
    public Optional<PartyMember> getLeader() {
        return members.values().stream()
                .filter(member -> member.getRole() == PartyRole.LEADER)
                .findFirst();
    }

    /**
     * 获取政党所有管理员。
     * @return 管理员列表 (不可修改)
     */
    public List<PartyMember> getAdmins() {
        return getMembersByRole(PartyRole.ADMIN);
    }

    /**
     * 获取政党所有普通成员 (不包括管理员和领袖)。
     * @return 普通成员列表 (不可修改)
     */
    public List<PartyMember> getRegularMembers() {
        return getMembersByRole(PartyRole.MEMBER);
    }

    /**
     * 获取所有申请加入政党的玩家。
     * @return 申请者列表 (不可修改)
     */
    public List<PartyMember> getApplicants() {
        return getMembersByRole(PartyRole.APPLICANT);
    }

    /**
     * 获取指定玩家的 PartyMember 对象。
     * @param playerId 玩家UUID
     * @return PartyMember Optional，如果玩家不在此政党（包括申请者）则为空
     */
    public Optional<PartyMember> getMember(UUID playerId) {
        if (playerId == null) return Optional.empty();
        return Optional.ofNullable(members.get(playerId));
    }

    /**
     * 获取政党所有成员及申请者。
     * @return 所有相关人员的不可修改集合
     */
    public Collection<PartyMember> getAllPartyPersonnel() {
        return Collections.unmodifiableCollection(members.values());
    }

    /**
     * 获取所有正式成员 (领袖, 管理员, 普通成员) 的UUID。
     * @return 正式成员的UUID集合 (不可修改)
     */
    public Set<UUID> getOfficialMemberIds() {
        return members.values().stream()
                .filter(m -> m.getRole().hasPermissionOf(PartyRole.MEMBER) && m.getRole() != PartyRole.APPLICANT)
                .map(PartyMember::getPlayerId)
                .collect(Collectors.toUnmodifiableSet());
    }

    // --- Setters and Mutators ---

    /**
     * 设置政党的新名称。
     * 此方法仅更新对象状态，实际的经济和唯一性检查应由 Manager 处理。
     * @param newName 新的政党名称
     * @throws IllegalArgumentException 如果newName为null或为空
     */
    public void setName(String newName) {
        if (newName == null || newName.trim().isEmpty()) {
            throw new IllegalArgumentException("New party name cannot be null or empty.");
        }
        this.name = newName.trim();
    }

    public void setLastLeaderElectionTime(long lastLeaderElectionTime) {
        if (lastLeaderElectionTime < 0) {
            throw new IllegalArgumentException("Last leader election time cannot be negative.");
        }
        this.lastLeaderElectionTime = lastLeaderElectionTime;
    }

    /**
     * 添加一个玩家作为政党的申请者。
     * 如果玩家已经是成员或申请者，则不执行任何操作。
     * @param playerId 申请者的玩家UUID
     * @return 如果成功添加为申请者，则返回true；如果已经是成员或申请者，则返回false。
     * @throws IllegalArgumentException 如果playerId为null
     */
    public boolean addPlayerAsApplicant(UUID playerId) {
        if (playerId == null) {
            throw new IllegalArgumentException("Player ID for applicant cannot be null.");
        }
        // computeIfAbsent 如果key不存在，则执行lambda创建并放入map，然后返回新value；如果key已存在，则直接返回现有value。
        // 这里我们希望如果已存在则不操作，所以用 putIfAbsent 更合适。
        PartyMember newApplicant = new PartyMember(playerId, PartyRole.APPLICANT);
        return members.putIfAbsent(playerId, newApplicant) == null; // putIfAbsent返回旧值，如果为null表示之前不存在
    }

    /**
     * 批准一个申请者，使其成为普通成员。
     * @param applicantId 申请者的玩家UUID
     * @return 如果成功批准，则返回true；如果玩家不是申请者或不存在，则返回false。
     * @throws IllegalArgumentException 如果applicantId为null
     */
    public boolean promoteApplicantToMember(UUID applicantId) {
        if (applicantId == null) {
            throw new IllegalArgumentException("Applicant ID cannot be null.");
        }
        PartyMember applicant = members.get(applicantId);
        if (applicant != null && applicant.getRole() == PartyRole.APPLICANT) {
            applicant.setRole(PartyRole.MEMBER);
            return true;
        }
        return false;
    }

    /**
     * 将一个普通成员提升为管理员。
     * @param memberId 要提升的成员的玩家UUID
     * @return 如果成功提升，则返回true；如果玩家不是普通成员或不存在，则返回false。
     * @throws IllegalArgumentException 如果memberId为null
     */
    public boolean promoteMemberToAdmin(UUID memberId) {
        if (memberId == null) {
            throw new IllegalArgumentException("Member ID for promotion cannot be null.");
        }
        PartyMember member = members.get(memberId);
        if (member != null && member.getRole() == PartyRole.MEMBER) {
            member.setRole(PartyRole.ADMIN);
            return true;
        }
        return false;
    }

    /**
     * 将一个管理员降级为普通成员。
     * @param adminId 要降级的管理员的玩家UUID
     * @return 如果成功降级，则返回true；如果玩家不是管理员或不存在，则返回false。
     * @throws IllegalArgumentException 如果adminId为null
     */
    public boolean demoteAdminToMember(UUID adminId) {
        if (adminId == null) {
            throw new IllegalArgumentException("Admin ID for demotion cannot be null.");
        }
        PartyMember admin = members.get(adminId);
        if (admin != null && admin.getRole() == PartyRole.ADMIN) {
            admin.setRole(PartyRole.MEMBER);
            return true;
        }
        return false;
    }

    /**
     * 移除一名玩家（成员或申请者）出政党。
     * 如果试图移除领袖，此操作通常应该被阻止或特殊处理。
     * 此方法不处理领袖的移除，应由 `setLeader` 或专门的领袖替换逻辑处理。
     * @param playerId 要移除的玩家的UUID
     * @return 如果成功移除，则返回被移除的PartyMember；如果玩家不在党内或为领袖，则返回null。
     * @throws IllegalArgumentException 如果playerId为null
     */
    public PartyMember removePlayer(UUID playerId) {
        if (playerId == null) {
            throw new IllegalArgumentException("Player ID for removal cannot be null.");
        }
        PartyMember memberToRemove = members.get(playerId);
        if (memberToRemove != null && memberToRemove.getRole() != PartyRole.LEADER) {
            return members.remove(playerId);
        }
        return null; // 不能直接移除领袖，或者玩家不存在
    }

    /**
     * 更换政党领袖。
     * 原领袖会被降级为管理员 (如果他不是新领袖)。新领袖必须已经是党内成员 (非申请者)。
     * @param newLeaderId 新领袖的玩家UUID
     * @return 如果成功更换领袖，则返回true。
     * @throws IllegalArgumentException 如果newLeaderId为null。
     * @throws IllegalStateException 如果找不到当前领袖或新领袖不是有效的正式成员。
     */
    public boolean setLeader(UUID newLeaderId) {
        if (newLeaderId == null) {
            throw new IllegalArgumentException("New leader ID cannot be null.");
        }

        PartyMember newLeaderMember = members.get(newLeaderId);
        if (newLeaderMember == null || newLeaderMember.getRole() == PartyRole.APPLICANT) {
            throw new IllegalStateException("New leader must be an existing official member of the party.");
        }

        if (newLeaderMember.getRole() == PartyRole.LEADER) {
            return true; // 已经是领袖，无需操作
        }

        Optional<PartyMember> currentLeaderOpt = getLeader();
        if (!currentLeaderOpt.isPresent()) {
            throw new IllegalStateException("Critical: No current leader found in party " + getName() + " (ID: " + partyId + ")");
        }

        PartyMember currentLeader = currentLeaderOpt.get();
        // 降级原领袖 (如果原领袖不是新领袖自己)
        if (!currentLeader.getPlayerId().equals(newLeaderId)) {
            currentLeader.setRole(PartyRole.ADMIN); // 或者 MEMBER，取决于设计
        }

        // 提升新领袖
        newLeaderMember.setRole(PartyRole.LEADER);
        return true;
    }

    /**
     * (供 PartyManager 加载数据时使用) 直接添加一个成员并赋予指定角色。
     * 如果成员已存在，则更新其角色。
     * @param playerId 玩家UUID
     * @param role 角色
     */
    public void addPlayerWithRoleInternal(UUID playerId, PartyRole role) {
        if (playerId == null || role == null) {
            throw new IllegalArgumentException("Player ID and Role cannot be null for internal add.");
        }
        members.put(playerId, new PartyMember(playerId, role));
    }

    /**
     * (供 PartyManager 的 invitePlayer/直接添加功能使用) 添加玩家为普通成员。
     * 如果玩家是申请者，则提升。如果已是更高职位，则不变。
     * @param playerId 玩家UUID
     * @return 如果成功添加或提升，返回true。
     */
    public boolean addPlayerAsMember(UUID playerId) {
        if (playerId == null) {
            throw new IllegalArgumentException("Player ID cannot be null.");
        }
        PartyMember existingMember = members.get(playerId);
        if (existingMember == null) {
            members.put(playerId, new PartyMember(playerId, PartyRole.MEMBER));
            return true;
        } else if (existingMember.getRole() == PartyRole.APPLICANT) {
            existingMember.setRole(PartyRole.MEMBER);
            return true;
        }
        // 如果已经是 MEMBER, ADMIN, 或 LEADER，则认为操作“成功”（因为目标已达成或更高）
        return existingMember.getRole().hasPermissionOf(PartyRole.MEMBER);
    }


    // --- Query Methods ---

    public boolean isLeader(UUID playerId) {
        if (playerId == null) return false;
        PartyMember member = members.get(playerId);
        return member != null && member.getRole() == PartyRole.LEADER;
    }

    public boolean isAdmin(UUID playerId) {
        if (playerId == null) return false;
        PartyMember member = members.get(playerId);
        return member != null && member.getRole() == PartyRole.ADMIN;
    }

    public boolean isRegularMember(UUID playerId) {
        if (playerId == null) return false;
        PartyMember member = members.get(playerId);
        return member != null && member.getRole() == PartyRole.MEMBER;
    }

    public boolean isOfficialMember(UUID playerId) {
        if (playerId == null) return false;
        PartyMember member = members.get(playerId);
        return member != null && member.getRole().hasPermissionOf(PartyRole.MEMBER) && member.getRole() != PartyRole.APPLICANT;
    }

    public boolean isApplicant(UUID playerId) {
        if (playerId == null) return false;
        PartyMember member = members.get(playerId);
        return member != null && member.getRole() == PartyRole.APPLICANT;
    }

    /**
     * 检查政党是否有指定名称的成员（不区分大小写）。
     * @param playerName 玩家名称
     * @return 如果存在该名称的成员，返回其PartyMember Optional
     */
    public Optional<PartyMember> getMemberByName(String playerName) {
        if (playerName == null || playerName.trim().isEmpty()) return Optional.empty();
        String lowerPlayerName = playerName.toLowerCase();
        return members.values().stream()
                .filter(member -> {
                    String memberCurrentName = member.getName(); // Uses cached name first
                    return memberCurrentName != null && memberCurrentName.toLowerCase().equals(lowerPlayerName);
                })
                .findFirst();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        Party party = (Party) o;
        return partyId.equals(party.partyId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(partyId);
    }

    @Override
    public String toString() {
        return "Party{" +
                "partyId=" + partyId +
                ", name='" + name + '\'' +
                ", leader=" + getLeader().map(PartyMember::getName).orElse("N/A") +
                ", members=" + members.size() + // 只显示数量，避免过长
                ", lastLeaderElectionTime=" + lastLeaderElectionTime +
                '}';
    }
}