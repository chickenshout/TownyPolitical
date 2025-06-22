// 文件名: VoteChoice.java
// 结构位置: top/chickenshout/townypolitical/enums/VoteChoice.java
package top.chickenshout.townypolitical.enums;

public enum VoteChoice {
    YEA("赞成"), // 同意
    NAY("反对"), // 不同意
    ABSTAIN("弃权"); // 不发表意见

    private final String displayName;

    VoteChoice(String displayName) {
        this.displayName = displayName;
    }

    public String getDisplayName() {
        return displayName;
    }

    public static VoteChoice fromString(String value) {
        if (value == null) return null;
        String upperValue = value.toUpperCase();
        for (VoteChoice choice : values()) {
            if (choice.name().equals(upperValue) || choice.getDisplayName().equalsIgnoreCase(value)) {
                return choice;
            }
        }
        return null;
    }
}