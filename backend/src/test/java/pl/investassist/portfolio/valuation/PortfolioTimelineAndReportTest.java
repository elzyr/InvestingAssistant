package pl.investassist.portfolio.valuation;

import org.junit.jupiter.api.Test;
import pl.investassist.common.enums.Currency;
import pl.investassist.common.enums.InstrumentType;
import pl.investassist.instrument.entity.Instrument;
import pl.investassist.portfolio.dto.response.AnnualReportResponse;
import pl.investassist.portfolio.dto.response.AnnualReportResponse.MonthStatus;
import pl.investassist.portfolio.dto.response.PortfolioTimelineResponse;
import pl.investassist.portfolio.dto.response.PortfolioValuationResponse;
import pl.investassist.portfolio.entity.Portfolio;
import pl.investassist.portfolio.entity.Transaction;
import pl.investassist.portfolio.enums.TransactionType;
import pl.investassist.portfolio.service.PortfolioCacheService;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class PortfolioTimelineAndReportTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 4, 15, 14, 0);

    @Test
    void timelineTodayPointMatchesValuationSummaryAndGroupsTopContributors() {
        Portfolio p = new Portfolio();
        p.setId(UUID.randomUUID());
        p.setUserId(UUID.randomUUID());
        p.setBaseCurrency(Currency.PLN);

        Instrument sxr8 = new Instrument();
        sxr8.setId(UUID.randomUUID());
        sxr8.setSymbol("SXR8.DE");
        sxr8.setName("S&P 500");
        sxr8.setType(InstrumentType.ETF);
        sxr8.setCurrency(Currency.EUR);

        Transaction buy = new Transaction();
        buy.setId(UUID.randomUUID());
        buy.setPortfolio(p);
        buy.setInstrumentId(sxr8.getId());
        buy.setTransactionType(TransactionType.BUY);
        buy.setQuantity(new BigDecimal("1"));
        buy.setPrice(new BigDecimal("400"));
        buy.setCommission(BigDecimal.ZERO);
        buy.setCurrency(Currency.EUR);
        buy.setExchangeRate(BigDecimal.ONE);
        buy.setExchangeRateToBase(new BigDecimal("4.20"));
        buy.setBaseAmount(new BigDecimal("1680"));
        buy.setExecutedAt(LocalDateTime.of(2026, 1, 10, 10, 0));

        FakeMarketDataPort market = new FakeMarketDataPort()
                .withQuote("SXR8.DE", 450.0, 445.0, "EUR")
                .withHistory("SXR8.DE", "EUR", Map.of(
                        LocalDate.of(2026, 1, 10), new BigDecimal("400"),
                        LocalDate.of(2026, 2, 15), new BigDecimal("420"),
                        LocalDate.of(2026, 3, 15), new BigDecimal("435")))
                .withFx("EUR", "PLN", 4.30, Map.of(
                        LocalDate.of(2026, 1, 10), new BigDecimal("4.20"),
                        LocalDate.of(2026, 2, 15), new BigDecimal("4.25"),
                        LocalDate.of(2026, 4, 15), new BigDecimal("4.30")));

        var quotes = market.getBatchQuotes(List.of("SXR8.DE"));
        var histories = market.getBatchHistory(List.of("SXR8.DE"), LocalDate.of(2026, 1, 1));
        var fxSeries = market.getBatchFxHistory(
                List.of(new pl.investassist.portfolio.port.MarketDataPort.CurrencyPair("EUR", "PLN")),
                LocalDate.of(2026, 1, 1), NOW.toLocalDate());

        PortfolioValuationResponse val = ValuationEngine.evaluate(
                p, List.of(buy), Map.of(sxr8.getId(), sxr8), quotes, fxSeries, new BigDecimal("3.6"), TaxMode.NETTO, NOW);

        PortfolioTimelineResponse timeline = TimelineEngine.build(
                p, List.of(buy), Map.of(sxr8.getId(), sxr8), quotes, histories, fxSeries,
                new BigDecimal("3.6"), Timeframe.YTD, TaxMode.NETTO, NOW);

        assertThat(timeline.points()).isNotEmpty();
        var todayPoint = timeline.points().getLast();
        assertThat(todayPoint.date()).isEqualTo(NOW.toLocalDate());
        assertThat(todayPoint.value()).isEqualByComparingTo(val.summary().totalValue());
        assertThat(todayPoint.invested()).isEqualByComparingTo(val.summary().totalInvested());
        assertThat(todayPoint.netProfit()).isEqualByComparingTo(val.summary().netProfit());
        assertThat(timeline.contributors()).hasSize(1);
        assertThat(timeline.contributors().getFirst().symbol()).isEqualTo("SXR8.DE");
    }

    @Test
    void annualReportGeneratesMonthlyStatusesCategoryBreakdownAndInflationFallback() {
        Portfolio p = new Portfolio();
        p.setId(UUID.randomUUID());
        p.setUserId(UUID.randomUUID());
        p.setBaseCurrency(Currency.PLN);

        Instrument etf = new Instrument();
        etf.setId(UUID.randomUUID());
        etf.setSymbol("SXR8.DE");
        etf.setName("S&P 500");
        etf.setType(InstrumentType.ETF);
        etf.setCurrency(Currency.PLN);

        Transaction buy = new Transaction();
        buy.setId(UUID.randomUUID());
        buy.setPortfolio(p);
        buy.setInstrumentId(etf.getId());
        buy.setTransactionType(TransactionType.BUY);
        buy.setQuantity(new BigDecimal("10"));
        buy.setPrice(new BigDecimal("100"));
        buy.setCommission(BigDecimal.ZERO);
        buy.setCurrency(Currency.PLN);
        buy.setExchangeRate(BigDecimal.ONE);
        buy.setBaseAmount(new BigDecimal("1000"));
        buy.setExecutedAt(LocalDateTime.of(2026, 2, 10, 9, 0));

        FakeMarketDataPort market = new FakeMarketDataPort()
                .withQuote("SXR8.DE", 120.0, 118.0, "PLN")
                .withHistory("SXR8.DE", "PLN", Map.of(
                        LocalDate.of(2026, 2, 28), new BigDecimal("105"),
                        LocalDate.of(2026, 3, 31), new BigDecimal("112")))
                .withMonthlyCpi(2026, 2, 3.8)
                .withMonthlyCpi(2026, 3, 3.6);

        AnnualReportResponse report = AnnualReportEngine.build(
                p,
                List.of(buy),
                Map.of(etf.getId(), etf),
                market.getBatchQuotes(List.of("SXR8.DE")),
                market.getBatchHistory(List.of("SXR8.DE"), LocalDate.of(2026, 1, 1)),
                Map.of(),
                market.getInflation(),
                2026,
                TaxMode.NETTO,
                NOW);

        assertThat(report.months()).hasSize(12);
        assertThat(report.months().get(0).status()).isEqualTo(MonthStatus.BEFORE_PORTFOLIO);
        assertThat(report.months().get(1).status()).isEqualTo(MonthStatus.RECORDED);
        assertThat(report.months().get(1).inflationIsFallback()).isFalse();
        assertThat(report.months().get(1).cpiInflation()).isEqualByComparingTo("3.8");
        assertThat(report.months().get(3).status()).isEqualTo(MonthStatus.RECORDED);
        assertThat(report.months().get(3).inflationIsFallback()).isTrue();
        assertThat(report.months().get(3).portfolioValue()).isEqualByComparingTo("1162.00");
        assertThat(report.months().get(4).status()).isEqualTo(MonthStatus.FUTURE);
        assertThat(report.ytdProfitNet()).isEqualByComparingTo("162.00");
        assertThat(report.ytdTwrPct()).isEqualByComparingTo("16.20");
    }

    @Test
    void annualReportMultiYearUsesDec31BaselineForJanuaryMomAndYtdTwr() {
        Portfolio p = new Portfolio();
        p.setId(UUID.randomUUID());
        p.setUserId(UUID.randomUUID());
        p.setBaseCurrency(Currency.PLN);

        Instrument etf = new Instrument();
        etf.setId(UUID.randomUUID());
        etf.setSymbol("SXR8.DE");
        etf.setName("S&P 500");
        etf.setType(InstrumentType.ETF);
        etf.setCurrency(Currency.PLN);

        Transaction buy = new Transaction();
        buy.setId(UUID.randomUUID());
        buy.setPortfolio(p);
        buy.setInstrumentId(etf.getId());
        buy.setTransactionType(TransactionType.BUY);
        buy.setQuantity(new BigDecimal("10"));
        buy.setPrice(new BigDecimal("100"));
        buy.setCommission(BigDecimal.ZERO);
        buy.setCurrency(Currency.PLN);
        buy.setExchangeRate(BigDecimal.ONE);
        buy.setBaseAmount(new BigDecimal("1000"));
        buy.setExecutedAt(LocalDateTime.of(2025, 6, 15, 10, 0));

        FakeMarketDataPort market = new FakeMarketDataPort()
                .withQuote("SXR8.DE", 121.0, 121.0, "PLN")
                .withHistory("SXR8.DE", "PLN", Map.of(
                        LocalDate.of(2025, 12, 31), new BigDecimal("110"),
                        LocalDate.of(2026, 1, 31), new BigDecimal("121"),
                        LocalDate.of(2026, 2, 28), new BigDecimal("121"),
                        LocalDate.of(2026, 3, 31), new BigDecimal("121")));

        AnnualReportResponse report = AnnualReportEngine.build(
                p,
                List.of(buy),
                Map.of(etf.getId(), etf),
                market.getBatchQuotes(List.of("SXR8.DE")),
                market.getBatchHistory(List.of("SXR8.DE"), LocalDate.of(2025, 12, 20)),
                Map.of(),
                market.getInflation(),
                2026,
                TaxMode.NETTO,
                NOW);

        var jan = report.months().getFirst();
        assertThat(jan.status()).isEqualTo(MonthStatus.RECORDED);
        assertThat(jan.portfolioValue()).isEqualByComparingTo("1170.10");
        assertThat(jan.profitNet()).isEqualByComparingTo("170.10");
        assertThat(jan.profitNetMom()).isEqualByComparingTo("89.10");
        assertThat(jan.returnNetMomPct()).isEqualByComparingTo("8.24");
        assertThat(report.ytdTwrPct()).isEqualByComparingTo("8.24");
    }

    @Test
    void fullProfitableSellKeepsPositiveTwrAcrossTimelineAndAnnualReport() {
        Portfolio p = new Portfolio();
        p.setId(UUID.randomUUID());
        p.setUserId(UUID.randomUUID());
        p.setBaseCurrency(Currency.PLN);

        Instrument stock = new Instrument();
        stock.setId(UUID.randomUUID());
        stock.setSymbol("CDR.WA");
        stock.setName("CD Projekt");
        stock.setType(InstrumentType.STOCK);
        stock.setCurrency(Currency.PLN);

        Transaction buy = new Transaction();
        buy.setId(UUID.randomUUID());
        buy.setPortfolio(p);
        buy.setInstrumentId(stock.getId());
        buy.setTransactionType(TransactionType.BUY);
        buy.setQuantity(new BigDecimal("10"));
        buy.setPrice(new BigDecimal("100"));
        buy.setCommission(BigDecimal.ZERO);
        buy.setCurrency(Currency.PLN);
        buy.setExchangeRate(BigDecimal.ONE);
        buy.setBaseAmount(new BigDecimal("1000"));
        buy.setExecutedAt(LocalDateTime.of(2026, 1, 10, 10, 0));

        Transaction sellAll = new Transaction();
        sellAll.setId(UUID.randomUUID());
        sellAll.setPortfolio(p);
        sellAll.setInstrumentId(stock.getId());
        sellAll.setTransactionType(TransactionType.SELL);
        sellAll.setQuantity(new BigDecimal("10"));
        sellAll.setPrice(new BigDecimal("120"));
        sellAll.setCommission(BigDecimal.ZERO);
        sellAll.setCurrency(Currency.PLN);
        sellAll.setExchangeRate(BigDecimal.ONE);
        sellAll.setBaseAmount(new BigDecimal("1200"));
        sellAll.setExecutedAt(LocalDateTime.of(2026, 3, 15, 10, 0));

        FakeMarketDataPort market = new FakeMarketDataPort()
                .withQuote("CDR.WA", 120.0, 120.0, "PLN")
                .withHistory("CDR.WA", "PLN", Map.of(
                        LocalDate.of(2026, 1, 31), new BigDecimal("110"),
                        LocalDate.of(2026, 2, 28), new BigDecimal("120")));

        var instMap = Map.of(stock.getId(), stock);
        var txs = List.of(buy, sellAll);
        var quotes = market.getBatchQuotes(List.of("CDR.WA"));
        var histories = market.getBatchHistory(List.of("CDR.WA"), LocalDate.of(2026, 1, 1));

        PortfolioTimelineResponse timeline = TimelineEngine.build(
                p, txs, instMap, quotes, histories, Map.of(), null, Timeframe.YTD, TaxMode.NETTO, NOW);
        AnnualReportResponse report = AnnualReportEngine.build(
                p, txs, instMap, quotes, histories, Map.of(), market.getInflation(), 2026, TaxMode.NETTO, NOW);

        assertThat(timeline.periodTwrPct()).isEqualByComparingTo("16.20");
        assertThat(report.ytdPortfolioValue()).isEqualByComparingTo("0.00");
        assertThat(report.ytdProfitNet()).isEqualByComparingTo("162.00");
        assertThat(report.ytdTwrPct()).isEqualByComparingTo("16.20");
    }

    @Test
    void annualReportBelkaDoesNotNetWinnerAgainstLoserInSameCategory() {
        Portfolio p = new Portfolio();
        p.setId(UUID.randomUUID());
        p.setUserId(UUID.randomUUID());
        p.setBaseCurrency(Currency.PLN);

        Instrument winner = new Instrument();
        winner.setId(UUID.randomUUID());
        winner.setSymbol("PKO.WA");
        winner.setName("PKO BP");
        winner.setType(InstrumentType.STOCK);
        winner.setCurrency(Currency.PLN);

        Instrument loser = new Instrument();
        loser.setId(UUID.randomUUID());
        loser.setSymbol("CDR.WA");
        loser.setName("CD Projekt");
        loser.setType(InstrumentType.STOCK);
        loser.setCurrency(Currency.PLN);

        Transaction buyWin = new Transaction();
        buyWin.setId(UUID.randomUUID());
        buyWin.setPortfolio(p);
        buyWin.setInstrumentId(winner.getId());
        buyWin.setTransactionType(TransactionType.BUY);
        buyWin.setQuantity(new BigDecimal("10"));
        buyWin.setPrice(new BigDecimal("100"));
        buyWin.setCommission(BigDecimal.ZERO);
        buyWin.setCurrency(Currency.PLN);
        buyWin.setExchangeRate(BigDecimal.ONE);
        buyWin.setBaseAmount(new BigDecimal("1000"));
        buyWin.setExecutedAt(LocalDateTime.of(2026, 4, 1, 10, 0));

        Transaction buyLose = new Transaction();
        buyLose.setId(UUID.randomUUID());
        buyLose.setPortfolio(p);
        buyLose.setInstrumentId(loser.getId());
        buyLose.setTransactionType(TransactionType.BUY);
        buyLose.setQuantity(new BigDecimal("10"));
        buyLose.setPrice(new BigDecimal("100"));
        buyLose.setCommission(BigDecimal.ZERO);
        buyLose.setCurrency(Currency.PLN);
        buyLose.setExchangeRate(BigDecimal.ONE);
        buyLose.setBaseAmount(new BigDecimal("1000"));
        buyLose.setExecutedAt(LocalDateTime.of(2026, 4, 1, 10, 0));

        FakeMarketDataPort market = new FakeMarketDataPort()
                .withQuote("PKO.WA", 120.0, 120.0, "PLN")
                .withQuote("CDR.WA", 80.0, 80.0, "PLN");

        AnnualReportResponse report = AnnualReportEngine.build(
                p,
                List.of(buyWin, buyLose),
                Map.of(winner.getId(), winner, loser.getId(), loser),
                market.getBatchQuotes(List.of("PKO.WA", "CDR.WA")),
                Map.of(),
                Map.of(),
                market.getInflation(),
                2026,
                TaxMode.NETTO,
                NOW);

        var april = report.months().get(3);
        assertThat(april.profitGross()).isEqualByComparingTo("0.00");
        assertThat(april.profitNet()).isEqualByComparingTo("0.00");
        assertThat(april.breakdown()).hasSize(1);
        assertThat(april.breakdown().getFirst().profitGross()).isEqualByComparingTo("0.00");
    }

    @Test
    void cacheServiceBumpsPortfolioVersionOnEvict() {
        PortfolioCacheService service = new PortfolioCacheService(null);
        UUID portfolioId = UUID.randomUUID();

        String initialVersion = service.versionOf(portfolioId);
        assertThat(initialVersion).isEqualTo("0");

        service.evictPortfolio(portfolioId);
        String nextVersion = service.versionOf(portfolioId);
        assertThat(nextVersion).isEqualTo("1");
    }

    @Test
    void annualReportComputesRealProfitPctFromDisplayValueAfterPartialSaleAndHasContinuousYears() {
        Portfolio pf = new Portfolio();
        pf.setId(UUID.randomUUID());
        pf.setName("Report PF");
        pf.setBaseCurrency(Currency.PLN);

        Instrument pkn = new Instrument();
        pkn.setId(UUID.randomUUID());
        pkn.setSymbol("PKN.WA");
        pkn.setName("Orlen");
        pkn.setType(InstrumentType.STOCK);
        pkn.setCurrency(Currency.PLN);

        LocalDateTime buyDate = LocalDateTime.of(2023, 1, 10, 10, 0);
        Transaction buy = new Transaction();
        buy.setId(UUID.randomUUID());
        buy.setPortfolio(pf);
        buy.setInstrumentId(pkn.getId());
        buy.setTransactionType(TransactionType.BUY);
        buy.setQuantity(new BigDecimal("10"));
        buy.setPrice(new BigDecimal("100"));
        buy.setCommission(BigDecimal.ZERO);
        buy.setCurrency(Currency.PLN);
        buy.setExchangeRate(BigDecimal.ONE);
        buy.setBaseAmount(new BigDecimal("1000"));
        buy.setExchangeRateToBase(BigDecimal.ONE);
        buy.setExecutedAt(buyDate);

        Transaction partialSell = new Transaction();
        partialSell.setId(UUID.randomUUID());
        partialSell.setPortfolio(pf);
        partialSell.setInstrumentId(pkn.getId());
        partialSell.setTransactionType(TransactionType.SELL);
        partialSell.setQuantity(new BigDecimal("5"));
        partialSell.setPrice(new BigDecimal("200"));
        partialSell.setCommission(BigDecimal.ZERO);
        partialSell.setCurrency(Currency.PLN);
        partialSell.setExchangeRate(BigDecimal.ONE);
        partialSell.setBaseAmount(new BigDecimal("1000"));
        partialSell.setExchangeRateToBase(BigDecimal.ONE);
        partialSell.setExecutedAt(LocalDateTime.of(2026, 1, 15, 10, 0));

        Map<String, pl.investassist.portfolio.port.MarketDataPort.MarketQuote> quotes = Map.of(
                "PKN.WA", new pl.investassist.portfolio.port.MarketDataPort.MarketQuote("PKN.WA", new BigDecimal("200"), new BigDecimal("200"), "PLN", false));
        Map<String, pl.investassist.portfolio.port.MarketDataPort.PriceHistory> histories = Map.of(
                "PKN.WA", new pl.investassist.portfolio.port.MarketDataPort.PriceHistory("PKN.WA", "PLN", List.of(
                        new pl.investassist.portfolio.port.MarketDataPort.PricePoint(LocalDate.of(2025, 12, 31), new BigDecimal("200")),
                        new pl.investassist.portfolio.port.MarketDataPort.PricePoint(LocalDate.of(2026, 6, 15), new BigDecimal("200")))));
        Map<Integer, Map<Integer, BigDecimal>> fullMonthly = new java.util.HashMap<>();
        for (int y = 2023; y <= 2026; y++) {
            Map<Integer, BigDecimal> monthsMap = new java.util.HashMap<>();
            for (int m = 1; m <= 12; m++) {
                monthsMap.put(m, new BigDecimal("4.0"));
            }
            fullMonthly.put(y, monthsMap);
        }
        pl.investassist.portfolio.port.MarketDataPort.InflationSnapshot inflation =
                new pl.investassist.portfolio.port.MarketDataPort.InflationSnapshot(new BigDecimal("4.0"), fullMonthly);

        LocalDateTime now = LocalDateTime.of(2026, 6, 15, 12, 0);
        AnnualReportResponse report = AnnualReportEngine.build(
                pf,
                List.of(buy, partialSell),
                Map.of(pkn.getId(), pkn),
                quotes,
                histories,
                Map.of(),
                inflation,
                2026,
                TaxMode.NETTO,
                now);

        assertThat(report.availableYears()).containsExactly(2026, 2025, 2024, 2023);

        AnnualReportResponse.MonthlyReportDto june = report.months().get(5);
        assertThat(june.status()).isEqualTo(AnnualReportResponse.MonthStatus.RECORDED);
        assertThat(june.portfolioValue()).isEqualByComparingTo("905.00");

        Double expectedHurdle = MetricsCalculator.compoundCpiPathHurdle(
                List.of(
                        new MetricsCalculator.CashFlow(-1000.0, buyDate),
                        new MetricsCalculator.CashFlow(1000.0, partialSell.getExecutedAt())),
                fullMonthly,
                now);
        assertThat(expectedHurdle).isNotNull().isPositive();
        BigDecimal expectedRealPct = ValuationSupport.bd((905.0 / expectedHurdle - 1.0) * 100.0, 2);
        assertThat(june.realProfitPct()).isEqualByComparingTo(expectedRealPct);
        assertThat(june.positions()).hasSize(1);
        assertThat(june.positions().getFirst().symbol()).isEqualTo("PKN.WA");
        assertThat(june.positions().getFirst().profitNet()).isEqualByComparingTo("810.00");
        assertThat(june.positions().getFirst().profitNetMom()).isEqualByComparingTo(june.profitNetMom());
    }

    @Test
    void annualReportIncludesRequestedFutureYearUpTo2100AndDistinguishesTrancheStockFromBond() {
        Portfolio pf = new Portfolio();
        pf.setId(UUID.randomUUID());
        pf.setName("Mix PF");
        pf.setBaseCurrency(Currency.PLN);

        Instrument pkn = new Instrument();
        pkn.setId(UUID.randomUUID());
        pkn.setSymbol("PKN.WA");
        pkn.setName("Orlen");
        pkn.setType(InstrumentType.STOCK);
        pkn.setCurrency(Currency.PLN);

        Transaction buyStockWithTranche = new Transaction();
        buyStockWithTranche.setId(UUID.randomUUID());
        buyStockWithTranche.setPortfolio(pf);
        buyStockWithTranche.setInstrumentId(pkn.getId());
        buyStockWithTranche.setTransactionType(TransactionType.BUY);
        buyStockWithTranche.setQuantity(new BigDecimal("5"));
        buyStockWithTranche.setPrice(new BigDecimal("100"));
        buyStockWithTranche.setCommission(BigDecimal.ZERO);
        buyStockWithTranche.setCurrency(Currency.PLN);
        buyStockWithTranche.setExchangeRate(BigDecimal.ONE);
        buyStockWithTranche.setBaseAmount(new BigDecimal("500"));
        buyStockWithTranche.setExchangeRateToBase(BigDecimal.ONE);
        buyStockWithTranche.setSeriesName("TRANCHE-A");
        buyStockWithTranche.setExecutedAt(LocalDateTime.of(2025, 1, 10, 10, 0));

        AnnualReportResponse futureReport = AnnualReportEngine.build(
                pf,
                List.of(buyStockWithTranche),
                Map.of(pkn.getId(), pkn),
                Map.of("PKN.WA", new pl.investassist.portfolio.port.MarketDataPort.MarketQuote(
                        "PKN.WA", new BigDecimal("110"), new BigDecimal("110"), "PLN", false)),
                Map.of(),
                Map.of(),
                new pl.investassist.portfolio.port.MarketDataPort.InflationSnapshot(null, Map.of()),
                2030,
                TaxMode.NETTO,
                NOW);

        assertThat(futureReport.availableYears()).contains(2030, 2026, 2025);
        assertThat(futureReport.months()).allMatch(m -> m.status() == MonthStatus.FUTURE);

        PortfolioValuationResponse valuation = ValuationEngine.evaluate(
                pf,
                List.of(buyStockWithTranche),
                Map.of(pkn.getId(), pkn),
                Map.of("PKN.WA", new pl.investassist.portfolio.port.MarketDataPort.MarketQuote(
                        "PKN.WA", new BigDecimal("110"), new BigDecimal("110"), "PLN", false)),
                Map.of(),
                null,
                TaxMode.NETTO,
                NOW);
        assertThat(valuation.positions()).hasSize(1);
        assertThat(valuation.positions().getFirst().category()).isEqualTo("STOCK");
        assertThat(valuation.positions().getFirst().bondFamily()).isNull();
    }

    @Test
    void twr_splitsOnExactTransactionDatesAndTreatsWipeoutAsMinus100() {
        double wipeout = MetricsCalculator.calculateTwr(List.of(
                new MetricsCalculator.TwrPeriod(100.0, 0.0, 0.0)));
        assertThat(wipeout).isCloseTo(-1.0, org.assertj.core.data.Offset.offset(1e-9));

        double noDietz = MetricsCalculator.calculateTwr(List.of(
                new MetricsCalculator.TwrPeriod(100.0, 150.0, 200.0)));
        assertThat(noDietz).isCloseTo(-1.0, org.assertj.core.data.Offset.offset(1e-9));
    }

    @Test
    void annualReport_isolatesCalendarYearYtdFromPriorYearBaseline() {
        Portfolio pf = new Portfolio();
        pf.setId(UUID.randomUUID());
        pf.setUserId(UUID.randomUUID());
        pf.setBaseCurrency(Currency.PLN);

        Instrument etf = new Instrument();
        etf.setId(UUID.randomUUID());
        etf.setSymbol("VUAA.DE");
        etf.setName("Vanguard S&P 500");
        etf.setType(InstrumentType.ETF);
        etf.setCurrency(Currency.PLN);

        Transaction buy2025 = new Transaction();
        buy2025.setId(UUID.randomUUID());
        buy2025.setPortfolio(pf);
        buy2025.setInstrumentId(etf.getId());
        buy2025.setTransactionType(TransactionType.BUY);
        buy2025.setQuantity(new BigDecimal("10"));
        buy2025.setPrice(new BigDecimal("100"));
        buy2025.setCommission(BigDecimal.ZERO);
        buy2025.setCurrency(Currency.PLN);
        buy2025.setExchangeRate(BigDecimal.ONE);
        buy2025.setBaseAmount(new BigDecimal("1000"));
        buy2025.setExchangeRateToBase(BigDecimal.ONE);
        buy2025.setExecutedAt(LocalDateTime.of(2025, 11, 1, 10, 0));

        java.util.NavigableMap<LocalDate, BigDecimal> prices = new java.util.TreeMap<>();
        prices.put(LocalDate.of(2025, 11, 1), new BigDecimal("100"));
        prices.put(LocalDate.of(2025, 12, 31), new BigDecimal("120"));
        prices.put(LocalDate.of(2026, 6, 30), new BigDecimal("120"));
        pl.investassist.portfolio.port.MarketDataPort.PriceHistory history =
                new pl.investassist.portfolio.port.MarketDataPort.PriceHistory("VUAA.DE", "PLN", prices);
        pl.investassist.portfolio.port.MarketDataPort.MarketQuote quote =
                new pl.investassist.portfolio.port.MarketDataPort.MarketQuote(
                        "VUAA.DE", new BigDecimal("120"), new BigDecimal("120"), "PLN", false);

        AnnualReportResponse report = AnnualReportEngine.build(
                pf,
                List.of(buy2025),
                Map.of(etf.getId(), etf),
                Map.of("VUAA.DE", quote),
                Map.of("VUAA.DE", history),
                Map.of(),
                new pl.investassist.portfolio.port.MarketDataPort.InflationSnapshot(
                        new BigDecimal("3.0"), Map.of(2026, Map.of(6, new BigDecimal("3.0")))),
                2026,
                TaxMode.BRUTTO,
                NOW);

        assertThat(report.ytdProfitGross()).isEqualByComparingTo("0.00");
        assertThat(report.ytdProfitNet()).isEqualByComparingTo("0.00");
        assertThat(report.ytdReturnNetPct()).isEqualByComparingTo("0.00");
    }

    @Test
    void cpiPathHurdle_usesMonthlyCpiPathAcrossAllExternalCashFlowsAndReturnsNullWhenCpiMissing() {
        List<MetricsCalculator.CashFlow> flows = List.of(
                new MetricsCalculator.CashFlow(-1000.0, LocalDateTime.of(2025, 1, 1, 0, 0)));
        Map<Integer, Map<Integer, BigDecimal>> partialCpi = Map.of(2025, Map.of(1, new BigDecimal("4.0")));
        assertThat(MetricsCalculator.compoundCpiPathHurdle(
                flows, partialCpi, LocalDateTime.of(2025, 3, 1, 0, 0))).isNull();

        Map<Integer, Map<Integer, BigDecimal>> fullCpi = Map.of(
                2025, Map.of(
                        1, new BigDecimal("12.0"),
                        2, new BigDecimal("12.0"),
                        3, new BigDecimal("12.0")));
        Double hurdle = MetricsCalculator.compoundCpiPathHurdle(
                flows, fullCpi, LocalDateTime.of(2025, 3, 1, 0, 0));
        assertThat(hurdle).isNotNull().isGreaterThan(1015.0).isLessThan(1025.0);

        List<MetricsCalculator.CostLot> lots = List.of(
                new MetricsCalculator.CostLot(1000.0, LocalDateTime.of(2025, 1, 1, 0, 0)));
        MetricsCalculator.RealMetrics fallbackMetrics = MetricsCalculator.calculatePortfolioRealMetrics(
                lots, flows, partialCpi, 1100.0, 4.0, 0.10, LocalDateTime.of(2025, 3, 1, 0, 0));
        assertThat(fallbackMetrics).isNotNull();
        assertThat(fallbackMetrics.realProfitAmount()).isGreaterThan(0.0);
    }
}
