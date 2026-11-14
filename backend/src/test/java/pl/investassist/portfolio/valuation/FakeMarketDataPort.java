package pl.investassist.portfolio.valuation;

import pl.investassist.common.enums.Currency;
import pl.investassist.instrument.dto.InstrumentResponse;
import pl.investassist.portfolio.port.MarketDataPort;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;

final class FakeMarketDataPort implements MarketDataPort {

    private final Map<String, MarketQuote> quotes = new LinkedHashMap<>();
    private final Map<String, PriceHistory> histories = new LinkedHashMap<>();
    private final Map<String, FxSeries> fxSeries = new LinkedHashMap<>();
    private BigDecimal currentCpi = new BigDecimal("3.6");
    private final Map<Integer, Map<Integer, BigDecimal>> monthlyInflation = new LinkedHashMap<>();

    FakeMarketDataPort withQuote(String symbol, double price, double previousClose, String currency) {
        quotes.put(symbol, new MarketQuote(
                symbol,
                BigDecimal.valueOf(price),
                BigDecimal.valueOf(previousClose),
                currency,
                false));
        return this;
    }

    FakeMarketDataPort withHistory(String symbol, String currency, Map<LocalDate, BigDecimal> closes) {
        histories.put(symbol, new PriceHistory(symbol, currency, new TreeMap<>(closes)));
        return this;
    }

    FakeMarketDataPort withFx(String from, String to, double spotRate, Map<LocalDate, BigDecimal> dailyRates) {
        NavigableMap<LocalDate, BigDecimal> map = dailyRates != null ? new TreeMap<>(dailyRates) : new TreeMap<>();
        fxSeries.put(from + ":" + to, new FxSeries(from, to, BigDecimal.valueOf(spotRate), map));
        return this;
    }

    FakeMarketDataPort withCurrentCpi(BigDecimal cpi) {
        this.currentCpi = cpi;
        return this;
    }

    FakeMarketDataPort withMonthlyCpi(int year, int month, double cpi) {
        this.monthlyInflation
                .computeIfAbsent(year, ignored -> new LinkedHashMap<>())
                .put(month, BigDecimal.valueOf(cpi));
        return this;
    }

    @Override
    public BigDecimal getExchangeRate(Currency from, Currency to, LocalDate date) {
        if (from == to) {
            return BigDecimal.ONE;
        }
        FxSeries series = fxSeries.get(from.name() + ":" + to.name());
        return series != null ? series.resolveRate(date, true) : BigDecimal.ONE;
    }

    @Override
    public List<InstrumentResponse> searchInstruments(String query) {
        return List.of();
    }

    @Override
    public Map<String, MarketQuote> getBatchQuotes(Collection<String> symbols) {
        Map<String, MarketQuote> out = new LinkedHashMap<>();
        for (String s : symbols) {
            if (quotes.containsKey(s)) {
                out.put(s, quotes.get(s));
            }
        }
        return out;
    }

    @Override
    public Map<String, PriceHistory> getBatchHistory(Collection<String> symbols, LocalDate startDate) {
        Map<String, PriceHistory> out = new LinkedHashMap<>();
        for (String s : symbols) {
            if (histories.containsKey(s)) {
                out.put(s, histories.get(s));
            }
        }
        return out;
    }

    @Override
    public Map<String, FxSeries> getBatchFxHistory(
            Collection<CurrencyPair> pairs, LocalDate startDate, LocalDate endDate) {
        Map<String, FxSeries> out = new LinkedHashMap<>();
        for (CurrencyPair pair : pairs) {
            if (fxSeries.containsKey(pair.key())) {
                out.put(pair.key(), fxSeries.get(pair.key()));
            }
        }
        return out;
    }

    @Override
    public BigDecimal getCurrentCpi() {
        return currentCpi;
    }

    @Override
    public InflationSnapshot getInflation() {
        return new InflationSnapshot(currentCpi, monthlyInflation);
    }
}
