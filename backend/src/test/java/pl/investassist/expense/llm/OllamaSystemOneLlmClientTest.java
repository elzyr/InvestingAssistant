package pl.investassist.expense.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import pl.investassist.expense.classifier.MerchantRuleSnapshot;
import pl.investassist.expense.enums.ExpenseCategory;
import pl.investassist.expense.enums.FlowKind;
import pl.investassist.expense.enums.RuleMatchType;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

class OllamaSystemOneLlmClientTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private HttpServer server;
  private String baseUrl;
  private final AtomicInteger statusCode = new AtomicInteger(200);
  private final AtomicInteger requestCount = new AtomicInteger(0);
  private final Queue<String> queuedResponses = new ConcurrentLinkedQueue<>();
  private final AtomicReference<String> defaultResponseBody = new AtomicReference<>("{}");
  private final AtomicReference<String> lastMethod = new AtomicReference<>();
  private final AtomicReference<String> lastUri = new AtomicReference<>();
  private final AtomicReference<String> lastApiKeyHeader = new AtomicReference<>();
  private final AtomicReference<String> lastRequestBody = new AtomicReference<>();

  @BeforeEach
  void setUp() throws IOException {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/", exchange -> {
      requestCount.incrementAndGet();
      lastMethod.set(exchange.getRequestMethod());
      lastUri.set(exchange.getRequestURI().toString());
      lastApiKeyHeader.set(exchange.getRequestHeaders().getFirst("x-goog-api-key"));
      byte[] reqBytes = exchange.getRequestBody().readAllBytes();
      lastRequestBody.set(new String(reqBytes, StandardCharsets.UTF_8));
      String body = queuedResponses.poll();
      if (body == null) {
        body = defaultResponseBody.get();
      }
      byte[] respBytes = body.getBytes(StandardCharsets.UTF_8);
      exchange.getResponseHeaders().add("Content-Type", "application/json");
      exchange.sendResponseHeaders(statusCode.get(), respBytes.length);
      exchange.getResponseBody().write(respBytes);
      exchange.close();
    });
    server.start();
    baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
  }

  @AfterEach
  void tearDown() {
    if (server != null) {
      server.stop(0);
    }
  }

  @Test
  void disabledClient_returnsSafeDefaultsWithoutCallingHttp() {
    OllamaSystemOneLlmClient disabled = new OllamaSystemOneLlmClient("none", "tev1:4b", baseUrl, 8192);
    assertThat(disabled.isEnabled()).isFalse();
    assertThat(disabled.providerName()).isEqualTo("none");
    assertThat(disabled.modelName()).isEmpty();
    assertThat(disabled.classifyUnknowns(List.of(
        new LlmClassifyInput("1", "SKLEP", new BigDecimal("-10.00"), "PLN")))).isEmpty();
    assertThat(disabled.classifyUnknowns(
        List.of(new LlmClassifyInput("1", "SKLEP", new BigDecimal("-10.00"), "PLN")),
        new LlmClassificationContext("Jan Kowalski", List.of("Sklep"), List.of()))).isEmpty();
    assertThat(requestCount.get()).isZero();

    assertThat(new OllamaSystemOneLlmClient("ollama", " ", baseUrl, 8192).isEnabled()).isFalse();
    assertThat(new OllamaSystemOneLlmClient("ollama", "tev1:4b", " ", 8192).isEnabled()).isFalse();
  }

  @Test
  void classifyUnknowns_sendsSystemOnePayloadWithLearnedExamplesAndClampsConfidence() throws Exception {
    statusCode.set(200);
    defaultResponseBody.set("""
        {
          "answers": {
            "category": {
              "choice": "SERVICES_FEES",
              "confidence": 1.8,
              "probabilities": {
                "SERVICES_FEES": 0.92,
                "SHOPPING": 0.08
              }
            }
          }
        }
        """);

    OllamaSystemOneLlmClient client = new OllamaSystemOneLlmClient("ollama", "tev1:4b", baseUrl, 16384);
    assertThat(client.isEnabled()).isTrue();
    assertThat(client.providerName()).isEqualTo("ollama");
    assertThat(client.modelName()).isEqualTo("tev1:4b");

    List<MerchantRuleSnapshot> learned = List.of(
        new MerchantRuleSnapshot(RuleMatchType.MERCHANT, "kantyna_biurowa", ExpenseCategory.DINING, FlowKind.EXPENSE),
        new MerchantRuleSnapshot(RuleMatchType.MERCHANT, "KANTYNA_BIUROWA", ExpenseCategory.SHOPPING, FlowKind.EXPENSE),
        new MerchantRuleSnapshot(RuleMatchType.MERCHANT, "unknown", ExpenseCategory.OTHER_EXPENSE, FlowKind.EXPENSE),
        new MerchantRuleSnapshot(RuleMatchType.MERCHANT, "payu", ExpenseCategory.SHOPPING, FlowKind.EXPENSE));

    Map<String, LlmCategorySuggestion> out = client.classifyUnknowns(
        List.of(new LlmClassifyInput("1", "FRYZJER MAREK", new BigDecimal("-80.00"), "PLN")),
        learned);

    assertThat(lastMethod.get()).isEqualTo("POST");
    assertThat(lastUri.get()).isEqualTo("/v1/systemone");
    assertThat(lastApiKeyHeader.get()).isNull();

    JsonNode reqJson = MAPPER.readTree(lastRequestBody.get());
    assertThat(reqJson.path("model").asText()).isEqualTo("tev1:4b");
    assertThat(reqJson.path("options").path("num_ctx").asInt()).isEqualTo(16384);

    JsonNode stateNode = reqJson.path("state");
    assertThat(stateNode.isTextual()).isTrue();
    String stateText = stateNode.asText();
    assertThat(stateText)
        .contains("FRYZJER MAREK")
        .contains("-80.00")
        .contains("PLN")
        .contains("outflow")
        .contains("kantyna biurowa")
        .contains("DINING")
        .doesNotContain("SHOPPING")
        .doesNotContain("unknown")
        .doesNotContain("payu");

    JsonNode categoryQuestion = reqJson.path("questions").path("category");
    assertThat(categoryQuestion.path("type").asText()).isEqualTo("choice");
    assertThat(categoryQuestion.path("instructions").isTextual()).isTrue();
    assertThat(categoryQuestion.path("instructions").asText()).isNotBlank();

    JsonNode criteriaNode = categoryQuestion.path("criteria");
    assertThat(criteriaNode.isObject()).isTrue();
    assertThat(criteriaNode).hasSize(25);
    for (String expected : List.of(
        "GROCERIES",
        "DINING",
        "HOUSING",
        "UTILITIES",
        "TELECOM",
        "HOME_GARDEN",
        "FUEL",
        "TRANSPORT",
        "CAR_MAINTENANCE",
        "HEALTH",
        "FITNESS_SPORT",
        "CLOTHING",
        "BEAUTY_CARE",
        "ELECTRONICS",
        "SHOPPING",
        "ENTERTAINMENT",
        "SUBSCRIPTIONS",
        "TRAVEL",
        "EDUCATION",
        "PETS",
        "GIFTS",
        "SERVICES_FEES",
        "OTHER_EXPENSE",
        "INVESTMENTS_SAVINGS",
        "INTERNAL_TRANSFER")) {
      assertThat(criteriaNode.path(expected).isTextual()).isTrue();
      assertThat(criteriaNode.path(expected).asText()).isNotBlank();
    }
    assertThat(criteriaNode.has("INCOME")).isFalse();
    assertThat(criteriaNode.has("REFUND")).isFalse();

    assertThat(out).containsOnlyKeys("1");
    assertThat(out.get("1").category()).isEqualTo(ExpenseCategory.SERVICES_FEES);
    assertThat(out.get("1").flowKind()).isEqualTo(FlowKind.EXPENSE);
    assertThat(out.get("1").confidence()).isEqualByComparingTo("1.00");
  }

  @Test
  void classifyUnknowns_withClassificationContextIncludesOwnerMerchantsOccurrencesAndMerchantDisplay()
      throws Exception {
    statusCode.set(200);
    defaultResponseBody.set("""
        {
          "answers": {
            "category": {
              "choice": "SUBSCRIPTIONS",
              "confidence": 0.93
            }
          }
        }
        """);

    OllamaSystemOneLlmClient client = new OllamaSystemOneLlmClient("ollama", "tev1:4b", baseUrl, 16384);
    LlmClassificationContext context = new LlmClassificationContext(
        "Jan Kowalski",
        List.of("Biedronka", "Netflix"),
        List.of(new MerchantRuleSnapshot(
            RuleMatchType.MERCHANT, "kantyna_biurowa_waw", ExpenseCategory.DINING, FlowKind.EXPENSE)));

    Map<String, LlmCategorySuggestion> out = client.classifyUnknowns(
        List.of(new LlmClassifyInput(
            "1",
            "WL*GOOGLE ZAKUP PRZY UZYCIU KARTY - INTERNET",
            new BigDecimal("-37.99"),
            "PLN",
            "Google",
            3)),
        context);

    JsonNode reqJson = MAPPER.readTree(lastRequestBody.get());
    assertThat(reqJson.path("options").path("num_ctx").asInt()).isEqualTo(16384);
    assertThat(reqJson.path("state").isTextual()).isTrue();
    assertThat(reqJson.path("state").asText())
        .contains("WL*GOOGLE")
        .contains("Google")
        .contains("-37.99")
        .contains("PLN")
        .contains("outflow")
        .contains("Statement occurrences: 3")
        .doesNotContain("Account owner:")
        .doesNotContain("Jan Kowalski")
        .contains("Other merchants in statement: Biedronka, Netflix")
        .contains("User learned rules:")
        .contains("kantyna biurowa waw")
        .contains("DINING");

    JsonNode categoryQuestion = reqJson.path("questions").path("category");
    assertThat(categoryQuestion.path("instructions").asText()).isNotBlank();
    JsonNode criteriaNode = categoryQuestion.path("criteria");
    assertThat(criteriaNode.isObject()).isTrue();
    assertThat(criteriaNode.has("SUBSCRIPTIONS")).isTrue();
    assertThat(criteriaNode.has("INCOME")).isFalse();
    assertThat(criteriaNode.has("REFUND")).isFalse();

    assertThat(out).containsOnlyKeys("1");
    assertThat(out.get("1").category()).isEqualTo(ExpenseCategory.SUBSCRIPTIONS);
    assertThat(out.get("1").confidence()).isEqualByComparingTo("0.93");

    client.classifyUnknowns(
        List.of(new LlmClassifyInput("2", "NOWY PUNKT", new BigDecimal("-12.00"), "PLN")),
        new LlmClassificationContext(null, List.of(), List.of()));
    JsonNode minimalReq = MAPPER.readTree(lastRequestBody.get());
    assertThat(minimalReq.path("state").isTextual()).isTrue();
    assertThat(minimalReq.path("state").asText())
        .contains("NOWY PUNKT")
        .contains("-12.00")
        .contains("Statement occurrences: 1")
        .doesNotContain("Account owner:")
        .doesNotContain("Other merchants in statement:")
        .doesNotContain("User learned rules:");
  }

  @Test
  void classifyUnknowns_keepsGoogleLearnedRuleAndCapsStatementMerchantsAtForty() throws Exception {
    statusCode.set(200);
    defaultResponseBody.set("""
        {
          "answers": {
            "category": {
              "choice": "SUBSCRIPTIONS",
              "confidence": 0.90
            }
          }
        }
        """);

    List<String> merchants = new ArrayList<>();
    for (int i = 1; i <= 45; i++) {
      merchants.add(String.format("Merchant %02d", i));
    }

    OllamaSystemOneLlmClient client = new OllamaSystemOneLlmClient("ollama", "tev1:4b", baseUrl, 16384);
    client.classifyUnknowns(
        List.of(new LlmClassifyInput("1", "NOWY SKLEP", new BigDecimal("-25.00"), "PLN")),
        new LlmClassificationContext(
            null,
            merchants,
            List.of(
                new MerchantRuleSnapshot(
                    RuleMatchType.MERCHANT, "google", ExpenseCategory.SUBSCRIPTIONS, FlowKind.EXPENSE),
                new MerchantRuleSnapshot(
                    RuleMatchType.MERCHANT, "payu", ExpenseCategory.SHOPPING, FlowKind.EXPENSE))));

    JsonNode reqJson = MAPPER.readTree(lastRequestBody.get());
    String stateText = reqJson.path("state").asText();
    assertThat(stateText)
        .contains("Merchant 01")
        .contains("Merchant 40")
        .doesNotContain("Merchant 41")
        .doesNotContain("Merchant 45")
        .contains("- google -> SUBSCRIPTIONS")
        .doesNotContain("payu");
  }

  @Test
  void classifyUnknowns_capsLearnedExamplesAtSixty() throws Exception {
    statusCode.set(200);
    defaultResponseBody.set("""
        {
          "answers": {
            "category": {
              "choice": "SHOPPING",
              "confidence": 0.90
            }
          }
        }
        """);

    List<MerchantRuleSnapshot> manyRules = new ArrayList<>();
    for (int i = 1; i <= 65; i++) {
      manyRules.add(new MerchantRuleSnapshot(
          RuleMatchType.MERCHANT,
          String.format("merchant_%02d", i),
          ExpenseCategory.SHOPPING,
          FlowKind.EXPENSE));
    }

    OllamaSystemOneLlmClient client = new OllamaSystemOneLlmClient("ollama", "tev1:4b", baseUrl, 16384);
    client.classifyUnknowns(
        List.of(new LlmClassifyInput("1", "NOWY SKLEP", new BigDecimal("-25.00"), "PLN")),
        manyRules);

    JsonNode reqJson = MAPPER.readTree(lastRequestBody.get());
    JsonNode stateNode = reqJson.path("state");
    assertThat(stateNode.isTextual()).isTrue();
    assertThat(stateNode.asText())
        .contains("merchant 01")
        .contains("merchant 60")
        .doesNotContain("merchant 61")
        .doesNotContain("merchant 65");
  }

  @Test
  void classifyUnknowns_usesProbabilitiesFallbackClampsLowConfidenceAndClampsNumCtx() throws Exception {
    statusCode.set(200);
    queuedResponses.add("""
        {
          "answers": {
            "category": {
              "choice": "DINING",
              "probabilities": {
                "DINING": 0.45,
                "SHOPPING": 0.55
              }
            }
          }
        }
        """);
    queuedResponses.add("""
        {
          "answers": {
            "category": {
              "choice": "SHOPPING",
              "confidence": -0.5
            }
          }
        }
        """);

    OllamaSystemOneLlmClient client = new OllamaSystemOneLlmClient("ollama", "tev1:4b", baseUrl, 256);
    Map<String, LlmCategorySuggestion> out = client.classifyUnknowns(List.of(
        new LlmClassifyInput("1", "BISTRO MAREK", new BigDecimal("-35.00"), "PLN"),
        new LlmClassifyInput("2", "GALERIA NOWA", new BigDecimal("-120.00"), "PLN")));

    JsonNode reqJson = MAPPER.readTree(lastRequestBody.get());
    assertThat(reqJson.path("options").path("num_ctx").asInt()).isEqualTo(1024);

    assertThat(out).containsOnlyKeys("1", "2");
    assertThat(out.get("1").category()).isEqualTo(ExpenseCategory.DINING);
    assertThat(out.get("1").confidence()).isEqualByComparingTo("0.45");
    assertThat(out.get("2").category()).isEqualTo(ExpenseCategory.SHOPPING);
    assertThat(out.get("2").confidence()).isEqualByComparingTo("0.00");
  }

  @Test
  void classifyUnknowns_inflowUsesInflowCriteriaAndIgnoresInvalidOrIncompatibleChoices() throws Exception {
    statusCode.set(200);
    queuedResponses.add("""
        {
          "answers": {
            "category": {
              "choice": "REFUND",
              "confidence": 0.91
            }
          }
        }
        """);
    queuedResponses.add("""
        {
          "answers": {
            "category": {
              "choice": "BOGUS_CATEGORY",
              "confidence": 0.95
            }
          }
        }
        """);
    queuedResponses.add("""
        {
          "answers": {
            "category": {
              "choice": "DINING",
              "confidence": 0.95
            }
          }
        }
        """);

    OllamaSystemOneLlmClient client = new OllamaSystemOneLlmClient("ollama", "tev1:4b", baseUrl, 16384);
    Map<String, LlmCategorySuggestion> out = client.classifyUnknowns(List.of(
        new LlmClassifyInput("in-valid", "ZWROT SKLEP", new BigDecimal("450.00"), "PLN"),
        new LlmClassifyInput("in-bogus", "PRZELEW ABC", new BigDecimal("450.00"), "PLN"),
        new LlmClassifyInput("in-incompatible", "KOLACJA ZWROT", new BigDecimal("450.00"), "PLN")));

    JsonNode reqJson = MAPPER.readTree(lastRequestBody.get());
    assertThat(reqJson.path("state").isTextual()).isTrue();
    assertThat(reqJson.path("state").asText()).contains("inflow");
    JsonNode criteriaNode = reqJson.path("questions").path("category").path("criteria");
    assertThat(criteriaNode.isObject()).isTrue();
    assertThat(criteriaNode).hasSize(4);
    assertThat(criteriaNode.has("INCOME")).isTrue();
    assertThat(criteriaNode.has("REFUND")).isTrue();
    assertThat(criteriaNode.has("INTERNAL_TRANSFER")).isTrue();
    assertThat(criteriaNode.has("INVESTMENTS_SAVINGS")).isTrue();
    assertThat(criteriaNode.has("DINING")).isFalse();
    assertThat(criteriaNode.has("GROCERIES")).isFalse();

    assertThat(out).containsOnlyKeys("in-valid");
    assertThat(out.get("in-valid").category()).isEqualTo(ExpenseCategory.REFUND);
    assertThat(out.get("in-valid").flowKind()).isEqualTo(FlowKind.REFUND);
    assertThat(out.get("in-valid").confidence()).isEqualByComparingTo("0.91");
  }

  @Test
  void classifyUnknowns_parsesGranularCategoriesAndExcludesThemFromInflowCriteria() throws Exception {
    statusCode.set(200);
    queuedResponses.add("""
        {
          "answers": {
            "category": {
              "choice": "CLOTHING",
              "confidence": 0.89
            }
          }
        }
        """);
    queuedResponses.add("""
        {
          "answers": {
            "category": {
              "choice": "PETS",
              "confidence": 0.92
            }
          }
        }
        """);
    queuedResponses.add("""
        {
          "answers": {
            "category": {
              "choice": "INCOME",
              "confidence": 0.96
            }
          }
        }
        """);

    OllamaSystemOneLlmClient client = new OllamaSystemOneLlmClient("ollama", "tev1:4b", baseUrl, 16384);
    Map<String, LlmCategorySuggestion> out = client.classifyUnknowns(List.of(
        new LlmClassifyInput("c1", "BUTIK MODA", new BigDecimal("-220.00"), "PLN"),
        new LlmClassifyInput("p1", "GABINET WETERYNARYJNY", new BigDecimal("-180.00"), "PLN"),
        new LlmClassifyInput("i1", "PRZELEW OD KLIENTA", new BigDecimal("2400.00"), "PLN")));

    assertThat(out.get("c1").category()).isEqualTo(ExpenseCategory.CLOTHING);
    assertThat(out.get("c1").flowKind()).isEqualTo(FlowKind.EXPENSE);
    assertThat(out.get("p1").category()).isEqualTo(ExpenseCategory.PETS);
    assertThat(out.get("p1").flowKind()).isEqualTo(FlowKind.EXPENSE);
    assertThat(out.get("i1").category()).isEqualTo(ExpenseCategory.INCOME);

    JsonNode inflowReq = MAPPER.readTree(lastRequestBody.get());
    JsonNode inflowCriteria = inflowReq.path("questions").path("category").path("criteria");
    assertThat(inflowCriteria).hasSize(4);
    assertThat(inflowCriteria.has("UTILITIES")).isFalse();
    assertThat(inflowCriteria.has("TELECOM")).isFalse();
    assertThat(inflowCriteria.has("CLOTHING")).isFalse();
    assertThat(inflowCriteria.has("BEAUTY_CARE")).isFalse();
    assertThat(inflowCriteria.has("ELECTRONICS")).isFalse();
    assertThat(inflowCriteria.has("PETS")).isFalse();
    assertThat(inflowCriteria.has("GIFTS")).isFalse();
  }

  @Test
  void classifyUnknowns_failsFastOnHttpErrorOrConnectionRefusedAndUnsupportedPortsDegrade() {
    OllamaSystemOneLlmClient client = new OllamaSystemOneLlmClient("ollama", "tev1:4b", baseUrl, 8192);
    assertThat(requestCount.get()).isZero();

    statusCode.set(500);
    defaultResponseBody.set("{\"error\":\"model unavailable\"}");
    org.assertj.core.api.Assertions.assertThatThrownBy(() -> client.classifyUnknowns(List.of(
        new LlmClassifyInput("1", "SKLEP A", new BigDecimal("-10.00"), "PLN"),
        new LlmClassifyInput("2", "SKLEP B", new BigDecimal("-20.00"), "PLN"))))
        .hasMessageContaining("HTTP 500");
    assertThat(requestCount.get()).isEqualTo(2);

    server.stop(0);
    server = null;
    org.assertj.core.api.Assertions.assertThatThrownBy(() -> client.classifyUnknowns(List.of(
        new LlmClassifyInput("1", "SKLEP A", new BigDecimal("-10.00"), "PLN"))))
        .hasMessageContaining("connection failed");
  }

  @Test
  void classifyUnknowns_neverSendsRawAccountOwnerNameAndScrubsLearnedExamples() {
    statusCode.set(200);
    defaultResponseBody.set("""
        {"answers":{"category":{"choice":"SHOPPING","confidence":0.90}}}
        """);

    OllamaSystemOneLlmClient client = new OllamaSystemOneLlmClient("ollama", "tev1:4b", baseUrl, 16384);
    LlmClassificationContext context = new LlmClassificationContext(
        "Jan Kowalski",
        List.of("Biedronka"),
        List.of(new MerchantRuleSnapshot(
            RuleMatchType.MERCHANT,
            "biuro_jan@wp.pl_123-456-32-18",
            ExpenseCategory.SERVICES_FEES,
            FlowKind.EXPENSE)));

    client.classifyUnknowns(
        List.of(new LlmClassifyInput("1", "SKLEP XYZ", new BigDecimal("-25.00"), "PLN")),
        context);

    String sentBody = lastRequestBody.get();
    assertThat(sentBody)
        .doesNotContain("Account owner:")
        .doesNotContain("Jan Kowalski")
        .doesNotContain("jan@wp.pl")
        .doesNotContain("123-456-32-18");
  }

  @Test
  void classifyUnknowns_survivesSingleItemFailureAndCapsBatchSize() throws Exception {
    OllamaSystemOneLlmClient client = new OllamaSystemOneLlmClient("ollama", "tev1:4b", baseUrl, 8192);
    server.stop(0);
    server = com.sun.net.httpserver.HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    requestCount.set(0);
    server.createContext("/", exchange -> {
      int idx = requestCount.incrementAndGet();
      byte[] resp = idx == 1
          ? "{\"error\":\"boom\"}".getBytes(StandardCharsets.UTF_8)
          : "{\"answers\":{\"category\":{\"choice\":\"DINING\",\"confidence\":0.91}}}"
              .getBytes(StandardCharsets.UTF_8);
      exchange.getResponseHeaders().add("Content-Type", "application/json");
      exchange.sendResponseHeaders(idx == 1 ? 500 : 200, resp.length);
      exchange.getResponseBody().write(resp);
      exchange.close();
    });
    server.start();
    baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
    OllamaSystemOneLlmClient resilientClient = new OllamaSystemOneLlmClient("ollama", "tev1:4b", baseUrl, 8192);

    Map<String, LlmCategorySuggestion> partial = resilientClient.classifyUnknowns(List.of(
        new LlmClassifyInput("1", "SKLEP A", new BigDecimal("-10.00"), "PLN"),
        new LlmClassifyInput("2", "BISTRO B", new BigDecimal("-25.00"), "PLN")));

    assertThat(partial).containsKey("2");
    assertThat(partial.get("2").category()).isEqualTo(ExpenseCategory.DINING);

    requestCount.set(0);
    List<LlmClassifyInput> oversizedBatch = new ArrayList<>();
    for (int i = 0; i < 100; i++) {
      oversizedBatch.add(new LlmClassifyInput("id-" + i, "SKLEP " + i, new BigDecimal("-10.00"), "PLN"));
    }
    resilientClient.classifyUnknowns(oversizedBatch);
    assertThat(requestCount.get()).isLessThan(100);
  }
}
