// 文件名: BillStatus.java
// 结构位置: top/chickenshout/townypolitical/enums/BillStatus.java
package top.chickenshout.townypolitical.enums;

import java.util.Arrays;
import java.util.Optional;

/**
 * 法案状态枚举。
 */
public enum BillStatus {
    PROPOSED("提案中", "法案已提出，等待处理或投票"),
    VOTING("议会投票中", "法案正在议会成员中进行投票"),
    PASSED_BY_PARLIAMENT("议会通过", "法案已通过议会投票，等待最终颁布"),
    REJECTED_BY_PARLIAMENT("议会否决", "法案未通过议会投票"),
    ENACTED("已颁布", "法案已正式颁布生效"),
    REPEALED("已废除", "法案已被废除"), // 未来可能用到
    CANCELLED("已取消/撤回", "法案在颁布前被取消或撤回");

    private final String displayName;
    private final String description;

    BillStatus(String displayName, String description) {
        this.displayName = displayName;
        this.description = description;
    }

    public String getDisplayName() {
        return displayName;
    }

    public String getDescription() {
        return description;
    }

    public static Optional<BillStatus> fromString(String name) {
        if (name == null || name.trim().isEmpty()) {
            return Optional.empty();
        }
        try {
            return Optional.of(BillStatus.valueOf(name.trim().toUpperCase()));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }
}