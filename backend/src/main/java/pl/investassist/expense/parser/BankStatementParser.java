package pl.investassist.expense.parser;

import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;
import pl.investassist.expense.ExpenseSupport;
import pl.investassist.expense.enums.BankProfile;

@Component
public class BankStatementParser {

  private static final Charset WINDOWS_1250 = Charset.forName("windows-1250");
  private static final Charset ISO_8859_2 = Charset.forName("ISO-8859-2");

  private static final Pattern CURRENCY_CODE_PATTERN =
      Pattern.compile("\\b(PLN|EUR|USD|GBP|CHF|CZK|NOK|SEK|DKK|JPY|CAD|AUD|HUF)\\b");
  private static final Pattern PERIOD_RANGE_PATTERN = Pattern.compile(
      "(\\d{4}-\\d{2}-\\d{2}|\\d{2}[./-]\\d{2}[./-]\\d{4})\\s*(?:-|–|do|to)\\s*(\\d{4}-\\d{2}-\\d{2}|\\d{2}[./-]\\d{2}[./-]\\d{4})",
      Pattern.CASE_INSENSITIVE);
  private static final Pattern LEADING_DATE_PATTERN = Pattern.compile(
      "^\\s*(\\d{4}-\\d{2}-\\d{2}|\\d{2}[./-]\\d{2}[./-]\\d{4}|\\d{1,2}\\s+[A-Za-zĄĆĘŁŃÓŚŹŻąćęłńóśźż]{3,4}\\s+\\d{4})\\b");
  private static final Pattern INLINE_DATE_PATTERN = Pattern.compile(
      "\\b(?:\\d{4}-\\d{2}-\\d{2}|\\d{2}[./-]\\d{2}[./-]\\d{4})\\b");
  private static final Pattern OPENING_LABEL_PATTERN = Pattern.compile(
      "(?:saldo poczatkowe|saldo otwarcia|opening balance|balance brought forward)");
  private static final Pattern CLOSING_LABEL_PATTERN = Pattern.compile(
      "(?:saldo koncowe|saldo zamkniecia|closing balance|balance carried forward)");
  private static final Pattern PDF_MONEY_TOKEN_PATTERN = Pattern.compile(
      "(?<![\\d.,])([+\\-\\u2212\\u2013\\u2014]?\\s*(?:[$€£]\\s*)?(?:\\d{1,3}(?:[ \\u00A0\\u202F.]\\d{3})+,\\d{2}|\\d{1,3}(?:[ \\u00A0\\u202F]\\d{3})+\\.\\d{2}|\\d{1,3}(?:,\\d{3})+\\.\\d{2}|\\d+[.,]\\d{2}))(?:\\s*(PLN|EUR|USD|GBP|CHF|CZK|NOK|SEK|JPY|zł|zl|[$€£]))?(?=$|\\s|;)",
      Pattern.CASE_INSENSITIVE);
  private static final Pattern FLEXIBLE_MONEY_PATTERN = Pattern.compile(
      "([+\\-\\u2212\\u2013\\u2014]?\\s*\\(?\\s*(?:[$€£]\\s*)?(?:\\d{1,3}(?:[ \\u00A0\\u202F.,]\\d{3})+|\\d+)(?:[.,]\\d{1,2})?\\s*\\)?)(?:\\s*(PLN|EUR|USD|GBP|CHF|CZK|NOK|SEK|JPY|zł|zl|[$€£]))?",
      Pattern.CASE_INSENSITIVE);

  private static final Map<String, Integer> MONTH_MAP = buildMonthMap();

  private static final DateTimeFormatter ISO_DATE = DateTimeFormatter.ISO_LOCAL_DATE;
  private static final DateTimeFormatter DOT_DATE = DateTimeFormatter.ofPattern("dd.MM.yyyy");
  private static final DateTimeFormatter DASH_DMY = DateTimeFormatter.ofPattern("dd-MM-yyyy");
  private static final DateTimeFormatter SLASH_DMY = DateTimeFormatter.ofPattern("dd/MM/yyyy");

  public ParsedStatement parseCsv(byte[] csvBytes, BankProfile profileHint) {
    if (csvBytes == null || csvBytes.length == 0) {
      return new ParsedStatement(
          resolveHintOrGeneric(profileHint), "PLN", null, null, null, null, List.of(), List.of());
    }
    String text = decodeCsvBytes(csvBytes);
    String[] rawLines = text.split("\\r?\\n", -1);

    HeaderInfo headerInfo = findCsvHeader(rawLines);
    if (headerInfo == null) {
      return parseText(text, profileHint);
    }
    Metadata meta = extractCsvMetadata(rawLines, headerInfo.lineIndex);

    String preambleText = joinLines(rawLines, headerInfo.lineIndex + 1);
    BankProfile profile = detectProfile(profileHint, preambleText, headerInfo.normalizedColumns);
    List<List<String>> dataRows = parseCsvRecords(rawLines, headerInfo.lineIndex + 1, headerInfo.delimiter);

    List<ParsedTransactionRow> rows = new ArrayList<>();
    List<BigDecimal> runningBalances = new ArrayList<>();
    List<String> warnings = new ArrayList<>();
    boolean allHaveRunningBalance;

    if (hasRevolutSchema(headerInfo.columnIndex)) {
      allHaveRunningBalance = parseRevolutRows(
          headerInfo.columnIndex, dataRows, rows, runningBalances, warnings);
    } else if (hasWiseSchema(headerInfo.columnIndex)) {
      allHaveRunningBalance = parseWiseRows(
          headerInfo.columnIndex, dataRows, rows, runningBalances, warnings);
    } else {
      allHaveRunningBalance = parseGenericCsvRows(
          headerInfo.columnIndex, dataRows, meta.currency, rows, runningBalances, warnings);
    }

    return buildParsedStatement(profile, meta, rows, runningBalances, allHaveRunningBalance, warnings);
  }

  public ParsedStatement parseText(String extractedText, BankProfile profileHint) {
    if (extractedText == null || extractedText.isBlank()) {
      return new ParsedStatement(
          resolveHintOrGeneric(profileHint), "PLN", null, null, null, null, List.of(), List.of());
    }
    String[] lines = extractedText.split("\\r?\\n");
    Metadata meta = extractMetadataFromLines(lines);
    BankProfile profile = detectProfile(profileHint, extractedText, List.of());
    String defaultCurrency = meta.currency != null ? meta.currency : "PLN";

    List<ParsedTransactionRow> rows = new ArrayList<>();
    List<BigDecimal> runningBalances = new ArrayList<>();
    List<String> warnings = new ArrayList<>();
    PendingRow pending = new PendingRow(defaultCurrency);

    for (String rawLine : lines) {
      String line = rawLine.trim();
      if (line.isEmpty()) {
        continue;
      }
      if (isSummaryOrMetadataLine(line)) {
        pending.flush(rows, runningBalances);
        continue;
      }

      Matcher dateMatcher = LEADING_DATE_PATTERN.matcher(line);
      if (dateMatcher.find()) {
        LocalDate parsedDate = parseDate(dateMatcher.group(1));
        if (parsedDate != null) {
          pending.flush(rows, runningBalances);

          String remainder = line.substring(dateMatcher.end()).trim();
          LocalDate bookingDate = parsedDate;
          Matcher secondDate = LEADING_DATE_PATTERN.matcher(remainder);
          if (secondDate.find()) {
            LocalDate maybeBooking = parseDate(secondDate.group(1));
            if (maybeBooking != null) {
              bookingDate = maybeBooking;
              remainder = remainder.substring(secondDate.end()).trim();
            }
          }

          pending.reset(parsedDate, bookingDate);
          List<MoneyMatch> moneyMatches = findPdfMoneyMatches(remainder);
          if (!moneyMatches.isEmpty()) {
            pending.applyMoneyMatches(remainder, moneyMatches);
          } else {
            pending.appendDescription(remainder);
          }
          continue;
        }
      }

      if (pending.hasDate() && !isNoiseHeaderOrFooter(line)) {
        if (!pending.hasAmount()) {
          List<MoneyMatch> moneyMatches = findPdfMoneyMatches(line);
          if (!moneyMatches.isEmpty()) {
            pending.applyMoneyMatches(line, moneyMatches);
            continue;
          }
        }
        pending.appendDescription(line);
      }
    }

    pending.flush(rows, runningBalances);
    return buildParsedStatement(profile, meta, rows, runningBalances, pending.allHaveRunningBalance, warnings);
  }

  private static ParsedStatement buildParsedStatement(
      BankProfile profile,
      Metadata meta,
      List<ParsedTransactionRow> rows,
      List<BigDecimal> runningBalances,
      boolean allHaveRunningBalance,
      List<String> warnings) {
    String statementCurrency = meta.currency;
    if (statementCurrency == null || statementCurrency.isBlank()) {
      statementCurrency = rows.stream()
          .map(ParsedTransactionRow::currency)
          .filter(c -> c != null && !c.isBlank())
          .findFirst()
          .orElse("PLN");
    }

    BigDecimal openingBalance = meta.openingBalance;
    BigDecimal closingBalance = meta.closingBalance;
    if ((openingBalance == null || closingBalance == null)
        && allHaveRunningBalance
        && !rows.isEmpty()
        && runningBalances.size() == rows.size()
        && hasSingleCurrency(rows)) {
      BigDecimal[] inferred = inferBalancesFromRunning(rows, runningBalances);
      if (openingBalance == null) {
        openingBalance = inferred[0];
      }
      if (closingBalance == null) {
        closingBalance = inferred[1];
      }
    }

    LocalDate[] period = inferPeriod(meta.periodStart, meta.periodEnd, rows);
    return new ParsedStatement(
        profile,
        statementCurrency,
        period[0],
        period[1],
        openingBalance,
        closingBalance,
        List.copyOf(rows),
        List.copyOf(warnings));
  }

  private static boolean hasRevolutSchema(Map<String, Integer> colIdx) {
    return colIdx.containsKey("started date")
        && colIdx.containsKey("completed date")
        && colIdx.containsKey("amount")
        && colIdx.containsKey("state");
  }

  private static boolean hasWiseSchema(Map<String, Integer> colIdx) {
    return colIdx.containsKey("transferwise id")
        || (colIdx.containsKey("running balance") && colIdx.containsKey("total fees")
            && colIdx.containsKey("date") && colIdx.containsKey("amount"));
  }

  private boolean parseRevolutRows(
      Map<String, Integer> colIdx,
      List<List<String>> dataRows,
      List<ParsedTransactionRow> outRows,
      List<BigDecimal> runningBalances,
      List<String> warnings) {
    Integer typeIdx = findCol(colIdx, "type");
    Integer startedIdx = findCol(colIdx, "started date");
    Integer completedIdx = findCol(colIdx, "completed date");
    Integer descIdx = findCol(colIdx, "description");
    Integer amountIdx = findCol(colIdx, "amount");
    Integer feeIdx = findCol(colIdx, "fee");
    Integer currencyIdx = findCol(colIdx, "currency");
    Integer stateIdx = findCol(colIdx, "state");
    Integer balanceIdx = findCol(colIdx, "balance");

    boolean allHaveBalance = true;
    for (int i = 0; i < dataRows.size(); i++) {
      List<String> row = dataRows.get(i);
      if (isRowEmpty(row)) {
        continue;
      }
      String state = getCell(row, stateIdx).toUpperCase(Locale.ROOT);
      if (!state.isEmpty() && !state.equals("COMPLETED")) {
        continue;
      }
      LocalDate txDate = parseDate(getCell(row, completedIdx));
      if (txDate == null) {
        txDate = parseDate(getCell(row, startedIdx));
      }
      LocalDate bookingDate = parseDate(getCell(row, completedIdx));
      if (bookingDate == null) {
        bookingDate = txDate;
      }
      BigDecimal amount = parseAmount(getCell(row, amountIdx));
      if (txDate == null || amount == null) {
        warnings.add("Skipped unparsed Revolut row #" + (i + 1));
        continue;
      }
      BigDecimal fee = parseAmount(getCell(row, feeIdx));
      BigDecimal netAmount = fee != null && fee.compareTo(BigDecimal.ZERO) > 0
          ? amount.subtract(fee)
          : amount;
      String desc = getCell(row, descIdx);
      String type = getCell(row, typeIdx);
      if (desc.isEmpty()) {
        desc = type.isEmpty() ? "Revolut transaction" : type;
      }
      String currency = normalizeCurrencyToken(getCell(row, currencyIdx), "EUR");
      outRows.add(buildRow(txDate, bookingDate, desc, netAmount, currency));

      BigDecimal bal = parseAmount(getCell(row, balanceIdx));
      if (bal != null) {
        runningBalances.add(bal);
      } else {
        allHaveBalance = false;
      }
    }
    return allHaveBalance;
  }

  private boolean parseWiseRows(
      Map<String, Integer> colIdx,
      List<List<String>> dataRows,
      List<ParsedTransactionRow> outRows,
      List<BigDecimal> runningBalances,
      List<String> warnings) {
    Integer dateIdx = findCol(colIdx, "date");
    Integer amountIdx = findCol(colIdx, "amount");
    Integer currencyIdx = findCol(colIdx, "currency");
    Integer descIdx = findCol(colIdx, "description");
    Integer refIdx = findCol(colIdx, "payment reference");
    Integer merchantIdx = findCol(colIdx, "merchant");
    Integer balanceIdx = findCol(colIdx, "running balance", "balance");

    boolean allHaveBalance = true;
    for (int i = 0; i < dataRows.size(); i++) {
      List<String> row = dataRows.get(i);
      if (isRowEmpty(row)) {
        continue;
      }
      LocalDate txDate = parseDate(getCell(row, dateIdx));
      BigDecimal amount = parseAmount(getCell(row, amountIdx));
      if (txDate == null || amount == null) {
        warnings.add("Skipped unparsed Wise row #" + (i + 1));
        continue;
      }
      String desc = combineParts(
          getCell(row, merchantIdx),
          getCell(row, descIdx),
          getCell(row, refIdx));
      if (desc.isEmpty()) {
        desc = "Wise transaction";
      }
      String currency = normalizeCurrencyToken(getCell(row, currencyIdx), "EUR");
      outRows.add(buildRow(txDate, txDate, desc, amount, currency));

      BigDecimal bal = parseAmount(getCell(row, balanceIdx));
      if (bal != null) {
        runningBalances.add(bal);
      } else {
        allHaveBalance = false;
      }
    }
    return allHaveBalance;
  }

  private boolean parseGenericCsvRows(
      Map<String, Integer> colIdx,
      List<List<String>> dataRows,
      String defaultCurrency,
      List<ParsedTransactionRow> outRows,
      List<BigDecimal> runningBalances,
      List<String> warnings) {
    Integer txDateIdx = findCol(colIdx,
        "data operacji", "data transakcji", "date", "transaction date", "operation date", "started date", "data");
    Integer bookingDateIdx = findCol(colIdx,
        "data ksiegowania", "data waluty", "booking date", "value date", "completed date");
    if (txDateIdx == null) {
      txDateIdx = bookingDateIdx;
    }

    Set<Integer> dateExcluded = excluded(txDateIdx, bookingDateIdx);
    Integer descIdx = findCol(colIdx, true, dateExcluded,
        "opis operacji", "opis transakcji", "description", "opis", "tytul", "details", "title", "merchant");
    Integer counterpartyIdx = findCol(colIdx, true, excluded(txDateIdx, bookingDateIdx, descIdx),
        "nadawca / odbiorca", "nadawca/odbiorca", "dane kontrahenta", "kontrahent", "nadawca", "odbiorca", "counterparty", "payee");
    Integer txTypeIdx = findCol(colIdx, true, excluded(txDateIdx, bookingDateIdx, descIdx, counterpartyIdx),
        "typ transakcji", "typ operacji");
    Integer titleIdx = findCol(colIdx, true, excluded(txDateIdx, bookingDateIdx, descIdx, counterpartyIdx, txTypeIdx),
        "tytul", "title", "szczegoly", "details");

    Integer debitIdx = findCol(colIdx, true, dateExcluded,
        "obciazenia", "obciazenie", "obciazen", "debit", "outflow", "wydatek");
    Integer creditIdx = findCol(colIdx, true, excluded(txDateIdx, bookingDateIdx, debitIdx),
        "uznania", "uznanie", "uznan", "credit", "inflow", "wplyw");
    Integer amountIdx = findCol(colIdx, true, excluded(txDateIdx, bookingDateIdx, debitIdx, creditIdx),
        "kwota", "kwota operacji", "kwota transakcji", "kwota w walucie rachunku", "amount");
    Integer balanceIdx = findCol(colIdx, true, excluded(txDateIdx, bookingDateIdx, debitIdx, creditIdx, amountIdx),
        "saldo po operacji", "saldo po transakcji", "saldo", "balance", "running balance");
    Integer currencyIdx = findCol(colIdx, true,
        excluded(txDateIdx, bookingDateIdx, debitIdx, creditIdx, amountIdx, balanceIdx),
        "waluta", "currency", "waluta transakcji");

    boolean allHaveBalance = true;
    String fallbackCurrency = defaultCurrency != null ? defaultCurrency : "PLN";

    for (int i = 0; i < dataRows.size(); i++) {
      List<String> row = dataRows.get(i);
      if (isRowEmpty(row)) {
        continue;
      }
      String firstCell = row.get(0).trim();
      if (isSummaryOrMetadataLine(firstCell)) {
        continue;
      }
      LocalDate txDate = parseDate(getCell(row, txDateIdx));
      if (txDate == null) {
        txDate = parseDate(getCell(row, bookingDateIdx));
      }
      LocalDate bookingDate = parseDate(getCell(row, bookingDateIdx));
      if (bookingDate == null) {
        bookingDate = txDate;
      }

      String rawAmountCell = getCell(row, amountIdx);
      BigDecimal amount = parseAmount(rawAmountCell);
      if (amount == null && (debitIdx != null || creditIdx != null)) {
        String debitRaw = getCell(row, debitIdx);
        String creditRaw = getCell(row, creditIdx);
        BigDecimal debit = parseAmount(debitRaw);
        BigDecimal credit = parseAmount(creditRaw);
        if (credit != null && credit.compareTo(BigDecimal.ZERO) != 0) {
          amount = credit.abs();
          rawAmountCell = creditRaw;
        } else if (debit != null && debit.compareTo(BigDecimal.ZERO) != 0) {
          amount = debit.abs().negate();
          rawAmountCell = debitRaw;
        }
      }

      if (txDate == null || amount == null) {
        if (!firstCell.isEmpty() && firstCell.matches(".*\\d.*")) {
          warnings.add("Skipped unparsed CSV row #" + (i + 1));
        }
        continue;
      }

      String desc = combineParts(
          getCell(row, txTypeIdx),
          getCell(row, counterpartyIdx),
          getCell(row, descIdx),
          getCell(row, titleIdx));
      if (desc.isEmpty()) {
        desc = "Bank transaction";
      }

      String rowCurrency = normalizeCurrencyToken(getCell(row, currencyIdx), null);
      if (rowCurrency == null) {
        rowCurrency = extractCurrencyFromText(rawAmountCell);
      }
      if (rowCurrency == null) {
        rowCurrency = fallbackCurrency;
      }

      outRows.add(buildRow(txDate, bookingDate, desc, amount, rowCurrency));

      BigDecimal bal = parseAmount(getCell(row, balanceIdx));
      if (bal != null) {
        runningBalances.add(bal);
      } else {
        allHaveBalance = false;
      }
    }
    return allHaveBalance;
  }

  private static String decodeCsvBytes(byte[] bytes) {
    int offset = 0;
    if (bytes.length >= 3
        && (bytes[0] & 0xFF) == 0xEF
        && (bytes[1] & 0xFF) == 0xBB
        && (bytes[2] & 0xFF) == 0xBF) {
      offset = 3;
      return new String(bytes, offset, bytes.length - offset, StandardCharsets.UTF_8);
    }

    CharsetDecoder utf8Decoder = StandardCharsets.UTF_8
        .newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT);
    try {
      return utf8Decoder.decode(ByteBuffer.wrap(bytes, offset, bytes.length - offset)).toString();
    } catch (CharacterCodingException ignored) {
      Charset legacyCharset = selectPolishLegacyCharset(bytes, offset);
      return new String(bytes, offset, bytes.length - offset, legacyCharset);
    }
  }

  private static Charset selectPolishLegacyCharset(byte[] bytes, int offset) {
    int cp1250Score = 0;
    int iso88592Score = 0;
    for (int i = offset; i < bytes.length; i++) {
      int b = bytes[i] & 0xFF;
      switch (b) {
        case 0x8C, 0x8F, 0x9C, 0x9F, 0xA5, 0xB9 -> cp1250Score++;
        case 0xA1, 0xA6, 0xAC, 0xB1, 0xB6, 0xBC -> iso88592Score++;
        default -> {}
      }
    }
    return iso88592Score > cp1250Score ? ISO_8859_2 : WINDOWS_1250;
  }

  private static HeaderInfo findCsvHeader(String[] lines) {
    int maxScan = Math.min(lines.length, 60);
    for (int i = 0; i < maxScan; i++) {
      String line = lines[i];
      if (line == null || line.isBlank()) {
        continue;
      }
      char delimiter = detectDelimiter(line);
      if (delimiter == 0) {
        continue;
      }
      List<String> cells = splitCsvLine(line, delimiter);
      if (cells.size() < 3) {
        continue;
      }
      List<String> normalized = new ArrayList<>(cells.size());
      Map<String, Integer> indexMap = new LinkedHashMap<>();
      boolean hasDateCol = false;
      boolean hasAmountOrDescCol = false;
      for (int c = 0; c < cells.size(); c++) {
        String norm = normalizeHeaderName(cells.get(c));
        normalized.add(norm);
        if (!norm.isEmpty()) {
          indexMap.putIfAbsent(norm, c);
        }
        if (norm.contains("data") || norm.contains("date")) {
          hasDateCol = true;
        }
        if (norm.contains("kwota") || norm.contains("amount")
            || norm.contains("opis") || norm.contains("description")
            || norm.contains("debit") || norm.contains("credit")
            || norm.contains("obciazenia") || norm.contains("uznania")
            || norm.contains("tytul")) {
          hasAmountOrDescCol = true;
        }
      }
      if (hasDateCol && hasAmountOrDescCol) {
        return new HeaderInfo(i, delimiter, normalized, indexMap);
      }
    }
    return null;
  }

  private static char detectDelimiter(String line) {
    int semi = countOutsideQuotes(line, ';');
    int comma = countOutsideQuotes(line, ',');
    int tab = countOutsideQuotes(line, '\t');
    if (semi >= 2 && semi >= comma && semi >= tab) {
      return ';';
    }
    if (comma >= 2 && comma >= tab) {
      return ',';
    }
    if (tab >= 2) {
      return '\t';
    }
    return 0;
  }

  private static int countOutsideQuotes(String line, char target) {
    boolean inQuotes = false;
    int count = 0;
    for (int i = 0; i < line.length(); i++) {
      char ch = line.charAt(i);
      if (ch == '"') {
        inQuotes = !inQuotes;
      } else if (!inQuotes && ch == target) {
        count++;
      }
    }
    return count;
  }

  private static List<List<String>> parseCsvRecords(String[] lines, int startLine, char delimiter) {
    List<List<String>> records = new ArrayList<>();
    StringBuilder current = new StringBuilder();
    boolean inQuotes = false;

    for (int i = startLine; i < lines.length; i++) {
      String line = lines[i];
      if (!inQuotes) {
        if (line.isBlank()) {
          continue;
        }
        current.setLength(0);
        current.append(line);
      } else {
        current.append(' ').append(line);
      }
      inQuotes = hasUnclosedQuotes(current);
      if (!inQuotes) {
        records.add(splitCsvLine(current.toString(), delimiter));
      }
    }
    if (inQuotes && !current.isEmpty()) {
      records.add(splitCsvLine(current.toString(), delimiter));
    }
    return records;
  }

  private static boolean hasUnclosedQuotes(CharSequence text) {
    return countUnclosedQuotes(text, '\0');
  }

  private static boolean countUnclosedQuotes(CharSequence text, char delimiter) {
    boolean inQuotes = false;
    boolean atFieldStart = true;
    for (int i = 0; i < text.length(); i++) {
      char ch = text.charAt(i);
      if (inQuotes) {
        if (ch == '"') {
          if (i + 1 < text.length() && text.charAt(i + 1) == '"') {
            i++;
          } else if (delimiter == '\0'
              ? isPotentialClosingQuoteWithoutDelimiter(text, i)
              : isClosingQuote(text, i, delimiter)) {
            inQuotes = false;
          }
        }
      } else {
        if (ch == '"' && atFieldStart) {
          inQuotes = true;
          atFieldStart = false;
        } else if (delimiter != '\0' && ch == delimiter) {
          atFieldStart = true;
        } else if (delimiter == '\0' && (ch == ';' || ch == ',' || ch == '\t')) {
          atFieldStart = true;
        } else if (!Character.isWhitespace(ch)) {
          atFieldStart = false;
        }
      }
    }
    return inQuotes;
  }

  private static boolean isClosingQuote(CharSequence text, int quoteIdx, char delimiter) {
    for (int j = quoteIdx + 1; j < text.length(); j++) {
      char next = text.charAt(j);
      if (next == delimiter) {
        return true;
      }
      if (next != ' ' && next != '\t') {
        return false;
      }
    }
    return true;
  }

  private static boolean isPotentialClosingQuoteWithoutDelimiter(CharSequence text, int quoteIdx) {
    for (int j = quoteIdx + 1; j < text.length(); j++) {
      char next = text.charAt(j);
      if (next == ';' || next == ',' || next == '\t') {
        return true;
      }
      if (next != ' ' && next != '\t') {
        return false;
      }
    }
    return true;
  }

  private static List<String> splitCsvLine(String line, char delimiter) {
    List<String> fields = new ArrayList<>();
    StringBuilder field = new StringBuilder();
    boolean inQuotes = false;
    boolean atFieldStart = true;

    for (int i = 0; i < line.length(); i++) {
      char ch = line.charAt(i);
      if (inQuotes) {
        if (ch == '"') {
          if (i + 1 < line.length() && line.charAt(i + 1) == '"') {
            field.append('"');
            i++;
          } else if (isClosingQuote(line, i, delimiter)) {
            inQuotes = false;
          } else {
            field.append('"');
          }
        } else {
          field.append(ch);
        }
      } else if (ch == '"' && atFieldStart) {
        inQuotes = true;
        atFieldStart = false;
      } else if (ch == delimiter) {
        fields.add(field.toString().trim());
        field.setLength(0);
        atFieldStart = true;
      } else {
        if (!Character.isWhitespace(ch)) {
          atFieldStart = false;
        }
        field.append(ch);
      }
    }
    fields.add(field.toString().trim());
    return fields;
  }

  private static BankProfile detectProfile(BankProfile hint, String headerContext, List<String> headers) {
    if (hint != null && hint != BankProfile.AUTO) {
      return hint;
    }
    String joinedHeaders = String.join("|", headers);
    String sample = ExpenseSupport.toAsciiLower(
        headerContext.length() > 2500 ? headerContext.substring(0, 2500) : headerContext);

    if (joinedHeaders.contains("started date") && joinedHeaders.contains("completed date")
        && joinedHeaders.contains("state") && joinedHeaders.contains("balance")) {
      return BankProfile.REVOLUT;
    }
    if (joinedHeaders.contains("transferwise id")
        || (joinedHeaders.contains("running balance") && joinedHeaders.contains("total fees"))) {
      return BankProfile.WISE;
    }
    if (joinedHeaders.contains("data operacji") && joinedHeaders.contains("opis operacji")
        && joinedHeaders.contains("saldo po operacji")) {
      return BankProfile.MBANK;
    }
    if (joinedHeaders.contains("data waluty") && joinedHeaders.contains("typ transakcji")) {
      return BankProfile.PKO_BP;
    }
    if (joinedHeaders.contains("dane kontrahenta") && joinedHeaders.contains("data transakcji")) {
      return BankProfile.ING;
    }
    if (joinedHeaders.contains("data ksiegowania") && joinedHeaders.contains("data operacji")
        && joinedHeaders.contains("opis transakcji")) {
      return BankProfile.SANTANDER;
    }
    if (joinedHeaders.contains("data ksiegowania") && joinedHeaders.contains("data waluty")
        && joinedHeaders.contains("nadawca / odbiorca")) {
      return BankProfile.PEKAO;
    }

    if (sample.contains("mbank")) {
      return BankProfile.MBANK;
    }
    if (sample.contains("pko bp") || sample.contains("pko bank polski") || sample.contains("ipko")) {
      return BankProfile.PKO_BP;
    }
    if (sample.contains("ing bank") || sample.contains("moje ing")) {
      return BankProfile.ING;
    }
    if (sample.contains("santander")) {
      return BankProfile.SANTANDER;
    }
    if (sample.contains("bank pekao") || sample.contains("pekao24")) {
      return BankProfile.PEKAO;
    }
    if (sample.contains("revolut")) {
      return BankProfile.REVOLUT;
    }
    if (sample.contains("transferwise") || sample.contains("wise payments")) {
      return BankProfile.WISE;
    }
    return BankProfile.GENERIC;
  }

  private static String joinLines(String[] lines, int count) {
    int limit = Math.min(lines.length, count);
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < limit; i++) {
      if (lines[i] != null) {
        sb.append(lines[i]).append('\n');
      }
    }
    return sb.toString();
  }

  private static Metadata extractCsvMetadata(String[] lines, int headerLineIndex) {
    List<String> metaLines = new ArrayList<>();
    int limit = Math.min(headerLineIndex, lines.length);
    for (int i = 0; i < limit; i++) {
      metaLines.add(lines[i]);
    }
    for (int i = headerLineIndex + 1; i < lines.length; i++) {
      String raw = lines[i];
      if (raw == null || raw.isBlank()) {
        continue;
      }
      String norm = ExpenseSupport.toAsciiLower(raw);
      if ((OPENING_LABEL_PATTERN.matcher(norm).find() || CLOSING_LABEL_PATTERN.matcher(norm).find())
          && !LEADING_DATE_PATTERN.matcher(raw.trim()).find()) {
        metaLines.add(raw);
      }
    }
    return extractMetadataFromLines(metaLines.toArray(String[]::new));
  }

  private static Metadata extractMetadataFromLines(String[] lines) {
    String currency = null;
    LocalDate periodStart = null;
    LocalDate periodEnd = null;
    BigDecimal openingBalance = null;
    BigDecimal closingBalance = null;

    for (String rawLine : lines) {
      if (rawLine == null || rawLine.isBlank()) {
        continue;
      }
      if (LEADING_DATE_PATTERN.matcher(rawLine.trim()).find()) {
        continue;
      }
      String norm = ExpenseSupport.toAsciiLower(rawLine);

      if (currency == null && (norm.contains("waluta") || norm.contains("currency")
          || norm.contains("wyciag") || norm.contains("saldo"))) {
        currency = extractCurrencyFromText(rawLine);
      }

      if (periodStart == null || periodEnd == null) {
        Matcher pm = PERIOD_RANGE_PATTERN.matcher(rawLine);
        if (pm.find()) {
          LocalDate s = parseDate(pm.group(1));
          LocalDate e = parseDate(pm.group(2));
          if (s != null && e != null) {
            periodStart = s;
            periodEnd = e;
          }
        }
      }

      if (openingBalance == null) {
        BigDecimal parsed = extractLabeledBalance(norm, OPENING_LABEL_PATTERN, CLOSING_LABEL_PATTERN);
        if (parsed != null) {
          openingBalance = parsed;
          if (currency == null) {
            currency = extractCurrencyFromText(rawLine);
          }
        }
      }

      if (closingBalance == null) {
        BigDecimal parsed = extractLabeledBalance(norm, CLOSING_LABEL_PATTERN, OPENING_LABEL_PATTERN);
        if (parsed != null) {
          closingBalance = parsed;
          if (currency == null) {
            currency = extractCurrencyFromText(rawLine);
          }
        }
      }
    }
    return new Metadata(currency, periodStart, periodEnd, openingBalance, closingBalance);
  }

  private static BigDecimal extractLabeledBalance(
      String normLine, Pattern labelPattern, Pattern stopLabelPattern) {
    Matcher labelMatcher = labelPattern.matcher(normLine);
    if (!labelMatcher.find()) {
      return null;
    }
    String slice = normLine.substring(labelMatcher.end());
    Matcher stopMatcher = stopLabelPattern.matcher(slice);
    if (stopMatcher.find()) {
      slice = slice.substring(0, stopMatcher.start());
    }
    slice = INLINE_DATE_PATTERN.matcher(slice).replaceAll(" ");

    Matcher moneyMatcher = PDF_MONEY_TOKEN_PATTERN.matcher(slice);
    if (moneyMatcher.find()) {
      BigDecimal exact = parseAmount(moneyMatcher.group(1));
      if (exact != null) {
        return exact;
      }
    }

    Matcher flex = FLEXIBLE_MONEY_PATTERN.matcher(slice);
    while (flex.find()) {
      BigDecimal candidate = parseAmount(flex.group(1));
      if (candidate != null) {
        return candidate;
      }
    }
    return null;
  }

  private static BigDecimal[] inferBalancesFromRunning(
      List<ParsedTransactionRow> rows, List<BigDecimal> balances) {
    int n = rows.size();
    if (n == 1) {
      BigDecimal opening = balances.get(0).subtract(rows.get(0).amount());
      return new BigDecimal[] {ExpenseSupport.scale2(opening), ExpenseSupport.scale2(balances.get(0))};
    }

    int forwardMatches = 0;
    int reverseMatches = 0;
    BigDecimal tol = new BigDecimal("0.02");
    for (int i = 1; i < n; i++) {
      BigDecimal expectedForward = balances.get(i - 1).add(rows.get(i).amount());
      if (expectedForward.subtract(balances.get(i)).abs().compareTo(tol) <= 0) {
        forwardMatches++;
      }
      BigDecimal expectedReverse = balances.get(i).add(rows.get(i - 1).amount());
      if (expectedReverse.subtract(balances.get(i - 1)).abs().compareTo(tol) <= 0) {
        reverseMatches++;
      }
    }

    if (reverseMatches > forwardMatches) {
      BigDecimal opening = balances.get(n - 1).subtract(rows.get(n - 1).amount());
      BigDecimal closing = balances.get(0);
      return new BigDecimal[] {ExpenseSupport.scale2(opening), ExpenseSupport.scale2(closing)};
    }
    BigDecimal opening = balances.get(0).subtract(rows.get(0).amount());
    BigDecimal closing = balances.get(n - 1);
    return new BigDecimal[] {ExpenseSupport.scale2(opening), ExpenseSupport.scale2(closing)};
  }

  private static boolean hasSingleCurrency(List<ParsedTransactionRow> rows) {
    if (rows.isEmpty()) {
      return false;
    }
    String first = rows.get(0).currency();
    for (ParsedTransactionRow row : rows) {
      if (!first.equalsIgnoreCase(row.currency())) {
        return false;
      }
    }
    return true;
  }

  private static LocalDate[] inferPeriod(
      LocalDate explicitStart, LocalDate explicitEnd, List<ParsedTransactionRow> rows) {
    LocalDate start = explicitStart;
    LocalDate end = explicitEnd;
    for (ParsedTransactionRow row : rows) {
      if (row.txDate() == null) {
        continue;
      }
      if (start == null || row.txDate().isBefore(start)) {
        start = row.txDate();
      }
      if (end == null || row.txDate().isAfter(end)) {
        end = row.txDate();
      }
    }
    return new LocalDate[] {start, end};
  }

  private static List<MoneyMatch> findPdfMoneyMatches(String text) {
    List<MoneyMatch> matches = new ArrayList<>();
    Matcher m = PDF_MONEY_TOKEN_PATTERN.matcher(text);
    while (m.find()) {
      BigDecimal parsed = parseAmount(m.group(1));
      if (parsed != null) {
        String curr = normalizeCurrencyToken(m.group(2), null);
        if (curr == null) {
          curr = extractCurrencyFromText(m.group(1));
        }
        matches.add(new MoneyMatch(m.start(), parsed, curr));
      }
    }
    return matches;
  }

  public static BigDecimal parseAmount(String raw) {
    if (raw == null) {
      return null;
    }
    String s = raw.trim();
    if (s.isEmpty()) {
      return null;
    }
    s = s.replace('\u00A0', ' ')
        .replace('\u202F', ' ')
        .replace('\u2212', '-')
        .replace('–', '-')
        .replace('—', '-');

    boolean negative = false;
    if (s.startsWith("(") && s.endsWith(")")) {
      negative = true;
      s = s.substring(1, s.length() - 1).trim();
    }

    s = s.replaceAll("(?i)\\b(PLN|EUR|USD|GBP|CHF|CZK|NOK|SEK|DKK|JPY|CAD|AUD|HUF|zł|zl)\\b", "")
        .replace("$", "")
        .replace("€", "")
        .replace("£", "")
        .trim();

    if (s.startsWith("-")) {
      negative = true;
      s = s.substring(1).trim();
    } else if (s.startsWith("+")) {
      s = s.substring(1).trim();
    }

    s = s.replace(" ", "");
    if (s.isEmpty() || !s.matches("\\d+(?:[.,]\\d+)*")) {
      return null;
    }

    int lastComma = s.lastIndexOf(',');
    int lastDot = s.lastIndexOf('.');
    String normalized;
    if (lastComma >= 0 && lastDot >= 0) {
      if (lastComma > lastDot) {
        if (!s.matches("\\d{1,3}(?:\\.\\d{3})+,\\d{1,2}")) {
          return null;
        }
        normalized = s.replace(".", "").replace(",", ".");
      } else {
        if (!s.matches("\\d{1,3}(?:,\\d{3})+\\.\\d{1,2}")) {
          return null;
        }
        normalized = s.replace(",", "");
      }
    } else if (lastComma >= 0) {
      int digitsAfter = s.length() - lastComma - 1;
      int commaCount = (int) s.chars().filter(c -> c == ',').count();
      if (commaCount == 1 && (digitsAfter != 3 || s.startsWith("0,"))) {
        normalized = s.replace(",", ".");
      } else if (s.matches("[1-9]\\d{0,2}(?:,\\d{3})+")) {
        normalized = s.replace(",", "");
      } else {
        return null;
      }
    } else if (lastDot >= 0) {
      int digitsAfter = s.length() - lastDot - 1;
      int dotCount = (int) s.chars().filter(c -> c == '.').count();
      if (dotCount == 1 && (digitsAfter != 3 || s.startsWith("0."))) {
        normalized = s;
      } else if (s.matches("[1-9]\\d{0,2}(?:\\.\\d{3})+")) {
        normalized = s.replace(".", "");
      } else {
        return null;
      }
    } else {
      normalized = s;
    }

    try {
      BigDecimal val = new BigDecimal(normalized);
      if (negative && val.compareTo(BigDecimal.ZERO) > 0) {
        val = val.negate();
      }
      return ExpenseSupport.scale2(val);
    } catch (NumberFormatException ignored) {
      return null;
    }
  }

  public static LocalDate parseDate(String raw) {
    if (raw == null) {
      return null;
    }
    String s = raw.trim();
    if (s.isEmpty()) {
      return null;
    }
    if (s.length() >= 10 && s.charAt(4) == '-' && s.charAt(7) == '-') {
      try {
        return LocalDate.parse(s.substring(0, 10), ISO_DATE);
      } catch (DateTimeParseException ignored) {
      }
    }
    if (s.length() >= 10 && s.charAt(2) == '.' && s.charAt(5) == '.') {
      try {
        return LocalDate.parse(s.substring(0, 10), DOT_DATE);
      } catch (DateTimeParseException ignored) {
      }
    }
    if (s.length() >= 10 && s.charAt(2) == '-' && s.charAt(5) == '-') {
      try {
        return LocalDate.parse(s.substring(0, 10), DASH_DMY);
      } catch (DateTimeParseException ignored) {
      }
    }
    if (s.length() >= 10 && s.charAt(2) == '/' && s.charAt(5) == '/') {
      try {
        return LocalDate.parse(s.substring(0, 10), SLASH_DMY);
      } catch (DateTimeParseException ignored) {
      }
    }
    String[] parts = s.split("\\s+");
    if (parts.length >= 3) {
      try {
        int day = Integer.parseInt(parts[0]);
        Integer month = MONTH_MAP.get(ExpenseSupport.toAsciiLower(parts[1]).replace(".", ""));
        int year = Integer.parseInt(parts[2].substring(0, 4));
        if (month != null && day >= 1 && day <= 31 && year >= 1990 && year <= 2100) {
          return LocalDate.of(year, month, day);
        }
      } catch (Exception ignored) {
      }
    }
    return null;
  }

  private static boolean isSummaryOrMetadataLine(String line) {
    String norm = ExpenseSupport.toAsciiLower(line);
    return norm.contains("saldo poczatkowe")
        || norm.contains("saldo koncowe")
        || norm.contains("saldo otwarcia")
        || norm.contains("saldo zamkniecia")
        || norm.contains("opening balance")
        || norm.contains("closing balance")
        || norm.contains("suma obrotow")
        || norm.contains("suma obciazen")
        || norm.contains("suma uznan")
        || norm.contains("podsumowanie")
        || norm.contains("liczba operacji");
  }

  private static boolean isNoiseHeaderOrFooter(String line) {
    String norm = ExpenseSupport.toAsciiLower(line);
    return norm.startsWith("strona ")
        || norm.startsWith("page ")
        || norm.startsWith("data operacji")
        || norm.startsWith("wyciag ")
        || norm.startsWith("mbank s.a.");
  }

  private static String extractCurrencyFromText(String text) {
    if (text == null || text.isBlank()) {
      return null;
    }
    Matcher m = CURRENCY_CODE_PATTERN.matcher(text.toUpperCase(Locale.ROOT));
    if (m.find()) {
      return m.group(1);
    }
    if (text.contains("€")) {
      return "EUR";
    }
    if (text.contains("$")) {
      return "USD";
    }
    if (text.contains("£")) {
      return "GBP";
    }
    if (text.toLowerCase(Locale.ROOT).contains("zł")) {
      return "PLN";
    }
    return null;
  }

  private static String normalizeCurrencyToken(String raw, String fallback) {
    if (raw == null || raw.isBlank()) {
      return fallback;
    }
    String trimmed = raw.trim();
    return switch (trimmed.toLowerCase(Locale.ROOT)) {
      case "$", "usd" -> "USD";
      case "€", "eur" -> "EUR";
      case "£", "gbp" -> "GBP";
      case "zł", "zl", "pln" -> "PLN";
      default -> {
        String clean = trimmed.replaceAll("[^A-Za-z]", "").toUpperCase(Locale.ROOT);
        yield clean.length() == 3 ? clean : fallback;
      }
    };
  }

  private static ParsedTransactionRow buildRow(
      LocalDate txDate,
      LocalDate bookingDate,
      String rawDescription,
      BigDecimal amount,
      String currency) {
    String cleanedDesc = rawDescription == null ? "" : rawDescription.trim().replaceAll("\\s+", " ");
    if (cleanedDesc.isEmpty()) {
      cleanedDesc = "Bank transaction";
    }
    return new ParsedTransactionRow(
        txDate,
        bookingDate != null ? bookingDate : txDate,
        cleanedDesc,
        ExpenseSupport.scale2(amount),
        normalizeCurrencyToken(currency, "PLN"));
  }

  private static Integer findCol(Map<String, Integer> colIdx, String... candidates) {
    return findCol(colIdx, false, Set.of(), candidates);
  }

  private static Integer findCol(
      Map<String, Integer> colIdx,
      boolean skipDateKeys,
      Set<Integer> excludedIndices,
      String... candidates) {
    for (String candidate : candidates) {
      Integer exact = colIdx.get(candidate);
      if (exact != null && !excludedIndices.contains(exact)) {
        return exact;
      }
    }
    for (String candidate : candidates) {
      for (Map.Entry<String, Integer> entry : colIdx.entrySet()) {
        String key = entry.getKey();
        Integer idx = entry.getValue();
        if (excludedIndices.contains(idx)) {
          continue;
        }
        if (skipDateKeys && (key.contains("date") || key.contains("data"))) {
          continue;
        }
        if (key.contains(candidate)) {
          return idx;
        }
      }
    }
    return null;
  }

  private static Set<Integer> excluded(Integer... indices) {
    Set<Integer> set = new HashSet<>();
    for (Integer idx : indices) {
      if (idx != null) {
        set.add(idx);
      }
    }
    return set;
  }

  private static String getCell(List<String> row, Integer index) {
    if (index == null || index < 0 || index >= row.size()) {
      return "";
    }
    String value = row.get(index);
    return value != null ? value.trim() : "";
  }

  private static boolean isRowEmpty(List<String> row) {
    for (String cell : row) {
      if (cell != null && !cell.isBlank()) {
        return false;
      }
    }
    return true;
  }

  private static String combineParts(String... parts) {
    StringBuilder sb = new StringBuilder();
    for (String part : parts) {
      if (part == null || part.isBlank()) {
        continue;
      }
      String trimmed = part.trim();
      if (sb.isEmpty()) {
        sb.append(trimmed);
      } else if (!sb.toString().toLowerCase(Locale.ROOT).contains(trimmed.toLowerCase(Locale.ROOT))) {
        sb.append(" / ").append(trimmed);
      }
    }
    return sb.toString();
  }

  private static void appendSpace(StringBuilder sb, String part) {
    if (!sb.isEmpty()) {
      sb.append(' ');
    }
    sb.append(part);
  }

  private static String normalizeHeaderName(String header) {
    if (header == null) {
      return "";
    }
    String stripped = header.trim().replaceFirst("^#+", "").trim();
    return ExpenseSupport.toAsciiLower(stripped).replaceAll("\\s+", " ");
  }

  private static BankProfile resolveHintOrGeneric(BankProfile hint) {
    return (hint != null && hint != BankProfile.AUTO) ? hint : BankProfile.GENERIC;
  }

  private static Map<String, Integer> buildMonthMap() {
    Map<String, Integer> map = new LinkedHashMap<>();
    map.put("jan", 1); map.put("sty", 1);
    map.put("feb", 2); map.put("lut", 2);
    map.put("mar", 3);
    map.put("apr", 4); map.put("kwi", 4);
    map.put("may", 5); map.put("maj", 5);
    map.put("jun", 6); map.put("cze", 6);
    map.put("jul", 7); map.put("lip", 7);
    map.put("aug", 8); map.put("sie", 8);
    map.put("sep", 9); map.put("wrz", 9);
    map.put("oct", 10); map.put("paz", 10);
    map.put("nov", 11); map.put("lis", 11);
    map.put("dec", 12); map.put("gru", 12);
    return Map.copyOf(map);
  }

  private record Metadata(
      String currency,
      LocalDate periodStart,
      LocalDate periodEnd,
      BigDecimal openingBalance,
      BigDecimal closingBalance) {}

  private record HeaderInfo(
      int lineIndex,
      char delimiter,
      List<String> normalizedColumns,
      Map<String, Integer> columnIndex) {}

  private static final class PendingRow {
    private final String defaultCurrency;
    private LocalDate txDate;
    private LocalDate bookingDate;
    private final StringBuilder description = new StringBuilder();
    private BigDecimal amount;
    private String currency;
    private BigDecimal balance;
    private boolean allHaveRunningBalance = true;

    private PendingRow(String defaultCurrency) {
      this.defaultCurrency = defaultCurrency;
    }

    boolean hasDate() {
      return txDate != null;
    }

    boolean hasAmount() {
      return amount != null;
    }

    void reset(LocalDate txDate, LocalDate bookingDate) {
      this.txDate = txDate;
      this.bookingDate = bookingDate;
      this.description.setLength(0);
      this.amount = null;
      this.currency = null;
      this.balance = null;
    }

    void flush(List<ParsedTransactionRow> rows, List<BigDecimal> runningBalances) {
      if (txDate != null && amount != null) {
        rows.add(buildRow(
            txDate,
            bookingDate,
            description.toString(),
            amount,
            currency != null ? currency : defaultCurrency));
        if (balance != null) {
          runningBalances.add(balance);
        } else {
          allHaveRunningBalance = false;
        }
      }
      reset(null, null);
    }

    void applyMoneyMatches(String sourceText, List<MoneyMatch> moneyMatches) {
      MoneyMatch txMatch;
      if (moneyMatches.size() >= 2) {
        MoneyMatch secondLast = moneyMatches.get(moneyMatches.size() - 2);
        MoneyMatch last = moneyMatches.get(moneyMatches.size() - 1);
        String lastCurr = last.currency != null ? last.currency : defaultCurrency;
        if (secondLast.currency != null && !secondLast.currency.equalsIgnoreCase(lastCurr)) {
          txMatch = last;
          this.balance = null;
        } else {
          txMatch = secondLast;
          this.balance = last.amount;
        }
      } else {
        txMatch = moneyMatches.get(moneyMatches.size() - 1);
      }
      this.amount = txMatch.amount;
      this.currency = txMatch.currency != null ? txMatch.currency : defaultCurrency;
      String descPart = sourceText.substring(0, txMatch.startIndex).trim();
      if (!descPart.isEmpty()) {
        appendSpace(this.description, descPart);
      }
    }

    void appendDescription(String text) {
      if (!text.isEmpty()) {
        appendSpace(this.description, text);
      }
    }
  }

  private record MoneyMatch(int startIndex, BigDecimal amount, String currency) {}
}
