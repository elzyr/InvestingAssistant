package pl.investassist.expense.llm;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import pl.investassist.expense.ExpenseSupport;
import pl.investassist.expense.classifier.MerchantNormalizer;
import pl.investassist.expense.classifier.MerchantRuleSnapshot;
import pl.investassist.expense.enums.ExpenseCategory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Component
public class OllamaSystemOneLlmClient implements ExpenseLlmPort {

  private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
  private static final Duration READ_TIMEOUT = Duration.ofSeconds(30);
  private static final int MIN_NUM_CTX = 1024;
  private static final int MAX_LEARNED_EXAMPLES = 60;
  private static final int MAX_STATEMENT_MERCHANTS = 40;
  private static final int MAX_DESCRIPTION_CHARS = 500;
  private static final BigDecimal DEFAULT_CONFIDENCE = new BigDecimal("0.75");
  private static final String CATEGORY_INSTRUCTIONS =
      "Classify the bank transaction into the single best matching category from criteria "
          + "(distinguish GROCERIES, DINING, HOUSING, UTILITIES, TELECOM, HOME_GARDEN, FUEL, TRANSPORT, "
          + "CAR_MAINTENANCE, HEALTH, FITNESS_SPORT, CLOTHING, BEAUTY_CARE, ELECTRONICS, SHOPPING, "
          + "ENTERTAINMENT, SUBSCRIPTIONS, TRAVEL, EDUCATION, PETS, GIFTS, SERVICES_FEES). "
          + "Prefer the user's learned rules when the merchant name is a variant, branch, or abbreviation of a learned rule. "
          + "Use INTERNAL_TRANSFER for transfers matching the account owner's name or own accounts.";
  private static final Map<ExpenseCategory, String> CATEGORY_DESCRIPTIONS = buildCategoryDescriptions();

  private final String provider;
  private final String model;
  private final String baseUrl;
  private final int numCtx;
  private final HttpClient httpClient;
  private final ObjectMapper objectMapper;

  public OllamaSystemOneLlmClient(
      @Value("${app.expenses.llm.provider:ollama}") String provider,
      @Value("${app.expenses.llm.model:tev1:4b}") String model,
      @Value("${app.expenses.llm.base-url:http://host.docker.internal:11434}") String baseUrl,
      @Value("${app.expenses.llm.num-ctx:16384}") int numCtx) {
    this.provider = provider != null ? provider.trim() : "none";
    this.model = model != null ? model.trim() : "";
    this.baseUrl = baseUrl != null ? baseUrl.trim().replaceAll("/+$", "") : "";
    this.numCtx = Math.max(MIN_NUM_CTX, numCtx);
    this.httpClient = HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build();
    this.objectMapper = new ObjectMapper();
  }

  @Override
  public boolean isEnabled() {
    return "ollama".equalsIgnoreCase(provider) && !model.isBlank() && !baseUrl.isBlank();
  }

  @Override
  public String providerName() {
    return isEnabled() ? "ollama" : "none";
  }

  @Override
  public String modelName() {
    return isEnabled() ? model : "";
  }

  @Override
  public Map<String, LlmCategorySuggestion> classifyUnknowns(List<LlmClassifyInput> items) {
    return classifyUnknowns(items, new LlmClassificationContext(null, List.of(), List.of()));
  }

  @Override
  public Map<String, LlmCategorySuggestion> classifyUnknowns(
      List<LlmClassifyInput> items, List<MerchantRuleSnapshot> learnedExamples) {
    return classifyUnknowns(items, new LlmClassificationContext(null, List.of(), learnedExamples));
  }

  @Override
  public Map<String, LlmCategorySuggestion> classifyUnknowns(
      List<LlmClassifyInput> items, LlmClassificationContext context) {
    if (!isEnabled() || items == null || items.isEmpty()) {
      return Map.of();
    }

    LlmClassificationContext safeContext = context != null
        ? context
        : new LlmClassificationContext(null, List.of(), List.of());
    String ownerName = sanitizeLine(safeContext.accountOwnerName(), 120);
    List<String> statementMerchants = sanitizeStatementMerchants(safeContext.statementMerchants());
    List<LearnedExample> formattedExamples = sanitizeLearnedExamples(safeContext.learnedExamples());
    URI endpoint = URI.create(baseUrl + "/v1/systemone");
    Map<String, LlmCategorySuggestion> results = new LinkedHashMap<>();

    for (LlmClassifyInput item : items) {
      if (item == null || item.id() == null || item.id().isBlank()) {
        continue;
      }
      LlmCategorySuggestion suggestion =
          classifySingleItem(endpoint, item, ownerName, statementMerchants, formattedExamples);
      if (suggestion != null) {
        results.put(item.id(), suggestion);
      }
    }
    return results;
  }

  private LlmCategorySuggestion classifySingleItem(
      URI endpoint,
      LlmClassifyInput item,
      String ownerName,
      List<String> statementMerchants,
      List<LearnedExample> learnedExamples) {
    try {
      String payload = objectMapper.writeValueAsString(
          buildRequestBody(item, ownerName, statementMerchants, learnedExamples));
      HttpRequest request = HttpRequest.newBuilder(endpoint)
          .timeout(READ_TIMEOUT)
          .header("Content-Type", "application/json")
          .POST(HttpRequest.BodyPublishers.ofString(payload, StandardCharsets.UTF_8))
          .build();

      HttpResponse<String> response =
          httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
      if (response.statusCode() < 200 || response.statusCode() >= 300) {
        throw new IllegalStateException("Ollama SystemOne HTTP " + response.statusCode());
      }

      return parseSingleResponse(response.body(), item.amount());
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("Ollama SystemOne request interrupted", e);
    } catch (IOException e) {
      throw new IllegalStateException("Ollama SystemOne connection failed: " + e.getMessage(), e);
    } catch (RuntimeException e) {
      if (e instanceof IllegalStateException) {
        throw e;
      }
      throw new IllegalStateException("Ollama SystemOne call failed: " + e.getMessage(), e);
    }
  }

  private Map<String, Object> buildRequestBody(
      LlmClassifyInput item,
      String ownerName,
      List<String> statementMerchants,
      List<LearnedExample> learnedExamples) {
    String description = sanitizeLine(item.scrubbedDescription(), MAX_DESCRIPTION_CHARS);
    String merchantDisplay = sanitizeLine(item.merchantDisplay(), 120);
    String amountStr = item.amount() != null ? item.amount().toPlainString() : "0.00";
    String currency = item.currency() != null && !item.currency().isBlank()
        ? item.currency().trim().toUpperCase(Locale.ROOT)
        : "PLN";
    String direction = item.amount() != null && item.amount().signum() > 0 ? "inflow" : "outflow";

    StringBuilder state = new StringBuilder();
    state.append("Transaction to classify:\n");
    state.append("Description: ").append(description).append('\n');
    if (!merchantDisplay.isEmpty() && !"unknown".equalsIgnoreCase(merchantDisplay)) {
      state.append("Merchant: ").append(merchantDisplay).append('\n');
    }
    state.append("Amount: ").append(amountStr).append(' ').append(currency)
        .append(" (").append(direction).append(")\n");
    state.append("Statement occurrences: ").append(Math.max(1, item.statementOccurrences())).append('\n');
    if (!ownerName.isEmpty()) {
      state.append("Account owner: ").append(ownerName).append('\n');
    }
    if (!statementMerchants.isEmpty()) {
      state.append("Other merchants in statement: ").append(String.join(", ", statementMerchants)).append('\n');
    }
    if (!learnedExamples.isEmpty()) {
      state.append("User learned rules:\n");
      for (LearnedExample ex : learnedExamples) {
        state.append("- ").append(ex.label()).append(" -> ").append(ex.category()).append('\n');
      }
    }

    Map<String, Object> categoryQuestion = new LinkedHashMap<>();
    categoryQuestion.put("type", "choice");
    categoryQuestion.put("instructions", CATEGORY_INSTRUCTIONS);
    categoryQuestion.put("criteria", buildCriteriaForAmount(item.amount()));

    Map<String, Object> body = new LinkedHashMap<>();
    body.put("model", model);
    body.put("options", Map.of("num_ctx", numCtx));
    body.put("state", state.toString().trim());
    body.put("questions", Map.of("category", categoryQuestion));
    return body;
  }

  private static Map<String, String> buildCriteriaForAmount(BigDecimal amount) {
    Map<String, String> criteria = new LinkedHashMap<>();
    for (ExpenseCategory category : ExpenseCategory.values()) {
      if (ExpenseSupport.isFlowCompatibleWithAmount(category.defaultFlowKind(), amount)) {
        criteria.put(category.name(), CATEGORY_DESCRIPTIONS.get(category));
      }
    }
    return criteria;
  }

  private static List<String> sanitizeStatementMerchants(List<String> merchants) {
    if (merchants == null || merchants.isEmpty()) {
      return List.of();
    }
    Set<String> unique = new LinkedHashSet<>();
    for (String m : merchants) {
      String cleaned = sanitizeLine(m, 80);
      if (cleaned.isEmpty() || "unknown".equalsIgnoreCase(cleaned)) {
        continue;
      }
      unique.add(cleaned);
      if (unique.size() >= MAX_STATEMENT_MERCHANTS) {
        break;
      }
    }
    return List.copyOf(unique);
  }

  private static List<LearnedExample> sanitizeLearnedExamples(
      List<MerchantRuleSnapshot> learnedExamples) {
    if (learnedExamples == null || learnedExamples.isEmpty()) {
      return List.of();
    }
    Map<String, LearnedExample> deduplicated = new LinkedHashMap<>();
    for (MerchantRuleSnapshot rule : learnedExamples) {
      if (rule == null || rule.matchKey() == null || rule.category() == null) {
        continue;
      }
      String normalizedKey = ExpenseSupport.toAsciiLower(rule.matchKey()).trim();
      if (normalizedKey.isEmpty()
          || "unknown".equals(normalizedKey)
          || MerchantNormalizer.isGatewayKey(normalizedKey)) {
        continue;
      }
      String label = normalizedKey.replace('_', ' ').replaceAll("\\s+", " ").trim();
      if (label.isEmpty()) {
        continue;
      }
      deduplicated.putIfAbsent(label, new LearnedExample(label, rule.category().name()));
      if (deduplicated.size() >= MAX_LEARNED_EXAMPLES) {
        break;
      }
    }
    return List.copyOf(deduplicated.values());
  }

  private static String sanitizeLine(String value, int maxChars) {
    if (value == null) {
      return "";
    }
    String normalized = value.replaceAll("[\\r\\n\\t]+", " ").replaceAll("\\s+", " ").trim();
    if (normalized.length() > maxChars) {
      return normalized.substring(0, maxChars).trim();
    }
    return normalized;
  }

  private LlmCategorySuggestion parseSingleResponse(String rawJson, BigDecimal amount) {
    if (rawJson == null || rawJson.isBlank()) {
      return null;
    }
    JsonNode root = objectMapper.readTree(rawJson);
    JsonNode categoryNode = root.path("answers").path("category");
    if (categoryNode.isMissingNode() || !categoryNode.isObject()) {
      return null;
    }

    ExpenseCategory category = ExpenseCategory.fromStringOrNull(categoryNode.path("choice").asText(""));
    if (category == null || !ExpenseSupport.isFlowCompatibleWithAmount(category.defaultFlowKind(), amount)) {
      return null;
    }

    BigDecimal confidence = resolveConfidence(categoryNode, category);
    return new LlmCategorySuggestion(category, category.defaultFlowKind(), "", confidence);
  }

  private static BigDecimal resolveConfidence(JsonNode categoryNode, ExpenseCategory chosenCategory) {
    BigDecimal direct = parseNumericNode(categoryNode.path("confidence"));
    if (direct != null) {
      return direct;
    }

    JsonNode probsNode = categoryNode.path("probabilities");
    if (probsNode.isObject()) {
      BigDecimal chosenProb = parseNumericNode(probsNode.path(chosenCategory.name()));
      if (chosenProb != null) {
        return chosenProb;
      }
      double maxProb = Double.NEGATIVE_INFINITY;
      for (JsonNode val : probsNode) {
        if (val.isNumber()) {
          double d = val.asDouble();
          if (Double.isFinite(d) && d > maxProb) {
            maxProb = d;
          }
        }
      }
      if (Double.isFinite(maxProb)) {
        return clampConfidence(maxProb);
      }
    }
    return DEFAULT_CONFIDENCE;
  }

  private static BigDecimal parseNumericNode(JsonNode node) {
    if (node.isNumber()) {
      return clampConfidence(node.asDouble());
    }
    if (node.isString()) {
      try {
        return clampConfidence(Double.parseDouble(node.asText().trim()));
      } catch (NumberFormatException ignored) {
      }
    }
    return null;
  }

  private static BigDecimal clampConfidence(double raw) {
    if (!Double.isFinite(raw)) {
      return DEFAULT_CONFIDENCE;
    }
    double clamped = Math.max(0.0, Math.min(1.0, raw));
    return BigDecimal.valueOf(clamped).setScale(2, RoundingMode.HALF_UP);
  }

  private static Map<ExpenseCategory, String> buildCategoryDescriptions() {
    EnumMap<ExpenseCategory, String> map = new EnumMap<>(ExpenseCategory.class);
    map.put(ExpenseCategory.GROCERIES, "Supermarkets, food stores, bakeries, butcher shops, daily groceries");
    map.put(ExpenseCategory.DINING, "Restaurants, cafes, bars, fast food, food delivery, canteens");
    map.put(ExpenseCategory.HOUSING, "Rent, mortgage payments, HOA or housing co-op fees");
    map.put(ExpenseCategory.UTILITIES, "Electricity, gas, water, heating, waste collection, municipal utilities");
    map.put(ExpenseCategory.TELECOM, "Mobile phone, fiber internet, cable TV, telecom bundles");
    map.put(ExpenseCategory.HOME_GARDEN, "Furniture, DIY hardware, home improvement, decor, garden supplies");
    map.put(ExpenseCategory.FUEL, "Fuel stations, petrol, diesel, LPG, EV charging");
    map.put(ExpenseCategory.TRANSPORT, "Public transit, rail, trains, buses, taxis, ride-hailing, scooters");
    map.put(ExpenseCategory.CAR_MAINTENANCE, "Highway tolls, parking, car wash, mechanic, tires, auto parts, inspection");
    map.put(ExpenseCategory.HEALTH, "Pharmacies, doctors, dentists, clinics, medical labs, optical, healthcare");
    map.put(ExpenseCategory.FITNESS_SPORT, "Gym memberships, fitness passes, sports clubs, sporting goods");
    map.put(ExpenseCategory.CLOTHING, "Apparel, footwear, fashion marketplaces, clothing accessories");
    map.put(ExpenseCategory.BEAUTY_CARE, "Drugstores, cosmetics, perfumery, hairdresser, barber, beauty salon, spa");
    map.put(ExpenseCategory.ELECTRONICS, "Consumer electronics, computers, smartphones, RTV and household appliances");
    map.put(ExpenseCategory.SHOPPING, "General online marketplaces, department stores, miscellaneous retail merchandise");
    map.put(ExpenseCategory.ENTERTAINMENT, "Cinema, concerts, theater, events, gaming, amusement parks");
    map.put(ExpenseCategory.SUBSCRIPTIONS, "Recurring digital services, streaming, cloud storage, software, AI tools");
    map.put(ExpenseCategory.TRAVEL, "Hotels, flights, accommodations, car rentals, travel agencies");
    map.put(ExpenseCategory.EDUCATION, "Courses, tutoring, books, tuition, schools, universities, childcare");
    map.put(ExpenseCategory.PETS, "Pet stores, pet food, veterinary clinics, pet care supplies");
    map.put(ExpenseCategory.GIFTS, "Gifts, jewelry, flowers, charity donations, fundraising");
    map.put(ExpenseCategory.SERVICES_FEES, "Bank fees, insurance, courier and postal services, legal, accounting, taxes");
    map.put(ExpenseCategory.OTHER_EXPENSE, "Uncategorized or miscellaneous expenses not fitting other categories");
    map.put(ExpenseCategory.INCOME, "Salary, wages, invoices, dividends, interest, benefits, other income");
    map.put(ExpenseCategory.INVESTMENTS_SAVINGS, "Brokerage deposits, stocks, bonds, ETFs, retirement accounts, savings transfers");
    map.put(ExpenseCategory.INTERNAL_TRANSFER, "Transfers between user's own accounts, self-transfers, currency exchange, card repayments");
    map.put(ExpenseCategory.REFUND, "Merchant refunds, chargebacks, reversals, cashback credits");
    return Map.copyOf(map);
  }

  private record LearnedExample(String label, String category) {}
}
