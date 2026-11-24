package pl.investassist.expense.classifier;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;
import pl.investassist.expense.ExpenseSupport;
import pl.investassist.expense.enums.ExpenseCategory;

@Component
public class MerchantNormalizer {

  private static final Pattern OPF_FULL_BLOCK = Pattern.compile(
      "/opf/[a-z0-9]+/[^/]+/[^/]+/\\d+/\\.?[a-z]{2}(?:/|\\b)",
      Pattern.CASE_INSENSITIVE);

  private static final Pattern OPF_ACCOUNT_PART = Pattern.compile(
      "/opf/[a-z0-9]+/[^/\\s]+/?",
      Pattern.CASE_INSENSITIVE);

  private static final Pattern OPF_TAIL_PART = Pattern.compile(
      "/\\d+/\\.?[a-z]{2}(?:/|\\b)",
      Pattern.CASE_INSENSITIVE);

  private static final Pattern IBAN_OR_NRB_PATTERN = Pattern.compile(
      "\\bpl\\s*\\d{2}(?:[\\s-]?\\d{4}){6}\\b|"
          + "\\bpl\\d{26}\\b|"
          + "\\b[a-z]{2}\\d{2}(?:[\\s-]?\\d{4}){3,6}(?:[\\s-]?\\d{1,4})?\\b|"
          + "\\b\\d{2}(?:[\\s-]?\\d{4}){6}\\b|"
          + "\\b\\d{26}\\b",
      Pattern.CASE_INSENSITIVE);

  private static final Pattern BLIK_PARTIES_PATTERN = Pattern.compile(
      "\\b(?:od|do)\\s*:\\s*[+\\d*x]+(?:[ -][+\\d*x]+)*",
      Pattern.CASE_INSENSITIVE);

  private static final Pattern BANK_NOISE_PATTERN = Pattern.compile(
      "\\b(?:"
          + "zakup przy uzyciu karty(?:\\s*[-:/]+\\s*internet)?|"
          + "platnosc karta(?:\\s*[-:/]+\\s*internet)?|"
          + "transakcja karta(?:\\s*[-:/]+\\s*internet)?|"
          + "zakup karta(?:\\s*[-:/]+\\s*internet)?|"
          + "obciazenie karta(?:\\s*[-:/]+\\s*internet)?|"
          + "przelew na telefon(?:\\s+blik)?|"
          + "blik\\s+p2p(?:[\\s-]+(?:przychodzacy|wychodzacy))?|"
          + "przelew\\s+(?:zewnetrzny|wewnetrzny)(?:\\s+(?:przychodzacy|wychodzacy))?|"
          + "przelew\\s+(?:przychodzacy|wychodzacy)|"
          + "zlecenie stale|polecenie zaplaty|"
          + "card payment|pos purchase|contactless payment"
          + ")\\b",
      Pattern.CASE_INSENSITIVE);

  private static final Pattern WL_PREFIX_PATTERN = Pattern.compile("\\bwl\\s*\\*\\s*", Pattern.CASE_INSENSITIVE);
  private static final Pattern GOOGLE_PREFIX_PATTERN = Pattern.compile("\\bgoogle\\s*\\*\\s*", Pattern.CASE_INSENSITIVE);
  private static final Pattern GOOGLE_DUP_PATTERN = Pattern.compile("\\bgoogle\\s+google\\b", Pattern.CASE_INSENSITIVE);

  private static final Pattern INLINE_METADATA_PATTERN = Pattern.compile(
      "(?:data transakcji\\s*:?\\s*\\d{2,4}[-./]\\d{2}[-./]\\d{2,4}|"
          + "nr karty\\s*:?\\s*[\\d*xX\\s-]+|"
          + "terminal\\s+\\w+|"
          + "\\b\\d{2}-\\d{3}\\b|"
          + "\\b(?:sp\\.?\\s*z\\s*o\\.?\\s*o\\.?|s\\.?\\s*a\\.?|sp\\.?\\s*k\\.?|sp\\.?\\s*j\\.?|ltd\\.?|gmbh|inc\\.?|llc|b\\.?v\\.?)\\b)",
      Pattern.CASE_INSENSITIVE);

  private static final Pattern TRAILING_STORE_OR_CITY = Pattern.compile(
      "\\b(?:\\d{3,6}|warszawa|krakow|wroclaw|gdansk|poznan|lodz|katowice|szczecin|lublin|bydgoszcz|gdynia|bialystok|(?<!\\.)pl|pol)\\b",
      Pattern.CASE_INSENSITIVE);

  private static final Pattern WHITESPACE_COLLAPSE = Pattern.compile("\\s+");
  private static final Pattern EDGE_SEPARATOR_PREFIX = Pattern.compile("^[:\\-/\\s]+");
  private static final Pattern EDGE_SEPARATOR_SUFFIX = Pattern.compile("[:\\-/\\s]+$");
  private static final Pattern SUBTEXT_EDGE_PREFIX = Pattern.compile("^[/\\-*|:;.,\\s]+");
  private static final Pattern SUBTEXT_EDGE_SUFFIX = Pattern.compile("[/\\-*|:;.,\\s]+$");
  private static final Pattern SUBTEXT_SEPARATORS = Pattern.compile("[/\\*|:;]+");
  private static final Pattern PHRASE_NON_ALNUM = Pattern.compile("[^a-z0-9\\s]");
  private static final Pattern LONG_DIGIT_TOKEN = Pattern.compile("\\b\\d{4,}\\b");
  private static final Pattern STRAY_DOT = Pattern.compile("(?<![a-z0-9])\\.|\\.(?![a-z0-9])");
  private static final Pattern CORE_NON_ALLOWED = Pattern.compile("[^a-z0-9\\s&.+]");
  private static final Pattern SLUG_NON_ALNUM = Pattern.compile("[^a-z0-9]+");
  private static final Pattern SLUG_EDGE_UNDERSCORES = Pattern.compile("^_+|_+$");
  private static final Pattern NON_ALPHA = Pattern.compile("[^a-z]+");

  private static final List<String> INVOICE_OR_TAX_CONTEXT = List.of(
      "faktura", "kwota netto", "wartosc netto", "wynagrodzenie", "brutto", "vat");

  private static final List<GatewayRule> GATEWAYS = List.of(
      new GatewayRule("przelewy24", "Przelewy24", Pattern.compile("\\b(?:przelewy24|p24|paypro)\\b")),
      new GatewayRule("payu", "PayU", Pattern.compile("\\bpayu\\b")),
      new GatewayRule("tpay", "Tpay", Pattern.compile("\\b(?:tpay|krajowy integrator platnosci)\\b")),
      new GatewayRule("autopay", "Autopay", Pattern.compile("\\b(?:autopay|blue media)\\b")),
      new GatewayRule("paypal", "PayPal", Pattern.compile("\\bpaypal\\b")),
      new GatewayRule("blik", "BLIK", Pattern.compile("\\b(?:transakcja blik|platnosc blik|blik)\\b")),
      new GatewayRule("apple", "Apple", Pattern.compile("\\bapple\\.com/bill\\b|\\bapple com bill\\b")),
      new GatewayRule("stripe", "Stripe", Pattern.compile("\\bstripe\\b|\\bsq\\s*\\*|\\bsumup\\s*\\*|\\bzettle\\b")),
      new GatewayRule("klarna", "Klarna", Pattern.compile("\\bklarna\\b")));

  private static final Set<String> GATEWAY_KEYS = Set.of(
      "payu", "przelewy24", "tpay", "autopay", "paypal", "blik", "apple", "stripe", "klarna");

  private static final List<CanonicalBrand> CANONICAL_BRANDS = buildCanonicalBrands();

  public static boolean isGatewayKey(String key) {
    return key != null && GATEWAY_KEYS.contains(key.trim().toLowerCase(Locale.ROOT));
  }

  public NormalizedMerchant normalize(String rawDescription) {
    if (rawDescription == null || rawDescription.isBlank()) {
      return new NormalizedMerchant("unknown", "Unknown", null, false, "");
    }

    String ascii = ExpenseSupport.toAsciiLower(rawDescription);
    String stripped = stripNoise(ascii);

    GatewayRule detectedGateway = null;
    String subText = stripped;
    for (GatewayRule gateway : GATEWAYS) {
      Matcher matcher = gateway.pattern.matcher(stripped);
      if (matcher.find()) {
        detectedGateway = gateway;
        subText = matcher.replaceAll(" ");
        subText = cleanSubText(subText);
        break;
      }
    }

    String matchTarget = detectedGateway != null && !subText.isEmpty() ? subText : stripped;
    CanonicalBrand brand = findCanonicalBrand(matchTarget);
    if (brand == null && detectedGateway == null) {
      brand = findCanonicalBrand(ascii);
    }

    if (detectedGateway != null) {
      String phraseKey = derivePhraseKey(subText);
      if (brand != null) {
        return new NormalizedMerchant(
            brand.key,
            brand.display,
            phraseKey.isEmpty() ? brand.key : phraseKey,
            true,
            stripped);
      }
      String display = !phraseKey.isEmpty() ? toTitleCase(phraseKey) : detectedGateway.display;
      return new NormalizedMerchant(
          detectedGateway.key,
          display,
          phraseKey.isEmpty() ? null : phraseKey,
          true,
          stripped);
    }

    if (brand != null) {
      String phraseKey = derivePhraseKey(stripped);
      return new NormalizedMerchant(
          brand.key,
          brand.display,
          phraseKey.isEmpty() ? brand.key : phraseKey,
          false,
          stripped);
    }

    String cleanedCore = deriveMerchantCore(stripped);
    String merchantKey = toSlugKey(cleanedCore);
    if (merchantKey.isEmpty()) {
      merchantKey = "unknown";
    }
    String display = cleanedCore.isEmpty() ? "Unknown" : toTitleCase(cleanedCore);
    String phraseKey = derivePhraseKey(stripped);
    return new NormalizedMerchant(
        merchantKey,
        display,
        phraseKey.isEmpty() ? merchantKey : phraseKey,
        false,
        stripped);
  }

  public ExpenseCategory lookupBrandCategory(String merchantKey) {
    if (merchantKey == null || merchantKey.isBlank()) {
      return null;
    }
    for (CanonicalBrand brand : CANONICAL_BRANDS) {
      if (brand.key.equals(merchantKey)) {
        return brand.category;
      }
    }
    return null;
  }

  private static String stripNoise(String asciiLower) {
    String s = WHITESPACE_COLLAPSE.matcher(asciiLower).replaceAll(" ").trim();
    s = stripOpfBlock(s);
    s = IBAN_OR_NRB_PATTERN.matcher(s).replaceAll(" ");
    s = BLIK_PARTIES_PATTERN.matcher(s).replaceAll(" ");
    s = BANK_NOISE_PATTERN.matcher(s).replaceAll(" ");
    s = INLINE_METADATA_PATTERN.matcher(s).replaceAll(" ");
    s = WL_PREFIX_PATTERN.matcher(s).replaceAll(" ");
    s = GOOGLE_PREFIX_PATTERN.matcher(s).replaceAll("google ");
    s = GOOGLE_DUP_PATTERN.matcher(s).replaceAll("google");
    s = EDGE_SEPARATOR_PREFIX.matcher(s).replaceAll("");
    s = EDGE_SEPARATOR_SUFFIX.matcher(s).replaceAll("");
    return WHITESPACE_COLLAPSE.matcher(s).replaceAll(" ").trim();
  }

  private static String stripOpfBlock(String s) {
    Matcher full = OPF_FULL_BLOCK.matcher(s);
    if (full.find()) {
      String before = NON_ALPHA.matcher(s.substring(0, full.start())).replaceAll("");
      if (before.length() >= 2) {
        return full.replaceAll(" ");
      }
    }
    String out = OPF_ACCOUNT_PART.matcher(s).replaceAll(" ");
    return OPF_TAIL_PART.matcher(out).replaceAll(" ");
  }

  private static String cleanSubText(String raw) {
    String s = SUBTEXT_EDGE_PREFIX.matcher(raw).replaceAll("");
    s = SUBTEXT_EDGE_SUFFIX.matcher(s).replaceAll("");
    s = SUBTEXT_SEPARATORS.matcher(s).replaceAll(" ");
    return WHITESPACE_COLLAPSE.matcher(s).replaceAll(" ").trim();
  }

  private static String derivePhraseKey(String text) {
    if (text == null || text.isBlank()) {
      return "";
    }
    String cleaned = PHRASE_NON_ALNUM.matcher(text).replaceAll(" ");
    cleaned = LONG_DIGIT_TOKEN.matcher(cleaned).replaceAll(" ");
    cleaned = WHITESPACE_COLLAPSE.matcher(cleaned).replaceAll(" ").trim();
    if (cleaned.length() > 140) {
      cleaned = cleaned.substring(0, 140).trim();
    }
    return cleaned;
  }

  private static String deriveMerchantCore(String stripped) {
    String core = TRAILING_STORE_OR_CITY.matcher(stripped).replaceAll(" ");
    core = STRAY_DOT.matcher(core).replaceAll(" ");
    core = CORE_NON_ALLOWED.matcher(core).replaceAll(" ");
    core = WHITESPACE_COLLAPSE.matcher(core).replaceAll(" ").trim();
    if (core.isEmpty()) {
      core = PHRASE_NON_ALNUM.matcher(stripped).replaceAll(" ");
      core = WHITESPACE_COLLAPSE.matcher(core).replaceAll(" ").trim();
    }
    String[] tokens = WHITESPACE_COLLAPSE.split(core);
    int maxTokens = Math.min(tokens.length, 4);
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < maxTokens; i++) {
      if (!tokens[i].isEmpty()) {
        if (!sb.isEmpty()) {
          sb.append(' ');
        }
        sb.append(tokens[i]);
      }
    }
    return sb.toString().trim();
  }

  private static String toSlugKey(String text) {
    String key = SLUG_NON_ALNUM.matcher(text.toLowerCase(Locale.ROOT)).replaceAll("_");
    key = SLUG_EDGE_UNDERSCORES.matcher(key).replaceAll("");
    return key.length() > 120 ? key.substring(0, 120) : key;
  }

  private static String toTitleCase(String text) {
    String[] words = WHITESPACE_COLLAPSE.split(text);
    StringBuilder sb = new StringBuilder();
    for (String w : words) {
      if (w.isEmpty()) {
        continue;
      }
      if (!sb.isEmpty()) {
        sb.append(' ');
      }
      sb.append(Character.toUpperCase(w.charAt(0)));
      if (w.length() > 1) {
        sb.append(w.substring(1));
      }
    }
    String out = sb.toString();
    return out.length() > 120 ? out.substring(0, 120) : out;
  }

  private static CanonicalBrand findCanonicalBrand(String text) {
    if (text == null || text.isBlank()) {
      return null;
    }
    for (CanonicalBrand brand : CANONICAL_BRANDS) {
      if (brand.key.equals("netto") && hasInvoiceOrTaxContext(text)) {
        continue;
      }
      for (String token : brand.patterns) {
        if (ExpenseSupport.containsWholePhrase(text, token)) {
          return brand;
        }
      }
    }
    return null;
  }

  private static boolean hasInvoiceOrTaxContext(String text) {
    for (String ctx : INVOICE_OR_TAX_CONTEXT) {
      if (ExpenseSupport.containsWholePhrase(text, ctx)) {
        return true;
      }
    }
    return false;
  }

  private static List<CanonicalBrand> buildCanonicalBrands() {
    Map<String, CanonicalBrand> brands = new LinkedHashMap<>();
    add(brands, "biedronka", "Biedronka", ExpenseCategory.GROCERIES,
        "biedronka", "jmp", "jeronimo martins");
    add(brands, "lidl", "Lidl", ExpenseCategory.GROCERIES, "lidl");
    add(brands, "zabka", "Żabka", ExpenseCategory.GROCERIES, "zabka");
    add(brands, "dino", "Dino", ExpenseCategory.GROCERIES, "dino market", "dino polska", "dino");
    add(brands, "kaufland", "Kaufland", ExpenseCategory.GROCERIES, "kaufland");
    add(brands, "auchan", "Auchan", ExpenseCategory.GROCERIES, "auchan");
    add(brands, "carrefour", "Carrefour", ExpenseCategory.GROCERIES, "carrefour");
    add(brands, "netto", "Netto", ExpenseCategory.GROCERIES, "netto");
    add(brands, "stokrotka", "Stokrotka", ExpenseCategory.GROCERIES, "stokrotka");
    add(brands, "lewiatan", "Lewiatan", ExpenseCategory.GROCERIES, "lewiatan");
    add(brands, "intermarche", "Intermarché", ExpenseCategory.GROCERIES, "intermarche");
    add(brands, "polomarket", "POLOmarket", ExpenseCategory.GROCERIES, "polomarket");
    add(brands, "aldi", "Aldi", ExpenseCategory.GROCERIES, "aldi");
    add(brands, "delikatesy_centrum", "Delikatesy Centrum", ExpenseCategory.GROCERIES, "delikatesy centrum");
    add(brands, "frisco", "Frisco", ExpenseCategory.GROCERIES, "frisco");
    add(brands, "leclerc", "E.Leclerc", ExpenseCategory.GROCERIES, "e.leclerc", "leclerc");
    add(brands, "makro", "Makro", ExpenseCategory.GROCERIES, "makro");
    add(brands, "selgros", "Selgros", ExpenseCategory.GROCERIES, "selgros");
    add(brands, "spolem", "Społem", ExpenseCategory.GROCERIES, "spolem");
    add(brands, "spar", "Spar", ExpenseCategory.GROCERIES, "spar");
    add(brands, "dealz", "Dealz", ExpenseCategory.GROCERIES, "dealz");
    add(brands, "groszek", "Groszek", ExpenseCategory.GROCERIES, "groszek");
    add(brands, "putka", "Piekarnia Putka", ExpenseCategory.GROCERIES, "putka");
    add(brands, "grzybki", "Piekarnia Grzybki", ExpenseCategory.GROCERIES, "piekarnia grzybki");
    add(brands, "rewe", "REWE", ExpenseCategory.GROCERIES, "rewe");
    add(brands, "edeka", "EDEKA", ExpenseCategory.GROCERIES, "edeka");
    add(brands, "tesco", "Tesco", ExpenseCategory.GROCERIES, "tesco");

    add(brands, "mcdonalds", "McDonald's", ExpenseCategory.DINING, "mcdonald", "mcdonalds");
    add(brands, "kfc", "KFC", ExpenseCategory.DINING, "kfc", "amrest kfc");
    add(brands, "burger_king", "Burger King", ExpenseCategory.DINING, "burger king");
    add(brands, "subway", "Subway", ExpenseCategory.DINING, "subway");
    add(brands, "pizza_hut", "Pizza Hut", ExpenseCategory.DINING, "pizza hut");
    add(brands, "dominos", "Domino's Pizza", ExpenseCategory.DINING, "domino s", "dominos");
    add(brands, "starbucks", "Starbucks", ExpenseCategory.DINING, "starbucks");
    add(brands, "costa_coffee", "Costa Coffee", ExpenseCategory.DINING, "costa coffee");
    add(brands, "green_caffe_nero", "Green Caffè Nero", ExpenseCategory.DINING, "green caffe nero", "caffe nero");
    add(brands, "etno_cafe", "Etno Cafe", ExpenseCategory.DINING, "etno cafe");
    add(brands, "max_burgers", "Max Burgers", ExpenseCategory.DINING, "max burgers");
    add(brands, "pasibus", "Pasibus", ExpenseCategory.DINING, "pasibus");
    add(brands, "north_fish", "North Fish", ExpenseCategory.DINING, "north fish");
    add(brands, "sphinx", "Sphinx", ExpenseCategory.DINING, "sphinx");
    add(brands, "da_grasso", "Da Grasso", ExpenseCategory.DINING, "da grasso");
    add(brands, "wolt", "Wolt", ExpenseCategory.DINING, "wolt");
    add(brands, "glovo", "Glovo", ExpenseCategory.DINING, "glovo");
    add(brands, "uber_eats", "Uber Eats", ExpenseCategory.DINING, "uber eats", "ubereats");
    add(brands, "pyszne", "Pyszne.pl", ExpenseCategory.DINING, "pyszne");
    add(brands, "bolt_food", "Bolt Food", ExpenseCategory.DINING, "bolt food");

    add(brands, "tauron", "Tauron", ExpenseCategory.UTILITIES, "tauron");
    add(brands, "pge", "PGE", ExpenseCategory.UTILITIES, "pge obrot", "pge dystrybucja", "pge");
    add(brands, "enea", "Enea", ExpenseCategory.UTILITIES, "enea");
    add(brands, "energa", "Energa", ExpenseCategory.UTILITIES, "energa");
    add(brands, "eon", "E.ON", ExpenseCategory.UTILITIES, "e.on", "eon polska", "innogy");
    add(brands, "pgnig", "PGNiG", ExpenseCategory.UTILITIES, "pgnig", "orlen gaz");
    add(brands, "veolia", "Veolia", ExpenseCategory.UTILITIES, "veolia");
    add(brands, "mpwik", "MPWiK", ExpenseCategory.UTILITIES, "mpwik");

    add(brands, "orange", "Orange", ExpenseCategory.TELECOM, "orange polska", "orange");
    add(brands, "play", "Play", ExpenseCategory.TELECOM, "p4 sp", "play polska");
    add(brands, "plus", "Plus", ExpenseCategory.TELECOM, "polkomtel");
    add(brands, "tmobile", "T-Mobile", ExpenseCategory.TELECOM, "t-mobile", "tmobile");
    add(brands, "vectra", "Vectra", ExpenseCategory.TELECOM, "vectra");
    add(brands, "upc", "UPC", ExpenseCategory.TELECOM, "upc polska");
    add(brands, "netia", "Netia", ExpenseCategory.TELECOM, "netia");
    add(brands, "inea", "Inea", ExpenseCategory.TELECOM, "inea");
    add(brands, "toya", "Toya", ExpenseCategory.TELECOM, "toya");

    add(brands, "ikea", "IKEA", ExpenseCategory.HOME_GARDEN, "ikea");
    add(brands, "castorama", "Castorama", ExpenseCategory.HOME_GARDEN, "castorama");
    add(brands, "leroy_merlin", "Leroy Merlin", ExpenseCategory.HOME_GARDEN, "leroy merlin");
    add(brands, "obi", "OBI", ExpenseCategory.HOME_GARDEN, "obi market", "obi");
    add(brands, "bricomarche", "Bricomarché", ExpenseCategory.HOME_GARDEN, "bricomarche");
    add(brands, "jysk", "JYSK", ExpenseCategory.HOME_GARDEN, "jysk");
    add(brands, "agata_meble", "Agata Meble", ExpenseCategory.HOME_GARDEN, "agata meble");
    add(brands, "homla", "Homla", ExpenseCategory.HOME_GARDEN, "homla");
    add(brands, "pepco", "Pepco", ExpenseCategory.HOME_GARDEN, "pepco");
    add(brands, "action", "Action", ExpenseCategory.HOME_GARDEN, "action poland", "action");

    add(brands, "orlen", "Orlen", ExpenseCategory.FUEL, "pkn orlen", "orlen");
    add(brands, "bp", "BP", ExpenseCategory.FUEL, "bp stacja", "bp europa", "stacja bp", "bp polska");
    add(brands, "shell", "Shell", ExpenseCategory.FUEL, "shell");
    add(brands, "circle_k", "Circle K", ExpenseCategory.FUEL, "circle k");
    add(brands, "moya", "MOYA", ExpenseCategory.FUEL, "moya stacja", "moya");
    add(brands, "amic", "AMIC Energy", ExpenseCategory.FUEL, "amic energy", "amic");
    add(brands, "mol", "MOL", ExpenseCategory.FUEL, "mol polska", "lotos");

    add(brands, "uber", "Uber", ExpenseCategory.TRANSPORT, "uber trip", "uber bv", "uber");
    add(brands, "bolt", "Bolt", ExpenseCategory.TRANSPORT, "bolt.eu", "bolt operations", "bolt");
    add(brands, "freenow", "FREE NOW", ExpenseCategory.TRANSPORT, "freenow", "free now");
    add(brands, "itaxi", "iTaxi", ExpenseCategory.TRANSPORT, "itaxi");
    add(brands, "pkp", "PKP Intercity", ExpenseCategory.TRANSPORT, "pkp intercity", "pkp");
    add(brands, "koleje_mazowieckie", "Koleje Mazowieckie", ExpenseCategory.TRANSPORT, "koleje mazowieckie");
    add(brands, "polregio", "Polregio", ExpenseCategory.TRANSPORT, "polregio");
    add(brands, "jakdojade", "Jakdojade", ExpenseCategory.TRANSPORT, "jakdojade");
    add(brands, "skycash", "SkyCash", ExpenseCategory.TRANSPORT, "skycash");
    add(brands, "mobilet", "moBILET", ExpenseCategory.TRANSPORT, "mobilet");
    add(brands, "koleo", "Koleo", ExpenseCategory.TRANSPORT, "koleo");
    add(brands, "flixbus", "FlixBus", ExpenseCategory.TRANSPORT, "flixbus");
    add(brands, "ztm", "ZTM", ExpenseCategory.TRANSPORT, "ztm warszawa", "ztm");
    add(brands, "mpk", "MPK", ExpenseCategory.TRANSPORT, "mpk");
    add(brands, "traficar", "Traficar", ExpenseCategory.TRANSPORT, "traficar");
    add(brands, "panek", "Panek", ExpenseCategory.TRANSPORT, "panek");
    add(brands, "lime", "Lime", ExpenseCategory.TRANSPORT, "lime scooter", "lime");

    add(brands, "etoll", "e-TOLL", ExpenseCategory.CAR_MAINTENANCE, "e-toll", "etoll");

    add(brands, "doz", "Apteka DOZ", ExpenseCategory.HEALTH, "doz apteka", "doz.pl", "doz");
    add(brands, "gemini_apteka", "Apteka Gemini", ExpenseCategory.HEALTH, "apteka gemini", "gemini apteka");
    add(brands, "superpharm", "Super-Pharm", ExpenseCategory.HEALTH, "super-pharm", "superpharm");
    add(brands, "ziko", "Ziko Apteka", ExpenseCategory.HEALTH, "ziko");
    add(brands, "drmax", "Dr.Max", ExpenseCategory.HEALTH, "dr.max", "drmax");
    add(brands, "luxmed", "LUX MED", ExpenseCategory.HEALTH, "luxmed", "lux med");
    add(brands, "medicover", "Medicover", ExpenseCategory.HEALTH, "medicover");
    add(brands, "enelmed", "Enel-Med", ExpenseCategory.HEALTH, "enel-med", "enelmed");
    add(brands, "znanylekarz", "ZnanyLekarz", ExpenseCategory.HEALTH, "znanylekarz", "znany lekarz");
    add(brands, "diagnostyka", "Diagnostyka", ExpenseCategory.HEALTH, "diagnostyka");
    add(brands, "alab", "ALAB Laboratoria", ExpenseCategory.HEALTH, "alab");
    add(brands, "synevo", "Synevo", ExpenseCategory.HEALTH, "synevo");
    add(brands, "vision_express", "Vision Express", ExpenseCategory.HEALTH, "vision express");
    add(brands, "fielmann", "Fielmann", ExpenseCategory.HEALTH, "fielmann");

    add(brands, "rossmann", "Rossmann", ExpenseCategory.BEAUTY_CARE, "rossmann");
    add(brands, "sephora", "Sephora", ExpenseCategory.BEAUTY_CARE, "sephora");
    add(brands, "douglas", "Douglas", ExpenseCategory.BEAUTY_CARE, "douglas");
    add(brands, "notino", "Notino", ExpenseCategory.BEAUTY_CARE, "notino");
    add(brands, "hebe", "Hebe", ExpenseCategory.BEAUTY_CARE, "hebe");

    add(brands, "netflix", "Netflix", ExpenseCategory.SUBSCRIPTIONS, "netflix");
    add(brands, "spotify", "Spotify", ExpenseCategory.SUBSCRIPTIONS, "spotify");
    add(brands, "youtube", "YouTube Premium", ExpenseCategory.SUBSCRIPTIONS, "youtube", "google youtube");
    add(brands, "hbo_max", "Max (HBO)", ExpenseCategory.SUBSCRIPTIONS, "hbo max", "hbomax", "max.com");
    add(brands, "disney_plus", "Disney+", ExpenseCategory.SUBSCRIPTIONS, "disney+", "disney plus");
    add(brands, "amazon_prime", "Amazon Prime", ExpenseCategory.SUBSCRIPTIONS, "amazon prime", "prime video");
    add(brands, "icloud", "Apple iCloud", ExpenseCategory.SUBSCRIPTIONS, "icloud", "apple one", "apple tv");
    add(brands, "canal_plus", "CANAL+", ExpenseCategory.SUBSCRIPTIONS, "canal+", "canal plus");
    add(brands, "player", "Player.pl", ExpenseCategory.SUBSCRIPTIONS, "player.pl");
    add(brands, "legimi", "Legimi", ExpenseCategory.SUBSCRIPTIONS, "legimi");
    add(brands, "audioteka", "Audioteka", ExpenseCategory.SUBSCRIPTIONS, "audioteka");
    add(brands, "storytel", "Storytel", ExpenseCategory.SUBSCRIPTIONS, "storytel");
    add(brands, "openai", "OpenAI / ChatGPT", ExpenseCategory.SUBSCRIPTIONS, "openai", "chatgpt");
    add(brands, "anthropic", "Claude (Anthropic)", ExpenseCategory.SUBSCRIPTIONS, "anthropic", "claude.ai");
    add(brands, "github", "GitHub", ExpenseCategory.SUBSCRIPTIONS, "github");
    add(brands, "jetbrains", "JetBrains", ExpenseCategory.SUBSCRIPTIONS, "jetbrains");
    add(brands, "microsoft_365", "Microsoft 365", ExpenseCategory.SUBSCRIPTIONS, "microsoft 365", "msft");
    add(brands, "adobe", "Adobe", ExpenseCategory.SUBSCRIPTIONS, "adobe");
    add(brands, "notion", "Notion", ExpenseCategory.SUBSCRIPTIONS, "notion");
    add(brands, "figma", "Figma", ExpenseCategory.SUBSCRIPTIONS, "figma");
    add(brands, "dropbox", "Dropbox", ExpenseCategory.SUBSCRIPTIONS, "dropbox");
    add(brands, "google_one", "Google One", ExpenseCategory.SUBSCRIPTIONS, "google one", "google storage");
    add(brands, "nordvpn", "NordVPN", ExpenseCategory.SUBSCRIPTIONS, "nordvpn");
    add(brands, "proton", "Proton", ExpenseCategory.SUBSCRIPTIONS, "proton ag", "protonmail");

    add(brands, "media_expert", "Media Expert", ExpenseCategory.ELECTRONICS, "media expert", "terg");
    add(brands, "rtv_euro_agd", "RTV Euro AGD", ExpenseCategory.ELECTRONICS, "rtv euro agd", "euro-net");
    add(brands, "media_markt", "MediaMarkt", ExpenseCategory.ELECTRONICS, "media markt", "mediamarkt");
    add(brands, "xkom", "x-kom", ExpenseCategory.ELECTRONICS, "x-kom", "xkom");
    add(brands, "morele", "Morele.net", ExpenseCategory.ELECTRONICS, "morele");
    add(brands, "komputronik", "Komputronik", ExpenseCategory.ELECTRONICS, "komputronik");

    add(brands, "multisport", "MultiSport", ExpenseCategory.FITNESS_SPORT, "multisport", "benefit systems");
    add(brands, "zdrofit", "Zdrofit", ExpenseCategory.FITNESS_SPORT, "zdrofit");
    add(brands, "cityfit", "CityFit", ExpenseCategory.FITNESS_SPORT, "cityfit");
    add(brands, "well_fitness", "Well Fitness", ExpenseCategory.FITNESS_SPORT, "well fitness");
    add(brands, "decathlon", "Decathlon", ExpenseCategory.FITNESS_SPORT, "decathlon");
    add(brands, "martes", "Martes Sport", ExpenseCategory.FITNESS_SPORT, "martes sport");
    add(brands, "4f", "4F", ExpenseCategory.FITNESS_SPORT, "otcf 4f", "4f sklep");

    add(brands, "zalando", "Zalando", ExpenseCategory.CLOTHING, "zalando");
    add(brands, "shein", "SHEIN", ExpenseCategory.CLOTHING, "shein");
    add(brands, "vinted", "Vinted", ExpenseCategory.CLOTHING, "vinted");
    add(brands, "nike", "Nike", ExpenseCategory.CLOTHING, "nike");
    add(brands, "adidas", "Adidas", ExpenseCategory.CLOTHING, "adidas");
    add(brands, "zara", "Zara", ExpenseCategory.CLOTHING, "zara");
    add(brands, "hm", "H&M", ExpenseCategory.CLOTHING, "h&m", "hennes mauritz");
    add(brands, "reserved", "Reserved", ExpenseCategory.CLOTHING, "reserved", "lpp reserved");
    add(brands, "sinsay", "Sinsay", ExpenseCategory.CLOTHING, "sinsay");
    add(brands, "cropp", "Cropp", ExpenseCategory.CLOTHING, "cropp");
    add(brands, "house", "House", ExpenseCategory.CLOTHING, "lpp house");
    add(brands, "mohito", "Mohito", ExpenseCategory.CLOTHING, "mohito");
    add(brands, "ccc", "CCC", ExpenseCategory.CLOTHING, "ccc obuwie", "ccc");
    add(brands, "eobuwie", "eobuwie", ExpenseCategory.CLOTHING, "eobuwie");
    add(brands, "halfprice", "HalfPrice", ExpenseCategory.CLOTHING, "halfprice");
    add(brands, "tkmaxx", "TK Maxx", ExpenseCategory.CLOTHING, "tk maxx", "tkmaxx");
    add(brands, "modivo", "Modivo", ExpenseCategory.CLOTHING, "modivo");
    add(brands, "answear", "Answear", ExpenseCategory.CLOTHING, "answear");

    add(brands, "allegro", "Allegro", ExpenseCategory.SHOPPING, "allegro");
    add(brands, "amazon", "Amazon", ExpenseCategory.SHOPPING, "amazon", "amzn");
    add(brands, "temu", "Temu", ExpenseCategory.SHOPPING, "temu");
    add(brands, "aliexpress", "AliExpress", ExpenseCategory.SHOPPING, "aliexpress");
    add(brands, "olx", "OLX", ExpenseCategory.SHOPPING, "olx");
    add(brands, "empik", "Empik", ExpenseCategory.SHOPPING, "empik");
    add(brands, "smyk", "Smyk", ExpenseCategory.SHOPPING, "smyk");

    add(brands, "helios", "Helios", ExpenseCategory.ENTERTAINMENT, "kino helios", "helios");
    add(brands, "cinema_city", "Cinema City", ExpenseCategory.ENTERTAINMENT, "cinema city");
    add(brands, "multikino", "Multikino", ExpenseCategory.ENTERTAINMENT, "multikino");
    add(brands, "eventim", "Eventim", ExpenseCategory.ENTERTAINMENT, "eventim");
    add(brands, "ebilet", "eBilet", ExpenseCategory.ENTERTAINMENT, "ebilet");
    add(brands, "going", "Going", ExpenseCategory.ENTERTAINMENT, "goingapp");
    add(brands, "ticketmaster", "Ticketmaster", ExpenseCategory.ENTERTAINMENT, "ticketmaster");
    add(brands, "steam", "Steam", ExpenseCategory.ENTERTAINMENT, "steampowered", "steam games", "steam");
    add(brands, "playstation", "PlayStation", ExpenseCategory.ENTERTAINMENT, "playstation", "sony interactive");
    add(brands, "xbox", "Xbox", ExpenseCategory.ENTERTAINMENT, "xbox");
    add(brands, "nintendo", "Nintendo", ExpenseCategory.ENTERTAINMENT, "nintendo");
    add(brands, "gog", "GOG.com", ExpenseCategory.ENTERTAINMENT, "gog.com");
    add(brands, "epic_games", "Epic Games", ExpenseCategory.ENTERTAINMENT, "epic games");

    add(brands, "zooplus", "Zooplus", ExpenseCategory.PETS, "zooplus");
    add(brands, "maxi_zoo", "Maxi Zoo", ExpenseCategory.PETS, "maxi zoo");

    add(brands, "booking", "Booking.com", ExpenseCategory.TRAVEL, "booking.com", "booking");
    add(brands, "airbnb", "Airbnb", ExpenseCategory.TRAVEL, "airbnb");
    add(brands, "ryanair", "Ryanair", ExpenseCategory.TRAVEL, "ryanair");
    add(brands, "wizzair", "Wizz Air", ExpenseCategory.TRAVEL, "wizz air", "wizzair");
    add(brands, "lot", "LOT Polish Airlines", ExpenseCategory.TRAVEL, "pll lot", "lot polish");
    add(brands, "lufthansa", "Lufthansa", ExpenseCategory.TRAVEL, "lufthansa");
    add(brands, "easyjet", "easyJet", ExpenseCategory.TRAVEL, "easyjet");
    add(brands, "klm", "KLM", ExpenseCategory.TRAVEL, "klm");
    add(brands, "wakacje_pl", "Wakacje.pl", ExpenseCategory.TRAVEL, "wakacje.pl");
    add(brands, "itaka", "Itaka", ExpenseCategory.TRAVEL, "itaka");
    add(brands, "tui", "TUI", ExpenseCategory.TRAVEL, "tui poland");

    add(brands, "udemy", "Udemy", ExpenseCategory.EDUCATION, "udemy");
    add(brands, "coursera", "Coursera", ExpenseCategory.EDUCATION, "coursera");
    add(brands, "duolingo", "Duolingo", ExpenseCategory.EDUCATION, "duolingo");
    add(brands, "preply", "Preply", ExpenseCategory.EDUCATION, "preply");
    add(brands, "helion", "Helion", ExpenseCategory.EDUCATION, "helion");
    add(brands, "bonito", "Bonito", ExpenseCategory.EDUCATION, "bonito");
    add(brands, "taniaksiazka", "TaniaKsiazka", ExpenseCategory.EDUCATION, "taniaksiazka");
    add(brands, "swiat_ksiazki", "Świat Książki", ExpenseCategory.EDUCATION, "swiat ksiazki");

    add(brands, "inpost", "InPost", ExpenseCategory.SERVICES_FEES, "inpost");
    add(brands, "dpd", "DPD", ExpenseCategory.SERVICES_FEES, "dpd polska", "dpd");
    add(brands, "dhl", "DHL", ExpenseCategory.SERVICES_FEES, "dhl");
    add(brands, "poczta_polska", "Poczta Polska", ExpenseCategory.SERVICES_FEES, "poczta polska");
    add(brands, "pzu", "PZU", ExpenseCategory.SERVICES_FEES, "pzu");
    add(brands, "warta", "Warta", ExpenseCategory.SERVICES_FEES, "tuir warta", "warta");
    add(brands, "allianz", "Allianz", ExpenseCategory.SERVICES_FEES, "allianz");
    add(brands, "link4", "Link4", ExpenseCategory.SERVICES_FEES, "link4");
    add(brands, "ergo_hestia", "ERGO Hestia", ExpenseCategory.SERVICES_FEES, "ergo hestia");
    add(brands, "generali", "Generali", ExpenseCategory.SERVICES_FEES, "generali");

    add(brands, "xtb", "XTB", ExpenseCategory.INVESTMENTS_SAVINGS, "xtb");
    add(brands, "bossa", "DM BOŚ (bossa.pl)", ExpenseCategory.INVESTMENTS_SAVINGS, "bossa", "dm bos");
    add(brands, "degiro", "DEGIRO", ExpenseCategory.INVESTMENTS_SAVINGS, "degiro");
    add(brands, "ibkr", "Interactive Brokers", ExpenseCategory.INVESTMENTS_SAVINGS, "interactive brokers", "ibkr");
    add(brands, "trading212", "Trading 212", ExpenseCategory.INVESTMENTS_SAVINGS, "trading 212", "trading212");
    add(brands, "finax", "Finax", ExpenseCategory.INVESTMENTS_SAVINGS, "finax");
    add(brands, "portu", "Portu", ExpenseCategory.INVESTMENTS_SAVINGS, "portu");
    add(brands, "inpzu", "inPZU", ExpenseCategory.INVESTMENTS_SAVINGS, "inpzu");
    add(brands, "obligacje", "Obligacje Skarbowe", ExpenseCategory.INVESTMENTS_SAVINGS,
        "obligacjeskarbowe", "obligacje skarbowe", "bm pko");

    return List.copyOf(brands.values());
  }

  private static void add(
      Map<String, CanonicalBrand> map,
      String key,
      String display,
      ExpenseCategory category,
      String... patterns) {
    map.put(key, new CanonicalBrand(key, display, category, List.of(patterns)));
  }

  private record GatewayRule(String key, String display, Pattern pattern) {}

  private record CanonicalBrand(
      String key, String display, ExpenseCategory category, List<String> patterns) {}
}
