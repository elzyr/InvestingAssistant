package pl.investassist.expense.parser;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;
import pl.investassist.common.enums.Currency;
import pl.investassist.expense.ExpenseSupport;

@Component
public class TransactionFingerprinter {

  public List<String> computeFingerprints(List<ParsedTransactionRow> rows) {
    return computeFingerprints(rows, Set.of());
  }

  public List<String> computeFingerprints(List<ParsedTransactionRow> rows, Set<String> reserved) {
    if (rows == null || rows.isEmpty()) {
      return List.of();
    }
    List<String> result = new ArrayList<>(rows.size());
    Map<String, Integer> occurrenceCounter = new HashMap<>();

    for (ParsedTransactionRow row : rows) {
      String datePart = row.txDate() != null ? row.txDate().toString() : "";
      BigDecimal amount = ExpenseSupport.scale2(row.amount());
      String currencyPart = Currency.normalizeCode(row.currency(), "PLN");
      String descPart = normalizeDescription(row.rawDescription());
      String baseKey = datePart + "|" + amount.toPlainString() + "|" + currencyPart + "|" + descPart;
      int occurrenceIndex = occurrenceCounter.getOrDefault(baseKey, 0);
      String fingerprint = fingerprint(baseKey, occurrenceIndex);
      while (reserved.contains(fingerprint)) {
        fingerprint = fingerprint(baseKey, ++occurrenceIndex);
      }
      occurrenceCounter.put(baseKey, occurrenceIndex + 1);
      result.add(fingerprint);
    }
    return result;
  }

  public String sha256Hex(byte[] bytes) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      byte[] hash = digest.digest(bytes != null ? bytes : new byte[0]);
      return HexFormat.of().formatHex(hash);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 unavailable", e);
    }
  }

  private String fingerprint(String baseKey, int occurrenceIndex) {
    return sha256Hex((baseKey + "|" + occurrenceIndex).getBytes(StandardCharsets.UTF_8));
  }

  private static String normalizeDescription(String raw) {
    if (raw == null) {
      return "";
    }
    return raw.trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
  }
}
