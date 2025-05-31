// 文件名: PartyRole.java
// 结构位置: top/chickenshout/townypolitical/enums/PartyRole.java
package top.chickenshout.townypolitical.enums;

import java.util.Arrays;
import java.util.Optional;

/**
 * 政党成员角色枚举
 * 定义了玩家在政党中可以扮演的角色及其权限级别。
 */
public enum PartyRole {
    LEADER("领导人", "领袖", 3),       // 政党领导人，拥有最高权限
    ADMIN("管理员", "管理", 2),     // 政党管理员，拥有管理权限，低于领导人
    MEMBER("成员", "党员", 1),      // 普通党员
    APPLICANT("申请者", "申请中", 0); // 申请加入政党的人，尚未被批准

    private final String displayName; // 用于显示的中文名称
    private final String shortName;   // 用于配置或命令的简称
    private final int permissionLevel; // 权限级别，数字越大权限越高

    PartyRole(String displayName, String shortName, int permissionLevel) {
        this.displayName = displayName;
        this.shortName = shortName;
        this.permissionLevel = permissionLevel;
    }

    /**
     * 获取角色的显示名称 (中文)。
     * @return 显示名称
     */
    public String getDisplayName() {
        return displayName;
    }

    /**
     * 获取角色的简称 (用于配置或命令)。
     * @return 简称
     */
    public String getShortName() {
        return shortName;
    }

    /**
     * 获取角色的权限级别。
     * @return 权限级别
     */
    public int getPermissionLevel() {
        return permissionLevel;
    }

    /**
     * 判断当前角色是否至少拥有指定角色的权限。
     * 例如，领导人拥有管理员的权限，管理员拥有成员的权限。
     * @param requiredRole 需要比较的权限角色
     * @return 如果当前角色权限大于或等于指定角色权限，则返回 true
     */
    public boolean hasPermissionOf(PartyRole requiredRole) {
        if (requiredRole == null) return true; // 如果不需要特定权限，则认为拥有
        return this.permissionLevel >= requiredRole.permissionLevel;
    }

    /**
     * 根据显示名称查找政党角色。
     * @param displayName 显示名称
     * @return 对应的 PartyRole Optional，如果找不到则为空
     */
    public static Optional<PartyRole> fromDisplayName(String displayName) {
        return Arrays.stream(values())
                .filter(role -> role.getDisplayName().equalsIgnoreCase(displayName))
                .findFirst();
    }

    /**
     * 根据简称查找政党角色。
     * @param shortName 简称
     * @return 对应的 PartyRole Optional，如果找不到则为空
     */
    public static Optional<PartyRole> fromShortName(String shortName) {
        return Arrays.stream(values())
                .filter(role -> role.getShortName().equalsIgnoreCase(shortName))
                .findFirst();
    }

    /**
     * 根据名称（可以是显示名称、简称或枚举自身的名称如LEADER）查找政党角色。
     * 查找顺序: 显示名称 -> 简称 -> 枚举名 (忽略大小写)。
     * @param name 名称字符串
     * @return 对应的 PartyRole Optional，如果找不到则为空
     */
    public static Optional<PartyRole> fromString(String name) {
        if (name == null || name.trim().isEmpty()) {
            return Optional.empty();
        }
        String normalizedName = name.trim();

        Optional<PartyRole> byDisplayName = fromDisplayName(normalizedName);
        if (byDisplayName.isPresent()) {
            return byDisplayName;
        }

        Optional<PartyRole> byShortName = fromShortName(normalizedName);
        if (byShortName.isPresent()) {
            return byShortName;
        }

        try {
            return Optional.of(PartyRole.valueOf(normalizedName.toUpperCase()));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }
}