package pl.investassist.portfolio.mapper;

import pl.investassist.portfolio.dto.response.PositionResponse;
import pl.investassist.portfolio.entity.Position;

public final class PositionMapper {

  private PositionMapper() {}

  public static PositionResponse toResponse(Position position) {
    if (position == null) {
      return null;
    }
    return new PositionResponse(
        position.getId(),
        position.getInstrumentId(),
        position.getQuantity(),
        position.getAverageBuyPrice(),
        position.getCurrency());
  }
}
