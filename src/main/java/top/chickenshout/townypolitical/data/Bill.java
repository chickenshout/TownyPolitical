// 文件名: Bill.java
// 结构位置: top/chickenshout/townypolitical/data/Bill.java
package top.chickenshout.townypolitical.data;

import top.chickenshout.townypolitical.enums.BillStatus;
import top.chickenshout.townypolitical.enums.VoteChoice; // 需要导入

import java.util.Map;
import java.util.UUID;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 代表一个法案。
 */
public class Bill {
    private final UUID billId;                 // 法案的唯一ID
    private final UUID nationId;               // 法案所属国家的UUID
    private final UUID proposerId;             // 提案人的玩家UUID
    private String proposerNameCache;      // 提案人名称缓存
    private String title;                  // 法案标题
    private String content;                // 法案内容 (纯文本)
    private BillStatus status;             // 法案当前状态
    private long proposalTimestamp;        // 提案时间戳
    private long votingEndTimestamp;       // (如果需要议会投票) 投票截止时间戳
    private long enactmentTimestamp;       // (如果已颁布) 颁布时间戳

    // 存储议员投票记录 <议员PlayerUUID, VoteChoice>
    // 仅在需要议会投票的政体下使用
    private final Map<UUID, VoteChoice> votes;

    public Bill(UUID billId, UUID nationId, UUID proposerId, String title, String content) {
        if (billId == null || nationId == null || proposerId == null || title == null || title.trim().isEmpty() || content == null || content.trim().isEmpty()) {
            throw new IllegalArgumentException("Bill ID, Nation ID, Proposer ID, Title, and Content cannot be null or empty.");
        }
        this.billId = billId;
        this.nationId = nationId;
        this.proposerId = proposerId;
        this.title = title.trim();
        this.content = content.trim();
        this.status = BillStatus.PROPOSED; // 初始状态
        this.proposalTimestamp = System.currentTimeMillis();
        this.votes = new ConcurrentHashMap<>();
    }

    // Getters
    public UUID getBillId() {
        return billId;
    }

    public UUID getNationId() {
        return nationId;
    }

    public UUID getProposerId() {
        return proposerId;
    }

    public String getProposerNameCache() {
        return proposerNameCache;
    }

    public String getTitle() {
        return title;
    }

    public String getContent() {
        return content;
    }

    public BillStatus getStatus() {
        return status;
    }

    public long getProposalTimestamp() {
        return proposalTimestamp;
    }

    public long getVotingEndTimestamp() {
        return votingEndTimestamp;
    }

    public long getEnactmentTimestamp() {
        return enactmentTimestamp;
    }

    public Map<UUID, VoteChoice> getVotes() {
        return new ConcurrentHashMap<>(votes); // 返回副本以保护内部状态
    }

    // Setters
    public void setProposerNameCache(String proposerNameCache) {
        this.proposerNameCache = proposerNameCache;
    }

    public void setTitle(String title) {
        if (title == null || title.trim().isEmpty()) throw new IllegalArgumentException("Title cannot be null or empty.");
        this.title = title.trim();
    }

    public void setContent(String content) {
        if (content == null || content.trim().isEmpty()) throw new IllegalArgumentException("Content cannot be null or empty.");
        this.content = content.trim();
    }

    public void setStatus(BillStatus status) {
        if (status == null) throw new IllegalArgumentException("Bill status cannot be null.");
        this.status = status;
    }

    public void setVotingEndTimestamp(long votingEndTimestamp) {
        this.votingEndTimestamp = votingEndTimestamp;
    }

    public void setEnactmentTimestamp(long enactmentTimestamp) {
        this.enactmentTimestamp = enactmentTimestamp;
    }

    // Vote management
    public void addVote(UUID voterId, VoteChoice choice) {
        if (voterId == null || choice == null) return;
        if (this.status == BillStatus.VOTING) { // 只能在投票阶段投票
            this.votes.put(voterId, choice);
        }
    }

    public void clearVotes() {
        this.votes.clear();
    }

    public int getYeaVotes() {
        return (int) votes.values().stream().filter(v -> v == VoteChoice.YEA).count();
    }

    public int getNayVotes() {
        return (int) votes.values().stream().filter(v -> v == VoteChoice.NAY).count();
    }

    public int getAbstainVotes() {
        return (int) votes.values().stream().filter(v -> v == VoteChoice.ABSTAIN).count();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        Bill bill = (Bill) o;
        return billId.equals(bill.billId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(billId);
    }

    @Override
    public String toString() {
        return "Bill{" +
                "billId=" + billId +
                ", nationId=" + nationId +
                ", title='" + title + '\'' +
                ", status=" + status +
                '}';
    }

    public void setProposalTimestamp(long proposalTimestamp) {
        this.proposalTimestamp = proposalTimestamp;
    }
}