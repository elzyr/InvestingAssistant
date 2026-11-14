package pl.investassist.portfolio.client;

import java.math.BigDecimal;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import pl.investassist.common.enums.Currency;
import pl.investassist.common.enums.InstrumentType;
import pl.investassist.common.exception.MarketDataUnavailableException;
import pl.investassist.common.util.AsyncFutureUtils;
import pl.investassist.config.CacheConfig;
import pl.investassist.instrument.dto.InstrumentResponse;
import pl.investassist.portfolio.port.MarketDataPort;

@Component
public class MarketDataClient implements MarketDataPort {

  private static final Logger log = LoggerFactory.getLogger(MarketDataClient.class);

  private final RestClient restClient;

  @Autowired
  public MarketDataClient(
      @Value("${services.market-data-service.url}") String marketDataServiceUrl) {
    SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
    factory.setConnectTimeout(Duration.ofSeconds(5));
    factory.setReadTimeout(Duration.ofSeconds(10));

    this.restClient = RestClient.builder()
        .baseUrl(marketDataServiceUrl)
        .requestFactory(factory)
        .build();
  }

  MarketDataClient(RestClient restClient) {
    this.restClient = restClient;
  }

  @Override
  @Cacheable(
      cacheResolver = "fxCacheResolver",
      key = "#from + '-' + #to + '-' + (#date != null ? #date : 'spot')",
      condition = "#from != null && #to != null && !#from.equals(#to)",
      unless = "#result == null")
  public BigDecimal getExchangeRate(Currency from, Currency to, LocalDate date) {
    if (from == null || to == null) {
      throw new MarketDataUnavailableException("Currency pair must not be null");
    }
    if (from.equals(to)) {
      return BigDecimal.ONE;
    }
    String uri = date != null
        ? String.format("/api/currencies/rate?from_currency=%s&to_currency=%s&date=%s", from.name(), to.name(), date)
        : String.format("/api/currencies/rate?from_currency=%s&to_currency=%s", from.name(), to.name());
    return fetchRateResponse(uri);
  }

  @Override
  @Cacheable(
      cacheNames = CacheConfig.CACHE_EXCHANGE_RATES_HISTORICAL,
      key = "'nbp-tax:' + #from + '-' + #to + '-' + #eventDate",
      condition = "#from != null && #to != null && #eventDate != null && !#from.equals(#to)",
      unless = "#result == null")
  public BigDecimal getNbpTaxRate(Currency from, Currency to, LocalDate eventDate) {
    if (from == null || to == null || eventDate == null) {
      throw new MarketDataUnavailableException("Currency pair and event date must not be null");
    }
    if (from.equals(to)) {
      return BigDecimal.ONE;
    }
    String uri = String.format(
        "/api/currencies/nbp-tax-rate?from_currency=%s&to_currency=%s&date=%s",
        from.name(), to.name(), eventDate);
    return fetchRateResponse(uri);
  }

  private BigDecimal fetchRateResponse(String uri) {
    try {
      ExchangeRateResponse response = restClient.get().uri(uri).retrieve().body(ExchangeRateResponse.class);
      if (response == null || response.rate() == null) {
        throw new MarketDataUnavailableException("Failed to fetch exchange rate");
      }
      return response.rate();
    } catch (MarketDataUnavailableException e) {
      throw e;
    } catch (Exception e) {
      throw new MarketDataUnavailableException("Market data service is not available", e);
    }
  }

  @Override
  @Cacheable(
      cacheNames = CacheConfig.CACHE_INSTRUMENT_SEARCH,
      key = "#query.trim().toLowerCase(T(java.util.Locale).ROOT)",
      condition = "#query != null && !#query.isBlank()",
      unless = "#result == null || #result.isEmpty()")
  public List<InstrumentResponse> searchInstruments(String query) {
    if (query == null || query.isBlank()) {
      return List.of();
    }
    try {
      String uri = "/api/prices/search?query=" + URLEncoder.encode(query.trim(), StandardCharsets.UTF_8);
      ExternalInstrumentDto[] results = restClient.get().uri(uri).retrieve().body(ExternalInstrumentDto[].class);
      if (results == null) {
        return List.of();
      }
      List<InstrumentResponse> list = new ArrayList<>();
      for (ExternalInstrumentDto dto : results) {
        if (dto == null || dto.symbol() == null || dto.symbol().isBlank() || dto.currency() == null) {
          continue;
        }
        try {
          String rawCur = dto.currency().trim().toUpperCase(Locale.ROOT);
          Currency currency = Currency.valueOf("GBX".equals(rawCur) ? "GBP" : rawCur);
          InstrumentType type = parseInstrumentType(dto.type(), dto.symbol());
          list.add(new InstrumentResponse(null, dto.symbol(), dto.name(), type, currency));
        } catch (IllegalArgumentException e) {
          log.warn("Skipping external instrument {} due to unsupported currency {}", dto.symbol(), dto.currency());
        }
      }
      return list;
    } catch (Exception e) {
      log.warn("External instrument search failed for query='{}': {}", query, e.getMessage());
      return List.of();
    }
  }

  private InstrumentType parseInstrumentType(String rawType, String symbol) {
    if (rawType == null || rawType.isBlank()) {
      return InstrumentType.STOCK;
    }
    try {
      return InstrumentType.valueOf(rawType.toUpperCase());
    } catch (IllegalArgumentException e) {
      log.debug("Unknown instrument type '{}' for symbol {}, defaulting to STOCK", rawType, symbol);
      return InstrumentType.STOCK;
    }
  }

  private <T> Map<String, T> postBatch(
      String uri,
      Map<String, ?> body,
      ParameterizedTypeReference<Map<String, T>> typeRef,
      String context) {
    try {
      Map<String, T> response = restClient.post()
          .uri(uri)
          .contentType(MediaType.APPLICATION_JSON)
          .body(body)
          .retrieve()
          .body(typeRef);
      return response != null ? response : Map.of();
    } catch (Exception e) {
      throw new MarketDataUnavailableException(context + " failed: " + e.getMessage(), e);
    }
  }

  private static boolean isSubunitGbp(String rawCurrency, String normalizedCurrency) {
    return "GBX".equals(normalizedCurrency)
        || (rawCurrency != null && "GBp".equals(rawCurrency.trim()));
  }

  private static LocalDate parseLocalDateOrNull(String raw) {
    if (raw == null || raw.length() < 10) {
      return null;
    }
    try {
      return LocalDate.parse(raw.substring(0, 10));
    } catch (DateTimeParseException e) {
      log.debug("Skipping malformed date '{}': {}", raw, e.getMessage());
      return null;
    }
  }

  @Override
  public Map<String, MarketQuote> getBatchQuotes(Collection<String> symbols) {
    List<String> cleanSymbols = normalizeSymbols(symbols);
    if (cleanSymbols.isEmpty()) {
      return Map.of();
    }
    Map<String, RawQuoteDto> response = postBatch(
        "/api/prices/batch",
        Map.of("symbols", cleanSymbols),
        new ParameterizedTypeReference<>() {},
        "Batch quotes fetch for " + cleanSymbols);
    Map<String, MarketQuote> out = new LinkedHashMap<>();
    for (Map.Entry<String, RawQuoteDto> entry : response.entrySet()) {
      RawQuoteDto dto = entry.getValue();
      String sym = entry.getKey().toUpperCase(Locale.ROOT);
      String rawCur = dto != null ? dto.currency() : null;
      String cur = Currency.normalizeCode(rawCur, null);
      if (dto == null
          || Boolean.TRUE.equals(dto.isUnavailable())
          || dto.price() == null
          || cur == null
          || isSubunitGbp(rawCur, cur)) {
        out.put(sym, new MarketQuote(sym, null, null, null, true));
        continue;
      }
      out.put(sym, new MarketQuote(sym, dto.price(), dto.previousClose(), cur, false));
    }
    return out;
  }

  @Override
  public Map<String, PriceHistory> getBatchHistory(Collection<String> symbols, LocalDate startDate) {
    List<String> cleanSymbols = normalizeSymbols(symbols);
    if (cleanSymbols.isEmpty()) {
      return Map.of();
    }
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("symbols", cleanSymbols);
    body.put("interval", "1d");
    if (startDate != null) {
      body.put("start_date", startDate.toString());
    } else {
      body.put("period", "1y");
    }
    Map<String, RawHistoryDto> response = postBatch(
        "/api/prices/history/batch",
        body,
        new ParameterizedTypeReference<>() {},
        "Batch history fetch for " + cleanSymbols);
    Map<String, PriceHistory> out = new LinkedHashMap<>();
    for (Map.Entry<String, RawHistoryDto> entry : response.entrySet()) {
      RawHistoryDto dto = entry.getValue();
      if (dto == null || dto.history() == null) {
        continue;
      }
      String sym = entry.getKey().toUpperCase(Locale.ROOT);
      String cur = Currency.normalizeCode(dto.currency(), null);
      if (cur == null || isSubunitGbp(dto.currency(), cur)) {
        continue;
      }
      List<PricePoint> points = new ArrayList<>(dto.history().size());
      for (RawHistoryPointDto p : dto.history()) {
        if (p == null) {
          continue;
        }
        BigDecimal close = p.close() != null ? p.close() : p.closePrice();
        LocalDate parsedDate = parseLocalDateOrNull(p.date());
        if (close != null && parsedDate != null) {
          points.add(new PricePoint(parsedDate, close));
        }
      }
      out.put(sym, new PriceHistory(sym, cur, points));
    }
    return out;
  }

  @Override
  public Map<String, FxSeries> getBatchFxHistory(
      Collection<CurrencyPair> pairs, LocalDate startDate, LocalDate endDate) {
    if (pairs == null || pairs.isEmpty()) {
      return Map.of();
    }
    List<Map<String, String>> reqPairs = new ArrayList<>();
    Map<String, CurrencyPair> distinct = new LinkedHashMap<>();
    for (CurrencyPair pair : pairs) {
      if (pair == null || pair.from() == null || pair.to() == null) {
        continue;
      }
      String from = pair.from().toUpperCase(Locale.ROOT);
      String to = pair.to().toUpperCase(Locale.ROOT);
      CurrencyPair normalized = new CurrencyPair(from, to);
      if (from.equals(to)) {
        distinct.putIfAbsent(normalized.key(), normalized);
        continue;
      }
      if (distinct.putIfAbsent(normalized.key(), normalized) == null) {
        reqPairs.add(Map.of("from_currency", from, "to_currency", to));
      }
    }

    Map<String, FxSeries> out = new LinkedHashMap<>();
    for (CurrencyPair p : distinct.values()) {
      if (p.from().equals(p.to())) {
        out.put(p.key(), new FxSeries(p.from(), p.to(), BigDecimal.ONE, new TreeMap<>()));
      }
    }
    if (reqPairs.isEmpty()) {
      return out;
    }

    Map<String, Object> body = new LinkedHashMap<>();
    body.put("pairs", reqPairs);
    body.put("start_date", (startDate != null ? startDate : LocalDate.now().minusYears(1)).toString());
    if (endDate != null) {
      body.put("end_date", endDate.toString());
    }
    Map<String, RawFxHistoryDto> response = postBatch(
        "/api/currencies/history/batch",
        body,
        new ParameterizedTypeReference<>() {},
        "Batch FX history fetch");
    for (Map.Entry<String, RawFxHistoryDto> entry : response.entrySet()) {
      RawFxHistoryDto dto = entry.getValue();
      if (dto == null) {
        continue;
      }
      NavigableMap<LocalDate, BigDecimal> daily = new TreeMap<>();
      if (dto.rates() != null) {
        for (Map.Entry<String, BigDecimal> r : dto.rates().entrySet()) {
          LocalDate parsedDate = parseLocalDateOrNull(r.getKey());
          if (parsedDate != null && r.getValue() != null) {
            daily.put(parsedDate, r.getValue());
          }
        }
      }
      String from = dto.from() != null ? dto.from().trim().toUpperCase(Locale.ROOT) : "";
      String to = dto.to() != null ? dto.to().trim().toUpperCase(Locale.ROOT) : "";
      String pairKey = (!from.isEmpty() && !to.isEmpty())
          ? from + ":" + to
          : entry.getKey().trim().toUpperCase(Locale.ROOT);
      out.put(pairKey, new FxSeries(from, to, dto.spotRate(), daily));
    }
    return out;
  }

  @Override
  public BigDecimal getCurrentCpi() {
    try {
      RawInflationDto cpiDto = restClient.get()
          .uri("/api/market/inflation")
          .retrieve()
          .body(RawInflationDto.class);
      return cpiDto != null ? cpiDto.currentCpi() : null;
    } catch (Exception e) {
      throw new MarketDataUnavailableException("Market-data service unavailable for current CPI", e);
    }
  }

  private Map<Integer, Map<Integer, BigDecimal>> getMonthlyInflation() {
    Map<Integer, Map<Integer, BigDecimal>> byYear = new HashMap<>();
    try {
      List<RawMonthlyCpiDto> monthlyList = restClient.get()
          .uri("/api/market/inflation/monthly")
          .retrieve()
          .body(new ParameterizedTypeReference<>() {});
      if (monthlyList != null) {
        for (RawMonthlyCpiDto m : monthlyList) {
          if (m != null && m.rate() != null) {
            byYear.computeIfAbsent(m.year(), ignored -> new HashMap<>()).put(m.month(), m.rate());
          }
        }
      }
      return byYear;
    } catch (Exception e) {
      throw new MarketDataUnavailableException("Market-data service unavailable for monthly CPI", e);
    }
  }

  @Override
  public InflationSnapshot getInflation() {
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      CompletableFuture<BigDecimal> cpiFuture = CompletableFuture.supplyAsync(this::getCurrentCpi, executor);
      CompletableFuture<Map<Integer, Map<Integer, BigDecimal>>> monthlyFuture =
          CompletableFuture.supplyAsync(this::getMonthlyInflation, executor);
      return new InflationSnapshot(
          AsyncFutureUtils.joinUnwrapping(cpiFuture),
          AsyncFutureUtils.joinUnwrapping(monthlyFuture));
    }
  }

  private static List<String> normalizeSymbols(Collection<String> symbols) {
    if (symbols == null || symbols.isEmpty()) {
      return List.of();
    }
    Set<String> out = new LinkedHashSet<>();
    for (String raw : symbols) {
      if (raw != null && !raw.isBlank()) {
        out.add(raw.trim().toUpperCase(Locale.ROOT));
      }
    }
    return List.copyOf(out);
  }

  private record ExchangeRateResponse(Currency from, Currency to, BigDecimal rate) {}

  private record ExternalInstrumentDto(String symbol, String name, String type, String currency) {}

  private record RawQuoteDto(
      String symbol,
      BigDecimal price,
      BigDecimal previousClose,
      String currency,
      Boolean isUnavailable) {}

  private record RawHistoryPointDto(String date, BigDecimal close, BigDecimal closePrice) {}

  private record RawHistoryDto(String symbol, String currency, List<RawHistoryPointDto> history) {}

  private record RawFxHistoryDto(
      String from, String to, BigDecimal spotRate, Map<String, BigDecimal> rates) {}

  @Override
  public java.util.NavigableMap<LocalDate, BigDecimal> getNbpReferenceRates() {
    try {
      List<RawNbpRefRateDto> rows = restClient
          .get()
          .uri("/api/currencies/nbp-reference-rates")
          .retrieve()
          .body(new ParameterizedTypeReference<>() {});
      java.util.NavigableMap<LocalDate, BigDecimal> result = new java.util.TreeMap<>();
      if (rows != null) {
        for (RawNbpRefRateDto row : rows) {
          if (row != null && row.effectiveFrom() != null && row.rate() != null) {
            result.put(LocalDate.parse(row.effectiveFrom()), row.rate());
          }
        }
      }
      return result;
    } catch (Exception e) {
      throw new MarketDataUnavailableException("Market-data service unavailable for NBP reference rates", e);
    }
  }

  @Override
  public pl.investassist.portfolio.dto.response.ProjectionResponse runMonteCarlo(MonteCarloRequest request) {
    try {
      return restClient
          .post()
          .uri("/api/market/analytics/monte-carlo")
          .body(request)
          .retrieve()
          .body(pl.investassist.portfolio.dto.response.ProjectionResponse.class);
    } catch (Exception e) {
      throw new MarketDataUnavailableException("Market-data service unavailable for Monte Carlo projection", e);
    }
  }

  private record RawInflationDto(BigDecimal currentCpi) {}

  private record RawMonthlyCpiDto(int year, int month, BigDecimal rate) {}

  private record RawNbpRefRateDto(String effectiveFrom, BigDecimal rate) {}
}
