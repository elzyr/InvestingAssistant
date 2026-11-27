package pl.investassist.expense.entity;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import pl.investassist.expense.enums.ClassificationSource;
import pl.investassist.expense.enums.ExpenseCategory;
import pl.investassist.expense.enums.FlowKind;

@Entity
@Table(
    name = "expense_transaction",
    uniqueConstraints = @UniqueConstraint(
        name = "uq_expense_tx_user_fp",
        columnNames = {"user_id", "fingerprint"}))
public class ExpenseTransaction {

  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  private UUID id;

  @Version
  @Column(name = "version", nullable = false)
  private Long version = 0L;

  @Column(name = "user_id", nullable = false)
  private UUID userId;

  @Column(name = "statement_id", nullable = false)
  private UUID statementId;

  @Column(name = "tx_date", nullable = false)
  private LocalDate txDate;

  @Column(name = "booking_date")
  private LocalDate bookingDate;

  @Column(name = "raw_description", nullable = false, columnDefinition = "TEXT")
  private String rawDescription;

  @Column(name = "merchant_key", nullable = false, length = 160)
  private String merchantKey;

  @Column(name = "merchant_display", nullable = false, length = 160)
  private String merchantDisplay;

  @Column(name = "phrase_key", length = 160)
  private String phraseKey;

  @Column(name = "amount", nullable = false, precision = 18, scale = 2)
  private BigDecimal amount;

  @Column(name = "currency", nullable = false, length = 8)
  private String currency;

  @Column(name = "base_amount", nullable = false, precision = 18, scale = 2)
  private BigDecimal baseAmount;

  @Column(name = "base_currency", nullable = false, length = 8)
  private String baseCurrency;

  @Column(name = "fx_rate", nullable = false, precision = 18, scale = 8)
  private BigDecimal fxRate = BigDecimal.ONE;

  @Column(name = "fx_degraded", nullable = false)
  private boolean fxDegraded;

  @Enumerated(EnumType.STRING)
  @Column(name = "flow_kind", nullable = false, length = 32)
  private FlowKind flowKind;

  @Enumerated(EnumType.STRING)
  @Column(name = "category", nullable = false, length = 32)
  private ExpenseCategory category;

  @Enumerated(EnumType.STRING)
  @Column(name = "classification_source", nullable = false, length = 32)
  private ClassificationSource classificationSource;

  @Column(name = "confidence", nullable = false, precision = 4, scale = 2)
  private BigDecimal confidence;

  @Column(name = "is_recurring", nullable = false)
  private boolean recurring;

  @Column(name = "excluded", nullable = false)
  private boolean excluded;

  @Column(name = "fingerprint", nullable = false, length = 64)
  private String fingerprint;

  @Column(name = "user_note", length = 255)
  private String userNote;

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

  public UUID getStatementId() {
    return statementId;
  }

  public void setStatementId(UUID statementId) {
    this.statementId = statementId;
  }

  public LocalDate getTxDate() {
    return txDate;
  }

  public void setTxDate(LocalDate txDate) {
    this.txDate = txDate;
  }

  public LocalDate getBookingDate() {
    return bookingDate;
  }

  public void setBookingDate(LocalDate bookingDate) {
    this.bookingDate = bookingDate;
  }

  public String getRawDescription() {
    return rawDescription;
  }

  public void setRawDescription(String rawDescription) {
    this.rawDescription = rawDescription;
  }

  public String getMerchantKey() {
    return merchantKey;
  }

  public void setMerchantKey(String merchantKey) {
    this.merchantKey = merchantKey;
  }

  public String getMerchantDisplay() {
    return merchantDisplay;
  }

  public void setMerchantDisplay(String merchantDisplay) {
    this.merchantDisplay = merchantDisplay;
  }

  public String getPhraseKey() {
    return phraseKey;
  }

  public void setPhraseKey(String phraseKey) {
    this.phraseKey = phraseKey;
  }

  public BigDecimal getAmount() {
    return amount;
  }

  public void setAmount(BigDecimal amount) {
    this.amount = amount;
  }

  public String getCurrency() {
    return currency;
  }

  public void setCurrency(String currency) {
    this.currency = currency;
  }

  public BigDecimal getBaseAmount() {
    return baseAmount;
  }

  public void setBaseAmount(BigDecimal baseAmount) {
    this.baseAmount = baseAmount;
  }

  public String getBaseCurrency() {
    return baseCurrency;
  }

  public void setBaseCurrency(String baseCurrency) {
    this.baseCurrency = baseCurrency;
  }

  public BigDecimal getFxRate() {
    return fxRate;
  }

  public void setFxRate(BigDecimal fxRate) {
    this.fxRate = fxRate;
  }

  public boolean isFxDegraded() {
    return fxDegraded;
  }

  public void setFxDegraded(boolean fxDegraded) {
    this.fxDegraded = fxDegraded;
  }

  public FlowKind getFlowKind() {
    return flowKind;
  }

  public void setFlowKind(FlowKind flowKind) {
    this.flowKind = flowKind;
  }

  public ExpenseCategory getCategory() {
    return category;
  }

  public void setCategory(ExpenseCategory category) {
    this.category = category;
  }

  public ClassificationSource getClassificationSource() {
    return classificationSource;
  }

  public void setClassificationSource(ClassificationSource classificationSource) {
    this.classificationSource = classificationSource;
  }

  public BigDecimal getConfidence() {
    return confidence;
  }

  public void setConfidence(BigDecimal confidence) {
    this.confidence = confidence;
  }

  public boolean isRecurring() {
    return recurring;
  }

  public void setRecurring(boolean recurring) {
    this.recurring = recurring;
  }

  public boolean isExcluded() {
    return excluded;
  }

  public void setExcluded(boolean excluded) {
    this.excluded = excluded;
  }

  public String getFingerprint() {
    return fingerprint;
  }

  public void setFingerprint(String fingerprint) {
    this.fingerprint = fingerprint;
  }

  public String getUserNote() {
    return userNote;
  }

  public void setUserNote(String userNote) {
    this.userNote = userNote;
  }

  public LocalDateTime getCreatedAt() {
    return createdAt;
  }

  public LocalDateTime getUpdatedAt() {
    return updatedAt;
  }
}
