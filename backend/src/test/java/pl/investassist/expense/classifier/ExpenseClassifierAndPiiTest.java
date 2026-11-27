package pl.investassist.expense.classifier;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.junit.jupiter.api.Test;
import pl.investassist.expense.enums.ClassificationSource;
import pl.investassist.expense.enums.ExpenseCategory;
import pl.investassist.expense.enums.FlowKind;
import pl.investassist.expense.enums.RuleMatchType;
import pl.investassist.expense.llm.ExpenseLlmPort;
import pl.investassist.expense.llm.LlmCategorySuggestion;
import pl.investassist.expense.llm.LlmClassificationContext;
import pl.investassist.expense.llm.LlmClassifyInput;

class ExpenseClassifierAndPiiTest {

  private final MerchantNormalizer normalizer = new MerchantNormalizer();
  private final PiiScrubber scrubber = new PiiScrubber();

  @Test
  void normalizeAndClassify_consolidatesRetailVariantsToCanonicalMerchant() {
    NormalizedMerchant m1 = normalizer.normalize("PŁATNOŚĆ KARTĄ BIEDRONKA 4512 WARSZAWA");
    NormalizedMerchant m2 = normalizer.normalize("JMP S.A. BIEDRONKA 0192");

    assertThat(m1.merchantKey()).isEqualTo("biedronka");
    assertThat(m1.merchantDisplay()).isEqualTo("Biedronka");
    assertThat(m1.intermediary()).isFalse();
    assertThat(m2.merchantKey()).isEqualTo("biedronka");
    assertThat(m2.merchantDisplay()).isEqualTo("Biedronka");

    ExpenseClassifier classifier = new ExpenseClassifier(normalizer, scrubber, new StubLlmPort(false));
    LocalDate date = LocalDate.of(2026, 10, 5);
    List<ClassifiedTransaction> out = classifier.classifyBatch(
        List.of(
            new ClassifiableRow("1", date, "PŁATNOŚĆ KARTĄ BIEDRONKA 4512 WARSZAWA", new BigDecimal("-95.00"), "PLN"),
            new ClassifiableRow("2", date, "JMP S.A. BIEDRONKA 0192", new BigDecimal("-42.00"), "PLN")),
        List.of(),
        false);

    assertThat(out).hasSize(2);
    assertThat(out.get(0).merchantKey()).isEqualTo("biedronka");
    assertThat(out.get(0).category()).isEqualTo(ExpenseCategory.GROCERIES);
    assertThat(out.get(0).flowKind()).isEqualTo(FlowKind.EXPENSE);
    assertThat(out.get(1).merchantKey()).isEqualTo("biedronka");
    assertThat(out.get(1).category()).isEqualTo(ExpenseCategory.GROCERIES);
    assertThat(out.get(1).flowKind()).isEqualTo(FlowKind.EXPENSE);
  }

  @Test
  void intermediaryUnpacking_resolvesSubMerchantsAndPreventsRuleHijack() {
    NormalizedMerchant payuNorm = normalizer.normalize("PAYU / KURS ANGIELSKIEGO");
    assertThat(payuNorm.intermediary()).isTrue();
    assertThat(payuNorm.phraseKey()).contains("kurs angielskiego").doesNotContain("payu");

    ExpenseClassifier classifier = new ExpenseClassifier(normalizer, scrubber, new StubLlmPort(false));
    LocalDate date = LocalDate.of(2026, 10, 8);

    List<MerchantRuleSnapshot> rules = List.of(
        new MerchantRuleSnapshot(
            RuleMatchType.PHRASE, "kurs angielskiego", ExpenseCategory.EDUCATION, FlowKind.EXPENSE),
        new MerchantRuleSnapshot(
            RuleMatchType.MERCHANT, "payu", ExpenseCategory.SHOPPING, FlowKind.EXPENSE)
    );

    List<ClassifiedTransaction> out = classifier.classifyBatch(
        List.of(
            new ClassifiableRow("1", date, "PRZELEWY24 / KINO HELIOS WARSZAWA", new BigDecimal("-44.00"), "PLN"),
            new ClassifiableRow("2", date, "PRZELEWY24 / ZNANYLEKARZ", new BigDecimal("-220.00"), "PLN"),
            new ClassifiableRow("3", date, "PAYU / KURS ANGIELSKIEGO", new BigDecimal("-300.00"), "PLN"),
            new ClassifiableRow("4", date, "PAYU / TAJEMNICZA USLUGA XYZ", new BigDecimal("-120.00"), "PLN")),
        rules,
        false);

    assertThat(out.get(0).category()).isEqualTo(ExpenseCategory.ENTERTAINMENT);
    assertThat(out.get(1).category()).isEqualTo(ExpenseCategory.HEALTH);
    assertThat(out.get(2).category()).isEqualTo(ExpenseCategory.EDUCATION);
    assertThat(out.get(2).source()).isEqualTo(ClassificationSource.USER_RULE);
    assertThat(out.get(2).needsReview()).isFalse();
    assertThat(out.get(3).category()).isNotEqualTo(ExpenseCategory.EDUCATION);
    assertThat(out.get(3).source()).isNotEqualTo(ClassificationSource.USER_RULE);
  }

  @Test
  void flowDetector_separatesInternalTransfersInvestmentsRefundsAndIncome() {
    ExpenseClassifier classifier = new ExpenseClassifier(normalizer, scrubber, new StubLlmPort(false));
    LocalDate date = LocalDate.of(2026, 10, 10);

    List<ClassifiedTransaction> out = classifier.classifyBatch(
        List.of(
            new ClassifiableRow("1", date, "PRZELEW NA RACHUNEK WŁASNY", new BigDecimal("-1000.00"), "PLN"),
            new ClassifiableRow("2", date, "XTB S.A. WPŁATA", new BigDecimal("-2500.00"), "PLN"),
            new ClassifiableRow("3", date, "ZALANDO ZWROT", new BigDecimal("450.00"), "PLN"),
            new ClassifiableRow("4", date, "WYNAGRODZENIE ZA PRACE", new BigDecimal("8500.00"), "PLN")),
        List.of(),
        false);

    assertThat(out.get(0).category()).isEqualTo(ExpenseCategory.INTERNAL_TRANSFER);
    assertThat(out.get(0).flowKind()).isEqualTo(FlowKind.INTERNAL_TRANSFER);
    assertThat(out.get(1).category()).isEqualTo(ExpenseCategory.INVESTMENTS_SAVINGS);
    assertThat(out.get(1).flowKind()).isEqualTo(FlowKind.INVESTMENT_SAVING);
    assertThat(out.get(2).category()).isEqualTo(ExpenseCategory.REFUND);
    assertThat(out.get(2).flowKind()).isEqualTo(FlowKind.REFUND);
    assertThat(out.get(3).category()).isEqualTo(ExpenseCategory.INCOME);
    assertThat(out.get(3).flowKind()).isEqualTo(FlowKind.INCOME);
  }

  @Test
  void piiScrubber_removesIbanNrbCardPeselEmailPhoneAndLongRefsPreservingWords() {
    String raw = "PRZELEW PL61 1090 1014 0000 0712 1981 2874 KARTA 4111 2222 3333 4444 "
        + "PESEL 90010112345 jan@wp.pl +48 600 123 456";
    String scrubbed = scrubber.scrub(raw);

    assertThat(scrubbed)
        .contains("PRZELEW")
        .contains("KARTA")
        .contains("PESEL")
        .doesNotContain("1090")
        .doesNotContain("1981")
        .doesNotContain("2874")
        .doesNotContain("4111")
        .doesNotContain("2222")
        .doesNotContain("3333")
        .doesNotContain("4444")
        .doesNotContain("90010112345")
        .doesNotContain("jan@wp.pl")
        .doesNotContain("600")
        .doesNotContain("123")
        .doesNotContain("456");

    String extra = "NRB 61109010140000071219812874 REF 987654321 KARTA 4111 **** **** 4444 BIEDRONKA 4512";
    String scrubbedExtra = scrubber.scrub(extra);

    assertThat(scrubbedExtra)
        .contains("BIEDRONKA")
        .doesNotContain("61109010140000071219812874")
        .doesNotContain("987654321")
        .doesNotContain("4111")
        .doesNotContain("4444");

    String foreignAndGrouped = "SEPA de89 3704 0044 0532 0130 00 KARTA 5214 56** **** 1234 "
        + "ALT 4*** **** **** 9876 TEL 600 123 456 SKLEP";
    String scrubbedForeign = scrubber.scrub(foreignAndGrouped);

    assertThat(scrubbedForeign)
        .contains("SEPA")
        .contains("SKLEP")
        .doesNotContain("3704")
        .doesNotContain("0130")
        .doesNotContain("5214")
        .doesNotContain("1234")
        .doesNotContain("9876")
        .doesNotContain("600")
        .doesNotContain("456");
  }

  @Test
  void piiScrubber_normalizesNbspAndNarrowNbspSeparatorsBeforeMatchingPii() {
    String nbspRaw = "PRZELEW PL61\u00A01090\u00A01014\u00A00000\u00A00712\u00A01981\u00A02874 "
        + "KARTA 4111\u00A02222\u00A03333\u00A04444 TEL +48\u00A0600\u00A0123\u00A0456";
    String scrubbedNbsp = scrubber.scrub(nbspRaw);

    assertThat(scrubbedNbsp)
        .contains("PRZELEW")
        .contains("KARTA")
        .contains("TEL")
        .contains("[IBAN]")
        .contains("[CARD]")
        .contains("[PHONE]")
        .doesNotContain("\u00A0")
        .doesNotContain("1090")
        .doesNotContain("1981")
        .doesNotContain("2874")
        .doesNotContain("4111")
        .doesNotContain("2222")
        .doesNotContain("3333")
        .doesNotContain("4444")
        .doesNotContain("600")
        .doesNotContain("123")
        .doesNotContain("456");

    String narrowNbspRaw = "NRB 61\u202F1090\u202F1014\u202F0000\u202F0712\u202F1981\u202F2874 "
        + "KARTA 5214\u202F56**\u202F****\u202F1234 TEL 600\u202F123\u202F456 "
        + "SEPA de89\u202F3704\u202F0044\u202F0532\u202F0130\u202F00 BIEDRONKA";
    String scrubbedNarrow = scrubber.scrub(narrowNbspRaw);

    assertThat(scrubbedNarrow)
        .contains("NRB")
        .contains("KARTA")
        .contains("TEL")
        .contains("SEPA")
        .contains("BIEDRONKA")
        .contains("[ACCOUNT]")
        .contains("[CARD]")
        .contains("[PHONE]")
        .contains("[IBAN]")
        .doesNotContain("\u202F")
        .doesNotContain("1090")
        .doesNotContain("2874")
        .doesNotContain("5214")
        .doesNotContain("1234")
        .doesNotContain("600")
        .doesNotContain("123")
        .doesNotContain("456")
        .doesNotContain("3704")
        .doesNotContain("0130");
  }

  @Test
  void userMerchantRule_respectsAmountSignAndOverridesNonGatewayMerchants() {
    ExpenseClassifier classifier = new ExpenseClassifier(normalizer, scrubber, new StubLlmPort(false));
    LocalDate date = LocalDate.of(2026, 10, 11);

    List<MerchantRuleSnapshot> rules = List.of(
        new MerchantRuleSnapshot(RuleMatchType.MERCHANT, "zalando", ExpenseCategory.SHOPPING, FlowKind.EXPENSE),
        new MerchantRuleSnapshot(RuleMatchType.MERCHANT, "pracodawca", ExpenseCategory.INCOME, FlowKind.INCOME),
        new MerchantRuleSnapshot(RuleMatchType.MERCHANT, "empik", ExpenseCategory.EDUCATION, FlowKind.EXPENSE)
    );

    List<ClassifiedTransaction> out = classifier.classifyBatch(
        List.of(
            new ClassifiableRow("1", date, "ZALANDO", new BigDecimal("-450.00"), "PLN"),
            new ClassifiableRow("2", date, "ZALANDO ZWROT", new BigDecimal("450.00"), "PLN"),
            new ClassifiableRow("3", date, "PRACODAWCA KOREKTA", new BigDecimal("-50.00"), "PLN"),
            new ClassifiableRow("4", date, "EMPIK 123 WARSZAWA", new BigDecimal("-89.00"), "PLN")),
        rules,
        false);

    assertThat(out.get(0).category()).isEqualTo(ExpenseCategory.SHOPPING);
    assertThat(out.get(0).flowKind()).isEqualTo(FlowKind.EXPENSE);
    assertThat(out.get(0).source()).isEqualTo(ClassificationSource.USER_RULE);

    assertThat(out.get(1).category()).isEqualTo(ExpenseCategory.REFUND);
    assertThat(out.get(1).flowKind()).isEqualTo(FlowKind.REFUND);
    assertThat(out.get(1).source()).isEqualTo(ClassificationSource.RULE_ENGINE);

    assertThat(out.get(2).flowKind()).isEqualTo(FlowKind.EXPENSE);
    assertThat(out.get(2).category()).isEqualTo(ExpenseCategory.OTHER_EXPENSE);

    assertThat(out.get(3).category()).isEqualTo(ExpenseCategory.EDUCATION);
    assertThat(out.get(3).source()).isEqualTo(ClassificationSource.USER_RULE);
    assertThat(out.get(3).needsReview()).isFalse();
  }

  @Test
  void normalizerAndClassifier_avoidAmbiguousBrandCollisionsWhilePreservingRealBrands() {
    ExpenseClassifier classifier = new ExpenseClassifier(normalizer, scrubber, new StubLlmPort(false));
    LocalDate date = LocalDate.of(2026, 10, 14);

    List<ClassifiedTransaction> out = classifier.classifyBatch(
        List.of(
            new ClassifiableRow("1", date, "PKO BP OPLATA ZA KARTE", new BigDecimal("-10.00"), "PLN"),
            new ClassifiableRow("2", date, "PKO BP PROWIZJA ZA PROWADZENIE KONTA", new BigDecimal("-15.00"), "PLN"),
            new ClassifiableRow("3", date, "GOOGLE GEMINI SUBSCRIPTION", new BigDecimal("-90.00"), "PLN"),
            new ClassifiableRow("4", date, "FAKTURA NETTO 1000", new BigDecimal("-1000.00"), "PLN"),
            new ClassifiableRow("5", date, "BP STACJA 123", new BigDecimal("-250.00"), "PLN"),
            new ClassifiableRow("6", date, "APTEKA GEMINI", new BigDecimal("-45.00"), "PLN"),
            new ClassifiableRow("7", date, "NETTO 123", new BigDecimal("-60.00"), "PLN")),
        List.of(),
        false);

    assertThat(out.get(0).category()).isEqualTo(ExpenseCategory.SERVICES_FEES);
    assertThat(out.get(0).merchantKey()).isNotEqualTo("bp");
    assertThat(out.get(1).category()).isEqualTo(ExpenseCategory.SERVICES_FEES);
    assertThat(out.get(2).category()).isEqualTo(ExpenseCategory.SUBSCRIPTIONS);
    assertThat(out.get(2).merchantKey()).isNotEqualTo("gemini_apteka");
    assertThat(out.get(3).category()).isNotEqualTo(ExpenseCategory.GROCERIES);
    assertThat(out.get(3).merchantKey()).isNotEqualTo("netto");

    assertThat(out.get(4).category()).isEqualTo(ExpenseCategory.FUEL);
    assertThat(out.get(4).merchantKey()).isEqualTo("bp");
    assertThat(out.get(5).category()).isEqualTo(ExpenseCategory.HEALTH);
    assertThat(out.get(5).merchantKey()).isEqualTo("gemini_apteka");
    assertThat(out.get(6).category()).isEqualTo(ExpenseCategory.GROCERIES);
    assertThat(out.get(6).merchantKey()).isEqualTo("netto");
  }

  @Test
  void classifyBatchWithMeta_tracksAiUsedWarningUseAiFlagAndLowConfidenceReview() {
    LocalDate date = LocalDate.of(2026, 10, 15);
    ClassifiableRow unknownRow = new ClassifiableRow(
        "u1", date, "TAJEMNICZY SKLEP XYZ", new BigDecimal("-75.00"), "PLN");

    StubLlmPort skippedLlm = new StubLlmPort(true);
    ExpenseClassifier skipClassifier = new ExpenseClassifier(normalizer, scrubber, skippedLlm);
    ExpenseClassifier.ClassificationBatchResult noAiResult =
        skipClassifier.classifyBatchWithMeta(List.of(unknownRow), List.of(), false);
    assertThat(skippedLlm.capturedInputs).isEmpty();
    assertThat(noAiResult.aiUsed()).isFalse();
    assertThat(noAiResult.aiWarning()).isNull();
    assertThat(noAiResult.transactions().get(0).source()).isEqualTo(ClassificationSource.FALLBACK);

    StubLlmPort lowConfLlm = new StubLlmPort(true);
    lowConfLlm.suggestions = Map.of(
        "u1",
        new LlmCategorySuggestion(
            ExpenseCategory.EDUCATION, FlowKind.EXPENSE, "Sklep XYZ", new BigDecimal("0.55")));
    ExpenseClassifier lowConfClassifier = new ExpenseClassifier(normalizer, scrubber, lowConfLlm);
    ExpenseClassifier.ClassificationBatchResult lowConfResult =
        lowConfClassifier.classifyBatchWithMeta(List.of(unknownRow), List.of(), true);
    assertThat(lowConfResult.aiUsed()).isTrue();
    assertThat(lowConfResult.aiWarning()).isNull();
    assertThat(lowConfResult.transactions().get(0).category()).isEqualTo(ExpenseCategory.EDUCATION);
    assertThat(lowConfResult.transactions().get(0).source()).isEqualTo(ClassificationSource.LLM);
    assertThat(lowConfResult.transactions().get(0).needsReview()).isTrue();

    StubLlmPort failingLlm = new StubLlmPort(true);
    failingLlm.throwOnClassify = true;
    ExpenseClassifier failingClassifier = new ExpenseClassifier(normalizer, scrubber, failingLlm);
    ExpenseClassifier.ClassificationBatchResult failResult =
        failingClassifier.classifyBatchWithMeta(List.of(unknownRow), List.of(), true);
    assertThat(failResult.aiUsed()).isFalse();
    assertThat(failResult.aiWarning()).isNotBlank();
    assertThat(failResult.transactions().get(0).source()).isEqualTo(ClassificationSource.FALLBACK);
    assertThat(failResult.transactions().get(0).needsReview()).isTrue();
  }

  @Test
  void classifier_invokesLlmWithScrubbedDescriptionsAndDegradesGracefullyOnFailure() {
    StubLlmPort successLlm = new StubLlmPort(true);
    successLlm.suggestions = Map.of(
        "u1",
        new LlmCategorySuggestion(
            ExpenseCategory.EDUCATION, FlowKind.EXPENSE, "Akademia Kodu", new BigDecimal("0.88")));

    ExpenseClassifier classifier = new ExpenseClassifier(normalizer, scrubber, successLlm);
    LocalDate date = LocalDate.of(2026, 10, 12);

    List<ClassifiedTransaction> classified = classifier.classifyBatch(
        List.of(new ClassifiableRow(
            "u1",
            date,
            "AKADEMIA KODU PL61 1090 1014 0000 0712 1981 2874 jan@wp.pl",
            new BigDecimal("-499.00"),
            "PLN")),
        List.of(),
        true);

    assertThat(successLlm.capturedInputs).hasSize(1);
    assertThat(successLlm.capturedInputs.get(0).scrubbedDescription())
        .doesNotContain("1981")
        .doesNotContain("jan@wp.pl");
    assertThat(classified.get(0).category()).isEqualTo(ExpenseCategory.EDUCATION);
    assertThat(classified.get(0).source()).isEqualTo(ClassificationSource.LLM);
    assertThat(classified.get(0).needsReview()).isFalse();

    StubLlmPort failingLlm = new StubLlmPort(true);
    failingLlm.throwOnClassify = true;
    ExpenseClassifier resilient = new ExpenseClassifier(normalizer, scrubber, failingLlm);

    List<ClassifiedTransaction> fallback = resilient.classifyBatch(
        List.of(new ClassifiableRow("u2", date, "COMPLETELY UNKNOWN MERCHANT QZX", new BigDecimal("-75.00"), "PLN")),
        List.of(),
        true);

    assertThat(fallback).hasSize(1);
    assertThat(fallback.get(0).category()).isEqualTo(ExpenseCategory.OTHER_EXPENSE);
    assertThat(fallback.get(0).source()).isEqualTo(ClassificationSource.FALLBACK);
    assertThat(fallback.get(0).needsReview()).isTrue();
  }

  @Test
  void classifyBatchWithMeta_rejectsLlmSuggestionsIncompatibleWithAmountSignAndFallsBackSafely() {
    LocalDate date = LocalDate.of(2026, 10, 16);
    StubLlmPort llm = new StubLlmPort(true);
    llm.suggestions = Map.of(
        "pos-expense",
        new LlmCategorySuggestion(
            ExpenseCategory.DINING, FlowKind.EXPENSE, "Jan Kolacja", new BigDecimal("0.85")),
        "neg-income",
        new LlmCategorySuggestion(
            ExpenseCategory.INCOME, FlowKind.INCOME, "Faktura Uslugi", new BigDecimal("0.90")),
        "pos-refund-no-kw",
        new LlmCategorySuggestion(
            ExpenseCategory.SHOPPING, null, "Zalando Berlin", new BigDecimal("0.88")),
        "neg-refund",
        new LlmCategorySuggestion(
            ExpenseCategory.REFUND, FlowKind.REFUND, "Sklep Obuwniczy", new BigDecimal("0.85"))
    );

    ExpenseClassifier classifier = new ExpenseClassifier(normalizer, scrubber, llm);
    List<ClassifiableRow> rows = List.of(
        new ClassifiableRow("pos-expense", date, "PRZELEW OD JANA ZA KOLACJE", new BigDecimal("200.00"), "PLN"),
        new ClassifiableRow("neg-income", date, "FAKTURA USLUGI", new BigDecimal("-500.00"), "PLN"),
        new ClassifiableRow("zalando-out", date, "ZALANDO BERLIN", new BigDecimal("-450.00"), "PLN"),
        new ClassifiableRow("pos-refund-no-kw", date, "ZALANDO BERLIN", new BigDecimal("450.00"), "PLN"),
        new ClassifiableRow("neg-refund", date, "SKLEP OBUWNICZY XYZ", new BigDecimal("-150.00"), "PLN")
    );

    ExpenseClassifier.ClassificationBatchResult result = classifier.classifyBatchWithMeta(rows, List.of(), true);

    ClassifiedTransaction posExpense = result.transactions().get(0);
    assertThat(posExpense.flowKind()).isEqualTo(FlowKind.INCOME);
    assertThat(posExpense.category()).isEqualTo(ExpenseCategory.INCOME);
    assertThat(posExpense.source()).isEqualTo(ClassificationSource.FALLBACK);
    assertThat(posExpense.needsReview()).isTrue();

    ClassifiedTransaction negIncome = result.transactions().get(1);
    assertThat(negIncome.flowKind()).isEqualTo(FlowKind.EXPENSE);
    assertThat(negIncome.category()).isEqualTo(ExpenseCategory.OTHER_EXPENSE);
    assertThat(negIncome.source()).isEqualTo(ClassificationSource.FALLBACK);
    assertThat(negIncome.needsReview()).isTrue();

    ClassifiedTransaction zalandoOut = result.transactions().get(2);
    assertThat(zalandoOut.flowKind()).isEqualTo(FlowKind.EXPENSE);
    assertThat(zalandoOut.category()).isEqualTo(ExpenseCategory.CLOTHING);

    ClassifiedTransaction posRefundNoKw = result.transactions().get(3);
    assertThat(posRefundNoKw.flowKind()).isEqualTo(FlowKind.INCOME);
    assertThat(posRefundNoKw.category()).isEqualTo(ExpenseCategory.INCOME);
    assertThat(posRefundNoKw.source()).isEqualTo(ClassificationSource.FALLBACK);
    assertThat(posRefundNoKw.needsReview()).isTrue();

    ClassifiedTransaction negRefund = result.transactions().get(4);
    assertThat(negRefund.flowKind()).isEqualTo(FlowKind.EXPENSE);
    assertThat(negRefund.category()).isEqualTo(ExpenseCategory.OTHER_EXPENSE);
    assertThat(negRefund.source()).isEqualTo(ClassificationSource.FALLBACK);
    assertThat(negRefund.needsReview()).isTrue();
  }

  @Test
  void varyingMerchants_delegateToLlmWithClassificationContextInsteadOfFuzzyRule() {
    LocalDate date = LocalDate.of(2026, 10, 17);
    StubLlmPort llm = new StubLlmPort(true);
    llm.suggestions = Map.of(
        "var-1", new LlmCategorySuggestion(ExpenseCategory.DINING, FlowKind.EXPENSE, "Kantyna Biurowa Krakow", new BigDecimal("0.91")),
        "var-2", new LlmCategorySuggestion(ExpenseCategory.DINING, FlowKind.EXPENSE, "Kantyna Biurowa Krakow", new BigDecimal("0.91")),
        "gw-1", new LlmCategorySuggestion(ExpenseCategory.DINING, FlowKind.EXPENSE, "Kantyna Biurowa Krakow", new BigDecimal("0.91")),
        "gw-2", new LlmCategorySuggestion(ExpenseCategory.SHOPPING, FlowKind.EXPENSE, "Inny Punkt Xyz", new BigDecimal("0.88")));

    ExpenseClassifier classifier = new ExpenseClassifier(normalizer, scrubber, llm);
    List<MerchantRuleSnapshot> rules = List.of(
        new MerchantRuleSnapshot(RuleMatchType.MERCHANT, "kantyna_biurowa_waw", ExpenseCategory.DINING, FlowKind.EXPENSE),
        new MerchantRuleSnapshot(RuleMatchType.PHRASE, "kantyna biurowa waw 01", ExpenseCategory.DINING, FlowKind.EXPENSE),
        new MerchantRuleSnapshot(RuleMatchType.MERCHANT, "payu", ExpenseCategory.SHOPPING, FlowKind.EXPENSE));

    ExpenseClassifier.ClassificationBatchResult batch = classifier.classifyBatchWithMeta(
        List.of(
            new ClassifiableRow("exact-1", date, "KANTYNA BIUROWA WAW", new BigDecimal("-32.00"), "PLN"),
            new ClassifiableRow("var-1", date, "KANTYNA BIUROWA KRAKOW 02", new BigDecimal("-32.00"), "PLN"),
            new ClassifiableRow("var-2", date, "KANTYNA BIUROWA KRAKOW 02", new BigDecimal("-32.00"), "PLN"),
            new ClassifiableRow("gw-1", date, "PAYU / KANTYNA BIUROWA KRAKOW 02", new BigDecimal("-32.00"), "PLN"),
            new ClassifiableRow("gw-2", date, "PAYU / INNY PUNKT XYZ", new BigDecimal("-20.00"), "PLN"),
            new ClassifiableRow("known-1", date, "BIEDRONKA 4512", new BigDecimal("-50.00"), "PLN")),
        rules,
        true,
        "Jan Kowalski");

    List<ClassifiedTransaction> result = batch.transactions();
    assertThat(result).hasSize(6);

    assertThat(result.get(0).source()).isEqualTo(ClassificationSource.USER_RULE);
    assertThat(result.get(0).category()).isEqualTo(ExpenseCategory.DINING);
    assertThat(result.get(0).confidence()).isEqualByComparingTo("1.00");

    assertThat(result.get(1).source()).isEqualTo(ClassificationSource.LLM);
    assertThat(result.get(1).category()).isEqualTo(ExpenseCategory.DINING);
    assertThat(result.get(1).needsReview()).isFalse();
    assertThat(result.get(2).source()).isEqualTo(ClassificationSource.LLM);
    assertThat(result.get(3).source()).isEqualTo(ClassificationSource.LLM);
    assertThat(result.get(3).merchantKey()).isEqualTo("payu");
    assertThat(result.get(4).source()).isEqualTo(ClassificationSource.LLM);
    assertThat(result.get(4).merchantKey()).isEqualTo("payu");
    assertThat(result.get(5).category()).isEqualTo(ExpenseCategory.GROCERIES);

    assertThat(llm.capturedInputs)
        .extracting(LlmClassifyInput::id)
        .containsExactly("var-1", "gw-1", "gw-2");
    assertThat(llm.capturedInputs.get(0).statementOccurrences()).isEqualTo(2);
    assertThat(llm.capturedInputs.get(0).merchantDisplay()).isNotBlank();
    assertThat(llm.capturedInputs.get(1).statementOccurrences()).isEqualTo(1);
    assertThat(llm.capturedInputs.get(2).statementOccurrences()).isEqualTo(1);

    assertThat(llm.capturedContext).isNotNull();
    assertThat(llm.capturedContext.accountOwnerName()).isEqualTo("Jan Kowalski");
    assertThat(llm.capturedContext.statementMerchants()).contains("Biedronka");
    assertThat(llm.capturedContext.learnedExamples())
        .anySatisfy(rule -> {
          assertThat(rule.matchKey()).isEqualTo("kantyna_biurowa_waw");
          assertThat(rule.category()).isEqualTo(ExpenseCategory.DINING);
        });
  }

  @Test
  void normalize_stripsMbankCardBlikP2pAndIbanNoise() {
    NormalizedMerchant google = normalizer.normalize("WL*GOOGLE ZAKUP PRZY UŻYCIU KARTY - INTERNET");
    assertThat(google.merchantDisplay()).contains("Google");
    assertThat(google.merchantDisplay().toLowerCase(Locale.ROOT))
        .doesNotContain("wl")
        .doesNotContain("zakup")
        .doesNotContain("uzyciu")
        .doesNotContain("karty")
        .doesNotContain("internet");

    NormalizedMerchant interviewMe = normalizer.normalize("InterviewMe.pl ZAKUP PRZY UŻYCIU KARTY - INTERNET");
    assertThat(interviewMe.merchantDisplay().toLowerCase(Locale.ROOT))
        .contains("interviewme.pl")
        .doesNotContain("zakup")
        .doesNotContain("uzyciu")
        .doesNotContain("karty")
        .doesNotContain("internet");

    NormalizedMerchant blikIncoming = normalizer.normalize(
        "ANNA NOWAK ., Na zakupy Od: 48600100200 Do: 485*****999 . BLIK P2P-PRZYCHODZĄCY");
    assertThat(blikIncoming.merchantDisplay())
        .contains("Anna Nowak")
        .contains("Na Zakupy")
        .doesNotContain("48600100200")
        .doesNotContain("485")
        .doesNotContain("999")
        .doesNotContain("P2p")
        .doesNotContain("Przychodzacy");
    assertThat(blikIncoming.phraseKey())
        .doesNotContain("od")
        .doesNotContain("do");

    NormalizedMerchant blikOutgoing = normalizer.normalize(
        "JAN NOWAK ., Obiad Od: 48500100200 Do: 486*****999 . BLIK P2P-WYCHODZĄCY");
    assertThat(blikOutgoing.merchantDisplay())
        .contains("Jan Nowak")
        .contains("Obiad")
        .doesNotContain("48500100200")
        .doesNotContain("486")
        .doesNotContain("999")
        .doesNotContain("P2p")
        .doesNotContain("Wychodzacy");
    assertThat(blikOutgoing.phraseKey())
        .doesNotContain("od")
        .doesNotContain("do");

    NormalizedMerchant blikTwoToken = normalizer.normalize(
        "JAN NOWAK Od: +48 500 100 200 Do: 486*****999 . BLIK P2P-WYCHODZĄCY");
    assertThat(blikTwoToken.merchantDisplay()).isEqualTo("Jan Nowak");
    assertThat(blikTwoToken.phraseKey())
        .isEqualTo("jan nowak")
        .doesNotContain("od")
        .doesNotContain("do")
        .doesNotContain("500")
        .doesNotContain("100")
        .doesNotContain("200")
        .doesNotContain("486")
        .doesNotContain("999");

    NormalizedMerchant spacedIban = normalizer.normalize(
        "SKLEP ALFA PL61 1090 1014 0000 0712 1981 2874");
    assertThat(spacedIban.merchantDisplay()).isEqualTo("Sklep Alfa");
    assertThat(spacedIban.phraseKey()).isEqualTo("sklep alfa");

    NormalizedMerchant unspacedNrb = normalizer.normalize(
        "SKLEP BETA 61109010140000071219812874");
    assertThat(unspacedNrb.merchantDisplay()).isEqualTo("Sklep Beta");
    assertThat(unspacedNrb.phraseKey()).isEqualTo("sklep beta");

    NormalizedMerchant spacedNrb = normalizer.normalize(
        "SKLEP DELTA 61 1090 1014 0000 0712 1981 2874");
    assertThat(spacedNrb.merchantDisplay()).isEqualTo("Sklep Delta");
    assertThat(spacedNrb.phraseKey()).isEqualTo("sklep delta");

    NormalizedMerchant ownTransfer = normalizer.normalize(
        "Jan Kowalski ., /OPF/AN/PL00102030400000000000000000/Jan Kowalski/1/.PL/Przelew na telefon BLIK");
    assertThat(ownTransfer.merchantDisplay()).isEqualTo("Jan Kowalski");
    assertThat(ownTransfer.phraseKey()).isEqualTo("jan kowalski");

    NormalizedMerchant opfWithoutLeadingName = normalizer.normalize(
        "/OPF/AN/PL00102030400000000000000000/Jan Kowalski/1/.PL/Przelew na telefon BLIK");
    assertThat(opfWithoutLeadingName.merchantDisplay()).isEqualTo("Jan Kowalski");
    assertThat(opfWithoutLeadingName.phraseKey()).isEqualTo("jan kowalski");
  }

  @Test
  void normalize_stripsGooglePrefixAndDuplicateGoogleForCanonicalBrands() {
    NormalizedMerchant youtube = normalizer.normalize("GOOGLE *YOUTUBE");
    assertThat(youtube.merchantKey()).isEqualTo("youtube");
    assertThat(youtube.merchantDisplay()).isEqualTo("YouTube Premium");

    NormalizedMerchant googleOne = normalizer.normalize("GOOGLE *GOOGLE ONE");
    assertThat(googleOne.merchantKey()).isEqualTo("google_one");
    assertThat(googleOne.merchantDisplay()).isEqualTo("Google One");
    assertThat(googleOne.phraseKey()).isEqualTo("google one");
  }

  @Test
  void wlGoogle_isNotTreatedAsGatewayAndSupportsUserMerchantRule() {
    NormalizedMerchant google = normalizer.normalize("WL*GOOGLE ZAKUP PRZY UŻYCIU KARTY - INTERNET");
    assertThat(google.merchantKey()).isEqualTo("google");
    assertThat(google.phraseKey()).isEqualTo("google");
    assertThat(google.merchantDisplay()).isEqualTo("Google");
    assertThat(google.intermediary()).isFalse();
    assertThat(MerchantNormalizer.isGatewayKey("google")).isFalse();
    assertThat(MerchantNormalizer.isGatewayKey("payu")).isTrue();

    ExpenseClassifier classifier = new ExpenseClassifier(normalizer, scrubber, new StubLlmPort(false));
    LocalDate date = LocalDate.of(2026, 10, 17);
    List<ClassifiedTransaction> out = classifier.classifyBatch(
        List.of(new ClassifiableRow(
            "g-1", date, "WL*GOOGLE ZAKUP PRZY UŻYCIU KARTY - INTERNET", new BigDecimal("-37.99"), "PLN")),
        List.of(new MerchantRuleSnapshot(
            RuleMatchType.MERCHANT, "google", ExpenseCategory.SUBSCRIPTIONS, FlowKind.EXPENSE)),
        false);

    assertThat(out).hasSize(1);
    assertThat(out.get(0).merchantKey()).isEqualTo("google");
    assertThat(out.get(0).category()).isEqualTo(ExpenseCategory.SUBSCRIPTIONS);
    assertThat(out.get(0).flowKind()).isEqualTo(FlowKind.EXPENSE);
    assertThat(out.get(0).source()).isEqualTo(ClassificationSource.USER_RULE);
    assertThat(out.get(0).needsReview()).isFalse();
  }

  @Test
  void detectStructuralFlowAndCuratedBrands_precedeOwnerSelfTransferWhenOwnerNameAppearsInRow() {
    LocalDate date = LocalDate.of(2026, 10, 18);
    ExpenseClassifier classifier = new ExpenseClassifier(normalizer, scrubber, new StubLlmPort(false));

    ExpenseClassifier.ClassificationBatchResult result = classifier.classifyBatchWithMeta(
        List.of(
            new ClassifiableRow(
                "salary",
                date,
                "WYNAGRODZENIE ZA PRACE - JAN KOWALSKI",
                new BigDecimal("8500.00"),
                "PLN"),
            new ClassifiableRow(
                "refund",
                date,
                "ZALANDO ZWROT JAN KOWALSKI",
                new BigDecimal("450.00"),
                "PLN"),
            new ClassifiableRow(
                "investment",
                date,
                "XTB S.A. WPLATA JAN KOWALSKI",
                new BigDecimal("-2500.00"),
                "PLN"),
            new ClassifiableRow(
                "brand-travel",
                date,
                "RYANAIR JAN KOWALSKI",
                new BigDecimal("-350.00"),
                "PLN"),
            new ClassifiableRow(
                "explicit-internal",
                date,
                "PRZELEW WLASNY WYNAGRODZENIE JAN KOWALSKI",
                new BigDecimal("500.00"),
                "PLN"),
            new ClassifiableRow(
                "owner-self-transfer",
                date,
                "REVOLUT JAN KOWALSKI",
                new BigDecimal("-500.00"),
                "PLN")),
        List.of(),
        false,
        "Jan Kowalski");

    List<ClassifiedTransaction> txs = result.transactions();
    assertThat(txs).hasSize(6);

    assertThat(txs.get(0).flowKind()).isEqualTo(FlowKind.INCOME);
    assertThat(txs.get(0).category()).isEqualTo(ExpenseCategory.INCOME);
    assertThat(txs.get(0).source()).isEqualTo(ClassificationSource.RULE_ENGINE);

    assertThat(txs.get(1).flowKind()).isEqualTo(FlowKind.REFUND);
    assertThat(txs.get(1).category()).isEqualTo(ExpenseCategory.REFUND);
    assertThat(txs.get(1).source()).isEqualTo(ClassificationSource.RULE_ENGINE);

    assertThat(txs.get(2).flowKind()).isEqualTo(FlowKind.INVESTMENT_SAVING);
    assertThat(txs.get(2).category()).isEqualTo(ExpenseCategory.INVESTMENTS_SAVINGS);
    assertThat(txs.get(2).source()).isEqualTo(ClassificationSource.RULE_ENGINE);

    assertThat(txs.get(3).flowKind()).isEqualTo(FlowKind.EXPENSE);
    assertThat(txs.get(3).category()).isEqualTo(ExpenseCategory.TRAVEL);
    assertThat(txs.get(3).source()).isEqualTo(ClassificationSource.RULE_ENGINE);

    assertThat(txs.get(4).flowKind()).isEqualTo(FlowKind.INTERNAL_TRANSFER);
    assertThat(txs.get(4).category()).isEqualTo(ExpenseCategory.INTERNAL_TRANSFER);
    assertThat(txs.get(4).source()).isEqualTo(ClassificationSource.RULE_ENGINE);

    assertThat(txs.get(5).flowKind()).isEqualTo(FlowKind.INTERNAL_TRANSFER);
    assertThat(txs.get(5).category()).isEqualTo(ExpenseCategory.INTERNAL_TRANSFER);
    assertThat(txs.get(5).source()).isEqualTo(ClassificationSource.RULE_ENGINE);
  }

  @Test
  void detectStructuralFlow_classifiesFullOwnerNameTransfersAsInternalAndAvoidsFirstNameFalsePositives() {
    LocalDate date = LocalDate.of(2026, 10, 17);
    StubLlmPort llm = new StubLlmPort(true);
    ExpenseClassifier classifier = new ExpenseClassifier(normalizer, scrubber, llm);

    ExpenseClassifier.ClassificationBatchResult result = classifier.classifyBatchWithMeta(
        List.of(
            new ClassifiableRow(
                "self-in",
                date,
                "Jan Kowalski ., /OPF/AN/PL00102030400000000000000000/Jan Kowalski/1/.PL/Przelew na telefon BLIK",
                new BigDecimal("100.00"),
                "PLN"),
            new ClassifiableRow(
                "self-out",
                date,
                "REVOLUT JAN KOWALSKI",
                new BigDecimal("-500.00"),
                "PLN"),
            new ClassifiableRow(
                "first-name-shop",
                date,
                "JAN SKLEP SPOZYWCZY",
                new BigDecimal("-40.00"),
                "PLN"),
            new ClassifiableRow(
                "first-name-inflow",
                date,
                "JAN PRZELEW",
                new BigDecimal("200.00"),
                "PLN")),
        List.of(),
        true,
        "Jan Kowalski");

    List<ClassifiedTransaction> txs = result.transactions();
    assertThat(txs).hasSize(4);

    assertThat(txs.get(0).flowKind()).isEqualTo(FlowKind.INTERNAL_TRANSFER);
    assertThat(txs.get(0).category()).isEqualTo(ExpenseCategory.INTERNAL_TRANSFER);
    assertThat(txs.get(0).source()).isEqualTo(ClassificationSource.RULE_ENGINE);
    assertThat(txs.get(0).confidence()).isEqualByComparingTo("0.95");
    assertThat(txs.get(0).needsReview()).isFalse();

    assertThat(txs.get(1).flowKind()).isEqualTo(FlowKind.INTERNAL_TRANSFER);
    assertThat(txs.get(1).category()).isEqualTo(ExpenseCategory.INTERNAL_TRANSFER);
    assertThat(txs.get(1).source()).isEqualTo(ClassificationSource.RULE_ENGINE);

    assertThat(txs.get(2).flowKind()).isEqualTo(FlowKind.EXPENSE);
    assertThat(txs.get(2).category()).isEqualTo(ExpenseCategory.GROCERIES);

    assertThat(txs.get(3).flowKind()).isNotEqualTo(FlowKind.INTERNAL_TRANSFER);
    assertThat(llm.capturedInputs).extracting(LlmClassifyInput::id).containsExactly("first-name-inflow");

    ExpenseClassifier.ClassificationBatchResult singleTokenOwnerResult = classifier.classifyBatchWithMeta(
        List.of(new ClassifiableRow("single", date, "JAN PRZELEW", new BigDecimal("200.00"), "PLN")),
        List.of(),
        false,
        "Jan");
    assertThat(singleTokenOwnerResult.transactions().get(0).flowKind()).isNotEqualTo(FlowKind.INTERNAL_TRANSFER);

    ExpenseClassifier.ClassificationBatchResult nullOwnerResult = classifier.classifyBatchWithMeta(
        List.of(new ClassifiableRow("no-owner", date, "JAN KOWALSKI PRZELEW", new BigDecimal("100.00"), "PLN")),
        List.of(),
        false,
        null);
    assertThat(nullOwnerResult.transactions().get(0).flowKind()).isNotEqualTo(FlowKind.INTERNAL_TRANSFER);

    ExpenseClassifier.ClassificationBatchResult substringCollisionResult = classifier.classifyBatchWithMeta(
        List.of(new ClassifiableRow("substring-collision", date, "JANUSZ LISTOPAD", new BigDecimal("150.00"), "PLN")),
        List.of(),
        false,
        "Jan Lis");
    assertThat(substringCollisionResult.transactions().get(0).flowKind())
        .isNotEqualTo(FlowKind.INTERNAL_TRANSFER);
    assertThat(substringCollisionResult.transactions().get(0).category())
        .isNotEqualTo(ExpenseCategory.INTERNAL_TRANSFER);

    ExpenseClassifier.ClassificationBatchResult diacriticsOwnerResult = classifier.classifyBatchWithMeta(
        List.of(new ClassifiableRow("diacritics-owner", date, "LUKASZ ZOLC PRZELEW", new BigDecimal("250.00"), "PLN")),
        List.of(),
        false,
        "Łukasz Żółć");
    assertThat(diacriticsOwnerResult.transactions().get(0).flowKind()).isEqualTo(FlowKind.INTERNAL_TRANSFER);
    assertThat(diacriticsOwnerResult.transactions().get(0).category()).isEqualTo(ExpenseCategory.INTERNAL_TRANSFER);
    assertThat(diacriticsOwnerResult.transactions().get(0).source()).isEqualTo(ClassificationSource.RULE_ENGINE);
    assertThat(diacriticsOwnerResult.transactions().get(0).confidence()).isEqualByComparingTo("0.95");
    assertThat(diacriticsOwnerResult.transactions().get(0).needsReview()).isFalse();
  }

  @Test
  void classifyBatchWithMeta_forwardsSafeRulesToLlmPortAndSurfacesAiWarningOnFailure() {
    LocalDate date = LocalDate.of(2026, 10, 18);
    MerchantRuleSnapshot diningRule = new MerchantRuleSnapshot(
        RuleMatchType.MERCHANT, "kantyna_biurowa_waw", ExpenseCategory.DINING, FlowKind.EXPENSE);

    StubLlmPort llm = new StubLlmPort(true);
    llm.suggestions = Map.of(
        "u-learn",
        new LlmCategorySuggestion(
            ExpenseCategory.SERVICES_FEES, FlowKind.EXPENSE, "Unknown Studio", new BigDecimal("0.88")));

    ExpenseClassifier classifier = new ExpenseClassifier(normalizer, scrubber, llm);
    ExpenseClassifier.ClassificationBatchResult okResult = classifier.classifyBatchWithMeta(
        List.of(new ClassifiableRow("u-learn", date, "STUDIO WIZERUNKU XYZ", new BigDecimal("-80.00"), "PLN")),
        List.of(diningRule),
        true);

    assertThat(okResult.aiUsed()).isTrue();
    assertThat(okResult.aiWarning()).isNull();
    assertThat(llm.capturedLearnedExamples).containsExactly(diningRule);
    assertThat(okResult.transactions().get(0).category()).isEqualTo(ExpenseCategory.SERVICES_FEES);

    StubLlmPort failingLlm = new StubLlmPort(true);
    failingLlm.throwOnClassify = true;
    ExpenseClassifier failingClassifier = new ExpenseClassifier(normalizer, scrubber, failingLlm);
    ExpenseClassifier.ClassificationBatchResult failResult = failingClassifier.classifyBatchWithMeta(
        List.of(new ClassifiableRow("u-fail", date, "STUDIO WIZERUNKU XYZ", new BigDecimal("-80.00"), "PLN")),
        List.of(diningRule),
        true);

    assertThat(failResult.aiUsed()).isFalse();
    assertThat(failResult.aiWarning()).isNotBlank();
    assertThat(failResult.transactions().get(0).category()).isEqualTo(ExpenseCategory.OTHER_EXPENSE);
    assertThat(failResult.transactions().get(0).source()).isEqualTo(ClassificationSource.FALLBACK);
    assertThat(failResult.transactions().get(0).needsReview()).isTrue();
  }

  @Test
  void expenseCategory_exposesAll27CategoriesWithExpectedFlowKinds() {
    assertThat(ExpenseCategory.values()).hasSize(27);
    long consumptionCount = java.util.Arrays.stream(ExpenseCategory.values())
        .filter(ExpenseCategory::isConsumption)
        .count();
    assertThat(consumptionCount).isEqualTo(23);
    assertThat(ExpenseCategory.UTILITIES.defaultFlowKind()).isEqualTo(FlowKind.EXPENSE);
    assertThat(ExpenseCategory.TELECOM.defaultFlowKind()).isEqualTo(FlowKind.EXPENSE);
    assertThat(ExpenseCategory.HOME_GARDEN.defaultFlowKind()).isEqualTo(FlowKind.EXPENSE);
    assertThat(ExpenseCategory.FUEL.defaultFlowKind()).isEqualTo(FlowKind.EXPENSE);
    assertThat(ExpenseCategory.CAR_MAINTENANCE.defaultFlowKind()).isEqualTo(FlowKind.EXPENSE);
    assertThat(ExpenseCategory.FITNESS_SPORT.defaultFlowKind()).isEqualTo(FlowKind.EXPENSE);
    assertThat(ExpenseCategory.CLOTHING.defaultFlowKind()).isEqualTo(FlowKind.EXPENSE);
    assertThat(ExpenseCategory.BEAUTY_CARE.defaultFlowKind()).isEqualTo(FlowKind.EXPENSE);
    assertThat(ExpenseCategory.ELECTRONICS.defaultFlowKind()).isEqualTo(FlowKind.EXPENSE);
    assertThat(ExpenseCategory.PETS.defaultFlowKind()).isEqualTo(FlowKind.EXPENSE);
    assertThat(ExpenseCategory.GIFTS.defaultFlowKind()).isEqualTo(FlowKind.EXPENSE);
  }

  @Test
  void normalizeAndClassify_mapsAllGranularBrandCategories() {
    ExpenseClassifier classifier = new ExpenseClassifier(normalizer, scrubber, new StubLlmPort(false));
    LocalDate date = LocalDate.of(2026, 10, 19);

    List<ClassifiableRow> rows = List.of(
        new ClassifiableRow("u1", date, "TAURON SPRZEDAZ", new BigDecimal("-220.00"), "PLN"),
        new ClassifiableRow("u2", date, "PGNiG OBROT", new BigDecimal("-180.00"), "PLN"),
        new ClassifiableRow("t1", date, "ORANGE POLSKA", new BigDecimal("-69.00"), "PLN"),
        new ClassifiableRow("t2", date, "VECTRA S.A.", new BigDecimal("-89.00"), "PLN"),
        new ClassifiableRow("hg1", date, "IKEA RETAIL", new BigDecimal("-340.00"), "PLN"),
        new ClassifiableRow("hg2", date, "PEPCO POLAND", new BigDecimal("-45.00"), "PLN"),
        new ClassifiableRow("f1", date, "PKN ORLEN STACJA 12", new BigDecimal("-280.00"), "PLN"),
        new ClassifiableRow("f2", date, "SHELL POLSKA", new BigDecimal("-210.00"), "PLN"),
        new ClassifiableRow("tr1", date, "UBER TRIP WARSZAWA", new BigDecimal("-35.00"), "PLN"),
        new ClassifiableRow("tr2", date, "PKP INTERCITY", new BigDecimal("-120.00"), "PLN"),
        new ClassifiableRow("cm1", date, "E-TOLL PRZEJAZD", new BigDecimal("-42.00"), "PLN"),
        new ClassifiableRow("bc1", date, "ROSSMANN SDP", new BigDecimal("-65.00"), "PLN"),
        new ClassifiableRow("bc2", date, "HEBE DROGERIA", new BigDecimal("-55.00"), "PLN"),
        new ClassifiableRow("el1", date, "MEDIA EXPERT", new BigDecimal("-1299.00"), "PLN"),
        new ClassifiableRow("el2", date, "X-KOM SP Z OO", new BigDecimal("-499.00"), "PLN"),
        new ClassifiableRow("fs1", date, "BENEFIT SYSTEMS MULTISPORT", new BigDecimal("-110.00"), "PLN"),
        new ClassifiableRow("fs2", date, "DECATHLON WARSZAWA", new BigDecimal("-230.00"), "PLN"),
        new ClassifiableRow("cl1", date, "ZALANDO SE", new BigDecimal("-320.00"), "PLN"),
        new ClassifiableRow("cl2", date, "RESERVED LPP", new BigDecimal("-190.00"), "PLN"),
        new ClassifiableRow("sh1", date, "ALLEGRO PL", new BigDecimal("-150.00"), "PLN"),
        new ClassifiableRow("sh2", date, "EMPIK", new BigDecimal("-70.00"), "PLN"),
        new ClassifiableRow("pt1", date, "ZOOPLUS AG", new BigDecimal("-160.00"), "PLN"),
        new ClassifiableRow("pt2", date, "MAXI ZOO POLSKA", new BigDecimal("-95.00"), "PLN")
    );

    List<ClassifiedTransaction> out = classifier.classifyBatch(rows, List.of(), false);
    assertThat(out).extracting(ClassifiedTransaction::category).containsExactly(
        ExpenseCategory.UTILITIES,
        ExpenseCategory.UTILITIES,
        ExpenseCategory.TELECOM,
        ExpenseCategory.TELECOM,
        ExpenseCategory.HOME_GARDEN,
        ExpenseCategory.HOME_GARDEN,
        ExpenseCategory.FUEL,
        ExpenseCategory.FUEL,
        ExpenseCategory.TRANSPORT,
        ExpenseCategory.TRANSPORT,
        ExpenseCategory.CAR_MAINTENANCE,
        ExpenseCategory.BEAUTY_CARE,
        ExpenseCategory.BEAUTY_CARE,
        ExpenseCategory.ELECTRONICS,
        ExpenseCategory.ELECTRONICS,
        ExpenseCategory.FITNESS_SPORT,
        ExpenseCategory.FITNESS_SPORT,
        ExpenseCategory.CLOTHING,
        ExpenseCategory.CLOTHING,
        ExpenseCategory.SHOPPING,
        ExpenseCategory.SHOPPING,
        ExpenseCategory.PETS,
        ExpenseCategory.PETS
    );
  }

  @Test
  void classifyBatch_alignsWholePhraseFallbackRulesAndPreservesUserRulePrecedence() {
    ExpenseClassifier classifier = new ExpenseClassifier(normalizer, scrubber, new StubLlmPort(false));
    LocalDate date = LocalDate.of(2026, 10, 20);

    List<MerchantRuleSnapshot> rules = List.of(
        new MerchantRuleSnapshot(RuleMatchType.MERCHANT, "orlen", ExpenseCategory.GROCERIES, FlowKind.EXPENSE)
    );

    List<ClassifiedTransaction> out = classifier.classifyBatch(
        List.of(
            new ClassifiableRow("1", date, "OPLATA ENERGIA ELEKTRYCZNA", new BigDecimal("-200.00"), "PLN"),
            new ClassifiableRow("2", date, "ABONAMENT SWIATLOWOD", new BigDecimal("-80.00"), "PLN"),
            new ClassifiableRow("3", date, "WARSZTAT SAMOCHODOWY KOWALSKI", new BigDecimal("-450.00"), "PLN"),
            new ClassifiableRow("4", date, "SILOWNIA MIEJSKA", new BigDecimal("-120.00"), "PLN"),
            new ClassifiableRow("5", date, "DROGERIA CENTRUM", new BigDecimal("-60.00"), "PLN"),
            new ClassifiableRow("6", date, "SKLEP OBUWIE MIEJSKIE", new BigDecimal("-220.00"), "PLN"),
            new ClassifiableRow("7", date, "ELEKTRONIKA SERWIS", new BigDecimal("-310.00"), "PLN"),
            new ClassifiableRow("8", date, "JUBILER KRAKOW", new BigDecimal("-500.00"), "PLN"),
            new ClassifiableRow("9", date, "CZYNSZ MIESZKANIOWY", new BigDecimal("-2100.00"), "PLN"),
            new ClassifiableRow("10", date, "PKN ORLEN STACJA 99", new BigDecimal("-30.00"), "PLN")
        ),
        rules,
        false);

    assertThat(out.get(0).category()).isEqualTo(ExpenseCategory.UTILITIES);
    assertThat(out.get(1).category()).isEqualTo(ExpenseCategory.TELECOM);
    assertThat(out.get(2).category()).isEqualTo(ExpenseCategory.CAR_MAINTENANCE);
    assertThat(out.get(3).category()).isEqualTo(ExpenseCategory.FITNESS_SPORT);
    assertThat(out.get(4).category()).isEqualTo(ExpenseCategory.BEAUTY_CARE);
    assertThat(out.get(5).category()).isEqualTo(ExpenseCategory.CLOTHING);
    assertThat(out.get(6).category()).isEqualTo(ExpenseCategory.ELECTRONICS);
    assertThat(out.get(7).category()).isEqualTo(ExpenseCategory.GIFTS);
    assertThat(out.get(8).category()).isEqualTo(ExpenseCategory.HOUSING);
    assertThat(out.get(9).category()).isEqualTo(ExpenseCategory.GROCERIES);
    assertThat(out.get(9).source()).isEqualTo(ClassificationSource.USER_RULE);
  }

  @Test
  void matchCuratedDictionary_prefersLongestWholePhraseOverEarlierShorterMatches() {
    ExpenseClassifier classifier = new ExpenseClassifier(normalizer, scrubber, new StubLlmPort(false));
    LocalDate date = LocalDate.of(2026, 10, 21);

    List<ClassifiedTransaction> out = classifier.classifyBatch(
        List.of(
            new ClassifiableRow("1", date, "KLINIKA WETERYNARYJNA AZOR", new BigDecimal("-180.00"), "PLN"),
            new ClassifiableRow("2", date, "PRZYCHODNIA WETERYNARYJNA PIESEK", new BigDecimal("-140.00"), "PLN"),
            new ClassifiableRow("3", date, "SERWIS GSM KOWALSKI", new BigDecimal("-250.00"), "PLN"),
            new ClassifiableRow("4", date, "SUPERMARKET PRZEMYSLOWY", new BigDecimal("-95.00"), "PLN"),
            new ClassifiableRow("5", date, "KLINIKA MEDYCZNA CENTRUM", new BigDecimal("-300.00"), "PLN"),
            new ClassifiableRow("6", date, "SALON GSM CENTRUM", new BigDecimal("-70.00"), "PLN"),
            new ClassifiableRow("7", date, "SUPERMARKET OSIEDLOWY", new BigDecimal("-65.00"), "PLN")
        ),
        List.of(),
        false);

    assertThat(out.get(0).category()).isEqualTo(ExpenseCategory.PETS);
    assertThat(out.get(0).source()).isEqualTo(ClassificationSource.RULE_ENGINE);
    assertThat(out.get(1).category()).isEqualTo(ExpenseCategory.PETS);
    assertThat(out.get(1).source()).isEqualTo(ClassificationSource.RULE_ENGINE);
    assertThat(out.get(2).category()).isEqualTo(ExpenseCategory.ELECTRONICS);
    assertThat(out.get(2).source()).isEqualTo(ClassificationSource.RULE_ENGINE);
    assertThat(out.get(3).category()).isEqualTo(ExpenseCategory.SHOPPING);
    assertThat(out.get(3).source()).isEqualTo(ClassificationSource.RULE_ENGINE);

    assertThat(out.get(4).category()).isEqualTo(ExpenseCategory.HEALTH);
    assertThat(out.get(5).category()).isEqualTo(ExpenseCategory.TELECOM);
    assertThat(out.get(6).category()).isEqualTo(ExpenseCategory.GROCERIES);
  }

  @Test
  void classifyBatch_deduplicatesIdenticalUnknownItemsBeforeCallingLlm() {
    LocalDate date = LocalDate.of(2026, 10, 10);
    StubLlmPort llm = new StubLlmPort(true);
    llm.suggestions = Map.of(
        "d1", new LlmCategorySuggestion(ExpenseCategory.SUBSCRIPTIONS, FlowKind.EXPENSE, "Apple", new BigDecimal("0.92")));
    ExpenseClassifier classifier = new ExpenseClassifier(normalizer, scrubber, llm);

    List<ClassifiedTransaction> result = classifier.classifyBatch(
        List.of(
            new ClassifiableRow("d1", date, "APPLE.COM/BILL", new BigDecimal("-19.99"), "PLN"),
            new ClassifiableRow("d2", date.plusDays(1), "APPLE.COM/BILL", new BigDecimal("-29.99"), "PLN"),
            new ClassifiableRow("d3", date.plusDays(2), "APPLE.COM/BILL", new BigDecimal("19.99"), "PLN")),
        List.of(),
        true);

    assertThat(result).hasSize(3);
    assertThat(llm.capturedInputs)
        .extracting(LlmClassifyInput::id)
        .containsExactly("d1", "d3");
    assertThat(llm.capturedInputs.get(0).statementOccurrences()).isEqualTo(3);
    assertThat(result.get(0).category()).isEqualTo(ExpenseCategory.SUBSCRIPTIONS);
    assertThat(result.get(1).category()).isEqualTo(ExpenseCategory.SUBSCRIPTIONS);
    assertThat(result.get(2).category()).isEqualTo(ExpenseCategory.INCOME);
  }

  @Test
  void piiScrubber_scrubsPolishNipAndShortIbanTokens() {
    String raw = "FAKTURA NIP 123-456-32-18 RACHUNEK PL1234567890, BIEDRONKA";
    String scrubbed = scrubber.scrub(raw);
    assertThat(scrubbed)
        .contains("BIEDRONKA")
        .doesNotContain("123-456-32-18")
        .doesNotContain("PL1234567890");
  }

  private static final class StubLlmPort implements ExpenseLlmPort {
    private final boolean enabled;
    private Map<String, LlmCategorySuggestion> suggestions = Map.of();
    private boolean throwOnClassify = false;
    private final List<LlmClassifyInput> capturedInputs = new ArrayList<>();
    private final List<MerchantRuleSnapshot> capturedLearnedExamples = new ArrayList<>();
    private LlmClassificationContext capturedContext;

    private StubLlmPort(boolean enabled) {
      this.enabled = enabled;
    }

    @Override
    public boolean isEnabled() {
      return enabled;
    }

    @Override
    public String providerName() {
      return enabled ? "ollama" : "none";
    }

    @Override
    public String modelName() {
      return enabled ? "tev1:4b" : "";
    }

    @Override
    public Map<String, LlmCategorySuggestion> classifyUnknowns(List<LlmClassifyInput> items) {
      capturedInputs.addAll(items);
      if (throwOnClassify) {
        throw new IllegalStateException("Ollama unavailable");
      }
      return suggestions;
    }

    @Override
    public Map<String, LlmCategorySuggestion> classifyUnknowns(
        List<LlmClassifyInput> items, List<MerchantRuleSnapshot> learnedExamples) {
      if (learnedExamples != null) {
        capturedLearnedExamples.addAll(learnedExamples);
      }
      return classifyUnknowns(items);
    }

    @Override
    public Map<String, LlmCategorySuggestion> classifyUnknowns(
        List<LlmClassifyInput> items, LlmClassificationContext context) {
      capturedContext = context;
      if (context != null && context.learnedExamples() != null) {
        capturedLearnedExamples.addAll(context.learnedExamples());
      }
      return classifyUnknowns(items);
    }
  }
}
