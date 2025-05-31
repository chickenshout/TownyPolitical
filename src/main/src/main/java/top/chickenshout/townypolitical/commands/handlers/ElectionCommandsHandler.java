// 文件名: ElectionCommandsHandler.java
// 结构位置: top/chickenshout/townypolitical/commands/handlers/ElectionCommandsHandler.java
package top.chickenshout.townypolitical.commands.handlers;

import com.palmergames.bukkit.towny.TownyAPI;
import com.palmergames.bukkit.towny.exceptions.NotRegisteredException;
import com.palmergames.bukkit.towny.exceptions.TownyException;
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

import javax.annotation.Nullable; // 如果你使用 @Nullable 注解
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.stream.Collectors;

public class ElectionCommandsHandler {
    private final TownyPolitical plugin;
    private final MessageManager messageManager;
    private final ElectionManager electionManager;
    private final PartyManager partyManager;
    private final SimpleDateFormat dateFormat;
    private String currentSubCommand = null; // 用于辅助 parseElectionContext

    public ElectionCommandsHandler(TownyPolitical plugin) {
        this.plugin = plugin;
        this.messageManager = plugin.getMessageManager();
        this.electionManager = plugin.getElectionManager();
        this.partyManager = plugin.getPartyManager();
        // 从config中读取日期格式和时区
        String configDateFormat = plugin.getConfig().getString("general.date_format", "yyyy-MM-dd HH:mm:ss z");
        String configTimeZone = plugin.getConfig().getString("general.time_zone", TimeZone.getDefault().getID());
        this.dateFormat = new SimpleDateFormat(configDateFormat);
        try {
            this.dateFormat.setTimeZone(TimeZone.getTimeZone(configTimeZone));
        } catch (Exception e) {
            plugin.getLogger().warning("Invalid time_zone in config: " + configTimeZone + ". Using server default.");
            this.dateFormat.setTimeZone(TimeZone.getDefault());
        }
    }

    public boolean handle(CommandSender sender, String commandLabel, String[] args) {
        if (args.length == 0) {
            sendElectionHelp(sender, commandLabel);
            this.currentSubCommand = null;
            return true;
        }
        String subCommand = args[0].toLowerCase();
        this.currentSubCommand = subCommand;
        String[] subArgs = Arrays.copyOfRange(args, 1, args.length);

        boolean result;
        switch (subCommand) {
            case "info":
                result = handleInfoCommand(sender, commandLabel, subArgs);
                break;
            case "candidates":
            case "listcandidates":
            case "lc":
                result = handleCandidatesCommand(sender, commandLabel, subArgs);
                break;
            case "register":
            case "run":
                result = handleRegisterCommand(sender, commandLabel, subArgs); // 将调用完整实现
                break;
            case "vote":
                result = handleVoteCommand(sender, commandLabel, subArgs); // 将调用完整实现
                break;
            case "results":
                result = handleResultsCommand(sender, commandLabel, subArgs);
                break;
            case "start":
                result = handleAdminStartCommand(sender, commandLabel, subArgs);
                break;
            case "stop":
            case "cancel":
                result = handleAdminStopCommand(sender, commandLabel, subArgs);
                break;
            case "help":
            case "?":
                sendElectionHelp(sender, commandLabel);
                result = true;
                break;
            default:
                messageManager.sendMessage(sender, "command-election-unknown", "subcommand", subCommand);
                sendElectionHelp(sender, commandLabel);
                result = true;
                break;
        }
        this.currentSubCommand = null;
        return result;
    }

    private ParsedElectionContext parseElectionContext(CommandSender sender, String[] args, @Nullable ElectionType defaultType) {
        UUID contextId = null;
        String contextName = "N/A";
        ElectionType determinedType = defaultType;
        int nextArgIndex = 0;
        boolean isResultsCmd = "results".equalsIgnoreCase(this.currentSubCommand);


        if (args.length > nextArgIndex) {
            Optional<ElectionType> typeOpt = ElectionType.fromString(args[nextArgIndex].toUpperCase());
            if (typeOpt.isPresent()) {
                determinedType = typeOpt.get();
                nextArgIndex++;
            }
        }

        if (args.length > nextArgIndex) {
            String nameArg = String.join(" ", Arrays.copyOfRange(args, nextArgIndex, args.length));
            Nation nation = TownyAPI.getInstance().getNation(nameArg);
            if (nation != null) {
                contextId = nation.getUUID();
                contextName = nation.getName();
                if (determinedType == null) determinedType = ElectionType.PRESIDENTIAL;
            } else {
                Party party = partyManager.getParty(nameArg);
                if (party != null) {
                    contextId = party.getPartyId();
                    contextName = party.getName();
                    if (determinedType == null || determinedType != ElectionType.PARTY_LEADER) {
                        messageManager.sendMessage(sender, "election-context-party-requires-leader-type", "party_name", party.getName());
                        return null;
                    }
                    determinedType = ElectionType.PARTY_LEADER;
                } else {
                    messageManager.sendMessage(sender, "error-context-not-found", "context", nameArg);
                    return null;
                }
            }
        } else if (sender instanceof Player) {
            Player player = (Player) sender;
            Resident resident = TownyAPI.getInstance().getResident(player.getUniqueId());
            if (resident != null) {
                if (determinedType == null || determinedType == ElectionType.PRESIDENTIAL || determinedType == ElectionType.PARLIAMENTARY) {
                    if (resident.hasNation()) {
                        try {
                            Nation playerNation = resident.getNation();
                            contextId = playerNation.getUUID();
                            contextName = playerNation.getName();
                            if (determinedType == null) determinedType = ElectionType.PRESIDENTIAL;
                        } catch (NotRegisteredException e) { return null; } catch (TownyException e) {
                            throw new RuntimeException(e);
                        }
                    }
                }
                if (contextId == null && (determinedType == null || determinedType == ElectionType.PARTY_LEADER)) {
                    Party playerParty = partyManager.getPartyByMember(player.getUniqueId());
                    if (playerParty != null) {
                        contextId = playerParty.getPartyId();
                        contextName = playerParty.getName();
                        determinedType = ElectionType.PARTY_LEADER;
                    } else if (determinedType == ElectionType.PARTY_LEADER) {
                        messageManager.sendMessage(player, "election-info-fail-not-in-party-for-leader-election");
                        return null;
                    }
                }
                if (contextId == null) {
                    messageManager.sendMessage(player, "election-info-fail-no-context");
                    return null;
                }
            } else {
                messageManager.sendMessage(player, "towny-resident-not-found", "player_name", player.getName());
                return null;
            }
        } else {
            messageManager.sendMessage(sender, "error-election-context-required-console");
            return null;
        }

        if (contextId == null || determinedType == null) {
            messageManager.sendMessage(sender, "error-election-context-resolution-failed");
            return null;
        }

        Election election = electionManager.getActiveElection(contextId, determinedType);
        if (election == null && isResultsCmd) {
            Optional<Election> latestFinishedOpt = electionManager.getLatestFinishedElection(contextId, determinedType);
            if (latestFinishedOpt.isPresent()) {
                election = latestFinishedOpt.get();
            }
        }

        if (election == null && !isResultsCmd) {
            messageManager.sendMessage(sender, "election-info-none-active-for-type", "context", contextName, "type", determinedType.getDisplayName());
            return null;
        }
        return new ParsedElectionContext(election, contextId, contextName, determinedType);
    }

    private static class ParsedElectionContext { /* ... (保持不变) ... */
        final Election election;
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

    private void displayElectionInfo(CommandSender sender, Election election) {
        // ... (方法主体与之前提供的一致，确保 dateFormat 正确使用) ...
        String contextName = electionManager.getContextName(election.getContextId(), election.getType());
        messageManager.sendRawMessage(sender, "election-info-header", "type", election.getType().getDisplayName(), "context", contextName);
        messageManager.sendRawMessage(sender, "election-info-id", "id", election.getElectionId().toString().substring(0,8)); // 显示部分ID
        messageManager.sendRawMessage(sender, "election-info-status", "status", election.getStatus().getDisplayName());

        if (election.getStartTime() > 0) messageManager.sendRawMessage(sender, "election-info-start-time", "time", dateFormat.format(new Date(election.getStartTime())));
        if (election.getStatus() == ElectionStatus.REGISTRATION && election.getRegistrationEndTime() > 0) {
            messageManager.sendRawMessage(sender, "election-info-registration-ends", "time", dateFormat.format(new Date(election.getRegistrationEndTime())));
        }
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
            // ... (显示获胜者逻辑) ...
            if (election.getType() == ElectionType.PRESIDENTIAL || election.getType() == ElectionType.PARTY_LEADER) {
                election.getWinnerPlayerUUID().ifPresentOrElse(
                        uuid -> {
                            OfflinePlayer winner = Bukkit.getOfflinePlayer(uuid);
                            messageManager.sendRawMessage(sender, "election-info-winner-player", "player_name", winner.getName() != null ? winner.getName() : "ID:"+uuid.toString().substring(0,6));
                        },
                        () -> messageManager.sendRawMessage(sender, "election-info-no-winner")
                );
            } else if (election.getType() == ElectionType.PARLIAMENTARY) {
                election.getWinnerPartyUUID().ifPresentOrElse(
                        uuid -> {
                            Party winningParty = partyManager.getParty(uuid);
                            messageManager.sendRawMessage(sender, "election-info-winner-party", "party_name", winningParty != null ? winningParty.getName() : "ID:"+uuid.toString().substring(0,6));
                        },
                        () -> messageManager.sendRawMessage(sender, "election-info-no-winner-party")
                );
            }
        } else if (election.getStatus() == ElectionStatus.AWAITING_TIE_RESOLUTION) {
            messageManager.sendRawMessage(sender, "election-info-status-tie-resolution");
        }
        messageManager.sendRawMessage(sender, "election-info-footer", "main_command_prefix", plugin.getCommand("townypolitical").getLabel());
    }

    private boolean handleInfoCommand(CommandSender sender, String commandLabel, String[] subArgs) {
        if (!sender.hasPermission("townypolitical.election.info")) {
            messageManager.sendMessage(sender, "error-no-permission");
            return true;
        }
        ParsedElectionContext parsed = parseElectionContext(sender, subArgs, null);
        if (parsed == null || parsed.election == null) {
            // parseElectionContext or subsequent getLatestFinishedElection (if results cmd) handles messages
            return true;
        }
        displayElectionInfo(sender, parsed.election);
        return true;
    }


    private boolean handleCandidatesCommand(CommandSender sender, String commandLabel, String[] subArgs) {
        if (!sender.hasPermission("townypolitical.election.info")) {
            messageManager.sendMessage(sender, "error-no-permission");
            return true;
        }
        ParsedElectionContext parsed = parseElectionContext(sender, subArgs, null);
        if (parsed == null || parsed.election == null) {
            return true;
        }

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
                else partyNameStr = " (未知政党)";
            }

            if (election.getStatus() == ElectionStatus.VOTING || election.getStatus() == ElectionStatus.FINISHED || election.getStatus() == ElectionStatus.AWAITING_TIE_RESOLUTION) {
                messageManager.sendRawMessage(sender, "election-candidates-entry-with-votes",
                        "player_name", candidate.getResolvedPlayerName(),
                        "party_info", partyNameStr,
                        "votes_count", String.valueOf(candidate.getVotes()));
            } else {
                messageManager.sendRawMessage(sender, "election-candidates-entry-no-votes",
                        "player_name", candidate.getResolvedPlayerName(),
                        "party_info", partyNameStr);
            }
        }
        return true;
    }

    // --- 修正 handleRegisterCommand ---
    private boolean handleRegisterCommand(CommandSender sender, String commandLabel, String[] subArgs) {
        if (!(sender instanceof Player)) {
            messageManager.sendMessage(sender, "error-player-only-command");
            return true;
        }
        Player player = (Player) sender;
        if (!player.hasPermission("townypolitical.election.registercandidate")) {
            messageManager.sendMessage(player, "error-no-permission");
            return true;
        }

        ParsedElectionContext parsed = parseElectionContext(player, subArgs, null);
        if (parsed == null || parsed.election == null) {
            if (parsed == null && subArgs.length > 0) { /* Message already sent by parseElectionContext */ }
            else { messageManager.sendMessage(player, "election-register-fail-no-election-context"); }
            return true;
        }
        Election election = parsed.election;

        // 允许在 PENDING_START 或 REGISTRATION 状态报名
        if (election.getStatus() != ElectionStatus.REGISTRATION && election.getStatus() != ElectionStatus.PENDING_START) {
            messageManager.sendMessage(player, "election-candidate-register-fail-closed");
            return true;
        }

        // 调用 ElectionManager 中的核心逻辑
        electionManager.registerCandidate(player, election);
        return true;
    }

    // --- 修正 handleVoteCommand ---
    private boolean handleVoteCommand(CommandSender sender, String commandLabel, String[] subArgs) {
        if (!(sender instanceof Player)) {
            messageManager.sendMessage(sender, "error-player-only-command");
            return true;
        }
        Player voter = (Player) sender;
        if (!voter.hasPermission("townypolitical.election.vote")) {
            messageManager.sendMessage(voter, "error-no-permission");
            return true;
        }

        if (subArgs.length < 1) {
            messageManager.sendMessage(voter, "error-invalid-arguments", "usage", "/" + commandLabel + " vote [类型] [上下文] <候选人名称>");
            return true;
        }

        String candidateNameArg = subArgs[subArgs.length - 1];
        String[] contextAndOptionalTypeArgs = Arrays.copyOfRange(subArgs, 0, subArgs.length - 1);

        ParsedElectionContext parsed = parseElectionContext(voter, contextAndOptionalTypeArgs, null);
        if (parsed == null || parsed.election == null) {
            if (parsed == null && contextAndOptionalTypeArgs.length > 0) { /* Message already sent */ }
            else { messageManager.sendMessage(voter, "election-vote-fail-no-election-context"); }
            return true;
        }
        Election election = parsed.election;

        if (election.getStatus() != ElectionStatus.VOTING) {
            messageManager.sendMessage(voter, "election-vote-fail-closed");
            return true;
        }

        // 查找候选人时忽略大小写
        Optional<Candidate> targetCandidateOpt = election.getCandidates().stream()
                .filter(c -> c.getResolvedPlayerName().equalsIgnoreCase(candidateNameArg))
                .findFirst();

        if (targetCandidateOpt.isEmpty()) {
            messageManager.sendMessage(voter, "election-vote-fail-candidate-not-found", "candidate_name", candidateNameArg);
            return true;
        }

        // 调用 ElectionManager 中的核心逻辑
        electionManager.castVote(voter, election, targetCandidateOpt.get());
        return true;
    }

    // ... (handleResultsCommand, handleAdminStartCommand, handleAdminStopCommand, sendElectionHelp 保持与之前提供的Part 4/N一致) ...
    // 确保它们也使用了 parseElectionContext (如果适用) 或者正确的 Election 对象获取方式
    private boolean handleResultsCommand(CommandSender sender, String commandLabel, String[] subArgs) { /* ... 之前的完整实现 ... */
        if (!sender.hasPermission("townypolitical.election.info")) {
            messageManager.sendMessage(sender, "error-no-permission");
            return true;
        }

        ParsedElectionContext parsed = parseElectionContext(sender, subArgs, null);

        Election electionToShowResultsFor = null;
        if (parsed != null && parsed.election != null && parsed.election.getStatus() == ElectionStatus.FINISHED) {
            electionToShowResultsFor = parsed.election;
        } else if (parsed != null) {
            Optional<Election> latestFinishedOpt = electionManager.getLatestFinishedElection(parsed.contextId, parsed.determinedType);
            if (latestFinishedOpt.isPresent()) {
                electionToShowResultsFor = latestFinishedOpt.get();
            }
        }

        if (electionToShowResultsFor == null) {
            String contextNameForMsg = (parsed != null && parsed.contextName != null && !parsed.contextName.equals("N/A")) ?
                    parsed.contextName : String.join(" ", subArgs);
            if (contextNameForMsg.isEmpty() && sender instanceof Player) {
                Player p = (Player) sender;
                Resident res = TownyAPI.getInstance().getResident(p.getUniqueId());
                if (res != null && res.hasNation()) {
                    try { contextNameForMsg = res.getNation().getName(); } catch (NotRegisteredException ignored) {} catch (
                            TownyException e) {
                        throw new RuntimeException(e);
                    }
                } else {
                    Party party = partyManager.getPartyByMember(p.getUniqueId());
                    if (party != null) contextNameForMsg = party.getName();
                }
            }
            if (contextNameForMsg.isEmpty() || contextNameForMsg.equals("N/A")) contextNameForMsg = "指定上下文";

            messageManager.sendMessage(sender, "election-results-none-found", "context", contextNameForMsg);
            return true;
        }


        if (electionToShowResultsFor.getStatus() != ElectionStatus.FINISHED) {
            messageManager.sendMessage(sender, "election-results-not-finished", "status", electionToShowResultsFor.getStatus().getDisplayName());
            displayElectionInfo(sender, electionToShowResultsFor);
            return true;
        }

        displayElectionInfo(sender, electionToShowResultsFor);

        if(electionToShowResultsFor.getType() == ElectionType.PARLIAMENTARY && !electionToShowResultsFor.getPartySeatDistribution().isEmpty()){
            messageManager.sendRawMessage(sender, "election-results-parliament-seats-header");
            electionToShowResultsFor.getPartySeatDistribution().entrySet().stream()
                    .sorted(Map.Entry.<UUID, Integer>comparingByValue().reversed())
                    .forEach(entry -> {
                        Party party = partyManager.getParty(entry.getKey());
                        String partyName = (party != null) ? party.getName() : "未知政党 (" + entry.getKey().toString().substring(0,6) + ")";
                        messageManager.sendRawMessage(sender, "election-results-parliament-seat-entry", "party_name", partyName, "seats", String.valueOf(entry.getValue()));
                    });
        }
        return true;
    }

    private boolean handleAdminStartCommand(CommandSender sender, String commandLabel, String[] subArgs) { /* ... 之前的完整实现 ... */
        if (!sender.hasPermission("townypolitical.election.manage")) {
            messageManager.sendMessage(sender, "error-no-permission");
            return true;
        }
        if (subArgs.length < 2) {
            messageManager.sendMessage(sender, "error-invalid-arguments", "usage", "/" + commandLabel + " start <上下文名称> <PARLIAMENTARY|PRESIDENTIAL|PARTY_LEADER>");
            return true;
        }

        String contextName = String.join(" ", Arrays.copyOfRange(subArgs, 0, subArgs.length - 1));
        String typeStr = subArgs[subArgs.length - 1].toLowerCase();
        Optional<ElectionType> typeOpt = ElectionType.fromString(typeStr);

        if (!typeOpt.isPresent()) {
            messageManager.sendMessage(sender, "election-admin-start-invalid-type", "type", typeStr);
            String availableTypes = Arrays.stream(ElectionType.values()).map(et -> et.name().toLowerCase()).collect(Collectors.joining(", "));
            return true;
        }
        ElectionType electionType = typeOpt.get();

        UUID contextId = null;
        String resolvedContextName = "";

        if (electionType == ElectionType.PARTY_LEADER) {
            Party party = partyManager.getParty(contextName);
            if (party == null) { messageManager.sendMessage(sender, "error-party-not-found", "party", contextName); return true; }
            contextId = party.getPartyId();
            resolvedContextName = party.getName();
        } else {
            Nation nation = TownyAPI.getInstance().getNation(contextName);
            if (nation == null) { messageManager.sendMessage(sender, "error-nation-not-found", "nation", contextName); return true; }
            contextId = nation.getUUID();
            resolvedContextName = nation.getName();
        }

        Election startedElection = null;
        if (electionType == ElectionType.PARTY_LEADER) {
            startedElection = electionManager.startPartyLeaderElection(contextId, false);
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

    private boolean handleAdminStopCommand(CommandSender sender, String commandLabel, String[] subArgs) { /* ... 之前的完整实现 ... */
        if (!sender.hasPermission("townypolitical.election.manage")) {
            messageManager.sendMessage(sender, "error-no-permission");
            return true;
        }
        if (subArgs.length < 1) {
            messageManager.sendMessage(sender, "error-invalid-arguments", "usage", "/" + commandLabel + " stop <选举ID 或 类型 上下文名称> [原因]");
            return true;
        }

        String identifier = subArgs[0];
        String reason = (subArgs.length > 1) ? String.join(" ", Arrays.copyOfRange(subArgs, 1, subArgs.length)) : "管理员手动停止";

        try {
            UUID electionId = UUID.fromString(identifier);
            Election electionToStop = electionManager.findElectionById(electionId);
            if (electionToStop != null) {
                electionManager.cancelElection(electionToStop, reason + " (操作者: " + sender.getName() + ")");
                messageManager.sendMessage(sender, "election-admin-stop-by-id-success", "id", electionId.toString().substring(0,8), "type", electionToStop.getType().getDisplayName(), "context", electionManager.getContextName(electionToStop.getContextId(), electionToStop.getType()));
                return true;
            } // 如果ID找不到，继续尝试 类型+上下文
        } catch (IllegalArgumentException ignored) { /* Not a UUID */ }

        if (subArgs.length < 2) {
            messageManager.sendMessage(sender, "error-invalid-arguments", "usage", "/" + commandLabel + " stop <选举ID 或 类型 上下文名称> [原因]");
            return true;
        }
        Optional<ElectionType> typeOpt = ElectionType.fromString(subArgs[0].toUpperCase()); // 类型是第一个参数
        if (!typeOpt.isPresent()) {
            messageManager.sendMessage(sender, "election-admin-stop-invalid-type-for-context-stop", "type", subArgs[0]);
            return true;
        }
        ElectionType electionType = typeOpt.get();

        // 上下文名称是类型之后的参数，直到原因参数（如果存在）
        String contextName;
        int reasonStartIndex = -1;
        // 尝试找到原因的开始。如果参数中除了类型和至少一个上下文名称词外还有更多，那些可能是原因
        // /tp e stop type context part1 context part2 reason part1 reason part2
        // subArgs = [type, context part1, context part2, reason part1, reason part2]
        //           0      1              2              3              4
        // 这里的解析逻辑比较复杂，如果上下文名称本身可能包含 "reason"
        // 为了简化，假设上下文名称在类型之后，是单个词或到参数末尾（如果没有更多参数作为原因）
        // 如果参数数量 > 2 (type, context, ...)，则 ... 可能是多词的context或reason
        // 之前的解析:
        // String contextName;
        // if (subArgs.length > 2 && subArgs[subArgs.length-1].length() > 1) {
        // contextName = subArgs[1]; // 假设上下文名是单个词
        // if(subArgs.length > 2) reason = String.join(" ", Arrays.copyOfRange(subArgs, 2, subArgs.length));
        // } else {
        // contextName = String.join(" ", Arrays.copyOfRange(subArgs, 1, subArgs.length));
        // }
        // 这个解析对于多词上下文名称和多词原因会混淆。
        // 更稳妥的方式： /tp election stop <type> "<context name>" [reason...]
        // 或者 /tp election stop <election_id> [reason...] (已支持)
        //
        // 简化：假设上下文名称是 subArgs[1]
        if (subArgs.length < 2) { /* 已在前面检查 */ return true;}
        contextName = subArgs[1];
        if (subArgs.length > 2) {
            reason = String.join(" ", Arrays.copyOfRange(subArgs, 2, subArgs.length));
        }


        UUID contextId = null;
        String resolvedContextName = "";

        if (electionType == ElectionType.PARTY_LEADER) {
            Party party = partyManager.getParty(contextName);
            if (party == null) { messageManager.sendMessage(sender, "error-party-not-found", "party", contextName); return true; }
            contextId = party.getPartyId();
            resolvedContextName = party.getName();
        } else {
            Nation nation = TownyAPI.getInstance().getNation(contextName);
            if (nation == null) { messageManager.sendMessage(sender, "error-nation-not-found", "nation", contextName); return true; }
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


    private void sendElectionHelp(CommandSender sender, String commandLabel) { /* ... 之前的完整实现 ... */
        String displayLabel = commandLabel.split(" ")[0];
        if (displayLabel.equalsIgnoreCase("tp") || displayLabel.equalsIgnoreCase("townypolitical") || displayLabel.equalsIgnoreCase("tpol")) {
            displayLabel += " election";
        }

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