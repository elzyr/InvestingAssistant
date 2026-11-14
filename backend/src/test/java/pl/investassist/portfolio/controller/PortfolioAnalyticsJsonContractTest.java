package pl.investassist.portfolio.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import pl.investassist.common.enums.Currency;
import pl.investassist.config.CacheConfig;
import pl.investassist.portfolio.dto.response.AnnualReportResponse;
import pl.investassist.portfolio.dto.response.AnnualReportResponse.MonthStatus;
import pl.investassist.portfolio.dto.response.AnnualReportResponse.MonthlyCategoryDto;
import pl.investassist.portfolio.dto.response.AnnualReportResponse.MonthlyReportDto;
import pl.investassist.portfolio.dto.response.PortfolioTimelineResponse;
import pl.investassist.portfolio.dto.response.PortfolioTimelineResponse.ProfitContributorDto;
import pl.investassist.portfolio.dto.response.PortfolioTimelineResponse.TimelinePointDto;
import pl.investassist.portfolio.dto.response.PortfolioValuationResponse;
import pl.investassist.portfolio.dto.response.PortfolioValuationResponse.EquityWeightDto;
import pl.investassist.portfolio.dto.response.PortfolioValuationResponse.FxDecompositionDto;
import pl.investassist.portfolio.dto.response.PortfolioValuationResponse.ProjectionSeedDto;
import pl.investassist.portfolio.dto.response.PortfolioValuationResponse.ValuationPositionDto;
import pl.investassist.portfolio.dto.response.PortfolioValuationResponse.ValuationSubPositionDto;
import pl.investassist.portfolio.dto.response.PortfolioValuationResponse.ValuationSummaryDto;
import pl.investassist.portfolio.entity.Portfolio;
import pl.investassist.portfolio.service.PortfolioAnalyticsService;
import pl.investassist.portfolio.service.PortfolioService;
import pl.investassist.portfolio.valuation.TaxMode;
import pl.investassist.portfolio.valuation.Timeframe;

@WebMvcTest(PortfolioAnalyticsController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import(CacheConfig.class)
@TestPropertySource(properties = "jwt.secret=01234567890123456789012345678901")
class PortfolioAnalyticsJsonContractTest {

  @Autowired
  private MockMvc mockMvc;

  @MockitoBean
  private PortfolioService portfolioService;

  @MockitoBean
  private PortfolioAnalyticsService portfolioAnalyticsService;

  private final ObjectMapper objectMapper = new ObjectMapper();

  private Portfolio samplePortfolio(UUID userId, UUID portfolioId) {
    Portfolio p = new Portfolio();
    p.setId(portfolioId);
    p.setUserId(userId);
    p.setName("Contract Portfolio");
    p.setBaseCurrency(Currency.PLN);
    return p;
  }

  @Test
  void valuation_serializesExactContractFieldNamesExpectedByFrontend() throws Exception {
    UUID userId = UUID.randomUUID();
    UUID portfolioId = UUID.randomUUID();
    UUID instrumentId = UUID.randomUUID();
    UUID txId = UUID.randomUUID();
    Portfolio portfolio = samplePortfolio(userId, portfolioId);

    ValuationSubPositionDto sub = new ValuationSubPositionDto(
        txId.toString(),
        txId,
        "2025-01-15",
        LocalDate.of(2025, 1, 15),
        new BigDecimal("2.000000"),
        new BigDecimal("2125.0000"),
        "PLN",
        new BigDecimal("100.00"),
        new BigDecimal("4250.00"),
        new BigDecimal("4730.00"),
        new BigDecimal("388.80"),
        new BigDecimal("9.15"),
        new BigDecimal("12.92"),
        new BigDecimal("550.0000"),
        new BigDecimal("500.0000"),
        null,
        null,
        new FxDecompositionDto("EUR", "PLN", new BigDecimal("10.00"), new BigDecimal("1.18")));

    ValuationPositionDto pos = new ValuationPositionDto(
        instrumentId.toString(),
        instrumentId,
        "SXR8.DE",
        "iShares Core S&P 500",
        "ETF",
        "SP500",
        "EUR",
        new BigDecimal("2.000000"),
        new BigDecimal("550.0000"),
        new BigDecimal("500.0000"),
        new BigDecimal("4730.00"),
        new BigDecimal("4250.00"),
        new BigDecimal("388.80"),
        new BigDecimal("480.00"),
        new BigDecimal("9.15"),
        new BigDecimal("86.00"),
        new BigDecimal("1.85"),
        new BigDecimal("100.00"),
        new BigDecimal("12.92"),
        BigDecimal.ZERO,
        BigDecimal.ZERO,
        null,
        null,
        null,
        null,
        null,
        false,
        false,
        "PURCHASES",
        new FxDecompositionDto("EUR", "PLN", new BigDecimal("10.00"), new BigDecimal("1.18")),
        List.of(sub));

    ValuationSummaryDto summary = new ValuationSummaryDto(
        new BigDecimal("4638.80"),
        new BigDecimal("4730.00"),
        new BigDecimal("4250.00"),
        new BigDecimal("388.80"),
        new BigDecimal("9.15"),
        new BigDecimal("480.00"),
        BigDecimal.ZERO,
        new BigDecimal("91.20"),
        BigDecimal.ZERO,
        new BigDecimal("86.00"),
        new BigDecimal("1.85"),
        new BigDecimal("12.92"),
        new BigDecimal("200.00"),
        new BigDecimal("4.50"),
        new BigDecimal("9.60"),
        new BigDecimal("3.00"),
        1);

    ProjectionSeedDto seed = new ProjectionSeedDto(
        new BigDecimal("4730.00"),
        new BigDecimal("4730.00"),
        BigDecimal.ZERO,
        new BigDecimal("4250.00"),
        new BigDecimal("6.00"),
        new BigDecimal("3.00"),
        new BigDecimal("0.19"),
        List.of(new EquityWeightDto("SXR8.DE", "iShares Core S&P 500", "SP500", "EUR", new BigDecimal("4730.00"), new BigDecimal("100.00"))));

    PortfolioValuationResponse resp = new PortfolioValuationResponse(
        portfolioId, "PLN", TaxMode.NETTO, summary, List.of(pos), seed);

    when(portfolioService.validateAndGetPortfolioEntity(userId, portfolioId)).thenReturn(portfolio);
    when(portfolioAnalyticsService.getValuation(portfolio, TaxMode.NETTO)).thenReturn(resp);

    MvcResult mvcResult = mockMvc.perform(get("/api/portfolios/{portfolioId}/valuation", portfolioId)
            .header("X-User-Id", userId.toString()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.summary.grossProfit").value(480.00))
        .andExpect(jsonPath("$.positions[0].isPriceUnavailable").exists())
        .andExpect(jsonPath("$.positions[0].isCpiUnavailable").exists())
        .andExpect(jsonPath("$.projectionSeed.belkaTaxRate").value(0.19))
        .andReturn();

    JsonNode root = objectMapper.readTree(mvcResult.getResponse().getContentAsString());
    assertThat(root.get("summary").has("openProfit")).isFalse();
    assertThat(root.get("summary").has("isXirrFallback")).isFalse();
    assertThat(root.get("summary").has("targetRealReturnPct")).isFalse();
    assertThat(root.get("projectionSeed").has("weightedEquityYieldPct")).isFalse();
    assertThat(root.get("summary").has("grossProfit")).isTrue();
    assertThat(root.get("summary").has("cpiBaselinePct")).isFalse();
    assertThat(root.get("summary").has("inflationGapPct")).isFalse();
  }

  @Test
  void timelineAndReport_serializeExactContractFieldNamesExpectedByFrontend() throws Exception {
    UUID userId = UUID.randomUUID();
    UUID portfolioId = UUID.randomUUID();
    Portfolio portfolio = samplePortfolio(userId, portfolioId);

    ProfitContributorDto contrib = new ProfitContributorDto(
        "OTHER", "OTHER", "OTHER", "OTHER",
        new BigDecimal("1000.00"), new BigDecimal("900.00"),
        new BigDecimal("100.00"), new BigDecimal("11.11"), new BigDecimal("100.0"), true);

    PortfolioTimelineResponse timeline = new PortfolioTimelineResponse(
        portfolioId,
        "PLN",
        Timeframe.YTD,
        TaxMode.NETTO,
        new BigDecimal("100.00"),
        new BigDecimal("11.11"),
        new BigDecimal("11.11"),
        List.of(new TimelinePointDto(
            LocalDate.of(2026, 6, 15),
            new BigDecimal("1000.00"),
            new BigDecimal("900.00"),
            new BigDecimal("100.00"),
            new BigDecimal("11.11"),
            BigDecimal.ZERO,
            new BigDecimal("1000.00"),
            BigDecimal.ZERO,
            List.of(contrib))),
        List.of(contrib));

    AnnualReportResponse report = new AnnualReportResponse(
        portfolioId,
        "PLN",
        2026,
        TaxMode.NETTO,
        true,
        List.of(2026),
        new BigDecimal("1000.00"),
        new BigDecimal("900.00"),
        new BigDecimal("120.00"),
        new BigDecimal("13.33"),
        new BigDecimal("97.20"),
        new BigDecimal("10.80"),
        new BigDecimal("10.80"),
        new BigDecimal("7.50"),
        new BigDecimal("3.0"),
        List.of(new MonthlyReportDto(
            0,
            MonthStatus.RECORDED,
            new BigDecimal("1000.00"),
            new BigDecimal("900.00"),
            new BigDecimal("120.00"),
            new BigDecimal("13.33"),
            new BigDecimal("97.20"),
            new BigDecimal("10.80"),
            new BigDecimal("97.20"),
            new BigDecimal("10.80"),
            new BigDecimal("3.0"),
            false,
            new BigDecimal("7.50"),
            List.of(new MonthlyCategoryDto(
                "SP500",
                new BigDecimal("900.00"),
                new BigDecimal("1000.00"),
                new BigDecimal("120.00"),
                new BigDecimal("97.20"),
                new BigDecimal("13.33"),
                new BigDecimal("10.80"),
                new BigDecimal("100.00"))),
            List.of())));

    when(portfolioService.validateAndGetPortfolioEntity(userId, portfolioId)).thenReturn(portfolio);
    when(portfolioAnalyticsService.getTimeline(portfolio, Timeframe.YTD, TaxMode.NETTO)).thenReturn(timeline);
    when(portfolioAnalyticsService.getAnnualReport(portfolio, 2026, TaxMode.NETTO)).thenReturn(report);

    mockMvc.perform(get("/api/portfolios/{portfolioId}/timeline", portfolioId)
            .header("X-User-Id", userId.toString()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.points[0].contributors[0].isOther").exists())
        .andExpect(jsonPath("$.contributors[0].isOther").exists());

    mockMvc.perform(get("/api/portfolios/{portfolioId}/reports/{year}", portfolioId, 2026)
            .header("X-User-Id", userId.toString()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.isCurrentYear").exists())
        .andExpect(jsonPath("$.months[0].inflationIsFallback").exists());
  }

  @Test
  void projection_serializesExactContractFieldNamesExpectedByFrontend() throws Exception {
    UUID userId = UUID.randomUUID();
    UUID portfolioId = UUID.randomUUID();
    Portfolio portfolio = samplePortfolio(userId, portfolioId);

    var req = new pl.investassist.portfolio.dto.request.ProjectionRequest(
        5, new BigDecimal("1000"), 0.7, "NETTO", false, true, new BigDecimal("100000"), null);
    var response = new pl.investassist.portfolio.dto.response.ProjectionResponse(
        List.of(new pl.investassist.portfolio.dto.response.ProjectionResponse.ProjectionPointDto(
            1,
            LocalDate.of(2026, 2, 1),
            new BigDecimal("10100"),
            new BigDecimal("10500"),
            new BigDecimal("10900"),
            new BigDecimal("10000"))),
        List.of("SHORT.DE: <52w history, using SP500 proxy return (9.0%)"),
        new pl.investassist.portfolio.dto.response.ProjectionResponse.ProjectionSummaryDto(
            new BigDecimal("80000"),
            new BigDecimal("95000"),
            new BigDecimal("115000"),
            new BigDecimal("70000"),
            64));

    when(portfolioService.validateAndGetPortfolioEntity(userId, portfolioId)).thenReturn(portfolio);
    when(portfolioAnalyticsService.getProjection(portfolio, req)).thenReturn(response);

    mockMvc.perform(post("/api/portfolios/{portfolioId}/projection", portfolioId)
            .header("X-User-Id", userId.toString())
            .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
            .content(objectMapper.writeValueAsString(req)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.points[0].monthOffset").value(1))
        .andExpect(jsonPath("$.points[0].p50").value(10500))
        .andExpect(jsonPath("$.proxyWarnings[0]").exists())
        .andExpect(jsonPath("$.summary.goalProbability").value(64));
  }
}
