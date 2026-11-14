package pl.investassist.portfolio.valuation;

import pl.investassist.instrument.entity.Instrument;
import pl.investassist.portfolio.port.MarketDataPort;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;
import java.util.UUID;

record ValuationContext(
        Map<UUID, Instrument> instruments,
        Map<String, MarketDataPort.MarketQuote> quotes,
        Map<String, MarketDataPort.PriceHistory> histories,
        Map<String, MarketDataPort.FxSeries> fxSeriesByPair,
        BigDecimal currentCpiPct,
        Map<Integer, Map<Integer, BigDecimal>> monthlyCpiByYear,
        NavigableMap<LocalDate, BigDecimal> nbpReferenceRates,
        String baseCurrency,
        boolean shouldApplyTax,
        LocalDate today,
        LocalDateTime effectiveNow) {

    ValuationContext(
            Map<UUID, Instrument> instruments,
            Map<String, MarketDataPort.MarketQuote> quotes,
            Map<String, MarketDataPort.PriceHistory> histories,
            Map<String, MarketDataPort.FxSeries> fxSeriesByPair,
            BigDecimal currentCpiPct,
            String baseCurrency,
            boolean shouldApplyTax,
            LocalDate today,
            LocalDateTime effectiveNow) {
        this(instruments, quotes, histories, fxSeriesByPair, currentCpiPct,
                Map.of(), new TreeMap<>(), baseCurrency, shouldApplyTax, today, effectiveNow);
    }

    ValuationContext forStep(LocalDate stepDate, BigDecimal stepCpiPct) {
        return new ValuationContext(
                instruments,
                quotes,
                histories,
                fxSeriesByPair,
                stepCpiPct,
                monthlyCpiByYear,
                nbpReferenceRates,
                baseCurrency,
                shouldApplyTax,
                stepDate,
                effectiveNow);
    }
}
