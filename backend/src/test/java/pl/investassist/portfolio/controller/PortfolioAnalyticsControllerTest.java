package pl.investassist.portfolio.controller;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.context.support.StaticMessageSource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;
import org.springframework.validation.beanvalidation.MethodValidationInterceptor;
import pl.investassist.common.enums.Currency;
import pl.investassist.common.exception.GlobalExceptionHandler;
import pl.investassist.portfolio.dto.response.AnnualReportResponse;
import pl.investassist.portfolio.dto.response.PortfolioTimelineResponse;
import pl.investassist.portfolio.dto.response.PortfolioValuationResponse;
import pl.investassist.portfolio.entity.Portfolio;
import pl.investassist.portfolio.service.PortfolioAnalyticsService;
import pl.investassist.portfolio.service.PortfolioService;
import pl.investassist.portfolio.valuation.TaxMode;
import pl.investassist.portfolio.valuation.Timeframe;

@ExtendWith(MockitoExtension.class)
class PortfolioAnalyticsControllerTest {

  @Mock
  private PortfolioService portfolioService;

  @Mock
  private PortfolioAnalyticsService portfolioAnalyticsService;

  private MockMvc mockMvc;
  private UUID userId;
  private UUID portfolioId;
  private Portfolio portfolio;

  @BeforeEach
  void setUp() {
    PortfolioAnalyticsController target =
        new PortfolioAnalyticsController(portfolioService, portfolioAnalyticsService);
    LocalValidatorFactoryBean validatorFactory = new LocalValidatorFactoryBean();
    validatorFactory.afterPropertiesSet();
    ProxyFactory proxyFactory = new ProxyFactory(target);
    proxyFactory.addAdvice(new MethodValidationInterceptor(validatorFactory.getValidator()));
    Object validatedController = proxyFactory.getProxy();

    StaticMessageSource messageSource = new StaticMessageSource();
    mockMvc = MockMvcBuilders.standaloneSetup(validatedController)
        .setControllerAdvice(new GlobalExceptionHandler(messageSource))
        .build();

    userId = UUID.randomUUID();
    portfolioId = UUID.randomUUID();
    portfolio = new Portfolio();
    portfolio.setId(portfolioId);
    portfolio.setUserId(userId);
    portfolio.setName("Main");
    portfolio.setBaseCurrency(Currency.PLN);
  }

  @Test
  void getValuation_defaultsToNettoAndForwardsOwnershipCheck() throws Exception {
    when(portfolioService.validateAndGetPortfolioEntity(userId, portfolioId)).thenReturn(portfolio);
    PortfolioValuationResponse resp = new PortfolioValuationResponse(
        portfolioId, "PLN", TaxMode.NETTO, null, List.of(), null);
    when(portfolioAnalyticsService.getValuation(portfolio, TaxMode.NETTO)).thenReturn(resp);

    mockMvc.perform(get("/api/portfolios/{portfolioId}/valuation", portfolioId)
            .header("X-User-Id", userId.toString()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.portfolioId").value(portfolioId.toString()))
        .andExpect(jsonPath("$.taxMode").value("NETTO"));

    verify(portfolioService).validateAndGetPortfolioEntity(userId, portfolioId);
    verify(portfolioAnalyticsService).getValuation(portfolio, TaxMode.NETTO);
  }

  @Test
  void getTimeline_parsesTimeframeAndTaxModeParams() throws Exception {
    when(portfolioService.validateAndGetPortfolioEntity(userId, portfolioId)).thenReturn(portfolio);
    PortfolioTimelineResponse resp = new PortfolioTimelineResponse(
        portfolioId,
        "PLN",
        Timeframe.ONE_YEAR,
        TaxMode.BRUTTO,
        BigDecimal.ZERO,
        BigDecimal.ZERO,
        BigDecimal.ZERO,
        List.of(),
        List.of());
    when(portfolioAnalyticsService.getTimeline(portfolio, Timeframe.ONE_YEAR, TaxMode.BRUTTO))
        .thenReturn(resp);

    mockMvc.perform(get("/api/portfolios/{portfolioId}/timeline", portfolioId)
            .header("X-User-Id", userId.toString())
            .param("timeframe", "1Y")
            .param("taxMode", "BRUTTO"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.baseCurrency").value("PLN"))
        .andExpect(jsonPath("$.taxMode").value("BRUTTO"));

    verify(portfolioAnalyticsService).getTimeline(portfolio, Timeframe.ONE_YEAR, TaxMode.BRUTTO);
  }

  @Test
  void timeframe_invalidReturns400AndExactWindows() throws Exception {
    when(portfolioService.validateAndGetPortfolioEntity(userId, portfolioId)).thenReturn(portfolio);

    mockMvc.perform(get("/api/portfolios/{portfolioId}/timeline", portfolioId)
            .header("X-User-Id", userId.toString())
            .param("timeframe", "1R"))
        .andExpect(status().isBadRequest());

    mockMvc.perform(get("/api/portfolios/{portfolioId}/timeline", portfolioId)
            .header("X-User-Id", userId.toString())
            .param("timeframe", "BOGUS"))
        .andExpect(status().isBadRequest());

    mockMvc.perform(get("/api/portfolios/{portfolioId}/valuation", portfolioId)
            .header("X-User-Id", userId.toString())
            .param("taxMode", "INVALID"))
        .andExpect(status().isBadRequest());

    java.time.LocalDate today = java.time.LocalDate.of(2026, 2, 10);
    java.time.LocalDate earliest = java.time.LocalDate.of(2025, 11, 20);
    org.assertj.core.api.Assertions.assertThat(Timeframe.ONE_MONTH.resolveStartDate(today, earliest))
        .isEqualTo(java.time.LocalDate.of(2026, 1, 10));
    org.assertj.core.api.Assertions.assertThat(Timeframe.THREE_MONTHS.resolveStartDate(today, earliest))
        .isEqualTo(java.time.LocalDate.of(2025, 11, 10));
    org.assertj.core.api.Assertions.assertThat(Timeframe.SIX_MONTHS.resolveStartDate(today, earliest))
        .isEqualTo(java.time.LocalDate.of(2025, 8, 10));
    org.assertj.core.api.Assertions.assertThat(Timeframe.YTD.resolveStartDate(today, earliest))
        .isEqualTo(java.time.LocalDate.of(2026, 1, 1));
    org.assertj.core.api.Assertions.assertThat(Timeframe.ONE_YEAR.resolveStartDate(today, earliest))
        .isEqualTo(java.time.LocalDate.of(2025, 2, 10));
    org.assertj.core.api.Assertions.assertThat(Timeframe.MAX.resolveStartDate(today, earliest))
        .isEqualTo(earliest);
  }

  @Test
  void getAnnualReport_defaultsTaxModeToNetto() throws Exception {
    when(portfolioService.validateAndGetPortfolioEntity(userId, portfolioId)).thenReturn(portfolio);
    AnnualReportResponse resp = new AnnualReportResponse(
        portfolioId,
        "PLN",
        2026,
        TaxMode.NETTO,
        true,
        List.of(2026),
        BigDecimal.ZERO,
        BigDecimal.ZERO,
        BigDecimal.ZERO,
        BigDecimal.ZERO,
        BigDecimal.ZERO,
        BigDecimal.ZERO,
        BigDecimal.ZERO,
        null,
        null,
        List.of());
    when(portfolioAnalyticsService.getAnnualReport(portfolio, 2026, TaxMode.NETTO)).thenReturn(resp);

    mockMvc.perform(get("/api/portfolios/{portfolioId}/reports/{year}", portfolioId, 2026)
            .header("X-User-Id", userId.toString()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.year").value(2026))
        .andExpect(jsonPath("$.taxMode").value("NETTO"));

    verify(portfolioAnalyticsService).getAnnualReport(portfolio, 2026, TaxMode.NETTO);
  }

  @Test
  void getAnnualReport_rejectsOutOfRangeYearsWithBadRequest() throws Exception {
    mockMvc.perform(get("/api/portfolios/{portfolioId}/reports/{year}", portfolioId, 1999)
            .header("X-User-Id", userId.toString()))
        .andExpect(status().isBadRequest());

    mockMvc.perform(get("/api/portfolios/{portfolioId}/reports/{year}", portfolioId, 2101)
            .header("X-User-Id", userId.toString()))
        .andExpect(status().isBadRequest());
  }
}
