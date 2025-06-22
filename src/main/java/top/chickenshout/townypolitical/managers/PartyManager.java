// 文件名: PartyManager.java
// 结构位置: top/chickenshout/townypolitical/managers/PartyManager.java
package top.chickenshout.townypolitical.managers;

import com.palmergames.bukkit.towny.TownyAPI; // 正确的API导入
import com.palmergames.bukkit.towny.object.Nation;
import com.palmergames.bukkit.towny.object.Resident;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import top.chickenshout.townypolitical.TownyPolitical;
import top.chickenshout.townypolitical.data.NationPolitics;
import top.chickenshout.townypolitical.data.Party;
import top.chickenshout.townypolitical.data.PartyMember;
import top.chickenshout.townypolitical.economy.EconomyService;
import top.chickenshout.townypolitical.enums.PartyRole;
import top.chickenshout.townypolitical.utils.MessageManager;
import top.chickenshout.townypolitical.managers.NationManager;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public class PartyManager {
    private final TownyPolitical plugin;
    private final MessageManager messageManager;
    private final EconomyService economyService;

    // <PartyUUID, Party>
    private final Map<UUID, Party> partiesById;
    // <LowercasePartyName, PartyUUID> for quick name lookup and uniqueness
    private final Map<String, UUID> partyNameToId;
    // <PlayerUUID, PartyUUID> for quick player's party lookup (only official members)
    private final Map<UUID, UUID> playerToPartyId;

    private final File partiesDataFolder;
    private static final String PARTY_FILE_EXTENSION = ".yml";
    // 默认中文、字母、数字、下划线，长度3-16。实际规则从config读取。
    private Pattern validPartyNamePattern;
    private int partyNameMinLength;
    private int partyNameMaxLength;

    private NationManager getNationManager() {
        return plugin.getNationManager(); // 或者 this.plugin.getNationManager()
    }

    public PartyManager(TownyPolitical plugin) {
        this.plugin = plugin;
        this.messageManager = plugin.getMessageManager();
        this.economyService = plugin.getEconomyService();
        // ElectionManager 可能在 PartyManager 构造时尚未初始化完毕，
        // 因此 ElectionManager 的引用最好通过 getter 在需要时获取，或者延迟初始化。
        // 为了简单，这里直接赋值，但主插件中初始化顺序很重要。


        this.partiesById = new ConcurrentHashMap<>();
        this.partyNameToId = new ConcurrentHashMap<>();
        this.playerToPartyId = new ConcurrentHashMap<>();

        this.partiesDataFolder = new File(plugin.getDataFolder(), "parties");
        if (!partiesDataFolder.exists()) {
            if (!partiesDataFolder.mkdirs()) {
                plugin.getLogger().severe("Could not create parties data folder!");
            }
        }
        loadConfigurableNameRules();
        loadParties();
    }

    private void loadConfigurableNameRules() {
        String regex = plugin.getConfig().getString("party.name.regex", "^[a-zA-Z0-9_\\u4e00-\\u9fa5]+$");
        this.partyNameMinLength = plugin.getConfig().getInt("party.name.min_length", 3);
        this.partyNameMaxLength = plugin.getConfig().getInt("party.name.max_length", 16);
        try {
            this.validPartyNamePattern = Pattern.compile(regex);
        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "Invalid party name regex in config.yml: " + regex + ". Using default.", e);
            this.validPartyNamePattern = Pattern.compile("^[a-zA-Z0-9_\\u4e00-\\u9fa5]+$"); // Fallback
        }
    }


    // --- Party Creation and Deletion ---
    public boolean createParty(Player founder, String name) {
        if (founder == null || name == null) return false;
        String trimmedName = name.trim();

        if (getPartyByMember(founder.getUniqueId()) != null) {
            messageManager.sendMessage(founder, "party-create-fail-already-in-party");
            return false;
        }

        if (!isValidPartyName(trimmedName)) {
            messageManager.sendMessage(founder, "party-name-invalid",
                    "name", trimmedName,
                    "min_length", String.valueOf(partyNameMinLength),
                    "max_length", String.valueOf(partyNameMaxLength));
            return false;
        }
        if (isPartyNameTaken(trimmedName)) {
            messageManager.sendMessage(founder, "party-name-taken", "name", trimmedName);
            return false;
        }

        double creationCost = plugin.getConfig().getDouble("party.creation_cost", 1000.0);
        if (economyService.isEnabled() && creationCost > 0) {
            if (!economyService.hasEnough(founder.getUniqueId(), creationCost)) {
                messageManager.sendMessage(founder, "error-not-enough-money", "amount", economyService.format(creationCost));
                return false;
            }
            if (!economyService.withdraw(founder.getUniqueId(), creationCost)) {
                messageManager.sendMessage(founder, "party-create-fail-eco-error");
                return false;
            }
        }

        UUID partyId = UUID.randomUUID();
        Party party = new Party(partyId, trimmedName, founder.getUniqueId());

        partiesById.put(partyId, party);
        partyNameToId.put(trimmedName.toLowerCase(), partyId);
        playerToPartyId.put(founder.getUniqueId(), partyId);

        saveParty(party);
        messageManager.sendMessage(founder, "party-created", "party_name", party.getName());
        if (creationCost > 0 && economyService.isEnabled()) {
            messageManager.sendMessage(founder, "party-creation-cost-paid", "amount", economyService.format(creationCost));
        }
        return true;
    }

    public boolean disbandParty(Party party, Player initiator) {
        if (party == null || initiator == null) return false;

        if (!party.isLeader(initiator.getUniqueId())) {
            messageManager.sendMessage(initiator, "party-disband-fail-not-leader");
            return false;
        }

        String partyName = party.getName();
        party.getAllPartyPersonnel().forEach(pm -> playerToPartyId.remove(pm.getPlayerId()));
        partiesById.remove(party.getPartyId());
        partyNameToId.remove(partyName.toLowerCase());
        deletePartyDataFile(party.getPartyId());

        // 在这里动态获取 ElectionManager 实例
        ElectionManager em = plugin.getElectionManager(); // <--- 动态获取
        if (em != null) {
            em.onPartyDisband(party);
        } else {
            // 这种情况理论上不应该发生，因为此时主插件应该已经完成了所有管理器的初始化
            plugin.getLogger().severe("[PartyManager] Critical: ElectionManager was null when trying to notify onPartyDisband for party: " + partyName);
        }

        messageManager.sendMessage(initiator, "party-disband-success", "party_name", partyName);
        messageManager.sendMessage(initiator, "party-disband-success", "party_name", partyName);
        // 广播给所有在线的前成员
        party.getAllPartyPersonnel().stream()
                .map(PartyMember::getOfflinePlayer)
                .filter(OfflinePlayer::isOnline)
                .map(OfflinePlayer::getPlayer)
                .filter(Objects::nonNull)
                .forEach(p -> messageManager.sendMessage(p, "party-disband-notification-member", "party_name", partyName));

        // 可选：全服广播政党解散
        if (plugin.getConfig().getBoolean("party.broadcast_disband", false)) {
            Bukkit.broadcastMessage(messageManager.getFormattedPrefix() + messageManager.getMessage("party-disband-broadcast-server", "party_name", partyName, "leader_name", initiator.getName()));
        }
        return true;
    }


    // --- Member Management ---
    public boolean invitePlayer(Player inviter, OfflinePlayer targetPlayer, Party party) {
        // 'invite' is a direct add by admin/leader in this context
        if (inviter == null || targetPlayer == null || party == null) return false;

        PartyMember inviterMember = party.getMember(inviter.getUniqueId()).orElse(null);
        if (inviterMember == null || !inviterMember.getRole().hasPermissionOf(PartyRole.ADMIN)) {
            messageManager.sendMessage(inviter, "party-invite-fail-no-permission");
            return false;
        }

        if (getPartyByMember(targetPlayer.getUniqueId()) != null) {
            messageManager.sendMessage(inviter, "party-invite-fail-target-already-member", "player", targetPlayer.getName());
            return false;
        }
        if (party.getMember(targetPlayer.getUniqueId()).isPresent()) {
            messageManager.sendMessage(inviter, "party-invite-fail-target-already-related", "player", targetPlayer.getName(), "party_name", party.getName());
            return false;
        }

        // 检查政党成员上限
        int maxMembers = plugin.getConfig().getInt("party.max_members", 0);
        if (maxMembers > 0 && party.getOfficialMemberIds().size() >= maxMembers) {
            messageManager.sendMessage(inviter, "party-invite-fail-party-full", "max_members", String.valueOf(maxMembers));
            return false;
        }


        if (party.addPlayerAsMember(targetPlayer.getUniqueId())) {
            playerToPartyId.put(targetPlayer.getUniqueId(), party.getPartyId());
            if (targetPlayer.getName() != null) { // Cache name on add
                party.getMember(targetPlayer.getUniqueId()).ifPresent(pm -> pm.setNameCache(targetPlayer.getName()));
            }
            saveParty(party);

            messageManager.sendMessage(inviter, "party-player-added-by-invite", "player", targetPlayer.getName(), "party_name", party.getName());
            if (targetPlayer.isOnline() && targetPlayer.getPlayer() != null) {
                messageManager.sendMessage(targetPlayer.getPlayer(), "party-added-notification-invited", "party_name", party.getName(), "inviter", inviter.getName());
            }
            return true;
        }
        messageManager.sendMessage(inviter, "party-invite-fail-unknown", "player", targetPlayer.getName());
        return false;
    }

    public boolean playerApplyToParty(Player applicant, Party party) {
        if (applicant == null || party == null) return false;

        if (getPartyByMember(applicant.getUniqueId()) != null) {
            messageManager.sendMessage(applicant, "party-apply-fail-already-in-party");
            return false;
        }
        if (party.getMember(applicant.getUniqueId()).isPresent()) {
            messageManager.sendMessage(applicant, "party-apply-fail-already-applied-or-member", "party_name", party.getName());
            return false;
        }

        // 检查政党成员上限 (如果申请成功会计入)
        int maxMembers = plugin.getConfig().getInt("party.max_members", 0);
        if (maxMembers > 0 && party.getOfficialMemberIds().size() >= maxMembers) {
            messageManager.sendMessage(applicant, "party-apply-fail-party-full", "max_members", String.valueOf(maxMembers));
            return false;
        }

        if (party.addPlayerAsApplicant(applicant.getUniqueId())) {
            party.getMember(applicant.getUniqueId()).ifPresent(pm -> pm.setNameCache(applicant.getName())); // Cache name
            saveParty(party);
            messageManager.sendMessage(applicant, "party-join-request-sent", "party_name", party.getName());

            String applicantName = applicant.getName();
            String partyName = party.getName();
            party.getMembersByRole(PartyRole.LEADER).forEach(leader -> notifyApplication(leader, applicantName, partyName));
            party.getAdmins().forEach(admin -> notifyApplication(admin, applicantName, partyName));
            return true;
        }
        return false;
    }

    private void notifyApplication(PartyMember recipient, String applicantName, String partyName) {
        OfflinePlayer p = recipient.getOfflinePlayer();
        if (p.isOnline() && p.getPlayer() != null) {
            messageManager.sendMessage(p.getPlayer(), "party-join-request-received-admin", "player", applicantName, "party_name", partyName);
        }
    }

    public boolean acceptPartyApplication(Player adminReviewer, OfflinePlayer applicantPlayer, Party party) {
        if (adminReviewer == null || applicantPlayer == null || party == null) return false;

        PartyMember adminMember = party.getMember(adminReviewer.getUniqueId()).orElse(null);
        if (adminMember == null || !adminMember.getRole().hasPermissionOf(PartyRole.ADMIN)) {
            messageManager.sendMessage(adminReviewer, "party-accept-fail-no-permission");
            return false;
        }

        if (getPartyByMember(applicantPlayer.getUniqueId()) != null && !party.isOfficialMember(applicantPlayer.getUniqueId())) {
            messageManager.sendMessage(adminReviewer, "party-accept-fail-applicant-in-other-party", "player", applicantPlayer.getName());
            party.removePlayer(applicantPlayer.getUniqueId()); // Remove their application from this party
            saveParty(party);
            return false;
        }

        // 再次检查政党成员上限
        int maxMembers = plugin.getConfig().getInt("party.max_members", 0);
        if (maxMembers > 0 && party.getOfficialMemberIds().size() >= maxMembers) {
            messageManager.sendMessage(adminReviewer, "party-accept-fail-party-full", "max_members", String.valueOf(maxMembers));
            // Optionally notify applicant
            if (applicantPlayer.isOnline() && applicantPlayer.getPlayer() != null) {
                messageManager.sendMessage(applicantPlayer.getPlayer(), "party-application-rejected-party-full", "party_name", party.getName());
            }
            party.removePlayer(applicantPlayer.getUniqueId()); // Remove their application
            saveParty(party);
            return false;
        }

        if (party.promoteApplicantToMember(applicantPlayer.getUniqueId())) {
            playerToPartyId.put(applicantPlayer.getUniqueId(), party.getPartyId());
            if (applicantPlayer.getName() != null) {
                party.getMember(applicantPlayer.getUniqueId()).ifPresent(pm -> pm.setNameCache(applicantPlayer.getName()));
            }
            saveParty(party);
            messageManager.sendMessage(adminReviewer, "party-applicant-accepted", "player", applicantPlayer.getName());
            if (applicantPlayer.isOnline() && applicantPlayer.getPlayer() != null) {
                messageManager.sendMessage(applicantPlayer.getPlayer(), "party-application-approved", "party_name", party.getName());
            }
            return true;
        }
        messageManager.sendMessage(adminReviewer, "party-accept-fail-not-applicant", "player", applicantPlayer.getName());
        return false;
    }

    public boolean rejectPartyApplication(Player adminReviewer, OfflinePlayer applicantPlayer, Party party) {
        // ... (类似 accept, 但调用 party.removePlayer(applicantId) 移除申请者)
        if (adminReviewer == null || applicantPlayer == null || party == null) return false;

        PartyMember adminMember = party.getMember(adminReviewer.getUniqueId()).orElse(null);
        if (adminMember == null || !adminMember.getRole().hasPermissionOf(PartyRole.ADMIN)) {
            messageManager.sendMessage(adminReviewer, "party-reject-fail-no-permission");
            return false;
        }

        Optional<PartyMember> applicantOpt = party.getMember(applicantPlayer.getUniqueId());
        if (applicantOpt.isPresent() && applicantOpt.get().getRole() == PartyRole.APPLICANT) {
            party.removePlayer(applicantPlayer.getUniqueId());
            saveParty(party);
            messageManager.sendMessage(adminReviewer, "party-applicant-rejected", "player", applicantPlayer.getName());
            if (applicantPlayer.isOnline() && applicantPlayer.getPlayer() != null) {
                messageManager.sendMessage(applicantPlayer.getPlayer(), "party-application-rejected", "party_name", party.getName());
            }
            return true;
        }
        messageManager.sendMessage(adminReviewer, "party-reject-fail-not-applicant", "player", applicantPlayer.getName());
        return false;
    }

    public boolean leaveParty(Player player) {
        if (player == null) return false;
        Party party = getPartyByMember(player.getUniqueId());
        if (party == null) {
            messageManager.sendMessage(player, "party-leave-fail-not-in-party");
            return false;
        }

        if (party.isLeader(player.getUniqueId())) {
            if (party.getOfficialMemberIds().size() > 1) { // 如果还有其他成员，领袖不能直接离开
                messageManager.sendMessage(player, "party-leave-fail-leader-must-setleader-or-disband");
                return false;
            } else { // 如果是最后一个成员（即领袖自己），离开等于解散
                return disbandParty(party, player); // 调用解散逻辑
            }
        }

        party.removePlayer(player.getUniqueId());
        playerToPartyId.remove(player.getUniqueId());
        saveParty(party);
        messageManager.sendMessage(player, "party-leave-success", "party_name", party.getName());
        // 通知领袖/管理员有成员离开 (可选)
        // party.getLeader().ifPresent(l -> notifyPlayerLeft(l, player.getName(), party.getName()));
        // party.getAdmins().forEach(a -> notifyPlayerLeft(a, player.getName(), party.getName()));
        return true;
    }

    public boolean kickPlayer(Player kicker, OfflinePlayer targetPlayer, Party party) {
        // ... (与之前版本类似，确保权限和逻辑正确)
        if (kicker == null || targetPlayer == null || party == null) return false;
        PartyMember kickerMember = party.getMember(kicker.getUniqueId()).orElse(null);
        PartyMember targetMember = party.getMember(targetPlayer.getUniqueId()).orElse(null);

        if (kickerMember == null) return false; // Kicker not in party (shouldn't happen)
        if (targetMember == null || targetMember.getRole() == PartyRole.APPLICANT) {
            messageManager.sendMessage(kicker, "party-kick-fail-not-member", "player", targetPlayer.getName());
            return false;
        }
        if (kicker.getUniqueId().equals(targetPlayer.getUniqueId())) {
            messageManager.sendMessage(kicker, "party-kick-fail-self");
            return false;
        }
        if (targetMember.getRole() == PartyRole.LEADER) {
            messageManager.sendMessage(kicker, "party-kick-fail-leader");
            return false;
        }
        if (!kickerMember.getRole().hasPermissionOf(PartyRole.ADMIN) || kickerMember.getRole().getPermissionLevel() <= targetMember.getRole().getPermissionLevel()) {
            messageManager.sendMessage(kicker, "party-kick-fail-cannot-kick-higher-role");
            return false;
        }

        party.removePlayer(targetPlayer.getUniqueId());
        playerToPartyId.remove(targetPlayer.getUniqueId());
        saveParty(party);
        messageManager.sendMessage(kicker, "party-kick-success", "player", targetPlayer.getName());
        if (targetPlayer.isOnline() && targetPlayer.getPlayer() != null) {
            messageManager.sendMessage(targetPlayer.getPlayer(), "party-kick-notification", "party_name", party.getName(), "kicker", kicker.getName());
        }
        return true;
    }

    public boolean promotePlayer(Player promoter, OfflinePlayer targetPlayer, Party party) {
        // ... (与之前版本类似)
        if (promoter == null || targetPlayer == null || party == null) return false;
        PartyMember promoterMember = party.getMember(promoter.getUniqueId()).orElse(null);
        PartyMember targetMember = party.getMember(targetPlayer.getUniqueId()).orElse(null);

        if (promoterMember == null || targetMember == null || targetMember.getRole() == PartyRole.APPLICANT) {
            messageManager.sendMessage(promoter, "party-promote-fail-not-member", "player", targetPlayer.getName());
            return false;
        }
        if (!promoterMember.getRole().equals(PartyRole.LEADER)) { // 只有领袖能提升至管理员
            messageManager.sendMessage(promoter, "party-promote-fail-no-permission-leader");
            return false;
        }
        if (targetMember.getRole().equals(PartyRole.MEMBER)) {
            if (party.promoteMemberToAdmin(targetPlayer.getUniqueId())) {
                saveParty(party);
                messageManager.sendMessage(promoter, "party-promote-success", "player", targetPlayer.getName(), "role", PartyRole.ADMIN.getDisplayName());
                if (targetPlayer.isOnline() && targetPlayer.getPlayer() != null) {
                    messageManager.sendMessage(targetPlayer.getPlayer(), "party-promote-notification", "role", PartyRole.ADMIN.getDisplayName());
                }
                return true;
            }
        } else {
            messageManager.sendMessage(promoter, "party-promote-fail-already-admin-or-leader", "player", targetPlayer.getName());
        }
        return false;
    }

    public boolean demotePlayer(Player demoter, OfflinePlayer targetPlayer, Party party) {
        // ... (与之前版本类似)
        if (demoter == null || targetPlayer == null || party == null) return false;
        PartyMember demoterMember = party.getMember(demoter.getUniqueId()).orElse(null);
        PartyMember targetMember = party.getMember(targetPlayer.getUniqueId()).orElse(null);

        if (demoterMember == null || targetMember == null || targetMember.getRole() == PartyRole.APPLICANT) {
            messageManager.sendMessage(demoter, "party-demote-fail-not-member", "player", targetPlayer.getName());
            return false;
        }
        if (!demoterMember.getRole().equals(PartyRole.LEADER)) { // 只有领袖能降级管理员
            messageManager.sendMessage(demoter, "party-demote-fail-no-permission-leader");
            return false;
        }
        if (targetMember.getRole().equals(PartyRole.ADMIN)) {
            if (party.demoteAdminToMember(targetPlayer.getUniqueId())) {
                saveParty(party);
                messageManager.sendMessage(demoter, "party-demote-success", "player", targetPlayer.getName(), "role", PartyRole.MEMBER.getDisplayName());
                if (targetPlayer.isOnline() && targetPlayer.getPlayer() != null) {
                    messageManager.sendMessage(targetPlayer.getPlayer(), "party-demote-notification", "role", PartyRole.MEMBER.getDisplayName());
                }
                return true;
            }
        } else {
            messageManager.sendMessage(demoter, "party-demote-fail-not-admin", "player", targetPlayer.getName());
        }
        return false;
    }

    // --- Party Info and Management ---
    public boolean renameParty(Party party, String newName, Player initiator) {
        if (party == null || newName == null || initiator == null) return false;
        String trimmedNewName = newName.trim();

        if (!party.isLeader(initiator.getUniqueId())) {
            messageManager.sendMessage(initiator, "party-rename-fail-not-leader");
            return false;
        }
        if (!isValidPartyName(trimmedNewName)) {
            messageManager.sendMessage(initiator, "party-name-invalid", "name", trimmedNewName, "min_length", String.valueOf(partyNameMinLength), "max_length", String.valueOf(partyNameMaxLength));
            return false;
        }
        if (!party.getName().equalsIgnoreCase(trimmedNewName) && isPartyNameTaken(trimmedNewName)) {
            messageManager.sendMessage(initiator, "party-name-taken", "name", trimmedNewName);
            return false;
        }

        double renameCost = plugin.getConfig().getDouble("party.rename_cost", 500.0);
        if (economyService.isEnabled() && renameCost > 0) {
            if (!economyService.hasEnough(initiator.getUniqueId(), renameCost)) {
                messageManager.sendMessage(initiator, "error-not-enough-money", "amount", economyService.format(renameCost));
                return false;
            }
            if (!economyService.withdraw(initiator.getUniqueId(), renameCost)) {
                messageManager.sendMessage(initiator, "party-rename-fail-eco-error");
                return false;
            }
        }

        String oldName = party.getName();
        partyNameToId.remove(oldName.toLowerCase());
        party.setName(trimmedNewName);
        partyNameToId.put(trimmedNewName.toLowerCase(), party.getPartyId());
        saveParty(party);

        messageManager.sendMessage(initiator, "party-name-changed", "old_name", oldName, "new_name", trimmedNewName);
        if (renameCost > 0 && economyService.isEnabled()) {
            messageManager.sendMessage(initiator, "party-rename-cost-paid", "amount", economyService.format(renameCost));
        }
        return true;
    }

    public boolean transferLeadership(Party party, Player currentLeaderPlayer, OfflinePlayer newLeaderPlayer) {
        if (party == null || currentLeaderPlayer == null || newLeaderPlayer == null) return false;

        if (!party.isLeader(currentLeaderPlayer.getUniqueId())) {
            messageManager.sendMessage(currentLeaderPlayer, "party-transfer-fail-not-leader");
            return false;
        }
        if (currentLeaderPlayer.getUniqueId().equals(newLeaderPlayer.getUniqueId())) {
            messageManager.sendMessage(currentLeaderPlayer, "party-transfer-fail-self");
            return false;
        }

        PartyMember targetMember = party.getMember(newLeaderPlayer.getUniqueId()).orElse(null);
        if (targetMember == null || targetMember.getRole() == PartyRole.APPLICANT) {
            messageManager.sendMessage(currentLeaderPlayer, "party-transfer-fail-target-not-member", "player", newLeaderPlayer.getName());
            return false;
        }

        try {
            if (party.setLeader(newLeaderPlayer.getUniqueId())) {
                saveParty(party);
                messageManager.sendMessage(currentLeaderPlayer, "party-transfer-leader-success-own", "new_leader", newLeaderPlayer.getName());
                if (newLeaderPlayer.isOnline() && newLeaderPlayer.getPlayer() != null) {
                    messageManager.sendMessage(newLeaderPlayer.getPlayer(), "party-transfer-leader-notification-new", "party_name", party.getName(), "old_leader", currentLeaderPlayer.getName());
                }
                // Notify other members (optional)
                // party.getOfficialMemberIds().stream()
                //    .filter(id -> !id.equals(currentLeaderPlayer.getUniqueId()) && !id.equals(newLeaderPlayer.getUniqueId()))
                //    .map(Bukkit::getOfflinePlayer)
                //    .filter(OfflinePlayer::isOnline).map(OfflinePlayer::getPlayer).filter(Objects::nonNull)
                //    .forEach(p -> messageManager.sendMessage(p, "party-transfer-leader-notification-member", "party_name", party.getName(), "new_leader", newLeaderPlayer.getName(), "old_leader", currentLeaderPlayer.getName()));
                return true;
            }
        } catch (IllegalStateException e) { // Party.setLeader might throw this
            messageManager.sendMessage(currentLeaderPlayer, "error-generic-party-action", "details", e.getMessage());
        }
        return false;
    }

    /**
     * 党魁为一个国家任命本党的议员。
     * @param party 操作的政党
     * @param nation 目标国家
     * @param initiator 执行命令的党魁
     * @param mpCandidateNames 要任命为议员的玩家名称列表
     * @return 是否成功
     */
    public boolean setPartyMPsInNation(Party party, Nation nation, Player initiator, List<String> mpCandidateNames) {
        if (party == null || nation == null || initiator == null || mpCandidateNames == null) return false;

        if (!party.isLeader(initiator.getUniqueId())) {
            messageManager.sendMessage(initiator, "party-setmps-fail-not-leader", "party_name", party.getName()); // 新消息
            return false;
        }

        NationPolitics nationPolitics = getNationManager().getNationPolitics(nation);
        if (nationPolitics == null || !nationPolitics.getGovernmentType().hasParliament()) {
            messageManager.sendMessage(initiator, "party-setmps-fail-nation-no-parliament", "nation_name", nation.getName()); // 新消息
            return false;
        }

        Map<UUID, Integer> seatsWonMap = nationPolitics.getParliamentarySeatsWonByParty();
        int seatsPartyHas = seatsWonMap.getOrDefault(party.getPartyId(), 0);

        if (seatsPartyHas == 0) {
            messageManager.sendMessage(initiator, "party-setmps-fail-no-seats", "party_name", party.getName(), "nation_name", nation.getName()); // 新消息
            return false;
        }

        if (mpCandidateNames.size() > seatsPartyHas) {
            messageManager.sendMessage(initiator, "party-setmps-fail-too-many-mps", "count", String.valueOf(mpCandidateNames.size()), "seats", String.valueOf(seatsPartyHas)); // 新消息
            return false;
        }

        List<UUID> newMpUUIDs = new ArrayList<>();
        List<String> invalidPlayerNames = new ArrayList<>();
        List<String> notPartyMembers = new ArrayList<>();
        List<String> notNationCitizens = new ArrayList<>(); // 可选检查

        for (String playerName : mpCandidateNames) {
            OfflinePlayer mpPlayer = Bukkit.getOfflinePlayer(playerName); // 注意：这可能导致主线程卡顿
            if (!mpPlayer.hasPlayedBefore() && !mpPlayer.isOnline()) {
                invalidPlayerNames.add(playerName);
                continue;
            }
            // 检查是否为本党成员
            if (!party.isOfficialMember(mpPlayer.getUniqueId())) {
                notPartyMembers.add(playerName);
                continue;
            }
            // 可选：检查是否为该国公民
            Resident mpResident = TownyAPI.getInstance().getResident(mpPlayer.getUniqueId());
            boolean checkCitizenship = plugin.getConfig().getBoolean("bills.mp_must_be_citizen", true); // 新配置项
            if (checkCitizenship && (mpResident == null || !mpResident.hasNation() || !mpResident.getNationOrNull().equals(nation))) {
                notNationCitizens.add(playerName);
                continue;
            }
            newMpUUIDs.add(mpPlayer.getUniqueId());
        }

        if (!invalidPlayerNames.isEmpty()) {
            messageManager.sendMessage(initiator, "party-setmps-fail-invalid-players", "players", String.join(", ", invalidPlayerNames));
            return false;
        }
        if (!notPartyMembers.isEmpty()) {
            messageManager.sendMessage(initiator, "party-setmps-fail-not-party-members", "players", String.join(", ", notPartyMembers), "party_name", party.getName());
            return false;
        }
        if (!notNationCitizens.isEmpty()) {
            messageManager.sendMessage(initiator, "party-setmps-fail-not-citizens", "players", String.join(", ", notNationCitizens), "nation_name", nation.getName());
            return false;
        }

        if (new HashSet<>(newMpUUIDs).size() != newMpUUIDs.size()) { // 检查是否有重复任命
            messageManager.sendMessage(initiator, "party-setmps-fail-duplicate-mps"); // 新消息
            return false;
        }


        // 更新 NationPolitics
        nationPolitics.setParliamentaryMembersForParty(party.getPartyId(), newMpUUIDs);
        getNationManager().saveNationPolitics(nationPolitics);

        messageManager.sendMessage(initiator, "party-setmps-success", // 新消息
                "count", String.valueOf(newMpUUIDs.size()),
                "nation_name", nation.getName(),
                "party_name", party.getName());
        if (!newMpUUIDs.isEmpty()) {
            messageManager.sendMessage(initiator, "party-setmps-list-header"); // 新消息
            for(UUID mpId : newMpUUIDs){
                OfflinePlayer op = Bukkit.getOfflinePlayer(mpId);
                messageManager.sendMessage(initiator, "party-setmps-list-entry", "player_name", op.getName() != null ? op.getName() : mpId.toString().substring(0,6)); // 新消息
            }
        }
        // TODO: 通知被任命/移除的议员 (如果需要)
        return true;
    }

    /**
     * 党魁为一个国家添加一名本党议员。
     * @param party 操作的政党
     * @param nation 目标国家
     * @param initiator 执行命令的党魁
     * @param mpCandidateName 要添加为议员的玩家名称
     * @return 是否成功
     */
    public boolean addPartyMPInNation(Party party, Nation nation, Player initiator, String mpCandidateName) {
        if (party == null || nation == null || initiator == null || mpCandidateName == null || mpCandidateName.trim().isEmpty()) return false;

        if (!party.isLeader(initiator.getUniqueId())) {
            messageManager.sendMessage(initiator, "party-addmp-fail-not-leader", "party_name", party.getName()); // 新消息
            return false;
        }

        NationPolitics nationPolitics = getNationManager().getNationPolitics(nation);
        if (nationPolitics == null || !nationPolitics.getGovernmentType().hasParliament()) {
            messageManager.sendMessage(initiator, "party-addmp-fail-nation-no-parliament", "nation_name", nation.getName()); // 新消息
            return false;
        }

        Map<UUID, Integer> seatsWonMap = nationPolitics.getParliamentarySeatsWonByParty();
        int seatsPartyHas = seatsWonMap.getOrDefault(party.getPartyId(), 0);

        if (seatsPartyHas == 0) {
            messageManager.sendMessage(initiator, "party-addmp-fail-no-seats", "party_name", party.getName(), "nation_name", nation.getName()); // 新消息
            return false;
        }

        List<UUID> currentMps = nationPolitics.getParliamentaryMembersForParty(party.getPartyId());
        if (currentMps.size() >= seatsPartyHas) {
            messageManager.sendMessage(initiator, "party-addmp-fail-all-seats-filled", "seats", String.valueOf(seatsPartyHas)); // 新消息
            return false;
        }

        OfflinePlayer mpPlayer = Bukkit.getOfflinePlayer(mpCandidateName);
        if (!mpPlayer.hasPlayedBefore() && !mpPlayer.isOnline()) {
            messageManager.sendMessage(initiator, "party-addmp-fail-player-not-found", "player", mpCandidateName); // 新消息
            return false;
        }

        if (!party.isOfficialMember(mpPlayer.getUniqueId())) {
            messageManager.sendMessage(initiator, "party-addmp-fail-not-party-member", "player", mpCandidateName, "party_name", party.getName()); // 新消息
            return false;
        }

        Resident mpResident = TownyAPI.getInstance().getResident(mpPlayer.getUniqueId());
        boolean checkCitizenship = plugin.getConfig().getBoolean("bills.mp_must_be_citizen", true);
        if (checkCitizenship && (mpResident == null || !mpResident.hasNation() || !mpResident.getNationOrNull().equals(nation))) {
            messageManager.sendMessage(initiator, "party-addmp-fail-not-citizen", "player", mpCandidateName, "nation_name", nation.getName()); // 新消息
            return false;
        }

        if (currentMps.contains(mpPlayer.getUniqueId())) {
            messageManager.sendMessage(initiator, "party-addmp-fail-already-mp", "player", mpCandidateName); // 新消息
            return false;
        }

        currentMps.add(mpPlayer.getUniqueId());
        nationPolitics.setParliamentaryMembersForParty(party.getPartyId(), currentMps); // setParliamentaryMembersForParty 会处理 new ArrayList
        getNationManager().saveNationPolitics(nationPolitics);

        messageManager.sendMessage(initiator, "party-addmp-success", "player", mpPlayer.getName(), "nation_name", nation.getName()); // 新消息
        // TODO: 通知被任命的议员
        if (mpPlayer.isOnline() && mpPlayer.getPlayer() != null) {
            messageManager.sendMessage(mpPlayer.getPlayer(), "party-appointed-as-mp-notification", "party_name", party.getName(), "nation_name", nation.getName()); // 新消息
        }
        return true;
    }

    /**
     * 党魁从一个国家移除一名本党议员。
     * @param party 操作的政党
     * @param nation 目标国家
     * @param initiator 执行命令的党魁
     * @param mpCandidateName 要移除的议员的玩家名称
     * @return 是否成功
     */
    public boolean removePartyMPInNation(Party party, Nation nation, Player initiator, String mpCandidateName) {
        if (party == null || nation == null || initiator == null || mpCandidateName == null || mpCandidateName.trim().isEmpty()) return false;

        if (!party.isLeader(initiator.getUniqueId())) {
            messageManager.sendMessage(initiator, "party-removemp-fail-not-leader", "party_name", party.getName()); // 新消息
            return false;
        }

        NationPolitics nationPolitics = getNationManager().getNationPolitics(nation);
        if (nationPolitics == null || !nationPolitics.getGovernmentType().hasParliament()) {
            // 如果国家没有议会了，理论上议员列表应该已经被清空，但还是检查一下
            messageManager.sendMessage(initiator, "party-removemp-fail-nation-no-parliament", "nation_name", nation.getName()); // 新消息
            return false;
        }

        OfflinePlayer mpPlayer = Bukkit.getOfflinePlayer(mpCandidateName);
        if (!mpPlayer.hasPlayedBefore() && !mpPlayer.isOnline() && nationPolitics.getParliamentaryMembersForParty(party.getPartyId()).stream().noneMatch(id -> id.equals(mpPlayer.getUniqueId()))) {
            // 如果玩家不存在，并且他也不在当前议员列表里（通过UUID匹配，因为名字可能对不上）
            messageManager.sendMessage(initiator, "party-removemp-fail-player-not-found-or-not-mp", "player", mpCandidateName); // 新消息
            return false;
        }


        List<UUID> currentMps = nationPolitics.getParliamentaryMembersForParty(party.getPartyId());
        boolean removed = currentMps.remove(mpPlayer.getUniqueId());

        if (removed) {
            nationPolitics.setParliamentaryMembersForParty(party.getPartyId(), currentMps);
            getNationManager().saveNationPolitics(nationPolitics);
            messageManager.sendMessage(initiator, "party-removemp-success", "player", mpPlayer.getName() != null ? mpPlayer.getName() : mpCandidateName, "nation_name", nation.getName()); // 新消息
            // TODO: 通知被移除的议员
            if (mpPlayer.isOnline() && mpPlayer.getPlayer() != null) {
                messageManager.sendMessage(mpPlayer.getPlayer(), "party-removed-as-mp-notification", "party_name", party.getName(), "nation_name", nation.getName()); // 新消息
            }
            return true;
        } else {
            messageManager.sendMessage(initiator, "party-removemp-fail-not-an-mp", "player", mpCandidateName, "party_name", party.getName()); // 新消息
            return false;
        }
    }


    // --- Getters and Utility ---
    public Party getParty(UUID partyId) {
        return partiesById.get(partyId);
    }

    public Party getParty(String name) {
        if (name == null) return null;
        UUID partyId = partyNameToId.get(name.toLowerCase());
        return partyId != null ? partiesById.get(partyId) : null;
    }

    public Party getPartyByMember(UUID playerId) {
        UUID partyId = playerToPartyId.get(playerId);
        return partyId != null ? partiesById.get(partyId) : null;
    }

    public Collection<Party> getAllParties() {
        return Collections.unmodifiableCollection(partiesById.values());
    }

    public boolean isPartyNameTaken(String name) {
        return partyNameToId.containsKey(name.toLowerCase());
    }

    public boolean isValidPartyName(String name) {
        if (name == null) return false;
        if (name.length() < partyNameMinLength || name.length() > partyNameMaxLength) {
            return false;
        }
        return validPartyNamePattern.matcher(name).matches();
    }

    public void onPlayerJoinServer(Player player) {
        // 更新玩家名称缓存（如果他在某个党派中）
        Party party = getPartyByMember(player.getUniqueId());
        if (party != null) {
            party.getMember(player.getUniqueId()).ifPresent(pm -> pm.setNameCache(player.getName()));
        }
        // 其他逻辑，例如发送未读政党消息等
    }

    public void onPlayerQuitServer(Player player) {
        // 清理会话特定数据（如果未来有）
    }


    // --- Data Persistence (YAML per party) ---
    public void loadParties() {
        partiesById.clear();
        partyNameToId.clear();
        playerToPartyId.clear();

        if (!partiesDataFolder.exists()) {
            plugin.getLogger().info("Parties data folder does not exist. No parties loaded.");
            return;
        }

        File[] partyFiles = partiesDataFolder.listFiles((dir, name) -> name.toLowerCase().endsWith(PARTY_FILE_EXTENSION));
        if (partyFiles == null || partyFiles.length == 0) {
            plugin.getLogger().info("No party data files found.");
            return;
        }

        for (File partyFile : partyFiles) {
            YamlConfiguration partyConfig = new YamlConfiguration();
            try {
                partyConfig.load(partyFile); // 使用 try-with-resources or ensure stream is closed
                UUID partyId = UUID.fromString(partyConfig.getString("id"));
                String name = partyConfig.getString("name");
                // long creationTimestamp = partyConfig.getLong("creationTimestamp"); // Party constructor handles this

                ConfigurationSection membersSection = partyConfig.getConfigurationSection("members");
                if (membersSection == null || name == null || name.isEmpty()) {
                    plugin.getLogger().warning("Party file " + partyFile.getName() + " is corrupted (missing name or members section). Skipping.");
                    moveCorruptedFile(partyFile, "corrupted_load_party_");
                    continue;
                }

                UUID leaderId = null;
                Map<UUID, PartyRole> tempMemberRoles = new HashMap<>();
                Map<UUID, String> tempMemberNameCache = new HashMap<>();

                for (String uuidStr : membersSection.getKeys(false)) {
                    try {
                        UUID memberUuid = UUID.fromString(uuidStr);
                        PartyRole role = PartyRole.fromString(membersSection.getString(uuidStr + ".role", "MEMBER"))
                                .orElse(PartyRole.MEMBER); // Default to MEMBER if role is invalid
                        tempMemberRoles.put(memberUuid, role);
                        if (membersSection.contains(uuidStr + ".nameCache")) { // Load name cache
                            tempMemberNameCache.put(memberUuid, membersSection.getString(uuidStr + ".nameCache"));
                        }
                        if (role == PartyRole.LEADER) {
                            leaderId = memberUuid;
                        }
                    } catch (IllegalArgumentException e) {
                        plugin.getLogger().warning("Invalid UUID or Role in party file " + partyFile.getName() + " for member " + uuidStr + ". Skipping member.");
                    }
                }

                if (leaderId == null) {
                    plugin.getLogger().warning("Party file " + partyFile.getName() + " has no leader defined. Skipping party.");
                    moveCorruptedFile(partyFile, "no_leader_");
                    continue;
                }

                Party party = new Party(partyId, name, leaderId);
                party.setLastLeaderElectionTime(partyConfig.getLong("lastLeaderElectionTime", 0L));

                for (Map.Entry<UUID, PartyRole> entry : tempMemberRoles.entrySet()) {
                    party.addPlayerWithRoleInternal(entry.getKey(), entry.getValue()); // Use internal method
                    if (entry.getValue() != PartyRole.APPLICANT) {
                        playerToPartyId.put(entry.getKey(), partyId);
                    }
                    // Set name cache after adding member
                    if (tempMemberNameCache.containsKey(entry.getKey())) {
                        party.getMember(entry.getKey()).ifPresent(pm -> pm.setNameCache(tempMemberNameCache.get(entry.getKey())));
                    } else { // If no cache, try to populate it from Bukkit (for online players or recently seen)
                        OfflinePlayer op = Bukkit.getOfflinePlayer(entry.getKey());
                        if (op.getName() != null) {
                            party.getMember(entry.getKey()).ifPresent(pm -> pm.setNameCache(op.getName()));
                        }
                    }
                }

                partiesById.put(partyId, party);
                partyNameToId.put(name.toLowerCase(), partyId);

            } catch (IOException | InvalidConfigurationException | IllegalArgumentException e) {
                plugin.getLogger().log(Level.SEVERE, "Failed to load party from file: " + partyFile.getName(), e);
                moveCorruptedFile(partyFile, "corrupted_exception_");
            }
        }
        plugin.getLogger().info("Loaded " + partiesById.size() + " parties.");
    }

    public void saveParty(Party party) {
        if (party == null) return;
        File partyFile = new File(partiesDataFolder, party.getPartyId().toString() + PARTY_FILE_EXTENSION);
        YamlConfiguration partyConfig = new YamlConfiguration();

        partyConfig.set("id", party.getPartyId().toString());
        partyConfig.set("name", party.getName());
        partyConfig.set("creationTimestamp", party.getCreationTimestamp());
        partyConfig.set("lastLeaderElectionTime", party.getLastLeaderElectionTime());

        ConfigurationSection membersSection = partyConfig.createSection("members");
        for (PartyMember member : party.getAllPartyPersonnel()) {
            String path = member.getPlayerId().toString();
            membersSection.set(path + ".role", member.getRole().name());
            membersSection.set(path + ".nameCache", member.getName()); // Save the (potentially cached) name
        }

        try {
            partyConfig.save(partyFile);
        } catch (IOException e) {
            plugin.getLogger().log(Level.SEVERE, "Could not save party: " + party.getName() + " (ID: " + party.getPartyId() + ")", e);
        }
    }

    private void moveCorruptedFile(File file, String prefix) {
        if (file == null || !file.exists()) return;
        File corruptedFolder = new File(partiesDataFolder.getParentFile(), "corrupted_data");
        if (!corruptedFolder.exists()) corruptedFolder.mkdirs();
        File newFile = new File(corruptedFolder, prefix + file.getName() + "_" + System.currentTimeMillis() + ".yml_disabled");
        try {
            Files.move(file.toPath(), newFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
            plugin.getLogger().warning("Moved corrupted file " + file.getName() + " to " + newFile.getPath());
        } catch (IOException ex) {
            plugin.getLogger().log(Level.SEVERE, "Could not move corrupted file " + file.getName(), ex);
        }
    }


    public void saveAllParties() {
        plugin.getLogger().info("Saving all parties (" + partiesById.size() + ")...");
        for (Party party : partiesById.values()) {
            saveParty(party);
        }
        plugin.getLogger().info("All parties saved.");
    }

    private void deletePartyDataFile(UUID partyId) {
        if (partyId == null) return;
        File partyFile = new File(partiesDataFolder, partyId.toString() + PARTY_FILE_EXTENSION);
        if (partyFile.exists()) {
            if (!partyFile.delete()) {
                plugin.getLogger().warning("Could not delete party data file: " + partyFile.getName());
            }
        }
    }

    public void reloadPartyConfigAndData() {
        loadConfigurableNameRules(); // Reload name rules from config.yml
        loadParties(); // Reload all parties from disk
        plugin.getLogger().info("Party configuration and data reloaded.");
    }

    public void shutdown() {
        saveAllParties();
    }
}