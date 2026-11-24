import apiClient from '../api/client.ts';
import type {
  BankProfile,
  CommitStatementRequest,
  ExpenseAnalyticsResponse,
  ExpenseCategory,
  ExpenseStatusResponse,
  ExpenseTransactionResponse,
  MerchantRuleResponse,
  ReclassifyExpensesRequest,
  ReclassifyExpensesResponse,
  StatementPreviewResponse,
  StatementSummaryResponse,
  UpdateExpenseTransactionRequest,
} from '../types/expense.types.ts';

export interface ExpenseAnalyticsQuery {
  from?: string;
  to?: string;
  statementId?: string;
  category?: ExpenseCategory;
  includeTransactions?: boolean;
  lang?: string;
  signal?: AbortSignal;
}

export const expenseService = {
  async getStatus(signal?: AbortSignal): Promise<ExpenseStatusResponse> {
    const { data } = await apiClient.get<ExpenseStatusResponse>('/api/expenses/status', { signal });
    return data;
  },

  async previewStatement(
    file: File,
    options: { bankProfile?: BankProfile; useAi?: boolean; accountLabel?: string; signal?: AbortSignal } = {}
  ): Promise<StatementPreviewResponse> {
    const formData = new FormData();
    formData.append('file', file);
    if (options.bankProfile) {
      formData.append('bankProfile', options.bankProfile);
    }
    if (options.useAi !== undefined) {
      formData.append('useAi', String(options.useAi));
    }
    if (options.accountLabel && options.accountLabel.trim()) {
      formData.append('accountLabel', options.accountLabel.trim());
    }

    const { data } = await apiClient.post<StatementPreviewResponse>(
      '/api/expenses/statements/preview',
      formData,
      {
        headers: { 'Content-Type': undefined },
        timeout: 60000,
        signal: options.signal,
      }
    );
    return data;
  },

  async commitStatement(payload: CommitStatementRequest): Promise<StatementSummaryResponse> {
    const { data } = await apiClient.post<StatementSummaryResponse>(
      '/api/expenses/statements/commit',
      payload
    );
    return data;
  },

  async listStatements(signal?: AbortSignal): Promise<StatementSummaryResponse[]> {
    const { data } = await apiClient.get<StatementSummaryResponse[]>('/api/expenses/statements', {
      signal,
    });
    return data;
  },

  async deleteStatement(statementId: string): Promise<void> {
    await apiClient.delete(`/api/expenses/statements/${statementId}`);
  },

  async getAnalytics(query: ExpenseAnalyticsQuery = {}): Promise<ExpenseAnalyticsResponse> {
    const params: Record<string, string | boolean> = {};
    if (query.from) params.from = query.from;
    if (query.to) params.to = query.to;
    if (query.statementId) params.statementId = query.statementId;
    if (query.category) params.category = query.category;
    if (query.includeTransactions !== undefined) {
      params.includeTransactions = query.includeTransactions;
    }
    if (query.lang) params.lang = query.lang;

    const { data } = await apiClient.get<ExpenseAnalyticsResponse>('/api/expenses/analytics', {
      params,
      signal: query.signal,
    });
    return data;
  },

  async updateTransaction(
    transactionId: string,
    payload: UpdateExpenseTransactionRequest
  ): Promise<ExpenseTransactionResponse> {
    const { data } = await apiClient.patch<ExpenseTransactionResponse>(
      `/api/expenses/transactions/${transactionId}`,
      payload
    );
    return data;
  },

  async listRules(signal?: AbortSignal): Promise<MerchantRuleResponse[]> {
    const { data } = await apiClient.get<MerchantRuleResponse[]>('/api/expenses/rules', {
      signal,
    });
    return data;
  },

  async deleteRule(ruleId: string): Promise<void> {
    await apiClient.delete(`/api/expenses/rules/${ruleId}`);
  },

  async reclassify(
    payload: ReclassifyExpensesRequest = {},
    signal?: AbortSignal
  ): Promise<ReclassifyExpensesResponse> {
    const { data } = await apiClient.post<ReclassifyExpensesResponse>(
      '/api/expenses/reclassify',
      payload,
      { timeout: 60000, signal }
    );
    return data;
  },
};
