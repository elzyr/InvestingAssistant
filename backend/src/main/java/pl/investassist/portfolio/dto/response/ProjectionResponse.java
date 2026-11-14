package pl.investassist.portfolio.dto.response;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public record ProjectionResponse(
    List<ProjectionPointDto> points,
    List<String> proxyWarnings,
    ProjectionSummaryDto summary) {

  public record ProjectionPointDto(
      int monthOffset,
      LocalDate date,
      BigDecimal p10,
      BigDecimal p50,
      BigDecimal p90,
      BigDecimal invested) {}

  public record ProjectionSummaryDto(
      BigDecimal terminalP10,
      BigDecimal terminalP50,
      BigDecimal terminalP90,
      BigDecimal totalInvested,
      Integer goalProbability) {}
}
