# TownyPolitical
A political plugin for Towny servers , incorporates political systems, parties, and elections to enhance the gaming experience.
**TownyPolitical** 是适用于Towny服务器的一个的政治插件，加入了政体、政党与选举，增强游玩体验。

---

## 🌟 主要功能

*   **政党模块 (Party System)**:
    *   玩家可以花费游戏内货币创建和命名政党。
    *   邀请或接受其他玩家加入政党。
    *   设立政党领导人（可选举或禅让）和管理员。
    *   完善的党员管理功能（踢出、同意申请等）。
*   **政体模块 (Government System)**:
    *   允许 Towny 国家在多种政体中进行选择（例如：君主专制、君主立宪制、议会制共和制、半总统制共和制、总统制共和制）。
    *   特定政体（如君主专制）会有特殊的游戏内效果（例如，增加国家操作花费并通过醒目广播宣告）。
*   **选举模块 (Election System)**:
    *   根据国家选择的政体，定期举行议会选举和/或总统直接选举。
    *   政党可以决定是否参与特定国家的选举。
    *   严格遵循现实中相应政体的选举逻辑和权力交接。
*   **议会模块 (Parliament System)**:
    *   除君主专制外，其他政体的国家拥有议会系统。
    *   议会中各党派的席位分布（未来可通过GUI菜单清晰呈现，当前通过命令查看）。
    *   议会拥有特定权力，例如在某些政体下弹劾政府首脑或被政府首脑解散（根据政体逻辑）。
*   **兼容性**:
    *   适用于 "Towny" 插件。
    *   兼容 "TheNewEconomy" 经济插件及其前置 "Vault"。
*   **其他特性**:
    *   完整的中文支持。
    *   所有指令均提供 Tab 补全。
    *   高度可配置的 `config.yml` 和消息文件 `messages_zh_CN.yml`。
    *   热重载支持。

---

## 🎮 如何开始

**主要命令前缀**: `/tp`, `/townypolitical`, `/tparty` (政党快捷方式)

**常用命令示例**:
*   `/tp help` - 显示主帮助信息。
*   `/tp party create <政党名称>` - 创建一个政党。
*   `/tp party apply <政党名称>` - 申请加入一个政党。
*   `/tp party info [政党名称]` - 查看政党信息。
*   `/tp nation info [国家名称]` - 查看国家政治信息。
*   `/tp election info [类型] [国家/政党]` - 查看选举信息。
*   ... 更多命令请查看详细的命令列表或使用游戏内帮助。

---

## 🔧 安装与配置

1.  **前置插件**:
    *   [PaperMC](https://papermc.io/) (或兼容的 Paper 分支) 1.19.4 服务器。
    *   [Towny Advanced](https://github.com/TownyAdvanced/Towny) (已测试v0.100.4.0)。
    *   [Vault](https://www.spigotmc.org/resources/vault.34315/)。
    *   一个 Vault 支持的经济插件，例如 [TheNewEconomy](https://www.spigotmc.org/resources/the-new-economy.7859/) (或 EssentialsX Econ, CMI Econ 等)。
2.  **下载**: 从 [Releases 页面](https://github.com/chickenshout/TownyPolitical/releases) 下载最新的 `TownyPolitical_xxx.jar` 文件。
3.  **安装**: 将下载的 JAR 文件放入服务器的 `plugins` 文件夹。
4.  **启动服务器**: 首次启动会自动生成配置文件 (`config.yml`) 和消息文件 (`messages.yml`, 默认为中文)。
5.  **配置**:
    *   打开 `plugins/TownyPolitical/config.yml` 文件。
    *   根据文件内的注释，仔细调整各项设置，例如经济花费、选举周期、政党名称规则等。
    *   打开 `plugins/TownyPolitical/messages.yml` 文件，可以自定义所有插件输出给玩家的消息。
6.  **权限**: 使用你喜欢的权限管理插件 (如 LuckPerms) 为玩家和不同组分配合适的权限节点。详细权限列表请参见 `plugin.yml` 或下方权限部分。
7.  **重载**: 修改配置后，可以在游戏内使用 `/tp reload` (需要 `townypolitical.command.reload` 权限) 或重启服务器使更改生效。

---

## 🛠️ 权限节点（部分）

*   `townypolitical.*` - 赋予所有 TownyPolitical 权限。
*   `townypolitical.admin` - 赋予管理权限 (重载, 管理选举等)。
*   **政党相关**:
    *   `townypolitical.party.create`
    *   `townypolitical.party.info`
    *   ... (其他政党权限)
*   **国家相关**:
    *   `townypolitical.nation.setgovernment`
    *   `townypolitical.nation.governmentinfo`
    *   ... (其他国家权限)
*   **选举相关**:
    *   `townypolitical.election.vote`
    *   `townypolitical.election.registercandidate`
    *   ... (其他选举权限)

---
