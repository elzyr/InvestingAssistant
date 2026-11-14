package pl.investassist.portfolio.service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import pl.investassist.common.util.AsyncFutureUtils;
import pl.investassist.config.CacheConfig;
import pl.investassist.instrument.entity.Instrument;
import pl.investassist.instrument.repository.InstrumentRepository;
import pl.investassist.portfolio.dto.request.ProjectionRequest;
import pl.investassist.portfolio.dto.response.AnnualReportResponse;
import pl.investassist.portfolio.dto.response.PortfolioTimelineResponse;
import pl.investassist.portfolio.dto.response.PortfolioValuationResponse;
import pl.investassist.portfolio.dto.response.ProjectionResponse;
import pl.investassist.portfolio.entity.Portfolio;
import pl.investassist.portfolio.entity.Transaction;
import pl.investassist.portfolio.port.MarketDataPort;
import pl.investassist.portfolio.repository.TransactionRepository;
import pl.investassist.portfolio.valuation.AnnualReportEngine;
import pl.investassist.portfolio.valuation.BondValuationEngine;
import pl.investassist.portfolio.valuation.PortfolioLedger;
import pl.investassist.portfolio.valuation.TaxMode;
import pl.investassist.portfolio.valuation.TaxPolicy;
import pl.investassist.portfolio.valuation.Timeframe;
import pl.investassist.portfolio.valuation.TimelineEngine;
import pl.investassist.portfolio.valuation.ValuationEngine;
import pl.investassist.portfolio.valuation.ValuationSupport;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Supplier;
import java.util.stream.Collectors;

@Service
public class PortfolioAnalyticsService {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(PortfolioAnalyticsService.class);

    private final TransactionRepository transactionRepository;
    private final InstrumentRepository instrumentRepository;
    private final MarketDataPort marketDataPort;
    private final Clock clock;
    private final TransactionTemplate readOnlyTxTemplate;

    @Autowired
    public PortfolioAnalyticsService(
            TransactionRepository transactionRepository,
            InstrumentRepository instrumentRepository,
            MarketDataPort marketDataPort,
            PlatformTransactionManager transactionManager) {
        this(transactionRepository, instrumentRepository, marketDataPort, Clock.systemDefaultZone(), transactionManager);
    }

    PortfolioAnalyticsService(
            TransactionRepository transactionRepository,
            InstrumentRepository instrumentRepository,
            MarketDataPort marketDataPort,
            Clock clock,
            PlatformTransactionManager transactionManager) {
        this.transactionRepository = transactionRepository;
        this.instrumentRepository = instrumentRepository;
        this.marketDataPort = marketDataPort;
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        TransactionTemplate template = new TransactionTemplate(
                Objects.requireNonNull(transactionManager, "transactionManager must not be null"));
        template.setReadOnly(true);
        this.readOnlyTxTemplate = template;
    }

    private record DbPortfolioSnapshot(
            List<Transaction> transactions,
            Map<UUID, Instrument> instruments
    ) {
    }

    private record MarketContextScope(
            LocalDate historyStartDate,
            LocalDate historyEndDate,
            boolean includeHistory,
            boolean includeMonthlyInflation,
            boolean openPositionsOnly,
            boolean fetchQuotes
    ) {
        static MarketContextScope valuation() {
            return new MarketContextScope(null, null, false, false, true, true);
        }

        static MarketContextScope timeline(LocalDate historyStartDate) {
            return new MarketContextScope(historyStartDate, null, true, false, false, true);
        }

        static MarketContextScope annualReport(LocalDate historyStartDate, LocalDate historyEndDate, boolean fetchQuotes) {
            return new MarketContextScope(historyStartDate, historyEndDate, true, true, false, fetchQuotes);
        }
    }

    private record LoadedContext(
            List<Transaction> transactions,
            PortfolioLedger.IncrementalLedger precomputedLedger,
            Map<UUID, Instrument> instruments,
            Map<String, MarketDataPort.MarketQuote> quotes,
            Map<String, MarketDataPort.PriceHistory> histories,
            Map<String, MarketDataPort.FxSeries> fxSeries,
            MarketDataPort.InflationSnapshot inflation,
            java.util.NavigableMap<LocalDate, BigDecimal> nbpReferenceRates,
            LocalDateTime now
    ) {
    }

    @Cacheable(
            value = CacheConfig.CACHE_PORTFOLIO_VALUATION,
            condition = "@portfolioCacheService.isCacheEnabled(#portfolio.id)",
            unless = "#result == null || #result.isDegraded()",
            key = "#portfolio.id + ':v' + @portfolioCacheService.versionOf(#portfolio.id) + ':' + (#taxMode ?: 'NETTO')")
    public PortfolioValuationResponse getValuation(Portfolio portfolio, TaxMode taxMode) {
        return computeValuation(portfolio, TaxMode.orDefault(taxMode), LocalDateTime.now(clock));
    }

    private PortfolioValuationResponse computeValuation(
            Portfolio portfolio, TaxMode resolvedTaxMode, LocalDateTime now) {
        DbPortfolioSnapshot snapshot = executeReadOnly(() -> loadPortfolioSnapshot(portfolio.getId()));
        if (snapshot.transactions().isEmpty()) {
            return ValuationEngine.evaluate(
                    portfolio, List.of(), Map.of(), Map.of(), Map.of(), null, resolvedTaxMode, now);
        }
        LoadedContext ctx = fetchMarketContext(portfolio, snapshot, MarketContextScope.valuation(), now);
        BigDecimal currentCpi = ctx.inflation() != null ? ctx.inflation().currentCpi() : null;
        Map<Integer, Map<Integer, BigDecimal>> monthlyCpi =
                ctx.inflation() != null && ctx.inflation().monthlyByYear() != null
                        ? ctx.inflation().monthlyByYear()
                        : Map.of();
        return ValuationEngine.evaluatePrecomputed(
                portfolio,
                ctx.transactions(),
                ctx.precomputedLedger(),
                ctx.instruments(),
                ctx.quotes(),
                ctx.fxSeries(),
                currentCpi,
                monthlyCpi,
                ctx.nbpReferenceRates(),
                resolvedTaxMode,
                ctx.now());
    }

    @Cacheable(
            value = CacheConfig.CACHE_PORTFOLIO_PROJECTION,
            condition = "@portfolioCacheService.isCacheEnabled(#portfolio.id)",
            unless = "#result == null",
            key = "#portfolio.id + ':v' + @portfolioCacheService.versionOf(#portfolio.id) + ':' + #request.cacheKeySuffix()")
    public ProjectionResponse getProjection(Portfolio portfolio, ProjectionRequest request) {
        TaxMode resolvedTaxMode = request.effectiveTaxMode();
        LocalDateTime now = LocalDateTime.now(clock);
        LocalDate today = now.toLocalDate();
        PortfolioValuationResponse valuation = computeValuation(portfolio, resolvedTaxMode, now);
        PortfolioValuationResponse.ProjectionSeedDto seed = valuation.projectionSeed();

        List<MarketDataPort.MonteCarloEquityHolding> equityHoldings = seed == null || seed.equityWeights() == null
                ? List.of()
                : seed.equityWeights().stream()
                        .map(w -> new MarketDataPort.MonteCarloEquityHolding(
                                w.symbol(),
                                w.categoryCode(),
                                w.valueBase() != null ? w.valueBase() : BigDecimal.ZERO,
                                w.weightPct() != null
                                        ? w.weightPct().divide(BigDecimal.valueOf(100), 6, RoundingMode.HALF_UP)
                                        : BigDecimal.ZERO))
                        .toList();

        MarketDataPort.MonteCarloBondSeed bondSeed = buildMonteCarloBondSeed(valuation.positions(), today);
        BigDecimal startInvested = seed != null && seed.startInvestedCapital() != null
                ? seed.startInvestedCapital()
                : BigDecimal.ZERO;
        BigDecimal cpiPct = seed != null ? seed.currentCpiPct() : null;

        MarketDataPort.MonteCarloRequest mcReq = new MarketDataPort.MonteCarloRequest(
                request.effectiveHorizonYears(),
                today.toString(),
                equityHoldings,
                bondSeed,
                startInvested,
                request.effectiveMonthlyDca(),
                request.effectiveDcaEquityShare(),
                resolvedTaxMode.name(),
                TaxPolicy.BELKA_RATE_BD,
                request.effectiveRealMode(),
                request.effectiveBondRollover(),
                cpiPct,
                request.targetGoal(),
                request.etfReturnOverridePct(),
                42);
        return marketDataPort.runMonteCarlo(mcReq);
    }

    private static MarketDataPort.MonteCarloBondSeed buildMonteCarloBondSeed(
            List<PortfolioValuationResponse.ValuationPositionDto> positions, LocalDate today) {
        BigDecimal fixedVal = BigDecimal.ZERO;
        BigDecimal indexedVal = BigDecimal.ZERO;
        BigDecimal fixedWeightedRate = BigDecimal.ZERO;
        BigDecimal indexedWeightedRate = BigDecimal.ZERO;
        BigDecimal remainingMonthsWeighted = BigDecimal.ZERO;
        BigDecimal totalBondVal = BigDecimal.ZERO;

        if (positions != null) {
            for (PortfolioValuationResponse.ValuationPositionDto pos : positions) {
                if (pos.bondFamily() == null || pos.valueBase() == null || pos.valueBase().compareTo(BigDecimal.ZERO) <= 0) {
                    continue;
                }
                BondValuationEngine.BondFamily bondFamily = BondValuationEngine.findBondFamily(pos.bondFamily()).orElse(null);
                boolean indexed = bondFamily != null && bondFamily.isInflationIndexed();
                BigDecimal val = pos.valueBase();
                BigDecimal rate = pos.bondRate() != null ? pos.bondRate() : BigDecimal.ZERO;
                if (indexed) {
                    indexedVal = indexedVal.add(val);
                    indexedWeightedRate = indexedWeightedRate.add(val.multiply(rate));
                } else {
                    fixedVal = fixedVal.add(val);
                    fixedWeightedRate = fixedWeightedRate.add(val.multiply(rate));
                }
                totalBondVal = totalBondVal.add(val);
                int termMonths = bondFamily != null ? bondFamily.termMonths() : 36;
                if (pos.subPositions() != null && !pos.subPositions().isEmpty()) {
                    for (PortfolioValuationResponse.ValuationSubPositionDto sub : pos.subPositions()) {
                        if (sub.valueBase() != null && sub.valueBase().compareTo(BigDecimal.ZERO) > 0 && sub.date() != null) {
                            long elapsed = Math.max(0L, ChronoUnit.MONTHS.between(sub.date(), today));
                            long remaining = Math.max(1L, termMonths - elapsed);
                            remainingMonthsWeighted = remainingMonthsWeighted.add(
                                    sub.valueBase().multiply(BigDecimal.valueOf(remaining)));
                        }
                    }
                } else {
                    remainingMonthsWeighted = remainingMonthsWeighted.add(val.multiply(BigDecimal.valueOf(termMonths)));
                }
            }
        }

        BigDecimal fixedRatePct = fixedVal.compareTo(BigDecimal.ZERO) > 0
                ? fixedWeightedRate.divide(fixedVal, 4, RoundingMode.HALF_UP)
                : null;
        BigDecimal indexedRatePct = indexedVal.compareTo(BigDecimal.ZERO) > 0
                ? indexedWeightedRate.divide(indexedVal, 4, RoundingMode.HALF_UP)
                : null;
        Integer avgRemainingMonths = totalBondVal.compareTo(BigDecimal.ZERO) > 0
                ? Math.max(1, remainingMonthsWeighted.divide(totalBondVal, 0, RoundingMode.HALF_UP).intValue())
                : null;
        return new MarketDataPort.MonteCarloBondSeed(fixedVal, indexedVal, fixedRatePct, indexedRatePct, avgRemainingMonths);
    }

    @Cacheable(
            value = CacheConfig.CACHE_PORTFOLIO_TIMELINE,
            condition = "@portfolioCacheService.isCacheEnabled(#portfolio.id)",
            unless = "#result == null",
            key = "#portfolio.id + ':v' + @portfolioCacheService.versionOf(#portfolio.id) + ':' + (#timeframe ?: 'YTD') + ':' + (#taxMode ?: 'NETTO')")
    public PortfolioTimelineResponse getTimeline(Portfolio portfolio, Timeframe timeframe, TaxMode taxMode) {
        Timeframe resolvedTf = Timeframe.orDefault(timeframe);
        TaxMode resolvedTax = TaxMode.orDefault(taxMode);
        LocalDateTime now = LocalDateTime.now(clock);
        LocalDate today = now.toLocalDate();
        DbPortfolioSnapshot snapshot = executeReadOnly(() -> loadPortfolioSnapshot(portfolio.getId()));
        if (snapshot.transactions().isEmpty()) {
            return TimelineEngine.build(
                    portfolio, List.of(), Map.of(), Map.of(), Map.of(), Map.of(), null, resolvedTf, resolvedTax, now);
        }
        LocalDate earliest = snapshot.transactions().stream()
                .map(Transaction::getExecutedAt)
                .filter(Objects::nonNull)
                .map(LocalDateTime::toLocalDate)
                .min(LocalDate::compareTo)
                .orElse(today);
        LocalDate historyStart = resolvedTf.resolveStartDate(today, earliest).minusDays(10);
        LoadedContext ctx = fetchMarketContext(portfolio, snapshot, MarketContextScope.timeline(historyStart), now);
        BigDecimal currentCpi = ctx.inflation() != null ? ctx.inflation().currentCpi() : null;
        Map<Integer, Map<Integer, BigDecimal>> monthlyCpi =
                ctx.inflation() != null && ctx.inflation().monthlyByYear() != null
                        ? ctx.inflation().monthlyByYear()
                        : Map.of();
        return TimelineEngine.build(
                portfolio,
                ctx.transactions(),
                ctx.instruments(),
                ctx.quotes(),
                ctx.histories(),
                ctx.fxSeries(),
                currentCpi,
                monthlyCpi,
                ctx.nbpReferenceRates(),
                resolvedTf,
                resolvedTax,
                ctx.now());
    }

    @Cacheable(
            value = CacheConfig.CACHE_PORTFOLIO_REPORT,
            condition = "@portfolioCacheService.isCacheEnabled(#portfolio.id)",
            unless = "#result == null",
            key = "#portfolio.id + ':v' + @portfolioCacheService.versionOf(#portfolio.id) + ':' + #year + ':' + (#taxMode ?: 'NETTO')")
    public AnnualReportResponse getAnnualReport(Portfolio portfolio, int year, TaxMode taxMode) {
        TaxMode resolvedTax = TaxMode.orDefault(taxMode);
        LocalDateTime now = LocalDateTime.now(clock);
        LocalDate today = now.toLocalDate();
        DbPortfolioSnapshot snapshot = executeReadOnly(() -> loadPortfolioSnapshot(portfolio.getId()));
        if (snapshot.transactions().isEmpty() || year > today.getYear()) {
            return AnnualReportEngine.build(
                    portfolio, snapshot.transactions(), snapshot.instruments(),
                    Map.of(), Map.of(), Map.of(), null, year, resolvedTax, now);
        }
        LocalDate historyStart = LocalDate.of(year - 1, 12, 31).minusDays(10);
        LocalDate yearEnd = LocalDate.of(year, 12, 31);
        LocalDate historyEnd = yearEnd.isBefore(today) ? yearEnd : today;
        boolean fetchQuotes = year == today.getYear();
        LoadedContext ctx = fetchMarketContext(
                portfolio, snapshot, MarketContextScope.annualReport(historyStart, historyEnd, fetchQuotes), now);
        return AnnualReportEngine.build(
                portfolio,
                ctx.transactions(),
                ctx.instruments(),
                ctx.quotes(),
                ctx.histories(),
                ctx.fxSeries(),
                ctx.inflation(),
                ctx.nbpReferenceRates(),
                year,
                resolvedTax,
                ctx.now());
    }

    private DbPortfolioSnapshot executeReadOnly(Supplier<DbPortfolioSnapshot> loader) {
        return readOnlyTxTemplate.execute(status -> loader.get());
    }

    private DbPortfolioSnapshot loadPortfolioSnapshot(UUID portfolioId) {
        List<Transaction> txs = transactionRepository.findAllByPortfolioIdOrderByExecutedAtAsc(portfolioId);
        Set<UUID> instrumentIds = txs.stream()
                .map(Transaction::getInstrumentId)
                .filter(Objects::nonNull)
                .collect(Collectors.toCollection(LinkedHashSet::new));

        Map<UUID, Instrument> instruments = new HashMap<>();
        if (!instrumentIds.isEmpty()) {
            instrumentRepository.findAllById(instrumentIds)
                    .forEach(inst -> instruments.put(inst.getId(), inst));
        }
        return new DbPortfolioSnapshot(txs, instruments);
    }

    private LoadedContext fetchMarketContext(
            Portfolio portfolio,
            DbPortfolioSnapshot snapshot,
            MarketContextScope scope,
            LocalDateTime now) {
        List<Transaction> txs = snapshot.transactions();
        Map<UUID, Instrument> instruments = snapshot.instruments();
        LocalDate today = now.toLocalDate();
        String baseCurrency = portfolio.getBaseCurrency() != null
                ? portfolio.getBaseCurrency().name()
                : "PLN";

        Map<UUID, Transaction> sampleTxByInst = new HashMap<>();
        for (Transaction tx : txs) {
            if (tx.getInstrumentId() != null) {
                sampleTxByInst.putIfAbsent(tx.getInstrumentId(), tx);
            }
        }

        Set<UUID> targetInstrumentIds = new LinkedHashSet<>();
        LocalDate earliestOpenLotDate = today;
        PortfolioLedger.IncrementalLedger precomputedLedger = null;

        if (scope.openPositionsOnly()) {
            txs = txs.stream().sorted(PortfolioLedger.TX_ORDER).toList();
            precomputedLedger = new PortfolioLedger.IncrementalLedger(instruments);
            for (Transaction tx : txs) {
                precomputedLedger.apply(tx);
            }
            PortfolioLedger.PortfolioReplay replay = precomputedLedger.snapshot();
            for (Map.Entry<PortfolioLedger.PositionKey, PortfolioLedger.PositionReplayResult> entry :
                    replay.positions().entrySet()) {
                PortfolioLedger.PositionReplayResult posResult = entry.getValue();
                if (posResult.totalQuantity().compareTo(BigDecimal.ZERO) <= 0) {
                    continue;
                }
                targetInstrumentIds.add(entry.getKey().instrumentId());
                for (PortfolioLedger.FifoLot lot : posResult.activeLots()) {
                    if (lot.executedAt() != null) {
                        LocalDate lotDate = lot.executedAt().toLocalDate();
                        if (lotDate.isBefore(earliestOpenLotDate)) {
                            earliestOpenLotDate = lotDate;
                        }
                    }
                }
            }
        } else {
            targetInstrumentIds.addAll(instruments.keySet());
        }

        Set<String> equitySymbols = new LinkedHashSet<>();
        boolean hasBonds = false;
        for (UUID instId : targetInstrumentIds) {
            Instrument inst = instruments.get(instId);
            if (inst == null) {
                continue;
            }
            Transaction sample = sampleTxByInst.get(instId);
            boolean isBond = BondValuationEngine.isBondPosition(
                    inst,
                    sample != null ? sample.getSeriesName() : null,
                    BondValuationEngine.hasBondMetadata(sample));
            if (isBond) {
                hasBonds = true;
            }
            if (!isBond && inst.getSymbol() != null && !inst.getSymbol().isBlank()) {
                equitySymbols.add(ValuationSupport.normalizeSymbolKey(inst, instId));
            }
        }

        boolean needMonthlyInflation = scope.includeMonthlyInflation() || hasBonds;
        boolean needNbpRates = hasBonds;
        boolean needQuotes = scope.fetchQuotes() && !equitySymbols.isEmpty();
        boolean needHistory = scope.includeHistory() && !equitySymbols.isEmpty();
        LocalDate effectiveHistoryEnd = scope.historyEndDate() != null && scope.historyEndDate().isBefore(today)
                ? scope.historyEndDate()
                : today;
        LocalDate effectiveHistoryStart = scope.historyStartDate() != null
                ? scope.historyStartDate()
                : today.minusDays(30);
        if (effectiveHistoryStart.isAfter(effectiveHistoryEnd)) {
            effectiveHistoryStart = effectiveHistoryEnd;
        }

        Map<String, MarketDataPort.MarketQuote> quotes;
        Map<String, MarketDataPort.PriceHistory> histories;
        MarketDataPort.InflationSnapshot inflation;
        java.util.NavigableMap<LocalDate, BigDecimal> nbpReferenceRates;

        if (!needQuotes && !needHistory) {
            quotes = Map.of();
            histories = Map.of();
            inflation = fetchInflationSafe(needMonthlyInflation);
            nbpReferenceRates = needNbpRates ? fetchNbpRatesSafe() : new java.util.TreeMap<>();
        } else {
            final LocalDate queryHistoryStart = effectiveHistoryStart;
            try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
                CompletableFuture<Map<String, MarketDataPort.MarketQuote>> quotesFuture = needQuotes
                        ? CompletableFuture.supplyAsync(() -> {
                            try {
                                return marketDataPort.getBatchQuotes(equitySymbols);
                            } catch (pl.investassist.common.exception.MarketDataUnavailableException e) {
                                log.warn("Quote batch unavailable, degrading positions to unavailable: {}", e.getMessage());
                                return Map.of();
                            }
                        }, executor)
                        : CompletableFuture.completedFuture(Map.of());
                CompletableFuture<Map<String, MarketDataPort.PriceHistory>> historyFuture = needHistory
                        ? CompletableFuture.supplyAsync(
                                () -> marketDataPort.getBatchHistory(equitySymbols, queryHistoryStart),
                                executor)
                        : CompletableFuture.completedFuture(Map.of());
                CompletableFuture<MarketDataPort.InflationSnapshot> inflationFuture = CompletableFuture.supplyAsync(
                        () -> fetchInflationSafe(needMonthlyInflation),
                        executor);
                CompletableFuture<java.util.NavigableMap<LocalDate, BigDecimal>> nbpFuture = needNbpRates
                        ? CompletableFuture.supplyAsync(this::fetchNbpRatesSafe, executor)
                        : CompletableFuture.completedFuture(new java.util.TreeMap<>());

                quotes = AsyncFutureUtils.joinUnwrapping(quotesFuture);
                histories = AsyncFutureUtils.joinUnwrapping(historyFuture);
                inflation = AsyncFutureUtils.joinUnwrapping(inflationFuture);
                nbpReferenceRates = AsyncFutureUtils.joinUnwrapping(nbpFuture);
            }
        }

        Set<MarketDataPort.CurrencyPair> fxPairs = new LinkedHashSet<>();
        for (UUID instId : targetInstrumentIds) {
            Instrument inst = instruments.get(instId);
            if (inst != null && inst.getCurrency() != null) {
                addPairIfDifferent(fxPairs, inst.getCurrency().name(), baseCurrency);
            }
        }
        for (MarketDataPort.MarketQuote q : quotes.values()) {
            if (q != null && !q.unavailable()) {
                addPairIfDifferent(fxPairs, q.currency(), baseCurrency);
            }
        }
        for (MarketDataPort.PriceHistory h : histories.values()) {
            if (h != null) {
                addPairIfDifferent(fxPairs, h.currency(), baseCurrency);
            }
        }

        LocalDate fxStart = scope.historyStartDate() != null
                ? scope.historyStartDate()
                : earliestOpenLotDate.minusDays(10);
        if (fxStart.isAfter(effectiveHistoryEnd)) {
            fxStart = effectiveHistoryEnd;
        }
        Map<String, MarketDataPort.FxSeries> fxSeries = fxPairs.isEmpty()
                ? Map.of()
                : marketDataPort.getBatchFxHistory(fxPairs, fxStart, effectiveHistoryEnd);

        return new LoadedContext(
                txs, precomputedLedger, instruments, quotes, histories, fxSeries, inflation, nbpReferenceRates, now);
    }

    private MarketDataPort.InflationSnapshot fetchInflationSafe(boolean includeMonthlyInflation) {
        try {
            return includeMonthlyInflation
                    ? marketDataPort.getInflation()
                    : new MarketDataPort.InflationSnapshot(marketDataPort.getCurrentCpi(), Map.of());
        } catch (pl.investassist.common.exception.MarketDataUnavailableException e) {
            log.warn("Inflation data unavailable, degrading CPI-dependent metrics: {}", e.getMessage());
            return new MarketDataPort.InflationSnapshot(null, Map.of());
        }
    }

    private java.util.NavigableMap<LocalDate, BigDecimal> fetchNbpRatesSafe() {
        try {
            java.util.NavigableMap<LocalDate, BigDecimal> rates = marketDataPort.getNbpReferenceRates();
            return rates != null ? rates : new java.util.TreeMap<>();
        } catch (pl.investassist.common.exception.MarketDataUnavailableException e) {
            log.warn("NBP reference rates unavailable, degrading ROR/DOR valuation: {}", e.getMessage());
            return new java.util.TreeMap<>();
        }
    }

    private static void addPairIfDifferent(
            Set<MarketDataPort.CurrencyPair> pairs, String rawFrom, String baseCurrency) {
        String normalized = ValuationSupport.normalizeCurrencyCode(rawFrom, null);
        if (normalized != null && !normalized.equalsIgnoreCase(baseCurrency)) {
            pairs.add(new MarketDataPort.CurrencyPair(normalized, baseCurrency));
        }
    }
}
