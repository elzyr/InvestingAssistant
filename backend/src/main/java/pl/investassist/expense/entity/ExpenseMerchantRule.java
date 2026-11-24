package pl.investassist.expense.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import pl.investassist.expense.enums.ExpenseCategory;
import pl.investassist.expense.enums.FlowKind;
import pl.investassist.expense.enums.RuleMatchType;

@Entity
@Table(
    name = "expense_merchant_rule",
    uniqueConstraints = @UniqueConstraint(
        name = "uq_expense_rule_user_key",
        columnNames = {"user_id", "match_type", "match_key"}))
public class ExpenseMerchantRule {

  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  private UUID id;

  @Column(name = "user_id", nullable = false)
  private UUID userId;

  @Enumerated(EnumType.STRING)
  @Column(name = "match_type", nullable = false, length = 16)
  private RuleMatchType matchType;

  @Column(name = "match_key", nullable = false, length = 160)
  private String matchKey;

  @Column(name = "display_label", nullable = false, length = 160)
  private String displayLabel;

  @Enumerated(EnumType.STRING)
  @Column(name = "category", nullable = false, length = 32)
  private ExpenseCategory category;

  @Enumerated(EnumType.STRING)
  @Column(name = "flow_kind", nullable = false, length = 32)
  private FlowKind flowKind;

  @Column(name = "source", nullable = false, length = 32)
  private String source;

  @Column(name = "hit_count", nullable = false)
  private int hitCount = 1;

  @Column(name = "created_at", nullable = false, updatable = false)
  private LocalDateTime createdAt;

  @Column(name = "updated_at", nullable = false)
  private LocalDateTime updatedAt;

  @PrePersist
  void ensureTimestamps() {
    LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
    if (createdAt == null) {
      createdAt = now;
    }
    if (updatedAt == null) {
      updatedAt = now;
    }
  }

  @PreUpdate
  void touchUpdatedAt() {
    updatedAt = LocalDateTime.now(ZoneOffset.UTC);
  }

  public UUID getId() {
    return id;
  }

  public void setId(UUID id) {
    this.id = id;
  }

  public UUID getUserId() {
    return userId;
  }

  public void setUserId(UUID userId) {
    this.userId = userId;
  }

  public RuleMatchType getMatchType() {
    return matchType;
  }

  public void setMatchType(RuleMatchType matchType) {
    this.matchType = matchType;
  }

  public String getMatchKey() {
    return matchKey;
  }

  public void setMatchKey(String matchKey) {
    this.matchKey = matchKey;
  }

  public String getDisplayLabel() {
    return displayLabel;
  }

  public void setDisplayLabel(String displayLabel) {
    this.displayLabel = displayLabel;
  }

  public ExpenseCategory getCategory() {
    return category;
  }

  public void setCategory(ExpenseCategory category) {
    this.category = category;
  }

  public FlowKind getFlowKind() {
    return flowKind;
  }

  public void setFlowKind(FlowKind flowKind) {
    this.flowKind = flowKind;
  }

  public String getSource() {
    return source;
  }

  public void setSource(String source) {
    this.source = source;
  }

  public int getHitCount() {
    return hitCount;
  }

  public void setHitCount(int hitCount) {
    this.hitCount = hitCount;
  }

  public LocalDateTime getCreatedAt() {
    return createdAt;
  }

  public LocalDateTime getUpdatedAt() {
    return updatedAt;
  }

  public void setUpdatedAt(LocalDateTime updatedAt) {
    this.updatedAt = updatedAt;
  }
}
