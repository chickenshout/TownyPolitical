// 文件名: ElectionType.java
// 结构位置: top/chickenshout/townypolitical/enums/ElectionType.java
package top.chickenshout.townypolitical.enums;

import java.util.Arrays;
import java.util.Optional;

/**
 * 选举类型枚举。
 * 定义了插件中可能发生的几种主要选举类型。
 */
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

    /**
     * 获取选举类型的显示名称 (中文)。
     * @return 显示名称
     */
    public String getDisplayName() {
        return displayName;
    }

    /**
     * 获取选举类型的描述。
     * @return 描述文本
     */
    public String getDescription() {
        return description;
    }

    /**
     * 根据显示名称查找选举类型。
     * @param displayName 显示名称
     * @return 对应的 ElectionType Optional，如果找不到则为空
     */
    public static Optional<ElectionType> fromDisplayName(String displayName) {
        return Arrays.stream(values())
                .filter(type -> type.getDisplayName().equalsIgnoreCase(displayName))
                .findFirst();
    }

    /**
     * 根据枚举名 (例如 "PARLIAMENTARY") 查找选举类型 (忽略大小写)。
     * @param name 枚举的名称字符串
     * @return 对应的 ElectionType Optional，如果找不到则为空
     */
    public static Optional<ElectionType> fromString(String name) {
        if (name == null || name.trim().isEmpty()) {
            return Optional.empty();
        }
        try {
            return Optional.of(ElectionType.valueOf(name.trim().toUpperCase()));
        } catch (IllegalArgumentException e) {
            // 可以尝试更宽松的匹配，例如去掉下划线或匹配简称，但目前保持严格
            return Optional.empty();
        }
    }
}