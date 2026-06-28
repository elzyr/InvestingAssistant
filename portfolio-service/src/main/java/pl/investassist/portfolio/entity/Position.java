package pl.investassist.portfolio.entity;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.util.UUID;
import pl.investassist.portfolio.enums.Currency;

@Entity
public class Position {

  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  private UUID id;

  @ManyToOne private Portfolio portfolio;

  private UUID instrumentId;

  private BigDecimal quantity = BigDecimal.ZERO;

  private BigDecimal averageBuyPrice = BigDecimal.ZERO;

  @Enumerated(EnumType.STRING)
  private Currency currency;

  public Position() {}

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

  public BigDecimal getQuantity() {
    return quantity;
  }

  public void setQuantity(BigDecimal quantity) {
    this.quantity = quantity;
  }

  public BigDecimal getAverageBuyPrice() {
    return averageBuyPrice;
  }

  public void setAverageBuyPrice(BigDecimal averageBuyPrice) {
    this.averageBuyPrice = averageBuyPrice;
  }

  public Currency getCurrency() {
    return currency;
  }

  public void setCurrency(Currency currency) {
    this.currency = currency;
  }
}
