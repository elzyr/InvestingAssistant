package pl.investassist.portfolio.dto.response;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;
import pl.investassist.portfolio.entity.Position;
import pl.investassist.portfolio.enums.Currency;

public record PositionResponse(
    UUID id,
    UUID instrumentId,
    BigDecimal quantity,
    BigDecimal averageBuyPrice,
    Currency currency,
    String seriesName,
    BigDecimal bondRate,
    BigDecimal bondMargin,
    Integer termMonths,
    LocalDateTime purchaseDate,
    BigDecimal totalCostBase) {

  public static PositionResponse from(Position position) {
    if (position == null) {
      return null;
    }
    return new PositionResponse(
        position.getId(),
        position.getInstrumentId(),
        position.getQuantity(),
        position.getAverageBuyPrice(),
        position.getCurrency(),
        position.getSeriesName(),
        position.getBondRate(),
        position.getBondMargin(),
        position.getTermMonths(),
        position.getPurchaseDate(),
        position.getTotalCostBase());
  }
}
