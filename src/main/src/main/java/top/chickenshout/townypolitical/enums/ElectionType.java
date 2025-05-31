// 文件名: ElectionType.java
// 结构位置: top/chickenshout/townypolitical/enums/ElectionType.java
package top.chickenshout.townypolitical.enums;

import java.util.Arrays;
import java.util.Optional;

public enum ElectionType {
    PARLIAMENTARY("议会选举", "选出议会成员，多数党领袖通常成为政府首脑（总理）"),
    PRESIDENTIAL("总统选举", "直接选举国家元首（总统）"),
    PARTY_LEADER("政党领袖选举", "选举政党内部的领导人");

    private final String displayName;
    private final String description;

    ElectionType(String displayName, String description) {
        this.displayName = displayName;
        this.description = description;
    }

    public String getDisplayName() {
        return displayName;
    }

    public String getDescription() {
        return description;
    }

    public static Optional<ElectionType> fromDisplayName(String displayName) {
        if (displayName == null) return Optional.empty();
        return Arrays.stream(values())
                .filter(type -> type.getDisplayName().equalsIgnoreCase(displayName.trim()))
                .findFirst();
    }

    /**
     * 根据名称（可以是显示名称、枚举名如 "PARLIAMENTARY"、或小写的 "parliament"）查找选举类型。
     * 查找顺序: 精确枚举名 (忽略大小写) -> 显示名称 (忽略大小写)。
     * @param name 名称字符串
     * @return 对应的 ElectionType Optional，如果找不到则为空
     */
    public static Optional<ElectionType> fromString(String name) {
        if (name == null || name.trim().isEmpty()) {
            return Optional.empty();
        }
        String normalizedName = name.trim();

        // 1. 尝试直接匹配枚举常量名 (忽略大小写)
        try {
            return Optional.of(ElectionType.valueOf(normalizedName.toUpperCase()));
        } catch (IllegalArgumentException ignored) {
            // 不是标准的枚举名，继续尝试其他匹配
        }

        // 2. 尝试匹配显示名称 (忽略大小写)
        Optional<ElectionType> byDisplayName = fromDisplayName(normalizedName);
        if (byDisplayName.isPresent()) {
            return byDisplayName;
        }

        // 3. (可选) 尝试匹配小写的枚举名 (如果玩家经常这样输入)
        // 虽然 toUpperCase() 已经覆盖了这种情况，但明确写出来也可以
        for (ElectionType type : values()) {
            if (type.name().equalsIgnoreCase(normalizedName)) {
                return Optional.of(type);
            }
        }

        return Optional.empty(); // 所有尝试都失败
    }
}