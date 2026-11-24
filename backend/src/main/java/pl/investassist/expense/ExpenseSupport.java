package pl.investassist.expense;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.Normalizer;
import java.util.Locale;
import java.util.regex.Pattern;
import pl.investassist.expense.enums.ClassificationSource;
import pl.investassist.expense.enums.ExpenseCategory;
import pl.investassist.expense.enums.FlowKind;

public final class ExpenseSupport {

  public static final BigDecimal ZERO = new BigDecimal("0.00");
  public static final BigDecimal REVIEW_THRESHOLD = new BigDecimal("0.70");
  private static final Pattern DIACRITICS_PATTERN = Pattern.compile("\\p{M}");

  private ExpenseSupport() {}

  public static String toAsciiLower(String input) {
    if (input == null) {
      return "";
    }
    String replaced = input.replace('ł', 'l').replace('Ł', 'L');
    String decomposed = Normalizer.normalize(replaced, Normalizer.Form.NFD);
    return DIACRITICS_PATTERN.matcher(decomposed).replaceAll("").toLowerCase(Locale.ROOT);
  }

  public static boolean containsWholePhrase(String haystack, String needle) {
    if (haystack == null || needle == null || needle.isEmpty()) {
      return false;
    }
    int idx = haystack.indexOf(needle);
    while (idx >= 0) {
      boolean leftOk = idx == 0 || !Character.isLetterOrDigit(haystack.charAt(idx - 1));
      int end = idx + needle.length();
      boolean rightOk = end == haystack.length() || !Character.isLetterOrDigit(haystack.charAt(end));
      if (leftOk && rightOk) {
        return true;
      }
      idx = haystack.indexOf(needle, idx + 1);
    }
    return false;
  }

  public static BigDecimal scale2(BigDecimal value) {
    if (value == null) {
      return ZERO;
    }
    BigDecimal scaled = value.setScale(2, RoundingMode.HALF_UP);
    return scaled.compareTo(BigDecimal.ZERO) == 0 ? ZERO : scaled;
  }

  public static boolean needsReview(
      ExpenseCategory category, ClassificationSource source, BigDecimal confidence) {
    if (source == ClassificationSource.USER_OVERRIDE || source == ClassificationSource.USER_RULE) {
      return false;
    }
    return category == ExpenseCategory.OTHER_EXPENSE
        || (confidence != null && confidence.compareTo(REVIEW_THRESHOLD) < 0);
  }

  public static boolean isFlowCompatibleWithAmount(FlowKind flowKind, BigDecimal amount) {
    if (flowKind == null) {
      return false;
    }
    int sign = amount != null ? amount.signum() : 0;
    if (sign > 0 && flowKind == FlowKind.EXPENSE) {
      return false;
    }
    if (sign < 0 && (flowKind == FlowKind.INCOME || flowKind == FlowKind.REFUND)) {
      return false;
    }
    return true;
  }

  public static FlowKind resolveFlowKind(
      ExpenseCategory category, FlowKind requestedFlowKind, BigDecimal amount) {
    FlowKind resolved = requestedFlowKind;
    if (resolved == null) {
      if (amount != null && amount.signum() > 0 && category.isConsumption()) {
        resolved = FlowKind.REFUND;
      } else {
        resolved = category.defaultFlowKind();
      }
    }
    if (!isFlowCompatibleWithAmount(resolved, amount)) {
      throw new IllegalArgumentException("error.bad_request");
    }
    return resolved;
  }
}
