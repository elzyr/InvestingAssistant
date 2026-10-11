package pl.investassist.instrument.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import pl.investassist.instrument.entity.Instrument;
import pl.investassist.instrument.enums.Currency;
import pl.investassist.instrument.enums.InstrumentType;

public record CreateInstrumentRequest(
    @NotBlank String symbol,
    @NotBlank String name,
    @NotNull InstrumentType type,
    @NotNull Currency currency) {

  public Instrument toEntity() {
    Instrument instrument = new Instrument();
    instrument.setSymbol(symbol);
    instrument.setName(name);
    instrument.setType(type);
    instrument.setCurrency(currency);
    return instrument;
  }
}
