package pl.investassist.portfolio.entity;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;
import pl.investassist.common.enums.Currency;

@Entity
@Table(
    name = "position",
    uniqueConstraints = {
      @UniqueConstraint(
          name = "uk_position_port_inst_series",
          columnNames = {"portfolio_id", "instrument_id", "series_name"})
    },
    indexes = {
      @Index(name = "idx_position_portfolio_id", columnList = "portfolio_id"),
      @Index(
          name = "idx_position_port_inst_series",
          columnList = "portfolio_id, instrument_id, series_name")
    })
public class Position {

  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  private UUID id;

  @Version
  private Long version;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "portfolio_id", nullable = false)
  private Portfolio portfolio;

  @Column(name = "instrument_id", nullable = false)
  private UUID instrumentId;

  @Column(precision = 38, scale = 8, nullable = false)
  private BigDecimal quantity = BigDecimal.ZERO;

  @Column(precision = 38, scale = 6, nullable = false)
  private BigDecimal averageBuyPrice = BigDecimal.ZERO;

  @Column(precision = 38, scale = 4, nullable = false)
  private BigDecimal totalCostBase = BigDecimal.ZERO;

  @Enumerated(EnumType.STRING)
  @Column(name = "currency", nullable = false)
  private Currency currency;

  @Column(length = 50)
  private String seriesName;

  @Column(precision = 10, scale = 4)
  private BigDecimal bondRate;

  @Column(precision = 10, scale = 4)
  private BigDecimal bondMargin;

  private Integer termMonths;

  private LocalDateTime purchaseDate;

  public Position() {}

  public UUID getId() {
    return id;
  }

  public void setId(UUID id) {
    this.id = id;
  }

  public Long getVersion() {
    return version;
  }

  public void setVersion(Long version) {
    this.version = version;
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

  public LocalDateTime getPurchaseDate() {
    return purchaseDate;
  }

  public void setPurchaseDate(LocalDateTime purchaseDate) {
    this.purchaseDate = purchaseDate;
  }

  public BigDecimal getTotalCostBase() {
    return totalCostBase;
  }

  public void setTotalCostBase(BigDecimal totalCostBase) {
    this.totalCostBase = totalCostBase;
  }
}
