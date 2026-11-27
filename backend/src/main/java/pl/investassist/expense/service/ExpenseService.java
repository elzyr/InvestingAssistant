package pl.investassist.expense.service;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import pl.investassist.auth.entity.User;
import pl.investassist.auth.repository.UserRepository;
import pl.investassist.common.enums.Currency;
import pl.investassist.common.exception.ResourceNotFoundException;
import pl.investassist.expense.ExpenseSupport;
import pl.investassist.expense.analytics.AnalyticsTransaction;
import pl.investassist.expense.analytics.ExpenseAnalyticsEngine;
import pl.investassist.expense.analytics.ExpenseAnalyticsResult;
import pl.investassist.expense.analytics.RecurringChargeItem;
import pl.investassist.expense.classifier.ClassifiableRow;
import pl.investassist.expense.classifier.ClassifiedTransaction;
import pl.investassist.expense.classifier.ExpenseClassifier;
import pl.investassist.expense.classifier.MerchantNormalizer;
import pl.investassist.expense.classifier.MerchantRuleSnapshot;
import pl.investassist.expense.dto.request.CommitStatementRequest;
import pl.investassist.expense.dto.request.CommitTransactionItem;
import pl.investassist.expense.dto.request.ReclassifyExpensesRequest;
import pl.investassist.expense.dto.request.UpdateExpenseTransactionRequest;
import pl.investassist.expense.dto.response.ExpenseAnalyticsResponse;
import pl.investassist.expense.dto.response.ExpenseStatusResponse;
import pl.investassist.expense.dto.response.ExpenseTransactionResponse;
import pl.investassist.expense.dto.response.MerchantRuleResponse;
import pl.investassist.expense.dto.response.PreviewTransactionItem;
import pl.investassist.expense.dto.response.ReclassifyExpensesResponse;
import pl.investassist.expense.dto.response.StatementPreviewResponse;
import pl.investassist.expense.dto.response.StatementSummaryResponse;
import pl.investassist.expense.entity.ExpenseMerchantRule;
import pl.investassist.expense.entity.ExpenseStatement;
import pl.investassist.expense.entity.ExpenseTransaction;
import pl.investassist.expense.enums.BankProfile;
import pl.investassist.expense.enums.ClassificationSource;
import pl.investassist.expense.enums.ExpenseCategory;
import pl.investassist.expense.enums.FlowKind;
import pl.investassist.expense.enums.RuleMatchType;
import pl.investassist.expense.enums.StatementFormat;
import pl.investassist.expense.llm.ExpenseLlmPort;
import pl.investassist.expense.parser.IngestedStatement;
import pl.investassist.expense.parser.ParsedTransactionRow;
import pl.investassist.expense.parser.ReconciliationResult;
import pl.investassist.expense.parser.StatementIngestionService;
import pl.investassist.expense.parser.TransactionFingerprinter;
import pl.investassist.expense.repository.ExpenseMerchantRuleRepository;
import pl.investassist.expense.repository.ExpenseStatementRepository;
import pl.investassist.expense.repository.ExpenseTransactionRepository;
import pl.investassist.portfolio.port.MarketDataPort;

@Service
public class ExpenseService {

  private static final Logger log = LoggerFactory.getLogger(ExpenseService.class);
  private static final int FINGERPRINT_CHUNK_SIZE = 1000;

  private final UserRepository userRepository;
  private final ExpenseStatementRepository statementRepository;
  private final ExpenseTransactionRepository transactionRepository;
  private final ExpenseMerchantRuleRepository ruleRepository;
  private final StatementIngestionService ingestionService;
  private final TransactionFingerprinter fingerprinter;
  private final ExpenseClassifier classifier;
  private final ExpenseAnalyticsEngine analyticsEngine;
  private final ExpenseLlmPort llmPort;
  private final MarketDataPort marketDataPort;

  public ExpenseService(
      UserRepository userRepository,
      ExpenseStatementRepository statementRepository,
      ExpenseTransactionRepository transactionRepository,
      ExpenseMerchantRuleRepository ruleRepository,
      StatementIngestionService ingestionService,
      TransactionFingerprinter fingerprinter,
      ExpenseClassifier classifier,
      ExpenseAnalyticsEngine analyticsEngine,
      ExpenseLlmPort llmPort,
      MarketDataPort marketDataPort) {
    this.userRepository = userRepository;
    this.statementRepository = statementRepository;
    this.transactionRepository = transactionRepository;
    this.ruleRepository = ruleRepository;
    this.ingestionService = ingestionService;
    this.fingerprinter = fingerprinter;
    this.classifier = classifier;
    this.analyticsEngine = analyticsEngine;
    this.llmPort = llmPort;
    this.marketDataPort = marketDataPort;
  }

  @Transactional(readOnly = true)
  public ExpenseStatusResponse getStatus(UUID userId) {
    String baseCurrency = resolveBaseCurrency(userId);
    List<String> profiles = Arrays.stream(BankProfile.values()).map(Enum::name).toList();
    return new ExpenseStatusResponse(
        llmPort.isEnabled(),
        llmPort.providerName(),
        llmPort.modelName(),
        baseCurrency,
        StatementIngestionService.MAX_FILE_SIZE_BYTES,
        List.of(StatementFormat.PDF.name(), StatementFormat.CSV.name()),
        profiles);
  }

  public StatementPreviewResponse previewStatement(
      UUID userId,
      MultipartFile file,
      BankProfile bankProfile,
      boolean useAi,
      String accountLabel) {
    if (file == null || file.isEmpty()) {
      throw new IllegalArgumentException("error.expense_empty_file");
    }
    byte[] bytes;
    try {
      bytes = file.getBytes();
    } catch (IOException e) {
      throw new IllegalArgumentException("error.expense_empty_file", e);
    }

    String fileName = file.getOriginalFilename();
    IngestedStatement ingested = ingestionService.ingest(fileName, bytes, bankProfile);

    List<ParsedTransactionRow> rows = ingested.rows();
    ReconciliationResult reconciliation = ingested.reconciliation();
    List<String> warnings = new ArrayList<>(ingested.warnings());

    if (rows.isEmpty()) {
      throw new IllegalArgumentException("error.expense_no_transactions");
    }

    Optional<User> user = userRepository.findById(userId);
    String baseCurrency = user
        .map(User::getBaseCurrency)
        .map(Enum::name)
        .orElse(Currency.PLN.name());
    String accountOwnerName = user.map(ExpenseService::resolveOwnerName).orElse(null);
    List<String> fingerprints = fingerprinter.computeFingerprints(rows);
    Set<String> existingFingerprints = findExistingFingerprintsChunked(userId, fingerprints);
    boolean alreadyUploaded =
        statementRepository.existsByUserIdAndFileHashSha256(userId, ingested.fileHashSha256());

    List<MerchantRuleSnapshot> userRules = ruleRepository.findAllByUserIdOrderByUpdatedAtDesc(userId)
        .stream()
        .map(r -> new MerchantRuleSnapshot(r.getMatchType(), r.getMatchKey(), r.getCategory(), r.getFlowKind()))
        .toList();

    List<ClassifiableRow> classifiable = new ArrayList<>(rows.size());
    for (int i = 0; i < rows.size(); i++) {
      ParsedTransactionRow r = rows.get(i);
      classifiable.add(new ClassifiableRow(
          String.valueOf(i), r.txDate(), r.rawDescription(), r.amount(), r.currency()));
    }
    ExpenseClassifier.ClassificationBatchResult classification =
        classifier.classifyBatchWithMeta(classifiable, userRules, useAi, accountOwnerName);
    boolean aiUsed = classification.aiUsed();

    Map<String, MarketDataPort.FxSeries> fxMap = loadFxHistorySafe(rows, baseCurrency);

    List<PreviewTransactionItem> previewItems = new ArrayList<>(rows.size());
    int duplicateCount = 0;
    int needsReviewCount = 0;
    int fxDegradedCount = 0;

    for (int i = 0; i < rows.size(); i++) {
      ParsedTransactionRow row = rows.get(i);
      ClassifiedTransaction cls = classification.transactions().get(i);
      String fp = fingerprints.get(i);
      boolean duplicate = existingFingerprints.contains(fp);
      if (duplicate) {
        duplicateCount++;
      } else if (cls.needsReview()) {
        needsReviewCount++;
      }

      FxConversion fx = convertToBase(row.amount(), row.currency(), baseCurrency, row.txDate(), fxMap);
      if (fx.degraded) {
        fxDegradedCount++;
      }

      previewItems.add(new PreviewTransactionItem(
          String.valueOf(i),
          row.txDate(),
          row.bookingDate(),
          row.rawDescription(),
          cls.merchantKey(),
          cls.merchantDisplay(),
          cls.phraseKey(),
          cls.intermediary(),
          row.amount(),
          row.currency(),
          fx.baseAmount,
          baseCurrency,
          fx.rate,
          fx.degraded,
          cls.flowKind(),
          cls.category(),
          cls.source(),
          cls.confidence(),
          cls.needsReview(),
          duplicate,
          fp));
    }

    LocalDate periodStart = ingested.periodStart();
    LocalDate periodEnd = ingested.periodEnd();
    if (periodStart == null || periodEnd == null) {
      for (ParsedTransactionRow row : rows) {
        if (row.txDate() != null) {
          if (periodStart == null || row.txDate().isBefore(periodStart)) {
            periodStart = row.txDate();
          }
          if (periodEnd == null || row.txDate().isAfter(periodEnd)) {
            periodEnd = row.txDate();
          }
        }
      }
    }

    return new StatementPreviewResponse(
        ingested.fileName(),
        ingested.fileHashSha256(),
        alreadyUploaded,
        ingested.format(),
        ingested.detectedBankProfile(),
        ingested.currency(),
        baseCurrency,
        periodStart,
        periodEnd,
        reconciliation,
        aiUsed,
        classification.aiWarning(),
        fxDegradedCount,
        List.copyOf(warnings),
        duplicateCount,
        needsReviewCount,
        List.copyOf(previewItems));
  }

  @Transactional
  public StatementSummaryResponse commitStatement(UUID userId, CommitStatementRequest request) {
    List<CommitTransactionItem> items = request.transactions() != null ? request.transactions() : List.of();
    for (CommitTransactionItem item : items) {
      validateCommitItem(item);
    }
    List<String> candidateFingerprints = items.stream()
        .map(CommitTransactionItem::fingerprint)
        .filter(fp -> fp != null && !fp.isBlank())
        .toList();

    Set<String> existingFingerprints = findExistingFingerprintsChunked(userId, candidateFingerprints);
    Set<String> seenInBatch = new HashSet<>();

    List<CommitTransactionItem> toSave = new ArrayList<>();
    List<FlowKind> resolvedFlows = new ArrayList<>();
    int skippedDuplicates = 0;
    for (CommitTransactionItem item : items) {
      FlowKind resolvedFlow =
          ExpenseSupport.resolveFlowKind(item.category(), item.flowKind(), item.amount());
      String fp = item.fingerprint();
      if (existingFingerprints.contains(fp) || !seenInBatch.add(fp)) {
        skippedDuplicates++;
        continue;
      }
      toSave.add(item);
      resolvedFlows.add(resolvedFlow);
    }

    String fileHash = truncate(request.fileHashSha256(), 64);
    if (toSave.isEmpty() && !items.isEmpty() && fileHash != null && !fileHash.isBlank()) {
      Optional<ExpenseStatement> existingStmt =
          statementRepository.findFirstByUserIdAndFileHashSha256OrderByCreatedAtDesc(userId, fileHash);
      if (existingStmt.isPresent()) {
        ExpenseStatement st = existingStmt.get();
        return new StatementSummaryResponse(
            st.getId(),
            st.getFileName(),
            st.getFormat(),
            st.getBankProfile(),
            st.getAccountLabel(),
            st.getCurrency(),
            st.getPeriodStart(),
            st.getPeriodEnd(),
            st.getReconciliationStatus(),
            st.getReconciliationDelta(),
            0,
            skippedDuplicates,
            st.getCreatedAt() != null ? st.getCreatedAt().toInstant(ZoneOffset.UTC) : Instant.now());
      }
      if (statementRepository.existsByUserIdAndFileHashSha256(userId, fileHash)) {
        return new StatementSummaryResponse(
            null,
            truncate(request.fileName(), 255),
            request.format(),
            request.bankProfile(),
            truncate(request.accountLabel(), 120),
            truncate(Currency.normalizeCode(request.currency(), "PLN"), 8),
            request.periodStart(),
            request.periodEnd(),
            request.reconciliationStatus(),
            request.reconciliationDelta(),
            0,
            skippedDuplicates,
            Instant.now());
      }
    }

    ExpenseStatement statement = new ExpenseStatement();
    statement.setUserId(userId);
    statement.setFileName(truncate(request.fileName(), 255));
    statement.setFileHashSha256(fileHash);
    statement.setFormat(request.format());
    statement.setBankProfile(request.bankProfile());
    statement.setAccountLabel(truncate(request.accountLabel(), 120));
    statement.setCurrency(truncate(Currency.normalizeCode(request.currency(), "PLN"), 8));
    statement.setPeriodStart(request.periodStart());
    statement.setPeriodEnd(request.periodEnd());
    statement.setOpeningBalance(request.openingBalance());
    statement.setClosingBalance(request.closingBalance());
    statement.setCalculatedInflows(
        request.calculatedInflows() != null ? request.calculatedInflows() : BigDecimal.ZERO);
    statement.setCalculatedOutflows(
        request.calculatedOutflows() != null ? request.calculatedOutflows() : BigDecimal.ZERO);
    statement.setReconciliationStatus(request.reconciliationStatus());
    statement.setReconciliationDelta(request.reconciliationDelta());
    statement.setParsedCount(toSave.size());
    statement.setSkippedDuplicates(skippedDuplicates);

    ExpenseStatement savedStatement = statementRepository.save(statement);

    List<ExpenseTransaction> txEntities = new ArrayList<>(toSave.size());
    for (int i = 0; i < toSave.size(); i++) {
      CommitTransactionItem item = toSave.get(i);
      FlowKind flowKind = resolvedFlows.get(i);
      ExpenseTransaction tx = new ExpenseTransaction();
      tx.setUserId(userId);
      tx.setStatementId(savedStatement.getId());
      tx.setTxDate(item.txDate());
      tx.setBookingDate(item.bookingDate() != null ? item.bookingDate() : item.txDate());
      tx.setRawDescription(item.rawDescription());
      tx.setMerchantKey(truncate(item.merchantKey(), 160));
      tx.setMerchantDisplay(truncate(item.merchantDisplay(), 160));
      tx.setPhraseKey(truncate(item.phraseKey(), 160));
      tx.setAmount(ExpenseSupport.scale2(item.amount()));
      tx.setCurrency(truncate(Currency.normalizeCode(item.currency(), "PLN"), 8));
      tx.setBaseAmount(ExpenseSupport.scale2(item.baseAmount()));
      tx.setBaseCurrency(truncate(Currency.normalizeCode(item.baseCurrency(), "PLN"), 8));
      tx.setFxRate(item.fxRate() != null ? item.fxRate() : BigDecimal.ONE);
      tx.setFxDegraded(Boolean.TRUE.equals(item.fxDegraded()));
      tx.setFlowKind(flowKind);
      tx.setCategory(item.category());
      tx.setClassificationSource(item.classificationSource());
      tx.setConfidence(ExpenseSupport.scale2(item.confidence() != null ? item.confidence() : BigDecimal.ONE));
      tx.setRecurring(false);
      tx.setExcluded(Boolean.TRUE.equals(item.excluded()));
      tx.setFingerprint(truncate(item.fingerprint(), 64));
      tx.setUserNote(truncate(item.userNote(), 255));
      txEntities.add(tx);

      if (Boolean.TRUE.equals(item.rememberRule())) {
        RuleTarget target = resolveRuleTarget(
            item.merchantKey(), item.phraseKey(), item.merchantDisplay());
        persistLearnedRule(userId, target, item.category(), flowKind);
      }
    }

    if (!txEntities.isEmpty()) {
      transactionRepository.saveAll(txEntities);
    }

    return toStatementSummary(savedStatement);
  }

  @Transactional(readOnly = true)
  public List<StatementSummaryResponse> listStatements(UUID userId) {
    return statementRepository.findAllByUserIdOrderByCreatedAtDesc(userId).stream()
        .map(ExpenseService::toStatementSummary)
        .toList();
  }

  @Transactional
  public void deleteStatement(UUID userId, UUID statementId) {
    ExpenseStatement statement = statementRepository.findByIdAndUserId(statementId, userId)
        .orElseThrow(() -> new ResourceNotFoundException("error.resource_not_found"));
    statementRepository.delete(statement);
  }

  public ExpenseAnalyticsResponse getAnalytics(
      UUID userId,
      LocalDate from,
      LocalDate to,
      UUID statementId,
      ExpenseCategory category,
      boolean includeTransactions,
      String lang) {
    if (statementId != null) {
      statementRepository.findByIdAndUserId(statementId, userId)
          .orElseThrow(() -> new ResourceNotFoundException("error.resource_not_found"));
    }

    String baseCurrency = resolveBaseCurrency(userId);
    List<ExpenseTransaction> allUserTxs =
        transactionRepository.findAllByUserIdOrderByTxDateDescCreatedAtDesc(userId);

    List<ExpenseTransaction> periodTxs = new ArrayList<>();
    List<ExpenseTransaction> analyticsPeriodTxs = new ArrayList<>();
    for (ExpenseTransaction tx : allUserTxs) {
      if (statementId != null && !statementId.equals(tx.getStatementId())) {
        continue;
      }
      if (from != null && tx.getTxDate().isBefore(from)) {
        continue;
      }
      if (to != null && tx.getTxDate().isAfter(to)) {
        continue;
      }
      if (category != null && tx.getCategory() != category) {
        if (category != ExpenseCategory.REFUND && tx.getFlowKind() == FlowKind.REFUND) {
          analyticsPeriodTxs.add(tx);
        }
        continue;
      }
      periodTxs.add(tx);
      analyticsPeriodTxs.add(tx);
    }

    List<AnalyticsTransaction> periodInput = analyticsPeriodTxs.stream().map(ExpenseService::toAnalyticsTx).toList();
    List<AnalyticsTransaction> historyInput = allUserTxs.stream().map(ExpenseService::toAnalyticsTx).toList();

    ExpenseAnalyticsResult result =
        analyticsEngine.analyze(periodInput, historyInput, baseCurrency, from, to);

    List<ExpenseTransactionResponse> txResponses;
    if (includeTransactions) {
      Set<String> recurringKeys = new HashSet<>();
      for (RecurringChargeItem item : result.recurringCharges()) {
        recurringKeys.add(item.merchantKey().toLowerCase(Locale.ROOT));
      }
      txResponses = periodTxs.stream()
          .map(tx -> toTransactionResponse(
              tx, tx.isRecurring() || recurringKeys.contains(analyticsMerchantKey(tx))))
          .toList();
    } else {
      txResponses = List.of();
    }

    return new ExpenseAnalyticsResponse(
        result.baseCurrency(),
        result.periodStart(),
        result.periodEnd(),
        result.kpis(),
        result.categoryBreakdown(),
        result.topMerchants(),
        result.monthlyTrend(),
        result.recurringCharges(),
        result.outliers(),
        result.insights(),
        txResponses);
  }

  @Transactional
  public ExpenseTransactionResponse updateTransaction(
      UUID userId, UUID transactionId, UpdateExpenseTransactionRequest request) {
    ExpenseTransaction tx = transactionRepository.findByIdAndUserId(transactionId, userId)
        .orElseThrow(() -> new ResourceNotFoundException("error.resource_not_found"));

    FlowKind newFlowKind = ExpenseSupport.resolveFlowKind(
        request.category(), request.flowKind(), tx.getAmount());
    tx.setCategory(request.category());
    tx.setFlowKind(newFlowKind);
    tx.setClassificationSource(ClassificationSource.USER_OVERRIDE);
    tx.setConfidence(BigDecimal.ONE);
    if (request.excluded() != null) {
      tx.setExcluded(request.excluded());
    }
    if (request.userNote() != null) {
      String trimmedNote = request.userNote().trim();
      tx.setUserNote(trimmedNote.isEmpty() ? null : truncate(trimmedNote, 255));
    }

    ExpenseTransaction saved = transactionRepository.save(tx);

    RuleTarget ruleTarget = resolveRuleTarget(
        saved.getMerchantKey(), saved.getPhraseKey(), saved.getMerchantDisplay());

    if (Boolean.TRUE.equals(request.rememberRule()) && ruleTarget != null) {
      persistLearnedRule(userId, ruleTarget, saved.getCategory(), saved.getFlowKind());
    }

    if (Boolean.TRUE.equals(request.applyToMatching()) && ruleTarget != null) {
      List<ExpenseTransaction> matching = ruleTarget.matchType == RuleMatchType.PHRASE
          ? transactionRepository.findAllByUserIdAndPhraseKey(userId, ruleTarget.matchKey)
          : transactionRepository.findAllByUserIdAndMerchantKey(userId, ruleTarget.matchKey);
      List<ExpenseTransaction> updatedBatch = new ArrayList<>();
      int savedSign = saved.getAmount() != null ? saved.getAmount().signum() : 0;
      for (ExpenseTransaction other : matching) {
        if (other.getId().equals(saved.getId())) {
          continue;
        }
        int otherSign = other.getAmount() != null ? other.getAmount().signum() : 0;
        if ((savedSign != 0 && otherSign != savedSign)
            || !ExpenseSupport.isFlowCompatibleWithAmount(saved.getFlowKind(), other.getAmount())) {
          continue;
        }
        other.setCategory(saved.getCategory());
        other.setFlowKind(saved.getFlowKind());
        other.setClassificationSource(ClassificationSource.USER_RULE);
        other.setConfidence(BigDecimal.ONE);
        updatedBatch.add(other);
      }
      if (!updatedBatch.isEmpty()) {
        transactionRepository.saveAll(updatedBatch);
      }
    }

    return toTransactionResponse(saved, saved.isRecurring());
  }

  @Transactional(readOnly = true)
  public List<MerchantRuleResponse> listRules(UUID userId) {
    return ruleRepository.findAllByUserIdOrderByUpdatedAtDesc(userId).stream()
        .map(ExpenseService::toRuleResponse)
        .toList();
  }

  @Transactional
  public void deleteRule(UUID userId, UUID ruleId) {
    ExpenseMerchantRule rule = ruleRepository.findByIdAndUserId(ruleId, userId)
        .orElseThrow(() -> new ResourceNotFoundException("error.resource_not_found"));
    ruleRepository.delete(rule);
  }

  public ReclassifyExpensesResponse reclassifyTransactions(
      UUID userId, ReclassifyExpensesRequest request) {
    LocalDate from = request != null ? request.from() : null;
    LocalDate to = request != null ? request.to() : null;
    UUID statementId = request != null ? request.statementId() : null;
    boolean includeManualOverrides =
        request != null && Boolean.TRUE.equals(request.includeManualOverrides());

    if (statementId != null) {
      statementRepository.findByIdAndUserId(statementId, userId)
          .orElseThrow(() -> new ResourceNotFoundException("error.resource_not_found"));
    }

    List<ExpenseTransaction> allUserTxs =
        transactionRepository.findAllByUserIdOrderByTxDateDescCreatedAtDesc(userId);
    List<ExpenseTransaction> eligible = new ArrayList<>();
    for (ExpenseTransaction tx : allUserTxs) {
      if (statementId != null && !statementId.equals(tx.getStatementId())) {
        continue;
      }
      if (from != null && (tx.getTxDate() == null || tx.getTxDate().isBefore(from))) {
        continue;
      }
      if (to != null && (tx.getTxDate() == null || tx.getTxDate().isAfter(to))) {
        continue;
      }
      if (tx.isExcluded()) {
        continue;
      }
      if (!includeManualOverrides && tx.getClassificationSource() == ClassificationSource.USER_OVERRIDE) {
        continue;
      }
      eligible.add(tx);
    }

    if (eligible.isEmpty()) {
      return new ReclassifyExpensesResponse(0, 0, false, null);
    }

    String accountOwnerName =
        userRepository.findById(userId).map(ExpenseService::resolveOwnerName).orElse(null);
    List<MerchantRuleSnapshot> userRules = ruleRepository.findAllByUserIdOrderByUpdatedAtDesc(userId)
        .stream()
        .map(r -> new MerchantRuleSnapshot(
            r.getMatchType(), r.getMatchKey(), r.getCategory(), r.getFlowKind()))
        .toList();

    List<ClassifiableRow> classifiable = new ArrayList<>(eligible.size());
    for (int i = 0; i < eligible.size(); i++) {
      ExpenseTransaction tx = eligible.get(i);
      String rowId = tx.getId() != null ? tx.getId().toString() : String.valueOf(i);
      classifiable.add(new ClassifiableRow(
          rowId, tx.getTxDate(), tx.getRawDescription(), tx.getAmount(), tx.getCurrency()));
    }

    ExpenseClassifier.ClassificationBatchResult batch =
        classifier.classifyBatchWithMeta(classifiable, userRules, true, accountOwnerName);

    List<ExpenseTransaction> candidates = new ArrayList<>();
    for (int i = 0; i < eligible.size(); i++) {
      ExpenseTransaction tx = eligible.get(i);
      ClassifiedTransaction cls = batch.transactions().get(i);
      if (!ExpenseSupport.isFlowCompatibleWithAmount(cls.flowKind(), tx.getAmount())) {
        continue;
      }
      if (cls.source() == ClassificationSource.FALLBACK
          && (batch.aiWarning() != null
              || !batch.aiUsed()
              || tx.getClassificationSource() == ClassificationSource.LLM
              || tx.getClassificationSource() == ClassificationSource.USER_OVERRIDE
              || (tx.getClassificationSource() == ClassificationSource.FALLBACK
                  && tx.getCategory() == cls.category()
                  && tx.getFlowKind() == cls.flowKind()))) {
        continue;
      }

      String candidateMerchantKey = truncate(cls.merchantKey(), 160);
      String effectiveMerchantKey =
          candidateMerchantKey != null && !candidateMerchantKey.isBlank()
              ? candidateMerchantKey
              : tx.getMerchantKey();
      String candidateMerchantDisplay = truncate(cls.merchantDisplay(), 160);
      String effectiveMerchantDisplay =
          candidateMerchantDisplay != null && !candidateMerchantDisplay.isBlank()
              ? candidateMerchantDisplay
              : tx.getMerchantDisplay();
      String nextPhraseKey = truncate(cls.phraseKey(), 160);
      ExpenseCategory nextCategory = cls.category();
      FlowKind nextFlowKind = cls.flowKind();
      ClassificationSource nextSource = cls.source();
      BigDecimal nextConfidence = ExpenseSupport.scale2(cls.confidence());

      boolean changed = !Objects.equals(tx.getMerchantKey(), effectiveMerchantKey)
          || !Objects.equals(tx.getMerchantDisplay(), effectiveMerchantDisplay)
          || !Objects.equals(tx.getPhraseKey(), nextPhraseKey)
          || tx.getCategory() != nextCategory
          || tx.getFlowKind() != nextFlowKind
          || tx.getClassificationSource() != nextSource
          || tx.getConfidence() == null
          || tx.getConfidence().compareTo(nextConfidence) != 0;

      if (changed) {
        tx.setMerchantKey(effectiveMerchantKey);
        tx.setMerchantDisplay(effectiveMerchantDisplay);
        tx.setPhraseKey(nextPhraseKey);
        tx.setCategory(nextCategory);
        tx.setFlowKind(nextFlowKind);
        tx.setClassificationSource(nextSource);
        tx.setConfidence(nextConfidence);
        candidates.add(tx);
      }
    }

    List<ExpenseTransaction> toSave = filterConcurrentlyOverridden(candidates, includeManualOverrides);
    if (!toSave.isEmpty()) {
      transactionRepository.saveAll(toSave);
    }

    return new ReclassifyExpensesResponse(
        eligible.size(), toSave.size(), batch.aiUsed(), batch.aiWarning());
  }

  private List<ExpenseTransaction> filterConcurrentlyOverridden(
      List<ExpenseTransaction> candidates, boolean includeManualOverrides) {
    if (candidates.isEmpty() || includeManualOverrides) {
      return candidates;
    }
    List<UUID> ids = candidates.stream()
        .map(ExpenseTransaction::getId)
        .filter(Objects::nonNull)
        .toList();
    if (ids.isEmpty()) {
      return candidates;
    }
    Map<UUID, ExpenseTransaction> latestById = new HashMap<>();
    for (int i = 0; i < ids.size(); i += FINGERPRINT_CHUNK_SIZE) {
      List<UUID> chunk = ids.subList(i, Math.min(i + FINGERPRINT_CHUNK_SIZE, ids.size()));
      Iterable<ExpenseTransaction> found = transactionRepository.findAllById(chunk);
      if (found != null) {
        for (ExpenseTransaction latest : found) {
          latestById.put(latest.getId(), latest);
        }
      }
    }
    if (latestById.isEmpty()) {
      return candidates;
    }
    List<ExpenseTransaction> filtered = new ArrayList<>(candidates.size());
    for (ExpenseTransaction candidate : candidates) {
      ExpenseTransaction latest = candidate.getId() != null ? latestById.get(candidate.getId()) : null;
      if (latest != null && latest.getClassificationSource() == ClassificationSource.USER_OVERRIDE) {
        continue;
      }
      filtered.add(candidate);
    }
    return filtered;
  }

  private Set<String> findExistingFingerprintsChunked(UUID userId, List<String> fingerprints) {
    if (fingerprints == null || fingerprints.isEmpty()) {
      return Set.of();
    }
    Set<String> existing = new HashSet<>();
    for (int i = 0; i < fingerprints.size(); i += FINGERPRINT_CHUNK_SIZE) {
      List<String> chunk = fingerprints.subList(i, Math.min(i + FINGERPRINT_CHUNK_SIZE, fingerprints.size()));
      Set<String> chunkMatches = transactionRepository.findExistingFingerprints(userId, chunk);
      if (chunkMatches != null) {
        existing.addAll(chunkMatches);
      }
    }
    return existing;
  }

  private static void validateCommitItem(CommitTransactionItem item) {
    if (item == null || item.txDate() == null) {
      throw new IllegalArgumentException("Transaction date is required");
    }
    if (item.amount() == null || item.amount().compareTo(BigDecimal.ZERO) == 0) {
      throw new IllegalArgumentException("Transaction amount must be non-zero");
    }
    if (item.baseAmount() == null || item.baseAmount().signum() != item.amount().signum()) {
      throw new IllegalArgumentException("Base amount must match transaction amount sign");
    }
    if (item.fxRate() == null || item.fxRate().compareTo(BigDecimal.ZERO) <= 0) {
      throw new IllegalArgumentException("FX rate must be positive");
    }
    if (!isThreeLetterCurrency(item.currency()) || !isThreeLetterCurrency(item.baseCurrency())) {
      throw new IllegalArgumentException("Currency must be a 3-letter code");
    }
  }

  private static boolean isThreeLetterCurrency(String code) {
    if (code == null) {
      return false;
    }
    String trimmed = code.trim();
    return trimmed.length() == 3
        && Character.isLetter(trimmed.charAt(0))
        && Character.isLetter(trimmed.charAt(1))
        && Character.isLetter(trimmed.charAt(2));
  }

  private void persistLearnedRule(
      UUID userId, RuleTarget target, ExpenseCategory category, FlowKind flowKind) {
    if (target == null || category == null || flowKind == null) {
      return;
    }
    FlowKind ruleFlowKind = flowKind;
    if (ruleFlowKind == FlowKind.REFUND) {
      if (!category.isConsumption()) {
        return;
      }
      ruleFlowKind = category.defaultFlowKind();
    }
    ruleRepository.upsertRule(
        UUID.randomUUID(),
        userId,
        target.matchType.name(),
        truncate(target.matchKey, 160),
        truncate(target.displayLabel, 160),
        category.name(),
        ruleFlowKind.name(),
        ClassificationSource.USER_OVERRIDE.name());
  }

  private static RuleTarget resolveRuleTarget(
      String merchantKey, String phraseKey, String merchantDisplay) {
    String cleanMerchant = merchantKey != null ? merchantKey.trim().toLowerCase(Locale.ROOT) : "";
    String cleanPhrase = phraseKey != null ? phraseKey.trim().toLowerCase(Locale.ROOT) : "";
    String display = merchantDisplay != null && !merchantDisplay.isBlank()
        ? merchantDisplay.trim()
        : (!cleanPhrase.isEmpty() ? cleanPhrase : cleanMerchant);

    if (MerchantNormalizer.isGatewayKey(cleanMerchant)) {
      if (!cleanPhrase.isEmpty() && !MerchantNormalizer.isGatewayKey(cleanPhrase)) {
        return new RuleTarget(RuleMatchType.PHRASE, cleanPhrase, display);
      }
      return null;
    }
    if (!cleanMerchant.isEmpty() && !cleanMerchant.equals("unknown")) {
      return new RuleTarget(RuleMatchType.MERCHANT, cleanMerchant, display);
    }
    if (!cleanPhrase.isEmpty() && !MerchantNormalizer.isGatewayKey(cleanPhrase)) {
      return new RuleTarget(RuleMatchType.PHRASE, cleanPhrase, display);
    }
    return null;
  }

  private Map<String, MarketDataPort.FxSeries> loadFxHistorySafe(
      List<ParsedTransactionRow> rows, String baseCurrency) {
    if (marketDataPort == null || rows.isEmpty() || !isSupportedCurrency(baseCurrency)) {
      return Map.of();
    }
    Set<MarketDataPort.CurrencyPair> pairs = new LinkedHashSet<>();
    LocalDate minDate = null;
    LocalDate maxDate = null;
    for (ParsedTransactionRow row : rows) {
      String curr = Currency.normalizeCode(row.currency(), baseCurrency);
      if (!curr.equalsIgnoreCase(baseCurrency) && isSupportedCurrency(curr)) {
        pairs.add(new MarketDataPort.CurrencyPair(curr, baseCurrency));
        if (row.txDate() != null) {
          if (minDate == null || row.txDate().isBefore(minDate)) {
            minDate = row.txDate();
          }
          if (maxDate == null || row.txDate().isAfter(maxDate)) {
            maxDate = row.txDate();
          }
        }
      }
    }
    if (pairs.isEmpty()) {
      return Map.of();
    }
    LocalDate start = minDate != null ? minDate.minusDays(7) : LocalDate.now().minusMonths(1);
    LocalDate end = maxDate != null ? maxDate : LocalDate.now();
    try {
      Map<String, MarketDataPort.FxSeries> fetched =
          marketDataPort.getBatchFxHistory(pairs, start, end);
      return fetched != null ? fetched : Map.of();
    } catch (Exception e) {
      log.warn("FX history lookup failed, marking non-base transactions as fxDegraded: {}", e.getMessage());
      return Map.of();
    }
  }

  private static FxConversion convertToBase(
      BigDecimal amount,
      String txCurrency,
      String baseCurrency,
      LocalDate txDate,
      Map<String, MarketDataPort.FxSeries> fxMap) {
    String normCurr = Currency.normalizeCode(txCurrency, baseCurrency);
    if (normCurr.equalsIgnoreCase(baseCurrency)) {
      return new FxConversion(ExpenseSupport.scale2(amount), BigDecimal.ONE, false);
    }
    String pairKey = normCurr + ":" + baseCurrency;
    MarketDataPort.FxSeries series = fxMap.get(pairKey);
    if (series != null) {
      BigDecimal rate = series.resolveRate(txDate, true);
      if (rate != null && rate.compareTo(BigDecimal.ZERO) > 0) {
        BigDecimal converted = ExpenseSupport.scale2(amount.multiply(rate));
        return new FxConversion(converted, rate, false);
      }
    }
    return new FxConversion(ExpenseSupport.scale2(amount), BigDecimal.ONE, true);
  }

  private static boolean isSupportedCurrency(String code) {
    if (code == null || code.isBlank()) {
      return false;
    }
    try {
      Currency.valueOf(code.trim().toUpperCase(Locale.ROOT));
      return true;
    } catch (IllegalArgumentException ignored) {
      return false;
    }
  }

  private String resolveBaseCurrency(UUID userId) {
    return userRepository.findById(userId)
        .map(User::getBaseCurrency)
        .map(Enum::name)
        .orElse(Currency.PLN.name());
  }

  private static String resolveOwnerName(User user) {
    if (user == null) {
      return null;
    }
    String first = user.getFirstName() != null ? user.getFirstName().trim() : "";
    String last = user.getLastName() != null ? user.getLastName().trim() : "";
    if (first.isEmpty() && last.isEmpty()) {
      return null;
    }
    if (first.isEmpty()) {
      return last;
    }
    if (last.isEmpty()) {
      return first;
    }
    return first + " " + last;
  }

  private static AnalyticsTransaction toAnalyticsTx(ExpenseTransaction tx) {
    return new AnalyticsTransaction(
        tx.getId(),
        tx.getTxDate(),
        analyticsMerchantKey(tx),
        tx.getMerchantDisplay(),
        tx.getBaseAmount(),
        tx.getFlowKind(),
        tx.getCategory(),
        tx.isExcluded(),
        tx.isFxDegraded(),
        needsReview(tx));
  }

  private static String analyticsMerchantKey(ExpenseTransaction tx) {
    String merchantKey = tx.getMerchantKey() != null
        ? tx.getMerchantKey().trim().toLowerCase(Locale.ROOT)
        : "";
    String phraseKey = tx.getPhraseKey() != null
        ? tx.getPhraseKey().trim().toLowerCase(Locale.ROOT)
        : "";
    if (MerchantNormalizer.isGatewayKey(merchantKey) && !phraseKey.isEmpty()) {
      return merchantKey + ":" + phraseKey;
    }
    return merchantKey;
  }

  private static boolean needsReview(ExpenseTransaction tx) {
    return ExpenseSupport.needsReview(
        tx.getCategory(), tx.getClassificationSource(), tx.getConfidence());
  }

  private static StatementSummaryResponse toStatementSummary(ExpenseStatement st) {
    return new StatementSummaryResponse(
        st.getId(),
        st.getFileName(),
        st.getFormat(),
        st.getBankProfile(),
        st.getAccountLabel(),
        st.getCurrency(),
        st.getPeriodStart(),
        st.getPeriodEnd(),
        st.getReconciliationStatus(),
        st.getReconciliationDelta(),
        st.getParsedCount(),
        st.getSkippedDuplicates(),
        st.getCreatedAt() != null ? st.getCreatedAt().toInstant(ZoneOffset.UTC) : null);
  }

  private static ExpenseTransactionResponse toTransactionResponse(
      ExpenseTransaction tx, boolean isRecurring) {
    return new ExpenseTransactionResponse(
        tx.getId(),
        tx.getStatementId(),
        tx.getTxDate(),
        tx.getBookingDate(),
        tx.getRawDescription(),
        tx.getMerchantKey(),
        tx.getMerchantDisplay(),
        tx.getPhraseKey(),
        tx.getAmount(),
        tx.getCurrency(),
        tx.getBaseAmount(),
        tx.getBaseCurrency(),
        tx.getFxRate(),
        tx.isFxDegraded(),
        tx.getFlowKind(),
        tx.getCategory(),
        tx.getClassificationSource(),
        tx.getConfidence(),
        needsReview(tx),
        isRecurring,
        tx.isExcluded(),
        tx.getUserNote());
  }

  private static MerchantRuleResponse toRuleResponse(ExpenseMerchantRule rule) {
    return new MerchantRuleResponse(
        rule.getId(),
        rule.getMatchType(),
        rule.getMatchKey(),
        rule.getDisplayLabel(),
        rule.getCategory(),
        rule.getFlowKind(),
        rule.getSource(),
        rule.getHitCount(),
        rule.getUpdatedAt() != null ? rule.getUpdatedAt().toInstant(ZoneOffset.UTC) : null);
  }

  private static String truncate(String val, int max) {
    if (val == null) {
      return null;
    }
    String trimmed = val.trim();
    return trimmed.length() > max ? trimmed.substring(0, max) : trimmed;
  }

  private record FxConversion(BigDecimal baseAmount, BigDecimal rate, boolean degraded) {}

  private record RuleTarget(RuleMatchType matchType, String matchKey, String displayLabel) {}
}
