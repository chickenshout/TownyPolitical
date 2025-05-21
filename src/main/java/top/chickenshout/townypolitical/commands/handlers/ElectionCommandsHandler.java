// 文件名: ElectionCommandsHandler.java
// 结构位置: top/chickenshout/townypolitical/commands/handlers/ElectionCommandsHandler.java
package top.chickenshout.townypolitical.commands.handlers;

import com.palmergames.bukkit.towny.TownyAPI;
import com.palmergames.bukkit.towny.object.Nation;
import com.palmergames.bukkit.towny.object.Resident;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import top.chickenshout.townypolitical.TownyPolitical;
import top.chickenshout.townypolitical.data.Party;
import top.chickenshout.townypolitical.elections.Candidate;
import top.chickenshout.townypolitical.elections.Election;
import top.chickenshout.townypolitical.enums.ElectionStatus;
import top.chickenshout.townypolitical.enums.ElectionType;
import top.chickenshout.townypolitical.managers.ElectionManager;
import top.chickenshout.townypolitical.managers.PartyManager;
import top.chickenshout.townypolitical.utils.MessageManager;

import javax.annotation.Nullable;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.stream.Collectors;

public class ElectionCommandsHandler {
    private final TownyPolitical plugin;
    private final MessageManager messageManager;
    private final ElectionManager electionManager;
    private final PartyManager partyManager;
    private final SimpleDateFormat dateFormat;

    public ElectionCommandsHandler(TownyPolitical plugin) {
        this.plugin = plugin;
        this.messageManager = plugin.getMessageManager();
        this.electionManager = plugin.getElectionManager();
        this.partyManager = plugin.getPartyManager();
        this.dateFormat = new SimpleDateFormat(plugin.getConfig().getString("elections.date_format", "yyyy-MM-dd HH:mm:ss z"));
        this.dateFormat.setTimeZone(TimeZone.getTimeZone(plugin.getConfig().getString("elections.time_zone", TimeZone.getDefault().getID())));
    }

    public boolean handle(CommandSender sender, String commandLabel, String[] args) {
        if (args.length == 0) {
            sendElectionHelp(sender, commandLabel);
            return true;
        }
        String subCommand = args[0].toLowerCase();
        String[] subArgs = Arrays.copyOfRange(args, 1, args.length);

        switch (subCommand) {
            case "info":
                return handleInfoCommand(sender, commandLabel, subArgs);
            case "candidates":
            case "listcandidates":
            case "lc":
                return handleCandidatesCommand(sender, commandLabel, subArgs);
            case "register":
            case "run":
                return handleRegisterCommand(sender, commandLabel, subArgs);
            case "vote":
                return handleVoteCommand(sender, commandLabel, subArgs);
            case "results":
                return handleResultsCommand(sender, commandLabel, subArgs);
            case "start": // Admin command
                return handleAdminStartCommand(sender, commandLabel, subArgs);
            case "stop":  // Admin command (cancel or force end)
            case "cancel":
                return handleAdminStopCommand(sender, commandLabel, subArgs);
            case "help":
            case "?":
                sendElectionHelp(sender, commandLabel);
                return true;
            default:
                messageManager.sendMessage(sender, "command-election-unknown", "subcommand", subCommand);
                sendElectionHelp(sender, commandLabel);
                return true;
        }
    }

    /**
     * 辅助方法，用于从参数中解析选举上下文（国家或政党）和可选的选举类型。
     * @param sender 命令发送者
     * @param args 从子命令之后开始的参数数组
     * @param defaultType 如果未在参数中指定类型，则使用的默认选举类型（可为null）
     * @return 一个包含 Election 和解析出的上下文名称的 Pair，或 null（如果解析失败）
     */
    private ParsedElectionContext parseElectionContext(CommandSender sender, String[] args, @Nullable ElectionType defaultType) {
        UUID contextId = null;
        String contextName = "N/A";
        ElectionType determinedType = defaultType;
        int nextArgIndex = 0;

        // 尝试解析第一个参数是否为选举类型
        if (args.length > nextArgIndex) {
            Optional<ElectionType> typeOpt = ElectionType.fromString(args[nextArgIndex].toUpperCase());
            if (typeOpt.isPresent()) {
                determinedType = typeOpt.get();
                nextArgIndex++;
            }
        }

        // 尝试解析上下文名称 (国家或政党)
        if (args.length > nextArgIndex) {
            // 假设上下文名称是类型之后的单个词或所有剩余词
            String nameArg = String.join(" ", Arrays.copyOfRange(args, nextArgIndex, args.length));
            Nation nation = TownyAPI.getInstance().getNation(nameArg);
            if (nation != null) {
                contextId = nation.getUUID();
                contextName = nation.getName();
                if (determinedType == null) determinedType = ElectionType.PRESIDENTIAL; // 默认查国家的总统选举
            } else {
                Party party = partyManager.getParty(nameArg);
                if (party != null) {
                    contextId = party.getPartyId();
                    contextName = party.getName();
                    if (determinedType == null || determinedType != ElectionType.PARTY_LEADER) { // 如果指定类型不是PARTY_LEADER，则无效
                        messageManager.sendMessage(sender, "election-context-party-requires-leader-type", "party_name", party.getName());
                        return null;
                    }
                    determinedType = ElectionType.PARTY_LEADER; // 强制
                } else {
                    messageManager.sendMessage(sender, "error-context-not-found", "context", nameArg);
                    return null;
                }
            }
        } else if (sender instanceof Player) { // 没有提供上下文名称，尝试使用玩家的上下文
            Player player = (Player) sender;
            Nation playerNation = TownyAPI.getInstance().getResidentNationOrNull((Resident) player);
            if (playerNation != null) {
                contextId = playerNation.getUUID();
                contextName = playerNation.getName();
                if (determinedType == null) determinedType = ElectionType.PRESIDENTIAL;
            } else {
                Party playerParty = partyManager.getPartyByMember(player.getUniqueId());
                if (playerParty != null) {
                    contextId = playerParty.getPartyId();
                    contextName = playerParty.getName();
                    if (determinedType == null || determinedType != ElectionType.PARTY_LEADER) {
                        messageManager.sendMessage(sender, "election-context-party-requires-leader-type-self");
                        return null;
                    }
                    determinedType = ElectionType.PARTY_LEADER;
                } else {
                    messageManager.sendMessage(player, "election-info-fail-no-context");
                    return null;
                }
            }
        } else { // 控制台且未指定上下文
            messageManager.sendMessage(sender, "error-election-context-required-console");
            return null;
        }

        if (contextId == null || determinedType == null) { // 应该不会到这里，但作为保险
            messageManager.sendMessage(sender, "error-election-context-resolution-failed");
            return null;
        }

        // 获取活跃选举或最新完成的选举（用于results命令）
        Election election = electionManager.getActiveElection(contextId, determinedType);
        if (election == null && (args.length == 0 || !args[0].equalsIgnoreCase("results"))) { // results 可以查已结束的
            // 对于非results命令，如果找不到活跃的，则提示
            messageManager.sendMessage(sender, "election-info-none-active-for-type", "context", contextName, "type", determinedType.getDisplayName());
            return null;
        }

        return new ParsedElectionContext(election, contextId, contextName, determinedType);
    }

    // 内部类用于返回解析结果
    private static class ParsedElectionContext {
        final Election election; // 可能为null（例如查results时，只返回contextId和type）
        final UUID contextId;
        final String contextName;
        final ElectionType determinedType;

        ParsedElectionContext(Election election, UUID contextId, String contextName, ElectionType determinedType) {
            this.election = election;
            this.contextId = contextId;
            this.contextName = contextName;
            this.determinedType = determinedType;
        }
    }


    private boolean handleInfoCommand(CommandSender sender, String commandLabel, String[] subArgs) {
        if (!sender.hasPermission("townypolitical.election.info")) {
            messageManager.sendMessage(sender, "error-no-permission");
            return true;
        }
        ParsedElectionContext parsed = parseElectionContext(sender, subArgs, null);
        if (parsed == null || parsed.election == null) {
            // parseElectionContext 应该已经发送了错误消息
            return true;
        }
        displayElectionInfo(sender, parsed.election);
        return true;
    }

    private void displayElectionInfo(CommandSender sender, Election election) {
        String contextName = electionManager.getContextName(election.getContextId(), election.getType());
        messageManager.sendRawMessage(sender, "election-info-header", "type", election.getType().getDisplayName(), "context", contextName);
        messageManager.sendRawMessage(sender, "election-info-id", "id", election.getElectionId().toString());
        messageManager.sendRawMessage(sender, "election-info-status", "status", election.getStatus().getDisplayName());

        if (election.getStartTime() > 0) messageManager.sendRawMessage(sender, "election-info-start-time", "time", dateFormat.format(new Date(election.getStartTime())));
        if (election.getStatus() == ElectionStatus.REGISTRATION && election.getRegistrationEndTime() > 0) {
            messageManager.sendRawMessage(sender, "election-info-registration-ends", "time", dateFormat.format(new Date(election.getRegistrationEndTime())));
        }
        // 假设投票在登记结束后开始
        if ((election.getStatus() == ElectionStatus.REGISTRATION || election.getStatus() == ElectionStatus.VOTING) && election.getRegistrationEndTime() > 0) {
            messageManager.sendRawMessage(sender, "election-info-voting-starts", "time", dateFormat.format(new Date(election.getRegistrationEndTime())));
        }
        if ((election.getStatus() == ElectionStatus.VOTING || election.getStatus() == ElectionStatus.AWAITING_TIE_RESOLUTION) && election.getEndTime() > 0) {
            messageManager.sendRawMessage(sender, "election-info-voting-ends", "time", dateFormat.format(new Date(election.getEndTime())));
        }

        messageManager.sendRawMessage(sender, "election-info-candidates-count", "count", String.valueOf(election.getCandidates().size()));
        if (election.getStatus() != ElectionStatus.NONE && election.getStatus() != ElectionStatus.PENDING_START && election.getStatus() != ElectionStatus.CANCELLED) {
            messageManager.sendRawMessage(sender, "election-info-votes-cast", "count", String.valueOf(election.getVoters().size()));
        }

        if (election.getStatus() == ElectionStatus.FINISHED) {
            if (election.getType() == ElectionType.PRESIDENTIAL || election.getType() == ElectionType.PARTY_LEADER) {
                election.getWinnerPlayerUUID().ifPresentOrElse(
                        uuid -> {
                            OfflinePlayer winner = Bukkit.getOfflinePlayer(uuid);
                            messageManager.sendRawMessage(sender, "election-info-winner-player", "player_name", winner.getName() != null ? winner.getName() : "N/A");
                        },
                        () -> messageManager.sendRawMessage(sender, "election-info-no-winner")
                );
            } else if (election.getType() == ElectionType.PARLIAMENTARY) {
                election.getWinnerPartyUUID().ifPresentOrElse(
                        uuid -> {
                            Party winningParty = partyManager.getParty(uuid);
                            messageManager.sendRawMessage(sender, "election-info-winner-party", "party_name", winningParty != null ? winningParty.getName() : "N/A");
                        },
                        () -> messageManager.sendRawMessage(sender, "election-info-no-winner-party")
                );
            }
        } else if (election.getStatus() == ElectionStatus.AWAITING_TIE_RESOLUTION) {
            messageManager.sendRawMessage(sender, "election-info-status-tie-resolution");
        }
        String commandLabel = null;
        messageManager.sendRawMessage(sender, "election-info-footer", "label", commandLabel.split(" ")[0] + " election");
    }

    private boolean handleCandidatesCommand(CommandSender sender, String commandLabel, String[] subArgs) {
        if (!sender.hasPermission("townypolitical.election.info")) { /* ... */ return true;}
        ParsedElectionContext parsed = parseElectionContext(sender, subArgs, null);
        if (parsed == null || parsed.election == null) return true;

        Election election = parsed.election;
        Collection<Candidate> candidates = election.getCandidates();

        if (candidates.isEmpty()) {
            messageManager.sendMessage(sender, "election-candidates-none", "context", parsed.contextName, "type", election.getType().getDisplayName());
            return true;
        }

        messageManager.sendRawMessage(sender, "election-candidates-header", "type", election.getType().getDisplayName(), "context", parsed.contextName);
        for (Candidate candidate : candidates) {
            String partyNameStr = "";
            if (candidate.getPartyUUID() != null) {
                Party party = partyManager.getParty(candidate.getPartyUUID());
                if (party != null) partyNameStr = " (" + (candidate.getPartyNameCache() != null ? candidate.getPartyNameCache() : party.getName()) + ")";
                else if (candidate.getPartyNameCache() != null) partyNameStr = " (" + candidate.getPartyNameCache() + ")";
            }
            String votesStr = (election.getStatus() == ElectionStatus.VOTING || election.getStatus() == ElectionStatus.FINISHED || election.getStatus() == ElectionStatus.AWAITING_TIE_RESOLUTION) ?
                    " - " + messageManager.getRawMessage("election-candidates-votes", String.valueOf(candidate.getVotes())) : "";
            messageManager.sendRawMessage(sender, "election-candidates-entry", "player_name", candidate.getResolvedPlayerName(), "party_info", partyNameStr, "votes_info", votesStr);
        }
        return true;
    }

    private boolean handleRegisterCommand(CommandSender sender, String commandLabel, String[] subArgs) {
        if (!(sender instanceof Player)) { /* ... */ messageManager.sendMessage(sender, "error-player-only-command"); return true; }
        Player player = (Player) sender;
        if (!player.hasPermission("townypolitical.election.registercandidate")) { /* ... */ messageManager.sendMessage(player, "error-no-permission"); return true; }

        ParsedElectionContext parsed = parseElectionContext(player, subArgs, null); // Context can be implied by player
        if (parsed == null || parsed.election == null) {
            if(parsed == null && subArgs.length > 0) {} // parseElectionContext sent error
            else messageManager.sendMessage(player, "election-register-fail-no-election-context");
            return true;
        }
        Election election = parsed.election;

        if (election.getStatus() != ElectionStatus.REGISTRATION && election.getStatus() != ElectionStatus.PENDING_START) {
            messageManager.sendMessage(player, "election-candidate-register-fail-closed");
            return true;
        }
        return true;
    }

    private boolean handleVoteCommand(CommandSender sender, String commandLabel, String[] subArgs) {
        if (!(sender instanceof Player)) { /* ... */ messageManager.sendMessage(sender, "error-player-only-command"); return true; }
        Player voter = (Player) sender;
        if (!voter.hasPermission("townypolitical.election.vote")) { /* ... */ messageManager.sendMessage(voter, "error-no-permission"); return true; }

        // Usage: /tp e vote [type] [context] <candidate_name> OR /tp e vote <candidate_name> (if context is self)
        if (subArgs.length < 1) {
            messageManager.sendMessage(voter, "error-invalid-arguments", "usage", "/" + commandLabel + " vote [type] [context] <candidate>");
            return true;
        }

        String candidateNameArg = subArgs[subArgs.length - 1]; // Candidate name is always last
        String[] contextAndOptionalTypeArgs = Arrays.copyOfRange(subArgs, 0, subArgs.length - 1);

        ParsedElectionContext parsed = parseElectionContext(voter, contextAndOptionalTypeArgs, null);
        if (parsed == null || parsed.election == null) {
            if(parsed == null && contextAndOptionalTypeArgs.length > 0){}
            else messageManager.sendMessage(voter, "election-vote-fail-no-election-context");
            return true;
        }
        Election election = parsed.election;

        if (election.getStatus() != ElectionStatus.VOTING) {
            messageManager.sendMessage(voter, "election-vote-fail-closed");
            return true;
        }

        Optional<Candidate> targetCandidateOpt = election.getCandidates().stream()
                .filter(c -> c.getResolvedPlayerName().equalsIgnoreCase(candidateNameArg))
                .findFirst();

        if (!targetCandidateOpt.isPresent()) {
            messageManager.sendMessage(voter, "election-vote-fail-candidate-not-found", "candidate_name", candidateNameArg);
            return true;
        }
        return true;
    }

    private boolean handleResultsCommand(CommandSender sender, String commandLabel, String[] subArgs) {
        if (!sender.hasPermission("townypolitical.election.info")) { /* ... */ messageManager.sendMessage(sender, "error-no-permission"); return true;}

        ParsedElectionContext parsed = parseElectionContext(sender, subArgs, null);
        // For results, we might need to fetch a FINISHED election if no active one matches
        Election electionToShowResultsFor = null;
        if (parsed != null && parsed.election != null && parsed.election.getStatus() == ElectionStatus.FINISHED) {
            electionToShowResultsFor = parsed.election;
        } else if (parsed != null) { // Context was parsed, but no active/finished election found by getActiveElection
            Optional<Election> latestFinishedOpt = Optional.ofNullable(electionManager.getActiveElection(parsed.contextId, parsed.determinedType));
            if (latestFinishedOpt.isPresent()) {
                electionToShowResultsFor = latestFinishedOpt.get();
            }
        }

        if (electionToShowResultsFor == null) {
            String contextNameForMsg = (parsed != null) ? parsed.contextName : String.join(" ", subArgs);
            messageManager.sendMessage(sender, "election-results-none-found", "context", contextNameForMsg);
            return true;
        }

        // displayElectionInfo already shows winner summary for FINISHED elections
        displayElectionInfo(sender, electionToShowResultsFor);

        // For detailed parliamentary results:
        if(electionToShowResultsFor.getType() == ElectionType.PARLIAMENTARY && !electionToShowResultsFor.getPartySeatDistribution().isEmpty()){
            messageManager.sendRawMessage(sender, "election-results-parliament-seats-header");
            electionToShowResultsFor.getPartySeatDistribution().entrySet().stream()
                    .sorted(Map.Entry.<UUID, Integer>comparingByValue().reversed()) // Sort by seats desc
                    .forEach(entry -> {
                        Party party = partyManager.getParty(entry.getKey());
                        String partyName = (party != null) ? party.getName() : "未知政党 (" + entry.getKey().toString().substring(0,6) + ")";
                        messageManager.sendRawMessage(sender, "election-results-parliament-seat-entry", "party_name", partyName, "seats", String.valueOf(entry.getValue()));
                    });
        }
        return true;
    }

    private boolean handleAdminStartCommand(CommandSender sender, String commandLabel, String[] subArgs) {
        if (!sender.hasPermission("townypolitical.election.manage")) { /* ... */ messageManager.sendMessage(sender, "error-no-permission"); return true; }
        // Usage: /tp election start <nation_name_or_party_name> <type:parliament|president|leader>
        if (subArgs.length < 2) {
            messageManager.sendMessage(sender, "error-invalid-arguments", "usage", "/" + commandLabel + " start <上下文名称> <parliament|president|leader>");
            return true;
        }

        String contextName = String.join(" ", Arrays.copyOfRange(subArgs, 0, subArgs.length - 1));
        String typeStr = subArgs[subArgs.length - 1].toLowerCase();
        Optional<ElectionType> typeOpt = ElectionType.fromString(typeStr);

        if (!typeOpt.isPresent()) {
            messageManager.sendMessage(sender, "election-admin-start-invalid-type", "type", typeStr);
            return true;
        }
        ElectionType electionType = typeOpt.get();

        UUID contextId = null;
        String resolvedContextName = "";

        if (electionType == ElectionType.PARTY_LEADER) {
            Party party = partyManager.getParty(contextName);
            if (party == null) { /* ... error party not found ... */ messageManager.sendMessage(sender, "error-party-not-found", "party", contextName); return true; }
            contextId = party.getPartyId();
            resolvedContextName = party.getName();
        } else { // PARLIAMENTARY or PRESIDENTIAL
            Nation nation = TownyAPI.getInstance().getNation(contextName);
            if (nation == null) { /* ... error nation not found ... */ messageManager.sendMessage(sender, "error-nation-not-found", "nation", contextName); return true; }
            contextId = nation.getUUID();
            resolvedContextName = nation.getName();
        }

        Election startedElection = null;
        if (electionType == ElectionType.PARTY_LEADER) {
            startedElection = electionManager.startPartyLeaderElection(contextId, false); // false for manual start
        } else {
            startedElection = electionManager.startNationElection(contextId, electionType, false);
        }

        if (startedElection != null) {
            messageManager.sendMessage(sender, "election-admin-start-success", "type", electionType.getDisplayName(), "context", resolvedContextName);
        } else {
            messageManager.sendMessage(sender, "election-admin-start-fail", "type", electionType.getDisplayName(), "context", resolvedContextName);
        }
        return true;
    }

    private boolean handleAdminStopCommand(CommandSender sender, String commandLabel, String[] subArgs) {
        if (!sender.hasPermission("townypolitical.election.manage")) { /* ... */ messageManager.sendMessage(sender, "error-no-permission"); return true; }
        // Usage: /tp election stop <type_if_known> <context_name> [reason...]
        // Or    /tp election stop <election_id> [reason...]
        if (subArgs.length < 1) {
            messageManager.sendMessage(sender, "error-invalid-arguments", "usage", "/" + commandLabel + " stop <选举ID 或 类型+上下文> [原因]");
            return true;
        }

        String identifier = subArgs[0];
        String reason = (subArgs.length > 1) ? String.join(" ", Arrays.copyOfRange(subArgs, 1, subArgs.length)) : "管理员手动停止";

        // Try to parse as Election ID first
        try {
            UUID electionId = UUID.fromString(identifier);
            Election electionToStop = electionManager.findElectionById(electionId);
            if (electionToStop != null) {
                electionManager.cancelElection(electionToStop, reason + " (操作者: " + sender.getName() + ")");
                messageManager.sendMessage(sender, "election-admin-stop-by-id-success", "id", electionId.toString().substring(0,8), "type", electionToStop.getType().getDisplayName(), "context", electionManager.getContextName(electionToStop.getContextId(), electionToStop.getType()));
                return true;
            }
        } catch (IllegalArgumentException ignored) { /* Not a UUID, proceed to parse as type + context */ }

        // Parse as type + context (context can be multi-word)
        // /tp e stop parliament My Nation Alpha reason here
        // args:      [stop, parliament, My, Nation, Alpha, reason, here]
        // subArgs:   [parliament, My, Nation, Alpha, reason, here]
        if (subArgs.length < 2) { // Need at least type and context name
            messageManager.sendMessage(sender, "error-invalid-arguments", "usage", "/" + commandLabel + " stop <选举ID 或 类型 上下文名称> [原因]");
            return true;
        }
        Optional<ElectionType> typeOpt = ElectionType.fromString(subArgs[0].toUpperCase());
        if (!typeOpt.isPresent()) {
            messageManager.sendMessage(sender, "election-admin-stop-invalid-type-for-context-stop", "type", subArgs[0]);
            return true;
        }
        ElectionType electionType = typeOpt.get();

        // The rest of subArgs (after type) form the context name and optionally the reason
        // This parsing is tricky if reason has multiple words and context name also has multiple words.
        // Simplification: assume context name is one word if reason is present, or all remaining words if no reason.
        String contextName;
        if (subArgs.length > 2 && subArgs[subArgs.length-1].length() > 1) { // If there's likely a reason
            // Heuristic: if last arg is longer than 1 char, assume it's start of reason or whole reason.
            // A better way: commands like this often use flags like -r "reason here"
            // For now, assume context name is subArgs[1]
            contextName = subArgs[1];
            if(subArgs.length > 2) reason = String.join(" ", Arrays.copyOfRange(subArgs, 2, subArgs.length));
        } else { // Only type and context name
            contextName = String.join(" ", Arrays.copyOfRange(subArgs, 1, subArgs.length));
        }


        UUID contextId = null;
        String resolvedContextName = "";

        if (electionType == ElectionType.PARTY_LEADER) {
            Party party = partyManager.getParty(contextName);
            if (party == null) { /* ... error party not found ... */ messageManager.sendMessage(sender, "error-party-not-found", "party", contextName); return true; }
            contextId = party.getPartyId();
            resolvedContextName = party.getName();
        } else {
            Nation nation = TownyAPI.getInstance().getNation(contextName);
            if (nation == null) { /* ... error nation not found ... */ messageManager.sendMessage(sender, "error-nation-not-found", "nation", contextName); return true; }
            contextId = nation.getUUID();
            resolvedContextName = nation.getName();
        }

        Election electionToStop = electionManager.getActiveElection(contextId, electionType);
        if (electionToStop == null) {
            messageManager.sendMessage(sender, "election-admin-stop-specific-none-active", "type", electionType.getDisplayName(), "context", resolvedContextName);
            return true;
        }

        electionManager.cancelElection(electionToStop, reason + " (操作者: " + sender.getName() + ")");
        messageManager.sendMessage(sender, "election-admin-stop-specific-success", "type", electionType.getDisplayName(), "context", resolvedContextName);
        return true;
    }


    private void sendElectionHelp(CommandSender sender, String commandLabel) {
        String displayLabel = commandLabel.split(" ")[0];
        if (displayLabel.equalsIgnoreCase("tp") || displayLabel.equalsIgnoreCase("townypolitical") || displayLabel.equalsIgnoreCase("tpol")) {
            displayLabel += " election";
        } // else, assume it's an alias like /telection

        messageManager.sendRawMessage(sender, "help-election-header", "label", displayLabel);
        if (sender.hasPermission("townypolitical.election.info")) {
            messageManager.sendRawMessage(sender, "help-election-info", "label", displayLabel);
            messageManager.sendRawMessage(sender, "help-election-candidates", "label", displayLabel);
            messageManager.sendRawMessage(sender, "help-election-results", "label", displayLabel);
        }
        if (sender.hasPermission("townypolitical.election.registercandidate"))
            messageManager.sendRawMessage(sender, "help-election-register", "label", displayLabel);
        if (sender.hasPermission("townypolitical.election.vote"))
            messageManager.sendRawMessage(sender, "help-election-vote", "label", displayLabel);

        if (sender.hasPermission("townypolitical.election.manage")) {
            messageManager.sendRawMessage(sender, "help-election-admin-start", "label", displayLabel);
            messageManager.sendRawMessage(sender, "help-election-admin-stop", "label", displayLabel);
        }
        messageManager.sendRawMessage(sender, "help-footer");
    }
}