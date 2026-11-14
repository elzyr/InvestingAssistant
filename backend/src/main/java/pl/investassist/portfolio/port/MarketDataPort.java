package pl.investassist.portfolio.port;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;
import pl.investassist.common.enums.Currency;
import pl.investassist.instrument.dto.InstrumentResponse;

public interface MarketDataPort {

  record CurrencyPair(String from, String to) {
    public String key() {
      return from + ":" + to;
    }
  }

  record MarketQuote(
      String symbol,
      BigDecimal price,
      BigDecimal previousClose,
      String currency,
      boolean unavailable) {}

  record PricePoint(LocalDate date, BigDecimal close) {}

  record PriceHistory(String symbol, String currency, NavigableMap<LocalDate, BigDecimal> closesByDate) {
    public PriceHistory(String symbol, String currency, List<PricePoint> points) {
      this(symbol, currency, toSortedMap(points));
    }

    private static NavigableMap<LocalDate, BigDecimal> toSortedMap(List<PricePoint> points) {
      TreeMap<LocalDate, BigDecimal> map = new TreeMap<>();
      if (points != null) {
        for (PricePoint p : points) {
          if (p != null && p.date() != null && p.close() != null) {
            map.put(p.date(), p.close());
          }
        }
      }
      return map;
    }

    public BigDecimal resolveCloseOnOrBefore(LocalDate date, BigDecimal fallback) {
      if (closesByDate == null || closesByDate.isEmpty() || date == null) {
        return fallback;
      }
      Map.Entry<LocalDate, BigDecimal> entry = closesByDate.floorEntry(date);
      return entry != null && entry.getValue() != null ? entry.getValue() : fallback;
    }
  }

  record FxSeries(
      String fromCurrency,
      String toCurrency,
      BigDecimal spotRate,
      NavigableMap<LocalDate, BigDecimal> dailyRates) {

    public BigDecimal resolveRateOnOrBefore(LocalDate date, BigDecimal fallback) {
      if (fromCurrency != null && fromCurrency.equalsIgnoreCase(toCurrency)) {
        return BigDecimal.ONE;
      }
      if (dailyRates != null && !dailyRates.isEmpty() && date != null) {
        Map.Entry<LocalDate, BigDecimal> floor = dailyRates.floorEntry(date);
        if (floor != null && floor.getValue() != null) {
          return floor.getValue();
        }
      }
      return fallback;
    }

    public BigDecimal resolveRate(LocalDate date, boolean allowSpotFallback) {
      BigDecimal historical = resolveRateOnOrBefore(date, null);
      if (historical != null) {
        return historical;
      }
      return allowSpotFallback ? spotRate : null;
    }
  }

  record InflationSnapshot(
      BigDecimal currentCpi,
      Map<Integer, Map<Integer, BigDecimal>> monthlyByYear) {}

  BigDecimal getExchangeRate(Currency from, Currency to, LocalDate date);

  default BigDecimal getNbpTaxRate(Currency from, Currency to, LocalDate eventDate) {
    return getExchangeRate(from, to, eventDate);
  }

  List<InstrumentResponse> searchInstruments(String query);

  Map<String, MarketQuote> getBatchQuotes(Collection<String> symbols);

  Map<String, PriceHistory> getBatchHistory(Collection<String> symbols, LocalDate startDate);

  Map<String, FxSeries> getBatchFxHistory(
      Collection<CurrencyPair> pairs, LocalDate startDate, LocalDate endDate);

  BigDecimal getCurrentCpi();

  InflationSnapshot getInflation();

  default NavigableMap<LocalDate, BigDecimal> getNbpReferenceRates() {
    return new TreeMap<>();
  }

  record MonteCarloEquityHolding(
      String symbol,
      String categoryCode,
      BigDecimal valueBase,
      BigDecimal weight) {}

  record MonteCarloBondSeed(
      BigDecimal startFixedValue,
      BigDecimal startIndexedValue,
      BigDecimal fixedRatePct,
      BigDecimal indexedRatePct,
      Integer avgRemainingMonths) {}

  record MonteCarloRequest(
      int horizonYears,
      String startDate,
      List<MonteCarloEquityHolding> equityHoldings,
      MonteCarloBondSeed bondSeed,
      BigDecimal startInvestedCapital,
      BigDecimal monthlyDca,
      double dcaEquityShare,
      String taxMode,
      BigDecimal belkaTaxRate,
      boolean realMode,
      boolean bondRollover,
      BigDecimal cpiPct,
      BigDecimal targetGoal,
      BigDecimal etfReturnOverridePct,
      Integer seed) {}

  default pl.investassist.portfolio.dto.response.ProjectionResponse runMonteCarlo(MonteCarloRequest request) {
    throw new UnsupportedOperationException("Monte Carlo projection not supported");
  }
}
