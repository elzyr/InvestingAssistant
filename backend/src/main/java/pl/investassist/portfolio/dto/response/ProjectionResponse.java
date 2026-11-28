package pl.investassist.portfolio.dto.response;

import com.fasterxml.jackson.annotation.JsonIgnore;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public record ProjectionResponse(
    List<ProjectionPointDto> points,
    List<String> proxyWarnings,
    ProjectionSummaryDto summary,
    @JsonIgnore boolean degraded) {

  public ProjectionResponse(
      List<ProjectionPointDto> points, List<String> proxyWarnings, ProjectionSummaryDto summary) {
    this(points, proxyWarnings, summary, false);
  }

  public ProjectionResponse asDegraded() {
    return new ProjectionResponse(points, proxyWarnings, summary, true);
  }

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
