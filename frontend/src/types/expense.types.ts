export type FlowKind =
  | 'EXPENSE'
  | 'INCOME'
  | 'INVESTMENT_SAVING'
  | 'INTERNAL_TRANSFER'
  | 'REFUND';

export type ExpenseCategory =
  | 'GROCERIES'
  | 'DINING'
  | 'HOUSING'
  | 'UTILITIES'
  | 'TELECOM'
  | 'HOME_GARDEN'
  | 'FUEL'
  | 'TRANSPORT'
  | 'CAR_MAINTENANCE'
  | 'HEALTH'
  | 'FITNESS_SPORT'
  | 'CLOTHING'
  | 'BEAUTY_CARE'
  | 'ELECTRONICS'
  | 'SHOPPING'
  | 'ENTERTAINMENT'
  | 'SUBSCRIPTIONS'
  | 'TRAVEL'
  | 'EDUCATION'
  | 'PETS'
  | 'GIFTS'
  | 'SERVICES_FEES'
  | 'OTHER_EXPENSE'
  | 'INCOME'
  | 'INVESTMENTS_SAVINGS'
  | 'INTERNAL_TRANSFER'
  | 'REFUND';

export type ExpenseViewMode = 'ANNUAL' | 'MONTH_DETAIL' | 'CUSTOM';

export type BankProfile =
  | 'AUTO'
  | 'MBANK'
  | 'REVOLUT'
  | 'WISE'
  | 'PKO_BP'
  | 'ING'
  | 'SANTANDER'
  | 'PEKAO'
  | 'GENERIC';

export type StatementFormat = 'PDF' | 'CSV';
export type ReconciliationStatus = 'VERIFIED' | 'MISMATCH' | 'UNAVAILABLE';
export type ClassificationSource =
  | 'USER_OVERRIDE'
  | 'USER_RULE'
  | 'RULE_ENGINE'
  | 'LLM'
  | 'FALLBACK';
export type RuleMatchType = 'MERCHANT' | 'PHRASE';
export type ExpensePeriodPreset =
  | 'THIS_MONTH'
  | 'LAST_MONTH'
  | 'LAST_3_MONTHS'
  | 'YTD'
  | 'ALL';

export interface ExpenseStatusResponse {
  aiEnabled: boolean;
  aiProvider: string;
  aiModel: string;
  baseCurrency: string;
  maxFileSizeBytes: number;
  supportedFormats: StatementFormat[];
  supportedBankProfiles: BankProfile[];
}

export interface ReconciliationResult {
  status: ReconciliationStatus;
  openingBalance: number | null;
  closingBalance: number | null;
  calculatedInflows: number;
  calculatedOutflows: number;
  discrepancy: number | null;
}

export interface PreviewTransactionItem {
  id: string;
  txDate: string;
  bookingDate: string | null;
  rawDescription: string;
  merchantKey: string;
  merchantDisplay: string;
  phraseKey: string | null;
  intermediary: boolean;
  amount: number;
  currency: string;
  baseAmount: number;
  baseCurrency: string;
  fxRate: number;
  fxDegraded: boolean;
  flowKind: FlowKind;
  category: ExpenseCategory;
  classificationSource: ClassificationSource;
  confidence: number;
  needsReview: boolean;
  duplicate: boolean;
  fingerprint: string;
}

export interface StatementPreviewResponse {
  fileName: string;
  fileHashSha256: string;
  alreadyUploadedFile: boolean;
  format: StatementFormat;
  detectedBankProfile: BankProfile;
  currency: string;
  baseCurrency: string;
  periodStart: string | null;
  periodEnd: string | null;
  reconciliation: ReconciliationResult;
  aiUsed: boolean;
  aiWarning: string | null;
  fxDegradedCount: number;
  warnings: string[];
  duplicateCount: number;
  needsReviewCount: number;
  transactions: PreviewTransactionItem[];
}

export interface CommitTransactionItem {
  txDate: string;
  bookingDate: string | null;
  rawDescription: string;
  merchantKey: string;
  merchantDisplay: string;
  phraseKey: string | null;
  amount: number;
  currency: string;
  baseAmount: number;
  baseCurrency: string;
  fxRate: number;
  fxDegraded: boolean;
  flowKind: FlowKind;
  category: ExpenseCategory;
  classificationSource: ClassificationSource;
  confidence: number;
  fingerprint: string;
  excluded: boolean;
  rememberRule: boolean;
  userNote?: string | null;
}

export interface CommitStatementRequest {
  fileName: string;
  fileHashSha256: string;
  format: StatementFormat;
  bankProfile: BankProfile;
  accountLabel?: string | null;
  currency: string;
  periodStart: string | null;
  periodEnd: string | null;
  openingBalance: number | null;
  closingBalance: number | null;
  calculatedInflows: number;
  calculatedOutflows: number;
  reconciliationStatus: ReconciliationStatus;
  reconciliationDelta: number | null;
  transactions: CommitTransactionItem[];
}

export interface StatementSummaryResponse {
  id: string;
  fileName: string;
  format: StatementFormat;
  bankProfile: BankProfile;
  accountLabel: string | null;
  currency: string;
  periodStart: string | null;
  periodEnd: string | null;
  reconciliationStatus: ReconciliationStatus;
  reconciliationDelta: number | null;
  savedCount: number;
  skippedDuplicates: number;
  createdAt: string;
}

export interface ExpenseKpis {
  totalIncome: number;
  totalExpenses: number;
  baseLivingExpenses: number;
  oneOffOutliersTotal: number;
  totalInvestmentsSavings: number;
  totalInternalTransfers: number;
  totalRefundsNetted: number;
  netCashflow: number;
  savingsRatePct: number | null;
  fixedCostsTotal: number;
  variableCostsTotal: number;
  fixedSharePct: number;
  needsReviewCount: number;
  fxDegradedCount: number;
}

export interface CategoryBreakdownItem {
  category: ExpenseCategory;
  amount: number;
  sharePct: number;
  transactionCount: number;
  previousPeriodAmount: number | null;
  momChangePct: number | null;
}

export interface MerchantSummaryItem {
  merchantKey: string;
  merchantDisplay: string;
  category: ExpenseCategory;
  totalAmount: number;
  transactionCount: number;
  averageAmount: number;
}

export interface MonthlyTrendPoint {
  month: string;
  income: number;
  expenses: number;
  investmentsSavings: number;
  netCashflow: number;
  transactionCount: number;
  topCategory: ExpenseCategory | null;
  topCategoryAmount: number;
  savingsRatePct: number | null;
  momExpensesChangePct: number | null;
}

export interface ReclassifyExpensesRequest {
  from?: string;
  to?: string;
  statementId?: string;
  includeManualOverrides?: boolean;
}

export interface ReclassifyExpensesResponse {
  processedCount: number;
  updatedCount: number;
  aiUsed: boolean;
  aiWarning: string | null;
}

export interface RecurringChargeItem {
  merchantKey: string;
  merchantDisplay: string;
  category: ExpenseCategory;
  averageMonthlyAmount: number;
  latestAmount: number;
  annualizedCost: number;
  occurrences: number;
  priceIncreasePct: number | null;
  lastChargeDate: string;
}

export interface OutlierExpenseItem {
  transactionId: string;
  txDate: string;
  merchantKey: string;
  merchantDisplay: string;
  category: ExpenseCategory;
  amount: number;
}

export interface ExpenseInsightDto {
  code: string;
  severity: 'INFO' | 'WARN' | 'SUCCESS' | string;
  params?: Record<string, unknown>;
}

export interface ExpenseTransactionResponse {
  id: string;
  statementId: string;
  txDate: string;
  bookingDate: string | null;
  rawDescription: string;
  merchantKey: string;
  merchantDisplay: string;
  phraseKey: string | null;
  amount: number;
  currency: string;
  baseAmount: number;
  baseCurrency: string;
  fxRate: number;
  fxDegraded: boolean;
  flowKind: FlowKind;
  category: ExpenseCategory;
  classificationSource: ClassificationSource;
  confidence: number;
  needsReview: boolean;
  isRecurring: boolean;
  excluded: boolean;
  userNote: string | null;
}

export interface ExpenseAnalyticsResponse {
  baseCurrency: string;
  periodStart: string | null;
  periodEnd: string | null;
  kpis: ExpenseKpis;
  categoryBreakdown: CategoryBreakdownItem[];
  topMerchants: MerchantSummaryItem[];
  monthlyTrend: MonthlyTrendPoint[];
  recurringCharges: RecurringChargeItem[];
  outliers: OutlierExpenseItem[];
  insights: ExpenseInsightDto[];
  transactions: ExpenseTransactionResponse[];
}

export interface UpdateExpenseTransactionRequest {
  category: ExpenseCategory;
  flowKind?: FlowKind;
  excluded?: boolean;
  userNote?: string | null;
  rememberRule: boolean;
  applyToMatching: boolean;
}

export interface MerchantRuleResponse {
  id: string;
  matchType: RuleMatchType;
  matchKey: string;
  displayLabel: string;
  category: ExpenseCategory;
  flowKind: FlowKind;
  source: string;
  hitCount: number;
  updatedAt: string;
}
