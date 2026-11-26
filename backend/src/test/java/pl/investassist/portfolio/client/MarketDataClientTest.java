package pl.investassist.portfolio.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import pl.investassist.common.enums.Currency;
import pl.investassist.common.enums.InstrumentType;
import pl.investassist.common.exception.MarketDataUnavailableException;
import pl.investassist.instrument.dto.InstrumentResponse;
import pl.investassist.portfolio.port.MarketDataPort;

class MarketDataClientTest {

  private MockRestServiceServer server;
  private MarketDataClient client;

  @BeforeEach
  void setUp() {
    RestClient.Builder builder = RestClient.builder().baseUrl("http://localhost");
    server = MockRestServiceServer.bindTo(builder).build();
    client = new MarketDataClient(builder.build());
  }

  @Test
  void getExchangeRate_validatesNullsReturnsSameCurrencyOneAndParsesRemoteRate() {
    assertThatThrownBy(() -> client.getExchangeRate(null, Currency.PLN, null))
        .isInstanceOf(MarketDataUnavailableException.class);
    assertThatThrownBy(() -> client.getExchangeRate(Currency.USD, null, null))
        .isInstanceOf(MarketDataUnavailableException.class);
    assertThat(client.getExchangeRate(Currency.PLN, Currency.PLN, null))
        .isEqualByComparingTo("1");

    server.expect(requestTo("http://localhost/api/currencies/rate?from_currency=USD&to_currency=PLN&date=2025-05-10"))
        .andExpect(method(HttpMethod.GET))
        .andRespond(withSuccess("{\"rate\":4.025}", MediaType.APPLICATION_JSON));

    BigDecimal rate = client.getExchangeRate(Currency.USD, Currency.PLN, LocalDate.of(2025, 5, 10));
    assertThat(rate).isEqualByComparingTo("4.025");
    server.verify();
  }

  @Test
  void searchInstruments_mapsGbxToGbpAndSkipsUnsupportedCurrencies() {
    server.expect(requestTo("http://localhost/api/prices/search?query=VWRL"))
        .andExpect(method(HttpMethod.GET))
        .andRespond(withSuccess(
            """
            [
              {"symbol":"VWRL.L","name":"Vanguard FTSE All-World","type":"ETF","currency":"GBX"},
              {"symbol":"WEIRD","name":"Unsupported","type":"STOCK","currency":"JPY"}
            ]
            """,
            MediaType.APPLICATION_JSON));

    List<InstrumentResponse> results = client.searchInstruments("VWRL");

    assertThat(results).hasSize(1);
    assertThat(results.getFirst().symbol()).isEqualTo("VWRL.L");
    assertThat(results.getFirst().type()).isEqualTo(InstrumentType.ETF);
    assertThat(results.getFirst().currency()).isEqualTo(Currency.GBP);
    server.verify();
  }

  @Test
  void searchInstruments_normalizesPaddedLowercaseAndYahooPenceAndSkipsBlankNullOrUnsupported() {
    server.expect(requestTo("http://localhost/api/prices/search?query=mixed"))
        .andExpect(method(HttpMethod.GET))
        .andRespond(withSuccess(
            """
            [
              null,
              {"symbol":"   ","name":"Blank Symbol","type":"STOCK","currency":"PLN"},
              {"symbol":"A.L","name":"Padded Lowercase GBX","type":"ETF","currency":" gbx "},
              {"symbol":"B.L","name":"Yahoo Pence","type":"STOCK","currency":"GBp"},
              {"symbol":"CDR.WA","name":"Padded Lowercase PLN","type":"STOCK","currency":" pln "},
              {"symbol":"C.L","name":"Unsupported","type":"STOCK","currency":"JPY"},
              {"symbol":"EMPTY","name":"Empty Currency","type":"STOCK","currency":""},
              {"symbol":"BLANK","name":"Blank Currency","type":"STOCK","currency":"   "},
              {"symbol":"MISSING","name":"Null Currency","type":"STOCK","currency":null}
            ]
            """,
            MediaType.APPLICATION_JSON));

    List<InstrumentResponse> results = client.searchInstruments("mixed");

    assertThat(results)
        .extracting(InstrumentResponse::symbol, InstrumentResponse::currency)
        .containsExactly(
            tuple("A.L", Currency.GBP),
            tuple("B.L", Currency.GBP),
            tuple("CDR.WA", Currency.PLN));
    server.verify();
  }

  @Test
  void getBatchQuotes_marksUnscaledPenceOrMissingCurrencyAsUnavailableAndPreservesNullPreviousClose() {
    server.expect(requestTo("http://localhost/api/prices/batch"))
        .andExpect(method(HttpMethod.POST))
        .andRespond(withSuccess(
            """
            {
              "CNDX.L":{"symbol":"CNDX.L","price":820.5,"previousClose":null,"currency":"GBP","isUnavailable":false},
              "BAD.L":{"symbol":"BAD.L","price":82050,"previousClose":82000,"currency":"GBp","isUnavailable":false},
              "MISSING":{"symbol":"MISSING","price":10.0,"previousClose":null,"currency":null,"isUnavailable":false}
            }
            """,
            MediaType.APPLICATION_JSON));

    Map<String, MarketDataPort.MarketQuote> quotes =
        client.getBatchQuotes(List.of(" cndx.l ", "CNDX.L", "BAD.L", "MISSING", ""));
    assertThat(quotes).containsKeys("CNDX.L", "BAD.L", "MISSING");
    assertThat(quotes.get("CNDX.L").currency()).isEqualTo("GBP");
    assertThat(quotes.get("CNDX.L").previousClose()).isNull();
    assertThat(quotes.get("CNDX.L").unavailable()).isFalse();
    assertThat(quotes.get("BAD.L").unavailable()).isTrue();
    assertThat(quotes.get("MISSING").unavailable()).isTrue();
    server.verify();
  }

  @Test
  void getBatchHistory_acceptsNormalizedGbpAndSkipsMalformedDates() {
    server.expect(requestTo("http://localhost/api/prices/history/batch"))
        .andExpect(method(HttpMethod.POST))
        .andRespond(withSuccess(
            """
            {
              "CNDX.L":{
                "symbol":"CNDX.L",
                "currency":"GBP",
                "history":[
                  {"date":"2025-05-09","close":810.0},
                  {"date":"bad-date","close":999.0}
                ]
              }
            }
            """,
            MediaType.APPLICATION_JSON));

    Map<String, MarketDataPort.PriceHistory> history =
        client.getBatchHistory(List.of("CNDX.L"), LocalDate.of(2025, 5, 1));
    assertThat(history).containsKey("CNDX.L");
    assertThat(history.get("CNDX.L").currency()).isEqualTo("GBP");
    assertThat(history.get("CNDX.L").closesByDate())
        .containsExactly(Map.entry(LocalDate.of(2025, 5, 9), new BigDecimal("810.0")));
    server.verify();
  }

  @Test
  void getNbpTaxRate_callsStrictPriorDayEndpoint() {
    server.expect(requestTo("http://localhost/api/currencies/nbp-tax-rate?from_currency=USD&to_currency=PLN&date=2025-05-09"))
        .andExpect(method(HttpMethod.GET))
        .andRespond(withSuccess("{\"rate\":4.25,\"effectiveDate\":\"2025-05-08\"}", MediaType.APPLICATION_JSON));

    BigDecimal rate = client.getNbpTaxRate(Currency.USD, Currency.PLN, LocalDate.of(2025, 5, 9));
    assertThat(rate).isEqualByComparingTo("4.25");
    server.verify();
  }
}
