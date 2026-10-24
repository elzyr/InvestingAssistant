package pl.investassist.instrument.entity;

import jakarta.persistence.*;
import java.util.UUID;
import pl.investassist.common.enums.Currency;
import pl.investassist.common.enums.InstrumentType;

@Entity
public class Instrument {

  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  private UUID id;

  @Column(nullable = false, unique = true)
  private String symbol;

  @Column(nullable = false)
  private String name;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false)
  private InstrumentType type;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false)
  private Currency currency;

  public Instrument() {}

  public String getSymbol() {
    return symbol;
  }

  public void setSymbol(String symbol) {
    this.symbol = symbol;
  }

  public InstrumentType getType() {
    return type;
  }

  public void setType(InstrumentType type) {
    this.type = type;
  }

  public Currency getCurrency() {
    return currency;
  }

  public void setCurrency(Currency currency) {
    this.currency = currency;
  }

  public String getName() {
    return name;
  }

  public void setName(String name) {
    this.name = name;
  }

  public void setId(UUID id) {
    this.id = id;
  }

  public UUID getId() {
    return id;
  }
}
