// 文件名: PoliticalTabCompleter.java
// 结构位置: top/chickenshout/townypolitical/commands/PoliticalTabCompleter.java
package top.chickenshout.townypolitical.commands;

import com.palmergames.bukkit.towny.TownyAPI;
import com.palmergames.bukkit.towny.exceptions.NotRegisteredException;
import com.palmergames.bukkit.towny.exceptions.TownyException;
import com.palmergames.bukkit.towny.object.Nation;
import com.palmergames.bukkit.towny.object.Resident; // For player context
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.util.StringUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import top.chickenshout.townypolitical.commands.handlers.ElectionCommandsHandler;
import top.chickenshout.townypolitical.data.Bill;
import top.chickenshout.townypolitical.data.NationPolitics;
import top.chickenshout.townypolitical.enums.*;
import top.chickenshout.townypolitical.TownyPolitical;
import top.chickenshout.townypolitical.data.Party;
import top.chickenshout.townypolitical.elections.Candidate;
import top.chickenshout.townypolitical.elections.Election;
import top.chickenshout.townypolitical.enums.BillStatus;
import top.chickenshout.townypolitical.enums.ElectionStatus;
import top.chickenshout.townypolitical.enums.ElectionType;
import top.chickenshout.townypolitical.enums.GovernmentType;
import top.chickenshout.townypolitical.managers.ElectionManager;
import top.chickenshout.townypolitical.managers.NationManager;
import top.chickenshout.townypolitical.managers.PartyManager;
import top.chickenshout.townypolitical.managers.BillManager;

import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class PoliticalTabCompleter implements TabCompleter {

    private final TownyPolitical plugin;
    private final PartyManager partyManager;
    private final NationManager nationManager;
    private final ElectionManager electionManager;
    private final BillManager billManager;
    private final ElectionCommandsHandler electionCmdHandler;

    // 主命令组 (根据权限显示)
    private static final List<String> MAIN_COMMAND_GROUPS_USER = Arrays.asList("party", "nation", "election", "bill", "help", "info"); // <--- 添加 "bill"
    private static final List<String> MAIN_COMMAND_GROUPS_ADMIN = Arrays.asList("party", "nation", "election", "bill", "help", "info", "reload"); // <--- 添加 "bill"

    // 各主命令组下的子命令
    private static final List<String> PARTY_SUBCOMMANDS = Arrays.asList(
            "create", "disband", "info", "list", "apply", "leave",
            "invite", "kick", "accept", "reject", "promote", "demote",
            "rename", "setleader", "setmps", "addmp", "removemp", "listmps"
    );
    private static final List<String> NATION_SUBCOMMANDS = Arrays.asList(
            "setgov", "info", "listgov", "setmonarch", "appointpremier", "parliament" // 'parliament' for parliamentinfo
    );
    private static final List<String> ELECTION_SUBCOMMANDS = Arrays.asList(
            "info", "candidates", "register", "vote", "results", // User commands
            "start", "stop" // Admin commands
    );
    private static final List<String> BILL_SUBCOMMANDS = Arrays.asList( // <--- 新增
            "propose", "list", "info", "vote" // "cancel", "enact" for future
    ); // <--- 新增


    public PoliticalTabCompleter(TownyPolitical plugin, PoliticalCommands politicalCommands) {
        this.plugin = plugin;
        this.partyManager = plugin.getPartyManager();
        this.nationManager = plugin.getNationManager();
        this.electionManager = plugin.getElectionManager();
        this.billManager = plugin.getBillManager();
        this.electionCmdHandler = politicalCommands.getElectionCommandsHandler();
    }

    @Nullable
    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String alias, @NotNull String[] args) {
        final List<String> completions = new ArrayList<>();
        final String currentArg = args[args.length - 1].toLowerCase();

        // 主命令入口: /tp <group> 或 /tparty <sub_party_command>
        if (args.length == 1) {
            if (command.getName().equalsIgnoreCase("townypolitical") || command.getName().equalsIgnoreCase("tp") || command.getName().equalsIgnoreCase("tpol")) {
                List<String> groups = sender.hasPermission("townypolitical.command.reload") ? MAIN_COMMAND_GROUPS_ADMIN : MAIN_COMMAND_GROUPS_USER;
                StringUtil.copyPartialMatches(currentArg, groups, completions);
            } else if (command.getName().equalsIgnoreCase("tparty") || command.getName().equalsIgnoreCase("party")) {
                // /tparty 的第一个参数直接是 party 的子命令
                StringUtil.copyPartialMatches(currentArg, PARTY_SUBCOMMANDS, completions);
            }
            // 可以为 /tnation, /telection 添加类似逻辑
        }
        // 子命令处理: /tp <group> <subcommand_of_group> [params...]
        // 或 /tparty <subcommand_of_party> [params...]
        else if (args.length >= 2) {
            String groupOrSubcommand = args[0].toLowerCase(); //可能是group，也可能是tparty的subcommand

            if (command.getName().equalsIgnoreCase("townypolitical") || command.getName().equalsIgnoreCase("tp") || command.getName().equalsIgnoreCase("tpol")) {
                // 这是 /tp <group> ... 的情况
                switch (groupOrSubcommand) {
                    case "party":
                    case "p":
                        completePartyCommands(sender, args, currentArg, completions, 1);
                        break;
                    case "nation":
                    case "n":
                        completeNationCommands(sender, args, currentArg, completions, 1);
                        break;
                    case "election":
                    case "e":
                        completeElectionCommands(sender, args, currentArg, completions, 1);
                        break;
                    case "bill":
                    case "b": // <--- 新增
                        completeBillCommands(sender, args, currentArg, completions, 1); // <--- 新增
                        break; // <--- 新增
                }
            } else if (command.getName().equalsIgnoreCase("tparty") || command.getName().equalsIgnoreCase("party")) {
                // 这是 /tparty <subcommand> ... 的情况
                // args[0] 是 subcommand, args[1] 是第一个参数
                completePartyCommands(sender, args, currentArg, completions, 0);
            }
        }

        Collections.sort(completions);
        return completions;
    }

    private void completePartyCommands(CommandSender sender, String[] args, String currentArg, List<String> completions, int subCommandIndex) {
        if (args.length == subCommandIndex + 1) { // 正在输入子命令本身
            StringUtil.copyPartialMatches(currentArg, PARTY_SUBCOMMANDS, completions);
        } else if (args.length > subCommandIndex + 1) { // 正在输入子命令的参数
            String partySubCommand = args[subCommandIndex].toLowerCase();
            int actualArgIndex = subCommandIndex + 1; // 子命令的第一个实际参数在args中的索引

            switch (partySubCommand) {
                case "info": // /tp party info [party_name]
                case "apply": // /tp party apply <party_name>
                    if (args.length == actualArgIndex + 1) {
                        StringUtil.copyPartialMatches(currentArg,
                                partyManager.getAllParties().stream().map(Party::getName).map(s -> s.replace(" ", "_")).collect(Collectors.toList()),
                                completions);
                    }
                    break;
                case "invite":
                case "kick":
                case "promote":
                case "demote":
                case "setleader":
                case "accept":
                case "reject": // /tp party invite <player_name>
                    if (args.length == actualArgIndex + 1) {
                        StringUtil.copyPartialMatches(currentArg,
                                Bukkit.getOnlinePlayers().stream().map(Player::getName).collect(Collectors.toList()),
                                completions);
                    }
                    break;
                case "create": // /tp party create <name...> (名称可以是多词)
                case "rename": // /tp party rename <new_name...>
                    // 不提供补全，因为名称可以是任意的
                    break;
                case "disband":
                    if (args.length == actualArgIndex + 1) {
                        StringUtil.copyPartialMatches(currentArg, Collections.singletonList("confirm"), completions);
                    }
                    break;
                case "setmps": // /tparty setmps <国家名> [玩家名...]
                case "addmp":  // /tparty addmp <国家名> <玩家名>
                case "removemp":// /tparty removemp <国家名> <玩家名>
                    if (args.length == actualArgIndex + 1) { // 正在输入国家名
                        if (TownyAPI.getInstance() != null) {
                            TownyAPI.getInstance().getNations().stream()
                                    .map(n -> n.getName().replace(" ", "_"))
                                    .filter(name -> name.toLowerCase().startsWith(currentArg))
                                    .forEach(completions::add);
                        }
                    } else if (args.length == actualArgIndex + 2 && (partySubCommand.equals("addmp") || partySubCommand.equals("removemp"))) { // addmp/removemp 的第二个参数是玩家名
                        // 补全在线玩家和本党成员
                        List<String> potentialPlayers = new ArrayList<>();
                        Bukkit.getOnlinePlayers().stream().map(Player::getName).forEach(potentialPlayers::add);
                        Party senderParty = null;
                        if (sender instanceof Player) {
                            senderParty = partyManager.getPartyByMember(((Player) sender).getUniqueId());
                        }
                        if (senderParty != null) { // 如果是 removemp，应该补全当前已任命的议员
                            if (partySubCommand.equals("removemp") && args.length > actualArgIndex) {
                                Nation nation = TownyAPI.getInstance().getNation(args[actualArgIndex]); // args[actualArgIndex] 是国家名
                                if (nation != null) {
                                    NationPolitics politics = nationManager.getNationPolitics(nation);
                                    if (politics != null) {
                                        politics.getParliamentaryMembersForParty(senderParty.getPartyId()).stream()
                                                .map(Bukkit::getOfflinePlayer)
                                                .map(OfflinePlayer::getName)
                                                .filter(Objects::nonNull)
                                                .forEach(potentialPlayers::add);
                                    }
                                }
                            } else { // addmp 或 setmps 的后续玩家
                                senderParty.getOfficialMemberIds().stream()
                                        .map(Bukkit::getOfflinePlayer)
                                        .map(OfflinePlayer::getName)
                                        .filter(Objects::nonNull)
                                        .filter(name -> !potentialPlayers.contains(name))
                                        .forEach(potentialPlayers::add);
                            }
                        }
                        StringUtil.copyPartialMatches(currentArg, potentialPlayers.stream().distinct().collect(Collectors.toList()), completions);
                    } else if (args.length >= actualArgIndex + 2 && partySubCommand.equals("setmps")) { // setmps 的后续参数都是玩家名
                        // (与上面 addmp/removemp 的玩家补全逻辑类似)
                        List<String> potentialPlayers = new ArrayList<>();
                        Bukkit.getOnlinePlayers().stream().map(Player::getName).forEach(potentialPlayers::add);
                        Party senderParty = null;
                        if (sender instanceof Player) {
                            senderParty = partyManager.getPartyByMember(((Player) sender).getUniqueId());
                        }
                        if (senderParty != null) {
                            senderParty.getOfficialMemberIds().stream()
                                    .map(Bukkit::getOfflinePlayer)
                                    .map(OfflinePlayer::getName)
                                    .filter(Objects::nonNull)
                                    .filter(name -> !potentialPlayers.contains(name))
                                    .forEach(potentialPlayers::add);
                        }
                        StringUtil.copyPartialMatches(currentArg, potentialPlayers.stream().distinct().collect(Collectors.toList()), completions);
                    }
                    break;
                case "listmps": // /tparty listmps <国家名> [政党名]
                    if (args.length == actualArgIndex + 1) { // 国家名或政党名
                        if (TownyAPI.getInstance() != null) {
                            TownyAPI.getInstance().getNations().stream()
                                    .map(n -> n.getName().replace(" ", "_"))
                                    .filter(name -> name.toLowerCase().startsWith(currentArg))
                                    .forEach(completions::add);
                        }
                        partyManager.getAllParties().stream()
                                .map(p -> p.getName().replace(" ", "_"))
                                .filter(name -> name.toLowerCase().startsWith(currentArg))
                                .forEach(completions::add);
                    } else if (args.length == actualArgIndex + 2) { // 政党名 (如果第一个参数是国家)
                        partyManager.getAllParties().stream()
                                .map(p -> p.getName().replace(" ", "_"))
                                .filter(name -> name.toLowerCase().startsWith(currentArg))
                                .forEach(completions::add);
                    }
                    break;
            }
        }
    }

    private void completeNationCommands(CommandSender sender, String[] args, String currentArg, List<String> completions, int subCommandIndex) {
        if (args.length == subCommandIndex + 1) {
            StringUtil.copyPartialMatches(currentArg, NATION_SUBCOMMANDS, completions);
        } else if (args.length > subCommandIndex + 1) {
            String nationSubCommand = args[subCommandIndex].toLowerCase();
            int actualArgIndex = subCommandIndex + 1;

            switch (nationSubCommand) {
                case "info": // /tp nation info [nation_name]
                case "parliament": // /tp nation parliament [nation_name]
                    if (args.length == actualArgIndex + 1 && TownyAPI.getInstance() != null) {
                        StringUtil.copyPartialMatches(currentArg,
                                TownyAPI.getInstance().getNations().stream().map(Nation::getName).map(s -> s.replace(" ", "_")).collect(Collectors.toList()),
                                completions);
                    }
                    break;
                case "setgov": // /tp nation setgov [nation_name] <gov_type>
                    if (args.length == actualArgIndex + 1 && TownyAPI.getInstance() != null) { // Typing nation name OR gov_type if nation implied
                        // Suggest nations first
                        TownyAPI.getInstance().getNations().stream()
                                .map(Nation::getName)
                                .map(s -> s.replace(" ", "_"))
                                .filter(name -> name.toLowerCase().startsWith(currentArg))
                                .forEach(completions::add);
                        // Also suggest gov types if previous arg might be a nation
                        if (args.length > actualArgIndex && TownyAPI.getInstance() != null && TownyAPI.getInstance().getNation(args[actualArgIndex]) != null) {
                            // This means currentArg is for gov_type
                        } else { // Or if no nation arg yet, suggest gov types
                            Arrays.stream(GovernmentType.values())
                                    .flatMap(gt -> Stream.of(gt.getShortName(), gt.name()))
                                    .filter(name -> name.toLowerCase().startsWith(currentArg))
                                    .forEach(completions::add);
                        }
                    } else if (args.length == actualArgIndex + 2) { // Typing gov_type after nation name
                        StringUtil.copyPartialMatches(currentArg,
                                Arrays.stream(GovernmentType.values()).flatMap(gt -> Stream.of(gt.getShortName(), gt.name())).collect(Collectors.toList()),
                                completions);
                    }
                    break;
                case "setmonarch": // /tp nation setmonarch [nation_name] <player|remove>
                case "appointpremier": // /tp nation appointpremier [nation_name] <player|remove>
                    if (args.length == actualArgIndex + 1) { // Typing nation OR player/remove
                        if (TownyAPI.getInstance() != null) {
                            TownyAPI.getInstance().getNations().stream()
                                    .map(Nation::getName)
                                    .map(s -> s.replace(" ", "_"))
                                    .filter(name -> name.toLowerCase().startsWith(currentArg))
                                    .forEach(completions::add);
                        }
                        Bukkit.getOnlinePlayers().stream()
                                .map(Player::getName)
                                .filter(name -> name.toLowerCase().startsWith(currentArg))
                                .forEach(completions::add);
                        if ("remove".startsWith(currentArg)) completions.add("remove");

                    } else if (args.length == actualArgIndex + 2) { // Typing player/remove after nation
                        StringUtil.copyPartialMatches(currentArg,
                                Bukkit.getOnlinePlayers().stream().map(Player::getName).collect(Collectors.toList()),
                                completions);
                        if ("remove".startsWith(currentArg)) completions.add("remove");
                    }
                    break;
                case "listgov": // No further args
                    break;
            }
        }
    }

    private void completeElectionCommands(CommandSender sender, String[] args, String currentArg, List<String> completions, int subCommandIndex) {
        if (args.length == subCommandIndex + 1) {
            StringUtil.copyPartialMatches(currentArg, ELECTION_SUBCOMMANDS, completions);
        } else if (args.length > subCommandIndex + 1) {
            String electionSubCommand = args[subCommandIndex].toLowerCase();
            int actualArgIndex = subCommandIndex + 1; // First param of election subcommand

            switch (electionSubCommand) {
                case "info":        // /tp e info [type] [context]
                case "candidates":  // /tp e candidates [type] [context]
                case "results":     // /tp e results [type] [context]
                case "register":
                    //  /tp e register parliamentary NationName
                    //  /tp e register (presidential/partyleader) ContextName
                    //  /tp e register (player implies context for pres/leader, or implies party for parl)
                    if (args.length == actualArgIndex + 1) { // Typing election type OR context name
                        Stream.of(ElectionType.values())
                                .map(et -> et.name().toLowerCase())
                                .filter(name -> name.startsWith(currentArg))
                                .forEach(completions::add);
                        suggestContextNames(currentArg, completions); // Suggest nation/party as context
                    } else if (args.length == actualArgIndex + 2) { // Typing context name (if type was provided)
                        // If the first param (args[actualArgIndex]) was "parliamentary", then this (currentArg) should be a nation name.
                        if (ElectionType.PARLIAMENTARY.name().equalsIgnoreCase(args[actualArgIndex])) {
                            if (TownyAPI.getInstance() != null) {
                                TownyAPI.getInstance().getNations().stream()
                                        .map(n -> n.getName().replace(" ", "_"))
                                        .filter(name -> name.toLowerCase().startsWith(currentArg))
                                        .forEach(completions::add);
                            }
                        } else { // For presidential or party leader, this is the context name.
                            suggestContextNames(currentArg, completions);
                        }
                    }
                    // For register, there are no further arguments after type and context.
                    break;
                case "vote": // /tp e vote [类型] [上下文] <目标名>
                    // The logic for suggesting type/context for args BEFORE the target name
                    if (args.length == actualArgIndex + 1) { // Typing the first param after "vote" (could be type, context, or target)
                        Stream.of(ElectionType.values()).map(et -> et.name().toLowerCase()).filter(name -> name.startsWith(currentArg)).forEach(completions::add);
                        suggestContextNames(currentArg, completions); // Suggests nation/party names
                        suggestTargetsForElectionVote(sender, args, actualArgIndex, currentArg, completions); // Also suggest targets if this could be it
                    } else if (args.length == actualArgIndex + 2) { // Typing the second param (could be context or target)
                        // If first param was a type, this is context. If first was context, this is target.
                        Optional<ElectionType> typeFromFirstParam = ElectionType.fromString(args[actualArgIndex]);
                        if (typeFromFirstParam.isPresent()) { // First param was type, so current is context
                            suggestContextNames(currentArg, completions);
                        }
                        suggestTargetsForElectionVote(sender, args, actualArgIndex, currentArg, completions); // Suggest targets
                    } else if (args.length >= actualArgIndex + 3) { // Typing the target name
                        suggestTargetsForElectionVote(sender, args, actualArgIndex, currentArg, completions);
                    }
                    break;
                case "start": // /tp e start <nation_name> <type:parliament|president>
                case "stop":  // /tp e stop <nation_name> <type:parliament|president> [reason]
                    if (args.length == actualArgIndex + 1 && TownyAPI.getInstance() != null) { // Typing nation name
                        StringUtil.copyPartialMatches(currentArg,
                                TownyAPI.getInstance().getNations().stream().map(Nation::getName).map(s -> s.replace(" ", "_")).collect(Collectors.toList()),
                                completions);
                    } else if (args.length == actualArgIndex + 2) { // Typing election type
                        StringUtil.copyPartialMatches(currentArg,
                                Arrays.asList("PARLIAMENTARY", "PRESIDENTIAL", "PARTY_LEADER"),
                                completions);
                    }
                    break;
            }
        }
    }

    private void completeBillCommands(CommandSender sender, String[] args, String currentArg, List<String> completions, int subCommandIndex) {
        if (args.length == subCommandIndex + 1) { // 正在输入子命令本身
            StringUtil.copyPartialMatches(currentArg, BILL_SUBCOMMANDS, completions);
        } else if (args.length > subCommandIndex + 1) { // 正在输入子命令的参数
            String billSubCommand = args[subCommandIndex].toLowerCase();
            int actualArgIndex = subCommandIndex + 1;

            switch (billSubCommand) {
                case "propose": // /tp bill propose [国家] "<标题>" "<内容>"
                    if (args.length == actualArgIndex + 1) { // 正在输入国家名或标题的开头
                        // 补全国家名
                        if (TownyAPI.getInstance() != null) {
                            TownyAPI.getInstance().getNations().stream()
                                    .map(Nation::getName)
                                    .map(s -> s.replace(" ", "_")) // 对于含空格名称，tab补全时用下划线
                                    .filter(name -> name.toLowerCase().startsWith(currentArg))
                                    .forEach(completions::add);
                        }
                        // 也可以提示 "<标题>"
                        if ("\"".startsWith(currentArg)) completions.add("\"<法案标题>\"");
                    }
                    // 后续的标题和内容不方便做精确补全
                    break;
                case "list": // /tp bill list [国家] [状态] [页码]
                    if (args.length == actualArgIndex + 1) { // 国家或状态或页码
                        if (TownyAPI.getInstance() != null) {
                            TownyAPI.getInstance().getNations().stream().map(n -> n.getName().replace(" ", "_")).filter(name -> name.toLowerCase().startsWith(currentArg)).forEach(completions::add);
                        }
                        Arrays.stream(BillStatus.values()).map(s -> s.name().toLowerCase()).filter(name -> name.startsWith(currentArg)).forEach(completions::add);
                        // 页码不补全
                    } else if (args.length == actualArgIndex + 2) { // 状态或页码
                        Arrays.stream(BillStatus.values()).map(s -> s.name().toLowerCase()).filter(name -> name.startsWith(currentArg)).forEach(completions::add);
                    }
                    break;
                case "info": // /tp bill info <法案ID>
                case "vote": // /tp bill vote <法案ID> <choice>
                    if (args.length == actualArgIndex + 1) { // 正在输入法案ID
                        // 补全活跃的或与玩家相关的法案ID (简化：补全所有已知法案的部分ID)
                        billManager.getAllBills().stream() // 需要 BillManager.getAllBills()
                                .map(b -> b.getBillId().toString().substring(0, 8)) // 取部分ID
                                .filter(id -> id.toLowerCase().startsWith(currentArg))
                                .distinct()
                                .forEach(completions::add);
                    } else if (args.length == actualArgIndex + 2 && billSubCommand.equals("vote")) { // 正在为vote输入选择
                        StringUtil.copyPartialMatches(currentArg,
                                Arrays.stream(VoteChoice.values()).map(vc -> vc.name().toLowerCase()).collect(Collectors.toList()),
                                completions);
                        StringUtil.copyPartialMatches(currentArg,
                                Arrays.stream(VoteChoice.values()).map(VoteChoice::getDisplayName).collect(Collectors.toList()),
                                completions);
                    }
                    break;
            }
        }
    }

    private void suggestContextNames(String currentArg, List<String> completions) {
        if (TownyAPI.getInstance() != null) {
            TownyAPI.getInstance().getNations().stream()
                    .map(Nation::getName).map(s -> s.replace(" ", "_"))
                    .filter(name -> name.toLowerCase().startsWith(currentArg))
                    .forEach(completions::add);
        }
        partyManager.getAllParties().stream()
                .map(Party::getName).map(s -> s.replace(" ", "_"))
                .filter(name -> name.toLowerCase().startsWith(currentArg))
                .forEach(completions::add);
    }

    private void suggestTargetsForElectionVote(CommandSender sender, String[] allArgs, int firstParamIndex, String currentTargetArg, List<String> completions) {
        // allArgs 是完整的命令参数，例如 ["e", "vote", "type_or_context", "context_or_target", "target_if_3_params"]
        // firstParamIndex 是 "type_or_context" 在 allArgs 中的索引

        List<String> contextAndTypeArgsList = new ArrayList<>();
        // 我们需要提取在 "vote" 之后，但在当前正在输入的参数之前的参数作为上下文/类型参数
        // allArgs[0] = "tp" (or alias), allArgs[1] = "election", allArgs[2] = "vote"
        // 如果是 /tp e vote type context target, currentTargetArg 是 target
        //   allArgs = ["e", "vote", "type", "context", "target"]
        //   firstParamIndex = 2 (index of "type")
        //   contextAndTypeArgsList should contain "type", "context"
        //   The current typing arg (target) is at allArgs[allArgs.length -1]
        for (int i = firstParamIndex; i < allArgs.length -1; i++) {
            contextAndTypeArgsList.add(allArgs[i]);
        }
        String[] contextAndTypeArgsArray = contextAndTypeArgsList.toArray(new String[0]);

        // 尝试确定选举类型，如果用户已经明确输入
        ElectionType preferredType = null;
        if (contextAndTypeArgsArray.length > 0) {
            Optional<ElectionType> typeOpt = ElectionType.fromString(contextAndTypeArgsArray[0]);
            if (typeOpt.isPresent()) {
                preferredType = typeOpt.get();
            }
        }

        Election election = electionCmdHandler.getTargetElectionForTabCompletion(sender, contextAndTypeArgsArray, preferredType);

        if (election != null && (election.getStatus() == ElectionStatus.VOTING || election.getStatus() == ElectionStatus.REGISTRATION)) {
            if (election.getType() == ElectionType.PARLIAMENTARY) {
                election.getParticipatingParties().stream()
                        .map(partyId -> partyManager.getParty(partyId))
                        .filter(Objects::nonNull)
                        .map(Party::getName)
                        .map(name -> name.replace(" ", "_")) // For tab completion with spaces
                        .filter(name -> name.toLowerCase().startsWith(currentTargetArg.toLowerCase()))
                        .forEach(completions::add);
            } else { // PRESIDENTIAL or PARTY_LEADER
                election.getCandidates().stream()
                        .map(Candidate::getResolvedPlayerName)
                        .filter(name -> name.toLowerCase().startsWith(currentTargetArg.toLowerCase()))
                        .forEach(completions::add);
            }
        } else if (election == null && contextAndTypeArgsArray.length == 0) {
            // 如果没有上下文参数，并且无法从玩家推断，则同时提示政党和玩家（作为最后手段）
            partyManager.getAllParties().stream()
                    .map(Party::getName)
                    .map(name -> name.replace(" ", "_"))
                    .filter(name -> name.toLowerCase().startsWith(currentTargetArg.toLowerCase()))
                    .forEach(completions::add);
            Bukkit.getOnlinePlayers().stream()
                    .map(Player::getName)
                    .filter(name -> name.toLowerCase().startsWith(currentTargetArg.toLowerCase()))
                    .forEach(completions::add);
        }
    }

    private ElectionType determineElectionTypeFromArgs(CommandSender sender, String[] allArgs, int firstParamIndex) {
        // allArgs[0] is main group (e.g., "election"), allArgs[1] is subcommand
        // firstParamIndex is the index of the first *actual* parameter to the subcommand within allArgs.
        // Example: /tp e vote type context target -> allArgs = ["e", "vote", "type", "context", "target"], firstParamIndex = 2

        // 1. 检查显式指定的类型参数
        if (allArgs.length > firstParamIndex) {
            Optional<ElectionType> typeOpt = ElectionType.fromString(allArgs[firstParamIndex]);
            if (typeOpt.isPresent()) {
                return typeOpt.get();
            }
        }

        // 2. 如果没有显式类型，尝试从后续参数推断上下文，再从上下文中活跃的选举推断类型
        // 这部分会比较复杂，因为后续参数可能是上下文，也可能是目标（候选人/政党）
        // 为了Tab补全，我们可能不需要在这里做得过于完美，因为后续的 suggestXXX 方法会进一步筛选
        // 简化：如果第一个参数不是类型，我们暂时无法仅凭此信息确定类型。
        // 更高级：可以检查 allArgs[firstParamIndex] 是否是国家名或政党名，然后查看该上下文活跃的选举。

        // 如果 sender 是玩家，并且没有明确的类型和上下文参数，可以尝试基于玩家所在国家/政党推断
        if (allArgs.length == firstParamIndex + 1 && sender instanceof Player) { // 玩家正在输入第一个参数（可能是类型、上下文或目标）
            Player player = (Player) sender;
            Resident resident = TownyAPI.getInstance().getResident(player.getUniqueId());
            if (resident != null) {
                if (resident.hasNation()) {
                    try {
                        Nation pNation = resident.getNation();
                        // 如果国家只有一个活跃选举，可以认为是那个类型
                        List<Election> activeNationElections = electionManager.getAllActiveElectionsForContext(pNation.getUUID());
                        if (activeNationElections.size() == 1) {
                            return activeNationElections.get(0).getType();
                        }
                        // 如果有多个，比如同时有议会和总统，则无法确定，除非命令本身有倾向
                        // (例如，"register" 更倾向于个人，"vote" 需要看目标)
                    } catch (TownyException ignored) {}
                }
                Party pParty = partyManager.getPartyByMember(player.getUniqueId());
                if (pParty != null) {
                    Election activePartyElection = electionManager.getActiveElection(pParty.getPartyId(), ElectionType.PARTY_LEADER);
                    if (activePartyElection != null) {
                        return ElectionType.PARTY_LEADER;
                    }
                }
            }
        }
        return null; // 无法确定
    }
}