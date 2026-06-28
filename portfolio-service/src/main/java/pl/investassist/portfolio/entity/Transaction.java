package pl.investassist.portfolio.entity;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;
import org.hibernate.annotations.CreationTimestamp;
import pl.investassist.portfolio.enums.Currency;
import pl.investassist.portfolio.enums.TransactionType;

@Entity
public class Transaction {

  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  private UUID id;

  @ManyToOne private Portfolio portfolio;

  private UUID instrumentId;

  @Enumerated(EnumType.STRING)
  private TransactionType transactionType;

  private BigDecimal quantity = BigDecimal.ZERO;

  private BigDecimal price = BigDecimal.ZERO;

  private BigDecimal commission = BigDecimal.ZERO;

  @Enumerated(EnumType.STRING)
  private Currency currency;

  private BigDecimal exchangeRate = BigDecimal.ONE;

  @CreationTimestamp private LocalDateTime executedAt;

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

  @Override
  public String toString() {
    return "Transaction{"
        + "id="
        + id
        + ", portfolio="
        + portfolio
        + ", instrumentId="
        + instrumentId
        + ", transactionType="
        + transactionType
        + ", quantity="
        + quantity
        + ", price="
        + price
        + ", commission="
        + commission
        + ", baseCurrency="
        + currency
        + ", exchangeRate="
        + exchangeRate
        + ", executedAt="
        + executedAt
        + '}';
  }
}
