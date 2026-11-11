package pl.investassist.portfolio.client;

import java.math.BigDecimal;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import pl.investassist.common.enums.Currency;
import pl.investassist.common.enums.InstrumentType;
import pl.investassist.common.exception.MarketDataUnavailableException;
import pl.investassist.config.CacheConfig;
import pl.investassist.instrument.dto.InstrumentResponse;
import pl.investassist.portfolio.dto.response.ExchangeRateResponse;

@Component
public class MarketDataClient {

  private static final Logger log = LoggerFactory.getLogger(MarketDataClient.class);

  private final RestClient restClient;

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

  @Cacheable(
      cacheResolver = "fxCacheResolver",
      key = "#from + '-' + #to + '-' + (#date != null ? #date : 'spot')",
      condition = "#from != null && #to != null && !#from.equals(#to)",
      unless = "#result == null")
  public BigDecimal getExchangeRate(Currency from, Currency to, LocalDate date) {
    if (from.equals(to)) {
      return BigDecimal.ONE;
    }
    try {
      String uri = date != null
          ? String.format("/api/currencies/rate?from_currency=%s&to_currency=%s&date=%s", from.name(), to.name(), date)
          : String.format("/api/currencies/rate?from_currency=%s&to_currency=%s", from.name(), to.name());
      ExchangeRateResponse response =
          restClient
              .get()
              .uri(uri)
              .retrieve()
              .body(ExchangeRateResponse.class);
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
          Currency currency = Currency.valueOf(dto.currency().toUpperCase());
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
}
