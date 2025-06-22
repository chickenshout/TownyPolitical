// 文件名: BillCommandsHandler.java
// 结构位置: top/chickenshout/townypolitical/commands/handlers/BillCommandsHandler.java
package top.chickenshout.townypolitical.commands.handlers;

import com.palmergames.bukkit.towny.TownyAPI;
import com.palmergames.bukkit.towny.exceptions.NotRegisteredException;
import com.palmergames.bukkit.towny.exceptions.TownyException;
import com.palmergames.bukkit.towny.object.Nation;
import com.palmergames.bukkit.towny.object.Resident;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import top.chickenshout.townypolitical.TownyPolitical;
import top.chickenshout.townypolitical.data.Bill;
import top.chickenshout.townypolitical.data.NationPolitics;
import top.chickenshout.townypolitical.enums.BillStatus;
import top.chickenshout.townypolitical.enums.GovernmentType;
import top.chickenshout.townypolitical.enums.VoteChoice;
import top.chickenshout.townypolitical.managers.BillManager;
import top.chickenshout.townypolitical.managers.NationManager;
import top.chickenshout.townypolitical.utils.MessageManager;

import java.text.SimpleDateFormat;
import java.util.*;
import java.util.stream.Collectors;

public class BillCommandsHandler {
    private final TownyPolitical plugin;
    private final MessageManager messageManager;
    private final BillManager billManager;
    private final NationManager nationManager;
    private final SimpleDateFormat dateFormat;

    public BillCommandsHandler(TownyPolitical plugin) {
        this.plugin = plugin;
        this.messageManager = plugin.getMessageManager();
        this.billManager = plugin.getBillManager();
        this.nationManager = plugin.getNationManager();

        String configDateFormat = plugin.getConfig().getString("general.date_format", "yyyy-MM-dd HH:mm:ss z");
        String configTimeZone = plugin.getConfig().getString("general.time_zone", TimeZone.getDefault().getID());
        this.dateFormat = new SimpleDateFormat(configDateFormat);
        try {
            this.dateFormat.setTimeZone(TimeZone.getTimeZone(configTimeZone));
        } catch (Exception e) {
            this.dateFormat.setTimeZone(TimeZone.getDefault());
        }
    }

    public boolean handleCommand(CommandSender sender, String commandLabel, String[] args) {
        if (args.length == 0) {
            sendBillHelp(sender, commandLabel);
            return true;
        }
        String subCommand = args[0].toLowerCase();
        String[] subArgs = Arrays.copyOfRange(args, 1, args.length);

        switch (subCommand) {
            case "propose":
                return handleProposeCommand(sender, commandLabel, subArgs);
            case "list":
                return handleListCommand(sender, commandLabel, subArgs);
            case "info":
                return handleInfoCommand(sender, commandLabel, subArgs);
            case "vote":
                return handleVoteCommand(sender, commandLabel, subArgs);
            // case "cancel": // 未来可能添加
            //    return handleCancelCommand(sender, commandLabel, subArgs);
            // case "enact": // 未来可能添加，用于管理员或特定角色手动颁布
            //    return handleEnactCommand(sender, commandLabel, subArgs);
            default:
                messageManager.sendMessage(sender, "command-bill-unknown", "subcommand", subCommand);
                sendBillHelp(sender, commandLabel);
                return true;
        }
    }

    private boolean handleProposeCommand(CommandSender sender, String commandLabel, String[] subArgs) {
        if (!(sender instanceof Player)) {
            messageManager.sendMessage(sender, "error-player-only-command");
            return true;
        }
        Player player = (Player) sender;

        if (!player.hasPermission("townypolitical.bill.propose")) {
            messageManager.sendMessage(player, "error-no-permission");
            return true;
        }

        // 用法: /tp bill propose <国家名称> "<法案标题>" "<法案内容>"
        // 或者 /tp bill propose "<法案标题>" "<法案内容>" (如果玩家在国家内，则默认为自己国家)
        if (subArgs.length < 2) {
            messageManager.sendMessage(player, "error-invalid-arguments", "usage", "/" + commandLabel + " propose [国家] \"<标题>\" \"<内容>\"");
            return true;
        }

        Nation targetNation;
        String title;
        String content;
        int argOffset = 0;

        // 尝试解析国家名称 (如果提供了多个参数)
        // 简单的解析：如果第一个参数不是以引号开头，则认为是国家名
        if (subArgs.length > 2 && !subArgs[0].startsWith("\"")) {
            targetNation = TownyAPI.getInstance().getNation(subArgs[0]);
            if (targetNation == null) {
                messageManager.sendMessage(player, "error-nation-not-found", "nation", subArgs[0]);
                return true;
            }
            argOffset = 1;
        } else {
            Resident resident = TownyAPI.getInstance().getResident(player.getUniqueId());
            if (resident == null || !resident.hasNation()) {
                messageManager.sendMessage(player, "bill-propose-fail-no-nation-context");
                return true;
            }
            try {
                targetNation = resident.getNation();
            } catch (NotRegisteredException e) {
                messageManager.sendMessage(player, "bill-propose-fail-no-nation-context");
                return true;
            } catch (TownyException e) {
                throw new RuntimeException(e);
            }
        }

        if (subArgs.length < argOffset + 2) {
            messageManager.sendMessage(player, "error-invalid-arguments", "usage", "/" + commandLabel + " propose [国家] \"<标题>\" \"<内容>\"");
            return true;
        }

        // 解析带引号的标题和内容
        // 这是一个简化的解析，实际中可能需要更健壮的引号处理
        String remainingArgs = String.join(" ", Arrays.copyOfRange(subArgs, argOffset, subArgs.length));
        String[] quotedParts = remainingArgs.split("\" \""); // 按 " " 分割
        if (quotedParts.length == 2 && quotedParts[0].startsWith("\"") && quotedParts[1].endsWith("\"")) {
            title = quotedParts[0].substring(1);
            content = quotedParts[1].substring(0, quotedParts[1].length() - 1);
        } else if (quotedParts.length > 2 && quotedParts[0].startsWith("\"")) {
            // 标题可能包含 " "，内容是最后一个 " " 之后的部分
            StringBuilder titleBuilder = new StringBuilder(quotedParts[0].substring(1));
            for(int i = 1; i < quotedParts.length -1; i++){
                titleBuilder.append("\" \"").append(quotedParts[i]);
            }
            title = titleBuilder.toString();
            content = quotedParts[quotedParts.length-1].substring(0, quotedParts[quotedParts.length-1].length() -1);

        }
        else if (subArgs.length == argOffset + 2) { // 假设标题和内容不含空格
            title = subArgs[argOffset];
            content = subArgs[argOffset+1];
        }
        else {
            messageManager.sendMessage(player, "bill-propose-fail-format-error");
            messageManager.sendMessage(player, "error-invalid-arguments", "usage", "/" + commandLabel + " propose [国家] \"<标题>\" \"<内容>\"");
            return true;
        }


        if (title.length() > plugin.getConfig().getInt("bills.max_title_length", 100)) {
            messageManager.sendMessage(player, "bill-propose-fail-title-too-long", "max", String.valueOf(plugin.getConfig().getInt("bills.max_title_length", 100)));
            return true;
        }
        if (content.length() > plugin.getConfig().getInt("bills.max_content_length", 1000)) {
            messageManager.sendMessage(player, "bill-propose-fail-content-too-long", "max", String.valueOf(plugin.getConfig().getInt("bills.max_content_length", 1000)));
            return true;
        }

        billManager.proposeBill(player, targetNation, title, content);
        return true;
    }

    private boolean handleListCommand(CommandSender sender, String commandLabel, String[] subArgs) {
        if (!sender.hasPermission("townypolitical.bill.list")) {
            messageManager.sendMessage(sender, "error-no-permission");
            return true;
        }

        Nation targetNation = null;
        BillStatus filterStatus = null;
        int page = 1; // 默认第一页

        // 解析参数: [国家] [状态] [页码]
        List<String> actualArgs = new ArrayList<>(Arrays.asList(subArgs));

        // 尝试解析页码 (最后一个数字参数)
        if (!actualArgs.isEmpty()) {
            try {
                page = Integer.parseInt(actualArgs.get(actualArgs.size() - 1));
                if (page < 1) page = 1;
                actualArgs.remove(actualArgs.size() - 1); // 移除页码参数
            } catch (NumberFormatException ignored) { /* 不是页码 */ }
        }

        // 尝试解析状态 (剩余参数中的一个，如果是有效的BillStatus)
        if (!actualArgs.isEmpty()) {
            Optional<BillStatus> statusOpt = BillStatus.fromString(actualArgs.get(actualArgs.size() - 1));
            if (statusOpt.isPresent()) {
                filterStatus = statusOpt.get();
                actualArgs.remove(actualArgs.size() - 1); // 移除状态参数
            }
        }

        // 剩余的参数（如果有）是国家名称
        if (!actualArgs.isEmpty()) {
            String nationName = String.join(" ", actualArgs);
            targetNation = TownyAPI.getInstance().getNation(nationName);
            if (targetNation == null) {
                messageManager.sendMessage(sender, "error-nation-not-found", "nation", nationName);
                return true;
            }
        } else if (sender instanceof Player) {
            Player player = (Player) sender;
            Resident resident = TownyAPI.getInstance().getResident(player.getUniqueId());
            if (resident != null && resident.hasNation()) {
                try {
                    targetNation = resident.getNation();
                } catch (NotRegisteredException ignored) {} catch (TownyException e) {
                    throw new RuntimeException(e);
                }
            }
        }

        if (targetNation == null) {
            messageManager.sendMessage(sender, "bill-list-fail-no-nation-context");
            return true;
        }

        List<Bill> bills;
        if (filterStatus != null) {
            bills = billManager.getBillsForNationByStatus(targetNation.getUUID(), filterStatus);
        } else {
            bills = billManager.getBillsForNation(targetNation.getUUID());
            bills.sort(Comparator.comparingLong(Bill::getProposalTimestamp).reversed()); // 默认按提案时间排序
        }

        if (bills.isEmpty()) {
            messageManager.sendMessage(sender, "bill-list-empty", "nation_name", targetNation.getName(), "status_filter", filterStatus != null ? filterStatus.getDisplayName() : "所有");
            return true;
        }

        int itemsPerPage = plugin.getConfig().getInt("bills.list_items_per_page", 7);
        int totalPages = (int) Math.ceil((double) bills.size() / itemsPerPage);
        if (page > totalPages) page = totalPages;

        messageManager.sendRawMessage(sender, "bill-list-header",
                "nation_name", targetNation.getName(),
                "status_filter", filterStatus != null ? filterStatus.getDisplayName() : "所有",
                "current_page", String.valueOf(page),
                "total_pages", String.valueOf(totalPages)
        );

        int startIndex = (page - 1) * itemsPerPage;
        int endIndex = Math.min(startIndex + itemsPerPage, bills.size());

        for (int i = startIndex; i < endIndex; i++) {
            Bill bill = bills.get(i);
            messageManager.sendRawMessage(sender, "bill-list-entry",
                    "id", bill.getBillId().toString().substring(0, 8),
                    "title", bill.getTitle(),
                    "status", bill.getStatus().getDisplayName(),
                    "proposer", bill.getProposerNameCache() != null ? bill.getProposerNameCache() : "未知"
            );
        }
        if (page < totalPages) {
            messageManager.sendRawMessage(sender, "bill-list-next-page", "next_page_command", "/" + commandLabel + " list " + targetNation.getName().replace(" ","_") + (filterStatus != null ? " " + filterStatus.name().toLowerCase() : "") + " " + (page + 1));
        }
        return true;
    }

    private boolean handleInfoCommand(CommandSender sender, String commandLabel, String[] subArgs) {
        if (!sender.hasPermission("townypolitical.bill.info")) {
            messageManager.sendMessage(sender, "error-no-permission");
            return true;
        }
        if (subArgs.length < 1) {
            messageManager.sendMessage(sender, "error-invalid-arguments", "usage", "/" + commandLabel + " info <法案ID>");
            return true;
        }
        UUID billId;
        try {
            billId = UUID.fromString(subArgs[0]);
        } catch (IllegalArgumentException e) {
            // 尝试按部分ID搜索
            String partialId = subArgs[0].toLowerCase();
            List<Bill> found = billManager.getAllBills().stream() // 需要 BillManager.getAllBills()
                    .filter(b -> b.getBillId().toString().toLowerCase().startsWith(partialId))
                    .collect(Collectors.toList());
            if (found.size() == 1) {
                billId = found.get(0).getBillId();
            } else if (found.size() > 1) {
                messageManager.sendMessage(sender, "bill-info-fail-multiple-found-by-partial-id", "partial_id", partialId);
                return true;
            } else {
                messageManager.sendMessage(sender, "bill-info-fail-invalid-id", "id", subArgs[0]);
                return true;
            }
        }

        Bill bill = billManager.getBill(billId);
        if (bill == null) {
            messageManager.sendMessage(sender, "bill-info-fail-not-found", "id", billId.toString().substring(0,8));
            return true;
        }

        Nation nation = TownyAPI.getInstance().getNation(bill.getNationId());
        messageManager.sendRawMessage(sender, "bill-info-header", "title", bill.getTitle());
        messageManager.sendRawMessage(sender, "bill-info-id", "id", bill.getBillId().toString());
        messageManager.sendRawMessage(sender, "bill-info-nation", "nation_name", nation != null ? nation.getName() : "未知国家");
        messageManager.sendRawMessage(sender, "bill-info-proposer", "name", bill.getProposerNameCache() != null ? bill.getProposerNameCache() : "未知");
        messageManager.sendRawMessage(sender, "bill-info-status", "status", bill.getStatus().getDisplayName());
        messageManager.sendRawMessage(sender, "bill-info-proposal-time", "time", dateFormat.format(new Date(bill.getProposalTimestamp())));
        if (bill.getStatus() == BillStatus.VOTING && bill.getVotingEndTimestamp() > 0) {
            messageManager.sendRawMessage(sender, "bill-info-voting-ends", "time", dateFormat.format(new Date(bill.getVotingEndTimestamp())));
        }
        if (bill.getStatus() == BillStatus.ENACTED && bill.getEnactmentTimestamp() > 0) {
            messageManager.sendRawMessage(sender, "bill-info-enactment-time", "time", dateFormat.format(new Date(bill.getEnactmentTimestamp())));
        }
        messageManager.sendRawMessage(sender, "bill-info-content-header");
        // 将内容分行发送，避免过长
        Arrays.stream(bill.getContent().split("\n")).forEach(line -> sender.sendMessage(ChatColor.GRAY + "  " + line));

        if (bill.getStatus() == BillStatus.VOTING || bill.getStatus() == BillStatus.PASSED_BY_PARLIAMENT || bill.getStatus() == BillStatus.REJECTED_BY_PARLIAMENT) {
            messageManager.sendRawMessage(sender, "bill-info-votes-header");
            messageManager.sendRawMessage(sender, "bill-info-votes-yea", "count", String.valueOf(bill.getYeaVotes()));
            messageManager.sendRawMessage(sender, "bill-info-votes-nay", "count", String.valueOf(bill.getNayVotes()));
            messageManager.sendRawMessage(sender, "bill-info-votes-abstain", "count", String.valueOf(bill.getAbstainVotes()));
            // TODO: 显示详细投票者列表 (如果需要且有权限)
        }
        return true;
    }

    private boolean handleVoteCommand(CommandSender sender, String commandLabel, String[] subArgs) {
        if (!(sender instanceof Player)) {
            messageManager.sendMessage(sender, "error-player-only-command");
            return true;
        }
        Player player = (Player) sender;
        if (!player.hasPermission("townypolitical.bill.vote")) {
            messageManager.sendMessage(player, "error-no-permission");
            return true;
        }
        // 用法: /tp bill vote <法案ID> <yea|nay|abstain>
        if (subArgs.length < 2) {
            messageManager.sendMessage(player, "error-invalid-arguments", "usage", "/" + commandLabel + " vote <法案ID> <赞成|反对|弃权>");
            return true;
        }
        UUID billId;
        try {
            billId = UUID.fromString(subArgs[0]);
        } catch (IllegalArgumentException e) {
            String partialId = subArgs[0].toLowerCase();
            List<Bill> found = billManager.getAllBills().stream()
                    .filter(b -> b.getBillId().toString().toLowerCase().startsWith(partialId) && b.getStatus() == BillStatus.VOTING)
                    .collect(Collectors.toList());
            if (found.size() == 1) {
                billId = found.get(0).getBillId();
            } else if (found.size() > 1) {
                messageManager.sendMessage(sender, "bill-vote-fail-multiple-voting-bills-by-partial-id", "partial_id", partialId);
                return true;
            } else {
                messageManager.sendMessage(sender, "bill-vote-fail-invalid-id-or-not-voting", "id", subArgs[0]);
                return true;
            }
        }

        Bill bill = billManager.getBill(billId);
        if (bill == null) {
            messageManager.sendMessage(player, "bill-info-fail-not-found", "id", billId.toString().substring(0,8)); // Reuse info message
            return true;
        }

        VoteChoice choice = VoteChoice.fromString(subArgs[1]);
        if (choice == null) {
            messageManager.sendMessage(player, "bill-vote-fail-invalid-choice", "choice", subArgs[1]);
            return true;
        }

        billManager.playerVoteOnBill(player, bill, choice);
        return true;
    }


    private void sendBillHelp(CommandSender sender, String commandLabel) {
        String displayLabel = commandLabel.split(" ")[0];
        if (displayLabel.equalsIgnoreCase("tp") || displayLabel.equalsIgnoreCase("townypolitical") || displayLabel.equalsIgnoreCase("tpol")) {
            displayLabel += " bill";
        }
        messageManager.sendRawMessage(sender, "help-bill-header", "label", displayLabel);
        if (sender.hasPermission("townypolitical.bill.propose"))
            messageManager.sendRawMessage(sender, "help-bill-propose", "label", displayLabel);
        if (sender.hasPermission("townypolitical.bill.list"))
            messageManager.sendRawMessage(sender, "help-bill-list", "label", displayLabel);
        if (sender.hasPermission("townypolitical.bill.info"))
            messageManager.sendRawMessage(sender, "help-bill-info", "label", displayLabel);
        if (sender.hasPermission("townypolitical.bill.vote"))
            messageManager.sendRawMessage(sender, "help-bill-vote", "label", displayLabel);
        // if (sender.hasPermission("townypolitical.bill.manage")) {
        // messageManager.sendRawMessage(sender, "help-bill-cancel", "label", displayLabel);
        // messageManager.sendRawMessage(sender, "help-bill-enact", "label", displayLabel);
        // }
        messageManager.sendRawMessage(sender, "help-footer");
    }
}