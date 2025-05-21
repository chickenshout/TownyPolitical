// 文件名: NationCommandsHandler.java
// 结构位置: top/chickenshout/townypolitical/commands/handlers/NationCommandsHandler.java
package top.chickenshout.townypolitical.commands.handlers;

import com.palmergames.bukkit.towny.TownyAPI;
import top.chickenshout.townypolitical.data.Party; // 需要导入
import top.chickenshout.townypolitical.managers.ElectionManager; // 需要导入
import top.chickenshout.townypolitical.managers.PartyManager; // 需要导入
import top.chickenshout.townypolitical.elections.Election; // 需要导入
import top.chickenshout.townypolitical.enums.ElectionType; // 需要导入
import java.util.Map; // 需要导入
import com.palmergames.bukkit.towny.exceptions.NotRegisteredException;
import com.palmergames.bukkit.towny.exceptions.TownyException;
import com.palmergames.bukkit.towny.object.Nation;
import com.palmergames.bukkit.towny.object.Resident;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import top.chickenshout.townypolitical.TownyPolitical;
import top.chickenshout.townypolitical.data.NationPolitics;
import top.chickenshout.townypolitical.enums.GovernmentType;
import top.chickenshout.townypolitical.managers.NationManager;
import top.chickenshout.townypolitical.utils.MessageManager;

import java.util.Arrays;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

public class NationCommandsHandler {
    private final TownyPolitical plugin;
    private final MessageManager messageManager;
    private final NationManager nationManager;

    private final ElectionManager electionManager; // 新增
    private final PartyManager partyManager;       // 新增

    public NationCommandsHandler(TownyPolitical plugin) {
        this.plugin = plugin;
        this.messageManager = plugin.getMessageManager();
        this.nationManager = plugin.getNationManager();
        this.electionManager = plugin.getElectionManager(); // 初始化
        this.partyManager = plugin.getPartyManager();       // 初始化
    }

    public boolean handleCommand(CommandSender sender, String commandLabel, String[] args) {
        if (args.length == 0) {
            sendNationHelp(sender, commandLabel);
            return true;
        }
        String subCommand = args[0].toLowerCase();
        String[] subArgs = new String[args.length - 1];
        if (args.length > 1) {
            System.arraycopy(args, 1, subArgs, 0, args.length - 1);
        }

        switch (subCommand) {
            case "setgov":
            case "setgovernment":
                return handleSetGovernmentCommand(sender, commandLabel, subArgs);
            case "info":
            case "govinfo":
                return handleGovernmentInfoCommand(sender, commandLabel, subArgs);
            case "listgov":
            case "listgovernments":
                return handleListGovernmentsCommand(sender, commandLabel, subArgs);
            default:
                messageManager.sendMessage(sender, "command-nation-unknown", "subcommand", subCommand);
                sendNationHelp(sender, commandLabel);
                return true;
        }
    }

    private boolean handleSetGovernmentCommand(CommandSender sender, String commandLabel, String[] subArgs) {
        if (!(sender instanceof Player)) {
            messageManager.sendMessage(sender, "error-player-only-command");
            return true;
        }
        Player player = (Player) sender;

        if (!player.hasPermission("townypolitical.nation.setgovernment")) {
            messageManager.sendMessage(player, "error-no-permission");
            return true;
        }

        if (subArgs.length < 1) { // 至少需要政体类型，国家可以默认为玩家所在国家
            messageManager.sendMessage(player, "error-invalid-arguments", "usage", "/" + commandLabel + " setgov [国家名称] <政体类型>");
            return true;
        }

        Nation targetNation;
        String governmentTypeName;

        if (subArgs.length == 1) { // /tp nation setgov <政体类型> -> 作用于玩家所在国家
            Resident resident = TownyAPI.getInstance().getResident(player.getUniqueId());
            if (resident == null || !resident.hasNation()) {
                messageManager.sendMessage(player, "nation-command-fail-not-in-nation");
                return true;
            }
            try {
                targetNation = resident.getNation();
            } catch (TownyException e) {
                messageManager.sendMessage(player, "nation-command-fail-not-in-nation"); // Should be caught by hasNation
                return true;
            }
            governmentTypeName = subArgs[0];
        } else { // /tp nation setgov <国家名称> <政体类型>
            // 国家名称可能包含空格，最后一个参数是政体类型
            governmentTypeName = subArgs[subArgs.length - 1];
            String nationName = Arrays.stream(subArgs, 0, subArgs.length - 1).collect(Collectors.joining(" "));

            targetNation = TownyAPI.getInstance().getNation(nationName);
            if (targetNation == null) {
                messageManager.sendMessage(player, "error-nation-not-found", "nation", nationName);
                return true;
            }
        }

        Optional<GovernmentType> optGovType = GovernmentType.fromString(governmentTypeName);
        if (!optGovType.isPresent()) {
            messageManager.sendMessage(player, "nation-setgov-invalid-type", "type", governmentTypeName);
            handleListGovernmentsCommand(player, commandLabel, new String[0]); // 显示可用政体列表
            return true;
        }

        nationManager.setGovernmentType(targetNation, optGovType.get(), player);
        return true;
    }

    private boolean handleGovernmentInfoCommand(CommandSender sender, String commandLabel, String[] subArgs) {
        if (!sender.hasPermission("townypolitical.nation.governmentinfo")) {
            messageManager.sendMessage(sender, "error-no-permission");
            return true;
        }

        Nation targetNation;
        if (subArgs.length == 0) { // 查看自己所在国家
            if (!(sender instanceof Player)) {
                messageManager.sendMessage(sender, "error-player-only-command-for-own-nation-info");
                messageManager.sendMessage(sender, "error-invalid-arguments", "usage", "/" + commandLabel + " info <国家名称>");
                return true;
            }
            Player player = (Player) sender;
            Resident resident = TownyAPI.getInstance().getResident(player.getUniqueId());
            if (resident == null || !resident.hasNation()) {
                messageManager.sendMessage(player, "nation-command-fail-not-in-nation");
                return true;
            }
            try {
                targetNation = resident.getNation();
            } catch (TownyException e) {
                messageManager.sendMessage(player, "nation-command-fail-not-in-nation");
                return true;
            }
        } else {
            String nationName = String.join(" ", subArgs);
            targetNation = TownyAPI.getInstance().getNation(nationName);
            if (targetNation == null) {
                messageManager.sendMessage(sender, "error-nation-not-found", "nation", nationName);
                return true;
            }
        }

        NationPolitics politics = nationManager.getNationPolitics(targetNation); // 会自动创建（如果不存在）
        GovernmentType currentGovType = politics.getGovernmentType();

        messageManager.sendRawMessage(sender, "nation-info-header", "nation_name", targetNation.getName());
        messageManager.sendRawMessage(sender, "nation-info-government-type", "type", currentGovType.getDisplayName());
        messageManager.sendRawMessage(sender, "nation-info-government-description", "description", currentGovType.getDescription()); // 需要在 GovernmentType 中添加 description 字段

        // 未来可以显示更多信息：例如议会状态、下次选举时间等
        // messageManager.sendRawMessage(sender, "nation-info-parliament-status", "status", ...);
        // messageManager.sendRawMessage(sender, "nation-info-next-election", "date", ...);



        if (politics.getPrimeMinisterUUID().isPresent()) {
            OfflinePlayer pm = Bukkit.getOfflinePlayer(politics.getPrimeMinisterUUID().get());
            messageManager.sendRawMessage(sender, "nation-info-prime-minister", "name", pm.getName() != null ? pm.getName() : "N/A");
        }
        if (politics.getGovernmentType() == GovernmentType.CONSTITUTIONAL_MONARCHY && politics.getTitularMonarchUUID().isPresent()) {
            OfflinePlayer monarch = Bukkit.getOfflinePlayer(politics.getTitularMonarchUUID().get());
            messageManager.sendRawMessage(sender, "nation-info-titular-monarch", "name", monarch.getName() != null ? monarch.getName() : "N/A");
        }
        return true;

    }

    private boolean handleListGovernmentsCommand(CommandSender sender, String commandLabel, String[] subArgs) {
        if (!sender.hasPermission("townypolitical.nation.governmentinfo")) { // 复用info权限
            messageManager.sendMessage(sender, "error-no-permission");
            return true;
        }

        messageManager.sendRawMessage(sender, "nation-listgov-header");
        double defaultCost = plugin.getConfig().getDouble("nation.government_change_cost.default", 2500.0);

        for (GovernmentType type : GovernmentType.values()) {
            double cost = plugin.getConfig().getDouble("nation.government_change_cost." + type.name().toLowerCase(), defaultCost);
            String costString = plugin.getEconomyService().isEnabled() && cost > 0 ?
                    " (花费: " + plugin.getEconomyService().format(cost) + ")" : "";

            messageManager.sendRawMessage(sender, "nation-listgov-entry",
                    "name", type.getDisplayName(),
                    "shortname", type.getShortName(), // 用于命令输入的简称
                    "description", type.getDescription(),
                    "cost_info", costString
            );
        }
        messageManager.sendRawMessage(sender, "nation-listgov-footer", "label", commandLabel.split(" ")[0]);
        return true;

    }

    private boolean handleParliamentInfoCommand(CommandSender sender, String commandLabel, String[] subArgs) {
        if (!sender.hasPermission("townypolitical.nation.parliamentinfo")) {
            messageManager.sendMessage(sender, "error-no-permission");
            return true;
        }

        Nation targetNation;
        if (subArgs.length == 0) { // 查看自己所在国家
            if (!(sender instanceof Player)) {
                messageManager.sendMessage(sender, "error-player-only-command-for-own-nation-info"); // 复用
                return true;
            }
            Player player = (Player) sender;
            Resident resident = TownyAPI.getInstance().getResident(player.getUniqueId());
            if (resident == null || !resident.hasNation()) {
                messageManager.sendMessage(player, "nation-command-fail-not-in-nation");
                return true;
            }
            try {
                targetNation = resident.getNation();
            } catch (TownyException e) {
                messageManager.sendMessage(player, "nation-command-fail-not-in-nation");
                return true;
            }
        } else {
            String nationName = String.join(" ", subArgs);
            targetNation = TownyAPI.getInstance().getNation(nationName);
            if (targetNation == null) {
                messageManager.sendMessage(sender, "error-nation-not-found", "nation", nationName);
                return true;
            }
        }

        NationPolitics politics = nationManager.getNationPolitics(targetNation);
        if (!politics.getGovernmentType().hasParliament()) {
            messageManager.sendMessage(sender, "nation-parliament-info-no-parliament",
                    "nation_name", targetNation.getName(),
                    "gov_type", politics.getGovernmentType().getDisplayName());
            return true;
        }

        // 查找最近一次完成的议会选举
        // 这需要 ElectionManager 提供一个方法来获取特定国家特定类型的最新已完成选举
        Optional<Election> latestParliamentElectionOpt = Optional.ofNullable(electionManager.getActiveElection(targetNation.getUUID(), ElectionType.PARLIAMENTARY));

        if (!latestParliamentElectionOpt.isPresent()) {
            messageManager.sendMessage(sender, "nation-parliament-info-no-election-data", "nation_name", targetNation.getName());
            return true;
        }

        Election latestElection = latestParliamentElectionOpt.get();
        Map<UUID, Integer> seatDistribution = latestElection.getPartySeatDistribution();

        messageManager.sendRawMessage(sender, "nation-parliament-info-header", "nation_name", targetNation.getName());
        if (seatDistribution.isEmpty()) {
            messageManager.sendRawMessage(sender, "nation-parliament-info-no-seats");
        } else {
            int totalSeatsDisplayed = seatDistribution.values().stream().mapToInt(Integer::intValue).sum();
            int configuredTotalSeats = plugin.getConfig().getInt("elections.parliament.total_seats", 100); // 从配置获取理论总席位
            messageManager.sendRawMessage(sender, "nation-parliament-info-total-seats", "allocated_seats", String.valueOf(totalSeatsDisplayed), "total_seats", String.valueOf(configuredTotalSeats));


            // 按席位多少排序显示
            seatDistribution.entrySet().stream()
                    .sorted(Map.Entry.<UUID, Integer>comparingByValue().reversed())
                    .forEach(entry -> {
                        Party party = partyManager.getParty(entry.getKey());
                        String partyName = (party != null) ? party.getName() : "未知政党 (" + entry.getKey().toString().substring(0, 6) + ")";
                        messageManager.sendRawMessage(sender, "nation-parliament-info-seat-entry",
                                "party_name", partyName,
                                "seats", String.valueOf(entry.getValue()));
                    });
            // 显示多数党/执政党（如果有）
            if (latestElection.getWinnerPartyUUID() != null) {
                Party rulingParty = partyManager.getParty(String.valueOf(latestElection.getWinnerPartyUUID()));
                if (rulingParty != null) {
                    messageManager.sendRawMessage(sender, "nation-parliament-info-ruling-party", "party_name", rulingParty.getName());
                }
            }
        }

        // 显示虚位君主信息（如果适用且已设置）
        if (politics.getGovernmentType() == GovernmentType.CONSTITUTIONAL_MONARCHY && politics.getTitularMonarchUUID().isPresent()) {
            OfflinePlayer monarch = Bukkit.getOfflinePlayer(politics.getTitularMonarchUUID().get());
            messageManager.sendRawMessage(sender, "nation-parliament-info-titular-monarch", "monarch_name", monarch.getName() != null ? monarch.getName() : "N/A");
        }

        if (politics.getPrimeMinisterUUID().isPresent()) { // 议会信息中也显示总理
            OfflinePlayer pm = Bukkit.getOfflinePlayer(politics.getPrimeMinisterUUID().get());
            messageManager.sendRawMessage(sender, "nation-parliament-info-prime-minister", "name", pm.getName() != null ? pm.getName() : "N/A");
        }
        if (politics.getGovernmentType() == GovernmentType.CONSTITUTIONAL_MONARCHY && politics.getTitularMonarchUUID().isPresent()) {
            OfflinePlayer monarch = Bukkit.getOfflinePlayer(politics.getTitularMonarchUUID().get());
            messageManager.sendRawMessage(sender, "nation-parliament-info-titular-monarch", "name", monarch.getName() != null ? monarch.getName() : "N/A");
        }

        return true;
    }

    private void sendNationHelp(CommandSender sender, String commandLabel) {
        String displayLabel = commandLabel.split(" ")[0];
        if (!displayLabel.equalsIgnoreCase("tnation")) { // Assuming /tnation is an alias
            displayLabel += " nation";
        }
        if (sender.hasPermission("townypolitical.nation.setmonarch"))
            messageManager.sendRawMessage(sender, "help-nation-setmonarch", "label", displayLabel);
        messageManager.sendRawMessage(sender, "help-nation-header", "label", displayLabel);
        if (sender.hasPermission("townypolitical.nation.setgovernment"))
            messageManager.sendRawMessage(sender, "help-nation-setgov", "label", displayLabel);
        if (sender.hasPermission("townypolitical.nation.governmentinfo")) {
            messageManager.sendRawMessage(sender, "help-nation-info", "label", displayLabel);
            messageManager.sendRawMessage(sender, "help-nation-listgov", "label", displayLabel);
        }
        messageManager.sendRawMessage(sender, "help-footer");
    }

    private boolean handleSetMonarchCommand(CommandSender sender, String commandLabel, String[] subArgs) {
        if (!(sender instanceof Player)) {
            messageManager.sendMessage(sender, "error-player-only-command");
            return true;
        }
        Player player = (Player) sender;

        if (!player.hasPermission("townypolitical.nation.setmonarch")) {
            messageManager.sendMessage(player, "error-no-permission");
            return true;
        }

        // 用法: /tp nation setmonarch [国家名称] <玩家名称_或_remove>
        if (subArgs.length < 1) {
            messageManager.sendMessage(player, "error-invalid-arguments", "usage", "/" + commandLabel + " setmonarch [国家名称] <玩家名称 | remove>");
            return true;
        }

        Nation targetNation;
        String targetPlayerNameOrAction;

        if (subArgs.length == 1) { // /tp nation setmonarch <玩家_或_remove> -> 作用于执行者所在国家
            Resident resident = TownyAPI.getInstance().getResident(player.getUniqueId());
            if (resident == null || !resident.hasNation()) {
                messageManager.sendMessage(player, "nation-command-fail-not-in-nation");
                return true;
            }
            try {
                targetNation = resident.getNation();
            } catch (TownyException e) {
                messageManager.sendMessage(player, "nation-command-fail-not-in-nation");
                return true;
            }
            targetPlayerNameOrAction = subArgs[0];
        } else { // /tp nation setmonarch <国家名称> <玩家_或_remove>
            targetPlayerNameOrAction = subArgs[subArgs.length - 1];
            String nationName = Arrays.stream(subArgs, 0, subArgs.length - 1).collect(Collectors.joining(" "));

            targetNation = TownyAPI.getInstance().getNation(nationName);
            if (targetNation == null) {
                messageManager.sendMessage(player, "error-nation-not-found", "nation", nationName);
                return true;
            }
        }

        // 权限检查: 只有当前Towny King (通常是总理) 才能指定虚位君主
        Resident actorResident = TownyAPI.getInstance().getResident(player.getUniqueId());
        if (actorResident == null || !targetNation.isKing(actorResident)) {
            messageManager.sendMessage(player, "nation-setmonarch-fail-not-king", "nation_name", targetNation.getName());
            return true;
        }

        NationPolitics politics = nationManager.getNationPolitics(targetNation);
        if (politics.getGovernmentType() != GovernmentType.CONSTITUTIONAL_MONARCHY) {
            messageManager.sendMessage(player, "nation-setmonarch-fail-wrong-govtype",
                    "nation_name", targetNation.getName(),
                    "gov_type", politics.getGovernmentType().getDisplayName(),
                    "required_gov_type", GovernmentType.CONSTITUTIONAL_MONARCHY.getDisplayName()
            );
            return true;
        }

        if (targetPlayerNameOrAction.equalsIgnoreCase("remove") || targetPlayerNameOrAction.equalsIgnoreCase("clear") || targetPlayerNameOrAction.equalsIgnoreCase("none")) {
            if (!politics.getTitularMonarchUUID().isPresent()) {
                messageManager.sendMessage(player, "nation-setmonarch-fail-none-to-remove", "nation_name", targetNation.getName());
                return true;
            }
            politics.setTitularMonarchUUID(null);
            nationManager.saveNationPolitics(politics);
            messageManager.sendMessage(player, "nation-setmonarch-remove-success", "nation_name", targetNation.getName());
            // 可以向国家广播
            broadcastToNation(targetNation, "nation-monarch-removed-broadcast", "nation_name", targetNation.getName(), "actor_name", player.getName());
            return true;
        }

        OfflinePlayer newMonarchPlayer = Bukkit.getOfflinePlayer(targetPlayerNameOrAction);
        if (!newMonarchPlayer.hasPlayedBefore() && !newMonarchPlayer.isOnline()) {
            messageManager.sendMessage(player, "error-player-not-found-or-never-played", "player", targetPlayerNameOrAction);
            return true;
        }

        // 确保新君主是国家成员 (可选，但推荐)
        Resident newMonarchResident = TownyAPI.getInstance().getResident(newMonarchPlayer.getUniqueId());
        if (newMonarchResident == null || !newMonarchResident.hasNation() || !newMonarchResident.getNationOrNull().equals(targetNation)) {
            messageManager.sendMessage(player, "nation-setmonarch-fail-target-not-citizen", "player_name", newMonarchPlayer.getName(), "nation_name", targetNation.getName());
            return true;
        }

        // 不能是当前实际的Towny King (总理)
        if (targetNation.hasKing() && targetNation.getKing().getUUID().equals(newMonarchPlayer.getUniqueId())) {
            messageManager.sendMessage(player, "nation-setmonarch-fail-target-is-king", "player_name", newMonarchPlayer.getName());
            return true;
        }


        politics.setTitularMonarchUUID(newMonarchPlayer.getUniqueId());
        nationManager.saveNationPolitics(politics);
        messageManager.sendMessage(player, "nation-setmonarch-success",
                "monarch_name", newMonarchPlayer.getName(),
                "nation_name", targetNation.getName());
        // 广播
        broadcastToNation(targetNation, "nation-monarch-appointed-broadcast",
                "nation_name", targetNation.getName(),
                "monarch_name", newMonarchPlayer.getName(),
                "actor_name", player.getName()
        );
        return true;
    }

    // 需要在 NationCommandsHandler 中添加 broadcastToNation 方法 (如果还没有)
    private void broadcastToNation(Nation nation, String messageKey, Object... placeholders) {
        if (nation == null) return;
        String message = messageManager.getMessage(messageKey, placeholders); // 使用带前缀的
        for (Resident resident : nation.getResidents()) {
            if (resident.isOnline()) {
                Player player = Bukkit.getPlayer(resident.getUUID());
                if (player != null) {
                    // messageManager.sendMessage(player, messageKey, placeholders); // 如果想用 sendMessage
                    player.sendMessage(message); // 直接发送已格式化的
                }
            }
        }
        plugin.getLogger().info("[Nation Broadcast to " + nation.getName() + "] " + messageManager.getRawMessage(messageKey, Arrays.toString(placeholders))); // 日志用原始消息
    }
}