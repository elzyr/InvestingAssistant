package pl.investassist.portfolio.entity;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;
import pl.investassist.portfolio.enums.Currency;
import pl.investassist.portfolio.enums.TransactionType;

@Entity
@Table(
    name = "transaction",
    indexes = {
      @Index(name = "idx_tx_portfolio_id", columnList = "portfolio_id"),
      @Index(
          name = "idx_tx_port_inst_time",
          columnList = "portfolio_id, instrument_id, executed_at")
    })
public class Transaction {

  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  private UUID id;

  @ManyToOne private Portfolio portfolio;

  private UUID instrumentId;

  @Enumerated(EnumType.STRING)
  private TransactionType transactionType;

  @Column(precision = 38, scale = 8)
  private BigDecimal quantity = BigDecimal.ZERO;

  @Column(precision = 38, scale = 6)
  private BigDecimal price = BigDecimal.ZERO;

  @Column(precision = 38, scale = 6)
  private BigDecimal commission = BigDecimal.ZERO;

  @Enumerated(EnumType.STRING)
  private Currency currency;

  @Column(precision = 38, scale = 8)
  private BigDecimal exchangeRate = BigDecimal.ONE;

  @Column(precision = 38, scale = 4)
  private BigDecimal baseAmount;

  private LocalDateTime executedAt;

  @Column(length = 50)
  private String seriesName;

  @Column(precision = 10, scale = 4)
  private BigDecimal bondRate;

  @Column(precision = 10, scale = 4)
  private BigDecimal bondMargin;

  private Integer termMonths;

  public Transaction() {}

  public UUID getId() {
    return id;
  }

  public void setId(UUID id) {
    this.id = id;
  }

  public Portfolio getPortfolio() {
    return portfolio;
  }

  public void setPortfolio(Portfolio portfolio) {
    this.portfolio = portfolio;
  }

  public UUID getInstrumentId() {
    return instrumentId;
  }

  public void setInstrumentId(UUID instrumentId) {
    this.instrumentId = instrumentId;
  }

  public TransactionType getTransactionType() {
    return transactionType;
  }

  public void setTransactionType(TransactionType transactionType) {
    this.transactionType = transactionType;
  }

  public BigDecimal getQuantity() {
    return quantity;
  }

  public void setQuantity(BigDecimal quantity) {
    this.quantity = quantity;
  }

  public BigDecimal getPrice() {
    return price;
  }

  public void setPrice(BigDecimal price) {
    this.price = price;
  }

  public BigDecimal getCommission() {
    return commission;
  }

  public void setCommission(BigDecimal commission) {
    this.commission = commission;
  }

  public Currency getCurrency() {
    return currency;
  }

  public void setCurrency(Currency currency) {
    this.currency = currency;
  }

  public BigDecimal getExchangeRate() {
    return exchangeRate;
  }

  public void setExchangeRate(BigDecimal exchangeRate) {
    this.exchangeRate = exchangeRate;
  }

  public LocalDateTime getExecutedAt() {
    return executedAt;
  }

  public void setExecutedAt(LocalDateTime executedAt) {
    this.executedAt = executedAt;
  }

  public String getSeriesName() {
    return seriesName;
  }

  public void setSeriesName(String seriesName) {
    this.seriesName = seriesName;
  }

  public BigDecimal getBondRate() {
    return bondRate;
  }

  public void setBondRate(BigDecimal bondRate) {
    this.bondRate = bondRate;
  }

  public BigDecimal getBondMargin() {
    return bondMargin;
  }

  public void setBondMargin(BigDecimal bondMargin) {
    this.bondMargin = bondMargin;
  }

  public Integer getTermMonths() {
    return termMonths;
  }

  public void setTermMonths(Integer termMonths) {
    this.termMonths = termMonths;
  }

  public BigDecimal getBaseAmount() {
    return baseAmount;
  }

  public void setBaseAmount(BigDecimal baseAmount) {
    this.baseAmount = baseAmount;
  }
}
