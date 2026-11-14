package pl.investassist.portfolio.valuation;

import org.junit.jupiter.api.Test;
import pl.investassist.common.enums.Currency;
import pl.investassist.common.enums.InstrumentType;
import pl.investassist.common.exception.MarketDataUnavailableException;
import pl.investassist.instrument.entity.Instrument;
import pl.investassist.portfolio.dto.response.PortfolioTimelineResponse;
import pl.investassist.portfolio.dto.response.PortfolioValuationResponse;
import pl.investassist.portfolio.dto.response.PortfolioValuationResponse.ValuationPositionDto;
import pl.investassist.portfolio.entity.Portfolio;
import pl.investassist.portfolio.entity.Transaction;
import pl.investassist.portfolio.enums.TransactionType;
import pl.investassist.portfolio.port.MarketDataPort;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PortfolioValuationTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 6, 15, 12, 0);

    private static Portfolio portfolio(Currency baseCurrency) {
        Portfolio p = new Portfolio();
        p.setId(UUID.randomUUID());
        p.setUserId(UUID.randomUUID());
        p.setName("Test Portfolio");
        p.setBaseCurrency(baseCurrency);
        return p;
    }

    private static Instrument instrument(String symbol, String name, InstrumentType type, Currency currency) {
        Instrument i = new Instrument();
        i.setId(UUID.randomUUID());
        i.setSymbol(symbol);
        i.setName(name);
        i.setType(type);
        i.setCurrency(currency);
        return i;
    }

    private static Transaction tx(
            Portfolio portfolio,
            UUID instrumentId,
            TransactionType type,
            double qty,
            double price,
            Currency currency,
            double exchangeRateToInst,
            double baseAmount,
            LocalDateTime executedAt) {
        Transaction t = new Transaction();
        t.setId(UUID.randomUUID());
        t.setPortfolio(portfolio);
        t.setInstrumentId(instrumentId);
        t.setTransactionType(type);
        t.setQuantity(BigDecimal.valueOf(qty));
        t.setPrice(BigDecimal.valueOf(price));
        t.setCommission(BigDecimal.ZERO);
        t.setCurrency(currency);
        t.setExchangeRate(BigDecimal.valueOf(exchangeRateToInst));
        t.setBaseAmount(BigDecimal.valueOf(baseAmount));
        t.setExchangeRateToBase(currency == portfolio.getBaseCurrency() ? BigDecimal.ONE : BigDecimal.valueOf(exchangeRateToInst));
        t.setExecutedAt(executedAt);
        return t;
    }

    @Test
    void xtbEurEtfBoughtInPln_valuesAccuratelyWithQuoteFxAndPerPositionBelka() {
        Portfolio p = portfolio(Currency.PLN);
        Instrument sxr8 = instrument("SXR8.DE", "iShares Core S&P 500", InstrumentType.ETF, Currency.EUR);
        sxr8.setAssetCategory(pl.investassist.common.enums.AssetCategory.SP500);

        Transaction buy = tx(
                p,
                sxr8.getId(),
                TransactionType.BUY,
                2.0,
                2125.0,
                Currency.PLN,
                4.25,
                4250.0,
                LocalDateTime.of(2025, 1, 15, 10, 0));

        FakeMarketDataPort market = new FakeMarketDataPort()
                .withQuote("SXR8.DE", 550.0, 540.0, "EUR")
                .withFx("EUR", "PLN", 4.30, Map.of(LocalDate.of(2026, 6, 15), new BigDecimal("4.30")))
                .withCurrentCpi(new BigDecimal("4.0"));

        PortfolioValuationResponse resp = ValuationEngine.evaluate(
                p,
                List.of(buy),
                Map.of(sxr8.getId(), sxr8),
                market.getBatchQuotes(List.of("SXR8.DE")),
                market.getBatchFxHistory(List.of(new pl.investassist.portfolio.port.MarketDataPort.CurrencyPair("EUR", "PLN")),
                        LocalDate.of(2025, 1, 1), LocalDate.of(2026, 6, 15)),
                new BigDecimal("4.0"),
                TaxMode.NETTO,
                NOW);

        assertThat(resp.summary().grossTotalValue()).isEqualByComparingTo("4730.00");
        assertThat(resp.summary().totalInvested()).isEqualByComparingTo("4250.00");
        assertThat(resp.summary().taxPaid()).isEqualByComparingTo("91.20");
        assertThat(resp.summary().totalValue()).isEqualByComparingTo("4638.80");
        assertThat(resp.summary().netProfit()).isEqualByComparingTo("388.80");
        assertThat(resp.summary().dailyChange()).isEqualByComparingTo("86.00");

        ValuationPositionDto pos = resp.positions().getFirst();
        assertThat(pos.categoryCode()).isEqualTo(pl.investassist.common.enums.AssetCategory.SP500.name());
        assertThat(pos.fxDecomposition()).isNotNull();
        assertThat(pos.fxDecomposition().marketReturnPct()).isEqualByComparingTo("10.00");
        assertThat(pos.fxDecomposition().fxReturnPct()).isEqualByComparingTo("1.18");
    }

    @Test
    void lseGbpQuoteUsesQuoteCurrencyRatherThanInstrumentEnumCurrency() {
        Portfolio p = portfolio(Currency.PLN);
        Instrument cndx = instrument("CNDX.L", "iShares Nasdaq 100", InstrumentType.ETF, Currency.USD);

        Transaction buy = tx(
                p,
                cndx.getId(),
                TransactionType.BUY,
                1.0,
                5000.0,
                Currency.PLN,
                5.0,
                5000.0,
                LocalDateTime.of(2025, 6, 1, 10, 0));

        FakeMarketDataPort market = new FakeMarketDataPort()
                .withQuote("CNDX.L", 1100.0, 1090.0, "GBp")
                .withFx("GBP", "PLN", 5.10, Map.of())
                .withFx("USD", "PLN", 4.00, Map.of());

        market.withQuote("CNDX.L", 1100.0, 1090.0, "GBP");

        PortfolioValuationResponse resp = ValuationEngine.evaluate(
                p,
                List.of(buy),
                Map.of(cndx.getId(), cndx),
                market.getBatchQuotes(List.of("CNDX.L")),
                market.getBatchFxHistory(
                        List.of(new pl.investassist.portfolio.port.MarketDataPort.CurrencyPair("GBP", "PLN")),
                        LocalDate.of(2025, 1, 1), LocalDate.of(2026, 6, 15)),
                new BigDecimal("3.5"),
                TaxMode.BRUTTO,
                NOW);

        assertThat(resp.positions().getFirst().quoteCurrency()).isEqualTo("GBP");
        assertThat(resp.summary().grossTotalValue()).isEqualByComparingTo("5610.00");
        assertThat(resp.summary().netProfit()).isEqualByComparingTo("610.00");
    }

    @Test
    void conservativePerPositionBelkaDoesNotOffsetLossAcrossPositions() {
        Portfolio p = portfolio(Currency.PLN);
        Instrument winner = instrument("PKO.WA", "PKO BP", InstrumentType.STOCK, Currency.PLN);
        Instrument loser = instrument("CDR.WA", "CD Projekt", InstrumentType.STOCK, Currency.PLN);

        Transaction buyWin = tx(p, winner.getId(), TransactionType.BUY, 10, 50.0, Currency.PLN, 1.0, 500.0, NOW.minusMonths(6));
        Transaction divWin = tx(p, winner.getId(), TransactionType.DIVIDEND, 1, 100.0, Currency.PLN, 1.0, 100.0, NOW.minusMonths(1));
        Transaction buyLose = tx(p, loser.getId(), TransactionType.BUY, 10, 100.0, Currency.PLN, 1.0, 1000.0, NOW.minusMonths(6));

        FakeMarketDataPort market = new FakeMarketDataPort()
                .withQuote("PKO.WA", 70.0, 70.0, "PLN")
                .withQuote("CDR.WA", 80.0, 80.0, "PLN");

        PortfolioValuationResponse netto = ValuationEngine.evaluate(
                p,
                List.of(buyWin, divWin, buyLose),
                Map.of(winner.getId(), winner, loser.getId(), loser),
                market.getBatchQuotes(List.of("PKO.WA", "CDR.WA")),
                Map.of(),
                null,
                TaxMode.NETTO,
                NOW);

        assertThat(netto.summary().taxPaid()).isEqualByComparingTo("19.00");
        assertThat(netto.summary().dividendsTotal()).isEqualByComparingTo("100.00");
        assertThat(netto.summary().netProfit()).isEqualByComparingTo("81.00");
        assertThat(netto.summary().totalValue()).isEqualByComparingTo("1500.00");
        assertThat(netto.projectionSeed().belkaTaxRate()).isEqualByComparingTo("0.19");

        PortfolioValuationResponse brutto = ValuationEngine.evaluate(
                p,
                List.of(buyWin, divWin, buyLose),
                Map.of(winner.getId(), winner, loser.getId(), loser),
                market.getBatchQuotes(List.of("PKO.WA", "CDR.WA")),
                Map.of(),
                null,
                TaxMode.BRUTTO,
                NOW);

        assertThat(brutto.summary().taxPaid()).isEqualByComparingTo("0.00");
        assertThat(brutto.summary().netProfit()).isEqualByComparingTo("100.00");
        assertThat(brutto.summary().totalValue()).isEqualByComparingTo("1500.00");
    }

    @Test
    void standardAccountPartialSellIncludesRealizedProfitInNetProfitBelkaAndConsistentEngines() {
        Portfolio p = portfolio(Currency.PLN);
        Instrument etf = instrument("VWCE.DE", "Vanguard FTSE All-World", InstrumentType.ETF, Currency.PLN);

        Transaction buy = tx(p, etf.getId(), TransactionType.BUY, 10, 100.0, Currency.PLN, 1.0, 1000.0,
                LocalDateTime.of(2026, 2, 10, 10, 0));
        Transaction partialSell = tx(p, etf.getId(), TransactionType.SELL, 4, 150.0, Currency.PLN, 1.0, 600.0,
                LocalDateTime.of(2026, 3, 20, 10, 0));

        FakeMarketDataPort market = new FakeMarketDataPort()
                .withQuote("VWCE.DE", 160.0, 155.0, "PLN")
                .withHistory("VWCE.DE", "PLN", Map.of(
                        LocalDate.of(2026, 2, 28), new BigDecimal("140"),
                        LocalDate.of(2026, 3, 31), new BigDecimal("155")));

        Map<UUID, Instrument> instMap = Map.of(etf.getId(), etf);
        var quotes = market.getBatchQuotes(List.of("VWCE.DE"));
        var histories = market.getBatchHistory(List.of("VWCE.DE"), LocalDate.of(2026, 2, 1));

        PortfolioValuationResponse val = ValuationEngine.evaluate(
                p, List.of(buy, partialSell), instMap, quotes, Map.of(), new BigDecimal("3.0"), TaxMode.NETTO, NOW);
        var timeline = TimelineEngine.build(
                p, List.of(buy, partialSell), instMap, quotes, histories, Map.of(),
                new BigDecimal("3.0"), Timeframe.MAX, TaxMode.NETTO, NOW);
        var report = AnnualReportEngine.build(
                p, List.of(buy, partialSell), instMap, quotes, histories, Map.of(),
                market.getInflation(), 2026, TaxMode.NETTO, NOW);

        assertThat(val.summary().totalInvested()).isEqualByComparingTo("600.00");
        assertThat(val.summary().grossTotalValue()).isEqualByComparingTo("960.00");
        assertThat(val.summary().realizedProfit()).isEqualByComparingTo("200.00");
        assertThat(val.summary().grossProfit()).isEqualByComparingTo("560.00");
        assertThat(val.summary().taxPaid()).isEqualByComparingTo("106.40");
        assertThat(val.summary().netProfit()).isEqualByComparingTo("453.60");
        assertThat(val.summary().totalValue()).isEqualByComparingTo("891.60");

        assertThat(timeline.points().getLast().value()).isEqualByComparingTo("891.60");
        assertThat(timeline.points().getLast().netProfit()).isEqualByComparingTo("453.60");
        assertThat(report.ytdPortfolioValue()).isEqualByComparingTo("891.60");
        assertThat(report.ytdProfitNet()).isEqualByComparingTo("453.60");
    }

    @Test
    void missingFxRateForOpenPositionThrowsMarketDataUnavailable() {
        Portfolio p = portfolio(Currency.PLN);
        Instrument sxr8 = instrument("SXR8.DE", "S&P 500", InstrumentType.ETF, Currency.EUR);
        Transaction buy = tx(p, sxr8.getId(), TransactionType.BUY, 1, 400.0, Currency.EUR, 4.25, 1700.0, NOW.minusDays(10));
        FakeMarketDataPort market = new FakeMarketDataPort().withQuote("SXR8.DE", 450.0, 445.0, "EUR");

        assertThatThrownBy(() -> ValuationEngine.evaluate(
                p,
                List.of(buy),
                Map.of(sxr8.getId(), sxr8),
                market.getBatchQuotes(List.of("SXR8.DE")),
                Map.of(),
                null,
                TaxMode.NETTO,
                NOW))
                .isInstanceOf(MarketDataUnavailableException.class)
                .hasMessageContaining("EUR -> PLN");
    }

    @Test
    void bondValuationHandlesExactAnniversaryMissingCpiAndDoesNotMisclassifyEquities() {
        Instrument coin = instrument("COIN", "Coinbase Global", InstrumentType.STOCK, Currency.USD);
        Instrument vendor = instrument("VENDOR", "Vendor Corp", InstrumentType.STOCK, Currency.USD);
        Instrument spots = instrument("SPOTS", "Spots ETF", InstrumentType.ETF, Currency.USD);

        assertThat(BondValuationEngine.isBond(coin, null)).isFalse();
        assertThat(BondValuationEngine.isBond(vendor, null)).isFalse();
        assertThat(BondValuationEngine.isBond(spots, null)).isFalse();
        assertThat(BondValuationEngine.isBond(null, "AAPL")).isFalse();
        assertThat(BondValuationEngine.isBond(null, "SP500")).isFalse();
        assertThat(BondValuationEngine.isBond(null, "EDO0634")).isTrue();

        LocalDateTime purchase = LocalDateTime.of(2024, 6, 15, 0, 0);
        double firstYearAnniversaryPrice = BondValuationEngine.calculateUnitPrice(
                purchase,
                new BigDecimal("6.80"),
                new BigDecimal("1.50"),
                new BigDecimal("3.60"),
                LocalDateTime.of(2025, 6, 15, 12, 0),
                "EDO0634",
                120);
        assertThat(firstYearAnniversaryPrice).isCloseTo(106.80, org.assertj.core.data.Offset.offset(0.01));

        double secondYearAnniversaryNullCpi = BondValuationEngine.calculateUnitPrice(
                purchase,
                new BigDecimal("6.80"),
                new BigDecimal("1.50"),
                null,
                LocalDateTime.of(2026, 6, 15, 12, 0),
                "EDO0634",
                120);
        assertThat(secondYearAnniversaryNullCpi).isCloseTo(106.80, org.assertj.core.data.Offset.offset(0.01));
    }

    @Test
    void inflationIndexedBondYearTwoWithoutCpiFlagsIsCpiUnavailable() {
        Portfolio p = portfolio(Currency.PLN);
        Instrument edo = instrument("PL_BOND_EDO", "Obligacje EDO", InstrumentType.BOND, Currency.PLN);

        Transaction edo2024 = tx(p, edo.getId(), TransactionType.BUY, 10, 100.0, Currency.PLN, 1.0, 1000.0,
                LocalDateTime.of(2024, 6, 15, 0, 0));
        edo2024.setSeriesName("EDO0634");
        edo2024.setBondRate(new BigDecimal("6.80"));
        edo2024.setBondMargin(new BigDecimal("1.50"));
        edo2024.setTermMonths(120);

        PortfolioValuationResponse withoutCpi = ValuationEngine.evaluate(
                p, List.of(edo2024), Map.of(edo.getId(), edo), Map.of(), Map.of(), null, TaxMode.NETTO, NOW);
        assertThat(withoutCpi.positions()).hasSize(1);
        assertThat(withoutCpi.positions().getFirst().isCpiUnavailable()).isTrue();

        Map<Integer, Map<Integer, BigDecimal>> monthlyCpi = Map.of(2025, Map.of(5, new BigDecimal("3.6")));
        PortfolioValuationResponse withCpi = ValuationEngine.evaluate(
                p, List.of(edo2024), Map.of(edo.getId(), edo), Map.of(), Map.of(),
                new BigDecimal("3.6"), monthlyCpi, new java.util.TreeMap<>(), TaxMode.NETTO, NOW);
        assertThat(withCpi.positions()).hasSize(1);
        assertThat(withCpi.positions().getFirst().isCpiUnavailable()).isFalse();
    }

    @Test
    void metricsCalculatorComputesExactXirrTwrAndShortWindowFallback() {
        List<MetricsCalculator.CashFlow> multiFlows = List.of(
                new MetricsCalculator.CashFlow(-1000.0, LocalDateTime.of(2024, 1, 1, 0, 0)),
                new MetricsCalculator.CashFlow(-1000.0, LocalDateTime.of(2025, 1, 1, 0, 0)),
                new MetricsCalculator.CashFlow(2310.0, LocalDateTime.of(2026, 1, 1, 0, 0)));
        Double xirr = MetricsCalculator.solveXirr(multiFlows, 0.1);
        assertThat(xirr).isNotNull();
        assertThat(xirr).isCloseTo(0.10, org.assertj.core.data.Offset.offset(0.002));

        MetricsCalculator.XirrResult shortHolding = MetricsCalculator.calculateHoldingXirr(
                List.of(new MetricsCalculator.CashFlow(-1000.0, LocalDateTime.of(2026, 6, 1, 0, 0))),
                1050.0,
                NOW,
                30);
        assertThat(shortHolding.xirr()).isNull();

        double twr = MetricsCalculator.calculateTwr(List.of(
                new MetricsCalculator.TwrPeriod(1000.0, 1100.0, 0.0),
                new MetricsCalculator.TwrPeriod(1100.0, 2200.0, 1000.0)));
        assertThat(twr).isCloseTo(0.20, org.assertj.core.data.Offset.offset(1e-6));

        MetricsCalculator.FxDecomposition decomp = MetricsCalculator.calculateFxDecomposition(100.0, 110.0, 4.0, 4.2);
        assertThat(decomp).isNotNull();
        assertThat(decomp.marketReturnPct()).isCloseTo(10.0, org.assertj.core.data.Offset.offset(1e-6));
        assertThat(decomp.fxReturnPct()).isCloseTo(5.0, org.assertj.core.data.Offset.offset(1e-6));
    }

    @Test
    void treasuryBondsGroupByFamilyAndYearlyCohortAndRespectNonCompoundingCoi() {
        Portfolio p = portfolio(Currency.PLN);
        Instrument edo = instrument("PL_BOND_EDO", "Obligacje EDO", InstrumentType.BOND, Currency.PLN);

        Transaction edo2024 = tx(p, edo.getId(), TransactionType.BUY, 10, 100.0, Currency.PLN, 1.0, 1000.0,
                LocalDateTime.of(2024, 6, 15, 0, 0));
        edo2024.setSeriesName("EDO0634");
        edo2024.setBondRate(new BigDecimal("6.80"));
        edo2024.setBondMargin(new BigDecimal("1.50"));
        edo2024.setTermMonths(120);

        Transaction edo2025 = tx(p, edo.getId(), TransactionType.BUY, 20, 100.0, Currency.PLN, 1.0, 2000.0,
                LocalDateTime.of(2025, 6, 15, 0, 0));
        edo2025.setSeriesName("EDO0635");
        edo2025.setBondRate(new BigDecimal("6.55"));
        edo2025.setBondMargin(new BigDecimal("1.50"));
        edo2025.setTermMonths(120);

        PortfolioValuationResponse resp = ValuationEngine.evaluate(
                p,
                List.of(edo2024, edo2025),
                Map.of(edo.getId(), edo),
                Map.of(),
                Map.of(),
                new BigDecimal("3.6"),
                TaxMode.NETTO,
                NOW);

        assertThat(resp.positions()).hasSize(1);
        ValuationPositionDto bondPos = resp.positions().getFirst();
        assertThat(bondPos.bondFamily()).isEqualTo("EDO");
        assertThat(bondPos.subPositionsType()).isEqualTo("BONDS_YEARLY");
        assertThat(bondPos.subPositions()).hasSize(2);
        assertThat(bondPos.subPositions().get(0).label()).isEqualTo("EDO0635");
        assertThat(bondPos.subPositions().get(0).date()).isEqualTo(LocalDate.of(2025, 6, 15));
        assertThat(bondPos.subPositions().get(1).label()).isEqualTo("EDO0634");
        assertThat(bondPos.subPositions().get(1).date()).isEqualTo(LocalDate.of(2024, 6, 15));
        assertThat(bondPos.valueBase()).isGreaterThan(new BigDecimal("3000.00"));
        assertThat(resp.projectionSeed().startBondValue()).isEqualByComparingTo(bondPos.valueBase());
    }

    @Test
    void closedEdoBondKeepsEdoFamilyAndFeedsOpenEdoPosition() {
        Portfolio pf = portfolio(Currency.PLN);
        Instrument closedEdo = instrument("EDO0135", "Obligacje EDO0135", InstrumentType.BOND, Currency.PLN);
        Instrument openEdo = instrument("EDO0236", "Obligacje EDO0236", InstrumentType.BOND, Currency.PLN);

        Transaction buyClosed = new Transaction();
        buyClosed.setId(UUID.randomUUID());
        buyClosed.setPortfolio(pf);
        buyClosed.setInstrumentId(closedEdo.getId());
        buyClosed.setTransactionType(TransactionType.BUY);
        buyClosed.setQuantity(new BigDecimal("10"));
        buyClosed.setPrice(new BigDecimal("100"));
        buyClosed.setCommission(BigDecimal.ZERO);
        buyClosed.setCurrency(Currency.PLN);
        buyClosed.setExchangeRate(BigDecimal.ONE);
        buyClosed.setBaseAmount(new BigDecimal("1000"));
        buyClosed.setExchangeRateToBase(BigDecimal.ONE);
        buyClosed.setExecutedAt(LocalDateTime.of(2025, 1, 10, 10, 0));
        buyClosed.setSeriesName("EDO0135");
        buyClosed.setBondRate(new BigDecimal("6.60"));
        buyClosed.setBondMargin(new BigDecimal("2.00"));
        buyClosed.setTermMonths(120);

        Transaction sellClosed = new Transaction();
        sellClosed.setId(UUID.randomUUID());
        sellClosed.setPortfolio(pf);
        sellClosed.setInstrumentId(closedEdo.getId());
        sellClosed.setTransactionType(TransactionType.SELL);
        sellClosed.setQuantity(new BigDecimal("10"));
        sellClosed.setPrice(new BigDecimal("110"));
        sellClosed.setCommission(BigDecimal.ZERO);
        sellClosed.setCurrency(Currency.PLN);
        sellClosed.setExchangeRate(BigDecimal.ONE);
        sellClosed.setBaseAmount(new BigDecimal("1100"));
        sellClosed.setExchangeRateToBase(BigDecimal.ONE);
        sellClosed.setExecutedAt(LocalDateTime.of(2026, 1, 10, 10, 0));
        sellClosed.setSeriesName("EDO0135");

        Transaction buyOpen = new Transaction();
        buyOpen.setId(UUID.randomUUID());
        buyOpen.setPortfolio(pf);
        buyOpen.setInstrumentId(openEdo.getId());
        buyOpen.setTransactionType(TransactionType.BUY);
        buyOpen.setQuantity(new BigDecimal("10"));
        buyOpen.setPrice(new BigDecimal("100"));
        buyOpen.setCommission(BigDecimal.ZERO);
        buyOpen.setCurrency(Currency.PLN);
        buyOpen.setExchangeRate(BigDecimal.ONE);
        buyOpen.setBaseAmount(new BigDecimal("1000"));
        buyOpen.setExchangeRateToBase(BigDecimal.ONE);
        buyOpen.setExecutedAt(LocalDateTime.of(2026, 2, 10, 10, 0));
        buyOpen.setSeriesName("EDO0236");
        buyOpen.setBondRate(new BigDecimal("6.60"));
        buyOpen.setBondMargin(new BigDecimal("2.00"));
        buyOpen.setTermMonths(120);

        PortfolioValuationResponse resp = ValuationEngine.evaluate(
                pf,
                List.of(buyClosed, sellClosed, buyOpen),
                Map.of(closedEdo.getId(), closedEdo, openEdo.getId(), openEdo),
                Map.of(),
                Map.of(),
                new BigDecimal("3.0"),
                TaxMode.NETTO,
                NOW);

        assertThat(resp.positions()).hasSize(1);
        ValuationPositionDto edo = resp.positions().getFirst();
        assertThat(edo.bondFamily()).isEqualTo("EDO");
        assertThat(edo.realizedProfitBase()).isEqualByComparingTo("100.00");
        assertThat(edo.xirrPct()).isNotNull();
    }

    @Test
    void partialBondSellUsesNonDuplicatedFamilyXirrAndReflectsSettledTaxInNetto() {
        Portfolio pf = portfolio(Currency.PLN);
        Instrument bond = instrument("COI", "Obligacje COI", InstrumentType.BOND, Currency.PLN);

        Transaction buy = new Transaction();
        buy.setId(UUID.randomUUID());
        buy.setPortfolio(pf);
        buy.setInstrumentId(bond.getId());
        buy.setTransactionType(TransactionType.BUY);
        buy.setQuantity(new BigDecimal("20"));
        buy.setPrice(new BigDecimal("100"));
        buy.setCommission(BigDecimal.ZERO);
        buy.setCurrency(Currency.PLN);
        buy.setExchangeRate(BigDecimal.ONE);
        buy.setBaseAmount(new BigDecimal("2000"));
        buy.setExchangeRateToBase(BigDecimal.ONE);
        buy.setExecutedAt(LocalDateTime.of(2025, 1, 10, 10, 0));
        buy.setSeriesName("EDO0135");
        buy.setBondRate(new BigDecimal("6.50"));
        buy.setBondMargin(new BigDecimal("1.50"));
        buy.setTermMonths(120);

        Transaction partialSell = new Transaction();
        partialSell.setId(UUID.randomUUID());
        partialSell.setPortfolio(pf);
        partialSell.setInstrumentId(bond.getId());
        partialSell.setTransactionType(TransactionType.SELL);
        partialSell.setQuantity(new BigDecimal("10"));
        partialSell.setPrice(new BigDecimal("120"));
        partialSell.setCommission(BigDecimal.ZERO);
        partialSell.setCurrency(Currency.PLN);
        partialSell.setExchangeRate(BigDecimal.ONE);
        partialSell.setBaseAmount(new BigDecimal("1200"));
        partialSell.setExchangeRateToBase(BigDecimal.ONE);
        partialSell.setExecutedAt(LocalDateTime.of(2026, 1, 10, 10, 0));
        partialSell.setSeriesName("EDO0135");

        Map<Integer, Map<Integer, BigDecimal>> monthlyCpi = Map.of(2025, Map.of(12, new BigDecimal("3.0")));
        PortfolioValuationResponse netto = ValuationEngine.evaluate(
                pf,
                List.of(buy, partialSell),
                Map.of(bond.getId(), bond),
                Map.of(),
                Map.of(),
                new BigDecimal("3.0"),
                monthlyCpi,
                new java.util.TreeMap<>(),
                TaxMode.NETTO,
                NOW);
        PortfolioValuationResponse brutto = ValuationEngine.evaluate(
                pf,
                List.of(buy, partialSell),
                Map.of(bond.getId(), bond),
                Map.of(),
                Map.of(),
                new BigDecimal("3.0"),
                monthlyCpi,
                new java.util.TreeMap<>(),
                TaxMode.BRUTTO,
                NOW);

        ValuationPositionDto nettoPos = netto.positions().getFirst();
        ValuationPositionDto bruttoPos = brutto.positions().getFirst();
        assertThat(nettoPos.xirrPct()).isNotNull().isLessThan(bruttoPos.xirrPct());
        assertThat(netto.summary().xirrPct()).isEqualByComparingTo(nettoPos.xirrPct());

        BigDecimal openTax = brutto.summary().totalValue().subtract(netto.summary().totalValue());
        BigDecimal settledTax = netto.summary().taxPaid().subtract(openTax);
        assertThat(settledTax).isEqualByComparingTo("38.00");

        List<MetricsCalculator.CashFlow> expectedNetFlows = List.of(
                new MetricsCalculator.CashFlow(-2000.0, buy.getExecutedAt()),
                new MetricsCalculator.CashFlow(1200.0, partialSell.getExecutedAt()),
                new MetricsCalculator.CashFlow(-settledTax.doubleValue(), partialSell.getExecutedAt()));
        BigDecimal expectedXirr = ValuationSupport.toXirrPct(
                MetricsCalculator.calculateHoldingXirr(expectedNetFlows, netto.summary().totalValue().doubleValue(), NOW, 30));
        assertThat(nettoPos.xirrPct()).isCloseTo(expectedXirr, org.assertj.core.data.Offset.offset(new BigDecimal("0.05")));
    }

    @Test
    void dividendBelkaIsNotOffsetByCapitalLossInPositionTax() {
        Portfolio pf = portfolio(Currency.PLN);
        Instrument pkn = instrument("PKN.WA", "Orlen", InstrumentType.STOCK, Currency.PLN);

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
        buy.setExecutedAt(LocalDateTime.of(2026, 1, 10, 10, 0));

        Transaction div = new Transaction();
        div.setId(UUID.randomUUID());
        div.setPortfolio(pf);
        div.setInstrumentId(pkn.getId());
        div.setTransactionType(TransactionType.DIVIDEND);
        div.setQuantity(new BigDecimal("10"));
        div.setPrice(new BigDecimal("10"));
        div.setCommission(BigDecimal.ZERO);
        div.setCurrency(Currency.PLN);
        div.setExchangeRate(BigDecimal.ONE);
        div.setBaseAmount(new BigDecimal("100"));
        div.setExchangeRateToBase(BigDecimal.ONE);
        div.setExecutedAt(LocalDateTime.of(2026, 3, 15, 10, 0));

        Map<String, pl.investassist.portfolio.port.MarketDataPort.MarketQuote> quotes = Map.of(
                "PKN.WA", new pl.investassist.portfolio.port.MarketDataPort.MarketQuote("PKN.WA", new BigDecimal("80"), new BigDecimal("80"), "PLN", false));

        PortfolioValuationResponse resp = ValuationEngine.evaluate(
                pf,
                List.of(buy, div),
                Map.of(pkn.getId(), pkn),
                quotes,
                Map.of(),
                new BigDecimal("3.0"),
                TaxMode.NETTO,
                NOW);

        assertThat(resp.summary().taxPaid()).isEqualByComparingTo("19.00");
        assertThat(resp.summary().dividendsTotal()).isEqualByComparingTo("100.00");
        assertThat(resp.summary().grossProfit()).isEqualByComparingTo("-100.00");
        assertThat(resp.summary().netProfit()).isEqualByComparingTo("-119.00");
        assertThat(resp.summary().totalValue()).isEqualByComparingTo("800.00");
        assertThat(resp.positions().getFirst().valueBase()).isEqualByComparingTo("800.00");
        assertThat(resp.positions().getFirst().profitBase()).isEqualByComparingTo("-119.00");
    }

    @Test
    void stockWithNonBlankSeriesNameStaysEquityAndUsesMarketQuote() {
        Portfolio pf = portfolio(Currency.PLN);
        Instrument cdr = instrument("CDR.WA", "CD Projekt", InstrumentType.STOCK, Currency.PLN);
        cdr.setAssetCategory(pl.investassist.common.enums.AssetCategory.PL_STOCKS);

        Transaction buy = new Transaction();
        buy.setId(UUID.randomUUID());
        buy.setPortfolio(pf);
        buy.setInstrumentId(cdr.getId());
        buy.setTransactionType(TransactionType.BUY);
        buy.setQuantity(new BigDecimal("5"));
        buy.setPrice(new BigDecimal("150"));
        buy.setCommission(BigDecimal.ZERO);
        buy.setCurrency(Currency.PLN);
        buy.setExchangeRate(BigDecimal.ONE);
        buy.setBaseAmount(new BigDecimal("750"));
        buy.setExchangeRateToBase(BigDecimal.ONE);
        buy.setExecutedAt(LocalDateTime.of(2026, 1, 10, 10, 0));
        buy.setSeriesName("TRANCHE-A");

        Map<String, pl.investassist.portfolio.port.MarketDataPort.MarketQuote> quotes = Map.of(
                "CDR.WA", new pl.investassist.portfolio.port.MarketDataPort.MarketQuote("CDR.WA", new BigDecimal("200"), new BigDecimal("195"), "PLN", false));

        PortfolioValuationResponse resp = ValuationEngine.evaluate(
                pf,
                List.of(buy),
                Map.of(cdr.getId(), cdr),
                quotes,
                Map.of(),
                null,
                TaxMode.BRUTTO,
                NOW);

        assertThat(resp.positions()).hasSize(1);
        ValuationPositionDto pos = resp.positions().getFirst();
        assertThat(pos.categoryCode()).isEqualTo("PL_STOCKS");
        assertThat(pos.bondFamily()).isNull();
        assertThat(pos.valueBase()).isEqualByComparingTo("1000.00");
    }

    @Test
    void calculateTwrUsesUniformSubPeriodFormulaAndTreatsZeroSubReturnAsTotalLoss() {
        double exactFlowMatch = MetricsCalculator.calculateTwr(List.of(
                new MetricsCalculator.TwrPeriod(1000.0, 500.0, 500.0)));
        assertThat(exactFlowMatch).isCloseTo(-1.0, org.assertj.core.data.Offset.offset(1e-9));

        double withZeroPeriod = MetricsCalculator.calculateTwr(List.of(
                new MetricsCalculator.TwrPeriod(1000.0, 1200.0, 0.0),
                new MetricsCalculator.TwrPeriod(100.0, 0.0, 0.0)));
        assertThat(withZeroPeriod).isCloseTo(-1.0, org.assertj.core.data.Offset.offset(1e-9));
    }

    @Test
    void sharedPlBondInstrumentRoutesScopedDividendsAndSplitsUnscopedAcrossFamiliesWithoutXirrDoubleCount() {
        Portfolio pf = portfolio(Currency.PLN);
        Instrument plBond = instrument("PL_BOND", "Obligacje Skarbowe", InstrumentType.BOND, Currency.PLN);
        LocalDateTime buyDate = NOW.minusMonths(6);

        Transaction buyEdo = tx(pf, plBond.getId(), TransactionType.BUY, 10, 100.0, Currency.PLN, 1.0, 1000.0, buyDate);
        buyEdo.setSeriesName("EDO0635");
        buyEdo.setBondRate(new BigDecimal("6.80"));
        buyEdo.setBondMargin(new BigDecimal("1.50"));
        buyEdo.setTermMonths(120);

        Transaction buyCoi = tx(pf, plBond.getId(), TransactionType.BUY, 10, 100.0, Currency.PLN, 1.0, 1000.0, buyDate);
        buyCoi.setSeriesName("COI0629");
        buyCoi.setBondRate(new BigDecimal("6.20"));
        buyCoi.setBondMargin(new BigDecimal("1.25"));
        buyCoi.setTermMonths(48);

        Transaction divCoi = tx(pf, plBond.getId(), TransactionType.DIVIDEND, 1, 100.0, Currency.PLN, 1.0, 100.0, NOW.minusMonths(2));
        divCoi.setSeriesName("COI0629");

        PortfolioValuationResponse scopedResp = ValuationEngine.evaluate(
                pf,
                List.of(buyEdo, buyCoi, divCoi),
                Map.of(plBond.getId(), plBond),
                Map.of(),
                Map.of(),
                new BigDecimal("3.0"),
                TaxMode.NETTO,
                NOW);

        ValuationPositionDto coiPos = scopedResp.positions().stream()
                .filter(p -> "COI".equals(p.bondFamily())).findFirst().orElseThrow();
        ValuationPositionDto edoPos = scopedResp.positions().stream()
                .filter(p -> "EDO".equals(p.bondFamily())).findFirst().orElseThrow();

        assertThat(coiPos.dividendsBase()).isEqualByComparingTo("100.00");
        assertThat(edoPos.dividendsBase()).isEqualByComparingTo("0.00");
        assertThat(coiPos.xirrPct()).isGreaterThan(edoPos.xirrPct());
        assertThat(scopedResp.summary().dividendsTotal()).isEqualByComparingTo("100.00");

        PortfolioTimelineResponse timeline = TimelineEngine.build(
                pf,
                List.of(buyEdo, buyCoi, divCoi),
                Map.of(plBond.getId(), plBond),
                Map.of(),
                Map.of(),
                Map.of(),
                new BigDecimal("3.0"),
                Timeframe.MAX,
                TaxMode.NETTO,
                NOW);
        assertThat(timeline.contributors()).extracting(PortfolioTimelineResponse.ProfitContributorDto::symbol)
                .containsExactlyInAnyOrder("COI", "EDO");
        assertThat(timeline.contributors()).noneMatch(c -> c.name().startsWith("Obligacje "));

        Transaction divUnscoped = tx(pf, plBond.getId(), TransactionType.DIVIDEND, 1, 30.0, Currency.PLN, 1.0, 30.0, NOW.minusMonths(1));
        PortfolioValuationResponse mixedResp = ValuationEngine.evaluate(
                pf,
                List.of(buyEdo, buyCoi, divCoi, divUnscoped),
                Map.of(plBond.getId(), plBond),
                Map.of(),
                Map.of(),
                new BigDecimal("3.0"),
                TaxMode.NETTO,
                NOW);

        ValuationPositionDto coiMixed = mixedResp.positions().stream()
                .filter(p -> "COI".equals(p.bondFamily())).findFirst().orElseThrow();
        ValuationPositionDto edoMixed = mixedResp.positions().stream()
                .filter(p -> "EDO".equals(p.bondFamily())).findFirst().orElseThrow();
        assertThat(coiMixed.dividendsBase().add(edoMixed.dividendsBase())).isEqualByComparingTo("130.00");
        assertThat(edoMixed.dividendsBase()).isGreaterThan(BigDecimal.ZERO);
        assertThat(edoMixed.dividendsBase()).isLessThan(new BigDecimal("30.00"));
        assertThat(mixedResp.summary().dividendsTotal()).isEqualByComparingTo("130.00");
    }

    @Test
    void resolveCategoryCodeUsesExplicitCategoryAndNeverGuessesFromTicker() {
        Instrument team = instrument("TEAM", "Atlassian", InstrumentType.STOCK, Currency.USD);
        Instrument eimi = instrument("EIMI.L", "iShares Core MSCI EM", InstrumentType.ETF, Currency.USD);
        eimi.setAssetCategory(pl.investassist.common.enums.AssetCategory.EMERGING_MARKETS);
        Instrument bond = instrument("EDO", "Obligacje", InstrumentType.BOND, Currency.PLN);

        assertThat(ValuationSupport.resolveCategoryCode(team, null, false)).isEqualTo("OTHER");
        assertThat(ValuationSupport.resolveCategoryCode(eimi, null, false))
                .isEqualTo(pl.investassist.common.enums.AssetCategory.EMERGING_MARKETS.name());
        assertThat(ValuationSupport.resolveCategoryCode(bond, null, true)).isEqualTo("TREASURY_BONDS");
    }

    @Test
    void series_lookupBeforeFirstPointReturnsNull() {
        var prices = new java.util.TreeMap<java.time.LocalDate, java.math.BigDecimal>();
        prices.put(java.time.LocalDate.of(2026, 2, 1), new java.math.BigDecimal("100.00"));
        var history = new pl.investassist.portfolio.port.MarketDataPort.PriceHistory("CDR.WA", "PLN", prices);
        assertThat(history.resolveCloseOnOrBefore(java.time.LocalDate.of(2026, 1, 15), null)).isNull();
        assertThat(history.resolveCloseOnOrBefore(java.time.LocalDate.of(2026, 2, 1), null)).isEqualByComparingTo("100.00");

        var fxRates = new java.util.TreeMap<java.time.LocalDate, java.math.BigDecimal>();
        fxRates.put(java.time.LocalDate.of(2026, 2, 1), new java.math.BigDecimal("4.20"));
        var fxSeries = new pl.investassist.portfolio.port.MarketDataPort.FxSeries("EUR", "PLN", new java.math.BigDecimal("4.25"), fxRates);
        assertThat(fxSeries.resolveRateOnOrBefore(java.time.LocalDate.of(2026, 1, 15), null)).isNull();
        assertThat(fxSeries.resolveRate(java.time.LocalDate.of(2026, 1, 15), false)).isNull();
        assertThat(fxSeries.resolveRate(java.time.LocalDate.of(2026, 1, 15), true)).isEqualByComparingTo("4.25");
    }

    @Test
    void tax_annualNettingLossCarryForwardWhtCreditAndSeedRate() {
        Portfolio pf = portfolio(Currency.PLN);
        Instrument win = instrument("PKO.WA", "PKO", InstrumentType.STOCK, Currency.PLN);
        Instrument lose = instrument("CDR.WA", "CDR", InstrumentType.STOCK, Currency.PLN);
        Instrument us = instrument("AAPL", "Apple", InstrumentType.STOCK, Currency.USD);

        // 2021 realized loss -1000 PLN, 2022 realized gain +600 PLN -> art. 9 ust. 3 caps offset at 50% (-500) -> 100 taxable -> 19.00 tax
        Transaction buyLose21 = tx(pf, lose.getId(), TransactionType.BUY, 10, 200.0, Currency.PLN, 1.0, 2000.0, LocalDateTime.of(2021, 1, 10, 10, 0));
        Transaction sellLose21 = tx(pf, lose.getId(), TransactionType.SELL, 10, 100.0, Currency.PLN, 1.0, 1000.0, LocalDateTime.of(2021, 6, 10, 10, 0));
        Transaction buyWin22 = tx(pf, win.getId(), TransactionType.BUY, 10, 100.0, Currency.PLN, 1.0, 1000.0, LocalDateTime.of(2022, 1, 10, 10, 0));
        Transaction sellWin22 = tx(pf, win.getId(), TransactionType.SELL, 10, 160.0, Currency.PLN, 1.0, 1600.0, LocalDateTime.of(2022, 6, 10, 10, 0));

        // 2022 US dividend 100 PLN with 15% WHT -> art. 30a ust. 9 top-up 4% = 4.00 PLN (never offset by 2021 art. 30b loss)
        Transaction divUs = tx(pf, us.getId(), TransactionType.DIVIDEND, 1, 100.0, Currency.PLN, 1.0, 100.0, LocalDateTime.of(2022, 8, 1, 10, 0));
        divUs.setWithholdingTaxPct(new BigDecimal("15.00"));

        PortfolioValuationResponse netto = ValuationEngine.evaluate(
                pf,
                List.of(buyLose21, sellLose21, buyWin22, sellWin22, divUs),
                Map.of(win.getId(), win, lose.getId(), lose, us.getId(), us),
                Map.of(),
                Map.of(),
                null,
                TaxMode.NETTO,
                NOW);
        assertThat(netto.summary().taxPaid()).isEqualByComparingTo("23.00");
        assertThat(netto.projectionSeed().belkaTaxRate()).isEqualByComparingTo("0.19");

        PortfolioValuationResponse brutto = ValuationEngine.evaluate(
                pf,
                List.of(buyLose21, sellLose21, buyWin22, sellWin22, divUs),
                Map.of(win.getId(), win, lose.getId(), lose, us.getId(), us),
                Map.of(),
                Map.of(),
                null,
                TaxMode.BRUTTO,
                NOW);
        assertThat(brutto.summary().taxPaid()).isEqualByComparingTo("0.00");
        assertThat(brutto.projectionSeed().belkaTaxRate()).isEqualByComparingTo("0.00");
    }

    @Test
    void equityDailyChangePctWithBelkaInverseFxAndShortTermBondFamiliesWorkAccurately() {
        Portfolio pf = portfolio(Currency.PLN);
        Instrument nvda = instrument("NVDA", "NVIDIA Corp", InstrumentType.STOCK, Currency.USD);
        Transaction buy = tx(pf, nvda.getId(), TransactionType.BUY, 2, 100.0, Currency.USD, 4.0, 800.0, NOW.minusDays(45));

        FakeMarketDataPort market = new FakeMarketDataPort()
                .withQuote("NVDA", 110.0, 100.0, "USD")
                .withFx("PLN", "USD", 0.25, Map.of());

        PortfolioValuationResponse resp = ValuationEngine.evaluate(
                pf,
                List.of(buy),
                Map.of(nvda.getId(), nvda),
                market.getBatchQuotes(List.of("NVDA")),
                market.getBatchFxHistory(
                        List.of(new MarketDataPort.CurrencyPair("PLN", "USD")),
                        NOW.minusDays(50).toLocalDate(), NOW.toLocalDate()),
                new BigDecimal("3.0"),
                TaxMode.NETTO,
                NOW);

        ValuationPositionDto pos = resp.positions().getFirst();
        assertThat(pos.valueBase()).isEqualByComparingTo("880.00");
        assertThat(pos.dailyChangeBase()).isEqualByComparingTo("80.00");
        assertThat(pos.dailyChangePct()).isEqualByComparingTo("10.00");
        assertThat(resp.summary().taxPaid()).isEqualByComparingTo("15.20");
        assertThat(resp.summary().netProfit()).isEqualByComparingTo("64.80");

        LocalDateTime purchase = LocalDateTime.of(2024, 1, 10, 10, 0);
        double edoTwoYears = BondValuationEngine.calculateUnitPrice(
                purchase, new BigDecimal("6.80"), new BigDecimal("1.50"), new BigDecimal("3.60"),
                purchase.plusYears(2), "EDO0134", 120);
        assertThat(edoTwoYears).isCloseTo(100.0 * 1.068 * 1.051, org.assertj.core.data.Offset.offset(0.01));

        java.util.NavigableMap<LocalDate, BigDecimal> nbpRates = new java.util.TreeMap<>();
        nbpRates.put(LocalDate.of(2024, 1, 1), new BigDecimal("5.75"));

        BondValuationEngine.LotValuation rorSixMonths = BondValuationEngine.evaluateLot(
                purchase, new BigDecimal("5.75"), BigDecimal.ZERO, Map.of(), nbpRates,
                purchase.plusMonths(6), "ROR0125", 12);
        double rorTotal = rorSixMonths.unitPriceNative() + rorSixMonths.completedCoupons().stream()
                .mapToDouble(BondValuationEngine.CompletedCoupon::grossCouponNative).sum();
        assertThat(rorSixMonths.unitPriceNative()).isCloseTo(100.0, org.assertj.core.data.Offset.offset(0.001));
        assertThat(rorTotal).isGreaterThan(102.5).isLessThan(103.5);

        BondValuationEngine.LotValuation dorHalfYear = BondValuationEngine.evaluateLot(
                purchase, new BigDecimal("5.90"), new BigDecimal("0.15"), Map.of(), nbpRates,
                purchase.plusMonths(6), "DOR0126", 24);
        double dorTotal = dorHalfYear.unitPriceNative() + dorHalfYear.completedCoupons().stream()
                .mapToDouble(BondValuationEngine.CompletedCoupon::grossCouponNative).sum();
        assertThat(dorHalfYear.unitPriceNative()).isCloseTo(100.0, org.assertj.core.data.Offset.offset(0.001));
        assertThat(dorTotal).isGreaterThan(102.5).isLessThan(103.5);

        double otsMaturity = BondValuationEngine.calculateUnitPrice(
                purchase, new BigDecimal("3.00"), null, null,
                purchase.plusMonths(6), "OTS0424", 3);
        double otsAtThreeMonths = BondValuationEngine.calculateUnitPrice(
                purchase, new BigDecimal("3.00"), null, null,
                purchase.plusMonths(3), "OTS0424", 3);
        assertThat(otsMaturity).isCloseTo(otsAtThreeMonths, org.assertj.core.data.Offset.offset(0.001));
        assertThat(otsAtThreeMonths).isCloseTo(100.75, org.assertj.core.data.Offset.offset(0.001));
    }

    @Test
    void edo_historicalPerPeriodCpiWithOneMonthLag() {
        LocalDateTime purchase = LocalDateTime.of(2024, 6, 15, 0, 0);
        Map<Integer, Map<Integer, BigDecimal>> monthlyCpi = Map.of(
                2025, Map.of(5, new BigDecimal("4.00"), 6, new BigDecimal("9.99")),
                2026, Map.of(5, new BigDecimal("2.50")));
        BondValuationEngine.LotValuation val = BondValuationEngine.evaluateLot(
                purchase,
                new BigDecimal("6.80"),
                new BigDecimal("1.50"),
                monthlyCpi,
                new java.util.TreeMap<>(),
                LocalDateTime.of(2026, 6, 15, 0, 0),
                "EDO0634",
                120);
        assertThat(val.rateUnavailable()).isFalse();
        assertThat(val.unitPriceNative()).isCloseTo(100.0 * 1.068 * 1.055, org.assertj.core.data.Offset.offset(0.01));
    }

    @Test
    void coi_preservesCompletedCouponsAsSettledIncomeAndResetsOpenAccrual() {
        Portfolio pf = portfolio(Currency.PLN);
        Instrument coi = instrument("PL_BOND", "Obligacje COI", InstrumentType.BOND, Currency.PLN);
        Transaction buy = tx(pf, coi.getId(), TransactionType.BUY, 10, 100.0, Currency.PLN, 1.0, 1000.0,
                LocalDateTime.of(2025, 6, 15, 0, 0));
        buy.setSeriesName("COI0629");
        buy.setBondRate(new BigDecimal("6.25"));
        buy.setBondMargin(new BigDecimal("1.50"));
        buy.setTermMonths(48);

        Map<Integer, Map<Integer, BigDecimal>> monthlyCpi = Map.of(2026, Map.of(5, new BigDecimal("3.00")));
        PortfolioValuationResponse resp = ValuationEngine.evaluate(
                pf, List.of(buy), Map.of(coi.getId(), coi), Map.of(), Map.of(),
                new BigDecimal("3.00"), monthlyCpi, new java.util.TreeMap<>(),
                TaxMode.NETTO, LocalDateTime.of(2026, 6, 15, 12, 0));

        assertThat(resp.positions()).hasSize(1);
        ValuationPositionDto pos = resp.positions().getFirst();
        assertThat(pos.valueBase()).isEqualByComparingTo("1000.00");
        assertThat(pos.dividendsBase()).isEqualByComparingTo("62.50");
        assertThat(resp.summary().dividendsTotal()).isEqualByComparingTo("62.50");
        assertThat(resp.summary().taxPaid()).isEqualByComparingTo("11.88");
    }

    @Test
    void rorAndDor_monthlyPeriodsUseNbpRefRateNotCpi() {
        LocalDateTime purchase = LocalDateTime.of(2025, 1, 10, 0, 0);
        java.util.NavigableMap<LocalDate, BigDecimal> nbpRates = new java.util.TreeMap<>();
        nbpRates.put(LocalDate.of(2025, 1, 1), new BigDecimal("5.75"));
        nbpRates.put(LocalDate.of(2025, 2, 1), new BigDecimal("5.25"));

        BondValuationEngine.LotValuation ror = BondValuationEngine.evaluateLot(
                purchase, new BigDecimal("5.95"), BigDecimal.ZERO, Map.of(), nbpRates,
                LocalDateTime.of(2025, 3, 10, 0, 0), "ROR0126", 12);
        assertThat(ror.rateUnavailable()).isFalse();
        assertThat(ror.unitPriceNative()).isCloseTo(100.0, org.assertj.core.data.Offset.offset(0.001));
        assertThat(ror.completedCoupons()).hasSize(2);
        double expectedM1 = 100.0 * 0.0595 * (31.0 / 365.0);
        double expectedM2 = 100.0 * 0.0525 * (28.0 / 365.0);
        assertThat(ror.completedCoupons().get(0).grossCouponNative()).isCloseTo(expectedM1, org.assertj.core.data.Offset.offset(0.001));
        assertThat(ror.completedCoupons().get(1).grossCouponNative()).isCloseTo(expectedM2, org.assertj.core.data.Offset.offset(0.001));

        BondValuationEngine.LotValuation dorMissing = BondValuationEngine.evaluateLot(
                purchase, new BigDecimal("6.10"), new BigDecimal("0.15"), Map.of(), new java.util.TreeMap<>(),
                LocalDateTime.of(2025, 3, 10, 0, 0), "DOR0127", 24);
        assertThat(dorMissing.rateUnavailable()).isTrue();
    }

    @Test
    void bondMissingRateAndUnknownSeries() {
        BondValuationEngine.LotValuation unknown = BondValuationEngine.evaluateLot(
                LocalDateTime.of(2025, 1, 1, 0, 0), new BigDecimal("6.0"), null,
                Map.of(), new java.util.TreeMap<>(), LocalDateTime.of(2025, 6, 1, 0, 0),
                "XYZ0130", null);
        assertThat(unknown.rateUnavailable()).isTrue();
        assertThat(unknown.unitPriceNative()).isCloseTo(100.0, org.assertj.core.data.Offset.offset(0.001));

        BondValuationEngine.LotValuation genericSymbol = BondValuationEngine.evaluateLot(
                LocalDateTime.of(2026, 10, 9, 12, 0), new BigDecimal("4.40"), null,
                Map.of(), new java.util.TreeMap<>(), LocalDateTime.of(2026, 11, 14, 12, 0),
                "PL_BOND", 36);
        assertThat(genericSymbol.rateUnavailable()).isFalse();
        assertThat(genericSymbol.unitPriceNative()).isGreaterThan(100.0);
    }

    @Test
    void bondSubPosition_exactBuyDateAndPrice() {
        Portfolio pf = portfolio(Currency.PLN);
        Instrument bond = instrument("PL_BOND", "Obligacje", InstrumentType.BOND, Currency.PLN);
        Transaction buy = tx(pf, bond.getId(), TransactionType.BUY, 10, 99.50, Currency.PLN, 1.0, 995.0,
                LocalDateTime.of(2026, 3, 17, 12, 0));
        buy.setSeriesName("TOS0329");
        buy.setBondRate(new BigDecimal("5.65"));
        buy.setTermMonths(36);

        PortfolioValuationResponse resp = ValuationEngine.evaluate(
                pf, List.of(buy), Map.of(bond.getId(), bond), Map.of(), Map.of(),
                null, TaxMode.NETTO, LocalDateTime.of(2026, 3, 17, 12, 0));
        assertThat(resp.positions()).hasSize(1);
        PortfolioValuationResponse.ValuationSubPositionDto sub = resp.positions().getFirst().subPositions().getFirst();
        assertThat(sub.label()).isEqualTo("TOS0329");
        assertThat(sub.date()).isEqualTo(LocalDate.of(2026, 3, 17));
        assertThat(sub.txPrice()).isEqualByComparingTo("99.5000");
    }

    @Test
    void xirr_datesSettledTaxOnEventDateAndReturnsNullUnder30Days() {
        List<MetricsCalculator.CashFlow> baseFlows = List.of(
                new MetricsCalculator.CashFlow(-1000.0, NOW.minusDays(365)));
        List<MetricsCalculator.CashFlow> taxedFlows = ValuationSupport.withSettledTaxFlow(
                baseFlows, new BigDecimal("50.00"), NOW.minusDays(180));
        assertThat(taxedFlows).hasSize(2);
        assertThat(taxedFlows.get(1).amount()).isEqualTo(-50.0);
        assertThat(taxedFlows.get(1).date()).isEqualTo(NOW.minusDays(180));

        MetricsCalculator.XirrResult grossXirr = MetricsCalculator.calculateHoldingXirr(baseFlows, 1200.0, NOW, 30);
        MetricsCalculator.XirrResult netXirr = MetricsCalculator.calculateHoldingXirr(taxedFlows, 1200.0, NOW, 30);
        assertThat(grossXirr.xirr()).isNotNull();
        assertThat(netXirr.xirr()).isNotNull().isLessThan(grossXirr.xirr());

        MetricsCalculator.XirrResult shortWindow = MetricsCalculator.calculateHoldingXirr(
                List.of(new MetricsCalculator.CashFlow(-1000.0, NOW.minusDays(10))),
                1050.0,
                NOW,
                30);
        assertThat(shortWindow.xirr()).isNull();
    }
}
