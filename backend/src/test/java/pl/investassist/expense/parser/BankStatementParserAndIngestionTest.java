package pl.investassist.expense.parser;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import pl.investassist.expense.enums.BankProfile;
import pl.investassist.expense.enums.ReconciliationStatus;
import pl.investassist.expense.enums.StatementFormat;

class BankStatementParserAndIngestionTest {

  private final BankStatementParser parser = new BankStatementParser();
  private final BalanceReconciler reconciler = new BalanceReconciler();
  private final TransactionFingerprinter fingerprinter = new TransactionFingerprinter();
  private final StatementIngestionService ingestionService = new StatementIngestionService();

  @Test
  void ingest_parsesMbankWindows1250CsvAndReconcilesExactBalances() {
    String csv = String.join("\r\n",
        "mBank S.A. Wyciąg z rachunku",
        "Waluta rachunku:;PLN",
        "Okres:;01.10.2026 - 31.10.2026",
        "Saldo początkowe:;1 000,00",
        "Saldo końcowe:;5 876,55",
        "",
        "#Data operacji;#Data księgowania;#Opis operacji;#Kwota;#Saldo po operacji",
        "2026-10-02;2026-10-02;PRZELEW PRZYCHODZACY WYNAGRODZENIE;5 000,00;6 000,00",
        "2026-10-05;2026-10-05;Płatność kartą Żabka 1234;-123,45;5 876,55"
    );
    byte[] bytes = csv.getBytes(Charset.forName("windows-1250"));

    IngestedStatement result = ingestionService.ingest("mbank_oct.csv", bytes, BankProfile.AUTO);

    assertThat(result.format()).isEqualTo(StatementFormat.CSV);
    assertThat(result.detectedBankProfile()).isEqualTo(BankProfile.MBANK);
    assertThat(result.currency()).isEqualTo("PLN");
    assertThat(result.rows()).hasSize(2);
    assertThat(result.rows().get(0).amount()).isEqualByComparingTo("5000.00");
    assertThat(result.rows().get(1).amount()).isEqualByComparingTo("-123.45");
    assertThat(result.rows().get(1).rawDescription()).contains("Żabka").contains("Płatność kartą");
    assertThat(result.reconciliation().status()).isEqualTo(ReconciliationStatus.VERIFIED);
    assertThat(result.reconciliation().calculatedInflows()).isEqualByComparingTo("5000.00");
    assertThat(result.reconciliation().calculatedOutflows()).isEqualByComparingTo("123.45");
    assertThat(result.reconciliation().discrepancy()).isEqualByComparingTo("0.00");
  }

  @Test
  void parseCsv_detectsRevolutSkipsRevertedAndNetsFeeIntoOutflow() {
    String csv = String.join("\n",
        "Type,Product,Started Date,Completed Date,Description,Amount,Fee,Currency,State,Balance",
        "CARD_PAYMENT,Current,2026-10-01 10:00:00,2026-10-01 12:00:00,Coffee Shop,-10.00,0.50,EUR,COMPLETED,89.50",
        "CARD_PAYMENT,Current,2026-10-02 09:00:00,2026-10-02 09:00:00,Reversed Hold,-50.00,0.00,EUR,REVERTED,89.50",
        "TOPUP,Current,2026-10-03 14:00:00,2026-10-03 14:00:00,Top-Up by Card,100.00,0.00,EUR,COMPLETED,189.50"
    );

    IngestedStatement result = ingestionService.ingest(
        "revolut.csv", csv.getBytes(StandardCharsets.UTF_8), BankProfile.AUTO);

    assertThat(result.detectedBankProfile()).isEqualTo(BankProfile.REVOLUT);
    assertThat(result.currency()).isEqualTo("EUR");
    assertThat(result.rows()).hasSize(2);
    assertThat(result.rows().get(0).amount()).isEqualByComparingTo("-10.50");
    assertThat(result.rows().get(1).amount()).isEqualByComparingTo("100.00");
    assertThat(result.reconciliation().status()).isEqualTo(ReconciliationStatus.VERIFIED);
    assertThat(result.reconciliation().discrepancy()).isEqualByComparingTo("0.00");
  }

  @Test
  void parseCsv_detectsWiseAndGenericWithEnglishNumberFormats() {
    String wiseCsv = String.join("\n",
        "TransferWise ID,Date,Amount,Currency,Description,Payment Reference,Running Balance,Total fees",
        "T1,02-10-2026,2500.00,USD,Salary Deposit,PAY,3500.00,0.00",
        "T2,05-10-2026,-1234.56,USD,Card Purchase Laptop,INV1,2265.44,0.00"
    );
    ParsedStatement wise = parser.parseCsv(wiseCsv.getBytes(StandardCharsets.UTF_8), BankProfile.AUTO);

    assertThat(wise.detectedBankProfile()).isEqualTo(BankProfile.WISE);
    assertThat(wise.rows()).hasSize(2);
    assertThat(wise.rows().get(0).txDate()).isEqualTo(LocalDate.of(2026, 10, 2));
    assertThat(wise.rows().get(1).amount()).isEqualByComparingTo("-1234.56");

    String genericCsv = String.join("\n",
        "Date,Description,Amount,Currency",
        "2026-10-01,Grocery Market,\"-1,234.56\",USD",
        "2026-10-04,Refund Store,$45.00,USD"
    );
    ParsedStatement generic = parser.parseCsv(genericCsv.getBytes(StandardCharsets.UTF_8), BankProfile.AUTO);

    assertThat(generic.detectedBankProfile()).isEqualTo(BankProfile.GENERIC);
    assertThat(generic.rows()).hasSize(2);
    assertThat(generic.rows().get(0).amount()).isEqualByComparingTo("-1234.56");
    assertThat(generic.rows().get(1).amount()).isEqualByComparingTo("45.00");
  }

  @Test
  void ingest_extractsValidPdf14AndReconcilesVerifiedBalance() {
    byte[] pdfBytes = buildMinimalPdf14(List.of(
        "Wyciag z rachunku 01.10.2026 - 31.10.2026 PLN",
        "Saldo poczatkowe: 1 000,00 PLN",
        "02.10.2026 PRZELEW PRZYCHODZACY +500,00 PLN",
        "05.10.2026 PLATNOSC KARTA BIEDRONKA -200,50 PLN",
        "Saldo koncowe: 1 299,50 PLN"
    ));

    IngestedStatement result = ingestionService.ingest("statement.pdf", pdfBytes, BankProfile.AUTO);

    assertThat(result.format()).isEqualTo(StatementFormat.PDF);
    assertThat(result.fileHashSha256()).hasSize(64);
    assertThat(result.rows()).hasSize(2);
    assertThat(result.reconciliation().status()).isEqualTo(ReconciliationStatus.VERIFIED);
    assertThat(result.reconciliation().discrepancy()).isEqualByComparingTo("0.00");
  }

  @Test
  void ingest_throws422ForScannedImageOnlyPdfWithoutTextOperators() {
    byte[] scannedPdf = buildMinimalPdf14(List.of());

    assertThatThrownBy(() -> ingestionService.ingest("scanned.pdf", scannedPdf, BankProfile.AUTO))
        .isInstanceOf(ResponseStatusException.class)
        .satisfies(ex -> {
          ResponseStatusException rse = (ResponseStatusException) ex;
          assertThat(rse.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
          assertThat(rse.getReason()).isEqualTo("error.expense_scanned_pdf");
        });
  }

  @Test
  void ingest_rejectsInvalidEmptyOversizedAndSpoofedFilesWithIllegalArgument() {
    assertThatThrownBy(() -> ingestionService.ingest("empty.csv", new byte[0], BankProfile.AUTO))
        .isInstanceOf(IllegalArgumentException.class);

    assertThatThrownBy(() -> ingestionService.ingest("null.csv", null, BankProfile.AUTO))
        .isInstanceOf(IllegalArgumentException.class);

    byte[] oversized = new byte[(10 * 1024 * 1024) + 1];
    assertThatThrownBy(() -> ingestionService.ingest("huge.csv", oversized, BankProfile.AUTO))
        .isInstanceOf(IllegalArgumentException.class);

    assertThatThrownBy(() -> ingestionService.ingest(
        "unsupported.xlsx", "abc".getBytes(StandardCharsets.UTF_8), BankProfile.AUTO))
        .isInstanceOf(IllegalArgumentException.class);

    assertThatThrownBy(() -> ingestionService.ingest(
        "fake.pdf", "NOT_A_PDF_HEADER".getBytes(StandardCharsets.UTF_8), BankProfile.AUTO))
        .isInstanceOf(IllegalArgumentException.class);

    assertThatThrownBy(() -> ingestionService.ingest(
        "corrupt.pdf", "%PDF-1.4\ncorrupt-body".getBytes(StandardCharsets.US_ASCII), BankProfile.AUTO))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void reconcile_handlesVerifiedWithinToleranceMismatchAndUnavailableBalances() {
    ReconciliationResult mismatch = reconciler.reconcile(
        new BigDecimal("1000.00"),
        new BigDecimal("800.00"),
        List.of(new BigDecimal("-150.00")));
    assertThat(mismatch.status()).isEqualTo(ReconciliationStatus.MISMATCH);
    assertThat(mismatch.discrepancy()).isEqualByComparingTo("-50.00");
    assertThat(mismatch.calculatedInflows()).isEqualByComparingTo("0.00");
    assertThat(mismatch.calculatedOutflows()).isEqualByComparingTo("150.00");

    ReconciliationResult withinTolerance = reconciler.reconcile(
        new BigDecimal("1000.00"),
        new BigDecimal("800.00"),
        List.of(new BigDecimal("-150.00"), new BigDecimal("-49.99")));
    assertThat(withinTolerance.status()).isEqualTo(ReconciliationStatus.VERIFIED);

    ReconciliationResult outsideTolerance = reconciler.reconcile(
        new BigDecimal("1000.00"),
        new BigDecimal("800.00"),
        List.of(new BigDecimal("-150.00"), new BigDecimal("-49.98")));
    assertThat(outsideTolerance.status()).isEqualTo(ReconciliationStatus.MISMATCH);
    assertThat(outsideTolerance.discrepancy()).isEqualByComparingTo("-0.02");

    ReconciliationResult missingOpening = reconciler.reconcile(
        null, new BigDecimal("800.00"), List.of(new BigDecimal("-150.00")));
    assertThat(missingOpening.status()).isEqualTo(ReconciliationStatus.UNAVAILABLE);
    assertThat(missingOpening.discrepancy()).isNull();

    ReconciliationResult missingClosing = reconciler.reconcile(
        new BigDecimal("1000.00"), null, List.of(new BigDecimal("-150.00")));
    assertThat(missingClosing.status()).isEqualTo(ReconciliationStatus.UNAVAILABLE);
    assertThat(missingClosing.discrepancy()).isNull();
  }

  @Test
  void fingerprinter_distinguishesSameDayIdenticalRowsAndMatchesOverlappingReimport() {
    LocalDate date = LocalDate.of(2026, 10, 5);
    List<ParsedTransactionRow> firstUpload = List.of(
        new ParsedTransactionRow(date, date, "COSTA COFFEE", new BigDecimal("-18.50"), "PLN"),
        new ParsedTransactionRow(date, date, "COSTA COFFEE", new BigDecimal("-18.50"), "PLN")
    );

    List<String> fps1 = fingerprinter.computeFingerprints(firstUpload);
    assertThat(fps1).hasSize(2);
    assertThat(fps1.get(0)).hasSize(64);
    assertThat(fps1.get(1)).hasSize(64);
    assertThat(fps1.get(0)).isNotEqualTo(fps1.get(1));

    List<ParsedTransactionRow> overlappingUpload = List.of(
        new ParsedTransactionRow(date, date, "COSTA   COFFEE", new BigDecimal("-18.5"), "PLN"),
        new ParsedTransactionRow(date, date, "COSTA COFFEE", new BigDecimal("-18.50"), "PLN"),
        new ParsedTransactionRow(date.plusDays(1), date.plusDays(1), "BIEDRONKA", new BigDecimal("-50.00"), "PLN")
    );
    List<String> fps2 = fingerprinter.computeFingerprints(overlappingUpload);
    assertThat(fps2.get(0)).isEqualTo(fps1.get(0));
    assertThat(fps2.get(1)).isEqualTo(fps1.get(1));

    String hash = fingerprinter.sha256Hex("abc".getBytes(StandardCharsets.UTF_8));
    assertThat(hash).isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
  }

  @Test
  void parseTextAndIngest_parseEnglishCommaThousandsPdfAmountsAndReconcile() {
    String pdfText = String.join("\n",
        "Statement 01.10.2026 - 31.10.2026 USD",
        "Opening balance: 1,000.00 USD",
        "2026-10-02 Salary Deposit +2,500.00 USD",
        "2026-10-05 Laptop Purchase -1,234.56 USD",
        "Closing balance: 2,265.44 USD"
    );
    ParsedStatement parsed = parser.parseText(pdfText, BankProfile.AUTO);
    assertThat(parsed.currency()).isEqualTo("USD");
    assertThat(parsed.openingBalance()).isEqualByComparingTo("1000.00");
    assertThat(parsed.closingBalance()).isEqualByComparingTo("2265.44");
    assertThat(parsed.rows()).hasSize(2);
    assertThat(parsed.rows().get(0).amount()).isEqualByComparingTo("2500.00");
    assertThat(parsed.rows().get(1).amount()).isEqualByComparingTo("-1234.56");

    byte[] pdfBytes = buildMinimalPdf14(List.of(
        "Statement 01.10.2026 - 31.10.2026 USD",
        "Opening balance: 5,000.00 USD",
        "05.10.2026 Apple Store -1,234.56 USD",
        "Closing balance: 3,765.44 USD"
    ));
    IngestedStatement ingested = ingestionService.ingest("revolut_usd.pdf", pdfBytes, BankProfile.AUTO);
    assertThat(ingested.rows()).hasSize(1);
    assertThat(ingested.rows().get(0).amount()).isEqualByComparingTo("-1234.56");
    assertThat(ingested.rows().get(0).currency()).isEqualTo("USD");
    assertThat(ingested.reconciliation().status()).isEqualTo(ReconciliationStatus.VERIFIED);
    assertThat(ingested.reconciliation().discrepancy()).isEqualByComparingTo("0.00");
  }

  @Test
  void parseCsv_prioritizesBankHeadersOverRevolutOrWiseMentionsInTransactions() {
    String pkoCsv = String.join("\n",
        "Data operacji;Data waluty;Typ transakcji;Kwota;Waluta;Saldo po transakcji;Opis transakcji",
        "2026-10-02;2026-10-02;Płatność kartą;-100,00;PLN;900,00;DOŁADOWANIE REVOLUT",
        "2026-10-03;2026-10-03;Przelew;-250,00;PLN;650,00;WISE PAYMENTS LIMITED"
    );
    ParsedStatement pko = parser.parseCsv(pkoCsv.getBytes(StandardCharsets.UTF_8), BankProfile.AUTO);
    assertThat(pko.detectedBankProfile()).isEqualTo(BankProfile.PKO_BP);
    assertThat(pko.rows()).hasSize(2);
    assertThat(pko.rows().get(0).amount()).isEqualByComparingTo("-100.00");
    assertThat(pko.rows().get(1).amount()).isEqualByComparingTo("-250.00");

    String genericWithRevolut = String.join("\n",
        "Date;Description;Amount;Currency",
        "2026-10-02;DOŁADOWANIE REVOLUT;-150,00;PLN",
        "2026-10-04;BIEDRONKA;-50,00;PLN"
    );
    ParsedStatement generic = parser.parseCsv(
        genericWithRevolut.getBytes(StandardCharsets.UTF_8), BankProfile.AUTO);
    assertThat(generic.rows()).hasSize(2);
    assertThat(generic.rows().get(0).amount()).isEqualByComparingTo("-150.00");
    assertThat(generic.rows().get(1).amount()).isEqualByComparingTo("-50.00");
  }

  @Test
  void parseCsv_autoDetectsIso88592PolishDiacriticsWithoutMojibake() {
    String csv = String.join("\r\n",
        "Data operacji;Opis operacji;Kwota;Waluta",
        "2026-10-05;Płatność kartą Żabka Śniadanie ąśźĄŚŹ;-24,50;PLN"
    );
    byte[] isoBytes = csv.getBytes(Charset.forName("ISO-8859-2"));

    ParsedStatement parsed = parser.parseCsv(isoBytes, BankProfile.AUTO);
    assertThat(parsed.rows()).hasSize(1);
    assertThat(parsed.rows().get(0).rawDescription())
        .isEqualTo("Płatność kartą Żabka Śniadanie ąśźĄŚŹ")
        .doesNotContain("±")
        .doesNotContain("¶")
        .doesNotContain("¦");
  }

  @Test
  void parseText_extractsSideBySideBalancesAndIgnoresTrailingDateOnBalanceLine() {
    String sideBySide = String.join("\n",
        "Saldo poczatkowe: 1 000,00 PLN   Saldo koncowe: 1 299,50 PLN (na dzien 31.10.2026)",
        "02.10.2026 PRZELEW +500,00 PLN",
        "05.10.2026 BIEDRONKA -200,50 PLN"
    );
    ParsedStatement parsed = parser.parseText(sideBySide, BankProfile.AUTO);
    assertThat(parsed.openingBalance()).isEqualByComparingTo("1000.00");
    assertThat(parsed.closingBalance()).isEqualByComparingTo("1299.50");
    assertThat(parsed.rows()).hasSize(2);

    ReconciliationResult rec = reconciler.reconcile(
        parsed.openingBalance(),
        parsed.closingBalance(),
        parsed.rows().stream().map(ParsedTransactionRow::amount).toList());
    assertThat(rec.status()).isEqualTo(ReconciliationStatus.VERIFIED);
    assertThat(rec.discrepancy()).isEqualByComparingTo("0.00");
  }

  @Test
  void parseCsvAndText_handleReverseChronologicalBalancesDebitCreditColumnsAndMultilineDualDatePdf() {
    String reverseCsv = String.join("\n",
        "Data operacji;Opis operacji;Obciążenia;Uznania;Saldo po operacji;Waluta",
        "2026-10-05;BIEDRONKA;200,00;;800,00;PLN",
        "2026-10-02;WYNAGRODZENIE;;500,00;1 000,00;PLN"
    );
    ParsedStatement reverseParsed = parser.parseCsv(reverseCsv.getBytes(StandardCharsets.UTF_8), BankProfile.AUTO);
    assertThat(reverseParsed.rows()).hasSize(2);
    assertThat(reverseParsed.rows().get(0).amount()).isEqualByComparingTo("-200.00");
    assertThat(reverseParsed.rows().get(1).amount()).isEqualByComparingTo("500.00");
    assertThat(reverseParsed.openingBalance()).isEqualByComparingTo("500.00");
    assertThat(reverseParsed.closingBalance()).isEqualByComparingTo("800.00");

    ReconciliationResult reverseRec = reconciler.reconcile(
        reverseParsed.openingBalance(),
        reverseParsed.closingBalance(),
        reverseParsed.rows().stream().map(ParsedTransactionRow::amount).toList());
    assertThat(reverseRec.status()).isEqualTo(ReconciliationStatus.VERIFIED);

    String multilinePdf = String.join("\n",
        "02.10.2026 03.10.2026 PRZELEW WYCHODZACY",
        "CONTINUATION LINE SKLEP -200,50 799,50",
        "06.10.2026 06.10.2026 WYNAGRODZENIE +1 000,00 1 799,50"
    );
    ParsedStatement multi = parser.parseText(multilinePdf, BankProfile.AUTO);
    assertThat(multi.rows()).hasSize(2);
    assertThat(multi.rows().get(0).txDate()).isEqualTo(LocalDate.of(2026, 10, 2));
    assertThat(multi.rows().get(0).bookingDate()).isEqualTo(LocalDate.of(2026, 10, 3));
    assertThat(multi.rows().get(0).rawDescription())
        .contains("PRZELEW WYCHODZACY")
        .contains("CONTINUATION LINE SKLEP");
    assertThat(multi.rows().get(0).amount()).isEqualByComparingTo("-200.50");
    assertThat(multi.openingBalance()).isEqualByComparingTo("1000.00");
    assertThat(multi.closingBalance()).isEqualByComparingTo("1799.50");
  }

  @Test
  void parseCsv_ignoresDateColumnsForAmountAndRejectsDateStringsAndNumericCurrencyTokens() {
    String debitCreditCsv = String.join("\n",
        "Transaction Date,Value Date,Description,Debit,Credit,Currency",
        "05.10.2026,05.10.2026,BIEDRONKA,200.00,,PLN",
        "06.10.2026,06.10.2026,WYNAGRODZENIE,,1500.00,PLN"
    );
    ParsedStatement dcParsed = parser.parseCsv(
        debitCreditCsv.getBytes(StandardCharsets.UTF_8), BankProfile.AUTO);

    assertThat(dcParsed.rows()).hasSize(2);
    assertThat(dcParsed.rows().get(0).txDate()).isEqualTo(LocalDate.of(2026, 10, 5));
    assertThat(dcParsed.rows().get(0).amount()).isEqualByComparingTo("-200.00");
    assertThat(dcParsed.rows().get(0).currency()).isEqualTo("PLN");
    assertThat(dcParsed.rows().get(1).amount()).isEqualByComparingTo("1500.00");
    assertThat(dcParsed.rows().get(1).currency()).isEqualTo("PLN");

    String amountCurrencyHeaderCsv = String.join("\n",
        "Date,Description,Amount (Currency)",
        "2026-10-05,BIEDRONKA,-100.00"
    );
    ParsedStatement acParsed = parser.parseCsv(
        amountCurrencyHeaderCsv.getBytes(StandardCharsets.UTF_8), BankProfile.AUTO);

    assertThat(acParsed.rows()).hasSize(1);
    assertThat(acParsed.rows().get(0).amount()).isEqualByComparingTo("-100.00");
    assertThat(acParsed.rows().get(0).currency()).isEqualTo("PLN");

    assertThat(BankStatementParser.parseAmount("05.10.2026")).isNull();
    assertThat(BankStatementParser.parseAmount("05,10,2026")).isNull();
  }

  @Test
  void parseTextAndIngest_handleUnicodeMinusNarrowNbspAndSpaceThousandsDotDecimal() {
    String text = String.join("\n",
        "05.10.2026 BIEDRONKA \u2212200,50 PLN",
        "06.10.2026 APTEKA \u201345,00 PLN",
        "07.10.2026 LAPTOP -1\u202F234,56 PLN",
        "08.10.2026 HOTEL -1 234.56 EUR"
    );
    ParsedStatement parsed = parser.parseText(text, BankProfile.AUTO);

    assertThat(parsed.rows()).hasSize(4);
    assertThat(parsed.rows().get(0).amount()).isEqualByComparingTo("-200.50");
    assertThat(parsed.rows().get(0).currency()).isEqualTo("PLN");
    assertThat(parsed.rows().get(1).amount()).isEqualByComparingTo("-45.00");
    assertThat(parsed.rows().get(1).currency()).isEqualTo("PLN");
    assertThat(parsed.rows().get(2).amount()).isEqualByComparingTo("-1234.56");
    assertThat(parsed.rows().get(2).currency()).isEqualTo("PLN");
    assertThat(parsed.rows().get(3).amount()).isEqualByComparingTo("-1234.56");
    assertThat(parsed.rows().get(3).currency()).isEqualTo("EUR");

    byte[] pdfBytes = buildMinimalPdf14(List.of(
        "Statement 01.10.2026 - 31.10.2026 EUR",
        "Opening balance: 2 000.00 EUR",
        "08.10.2026 HOTEL -1 234.56 EUR",
        "Closing balance: 765.44 EUR"
    ));
    IngestedStatement ingested = ingestionService.ingest("hotel.pdf", pdfBytes, BankProfile.AUTO);
    assertThat(ingested.rows()).hasSize(1);
    assertThat(ingested.rows().get(0).amount()).isEqualByComparingTo("-1234.56");
    assertThat(ingested.rows().get(0).currency()).isEqualTo("EUR");
    assertThat(ingested.reconciliation().status()).isEqualTo(ReconciliationStatus.VERIFIED);
    assertThat(ingested.reconciliation().discrepancy()).isEqualByComparingTo("0.00");
  }

  @Test
  void parseCsv_excludesDebitAndCreditAmountColumnsFromAmountLookup() {
    String englishCsv = String.join("\n",
        "Transaction Date,Description,Debit Amount,Credit Amount,Currency",
        "2026-10-05,BIEDRONKA,200.00,,PLN",
        "2026-10-06,WYNAGRODZENIE,,1500.00,PLN"
    );
    ParsedStatement english = parser.parseCsv(
        englishCsv.getBytes(StandardCharsets.UTF_8), BankProfile.AUTO);

    assertThat(english.rows()).hasSize(2);
    assertThat(english.rows().get(0).amount()).isEqualByComparingTo("-200.00");
    assertThat(english.rows().get(0).currency()).isEqualTo("PLN");
    assertThat(english.rows().get(1).amount()).isEqualByComparingTo("1500.00");
    assertThat(english.rows().get(1).currency()).isEqualTo("PLN");

    String polishCsv = String.join("\n",
        "Data operacji;Opis;Kwota obciążenia;Kwota uznania;Waluta",
        "2026-10-05;BIEDRONKA;200,00;;PLN",
        "2026-10-06;WYNAGRODZENIE;;3 000,00;PLN"
    );
    ParsedStatement polish = parser.parseCsv(
        polishCsv.getBytes(StandardCharsets.UTF_8), BankProfile.AUTO);

    assertThat(polish.rows()).hasSize(2);
    assertThat(polish.rows().get(0).amount()).isEqualByComparingTo("-200.00");
    assertThat(polish.rows().get(0).currency()).isEqualTo("PLN");
    assertThat(polish.rows().get(1).amount()).isEqualByComparingTo("3000.00");
    assertThat(polish.rows().get(1).currency()).isEqualTo("PLN");
  }

  @Test
  void parseText_extractsPrefixCurrencySymbolsWhenTrailingIsoCodeIsAbsent() {
    String pdfText = String.join("\n",
        "02.10.2026 AMAZON -$45.00",
        "05.10.2026 BOOKING -€120.00",
        "06.10.2026 LONDON CAFE -£12.50"
    );

    ParsedStatement parsed = parser.parseText(pdfText, BankProfile.AUTO);

    assertThat(parsed.rows()).hasSize(3);
    assertThat(parsed.rows().get(0).amount()).isEqualByComparingTo("-45.00");
    assertThat(parsed.rows().get(0).currency()).isEqualTo("USD");
    assertThat(parsed.rows().get(1).amount()).isEqualByComparingTo("-120.00");
    assertThat(parsed.rows().get(1).currency()).isEqualTo("EUR");
    assertThat(parsed.rows().get(2).amount()).isEqualByComparingTo("-12.50");
    assertThat(parsed.rows().get(2).currency()).isEqualTo("GBP");
  }

  @Test
  void ingest_parsesPolishGenericCsvWithShortDataColumnHeader() {
    String csv = String.join("\n",
        "Data;Opis;Kwota;Waluta",
        "2026-10-05;BIEDRONKA;-45,00;PLN",
        "2026-10-06;ZWROT SKLEP;15,00;PLN"
    );

    IngestedStatement result = ingestionService.ingest(
        "short_data.csv", csv.getBytes(StandardCharsets.UTF_8), BankProfile.AUTO);

    assertThat(result.format()).isEqualTo(StatementFormat.CSV);
    assertThat(result.detectedBankProfile()).isEqualTo(BankProfile.GENERIC);
    assertThat(result.rows()).hasSize(2);
    assertThat(result.rows().get(0).txDate()).isEqualTo(LocalDate.of(2026, 10, 5));
    assertThat(result.rows().get(0).bookingDate()).isEqualTo(LocalDate.of(2026, 10, 5));
    assertThat(result.rows().get(0).rawDescription()).isEqualTo("BIEDRONKA");
    assertThat(result.rows().get(0).amount()).isEqualByComparingTo("-45.00");
    assertThat(result.rows().get(0).currency()).isEqualTo("PLN");
    assertThat(result.rows().get(1).txDate()).isEqualTo(LocalDate.of(2026, 10, 6));
    assertThat(result.rows().get(1).amount()).isEqualByComparingTo("15.00");
  }

  @Test
  void ingest_preservesTitleColumnAlongsideOpisOperacjiInMbankCsv() {
    String csv = String.join("\r\n",
        "mBank S.A. Wyciąg z rachunku",
        "Waluta rachunku:;PLN",
        "Saldo początkowe:;1 000,00",
        "Saldo końcowe:;710,00",
        "",
        "#Data operacji;#Data księgowania;#Opis operacji;#Tytuł;#Nadawca/Odbiorca;#Numer konta;#Kwota;#Saldo po operacji",
        "2026-10-05;2026-10-05;ZAKUP PRZY UŻYCIU KARTY;BIEDRONKA 4512 WARSZAWA;;;-45,00;955,00",
        "2026-10-05;2026-10-05;ZAKUP PRZY UŻYCIU KARTY;APTEKA GEMINI KRAKÓW;;;-45,00;910,00",
        "2026-10-06;2026-10-06;PRZELEW ZEWNĘTRZNY WYCHODZĄCY;CZYNSZ PAŹDZIERNIK;JAN KOWALSKI;123456;-200,00;710,00"
    );
    byte[] bytes = csv.getBytes(Charset.forName("windows-1250"));

    IngestedStatement result = ingestionService.ingest("mbank_title.csv", bytes, BankProfile.AUTO);

    assertThat(result.detectedBankProfile()).isEqualTo(BankProfile.MBANK);
    assertThat(result.rows()).hasSize(3);
    assertThat(result.rows().get(0).rawDescription())
        .isEqualTo("ZAKUP PRZY UŻYCIU KARTY / BIEDRONKA 4512 WARSZAWA");
    assertThat(result.rows().get(1).rawDescription())
        .isEqualTo("ZAKUP PRZY UŻYCIU KARTY / APTEKA GEMINI KRAKÓW");
    assertThat(result.rows().get(0).rawDescription())
        .isNotEqualTo(result.rows().get(1).rawDescription());
    assertThat(result.rows().get(2).rawDescription())
        .isEqualTo("JAN KOWALSKI / PRZELEW ZEWNĘTRZNY WYCHODZĄCY / CZYNSZ PAŹDZIERNIK");
    assertThat(result.rows().get(0).amount()).isEqualByComparingTo("-45.00");
    assertThat(result.rows().get(1).amount()).isEqualByComparingTo("-45.00");
    assertThat(result.rows().get(2).amount()).isEqualByComparingTo("-200.00");
    assertThat(result.reconciliation().status()).isEqualTo(ReconciliationStatus.VERIFIED);
    assertThat(result.reconciliation().discrepancy()).isEqualByComparingTo("0.00");
  }

  private static byte[] buildMinimalPdf14(List<String> textLines) {
    StringBuilder stream = new StringBuilder();
    if (textLines.isEmpty()) {
      stream.append("q 100 0 0 100 50 600 cm Q\n");
    } else {
      stream.append("BT\n/F1 10 Tf\n50 750 Td\n14 TL\n");
      for (int i = 0; i < textLines.size(); i++) {
        String escaped = textLines.get(i)
            .replace("\\", "\\\\")
            .replace("(", "\\(")
            .replace(")", "\\)");
        stream.append("(").append(escaped).append(") Tj");
        if (i < textLines.size() - 1) {
          stream.append(" T*");
        }
        stream.append("\n");
      }
      stream.append("ET\n");
    }
    byte[] streamBytes = stream.toString().getBytes(StandardCharsets.US_ASCII);

    List<String> objects = List.of(
        "1 0 obj\n<< /Type /Catalog /Pages 2 0 R >>\nendobj\n",
        "2 0 obj\n<< /Type /Pages /Kids [3 0 R] /Count 1 >>\nendobj\n",
        "3 0 obj\n<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] "
            + "/Resources << /Font << /F1 4 0 R >> >> /Contents 5 0 R >>\nendobj\n",
        "4 0 obj\n<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>\nendobj\n"
    );

    ByteArrayOutputStream out = new ByteArrayOutputStream();
    out.writeBytes("%PDF-1.4\n".getBytes(StandardCharsets.US_ASCII));
    List<Integer> offsets = new ArrayList<>();
    for (String obj : objects) {
      offsets.add(out.size());
      out.writeBytes(obj.getBytes(StandardCharsets.US_ASCII));
    }
    offsets.add(out.size());
    String obj5Header = String.format(
        Locale.ROOT, "5 0 obj\n<< /Length %d >>\nstream\n", streamBytes.length);
    out.writeBytes(obj5Header.getBytes(StandardCharsets.US_ASCII));
    out.writeBytes(streamBytes);
    out.writeBytes("endstream\nendobj\n".getBytes(StandardCharsets.US_ASCII));

    int xrefOffset = out.size();
    StringBuilder xref = new StringBuilder();
    xref.append("xref\n0 ").append(offsets.size() + 1).append("\n");
    xref.append("0000000000 65535 f \n");
    for (int offset : offsets) {
      xref.append(String.format(Locale.ROOT, "%010d 00000 n \n", offset));
    }
    xref.append("trailer\n<< /Size ").append(offsets.size() + 1).append(" /Root 1 0 R >>\n");
    xref.append("startxref\n").append(xrefOffset).append("\n%%EOF\n");
    out.writeBytes(xref.toString().getBytes(StandardCharsets.US_ASCII));
    return out.toByteArray();
  }
}
