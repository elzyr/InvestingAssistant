import React, { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { FileText, BookmarkCheck, Trash2, CheckCircle2, AlertTriangle, HelpCircle } from 'lucide-react';
import type {
  MerchantRuleResponse,
  StatementSummaryResponse,
} from '../../types/expense.types.ts';
import { getCategoryMeta } from '../../utils/expenseFormatters.ts';

interface ExpenseStatementsAndRulesPanelProps {
  statements: StatementSummaryResponse[];
  rules: MerchantRuleResponse[];
  activeStatementId: string;
  onSelectStatement: (statementId: string) => void;
  onDeleteStatement: (statementId: string) => Promise<void>;
  onDeleteRule: (ruleId: string) => Promise<void>;
}

export const ExpenseStatementsAndRulesPanel: React.FC<ExpenseStatementsAndRulesPanelProps> = ({
  statements,
  rules,
  activeStatementId,
  onSelectStatement,
  onDeleteStatement,
  onDeleteRule,
}) => {
  const { t } = useTranslation();
  const [activeTab, setActiveTab] = useState<'STATEMENTS' | 'RULES'>('STATEMENTS');

  return (
    <div className="bg-zinc-900/50 border border-zinc-800/80 rounded-xl p-5 space-y-4">
      <div className="flex items-center justify-between gap-2">
        <div className="inline-flex rounded-lg bg-zinc-950 p-1 border border-zinc-800 text-xs font-medium">
          <button
            type="button"
            onClick={() => setActiveTab('STATEMENTS')}
            className={`inline-flex items-center gap-1.5 px-3 py-1.5 rounded-md transition cursor-pointer ${
              activeTab === 'STATEMENTS'
                ? 'bg-zinc-800 text-emerald-400 font-semibold'
                : 'text-zinc-400 hover:text-zinc-200'
            }`}
          >
            <FileText className="w-3.5 h-3.5" />
            <span>
              {t('expenses.panels.statementsTab')} ({statements.length})
            </span>
          </button>
          <button
            type="button"
            onClick={() => setActiveTab('RULES')}
            className={`inline-flex items-center gap-1.5 px-3 py-1.5 rounded-md transition cursor-pointer ${
              activeTab === 'RULES'
                ? 'bg-zinc-800 text-emerald-400 font-semibold'
                : 'text-zinc-400 hover:text-zinc-200'
            }`}
          >
            <BookmarkCheck className="w-3.5 h-3.5" />
            <span>
              {t('expenses.panels.rulesTab')} ({rules.length})
            </span>
          </button>
        </div>
      </div>

      {activeTab === 'STATEMENTS' ? (
        statements.length === 0 ? (
          <p className="text-xs text-zinc-500 py-4 text-center">
            {t('expenses.panels.noStatements')}
          </p>
        ) : (
          <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-2.5">
            {statements.map((st) => {
              const isSelected = activeStatementId === st.id;
              const StatusIcon =
                st.reconciliationStatus === 'VERIFIED'
                  ? CheckCircle2
                  : st.reconciliationStatus === 'MISMATCH'
                  ? AlertTriangle
                  : HelpCircle;
              const statusColor =
                st.reconciliationStatus === 'VERIFIED'
                  ? 'text-emerald-400'
                  : st.reconciliationStatus === 'MISMATCH'
                  ? 'text-rose-400'
                  : 'text-zinc-500';

              return (
                <div
                  key={st.id}
                  className={`p-3 rounded-lg border transition flex flex-col justify-between gap-2 ${
                    isSelected
                      ? 'bg-emerald-500/10 border-emerald-500/40'
                      : 'bg-zinc-950/50 border-zinc-800/70'
                  }`}
                >
                  <div className="flex items-start justify-between gap-2">
                    <div className="min-w-0">
                      <div className="flex items-center gap-1.5">
                        <StatusIcon className={`w-3.5 h-3.5 shrink-0 ${statusColor}`} />
                        <span className="text-xs font-semibold text-zinc-200 truncate" title={st.fileName}>
                          {st.fileName}
                        </span>
                      </div>
                      <p className="text-[11px] text-zinc-500 font-mono mt-1">
                        {st.bankProfile} &bull; {st.format} &bull; {st.currency}
                        {st.accountLabel ? ` • ${st.accountLabel}` : ''}
                      </p>
                      {st.periodStart && st.periodEnd && (
                        <p className="text-[10px] text-zinc-500 font-mono">
                          {st.periodStart} → {st.periodEnd}
                        </p>
                      )}
                    </div>
                    <button
                      type="button"
                      onClick={() => {
                        if (window.confirm(t('expenses.panels.deleteStatementConfirm'))) {
                          void onDeleteStatement(st.id);
                        }
                      }}
                      className="p-1.5 rounded text-zinc-500 hover:text-rose-400 hover:bg-zinc-800 transition cursor-pointer shrink-0"
                    >
                      <Trash2 className="w-3.5 h-3.5" />
                    </button>
                  </div>

                  <div className="flex items-center justify-between gap-2 pt-2 border-t border-zinc-800/60 text-[11px] font-mono">
                    <span className="text-zinc-400">
                      {t('expenses.panels.savedRows', { count: st.savedCount })}
                      {st.skippedDuplicates > 0
                        ? ` • ${t('expenses.panels.skippedRows', { count: st.skippedDuplicates })}`
                        : ''}
                    </span>
                    <button
                      type="button"
                      onClick={() => onSelectStatement(isSelected ? '' : st.id)}
                      className={`px-2 py-0.5 rounded transition cursor-pointer ${
                        isSelected
                          ? 'bg-emerald-500/20 text-emerald-300'
                          : 'bg-zinc-800 text-zinc-300 hover:bg-zinc-700'
                      }`}
                    >
                      {t('expenses.panels.filterStatement')}
                    </button>
                  </div>
                </div>
              );
            })}
          </div>
        )
      ) : rules.length === 0 ? (
        <p className="text-xs text-zinc-500 py-4 text-center">{t('expenses.panels.noRules')}</p>
      ) : (
        <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-2.5">
          {rules.map((rule) => {
            const meta = getCategoryMeta(rule.category);
            return (
              <div
                key={rule.id}
                className="p-3 rounded-lg bg-zinc-950/50 border border-zinc-800/70 flex items-center justify-between gap-2 text-xs"
              >
                <div className="min-w-0">
                  <div className="flex items-center gap-1.5">
                    <span
                      className="w-2 h-2 rounded-full shrink-0"
                      style={{ backgroundColor: meta.color }}
                    />
                    <span className="font-semibold text-zinc-200 truncate">{rule.displayLabel}</span>
                  </div>
                  <p className="text-[11px] text-zinc-500 font-mono mt-0.5 truncate">
                    {rule.matchType === 'PHRASE'
                      ? t('expenses.panels.matchPhrase')
                      : t('expenses.panels.matchMerchant')}
                    : {rule.matchKey}
                  </p>
                  <p className="text-[10px] text-zinc-400 mt-0.5">
                    {t(meta.labelKey)} &bull;{' '}
                    {t('expenses.panels.ruleHits', { count: rule.hitCount })}
                  </p>
                </div>
                <button
                  type="button"
                  onClick={() => {
                    if (window.confirm(t('expenses.panels.deleteRuleConfirm'))) {
                      void onDeleteRule(rule.id);
                    }
                  }}
                  className="p-1.5 rounded text-zinc-500 hover:text-rose-400 hover:bg-zinc-800 transition cursor-pointer shrink-0"
                >
                  <Trash2 className="w-3.5 h-3.5" />
                </button>
              </div>
            );
          })}
        </div>
      )}
    </div>
  );
};
