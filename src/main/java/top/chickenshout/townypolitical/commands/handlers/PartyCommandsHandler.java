// 文件名: PartyCommandsHandler.java
// 结构位置: top/chickenshout/townypolitical/commands/handlers/PartyCommandsHandler.java
package top.chickenshout.townypolitical.commands.handlers;

import com.palmergames.bukkit.towny.TownyAPI;
import com.palmergames.bukkit.towny.object.Nation;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import top.chickenshout.townypolitical.TownyPolitical;
import top.chickenshout.townypolitical.data.NationPolitics;
import top.chickenshout.townypolitical.data.Party;
import top.chickenshout.townypolitical.data.PartyMember;
import top.chickenshout.townypolitical.enums.PartyRole;
import top.chickenshout.townypolitical.managers.NationManager;
import top.chickenshout.townypolitical.managers.PartyManager;
import top.chickenshout.townypolitical.utils.MessageManager;

import java.util.*;
import java.util.concurrent.CompletableFuture; // For async player lookup
import java.util.stream.Collectors;

public class PartyCommandsHandler {

    private final TownyPolitical plugin;
    private final MessageManager messageManager;
    private final PartyManager partyManager;
    private final NationManager nationManager;

    public PartyCommandsHandler(TownyPolitical plugin) {
        this.plugin = plugin;
        this.messageManager = plugin.getMessageManager();
        this.partyManager = plugin.getPartyManager();
        this.nationManager = plugin .getNationManager();
    }

    public boolean handleCommand(CommandSender sender, String commandLabel, String[] args) {
        if (args.length == 0) {
            sendPartyHelp(sender, commandLabel);
            return true;
        }

        String subCommand = args[0].toLowerCase();
        String[] subArgs = new String[args.length - 1];
        if (args.length > 1) {
            System.arraycopy(args, 1, subArgs, 0, args.length - 1);
        }

        switch (subCommand) {
            case "create":
                return handleCreateCommand(sender, commandLabel, subArgs);
            case "info":
                return handleInfoCommand(sender, commandLabel, subArgs);
            case "list":
                return handleListCommand(sender, commandLabel, subArgs);
            case "apply":
                return handleApplyCommand(sender, commandLabel, subArgs);
            case "leave":
                return handleLeaveCommand(sender, commandLabel, subArgs);
            case "accept":
                return handleAcceptCommand(sender, commandLabel, subArgs);
            case "reject":
                return handleRejectCommand(sender, commandLabel, subArgs);
            case "disband":
                return handleDisbandCommand(sender, commandLabel, subArgs);
            case "invite": // 'invite' here means direct add by admin/leader
                return handleInviteCommand(sender, commandLabel, subArgs);
            case "kick":
                return handleKickCommand(sender, commandLabel, subArgs);
            case "promote":
                return handlePromoteCommand(sender, commandLabel, subArgs);
            case "demote":
                return handleDemoteCommand(sender, commandLabel, subArgs);
            case "rename":
                return handleRenameCommand(sender, commandLabel, subArgs);
            case "setleader":
                return handleSetLeaderCommand(sender, commandLabel, subArgs);
            case "setmps": // 可以保留这个作为批量设置，或改为 setallmps
                return handleSetAllMPsCommand(sender, commandLabel, subArgs); // 调用新的处理方法名
            case "addmp": // <--- 新增
                return handleAddMPCommand(sender, commandLabel, subArgs);
            case "removemp": // <--- 新增
                return handleRemoveMPCommand(sender, commandLabel, subArgs);
            case "listmps": // <--- 新增，用于查看议员
                return handleListMPsCommand(sender, commandLabel, subArgs);
            default:
                messageManager.sendMessage(sender, "command-party-unknown", "subcommand", subCommand);
                sendPartyHelp(sender, commandLabel);
                return true;
        }
    }


    private boolean handleCreateCommand(CommandSender sender, String commandLabel, String[] subArgs) {
        if (!(sender instanceof Player)) {
            messageManager.sendMessage(sender, "error-player-only-command");
            return true;
        }
        Player player = (Player) sender;

        if (!player.hasPermission("townypolitical.party.create")) {
            messageManager.sendMessage(player, "error-no-permission");
            return true;
        }

        if (subArgs.length < 1) {
            messageManager.sendMessage(player, "error-invalid-arguments", "usage", "/" + commandLabel + " create <政党名称>");
            return true;
        }

        String partyName = String.join(" ", subArgs);
        partyManager.createParty(player, partyName);
        return true;
    }

    private boolean handleInfoCommand(CommandSender sender, String commandLabel, String[] subArgs) {
        if (!sender.hasPermission("townypolitical.party.info")) {
            messageManager.sendMessage(sender, "error-no-permission");
            return true;
        }

        Party targetParty;
        if (subArgs.length == 0) {
            if (!(sender instanceof Player)) {
                messageManager.sendMessage(sender, "error-player-only-command-for-own-party-info");
                messageManager.sendMessage(sender, "error-invalid-arguments", "usage", "/" + commandLabel + " info <政党名称>");
                return true;
            }
            Player player = (Player) sender;
            targetParty = partyManager.getPartyByMember(player.getUniqueId());
            if (targetParty == null) {
                messageManager.sendMessage(player, "party-info-fail-not-in-party");
                messageManager.sendMessage(player, "error-invalid-arguments", "usage", "/" + commandLabel + " info [政党名称]");
                return true;
            }
        } else {
            String partyName = String.join(" ", subArgs);
            targetParty = partyManager.getParty(partyName);
            if (targetParty == null) {
                messageManager.sendMessage(sender, "error-party-not-found", "party", partyName);
                return true;
            }
        }

        messageManager.sendRawMessage(sender, "party-info-header", "party_name", targetParty.getName());
        targetParty.getLeader().ifPresent(leader ->
                messageManager.sendRawMessage(sender, "party-info-leader", "name", leader.getName())
        );

        List<PartyMember> admins = targetParty.getAdmins();
        messageManager.sendRawMessage(sender, "party-info-admins", "count", String.valueOf(admins.size()));
        if (admins.isEmpty()) {
            messageManager.sendRawMessage(sender, "party-info-no-one");
        } else {
            admins.forEach(admin -> messageManager.sendRawMessage(sender, "party-member-info-entry", "name", admin.getName(), "role", admin.getRole().getDisplayName()));
        }

        List<PartyMember> members = targetParty.getRegularMembers();
        messageManager.sendRawMessage(sender, "party-info-members", "count", String.valueOf(members.size()));
        if (members.isEmpty()) {
            messageManager.sendRawMessage(sender, "party-info-no-one");
        } else {
            members.forEach(member -> messageManager.sendRawMessage(sender, "party-member-info-entry", "name", member.getName(), "role", member.getRole().getDisplayName()));
        }

        boolean canSeeApplicants = false;
        if (sender instanceof Player) {
            Player playerSender = (Player) sender;
            PartyMember senderPartyMember = targetParty.getMember(playerSender.getUniqueId()).orElse(null);
            if (senderPartyMember != null && senderPartyMember.getRole().hasPermissionOf(PartyRole.ADMIN)) {
                canSeeApplicants = true;
            }
        }
        if (sender.hasPermission("townypolitical.party.manage_applicants")) {
            canSeeApplicants = true;
        }


        if (canSeeApplicants) {
            List<PartyMember> applicants = targetParty.getApplicants();
            if (!applicants.isEmpty()) {
                messageManager.sendRawMessage(sender, "party-info-applicants", "count", String.valueOf(applicants.size()));
                applicants.forEach(applicant -> messageManager.sendRawMessage(sender, "party-applicant-info-entry", "name", applicant.getName()));
            } else if (sender instanceof Player && targetParty.getMember(((Player)sender).getUniqueId()).isPresent()){
                messageManager.sendRawMessage(sender, "party-info-no-applicants");
            }
        }
        return true;
    }

    private boolean handleListCommand(CommandSender sender, String commandLabel, String[] subArgs) {
        if (!sender.hasPermission("townypolitical.party.list")) {
            messageManager.sendMessage(sender, "error-no-permission");
            return true;
        }

        Collection<Party> parties = partyManager.getAllParties();
        if (parties.isEmpty()) {
            messageManager.sendMessage(sender, "party-list-empty");
            return true;
        }

        messageManager.sendRawMessage(sender, "party-list-header", "count", String.valueOf(parties.size()));
        for (Party party : parties) {
            messageManager.sendRawMessage(sender, "party-list-entry",
                    "name", party.getName(),
                    "leader_name", party.getLeader().map(PartyMember::getName).orElse("N/A"),
                    "member_count", String.valueOf(party.getOfficialMemberIds().size())
            );
        }
        return true;
    }

    private boolean handleApplyCommand(CommandSender sender, String commandLabel, String[] subArgs) {
        if (!(sender instanceof Player)) {
            messageManager.sendMessage(sender, "error-player-only-command");
            return true;
        }
        Player player = (Player) sender;

        if (!player.hasPermission("townypolitical.party.apply")) {
            messageManager.sendMessage(player, "error-no-permission");
            return true;
        }

        if (subArgs.length < 1) {
            messageManager.sendMessage(player, "error-invalid-arguments", "usage", "/" + commandLabel + " apply <政党名称>");
            return true;
        }

        String partyName = String.join(" ", subArgs);
        Party targetParty = partyManager.getParty(partyName);
        if (targetParty == null) {
            messageManager.sendMessage(player, "error-party-not-found", "party", partyName);
            return true;
        }

        partyManager.playerApplyToParty(player, targetParty);
        return true;
    }

    private boolean handleLeaveCommand(CommandSender sender, String commandLabel, String[] subArgs) {
        if (!(sender instanceof Player)) {
            messageManager.sendMessage(sender, "error-player-only-command");
            return true;
        }
        Player player = (Player) sender;

        if (!player.hasPermission("townypolitical.party.leave")) {
            messageManager.sendMessage(player, "error-no-permission");
            return true;
        }

        partyManager.leaveParty(player);
        return true;
    }

    private boolean handleAcceptCommand(CommandSender sender, String commandLabel, String[] subArgs) {
        if (!(sender instanceof Player)) {
            messageManager.sendMessage(sender, "error-player-only-command");
            return true;
        }
        Player adminReviewer = (Player) sender;

        if (!adminReviewer.hasPermission("townypolitical.party.manage_applications")) {
            Party party = partyManager.getPartyByMember(adminReviewer.getUniqueId());
            if(party == null || !party.getMember(adminReviewer.getUniqueId()).map(m -> m.getRole().hasPermissionOf(PartyRole.ADMIN)).orElse(false)){
                messageManager.sendMessage(adminReviewer, "error-no-permission");
                return true;
            }
        }

        if (subArgs.length < 1) {
            messageManager.sendMessage(adminReviewer, "error-invalid-arguments", "usage", "/" + commandLabel + " accept <玩家名称>");
            return true;
        }

        String targetPlayerName = subArgs[0];
        // Use async lookup for OfflinePlayer if on Paper, for Spigot getOfflinePlayer is acceptable for commands
        OfflinePlayer applicantPlayer = Bukkit.getOfflinePlayer(targetPlayerName); // This can block if player is not cached
        if (!applicantPlayer.hasPlayedBefore() && !applicantPlayer.isOnline()) {
            messageManager.sendMessage(adminReviewer, "error-player-not-found-or-never-played", "player", targetPlayerName);
            return true;
        }

        Party party = partyManager.getPartyByMember(adminReviewer.getUniqueId());
        if (party == null) {
            messageManager.sendMessage(adminReviewer, "party-admin-action-fail-not-in-party");
            return true;
        }

        partyManager.acceptPartyApplication(adminReviewer, applicantPlayer, party);
        return true;
    }

    private boolean handleRejectCommand(CommandSender sender, String commandLabel, String[] subArgs) {
        if (!(sender instanceof Player)) {
            messageManager.sendMessage(sender, "error-player-only-command");
            return true;
        }
        Player adminReviewer = (Player) sender;

        if (!adminReviewer.hasPermission("townypolitical.party.manage_applications")) {
            Party party = partyManager.getPartyByMember(adminReviewer.getUniqueId());
            if(party == null || !party.getMember(adminReviewer.getUniqueId()).map(m -> m.getRole().hasPermissionOf(PartyRole.ADMIN)).orElse(false)){
                messageManager.sendMessage(adminReviewer, "error-no-permission");
                return true;
            }
        }

        if (subArgs.length < 1) {
            messageManager.sendMessage(adminReviewer, "error-invalid-arguments", "usage", "/" + commandLabel + " reject <玩家名称>");
            return true;
        }

        String targetPlayerName = subArgs[0];
        OfflinePlayer applicantPlayer = Bukkit.getOfflinePlayer(targetPlayerName);
        if (!applicantPlayer.hasPlayedBefore() && !applicantPlayer.isOnline()) {
            messageManager.sendMessage(adminReviewer, "error-player-not-found-or-never-played", "player", targetPlayerName);
            return true;
        }

        Party party = partyManager.getPartyByMember(adminReviewer.getUniqueId());
        if (party == null) {
            messageManager.sendMessage(adminReviewer, "party-admin-action-fail-not-in-party");
            return true;
        }

        partyManager.rejectPartyApplication(adminReviewer, applicantPlayer, party);
        return true;
    }
    // --- End of existing command handlers ---

    private boolean handleDisbandCommand(CommandSender sender, String commandLabel, String[] subArgs) {
        if (!(sender instanceof Player)) {
            messageManager.sendMessage(sender, "error-player-only-command");
            return true;
        }
        Player player = (Player) sender;

        if (!player.hasPermission("townypolitical.party.disband")) { // General permission to use the command
            messageManager.sendMessage(player, "error-no-permission");
            return true;
        }

        Party party = partyManager.getPartyByMember(player.getUniqueId());
        if (party == null) {
            messageManager.sendMessage(player, "party-disband-fail-not-in-party");
            return true;
        }

        // Confirmation step
        if (subArgs.length == 0 || !subArgs[0].equalsIgnoreCase("confirm")) {
            messageManager.sendMessage(player, "party-disband-confirm-required", "party_name", party.getName(), "label", commandLabel);
            return true;
        }

        partyManager.disbandParty(party, player); // PartyManager will do the leader check
        return true;
    }

    private boolean handleInviteCommand(CommandSender sender, String commandLabel, String[] subArgs) {
        if (!(sender instanceof Player)) {
            messageManager.sendMessage(sender, "error-player-only-command");
            return true;
        }
        Player inviter = (Player) sender;

        if (!inviter.hasPermission("townypolitical.party.invite")) {
            messageManager.sendMessage(inviter, "error-no-permission");
            return true;
        }

        if (subArgs.length < 1) {
            messageManager.sendMessage(inviter, "error-invalid-arguments", "usage", "/" + commandLabel + " invite <玩家名称>");
            return true;
        }

        String targetPlayerName = subArgs[0];
        OfflinePlayer targetPlayer = Bukkit.getOfflinePlayer(targetPlayerName);
        if (!targetPlayer.hasPlayedBefore() && !targetPlayer.isOnline()) { // Check if player exists
            messageManager.sendMessage(inviter, "error-player-not-found-or-never-played", "player", targetPlayerName);
            return true;
        }

        Party party = partyManager.getPartyByMember(inviter.getUniqueId());
        if (party == null) {
            messageManager.sendMessage(inviter, "party-invite-fail-inviter-not-in-party");
            return true;
        }

        partyManager.invitePlayer(inviter, targetPlayer, party); // PartyManager handles logic
        return true;
    }

    private boolean handleKickCommand(CommandSender sender, String commandLabel, String[] subArgs) {
        if (!(sender instanceof Player)) {
            messageManager.sendMessage(sender, "error-player-only-command");
            return true;
        }
        Player kicker = (Player) sender;

        if (!kicker.hasPermission("townypolitical.party.kick")) {
            messageManager.sendMessage(kicker, "error-no-permission");
            return true;
        }

        if (subArgs.length < 1) {
            messageManager.sendMessage(kicker, "error-invalid-arguments", "usage", "/" + commandLabel + " kick <玩家名称>");
            return true;
        }

        String targetPlayerName = subArgs[0];
        OfflinePlayer targetPlayer = Bukkit.getOfflinePlayer(targetPlayerName);
        if (!targetPlayer.hasPlayedBefore() && !targetPlayer.isOnline()) {
            messageManager.sendMessage(kicker, "error-player-not-found-or-never-played", "player", targetPlayerName);
            return true;
        }

        Party party = partyManager.getPartyByMember(kicker.getUniqueId());
        if (party == null) {
            messageManager.sendMessage(kicker, "party-kick-fail-kicker-not-in-party");
            return true;
        }

        partyManager.kickPlayer(kicker, targetPlayer, party);
        return true;
    }

    private boolean handlePromoteCommand(CommandSender sender, String commandLabel, String[] subArgs) {
        if (!(sender instanceof Player)) {
            messageManager.sendMessage(sender, "error-player-only-command");
            return true;
        }
        Player promoter = (Player) sender;

        if (!promoter.hasPermission("townypolitical.party.promote")) {
            messageManager.sendMessage(promoter, "error-no-permission");
            return true;
        }

        if (subArgs.length < 1) {
            messageManager.sendMessage(promoter, "error-invalid-arguments", "usage", "/" + commandLabel + " promote <玩家名称>");
            return true;
        }

        String targetPlayerName = subArgs[0];
        OfflinePlayer targetPlayer = Bukkit.getOfflinePlayer(targetPlayerName);
        if (!targetPlayer.hasPlayedBefore() && !targetPlayer.isOnline()) {
            messageManager.sendMessage(promoter, "error-player-not-found-or-never-played", "player", targetPlayerName);
            return true;
        }

        Party party = partyManager.getPartyByMember(promoter.getUniqueId());
        if (party == null) {
            messageManager.sendMessage(promoter, "party-promote-fail-promoter-not-in-party");
            return true;
        }

        partyManager.promotePlayer(promoter, targetPlayer, party);
        return true;
    }

    private boolean handleDemoteCommand(CommandSender sender, String commandLabel, String[] subArgs) {
        if (!(sender instanceof Player)) {
            messageManager.sendMessage(sender, "error-player-only-command");
            return true;
        }
        Player demoter = (Player) sender;

        if (!demoter.hasPermission("townypolitical.party.demote")) {
            messageManager.sendMessage(demoter, "error-no-permission");
            return true;
        }

        if (subArgs.length < 1) {
            messageManager.sendMessage(demoter, "error-invalid-arguments", "usage", "/" + commandLabel + " demote <玩家名称>");
            return true;
        }

        String targetPlayerName = subArgs[0];
        OfflinePlayer targetPlayer = Bukkit.getOfflinePlayer(targetPlayerName);
        if (!targetPlayer.hasPlayedBefore() && !targetPlayer.isOnline()) {
            messageManager.sendMessage(demoter, "error-player-not-found-or-never-played", "player", targetPlayerName);
            return true;
        }

        Party party = partyManager.getPartyByMember(demoter.getUniqueId());
        if (party == null) {
            messageManager.sendMessage(demoter, "party-demote-fail-demoter-not-in-party");
            return true;
        }

        partyManager.demotePlayer(demoter, targetPlayer, party);
        return true;
    }

    private boolean handleRenameCommand(CommandSender sender, String commandLabel, String[] subArgs) {
        if (!(sender instanceof Player)) {
            messageManager.sendMessage(sender, "error-player-only-command");
            return true;
        }
        Player player = (Player) sender;

        if (!player.hasPermission("townypolitical.party.rename")) {
            messageManager.sendMessage(player, "error-no-permission");
            return true;
        }

        if (subArgs.length < 1) {
            messageManager.sendMessage(player, "error-invalid-arguments", "usage", "/" + commandLabel + " rename <新政党名称>");
            return true;
        }

        Party party = partyManager.getPartyByMember(player.getUniqueId());
        if (party == null) {
            messageManager.sendMessage(player, "party-rename-fail-not-in-party");
            return true;
        }

        String newPartyName = String.join(" ", subArgs);
        partyManager.renameParty(party, newPartyName, player);
        return true;
    }

    private boolean handleSetLeaderCommand(CommandSender sender, String commandLabel, String[] subArgs) {
        if (!(sender instanceof Player)) {
            messageManager.sendMessage(sender, "error-player-only-command");
            return true;
        }
        Player currentLeader = (Player) sender;

        if (!currentLeader.hasPermission("townypolitical.party.setleader")) {
            messageManager.sendMessage(currentLeader, "error-no-permission");
            return true;
        }

        if (subArgs.length < 1) {
            messageManager.sendMessage(currentLeader, "error-invalid-arguments", "usage", "/" + commandLabel + " setleader <新领袖玩家名称>");
            return true;
        }

        String targetPlayerName = subArgs[0];
        OfflinePlayer newLeaderPlayer = Bukkit.getOfflinePlayer(targetPlayerName);
        if (!newLeaderPlayer.hasPlayedBefore() && !newLeaderPlayer.isOnline()) {
            messageManager.sendMessage(currentLeader, "error-player-not-found-or-never-played", "player", targetPlayerName);
            return true;
        }

        Party party = partyManager.getPartyByMember(currentLeader.getUniqueId());
        if (party == null) {
            messageManager.sendMessage(currentLeader, "party-setleader-fail-not-in-party");
            return true;
        }

        partyManager.transferLeadership(party, currentLeader, newLeaderPlayer);
        return true;
    }

    private boolean handleSetAllMPsCommand(CommandSender sender, String commandLabel, String[] subArgs) {
        if (!(sender instanceof Player)) {
            messageManager.sendMessage(sender, "error-player-only-command");
            return true;
        }
        Player player = (Player) sender;
        if (!player.hasPermission("townypolitical.party.setmps")) { // 新权限
            messageManager.sendMessage(player, "error-no-permission");
            return true;
        }
        // 用法: /tparty setmps <国家名> [玩家1] [玩家2] ...
        // 如果不提供玩家名，则视为清空该党在该国的议员
        if (subArgs.length < 1) {
            messageManager.sendMessage(player, "error-invalid-arguments", "usage", "/" + commandLabel + " setmps <国家名> [议员玩家名列表...]");
            return true;
        }
        Party party = partyManager.getPartyByMember(player.getUniqueId());
        if (party == null) {
            messageManager.sendMessage(player, "party-command-fail-not-in-party"); // 新消息或复用
            return true;
        }
        String nationName = subArgs[0];
        Nation nation = TownyAPI.getInstance().getNation(nationName);
        if (nation == null) {
            messageManager.sendMessage(player, "error-nation-not-found", "nation", nationName);
            return true;
        }
        List<String> mpNames = new ArrayList<>();
        if (subArgs.length > 1) {
            mpNames.addAll(Arrays.asList(subArgs).subList(1, subArgs.length));
        }
        // 如果 mpNames 为空，则表示清空
        partyManager.setPartyMPsInNation(party, nation, player, mpNames);
        return true;
    }

    private boolean handleAddMPCommand(CommandSender sender, String commandLabel, String[] subArgs) {
        if (!(sender instanceof Player)) { /* ... */ return true; }
        Player player = (Player) sender;
        if (!player.hasPermission("townypolitical.party.addmp")) { // 新权限
            messageManager.sendMessage(player, "error-no-permission");
            return true;
        }
        // 用法: /tparty addmp <国家名> <玩家名>
        if (subArgs.length < 2) {
            messageManager.sendMessage(player, "error-invalid-arguments", "usage", "/" + commandLabel + " addmp <国家名> <玩家名>");
            return true;
        }
        Party party = partyManager.getPartyByMember(player.getUniqueId());
        if (party == null) { /* ... */ return true; }
        String nationName = subArgs[0];
        Nation nation = TownyAPI.getInstance().getNation(nationName);
        if (nation == null) { /* ... */ return true; }
        String mpName = subArgs[1];

        partyManager.addPartyMPInNation(party, nation, player, mpName);
        return true;
    }

    private boolean handleRemoveMPCommand(CommandSender sender, String commandLabel, String[] subArgs) {
        if (!(sender instanceof Player)) { /* ... */ return true; }
        Player player = (Player) sender;
        if (!player.hasPermission("townypolitical.party.removemp")) { // 新权限
            messageManager.sendMessage(player, "error-no-permission");
            return true;
        }
        // 用法: /tparty removemp <国家名> <玩家名>
        if (subArgs.length < 2) {
            messageManager.sendMessage(player, "error-invalid-arguments", "usage", "/" + commandLabel + " removemp <国家名> <玩家名>");
            return true;
        }
        Party party = partyManager.getPartyByMember(player.getUniqueId());
        if (party == null) { /* ... */ return true; }
        String nationName = subArgs[0];
        Nation nation = TownyAPI.getInstance().getNation(nationName);
        if (nation == null) { /* ... */ return true; }
        String mpName = subArgs[1];

        partyManager.removePartyMPInNation(party, nation, player, mpName);
        return true;
    }

    private boolean handleListMPsCommand(CommandSender sender, String commandLabel, String[] subArgs) {
        if (!sender.hasPermission("townypolitical.party.listmps")) { // 新权限
            messageManager.sendMessage(sender, "error-no-permission");
            return true;
        }
        // 用法: /tparty listmps <国家名> [政党名]
        if (subArgs.length < 1) {
            messageManager.sendMessage(sender, "error-invalid-arguments", "usage", "/" + commandLabel + " listmps <国家名> [政党名]");
            return true;
        }
        String nationName = subArgs[0];
        Nation nation = TownyAPI.getInstance().getNation(nationName);
        if (nation == null) {
            messageManager.sendMessage(sender, "error-nation-not-found", "nation", nationName);
            return true;
        }

        NationPolitics politics = nationManager.getNationPolitics(nation);
        if (politics == null || !politics.getGovernmentType().hasParliament()) {
            messageManager.sendMessage(sender, "party-listmps-fail-nation-no-parliament", "nation_name", nation.getName()); // 新消息
            return true;
        }

        Party filterParty = null;
        if (subArgs.length > 1) {
            String partyName = subArgs[1];
            filterParty = partyManager.getParty(partyName);
            if (filterParty == null) {
                messageManager.sendMessage(sender, "error-party-not-found", "party", partyName);
                return true;
            }
        }

        messageManager.sendRawMessage(sender, "party-listmps-header", "nation_name", nation.getName()); // 新消息
        Map<UUID, List<UUID>> mpsByParty = politics.getParliamentaryMembersByPartyInternal();

        if (mpsByParty.isEmpty() || mpsByParty.values().stream().allMatch(List::isEmpty)) {
            messageManager.sendRawMessage(sender, "party-listmps-none-appointed", "nation_name", nation.getName()); // 新消息
            return true;
        }

        boolean foundAny = false;
        for (Map.Entry<UUID, List<UUID>> entry : mpsByParty.entrySet()) {
            Party party = partyManager.getParty(entry.getKey());
            if (party == null) continue; // 政党数据可能已丢失

            if (filterParty != null && !filterParty.getPartyId().equals(party.getPartyId())) {
                continue; // 如果指定了政党过滤，则跳过其他政党
            }

            if (!entry.getValue().isEmpty()) {
                foundAny = true;
                messageManager.sendRawMessage(sender, "party-listmps-party-header", "party_name", party.getName(), "count", String.valueOf(entry.getValue().size())); // 新消息
                for (UUID mpId : entry.getValue()) {
                    OfflinePlayer mpPlayer = Bukkit.getOfflinePlayer(mpId);
                    messageManager.sendRawMessage(sender, "party-listmps-entry", "player_name", mpPlayer.getName() != null ? mpPlayer.getName() : "ID:"+mpId.toString().substring(0,6)); // 新消息
                }
            }
        }
        if (!foundAny) {
            if (filterParty != null) {
                messageManager.sendRawMessage(sender, "party-listmps-party-none-appointed", "party_name", filterParty.getName(), "nation_name", nation.getName()); // 新消息
            } else {
                messageManager.sendRawMessage(sender, "party-listmps-none-appointed", "nation_name", nation.getName());
            }
        }
        return true;
    }

    private void sendPartyHelp(CommandSender sender, String commandLabel) {
        // (保持之前的 sendPartyHelp 方法，并根据新添加的命令和权限进行扩充)
        String displayLabel = commandLabel.split(" ")[0];
        boolean isAlias = displayLabel.equalsIgnoreCase("tparty") || displayLabel.equalsIgnoreCase("party");
        if (!isAlias) { // If it was /tp, it becomes /tp party
            displayLabel += " party";
        }

        messageManager.sendRawMessage(sender, "help-party-header", "label", displayLabel);
        if (sender.hasPermission("townypolitical.party.create"))
            messageManager.sendRawMessage(sender, "help-party-create", "label", displayLabel);
        if (sender.hasPermission("townypolitical.party.info"))
            messageManager.sendRawMessage(sender, "help-party-info", "label", displayLabel);
        if (sender.hasPermission("townypolitical.party.list"))
            messageManager.sendRawMessage(sender, "help-party-list", "label", displayLabel);
        if (sender.hasPermission("townypolitical.party.apply"))
            messageManager.sendRawMessage(sender, "help-party-apply", "label", displayLabel);
        if (sender.hasPermission("townypolitical.party.leave"))
            messageManager.sendRawMessage(sender, "help-party-leave", "label", displayLabel);
        if (sender.hasPermission("townypolitical.party.manage_applications") || (sender instanceof Player && partyManager.getPartyByMember(((Player)sender).getUniqueId()) != null && partyManager.getPartyByMember(((Player)sender).getUniqueId()).getMember(((Player)sender).getUniqueId()).get().getRole().hasPermissionOf(PartyRole.ADMIN))) {
            messageManager.sendRawMessage(sender, "help-party-accept", "label", displayLabel);
            messageManager.sendRawMessage(sender, "help-party-reject", "label", displayLabel);
        }
        if (sender.hasPermission("townypolitical.party.disband"))
            messageManager.sendRawMessage(sender, "help-party-disband", "label", displayLabel);
        if (sender.hasPermission("townypolitical.party.invite"))
            messageManager.sendRawMessage(sender, "help-party-invite", "label", displayLabel);
        if (sender.hasPermission("townypolitical.party.kick"))
            messageManager.sendRawMessage(sender, "help-party-kick", "label", displayLabel);
        if (sender.hasPermission("townypolitical.party.promote"))
            messageManager.sendRawMessage(sender, "help-party-promote", "label", displayLabel);
        if (sender.hasPermission("townypolitical.party.demote"))
            messageManager.sendRawMessage(sender, "help-party-demote", "label", displayLabel);
        if (sender.hasPermission("townypolitical.party.rename"))
            messageManager.sendRawMessage(sender, "help-party-rename", "label", displayLabel);
        if (sender.hasPermission("townypolitical.party.setleader"))
            messageManager.sendRawMessage(sender, "help-party-setleader", "label", displayLabel);
        if (sender.hasPermission("townypolitical.party.setmps"))
            messageManager.sendRawMessage(sender, "help-party-setmps", "label", displayLabel);
        if (sender.hasPermission("townypolitical.party.addmp"))
            messageManager.sendRawMessage(sender, "help-party-addmp", "label", displayLabel); // 新消息键
        if (sender.hasPermission("townypolitical.party.removemp"))
            messageManager.sendRawMessage(sender, "help-party-removemp", "label", displayLabel); // 新消息键
        if (sender.hasPermission("townypolitical.party.listmps"))
            messageManager.sendRawMessage(sender, "help-party-listmps", "label", displayLabel); // 新消息键
        messageManager.sendRawMessage(sender, "help-footer");
    }
}
