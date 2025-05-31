// 文件名: PoliticalTabCompleter.java
// 结构位置: top/chickenshout/townypolitical/commands/PoliticalTabCompleter.java
package top.chickenshout.townypolitical.commands;

import com.palmergames.bukkit.towny.TownyAPI;
import com.palmergames.bukkit.towny.exceptions.NotRegisteredException;
import com.palmergames.bukkit.towny.object.Nation;
import com.palmergames.bukkit.towny.object.Resident; // For player context
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.util.StringUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import top.chickenshout.townypolitical.TownyPolitical;
import top.chickenshout.townypolitical.data.Party;
import top.chickenshout.townypolitical.elections.Candidate;
import top.chickenshout.townypolitical.elections.Election;
import top.chickenshout.townypolitical.enums.ElectionStatus;
import top.chickenshout.townypolitical.enums.ElectionType;
import top.chickenshout.townypolitical.enums.GovernmentType;
import top.chickenshout.townypolitical.managers.ElectionManager;
import top.chickenshout.townypolitical.managers.NationManager;
import top.chickenshout.townypolitical.managers.PartyManager;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class PoliticalTabCompleter implements TabCompleter {

    private final TownyPolitical plugin;
    private final PartyManager partyManager;
    private final NationManager nationManager;
    private final ElectionManager electionManager;

    // 主命令组 (根据权限显示)
    private static final List<String> MAIN_COMMAND_GROUPS_USER = Arrays.asList("party", "nation", "election", "help", "info");
    private static final List<String> MAIN_COMMAND_GROUPS_ADMIN = Arrays.asList("party", "nation", "election", "help", "info", "reload");

    // 各主命令组下的子命令
    private static final List<String> PARTY_SUBCOMMANDS = Arrays.asList(
            "create", "disband", "info", "list", "apply", "leave",
            "invite", "kick", "accept", "reject", "promote", "demote",
            "rename", "setleader"
    );
    private static final List<String> NATION_SUBCOMMANDS = Arrays.asList(
            "setgov", "info", "listgov", "setmonarch", "appointpremier", "parliament" // 'parliament' for parliamentinfo
    );
    private static final List<String> ELECTION_SUBCOMMANDS = Arrays.asList(
            "info", "candidates", "register", "vote", "results", // User commands
            "start", "stop" // Admin commands
    );


    public PoliticalTabCompleter(TownyPolitical plugin) {
        this.plugin = plugin;
        this.partyManager = plugin.getPartyManager();
        this.nationManager = plugin.getNationManager();
        this.electionManager = plugin.getElectionManager();
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
                    case "party": case "p":
                        completePartyCommands(sender, args, currentArg, completions, 1);
                        break;
                    case "nation": case "n":
                        completeNationCommands(sender, args, currentArg, completions, 1);
                        break;
                    case "election": case "e":
                        completeElectionCommands(sender, args, currentArg, completions, 1);
                        break;
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
                        if(args.length > actualArgIndex && TownyAPI.getInstance() != null && TownyAPI.getInstance().getNation(args[actualArgIndex]) != null){
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
                case "register":    // /tp e register [type] [context]
                    if (args.length == actualArgIndex + 1) { // Typing election type OR context name
                        // Suggest election types
                        Stream.of(ElectionType.values())
                                .map(et -> et.name().toLowerCase())
                                .filter(name -> name.startsWith(currentArg))
                                .forEach(completions::add);
                        // Suggest contexts (nations/parties)
                        suggestContextNames(currentArg, completions);
                    } else if (args.length == actualArgIndex + 2) { // Typing context name after type
                        suggestContextNames(currentArg, completions);
                    }
                    break;
                case "vote": // /tp e vote [type] [context] <candidate_name>
                    if (args.length >= actualArgIndex + 1 && args.length <= actualArgIndex + 3) { // Typing type, context, or candidate
                        if (args.length == actualArgIndex + 1) { // Type or Context or Candidate
                            Stream.of(ElectionType.values()).map(et -> et.name().toLowerCase()).filter(name -> name.startsWith(currentArg)).forEach(completions::add);
                            suggestContextNames(currentArg, completions);
                            suggestCandidateNamesForContext(sender, args, actualArgIndex -1, currentArg, completions); // args[actualArgIndex-1] could be context
                        } else if (args.length == actualArgIndex + 2) { // Context or Candidate
                            suggestContextNames(currentArg, completions);
                            suggestCandidateNamesForContext(sender, args, actualArgIndex -1, currentArg, completions);
                        } else { // Candidate
                            suggestCandidateNamesForContext(sender, args, actualArgIndex, currentArg, completions);
                        }
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

    private void suggestCandidateNamesForContext(CommandSender sender, String[] allArgs, int contextArgIndex, String currentCandidateArg, List<String> completions) {
        // Try to determine context from allArgs[contextArgIndex]
        // This is complex because context could be implied or explicitly given with type.
        // For simplicity, find any active election and suggest its candidates.
        // A more robust way: getTargetElectionForInfo from ElectionCH, but that needs more args.

        Player player = (sender instanceof Player) ? (Player) sender : null;
        Election election = null;
        List<String> potentialContextArgs = new ArrayList<>();
        for(int i = 1; i < allArgs.length -1; i++){ // Iterate through args between main command and current typing arg
            potentialContextArgs.add(allArgs[i]);
        }

        // Attempt to find the election context more robustly
        ElectionType preferredType = null;
        String[] contextNameArgsArray = {};
        if (!potentialContextArgs.isEmpty()) {
            String firstPotContext = potentialContextArgs.get(0).toLowerCase();
            if (firstPotContext.equals("parliament") || firstPotContext.equals("parl")) {
                preferredType = ElectionType.PARLIAMENTARY;
                if (potentialContextArgs.size() > 1) contextNameArgsArray = potentialContextArgs.subList(1, potentialContextArgs.size()).toArray(new String[0]);
            } else if (firstPotContext.equals("president") || firstPotContext.equals("pres")) {
                preferredType = ElectionType.PRESIDENTIAL;
                if (potentialContextArgs.size() > 1) contextNameArgsArray = potentialContextArgs.subList(1, potentialContextArgs.size()).toArray(new String[0]);
            } else if (firstPotContext.equals("leader")) {
                preferredType = ElectionType.PARTY_LEADER;
                if (potentialContextArgs.size() > 1) contextNameArgsArray = potentialContextArgs.subList(1, potentialContextArgs.size()).toArray(new String[0]);
            }
            else { // Assume all potentialContextArgs are part of the context name
                contextNameArgsArray = potentialContextArgs.toArray(new String[0]);
            }
        }


        // Use a simplified getTargetElection logic here (cannot directly call ElectionCH's method easily)
        if (contextNameArgsArray.length == 0 && player != null) {
            Nation pNation = TownyAPI.getInstance().getResidentNationOrNull((Resident) player);
            if (pNation != null) {
                election = electionManager.getActiveElection(pNation.getUUID(), preferredType != null ? preferredType : ElectionType.PRESIDENTIAL); // Default to pres if no type
                if (election == null && preferredType == null) election = electionManager.getActiveElection(pNation.getUUID(), ElectionType.PARLIAMENTARY);
            }
            if (election == null) {
                Party pParty = partyManager.getPartyByMember(player.getUniqueId());
                if (pParty != null) election = electionManager.getActiveElection(pParty.getPartyId(), ElectionType.PARTY_LEADER);
            }
        } else if (contextNameArgsArray.length > 0) {
            String ctxName = String.join(" ", contextNameArgsArray);
            Nation nationCtx = TownyAPI.getInstance().getNation(ctxName);
            if (nationCtx != null) {
                election = electionManager.getActiveElection(nationCtx.getUUID(), preferredType != null ? preferredType : ElectionType.PRESIDENTIAL);
                if (election == null && preferredType == null) election = electionManager.getActiveElection(nationCtx.getUUID(), ElectionType.PARLIAMENTARY);
            }
            if (election == null) {
                Party partyCtx = partyManager.getParty(ctxName);
                if (partyCtx != null) election = electionManager.getActiveElection(partyCtx.getPartyId(), ElectionType.PARTY_LEADER);
            }
        }


        if (election != null && (election.getStatus() == ElectionStatus.VOTING || election.getStatus() == ElectionStatus.REGISTRATION)) {
            election.getCandidates().stream()
                    .map(Candidate::getResolvedPlayerName) // Use resolved name
                    .filter(name -> name.toLowerCase().startsWith(currentCandidateArg))
                    .forEach(completions::add);
        }
    }
}