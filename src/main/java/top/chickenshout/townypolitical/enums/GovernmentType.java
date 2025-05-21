// 文件名: GovernmentType.java
// 结构位置: top/chickenshout/townypolitical/enums/GovernmentType.java
package top.chickenshout.townypolitical.enums;

import java.util.Arrays;
import java.util.Optional;

/**
 * 国家政体枚举
 * 包含了所有支持的政体类型及其基本特性和描述。
 */
public enum GovernmentType {
    ABSOLUTE_MONARCHY("君主专制", "AbsMonarchy", "国家由单一君主完全统治，权力至高无上。所有国家行动花费显著增加。", true, false, false, false),
    CONSTITUTIONAL_MONARCHY("君主立宪制", "ConstMonarchy", "君主为国家元首，但权力受宪法和议会限制。总理为政府首脑，由议会多数党产生。", false, true, false, true),
    PARLIAMENTARY_REPUBLIC("议会制共和制", "ParlRepublic", "国家元首（总统）通常为象征性职位或由议会选举。总理为政府首脑，由议会多数党产生。", false, true, false, true),
    SEMI_PRESIDENTIAL_REPUBLIC("半总统制共和制", "SemiPresRepublic", "总统由民选产生，拥有实际行政权；总理也存在并对议会负责，形成双首长制。", false, true, true, true),
    PRESIDENTIAL_REPUBLIC("总统制共和制", "PresRepublic", "总统既是国家元首也是政府首脑，由民选产生，独立于议会行使行政权。", false, false, true, true);

    private final String displayName;
    private final String shortName;
    private final String description;
    private final boolean isAbsoluteMonarchy;
    private final boolean hasParliament;
    private final boolean hasDirectPresidentialElection;
    private final boolean allowsParties; // 政党在该政体下是否能正常参与选举等政治活动

    GovernmentType(String displayName, String shortName, String description, boolean isAbsoluteMonarchy, boolean hasParliament, boolean hasDirectPresidentialElection, boolean allowsParties) {
        this.displayName = displayName;
        this.shortName = shortName;
        this.description = description;
        this.isAbsoluteMonarchy = isAbsoluteMonarchy;
        this.hasParliament = hasParliament;
        this.hasDirectPresidentialElection = hasDirectPresidentialElection;
        this.allowsParties = allowsParties;
    }

    /**
     * 获取政体的显示名称 (中文)。
     * @return 显示名称
     */
    public String getDisplayName() {
        return displayName;
    }

    /**
     * 获取政体的简称 (用于配置或命令)。
     * @return 简称
     */
    public String getShortName() {
        return shortName;
    }

    /**
     * 获取政体的描述。
     * @return 描述文本
     */
    public String getDescription() {
        return description;
    }

    /**
     * 判断此政体是否为君主专制。
     * @return 如果是君主专制则返回 true，否则返回 false
     */
    public boolean isAbsoluteMonarchy() {
        return isAbsoluteMonarchy;
    }

    /**
     * 判断此政体是否拥有议会系统。
     * @return 如果拥有议会系统则返回 true，否则返回 false
     */
    public boolean hasParliament() {
        return hasParliament;
    }

    /**
     * 判断此政体是否有独立的总统直选。
     * @return 如果有总统直选则返回 true，否则返回 false
     */
    public boolean hasDirectPresidentialElection() {
        return hasDirectPresidentialElection;
    }

    /**
     * 判断此政体是否允许政党积极参与政治（主要影响选举和议会构成）。
     * @return 是否允许政党活动
     */
    public boolean allowsPartyPolitics() {
        return allowsParties;
    }

    /**
     * 根据显示名称查找政体类型。
     * @param displayName 显示名称
     * @return 对应的 GovernmentType Optional，如果找不到则为空
     */
    public static Optional<GovernmentType> fromDisplayName(String displayName) {
        return Arrays.stream(values())
                .filter(type -> type.getDisplayName().equalsIgnoreCase(displayName))
                .findFirst();
    }

    /**
     * 根据简称查找政体类型。
     * @param shortName 简称
     * @return 对应的 GovernmentType Optional，如果找不到则为空
     */
    public static Optional<GovernmentType> fromShortName(String shortName) {
        return Arrays.stream(values())
                .filter(type -> type.getShortName().equalsIgnoreCase(shortName))
                .findFirst();
    }

    /**
     * 根据名称（可以是显示名称、简称或枚举自身的名称如ABSOLUTE_MONARCHY）查找政体类型。
     * 查找顺序: 显示名称 -> 简称 -> 枚举名 (忽略大小写)。
     * @param name 名称字符串
     * @return 对应的 GovernmentType Optional，如果找不到则为空
     */
    public static Optional<GovernmentType> fromString(String name) {
        if (name == null || name.trim().isEmpty()) {
            return Optional.empty();
        }
        String normalizedName = name.trim();

        Optional<GovernmentType> byDisplayName = fromDisplayName(normalizedName);
        if (byDisplayName.isPresent()) {
            return byDisplayName;
        }

        Optional<GovernmentType> byShortName = fromShortName(normalizedName);
        if (byShortName.isPresent()) {
            return byShortName;
        }

        try {
            return Optional.of(GovernmentType.valueOf(normalizedName.toUpperCase().replace(" ", "_")));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }
}