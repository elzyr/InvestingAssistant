package pl.investassist.portfolio.controller;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import pl.investassist.portfolio.dto.request.ProjectionRequest;
import pl.investassist.portfolio.dto.response.AnnualReportResponse;
import pl.investassist.portfolio.dto.response.PortfolioTimelineResponse;
import pl.investassist.portfolio.dto.response.PortfolioValuationResponse;
import pl.investassist.portfolio.dto.response.ProjectionResponse;
import pl.investassist.portfolio.entity.Portfolio;
import pl.investassist.portfolio.service.PortfolioAnalyticsService;
import pl.investassist.portfolio.service.PortfolioService;
import pl.investassist.portfolio.valuation.TaxMode;
import pl.investassist.portfolio.valuation.Timeframe;

import java.util.UUID;

@Validated
@RestController
@RequestMapping("/api/portfolios/{portfolioId}")
public class PortfolioAnalyticsController {

    private final PortfolioService portfolioService;
    private final PortfolioAnalyticsService portfolioAnalyticsService;

    public PortfolioAnalyticsController(
            PortfolioService portfolioService,
            PortfolioAnalyticsService portfolioAnalyticsService) {
        this.portfolioService = portfolioService;
        this.portfolioAnalyticsService = portfolioAnalyticsService;
    }

    @GetMapping("/valuation")
    public PortfolioValuationResponse getValuation(
            @RequestHeader("X-User-Id") UUID userId,
            @PathVariable UUID portfolioId,
            @RequestParam(required = false) String taxMode) {
        Portfolio portfolio = portfolioService.validateAndGetPortfolioEntity(userId, portfolioId);
        return portfolioAnalyticsService.getValuation(portfolio, TaxMode.fromParam(taxMode));
    }

    @GetMapping("/timeline")
    public PortfolioTimelineResponse getTimeline(
            @RequestHeader("X-User-Id") UUID userId,
            @PathVariable UUID portfolioId,
            @RequestParam(required = false) String timeframe,
            @RequestParam(required = false) String taxMode) {
        Portfolio portfolio = portfolioService.validateAndGetPortfolioEntity(userId, portfolioId);
        return portfolioAnalyticsService.getTimeline(
                portfolio,
                Timeframe.fromParam(timeframe),
                TaxMode.fromParam(taxMode));
    }

    @GetMapping("/reports/{year}")
    public AnnualReportResponse getAnnualReport(
            @RequestHeader("X-User-Id") UUID userId,
            @PathVariable UUID portfolioId,
            @PathVariable @Min(2000) @Max(2100) int year,
            @RequestParam(required = false) String taxMode) {
        Portfolio portfolio = portfolioService.validateAndGetPortfolioEntity(userId, portfolioId);
        return portfolioAnalyticsService.getAnnualReport(portfolio, year, TaxMode.fromParam(taxMode));
    }

    @PostMapping("/projection")
    public ProjectionResponse getProjection(
            @RequestHeader("X-User-Id") UUID userId,
            @PathVariable UUID portfolioId,
            @Valid @RequestBody ProjectionRequest request) {
        Portfolio portfolio = portfolioService.validateAndGetPortfolioEntity(userId, portfolioId);
        return portfolioAnalyticsService.getProjection(portfolio, request);
    }
}
