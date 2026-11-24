package pl.investassist.expense.classifier;

import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

@Component
public class PiiScrubber {

  private static final Pattern EMAIL_PATTERN =
      Pattern.compile("\\b[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}\\b");
  private static final Pattern POLISH_IBAN_PATTERN =
      Pattern.compile("\\bPL\\s?\\d{2}(?:[\\s-]?\\d{4}){6}\\b", Pattern.CASE_INSENSITIVE);
  private static final Pattern GENERIC_IBAN_PATTERN =
      Pattern.compile(
          "\\b[A-Z]{2}\\d{2}(?:[\\s-]?[A-Z0-9]{4}){2,7}(?:[\\s-]?[A-Z0-9]{1,4})?\\b",
          Pattern.CASE_INSENSITIVE);
  private static final Pattern POLISH_NRB_SPACED_PATTERN =
      Pattern.compile("\\b\\d{2}(?:[\\s-]?\\d{4}){6}\\b");
  private static final Pattern POLISH_NRB_COMPACT_PATTERN =
      Pattern.compile("\\b\\d{26}\\b");
  private static final Pattern MASKED_CARD_PATTERN = Pattern.compile(
      "(?<!\\w)(?:"
          + "\\d{4}[\\s-]?\\d{2}[*xX]{2}[\\s-]?[*xX]{4}[\\s-]?\\d{4}|"
          + "\\d{1,6}[\\s-]?[*xX]{2,8}[\\s-]?[*xX]{2,8}[\\s-]?\\d{4}|"
          + "\\d[*xX]{3}[\\s-]?[*xX]{4}[\\s-]?[*xX]{4}[\\s-]?\\d{4}|"
          + "[*xX]{4}[\\s-]?[*xX]{4}[\\s-]?[*xX]{4}[\\s-]?\\d{4}|"
          + "\\d{4}[\\s-]?[*xX]{4}[\\s-]?[*xX]{4}[\\s-]?\\d{4}"
          + ")(?!\\w)");
  private static final Pattern CARD_NUMBER_PATTERN =
      Pattern.compile("\\b(?:\\d{4}[\\s-]){3}\\d{1,7}\\b|\\b\\d{13,19}\\b");
  private static final Pattern INTL_PHONE_PATTERN =
      Pattern.compile("(?<!\\w)\\+\\d{1,3}(?:[\\s-]?\\d){7,12}\\b");
  private static final Pattern GROUPED_PHONE_PATTERN =
      Pattern.compile("\\b\\d{3}[\\s-]\\d{3}[\\s-]\\d{3}\\b");
  private static final Pattern LONG_NUMERIC_ID_PATTERN =
      Pattern.compile("\\b\\d{8,}\\b");

  public String scrub(String rawText) {
    if (rawText == null || rawText.isBlank()) {
      return "";
    }
    String text = rawText.replace("\r\n", "\n").replace('\r', '\n');
    text = text.replaceAll("[\\t\\p{Zs}]+", " ");
    text = EMAIL_PATTERN.matcher(text).replaceAll("[EMAIL]");
    text = POLISH_IBAN_PATTERN.matcher(text).replaceAll("[IBAN]");
    text = scrubGenericIbans(text);
    text = POLISH_NRB_SPACED_PATTERN.matcher(text).replaceAll("[ACCOUNT]");
    text = POLISH_NRB_COMPACT_PATTERN.matcher(text).replaceAll("[ACCOUNT]");
    text = MASKED_CARD_PATTERN.matcher(text).replaceAll("[CARD]");
    text = CARD_NUMBER_PATTERN.matcher(text).replaceAll("[CARD]");
    text = INTL_PHONE_PATTERN.matcher(text).replaceAll("[PHONE]");
    text = GROUPED_PHONE_PATTERN.matcher(text).replaceAll("[PHONE]");
    text = LONG_NUMERIC_ID_PATTERN.matcher(text).replaceAll("[ID]");
    text = text.replaceAll("[ \\t\\u00A0\\u202F]+", " ");
    text = text.replaceAll(" *\\n *", "\n");
    text = text.replaceAll("\\n{3,}", "\n\n");
    return text.trim();
  }

  private static String scrubGenericIbans(String input) {
    Matcher m = GENERIC_IBAN_PATTERN.matcher(input);
    StringBuilder sb = new StringBuilder();
    while (m.find()) {
      String token = m.group();
      long digitCount = token.chars().filter(Character::isDigit).count();
      long compactLen = token.replaceAll("[\\s-]", "").length();
      if (compactLen >= 15 && compactLen <= 34 && digitCount >= 8) {
        m.appendReplacement(sb, "[IBAN]");
      } else {
        m.appendReplacement(sb, Matcher.quoteReplacement(token));
      }
    }
    m.appendTail(sb);
    return sb.toString();
  }
}
