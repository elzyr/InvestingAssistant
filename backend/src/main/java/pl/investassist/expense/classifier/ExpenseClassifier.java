package pl.investassist.expense.classifier;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import pl.investassist.expense.ExpenseSupport;
import pl.investassist.expense.enums.ClassificationSource;
import pl.investassist.expense.enums.ExpenseCategory;
import pl.investassist.expense.enums.FlowKind;
import pl.investassist.expense.enums.RuleMatchType;
import pl.investassist.expense.llm.ExpenseLlmPort;
import pl.investassist.expense.llm.LlmCategorySuggestion;
import pl.investassist.expense.llm.LlmClassificationContext;
import pl.investassist.expense.llm.LlmClassifyInput;

@Component
public class ExpenseClassifier {

  private static final Logger log = LoggerFactory.getLogger(ExpenseClassifier.class);

  private static final List<String> INTERNAL_TRANSFER_PHRASES = List.of(
      "przelew wlasny", "rachunek wlasny", "konto wlasne", "moje konto",
      "own account", "self transfer", "internal transfer",
      "przelew miedzy rachunkami", "przelew miedzy kontami",
      "top-up by card", "topup", "doladowanie revolut", "revolut top-up",
      "przewalutowanie", "kantor walutowy", "zasilenie konta",
      "splata karty kredytowej", "credit card repayment");

  private static final List<String> INVESTMENT_PHRASES = List.of(
      "xtb", "bossa", "dm bos", "degiro", "interactive brokers", "ibkr",
      "trading 212", "trading212", "emakler", "mbank makler", "bm pko",
      "obligacjeskarbowe", "obligacje skarbowe", "obligacje",
      "ike", "ikze", "ppk", "finax", "portu.pl", "portu pl", "inpzu", "goldsaver",
      "konto oszczednosciowe", "cel oszczednosciowy", "lokata", "savings vault",
      "fundusz inwestycyjny", "tfi", "brokerage");

  private static final List<String> SALARY_PHRASES = List.of(
      "wynagrodzenie", "pensja", "salary", "payroll", "emerytura", "stypendium");

  private static final List<String> REFUND_PHRASES = List.of(
      "zwrot", "refund", "reversal", "chargeback", "cashback", "korekta", "anulowanie");

  private static final List<String> INCOME_PHRASES = List.of(
      "wynagrodzenie", "pensja", "salary", "payroll", "wyplata wynagrodzenia",
      "faktura", "invoice", "dywidenda", "dividend", "odsetki", "interest",
      "stypendium", "emerytura", "renta", "800+", "500+", "premia", "bonus",
      "honorarium", "umowa o dzielo", "umowa zlecenie", "przychod", "deposit");

  private static final Map<ExpenseCategory, List<String>> CATEGORY_PHRASES = buildCategoryPhrases();

  private final MerchantNormalizer normalizer;
  private final PiiScrubber piiScrubber;
  private final ExpenseLlmPort llmPort;

  public ExpenseClassifier(
      MerchantNormalizer normalizer,
      PiiScrubber piiScrubber,
      ExpenseLlmPort llmPort) {
    this.normalizer = normalizer;
    this.piiScrubber = piiScrubber;
    this.llmPort = llmPort;
  }

  public List<ClassifiedTransaction> classifyBatch(
      List<ClassifiableRow> rows,
      List<MerchantRuleSnapshot> userRules,
      boolean useAi) {
    return classifyBatchWithMeta(rows, userRules, useAi, null).transactions();
  }

  public ClassificationBatchResult classifyBatchWithMeta(
      List<ClassifiableRow> rows,
      List<MerchantRuleSnapshot> userRules,
      boolean useAi) {
    return classifyBatchWithMeta(rows, userRules, useAi, null);
  }

  public ClassificationBatchResult classifyBatchWithMeta(
      List<ClassifiableRow> rows,
      List<MerchantRuleSnapshot> userRules,
      boolean useAi,
      String accountOwnerName) {
    if (rows == null || rows.isEmpty()) {
      return new ClassificationBatchResult(List.of(), false, null);
    }

    List<MerchantRuleSnapshot> safeRules = userRules != null ? userRules : List.of();
    List<String> ownerTokens = extractOwnerTokens(accountOwnerName);
    String cleanOwnerName = accountOwnerName != null && !accountOwnerName.isBlank()
        ? accountOwnerName.trim()
        : null;
    ClassifiedTransaction[] results = new ClassifiedTransaction[rows.size()];
    List<PendingUnknown> unknowns = new ArrayList<>();
    Map<String, Integer> occurrenceCounts = new HashMap<>();
    Set<String> statementMerchants = new LinkedHashSet<>();

    for (int i = 0; i < rows.size(); i++) {
      ClassifiableRow row = rows.get(i);
      NormalizedMerchant norm = normalizer.normalize(row.rawDescription());
      String asciiRaw = ExpenseSupport.toAsciiLower(row.rawDescription());
      BigDecimal amount = row.amount() != null ? row.amount() : BigDecimal.ZERO;

      String occKey = occurrenceKey(norm);
      if (occKey != null) {
        occurrenceCounts.merge(occKey, 1, Integer::sum);
      }
      String safeMerchantDisplay = sanitizeDisplay(norm.merchantDisplay());
      if (safeMerchantDisplay != null) {
        statementMerchants.add(safeMerchantDisplay);
      }

      ClassifiedTransaction decided = matchUserRules(row, norm, safeRules, amount);
      if (decided == null) {
        decided = detectStructuralFlow(row, norm, asciiRaw, amount);
      }
      if (decided == null) {
        decided = matchCuratedDictionary(row, norm, asciiRaw, amount);
      }
      if (decided == null && isOwnerSelfTransfer(asciiRaw, ownerTokens)) {
        decided = buildRuleEngine(row, norm, FlowKind.INTERNAL_TRANSFER, ExpenseCategory.INTERNAL_TRANSFER, "0.95");
      }

      if (decided != null) {
        results[i] = decided;
      } else {
        unknowns.add(new PendingUnknown(i, row, norm, amount));
      }
    }

    boolean aiUsed = false;
    String aiWarning = null;
    Map<String, LlmCategorySuggestion> llmSuggestions = Map.of();

    if (!unknowns.isEmpty() && useAi && llmPort != null && llmPort.isEnabled()) {
      Map<LlmDedupeKey, List<PendingUnknown>> groupedUnknowns = new LinkedHashMap<>();
      Map<LlmDedupeKey, LlmClassifyInput> representativeInputs = new LinkedHashMap<>();
      for (PendingUnknown u : unknowns) {
        String scrubbed = piiScrubber.scrub(u.row.rawDescription());
        String display = sanitizeDisplay(u.norm.merchantDisplay());
        String currency = u.row.currency() != null ? u.row.currency() : "PLN";
        LlmDedupeKey dedupeKey = new LlmDedupeKey(scrubbed, display, u.amount.signum(), currency);
        groupedUnknowns.computeIfAbsent(dedupeKey, k -> new ArrayList<>()).add(u);
        representativeInputs.computeIfAbsent(dedupeKey, k -> {
          String occKey = occurrenceKey(u.norm);
          int occurrences = occKey != null ? occurrenceCounts.getOrDefault(occKey, 1) : 1;
          return new LlmClassifyInput(
              u.row.id(), scrubbed, u.amount, currency, display, occurrences);
        });
      }
      try {
        LlmClassificationContext context = new LlmClassificationContext(
            cleanOwnerName, List.copyOf(statementMerchants), safeRules);
        Map<String, LlmCategorySuggestion> uniqueSuggestions =
            llmPort.classifyUnknowns(List.copyOf(representativeInputs.values()), context);
        Map<String, LlmCategorySuggestion> expandedSuggestions = new LinkedHashMap<>();
        if (uniqueSuggestions != null) {
          for (Map.Entry<LlmDedupeKey, LlmClassifyInput> entry : representativeInputs.entrySet()) {
            LlmCategorySuggestion suggestion = uniqueSuggestions.get(entry.getValue().id());
            if (suggestion != null) {
              for (PendingUnknown match : groupedUnknowns.get(entry.getKey())) {
                expandedSuggestions.put(match.row.id(), suggestion);
              }
            }
          }
        }
        llmSuggestions = expandedSuggestions;
        aiUsed = true;
      } catch (Exception e) {
        log.warn("Expense LLM classification failed, falling back deterministically: {}", e.getMessage());
        aiWarning = "AI classification temporarily unavailable; uncertain rows marked for manual review.";
      }
    }

    for (PendingUnknown u : unknowns) {
      LlmCategorySuggestion suggestion = llmSuggestions != null ? llmSuggestions.get(u.row.id()) : null;
      FlowKind suggestedFlow = suggestion != null && suggestion.category() != null
          ? (suggestion.flowKind() != null ? suggestion.flowKind() : suggestion.category().defaultFlowKind())
          : null;
      if (suggestion != null
          && suggestion.category() != null
          && ExpenseSupport.isFlowCompatibleWithAmount(suggestedFlow, u.amount)) {
        ExpenseCategory cat = suggestion.category();
        BigDecimal conf = suggestion.confidence() != null ? suggestion.confidence() : new BigDecimal("0.75");
        String display = suggestion.merchantDisplay() != null && !suggestion.merchantDisplay().isBlank()
            ? suggestion.merchantDisplay().trim()
            : u.norm.merchantDisplay();
        boolean review = ExpenseSupport.needsReview(cat, ClassificationSource.LLM, conf);
        results[u.index] = new ClassifiedTransaction(
            u.row.id(),
            u.norm.merchantKey(),
            display,
            u.norm.phraseKey(),
            u.norm.intermediary(),
            suggestedFlow,
            cat,
            ClassificationSource.LLM,
            conf,
            review);
      } else {
        boolean isPositive = u.amount.compareTo(BigDecimal.ZERO) > 0;
        ExpenseCategory cat = isPositive ? ExpenseCategory.INCOME : ExpenseCategory.OTHER_EXPENSE;
        FlowKind fk = isPositive ? FlowKind.INCOME : FlowKind.EXPENSE;
        results[u.index] = new ClassifiedTransaction(
            u.row.id(),
            u.norm.merchantKey(),
            u.norm.merchantDisplay(),
            u.norm.phraseKey(),
            u.norm.intermediary(),
            fk,
            cat,
            ClassificationSource.FALLBACK,
            new BigDecimal("0.30"),
            true);
      }
    }

    return new ClassificationBatchResult(List.of(results), aiUsed, aiWarning);
  }

  private ClassifiedTransaction matchUserRules(
      ClassifiableRow row, NormalizedMerchant norm, List<MerchantRuleSnapshot> rules, BigDecimal amount) {
    if (rules.isEmpty()) {
      return null;
    }

    String phraseKey = norm.phraseKey() != null ? norm.phraseKey().toLowerCase() : "";
    for (MerchantRuleSnapshot rule : rules) {
      if (rule.matchType() != RuleMatchType.PHRASE || rule.matchKey() == null || rule.matchKey().isBlank()) {
        continue;
      }
      FlowKind fk = rule.flowKind() != null ? rule.flowKind() : rule.category().defaultFlowKind();
      if (!ExpenseSupport.isFlowCompatibleWithAmount(fk, amount)) {
        continue;
      }
      String ruleKey = ExpenseSupport.toAsciiLower(rule.matchKey()).trim();
      if (ruleKey.isEmpty() || MerchantNormalizer.isGatewayKey(ruleKey)) {
        continue;
      }
      if (!phraseKey.isEmpty()
          && (phraseKey.equals(ruleKey) || ExpenseSupport.containsWholePhrase(phraseKey, ruleKey))) {
        return new ClassifiedTransaction(
            row.id(),
            norm.merchantKey(),
            norm.merchantDisplay(),
            norm.phraseKey(),
            norm.intermediary(),
            fk,
            rule.category(),
            ClassificationSource.USER_RULE,
            new BigDecimal("1.00"),
            false);
      }
    }

    if (!MerchantNormalizer.isGatewayKey(norm.merchantKey())) {
      for (MerchantRuleSnapshot rule : rules) {
        if (rule.matchType() != RuleMatchType.MERCHANT || rule.matchKey() == null || rule.matchKey().isBlank()) {
          continue;
        }
        FlowKind fk = rule.flowKind() != null ? rule.flowKind() : rule.category().defaultFlowKind();
        if (!ExpenseSupport.isFlowCompatibleWithAmount(fk, amount)) {
          continue;
        }
        String ruleKey = rule.matchKey().trim().toLowerCase();
        if (MerchantNormalizer.isGatewayKey(ruleKey)) {
          continue;
        }
        if (norm.merchantKey() != null && norm.merchantKey().equalsIgnoreCase(ruleKey)) {
          return new ClassifiedTransaction(
              row.id(),
              norm.merchantKey(),
              norm.merchantDisplay(),
              norm.phraseKey(),
              norm.intermediary(),
              fk,
              rule.category(),
              ClassificationSource.USER_RULE,
              new BigDecimal("1.00"),
              false);
        }
      }
    }

    return null;
  }

  private ClassifiedTransaction detectStructuralFlow(
      ClassifiableRow row,
      NormalizedMerchant norm,
      String asciiRaw,
      BigDecimal amount) {
    if (matchesAny(asciiRaw, INTERNAL_TRANSFER_PHRASES)) {
      return buildRuleEngine(row, norm, FlowKind.INTERNAL_TRANSFER, ExpenseCategory.INTERNAL_TRANSFER, "0.95");
    }

    if (amount.compareTo(BigDecimal.ZERO) > 0 && matchesAny(asciiRaw, SALARY_PHRASES)) {
      return buildRuleEngine(row, norm, FlowKind.INCOME, ExpenseCategory.INCOME, "0.92");
    }

    ExpenseCategory brandCat = normalizer.lookupBrandCategory(norm.merchantKey());
    if (brandCat == ExpenseCategory.INVESTMENTS_SAVINGS || matchesAny(asciiRaw, INVESTMENT_PHRASES)) {
      return buildRuleEngine(row, norm, FlowKind.INVESTMENT_SAVING, ExpenseCategory.INVESTMENTS_SAVINGS, "0.94");
    }

    if (amount.compareTo(BigDecimal.ZERO) > 0) {
      if (matchesAny(asciiRaw, REFUND_PHRASES)) {
        return buildRuleEngine(row, norm, FlowKind.REFUND, ExpenseCategory.REFUND, "0.92");
      }
      if (matchesAny(asciiRaw, INCOME_PHRASES)) {
        return buildRuleEngine(row, norm, FlowKind.INCOME, ExpenseCategory.INCOME, "0.92");
      }
    }

    return null;
  }

  private ClassifiedTransaction matchCuratedDictionary(
      ClassifiableRow row, NormalizedMerchant norm, String asciiRaw, BigDecimal amount) {
    if (amount.compareTo(BigDecimal.ZERO) > 0) {
      return null;
    }

    ExpenseCategory brandCategory = normalizer.lookupBrandCategory(norm.merchantKey());
    if (brandCategory != null) {
      return buildRuleEngine(row, norm, brandCategory.defaultFlowKind(), brandCategory, "0.94");
    }

    String searchText = norm.intermediary() && norm.phraseKey() != null
        ? norm.phraseKey()
        : asciiRaw;

    ExpenseCategory bestCategory = null;
    int bestLength = -1;
    for (Map.Entry<ExpenseCategory, List<String>> entry : CATEGORY_PHRASES.entrySet()) {
      for (String phrase : entry.getValue()) {
        if (phrase.length() > bestLength
            && ExpenseSupport.containsWholePhrase(searchText, phrase)) {
          bestCategory = entry.getKey();
          bestLength = phrase.length();
        }
      }
    }
    if (bestCategory != null) {
      return buildRuleEngine(row, norm, bestCategory.defaultFlowKind(), bestCategory, "0.86");
    }
    return null;
  }

  private static ClassifiedTransaction buildRuleEngine(
      ClassifiableRow row,
      NormalizedMerchant norm,
      FlowKind flowKind,
      ExpenseCategory category,
      String confidence) {
    return new ClassifiedTransaction(
        row.id(),
        norm.merchantKey(),
        norm.merchantDisplay(),
        norm.phraseKey(),
        norm.intermediary(),
        flowKind,
        category,
        ClassificationSource.RULE_ENGINE,
        new BigDecimal(confidence),
        false);
  }

  private static boolean matchesAny(String haystack, List<String> phrases) {
    for (String phrase : phrases) {
      if (ExpenseSupport.containsWholePhrase(haystack, phrase)) {
        return true;
      }
    }
    return false;
  }

  private static List<String> extractOwnerTokens(String accountOwnerName) {
    if (accountOwnerName == null || accountOwnerName.isBlank()) {
      return List.of();
    }
    String ascii = ExpenseSupport.toAsciiLower(accountOwnerName);
    Set<String> tokens = new LinkedHashSet<>();
    for (String part : ascii.split("[^a-z0-9]+")) {
      if (part.length() >= 2) {
        tokens.add(part);
      }
    }
    return tokens.size() >= 2 ? List.copyOf(tokens) : List.of();
  }

  private static boolean isOwnerSelfTransfer(String asciiRaw, List<String> ownerTokens) {
    if (ownerTokens.size() < 2 || asciiRaw == null || asciiRaw.isBlank()) {
      return false;
    }
    for (String token : ownerTokens) {
      if (!ExpenseSupport.containsWholePhrase(asciiRaw, token)) {
        return false;
      }
    }
    return true;
  }

  private static String occurrenceKey(NormalizedMerchant norm) {
    String key = norm.merchantKey() != null ? norm.merchantKey().trim() : "";
    String phrase = norm.phraseKey() != null ? norm.phraseKey().trim() : "";
    if (key.isEmpty() || "unknown".equalsIgnoreCase(key)) {
      return phrase.isEmpty() ? null : "unknown:" + phrase;
    }
    if (MerchantNormalizer.isGatewayKey(key) && !phrase.isEmpty()) {
      return key + ":" + phrase;
    }
    return key;
  }

  private String sanitizeDisplay(String rawDisplay) {
    if (rawDisplay == null || rawDisplay.isBlank() || "unknown".equalsIgnoreCase(rawDisplay.trim())) {
      return null;
    }
    String scrubbed = piiScrubber.scrub(rawDisplay).trim();
    if (scrubbed.isEmpty()
        || "unknown".equalsIgnoreCase(scrubbed)
        || scrubbed.matches("\\[[A-Z]+\\]")) {
      return null;
    }
    return scrubbed;
  }

  private static Map<ExpenseCategory, List<String>> buildCategoryPhrases() {
    Map<ExpenseCategory, List<String>> map = new LinkedHashMap<>();
    map.put(ExpenseCategory.GROCERIES, List.of(
        "piekarnia", "cukiernia", "warzywniak", "sklep spozywczy", "spozywczy",
        "supermarket", "delikatesy", "grocery", "market spozywczy", "miesny"));
    map.put(ExpenseCategory.DINING, List.of(
        "restauracja", "bistro", "pizzeria", "kebab", "sushi", "bar mleczny",
        "kawiarnia", "cafe", "coffee", "pub", "browar", "ramen", "bakery",
        "restaurant", "burger", "lodziarnia", "catering"));
    map.put(ExpenseCategory.HOUSING, List.of(
        "czynsz", "wspolnota mieszkaniowa", "spoldzielnia mieszkaniowa",
        "wynajem", "najem", "rent", "hipoteka", "kredyt hipoteczny"));
    map.put(ExpenseCategory.UTILITIES, List.of(
        "wodociagi", "gazownia", "energia elektryczna", "prad", "ogrzewanie",
        "wywoz smieci", "odpady komunalne", "woda i scieki", "centralne ogrzewanie"));
    map.put(ExpenseCategory.TELECOM, List.of(
        "swiatlowod", "internet domowy", "telewizja kablowa", "telefon komorkowy",
        "gsm", "telekomunikacja"));
    map.put(ExpenseCategory.HOME_GARDEN, List.of(
        "meble", "ogrod", "sklep budowlany", "materialy budowlane",
        "wyposazenie wnetrz", "narzedzia"));
    map.put(ExpenseCategory.FUEL, List.of(
        "stacja paliw", "paliwo", "fuel", "tankowanie", "ladowanie ev",
        "orlen charge", "greenway"));
    map.put(ExpenseCategory.TRANSPORT, List.of(
        "taxi", "bilet kolejowy", "bilet mpk", "bilet autobusowy",
        "komunikacja miejska", "przejazd kolejowy"));
    map.put(ExpenseCategory.CAR_MAINTENANCE, List.of(
        "parking", "parkometr", "mechanik", "wulkanizacja", "myjnia",
        "autostrada", "przejazd autostrada", "warsztat samochodowy",
        "stacja kontroli pojazdow", "przeglad techniczny", "czesci samochodowe", "opony"));
    map.put(ExpenseCategory.HEALTH, List.of(
        "apteka", "pharmacy", "dentysta", "dentystyka", "stomatolog", "klinika",
        "przychodnia", "szpital", "lekarz", "optyk", "okulista", "fizjoterapeuta",
        "psycholog", "laboratorium medyczne", "badania laboratoryjne", "rehabilitacja"));
    map.put(ExpenseCategory.FITNESS_SPORT, List.of(
        "silownia", "fitness", "basen", "klub sportowy", "crossfit",
        "pilates", "joga", "tenis", "squash", "sklep sportowy"));
    map.put(ExpenseCategory.CLOTHING, List.of(
        "obuwie", "odziez", "boutique", "butik", "garderoba"));
    map.put(ExpenseCategory.BEAUTY_CARE, List.of(
        "drogeria", "perfumeria", "kosmetyki", "fryzjer", "barber",
        "kosmetyczka", "salon urody", "salon kosmetyczny", "manicure", "pedicure", "spa"));
    map.put(ExpenseCategory.ELECTRONICS, List.of(
        "elektronika", "komputery", "rtv", "agd", "serwis gsm", "serwis komputerowy"));
    map.put(ExpenseCategory.SHOPPING, List.of(
        "galeria handlowa", "dom handlowy", "wielobranzowy", "supermarket przemyslowy"));
    map.put(ExpenseCategory.ENTERTAINMENT, List.of(
        "kino", "cinema", "teatr", "opera", "filharmonia", "muzeum", "aquapark",
        "energylandia", "kregle", "bowling", "bilard", "escape room", "koncert", "bilety"));
    map.put(ExpenseCategory.SUBSCRIPTIONS, List.of(
        "subskrypcja", "subscription", "abonament", "membership"));
    map.put(ExpenseCategory.TRAVEL, List.of(
        "hotel", "hostel", "apartament", "nocleg", "lotnisko", "airport",
        "duty free", "rentalcars", "hertz", "avis", "sixt", "airlines", "biuro podrozy"));
    map.put(ExpenseCategory.EDUCATION, List.of(
        "szkola jezykowa", "szkolenie", "korepetycje", "uczelnia", "uniwersytet",
        "politechnika", "czesne", "przedszkole", "zlobek", "ksiegarnia", "edukacja"));
    map.put(ExpenseCategory.PETS, List.of(
        "weterynarz", "gabinet weterynaryjny", "klinika weterynaryjna",
        "lecznica weterynaryjna", "przychodnia weterynaryjna", "sklep zoologiczny",
        "zoologiczny", "karma dla zwierzat", "groomer"));
    map.put(ExpenseCategory.GIFTS, List.of(
        "jubiler", "zlotnik", "kwiaciarnia", "kwiaty", "prezent",
        "prezenty", "upominek", "darowizna", "fundacja", "zbiorka", "charytatywna"));
    map.put(ExpenseCategory.SERVICES_FEES, List.of(
        "prowizja", "oplata za karte", "oplata za prowadzenie", "oplata miesieczna",
        "oplata bankowa", "bank fee", "account fee", "card fee", "ubezpieczenie",
        "notariusz", "prawnik", "adwokat", "ksiegowosc", "urzad skarbowy", "zus",
        "mandat", "pralnia", "kurier", "przesylka"));
    return Collections.unmodifiableMap(map);
  }

  public record ClassificationBatchResult(
      List<ClassifiedTransaction> transactions,
      boolean aiUsed,
      String aiWarning) {}

  private record PendingUnknown(
      int index,
      ClassifiableRow row,
      NormalizedMerchant norm,
      BigDecimal amount) {}

  private record LlmDedupeKey(
      String scrubbedDescription,
      String merchantDisplay,
      int amountSign,
      String currency) {}
}
