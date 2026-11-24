package pl.investassist.expense.entity;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import pl.investassist.expense.enums.BankProfile;
import pl.investassist.expense.enums.ReconciliationStatus;
import pl.investassist.expense.enums.StatementFormat;

@Entity
@Table(name = "expense_statement")
public class ExpenseStatement {

  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  private UUID id;

  @Column(name = "user_id", nullable = false)
  private UUID userId;

  @Column(name = "file_name", nullable = false, length = 255)
  private String fileName;

  @Column(name = "file_hash_sha256", nullable = false, length = 64)
  private String fileHashSha256;

  @Enumerated(EnumType.STRING)
  @Column(name = "format", nullable = false, length = 16)
  private StatementFormat format;

  @Enumerated(EnumType.STRING)
  @Column(name = "bank_profile", nullable = false, length = 32)
  private BankProfile bankProfile;

  @Column(name = "account_label", length = 120)
  private String accountLabel;

  @Column(name = "currency", nullable = false, length = 8)
  private String currency;

  @Column(name = "period_start")
  private LocalDate periodStart;

  @Column(name = "period_end")
  private LocalDate periodEnd;

  @Column(name = "opening_balance", precision = 18, scale = 2)
  private BigDecimal openingBalance;

  @Column(name = "closing_balance", precision = 18, scale = 2)
  private BigDecimal closingBalance;

  @Column(name = "calculated_inflows", nullable = false, precision = 18, scale = 2)
  private BigDecimal calculatedInflows = BigDecimal.ZERO;

  @Column(name = "calculated_outflows", nullable = false, precision = 18, scale = 2)
  private BigDecimal calculatedOutflows = BigDecimal.ZERO;

  @Enumerated(EnumType.STRING)
  @Column(name = "reconciliation_status", nullable = false, length = 20)
  private ReconciliationStatus reconciliationStatus;

  @Column(name = "reconciliation_delta", precision = 18, scale = 2)
  private BigDecimal reconciliationDelta;

  @Column(name = "parsed_count", nullable = false)
  private int parsedCount;

  @Column(name = "skipped_duplicates", nullable = false)
  private int skippedDuplicates;

  @Column(name = "created_at", nullable = false, updatable = false)
  private LocalDateTime createdAt;

  @PrePersist
  void ensureTimestamp() {
    if (createdAt == null) {
      createdAt = LocalDateTime.now(ZoneOffset.UTC);
    }
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

  public String getFileName() {
    return fileName;
  }

  public void setFileName(String fileName) {
    this.fileName = fileName;
  }

  public String getFileHashSha256() {
    return fileHashSha256;
  }

  public void setFileHashSha256(String fileHashSha256) {
    this.fileHashSha256 = fileHashSha256;
  }

  public StatementFormat getFormat() {
    return format;
  }

  public void setFormat(StatementFormat format) {
    this.format = format;
  }

  public BankProfile getBankProfile() {
    return bankProfile;
  }

  public void setBankProfile(BankProfile bankProfile) {
    this.bankProfile = bankProfile;
  }

  public String getAccountLabel() {
    return accountLabel;
  }

  public void setAccountLabel(String accountLabel) {
    this.accountLabel = accountLabel;
  }

  public String getCurrency() {
    return currency;
  }

  public void setCurrency(String currency) {
    this.currency = currency;
  }

  public LocalDate getPeriodStart() {
    return periodStart;
  }

  public void setPeriodStart(LocalDate periodStart) {
    this.periodStart = periodStart;
  }

  public LocalDate getPeriodEnd() {
    return periodEnd;
  }

  public void setPeriodEnd(LocalDate periodEnd) {
    this.periodEnd = periodEnd;
  }

  public BigDecimal getOpeningBalance() {
    return openingBalance;
  }

  public void setOpeningBalance(BigDecimal openingBalance) {
    this.openingBalance = openingBalance;
  }

  public BigDecimal getClosingBalance() {
    return closingBalance;
  }

  public void setClosingBalance(BigDecimal closingBalance) {
    this.closingBalance = closingBalance;
  }

  public BigDecimal getCalculatedInflows() {
    return calculatedInflows;
  }

  public void setCalculatedInflows(BigDecimal calculatedInflows) {
    this.calculatedInflows = calculatedInflows;
  }

  public BigDecimal getCalculatedOutflows() {
    return calculatedOutflows;
  }

  public void setCalculatedOutflows(BigDecimal calculatedOutflows) {
    this.calculatedOutflows = calculatedOutflows;
  }

  public ReconciliationStatus getReconciliationStatus() {
    return reconciliationStatus;
  }

  public void setReconciliationStatus(ReconciliationStatus reconciliationStatus) {
    this.reconciliationStatus = reconciliationStatus;
  }

  public BigDecimal getReconciliationDelta() {
    return reconciliationDelta;
  }

  public void setReconciliationDelta(BigDecimal reconciliationDelta) {
    this.reconciliationDelta = reconciliationDelta;
  }

  public int getParsedCount() {
    return parsedCount;
  }

  public void setParsedCount(int parsedCount) {
    this.parsedCount = parsedCount;
  }

  public int getSkippedDuplicates() {
    return skippedDuplicates;
  }

  public void setSkippedDuplicates(int skippedDuplicates) {
    this.skippedDuplicates = skippedDuplicates;
  }

  public LocalDateTime getCreatedAt() {
    return createdAt;
  }

  public void setCreatedAt(LocalDateTime createdAt) {
    this.createdAt = createdAt;
  }
}
