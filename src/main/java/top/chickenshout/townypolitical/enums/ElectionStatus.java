// 文件名: ElectionStatus.java
// 结构位置: top/chickenshout/townypolitical/enums/ElectionStatus.java
package top.chickenshout.townypolitical.enums;

import java.util.Arrays;
import java.util.Optional;

/**
 * 选举状态枚举。
 * 定义了选举在其生命周期中可能处于的各种状态。
 */
public enum ElectionStatus {
    NONE("无选举", "当前没有正在进行的选举或未初始化"),
    PENDING_START("待开始", "选举已安排，但尚未到开始时间"), // 新增，用于调度
    REGISTRATION("候选人登记", "候选人可以报名参加选举"),
    VOTING("投票进行中", "合格选民可以投票"),
    COUNTING("计票中", "投票已结束，正在统计结果"), // 通常是一个短暂的内部状态
    AWAITING_TIE_RESOLUTION("等待平票处理", "选举出现平票，等待管理员介入或决选"), // 新增
    FINISHED("已结束", "选举结果已公布，处于公示期或已归档"),
    CANCELLED("已取消", "选举因故取消");

    private final String displayName;
    private final String description;

    ElectionStatus(String displayName, String description) {
        this.displayName = displayName;
        this.description = description;
    }

    /**
     * 获取选举状态的显示名称 (中文)。
     * @return 显示名称
     */
    public String getDisplayName() {
        return displayName;
    }

    /**
     * 获取选举状态的描述。
     * @return 描述文本
     */
    public String getDescription() {
        return description;
    }

    /**
     * 根据显示名称查找选举状态。
     * @param displayName 显示名称
     * @return 对应的 ElectionStatus Optional，如果找不到则为空
     */
    public static Optional<ElectionStatus> fromDisplayName(String displayName) {
        return Arrays.stream(values())
                .filter(status -> status.getDisplayName().equalsIgnoreCase(displayName))
                .findFirst();
    }

    /**
     * 根据枚举名 (例如 "VOTING") 查找选举状态 (忽略大小写)。
     * @param name 枚举的名称字符串
     * @return 对应的 ElectionStatus Optional，如果找不到则为空
     */
    public static Optional<ElectionStatus> fromString(String name) {
        if (name == null || name.trim().isEmpty()) {
            return Optional.empty();
        }
        try {
            return Optional.of(ElectionStatus.valueOf(name.trim().toUpperCase()));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }
}