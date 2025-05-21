// 文件名: PoliticalCommands.java
// 结构位置: top/chickenshout/townypolitical/commands/PoliticalCommands.java
package top.chickenshout.townypolitical.commands;

import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.jetbrains.annotations.NotNull;
import top.chickenshout.townypolitical.TownyPolitical;
import top.chickenshout.townypolitical.commands.handlers.ElectionCommandsHandler;
import top.chickenshout.townypolitical.commands.handlers.NationCommandsHandler;
import top.chickenshout.townypolitical.commands.handlers.PartyCommandsHandler;
// BillCommandsHandler 已移除
import top.chickenshout.townypolitical.utils.MessageManager;

import java.util.Arrays;
import java.util.List;

/**
 * 主命令执行器，负责接收插件的所有命令并分发到相应的处理器。
 * 支持 /townypolitical (及其别名) 和可能的快捷别名 (如 /tparty)。
 */
public class PoliticalCommands implements CommandExecutor {

    private final TownyPolitical plugin;
    private final MessageManager messageManager;

    // 子命令处理器实例
    private final PartyCommandsHandler partyCommandsHandler;
    private final NationCommandsHandler nationCommandsHandler;
    private final ElectionCommandsHandler electionCommandsHandler;
    // private final BillCommandsHandler billCommandsHandler; // 已移除

    public PoliticalCommands(TownyPolitical plugin) {
        this.plugin = plugin;
        this.messageManager = plugin.getMessageManager();

        // 初始化所有子命令处理器
        this.partyCommandsHandler = new PartyCommandsHandler(plugin);
        this.nationCommandsHandler = new NationCommandsHandler(plugin);
        this.electionCommandsHandler = new ElectionCommandsHandler(plugin);
        // this.billCommandsHandler = new BillCommandsHandler(plugin); // 已移除
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        String commandName = command.getName().toLowerCase(); // 获取 plugin.yml 中定义的命令名

        // 1. 处理主命令 /townypolitical (及其别名 tp, tpol, political)
        if (commandName.equals("townypolitical") || commandName.equals("tp") || commandName.equals("tpol") || commandName.equals("political")) {
            if (args.length == 0) {
                sendGeneralHelp(sender, label); // 如果只有 /tp，显示主帮助
                return true;
            }

            String mainGroup = args[0].toLowerCase();
            String[] subCommandArgs = Arrays.copyOfRange(args, 1, args.length); // group 后面的所有参数

            switch (mainGroup) {
                case "party": case "p":
                    return partyCommandsHandler.handleCommand(sender, label + " " + mainGroup, subCommandArgs);
                case "nation": case "n":
                    return nationCommandsHandler.handleCommand(sender, label + " " + mainGroup, subCommandArgs);
                case "election": case "e":
                    return electionCommandsHandler.handle(sender, label + " " + mainGroup, subCommandArgs);
                // case "bill": case "b": // 已移除
                //     return billCommandsHandler.handle(sender, label + " " + mainGroup, subCommandArgs);
                case "reload":
                    if (sender.hasPermission("townypolitical.command.reload")) {
                        if (plugin.reloadPlugin()) {
                            messageManager.sendMessage(sender, "plugin-reloaded-success");
                        } else {
                            messageManager.sendMessage(sender, "plugin-reloaded-fail");
                        }
                    } else {
                        messageManager.sendMessage(sender, "error-no-permission");
                    }
                    return true;
                case "help": case "?":
                    sendGeneralHelp(sender, label);
                    return true;
                case "info": case "version":
                    sendPluginInfo(sender);
                    return true;
                default:
                    messageManager.sendMessage(sender, "error-unknown-main-command", "label", label, "command", mainGroup);
                    sendGeneralHelp(sender, label);
                    return true;
            }
        }

        // 2. 处理快捷别名命令 (例如 /tparty, /tnation, /telection)
        // args 数组此时直接是子命令及其参数 (例如 /tparty create Name -> args = ["create", "Name"])
        // label 是玩家输入的快捷别名 (例如 "tparty")
        if (commandName.equals("tparty") || commandName.equals("party")) { // "party" 作为 plugin.yml 中的 alias
            return partyCommandsHandler.handleCommand(sender, label, args);
        }
        // 可以为 /tnation, /telection (如果在plugin.yml中定义了这些别名) 添加类似的 else if:
        /*
        else if (commandName.equals("tnation") || commandName.equals("nationcmd")) { // 假设 "nationcmd" 是 /tp nation 的别名
            return nationCommandsHandler.handle(sender, label, args);
        }
        else if (commandName.equals("telection") || commandName.equals("electioncmd")) {
            return electionCommandsHandler.handle(sender, label, args);
        }
        */

        // 如果命令走到了这里，说明 plugin.yml 中的命令配置可能有问题，或者有未处理的命令
        plugin.getLogger().warning("PoliticalCommands received an unhandled command label: " + label + " (from command: " + command.getName() + ")");
        return false; // 返回 false 通常会显示 plugin.yml 中定义的 usage
    }

    /**
     * 发送插件的通用帮助信息。
     * @param sender 命令发送者
     * @param label 玩家实际输入的命令标签 (如 /tp)
     */
    private void sendGeneralHelp(CommandSender sender, String label) {
        messageManager.sendRawMessage(sender, "help-header", "plugin_name", plugin.getDescription().getName());
        messageManager.sendRawMessage(sender, "help-command-format", "label", label); // 例如 "/tp <主命令> ..."

        // 根据权限显示可用命令组
        if (sender.hasPermission("townypolitical.command.party.base") || sender.hasPermission("townypolitical.admin")) // 假设有基础权限
            messageManager.sendRawMessage(sender, "help-group-party", "label", label);
        if (sender.hasPermission("townypolitical.nation.governmentinfo") || sender.hasPermission("townypolitical.admin")) // 使用一个通用的 nation info 权限
            messageManager.sendRawMessage(sender, "help-group-nation", "label", label);
        if (sender.hasPermission("townypolitical.election.info") || sender.hasPermission("townypolitical.admin")) // 使用一个通用的 election info 权限
            messageManager.sendRawMessage(sender, "help-group-election", "label", label);
        // Bill group removed

        if (sender.hasPermission("townypolitical.command.reload")) {
            messageManager.sendRawMessage(sender, "help-command-reload", "label", label);
        }
        messageManager.sendRawMessage(sender, "help-command-info", "label", label); // info/version 通常所有人可用
        messageManager.sendRawMessage(sender, "help-footer");
    }

    /**
     * 发送插件的基本信息 (名称、版本、作者)。
     * @param sender 命令发送者
     */
    private void sendPluginInfo(CommandSender sender){
        messageManager.sendRawMessage(sender, "plugin-info-name", "name", plugin.getDescription().getName());
        messageManager.sendRawMessage(sender, "plugin-info-version", "version", plugin.getDescription().getVersion());
        List<String> authors = plugin.getDescription().getAuthors();
        messageManager.sendRawMessage(sender, "plugin-info-author", "author", authors != null && !authors.isEmpty() ? String.join(", ", authors) : "N/A");
        messageManager.sendRawMessage(sender, "plugin-info-description", "description", plugin.getDescription().getDescription() != null ? plugin.getDescription().getDescription() : "N/A");
    }
}