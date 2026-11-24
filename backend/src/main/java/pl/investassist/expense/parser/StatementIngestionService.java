package pl.investassist.expense.parser;

import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import pl.investassist.expense.enums.BankProfile;
import pl.investassist.expense.enums.StatementFormat;

@Service
public class StatementIngestionService {

  public static final long MAX_FILE_SIZE_BYTES = 10L * 1024L * 1024L;

  private final PdfTextExtractor pdfTextExtractor;
  private final BankStatementParser parser;
  private final BalanceReconciler reconciler;
  private final TransactionFingerprinter fingerprinter;

  public StatementIngestionService() {
    this(
        new PdfTextExtractor(),
        new BankStatementParser(),
        new BalanceReconciler(),
        new TransactionFingerprinter());
  }

  @Autowired
  public StatementIngestionService(
      PdfTextExtractor pdfTextExtractor,
      BankStatementParser parser,
      BalanceReconciler reconciler,
      TransactionFingerprinter fingerprinter) {
    this.pdfTextExtractor = pdfTextExtractor;
    this.parser = parser;
    this.reconciler = reconciler;
    this.fingerprinter = fingerprinter;
  }

  public IngestedStatement ingest(String fileName, byte[] content, BankProfile profileHint) {
    if (content == null || content.length == 0) {
      throw new IllegalArgumentException("error.expense_empty_file");
    }
    if (content.length > MAX_FILE_SIZE_BYTES) {
      throw new IllegalArgumentException("error.expense_file_too_large");
    }

    StatementFormat format = resolveFormat(fileName);
    String fileHash = fingerprinter.sha256Hex(content);
    BankProfile effectiveHint = profileHint != null ? profileHint : BankProfile.AUTO;

    ParsedStatement parsed;
    if (format == StatementFormat.PDF) {
      String extractedText = pdfTextExtractor.extractText(content);
      if (extractedText.isBlank()) {
        throw new ResponseStatusException(
            HttpStatus.UNPROCESSABLE_ENTITY, "error.expense_scanned_pdf");
      }
      parsed = parser.parseText(extractedText, effectiveHint);
    } else {
      parsed = parser.parseCsv(content, effectiveHint);
    }

    List<BigDecimal> signedAmounts = parsed.rows().stream()
        .map(ParsedTransactionRow::amount)
        .toList();
    ReconciliationResult reconciliation = reconciler.reconcile(
        parsed.openingBalance(), parsed.closingBalance(), signedAmounts);

    return new IngestedStatement(
        fileName != null ? fileName.trim() : "statement",
        fileHash,
        format,
        parsed.detectedBankProfile(),
        parsed.currency(),
        parsed.periodStart(),
        parsed.periodEnd(),
        reconciliation,
        parsed.rows(),
        parsed.warnings());
  }

  private static StatementFormat resolveFormat(String fileName) {
    if (fileName == null || fileName.isBlank()) {
      throw new IllegalArgumentException("error.expense_unsupported_format");
    }
    String lower = fileName.trim().toLowerCase(Locale.ROOT);
    if (lower.endsWith(".pdf")) {
      return StatementFormat.PDF;
    }
    if (lower.endsWith(".csv")) {
      return StatementFormat.CSV;
    }
    throw new IllegalArgumentException("error.expense_unsupported_format");
  }
}
