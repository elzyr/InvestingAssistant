package pl.investassist.portfolio.service;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.concurrent.ConcurrentMapCacheManager;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.SimpleTransactionStatus;
import pl.investassist.common.enums.Currency;
import pl.investassist.common.enums.InstrumentType;
import pl.investassist.common.exception.MarketDataUnavailableException;
import pl.investassist.config.CacheConfig;
import pl.investassist.instrument.entity.Instrument;
import pl.investassist.instrument.repository.InstrumentRepository;
import pl.investassist.portfolio.dto.response.AnnualReportResponse;
import pl.investassist.portfolio.dto.response.PortfolioTimelineResponse;
import pl.investassist.portfolio.dto.response.PortfolioValuationResponse;
import pl.investassist.portfolio.entity.Portfolio;
import pl.investassist.portfolio.entity.Transaction;
import pl.investassist.portfolio.enums.TransactionType;
import pl.investassist.portfolio.port.MarketDataPort;
import pl.investassist.portfolio.repository.TransactionRepository;
import pl.investassist.portfolio.valuation.AnnualReportEngine;
import pl.investassist.portfolio.valuation.TaxMode;
import pl.investassist.portfolio.valuation.Timeframe;
import pl.investassist.portfolio.valuation.TimelineEngine;
import pl.investassist.portfolio.valuation.ValuationEngine;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PortfolioCacheAndSerializationTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 4, 15, 12, 0);

    @Test
    void springCacheable_closesReadOnlyTxBeforeHttpAndRecomputesAcrossAnyYearAfterEvict() {
        TransactionRepository txRepo = mock(TransactionRepository.class);
        InstrumentRepository instRepo = mock(InstrumentRepository.class);
        MarketDataPort marketDataPort = mock(MarketDataPort.class);
        PlatformTransactionManager txManager = mock(PlatformTransactionManager.class);

        Portfolio portfolio = samplePortfolio(UUID.randomUUID());
        Instrument etf = sampleInstrument("SXR8.DE", InstrumentType.ETF, Currency.EUR);
        Transaction buy = sampleTx(portfolio, etf.getId(), TransactionType.BUY,
                new BigDecimal("1"), new BigDecimal("400"), Currency.EUR, new BigDecimal("4.20"),
                LocalDateTime.of(2026, 1, 10, 10, 0), null, null, null);

        when(txManager.getTransaction(any())).thenAnswer(inv -> new SimpleTransactionStatus());
        when(txRepo.findAllByPortfolioIdOrderByExecutedAtAsc(portfolio.getId())).thenReturn(List.of(buy));
        when(instRepo.findAllById(anyCollection())).thenReturn(List.of(etf));
        when(marketDataPort.getBatchQuotes(anyCollection())).thenReturn(Map.of(
                "SXR8.DE", new MarketDataPort.MarketQuote("SXR8.DE", new BigDecimal("450"), new BigDecimal("445"), "EUR", false)));
        when(marketDataPort.getBatchHistory(anyCollection(), any())).thenReturn(Map.of());
        when(marketDataPort.getBatchFxHistory(anyCollection(), any(), any())).thenReturn(Map.of(
                "EUR:PLN", new MarketDataPort.FxSeries("EUR", "PLN", new BigDecimal("4.25"), new TreeMap<>(Map.of(
                        LocalDate.of(2026, 1, 1), new BigDecimal("4.25"),
                        LocalDate.of(2026, 4, 15), new BigDecimal("4.25"))))));
        when(marketDataPort.getCurrentCpi()).thenReturn(new BigDecimal("3.0"));
        when(marketDataPort.getInflation()).thenReturn(new MarketDataPort.InflationSnapshot(new BigDecimal("3.0"), Map.of()));

        try (AnnotationConfigApplicationContext ctx = new AnnotationConfigApplicationContext()) {
            ctx.registerBean(TransactionRepository.class, () -> txRepo);
            ctx.registerBean(InstrumentRepository.class, () -> instRepo);
            ctx.registerBean(MarketDataPort.class, () -> marketDataPort);
            ctx.registerBean(PlatformTransactionManager.class, () -> txManager);
            ctx.register(TestCacheWiringConfig.class);
            ctx.refresh();

            PortfolioAnalyticsService analytics = ctx.getBean(PortfolioAnalyticsService.class);
            PortfolioCacheService cacheService = ctx.getBean(PortfolioCacheService.class);

            analytics.getValuation(portfolio, TaxMode.NETTO);
            analytics.getValuation(portfolio, TaxMode.NETTO);
            verify(txRepo, times(1)).findAllByPortfolioIdOrderByExecutedAtAsc(portfolio.getId());

            ArgumentCaptor<TransactionDefinition> txDefCaptor = ArgumentCaptor.forClass(TransactionDefinition.class);
            verify(txManager).getTransaction(txDefCaptor.capture());
            assertThat(txDefCaptor.getValue().isReadOnly()).isTrue();

            InOrder inOrder = inOrder(txRepo, txManager, marketDataPort);
            inOrder.verify(txRepo).findAllByPortfolioIdOrderByExecutedAtAsc(portfolio.getId());
            inOrder.verify(txManager).commit(any());
            inOrder.verify(marketDataPort).getBatchQuotes(anyCollection());

            analytics.getTimeline(portfolio, Timeframe.YTD, TaxMode.NETTO);
            analytics.getTimeline(portfolio, Timeframe.YTD, TaxMode.NETTO);
            verify(txRepo, times(2)).findAllByPortfolioIdOrderByExecutedAtAsc(portfolio.getId());

            analytics.getAnnualReport(portfolio, 2005, TaxMode.NETTO);
            analytics.getAnnualReport(portfolio, 2005, TaxMode.NETTO);
            verify(txRepo, times(3)).findAllByPortfolioIdOrderByExecutedAtAsc(portfolio.getId());

            cacheService.evictPortfolio(portfolio.getId());
            assertThat(cacheService.versionOf(portfolio.getId())).isEqualTo("1");

            analytics.getValuation(portfolio, TaxMode.NETTO);
            analytics.getTimeline(portfolio, Timeframe.YTD, TaxMode.NETTO);
            analytics.getAnnualReport(portfolio, 2005, TaxMode.NETTO);
            verify(txRepo, times(6)).findAllByPortfolioIdOrderByExecutedAtAsc(portfolio.getId());
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void portfolioCacheServiceWithRedisTemplateRefreshesTtlOnEvictAndBypassesCacheOnRedisError() {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        ValueOperations<String, String> valueOps = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOps);

        UUID portfolioId = UUID.randomUUID();
        String expectedKey = CacheConfig.KEY_PREFIX + "portfolioVersion::" + portfolioId;
        when(valueOps.increment(expectedKey)).thenReturn(1L, 3L).thenThrow(new RuntimeException("Redis timeout"));
        when(valueOps.get(expectedKey)).thenReturn("3");

        PortfolioCacheService service = new PortfolioCacheService(redisTemplate);
        service.evictPortfolio(portfolioId);
        verify(redisTemplate, times(1)).expire(expectedKey, java.time.Duration.ofHours(24));
        assertThat(service.isCacheEnabled(portfolioId)).isTrue();

        service.evictPortfolio(portfolioId);
        verify(redisTemplate, times(2)).expire(expectedKey, java.time.Duration.ofHours(24));
        assertThat(service.versionOf(portfolioId)).isEqualTo("3");

        service.evictPortfolio(portfolioId);
        assertThat(service.isCacheEnabled(portfolioId)).isFalse();
    }

    @Test
    @SuppressWarnings("unchecked")
    void portfolioCacheServiceVersionOfTakesMaxOfLocalAndExpiredRedisRemote() {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        ValueOperations<String, String> valueOps = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOps);

        UUID portfolioId = UUID.randomUUID();
        String expectedKey = CacheConfig.KEY_PREFIX + "portfolioVersion::" + portfolioId;
        when(valueOps.increment(expectedKey)).thenReturn(5L);
        when(valueOps.get(expectedKey)).thenReturn("1");

        PortfolioCacheService service = new PortfolioCacheService(redisTemplate);
        service.evictPortfolio(portfolioId);
        assertThat(service.versionOf(portfolioId)).isEqualTo("5");
    }

    @Test
    @SuppressWarnings("unchecked")
    void evictPortfolio_whenRedisFailsAndLaterResetsBelowLocal_maintainsMonotonicVersions() {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        ValueOperations<String, String> valueOps = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOps);

        UUID portfolioId = UUID.randomUUID();
        String expectedKey = CacheConfig.KEY_PREFIX + "portfolioVersion::" + portfolioId;
        when(valueOps.increment(expectedKey))
                .thenThrow(new RuntimeException("Redis connection lost"))
                .thenReturn(1L);

        PortfolioCacheService service = new PortfolioCacheService(redisTemplate);
        service.evictPortfolio(portfolioId);
        assertThat(service.versionOf(portfolioId)).isEqualTo("1");

        service.evictPortfolio(portfolioId);
        verify(valueOps).set(expectedKey, "2", java.time.Duration.ofHours(24));
        when(valueOps.get(expectedKey)).thenReturn("2");
        assertThat(service.versionOf(portfolioId)).isEqualTo("2");
    }

    @Test
    void analyticsServiceFutureYearReportSkipsMarketDataCallsWithInvalidDateRange() {
        TransactionRepository txRepo = mock(TransactionRepository.class);
        InstrumentRepository instRepo = mock(InstrumentRepository.class);
        MarketDataPort marketDataPort = mock(MarketDataPort.class);
        PlatformTransactionManager txManager = mock(PlatformTransactionManager.class);
        when(txManager.getTransaction(any())).thenAnswer(inv -> new SimpleTransactionStatus());

        Portfolio portfolio = samplePortfolio(UUID.randomUUID());
        Instrument etf = sampleInstrument("SXR8.DE", InstrumentType.ETF, Currency.EUR);
        Transaction buy = sampleTx(portfolio, etf.getId(), TransactionType.BUY,
                new BigDecimal("1"), new BigDecimal("400"), Currency.EUR, new BigDecimal("4.20"),
                LocalDateTime.of(2026, 2, 10, 10, 0), null, null, null);
        when(txRepo.findAllByPortfolioIdOrderByExecutedAtAsc(portfolio.getId())).thenReturn(List.of(buy));
        when(instRepo.findAllById(anyCollection())).thenReturn(List.of(etf));

        Clock fixedClock = Clock.fixed(Instant.parse("2026-04-15T12:00:00Z"), ZoneOffset.UTC);
        PortfolioAnalyticsService service = new PortfolioAnalyticsService(
                txRepo, instRepo, marketDataPort, fixedClock, txManager);

        AnnualReportResponse futureReport = service.getAnnualReport(portfolio, 2027, TaxMode.NETTO);
        assertThat(futureReport.year()).isEqualTo(2027);
        assertThat(futureReport.months()).allMatch(m -> m.status() == AnnualReportResponse.MonthStatus.FUTURE);
        verify(marketDataPort, times(0)).getBatchFxHistory(any(), any(), any());
        verify(marketDataPort, times(0)).getBatchHistory(any(), any());
        verify(marketDataPort, times(0)).getBatchQuotes(any());
    }

    @Test
    @SuppressWarnings("unchecked")
    void analyticsServiceSkipsHttpOnEmptyPortfolioUsesOpenLotsEarliestFxDateAndUnwrapsCompletionException() {
        TransactionRepository txRepo = mock(TransactionRepository.class);
        InstrumentRepository instRepo = mock(InstrumentRepository.class);
        MarketDataPort marketDataPort = mock(MarketDataPort.class);
        PlatformTransactionManager txManager = mock(PlatformTransactionManager.class);
        when(txManager.getTransaction(any())).thenAnswer(inv -> new SimpleTransactionStatus());

        Portfolio portfolio = samplePortfolio(UUID.randomUUID());
        Clock fixedClock = Clock.fixed(Instant.parse("2026-04-15T12:00:00Z"), ZoneOffset.UTC);
        PortfolioAnalyticsService service = new PortfolioAnalyticsService(
                txRepo, instRepo, marketDataPort, fixedClock, txManager);

        when(txRepo.findAllByPortfolioIdOrderByExecutedAtAsc(portfolio.getId())).thenReturn(List.of());
        var emptyVal = service.getValuation(portfolio, TaxMode.NETTO);
        var emptyTimeline = service.getTimeline(portfolio, Timeframe.YTD, TaxMode.NETTO);
        var emptyReport = service.getAnnualReport(portfolio, 2026, TaxMode.NETTO);
        assertThat(emptyVal.positions()).isEmpty();
        assertThat(emptyTimeline.points()).isEmpty();
        assertThat(emptyReport.ytdPortfolioValue()).isEqualByComparingTo("0.00");
        org.mockito.Mockito.verifyNoInteractions(marketDataPort);

        Instrument closedEur = sampleInstrument("OLD.DE", InstrumentType.STOCK, Currency.EUR);
        Instrument openUsd = sampleInstrument("NVDA", InstrumentType.STOCK, Currency.USD);
        Transaction buyClosed = sampleTx(portfolio, closedEur.getId(), TransactionType.BUY,
                BigDecimal.ONE, new BigDecimal("100"), Currency.EUR, new BigDecimal("4.20"),
                LocalDateTime.of(2024, 1, 5, 10, 0), null, null, null);
        Transaction sellClosed = sampleTx(portfolio, closedEur.getId(), TransactionType.SELL,
                BigDecimal.ONE, new BigDecimal("120"), Currency.EUR, new BigDecimal("4.30"),
                LocalDateTime.of(2024, 6, 5, 10, 0), null, null, null);
        Transaction buyOpen = sampleTx(portfolio, openUsd.getId(), TransactionType.BUY,
                new BigDecimal("2"), new BigDecimal("100"), Currency.USD, new BigDecimal("4.00"),
                LocalDateTime.of(2025, 3, 20, 10, 0), null, null, null);

        when(txRepo.findAllByPortfolioIdOrderByExecutedAtAsc(portfolio.getId()))
                .thenReturn(List.of(buyClosed, sellClosed, buyOpen));
        when(instRepo.findAllById(anyCollection())).thenReturn(List.of(closedEur, openUsd));
        when(marketDataPort.getBatchQuotes(anyCollection())).thenReturn(Map.of(
                "NVDA", new MarketDataPort.MarketQuote("NVDA", new BigDecimal("110"), new BigDecimal("105"), "USD", false)));
        when(marketDataPort.getCurrentCpi()).thenReturn(new BigDecimal("3.2"));
        when(marketDataPort.getBatchFxHistory(anyCollection(), any(), any())).thenReturn(Map.of(
                "USD:PLN", new MarketDataPort.FxSeries("USD", "PLN", new BigDecimal("4.05"), new TreeMap<>(Map.of(
                        LocalDate.of(2026, 4, 15), new BigDecimal("4.05"))))));

        var valuation = service.getValuation(portfolio, TaxMode.NETTO);
        assertThat(valuation.positions()).hasSize(1);

        ArgumentCaptor<java.util.Collection<String>> symbolsCaptor = ArgumentCaptor.forClass(java.util.Collection.class);
        verify(marketDataPort).getBatchQuotes(symbolsCaptor.capture());
        assertThat(symbolsCaptor.getValue()).containsExactly("NVDA");

        ArgumentCaptor<LocalDate> fxFromCaptor = ArgumentCaptor.forClass(LocalDate.class);
        verify(marketDataPort).getBatchFxHistory(anyCollection(), fxFromCaptor.capture(), any());
        assertThat(fxFromCaptor.getValue()).isEqualTo(LocalDate.of(2025, 3, 10));
        verify(marketDataPort).getCurrentCpi();
        verify(marketDataPort, times(0)).getInflation();

        when(marketDataPort.getBatchQuotes(anyCollection()))
                .thenThrow(new MarketDataUnavailableException("Market data down"));
        var degraded = service.getValuation(portfolio, TaxMode.NETTO);
        assertThat(degraded.isDegraded()).isTrue();
        assertThat(degraded.positions()).hasSize(1);
        assertThat(degraded.positions().getFirst().isPriceUnavailable()).isTrue();

        org.mockito.Mockito.doReturn(Map.of(
                "NVDA", new MarketDataPort.MarketQuote("NVDA", new BigDecimal("110"), new BigDecimal("105"), "USD", false)))
                .when(marketDataPort).getBatchQuotes(anyCollection());
        when(marketDataPort.getBatchFxHistory(anyCollection(), any(), any()))
                .thenThrow(new MarketDataUnavailableException("FX service down"));
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.getValuation(portfolio, TaxMode.NETTO))
                .isExactlyInstanceOf(MarketDataUnavailableException.class)
                .hasMessage("FX service down");
    }

    @Test
    void springCacheable_doesNotCacheDegradedValuation() {
        TransactionRepository txRepo = mock(TransactionRepository.class);
        InstrumentRepository instRepo = mock(InstrumentRepository.class);
        MarketDataPort marketDataPort = mock(MarketDataPort.class);
        PlatformTransactionManager txManager = mock(PlatformTransactionManager.class);

        Portfolio portfolio = samplePortfolio(UUID.randomUUID());
        Instrument etf = sampleInstrument("SXR8.DE", InstrumentType.ETF, Currency.EUR);
        Transaction buy = sampleTx(portfolio, etf.getId(), TransactionType.BUY,
                new BigDecimal("1"), new BigDecimal("400"), Currency.EUR, new BigDecimal("4.20"),
                LocalDateTime.of(2026, 1, 10, 10, 0), null, null, null);

        when(txManager.getTransaction(any())).thenAnswer(inv -> new SimpleTransactionStatus());
        when(txRepo.findAllByPortfolioIdOrderByExecutedAtAsc(portfolio.getId())).thenReturn(List.of(buy));
        when(instRepo.findAllById(anyCollection())).thenReturn(List.of(etf));
        when(marketDataPort.getBatchQuotes(anyCollection())).thenThrow(new MarketDataUnavailableException("Down"));
        when(marketDataPort.getBatchFxHistory(anyCollection(), any(), any())).thenReturn(Map.of(
                "EUR:PLN", new MarketDataPort.FxSeries("EUR", "PLN", new BigDecimal("4.25"), new TreeMap<>(Map.of(
                        LocalDate.of(2026, 1, 1), new BigDecimal("4.25"))))));
        when(marketDataPort.getCurrentCpi()).thenReturn(new BigDecimal("3.0"));

        try (AnnotationConfigApplicationContext ctx = new AnnotationConfigApplicationContext()) {
            ctx.registerBean(TransactionRepository.class, () -> txRepo);
            ctx.registerBean(InstrumentRepository.class, () -> instRepo);
            ctx.registerBean(MarketDataPort.class, () -> marketDataPort);
            ctx.registerBean(PlatformTransactionManager.class, () -> txManager);
            ctx.register(TestCacheWiringConfig.class);
            ctx.refresh();

            PortfolioAnalyticsService analytics = ctx.getBean(PortfolioAnalyticsService.class);
            PortfolioValuationResponse first = analytics.getValuation(portfolio, TaxMode.NETTO);
            assertThat(first.isDegraded()).isTrue();

            org.mockito.Mockito.doReturn(Map.of(
                    "SXR8.DE", new MarketDataPort.MarketQuote("SXR8.DE", new BigDecimal("450"), new BigDecimal("445"), "EUR", false)))
                    .when(marketDataPort).getBatchQuotes(anyCollection());

            PortfolioValuationResponse second = analytics.getValuation(portfolio, TaxMode.NETTO);
            assertThat(second.isDegraded()).isFalse();
            verify(marketDataPort, times(2)).getBatchQuotes(anyCollection());
        }
    }

    @Configuration
    @EnableCaching
    @Import(CacheConfig.class)
    static class TestCacheWiringConfig {

        @Bean
        PortfolioCacheService portfolioCacheService() {
            return new PortfolioCacheService(null);
        }

        @Bean
        PortfolioAnalyticsService portfolioAnalyticsService(
                TransactionRepository transactionRepository,
                InstrumentRepository instrumentRepository,
                MarketDataPort marketDataPort,
                PlatformTransactionManager transactionManager) {
            Clock fixedClock = Clock.fixed(Instant.parse("2026-04-15T12:00:00Z"), ZoneOffset.UTC);
            return new PortfolioAnalyticsService(
                    transactionRepository, instrumentRepository, marketDataPort, fixedClock, transactionManager);
        }
    }

    private static Portfolio samplePortfolio(UUID portfolioId) {
        Portfolio p = new Portfolio();
        p.setId(portfolioId);
        p.setUserId(UUID.randomUUID());
        p.setName("Cache Test");
        p.setBaseCurrency(Currency.PLN);
        return p;
    }

    private static Instrument sampleInstrument(String symbol, InstrumentType type, Currency currency) {
        Instrument inst = new Instrument();
        inst.setId(UUID.randomUUID());
        inst.setSymbol(symbol);
        inst.setName(symbol);
        inst.setType(type);
        inst.setCurrency(currency);
        return inst;
    }

    private static Transaction sampleTx(
            Portfolio portfolio,
            UUID instrumentId,
            TransactionType type,
            BigDecimal qty,
            BigDecimal price,
            Currency currency,
            BigDecimal fxToBase,
            LocalDateTime executedAt,
            String seriesName,
            BigDecimal bondRate,
            BigDecimal bondMargin) {
        Transaction tx = new Transaction();
        tx.setId(UUID.randomUUID());
        tx.setPortfolio(portfolio);
        tx.setInstrumentId(instrumentId);
        tx.setTransactionType(type);
        tx.setQuantity(qty);
        tx.setPrice(price);
        tx.setCommission(BigDecimal.ZERO);
        tx.setCurrency(currency);
        tx.setExchangeRate(BigDecimal.ONE);
        tx.setExchangeRateToBase(fxToBase);
        tx.setBaseAmount(qty.multiply(price).multiply(fxToBase));
        tx.setExecutedAt(executedAt);
        tx.setSeriesName(seriesName);
        tx.setBondRate(bondRate);
        tx.setBondMargin(bondMargin);
        tx.setTermMonths(seriesName != null ? 120 : null);
        return tx;
    }
}
