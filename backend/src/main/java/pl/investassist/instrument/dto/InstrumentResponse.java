package pl.investassist.instrument.dto;

import java.util.UUID;
import pl.investassist.common.enums.Currency;
import pl.investassist.common.enums.InstrumentType;
import pl.investassist.instrument.entity.Instrument;

public record InstrumentResponse(
    UUID id, String symbol, String name, InstrumentType type, Currency currency) {

  public static InstrumentResponse from(Instrument entity) {
    if (entity == null) {
      return null;
    }
    return new InstrumentResponse(
        entity.getId(),
        entity.getSymbol(),
        entity.getName(),
        entity.getType(),
        entity.getCurrency());
  }
}
