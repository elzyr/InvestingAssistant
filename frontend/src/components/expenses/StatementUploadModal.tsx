import React, { useEffect, useMemo, useRef, useState } from 'react';
import { useTranslation } from 'react-i18next';
import axios from 'axios';
import {
  Upload,
  FileSpreadsheet,
  CheckCircle2,
  AlertTriangle,
  HelpCircle,
  Loader2,
  Sparkles,
  ArrowLeft,
} from 'lucide-react';
import type {
  BankProfile,
  CommitStatementRequest,
  ExpenseCategory,
  ExpenseStatusResponse,
  PreviewTransactionItem,
  StatementPreviewResponse,
} from '../../types/expense.types.ts';
import { expenseService } from '../../services/expense.service.ts';
import { extractApiErrorMessage } from '../../api/client.ts';
import {
  formatExpenseCurrency,
  getCategoryMeta,
  getSelectableCategoriesForAmount,
  groupPreviewRows,
  groupPreviewRowsByMonth,
  isFlowCompatibleWithAmount,
  resolveCategoryFlowKind,
  shouldAutoRememberRule,
} from '../../utils/expenseFormatters.ts';
import { Modal } from '../common/Modal.tsx';
import { ErrorBanner } from '../common/ErrorBanner.tsx';

interface StatementUploadModalProps {
  isOpen: boolean;
  onClose: () => void;
  status: ExpenseStatusResponse | null;
  onCommitted: (latestMonth?: string) => void;
}

interface RowOverride {
  category: ExpenseCategory;
  excluded: boolean;
  rememberRule: boolean;
}

const DEFAULT_PROFILES: BankProfile[] = [
  'AUTO',
  'MBANK',
  'REVOLUT',
  'WISE',
  'PKO_BP',
  'ING',
  'SANTANDER',
  'PEKAO',
  'GENERIC',
];

export const StatementUploadModal: React.FC<StatementUploadModalProps> = ({
  isOpen,
  onClose,
  status,
  onCommitted,
}) => {
  const { t, i18n } = useTranslation();
  const locale = i18n.language.startsWith('pl') ? 'pl-PL' : 'en-US';
  const fileInputRef = useRef<HTMLInputElement | null>(null);
  const previewAbortRef = useRef<AbortController | null>(null);
  const previewGenRef = useRef<number>(0);
  const sessionGenRef = useRef<number>(0);
  const isMountedRef = useRef<boolean>(true);

  const [file, setFile] = useState<File | null>(null);
  const [bankProfile, setBankProfile] = useState<BankProfile>('AUTO');
  const [accountLabel, setAccountLabel] = useState('');
  const [useAi, setUseAi] = useState(true);
  const [loading, setLoading] = useState(false);
  const [committing, setCommitting] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const [preview, setPreview] = useState<StatementPreviewResponse | null>(null);
  const [overrides, setOverrides] = useState<Record<string, RowOverride>>({});
  const [previewTab, setPreviewTab] = useState<'REVIEW' | 'READY' | 'DUPLICATES'>('REVIEW');
  const [selectedMonth, setSelectedMonth] = useState<string>('ALL');
  const [visibleLimit, setVisibleLimit] = useState<number>(100);

  useEffect(() => {
    isMountedRef.current = true;
    return () => {
      isMountedRef.current = false;
      previewAbortRef.current?.abort();
    };
  }, []);

  useEffect(() => {
    if (!isOpen) {
      sessionGenRef.current += 1;
    }
  }, [isOpen]);

  const cancelPendingPreview = () => {
    previewAbortRef.current?.abort();
    previewAbortRef.current = null;
    previewGenRef.current += 1;
  };

  const resetAll = () => {
    cancelPendingPreview();
    sessionGenRef.current += 1;
    setFile(null);
    setBankProfile('AUTO');
    setAccountLabel('');
    setUseAi(status?.aiEnabled ?? true);
    setLoading(false);
    setCommitting(false);
    setError(null);
    setPreview(null);
    setOverrides({});
    setPreviewTab('REVIEW');
    setSelectedMonth('ALL');
    if (fileInputRef.current) {
      fileInputRef.current.value = '';
    }
  };

  const handleClose = () => {
    resetAll();
    onClose();
  };

  const handleFileSelected = (selected: File | null) => {
    cancelPendingPreview();
    setLoading(false);
    setFile(selected);
    setPreview(null);
    setOverrides({});
    setSelectedMonth('ALL');
    setError(null);
  };

  const handlePreview = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!file) return;
    cancelPendingPreview();
    const controller = new AbortController();
    previewAbortRef.current = controller;
    const gen = previewGenRef.current;

    setLoading(true);
    setError(null);
    try {
      const result = await expenseService.previewStatement(file, {
        bankProfile,
        useAi: status?.aiEnabled ? useAi : false,
        accountLabel,
        signal: controller.signal,
      });
      if (gen !== previewGenRef.current || controller.signal.aborted) return;
      setPreview(result);
      const initialOverrides: Record<string, RowOverride> = {};
      for (const tx of result.transactions) {
        const selectable = getSelectableCategoriesForAmount(tx.amount);
        initialOverrides[tx.id] = {
          category: selectable.includes(tx.category) ? tx.category : 'OTHER_EXPENSE',
          excluded: false,
          rememberRule: false,
        };
      }
      setOverrides(initialOverrides);
      setSelectedMonth('ALL');
      setVisibleLimit(100);
      const grouped = groupPreviewRows(result.transactions);
      setPreviewTab(
        grouped.needsReview.length > 0
          ? 'REVIEW'
          : grouped.ready.length > 0
          ? 'READY'
          : 'DUPLICATES'
      );
    } catch (err) {
      if (gen !== previewGenRef.current || controller.signal.aborted || axios.isCancel(err)) return;
      setError(extractApiErrorMessage(err, t('expenses.errors.previewFailed')));
    } finally {
      if (gen === previewGenRef.current) {
        setLoading(false);
      }
    }
  };

  const monthBreakdown = useMemo(
    () => (preview ? groupPreviewRowsByMonth(preview.transactions) : []),
    [preview]
  );

  const allGrouped = useMemo(
    () => (preview ? groupPreviewRows(preview.transactions) : { needsReview: [], ready: [], duplicates: [] }),
    [preview]
  );

  const grouped = useMemo(() => {
    if (!preview) return { needsReview: [], ready: [], duplicates: [] };
    if (selectedMonth === 'ALL') return allGrouped;
    return groupPreviewRows(
      preview.transactions.filter((tx) => tx.txDate.startsWith(selectedMonth))
    );
  }, [preview, selectedMonth, allGrouped]);

  const newRowsCount = allGrouped.needsReview.length + allGrouped.ready.length;

  const updateRowOverride = (id: string, patch: Partial<RowOverride>) => {
    setOverrides((prev) => {
      const current = prev[id];
      if (!current) return prev;
      return { ...prev, [id]: { ...current, ...patch } };
    });
  };

  const handleCommit = async () => {
    if (!preview) return;
    const commitGen = sessionGenRef.current;
    setCommitting(true);
    setError(null);
    try {
      const payload: CommitStatementRequest = {
        fileName: preview.fileName,
        fileHashSha256: preview.fileHashSha256,
        format: preview.format,
        bankProfile: preview.detectedBankProfile,
        accountLabel: accountLabel.trim() || null,
        currency: preview.currency,
        periodStart: preview.periodStart,
        periodEnd: preview.periodEnd,
        openingBalance: preview.reconciliation.openingBalance,
        closingBalance: preview.reconciliation.closingBalance,
        calculatedInflows: preview.reconciliation.calculatedInflows,
        calculatedOutflows: preview.reconciliation.calculatedOutflows,
        reconciliationStatus: preview.reconciliation.status,
        reconciliationDelta: preview.reconciliation.discrepancy,
        transactions: preview.transactions.map((tx) => {
          const ov = overrides[tx.id];
          const finalCategory = ov ? ov.category : tx.category;
          const changedCategory = finalCategory !== tx.category;
          const finalFlow =
            !changedCategory && isFlowCompatibleWithAmount(tx.flowKind, tx.amount)
              ? tx.flowKind
              : resolveCategoryFlowKind(finalCategory, tx.amount);
          return {
            txDate: tx.txDate,
            bookingDate: tx.bookingDate,
            rawDescription: tx.rawDescription,
            merchantKey: tx.merchantKey,
            merchantDisplay: tx.merchantDisplay,
            phraseKey: tx.phraseKey,
            amount: tx.amount,
            currency: tx.currency,
            baseAmount: tx.baseAmount,
            baseCurrency: tx.baseCurrency,
            fxRate: tx.fxRate,
            fxDegraded: tx.fxDegraded,
            flowKind: finalFlow,
            category: finalCategory,
            classificationSource: changedCategory ? 'USER_OVERRIDE' : tx.classificationSource,
            confidence: changedCategory ? 1 : tx.confidence,
            fingerprint: tx.fingerprint,
            excluded: ov ? ov.excluded : false,
            rememberRule: ov ? ov.rememberRule : false,
          };
        }),
      };

      const latestMonth = monthBreakdown.at(-1)?.month ?? preview.periodEnd?.slice(0, 7) ?? undefined;
      await expenseService.commitStatement(payload);
      if (!isMountedRef.current || commitGen !== sessionGenRef.current) {
        return;
      }
      resetAll();
      onCommitted(latestMonth);
      onClose();
    } catch (err) {
      if (!isMountedRef.current || commitGen !== sessionGenRef.current) {
        return;
      }
      setError(extractApiErrorMessage(err, t('expenses.errors.commitFailed')));
    } finally {
      if (isMountedRef.current && commitGen === sessionGenRef.current) {
        setCommitting(false);
      }
    }
  };

  const activeRows: PreviewTransactionItem[] =
    previewTab === 'REVIEW'
      ? grouped.needsReview
      : previewTab === 'READY'
      ? grouped.ready
      : grouped.duplicates;
  const visibleRows = activeRows.slice(0, visibleLimit);
  const remainingRowsCount = Math.max(0, activeRows.length - visibleRows.length);

  const bankProfiles = status?.supportedBankProfiles?.length
    ? status.supportedBankProfiles
    : DEFAULT_PROFILES;

  return (
    <Modal
      isOpen={isOpen}
      onClose={handleClose}
      maxWidthClass={preview ? 'max-w-5xl' : 'max-w-lg'}
      headerIcon={<FileSpreadsheet className="w-5 h-5 text-emerald-400 shrink-0" />}
      title={<span className="text-sm font-semibold text-zinc-100">{t('expenses.upload.title')}</span>}
      subtitle={t('expenses.upload.subtitle')}
    >
      <div className="p-5 space-y-4 overflow-y-auto">
        <ErrorBanner error={error} onDismiss={() => setError(null)} />

        {!preview ? (
          <form onSubmit={handlePreview} className="space-y-4 text-xs">
            <div
              onDragOver={(e) => e.preventDefault()}
              onDrop={(e) => {
                e.preventDefault();
                const dropped = e.dataTransfer.files?.[0] ?? null;
                if (dropped) handleFileSelected(dropped);
              }}
              onClick={() => fileInputRef.current?.click()}
              className="border-2 border-dashed border-zinc-700/80 hover:border-emerald-500/60 bg-zinc-950/60 rounded-xl p-6 text-center cursor-pointer transition space-y-2"
            >
              <Upload className="w-7 h-7 text-emerald-400 mx-auto" />
              <p className="text-zinc-200 font-medium">
                {file ? file.name : t('expenses.upload.dropLabel')}
              </p>
              {file && (
                <p className="text-[11px] font-mono text-zinc-500">
                  {(file.size / 1024).toFixed(1)} KB
                </p>
              )}
              <input
                ref={fileInputRef}
                type="file"
                accept=".pdf,.csv"
                onChange={(e) => handleFileSelected(e.target.files?.[0] ?? null)}
                className="hidden"
              />
            </div>

            <div className="grid grid-cols-1 sm:grid-cols-2 gap-3">
              <div className="space-y-1.5">
                <label className="block text-zinc-300 font-medium">
                  {t('expenses.upload.bankProfile')}
                </label>
                <select
                  value={bankProfile}
                  onChange={(e) => setBankProfile(e.target.value as BankProfile)}
                  className="w-full bg-zinc-950 border border-zinc-800 text-zinc-100 rounded-lg px-3 py-2 focus:outline-none focus:border-emerald-500"
                >
                  {bankProfiles.map((bp) => (
                    <option key={bp} value={bp}>
                      {bp}
                    </option>
                  ))}
                </select>
              </div>

              <div className="space-y-1.5">
                <label className="block text-zinc-300 font-medium">
                  {t('expenses.upload.accountLabel')}
                </label>
                <input
                  type="text"
                  maxLength={120}
                  value={accountLabel}
                  onChange={(e) => setAccountLabel(e.target.value)}
                  placeholder={t('expenses.upload.accountPlaceholder')}
                  className="w-full bg-zinc-950 border border-zinc-800 text-zinc-100 rounded-lg px-3 py-2 focus:outline-none focus:border-emerald-500"
                />
              </div>
            </div>

            {status?.aiEnabled ? (
              <label className="flex items-center gap-2 text-zinc-300 bg-emerald-950/25 border border-emerald-800/40 rounded-lg p-3 cursor-pointer">
                <input
                  type="checkbox"
                  checked={useAi}
                  onChange={(e) => setUseAi(e.target.checked)}
                  className="accent-emerald-500"
                />
                <Sparkles className="w-4 h-4 text-emerald-400 shrink-0" />
                <span>
                  {t('expenses.upload.useAiLabel', { model: status.aiModel || 'tev1:4b' })}
                </span>
              </label>
            ) : (
              <p className="text-[11px] text-zinc-500 bg-zinc-950/50 border border-zinc-800/60 rounded-lg p-2.5">
                {t('expenses.upload.aiDisabledHint')}
              </p>
            )}

            <div className="flex items-center justify-end gap-2 pt-3 border-t border-zinc-800">
              <button
                type="button"
                onClick={handleClose}
                className="px-3.5 py-2 rounded-lg bg-zinc-800 hover:bg-zinc-700 text-zinc-300 font-medium transition cursor-pointer"
              >
                {t('common.cancel')}
              </button>
              <button
                type="submit"
                disabled={!file || loading}
                className="inline-flex items-center gap-1.5 px-4 py-2 rounded-lg bg-emerald-600 hover:bg-emerald-500 text-white font-semibold transition disabled:opacity-50 cursor-pointer"
              >
                {loading && <Loader2 className="w-3.5 h-3.5 animate-spin" />}
                <span>
                  {loading ? t('expenses.upload.parsing') : t('expenses.upload.previewAction')}
                </span>
              </button>
            </div>
          </form>
        ) : (
          <div className="space-y-4 text-xs">
            <div className="flex flex-wrap items-center justify-between gap-2 p-3 rounded-xl bg-zinc-950/70 border border-zinc-800">
              <div className="flex flex-wrap items-center gap-2">
                <span className="font-semibold text-zinc-100">{preview.fileName}</span>
                <span className="px-2 py-0.5 rounded bg-zinc-800 font-mono text-[11px] text-zinc-300">
                  {preview.detectedBankProfile}
                </span>
                <span className="px-2 py-0.5 rounded bg-zinc-800 font-mono text-[11px] text-zinc-300">
                  {preview.currency}
                </span>
                {preview.periodStart && preview.periodEnd && (
                  <span className="font-mono text-[11px] text-zinc-400">
                    {preview.periodStart} → {preview.periodEnd}
                  </span>
                )}
              </div>

              <div>
                {preview.reconciliation.status === 'VERIFIED' ? (
                  <span className="inline-flex items-center gap-1.5 px-2.5 py-1 rounded-lg bg-emerald-950/50 border border-emerald-700/50 text-emerald-300 font-mono">
                    <CheckCircle2 className="w-3.5 h-3.5" />
                    {t('expenses.upload.reconciledVerified')}
                  </span>
                ) : preview.reconciliation.status === 'MISMATCH' ? (
                  <span className="inline-flex items-center gap-1.5 px-2.5 py-1 rounded-lg bg-rose-950/50 border border-rose-700/50 text-rose-300 font-mono">
                    <AlertTriangle className="w-3.5 h-3.5" />
                    {t('expenses.upload.reconciledMismatch', {
                      delta: formatExpenseCurrency(
                        preview.reconciliation.discrepancy ?? 0,
                        true,
                        preview.currency,
                        locale
                      ),
                    })}
                  </span>
                ) : (
                  <span className="inline-flex items-center gap-1.5 px-2.5 py-1 rounded-lg bg-zinc-900 border border-zinc-700 text-zinc-400 font-mono">
                    <HelpCircle className="w-3.5 h-3.5" />
                    {t('expenses.upload.reconciledUnavailable')}
                  </span>
                )}
              </div>
            </div>

            {preview.alreadyUploadedFile && (
              <div className="p-2.5 rounded-lg bg-amber-950/40 border border-amber-700/50 text-amber-200 flex items-center gap-2">
                <AlertTriangle className="w-4 h-4 text-amber-400 shrink-0" />
                <span>{t('expenses.upload.alreadyUploaded')}</span>
              </div>
            )}

            {preview.aiWarning && (
              <div className="p-2.5 rounded-lg bg-amber-950/30 border border-amber-800/50 text-amber-200">
                {preview.aiWarning}
              </div>
            )}

            {monthBreakdown.length > 1 && (
              <div className="flex flex-wrap items-center gap-1.5 p-2.5 rounded-lg bg-zinc-950/70 border border-zinc-800">
                <span className="text-[11px] font-semibold text-zinc-400 mr-1">
                  {t('expenses.upload.monthsInFile')}:
                </span>
                <button
                  type="button"
                  onClick={() => {
                    setSelectedMonth('ALL');
                    setVisibleLimit(100);
                  }}
                  className={`px-2.5 py-1 rounded-md text-[11px] font-mono transition cursor-pointer ${
                    selectedMonth === 'ALL'
                      ? 'bg-emerald-500/20 text-emerald-300 border border-emerald-500/40 font-semibold'
                      : 'bg-zinc-900 text-zinc-400 hover:text-zinc-200 border border-zinc-800'
                  }`}
                >
                  {t('expenses.upload.allMonths', { count: preview.transactions.length })}
                </button>
                {monthBreakdown.map((m) => (
                  <button
                    type="button"
                    key={m.month}
                    onClick={() => {
                      setSelectedMonth(m.month);
                      setVisibleLimit(100);
                    }}
                    title={`-${formatExpenseCurrency(m.totalOutflow, false, preview.currency, locale)} / +${formatExpenseCurrency(m.totalInflow, false, preview.currency, locale)}`}
                    className={`px-2.5 py-1 rounded-md text-[11px] font-mono transition cursor-pointer ${
                      selectedMonth === m.month
                        ? 'bg-emerald-500/20 text-emerald-300 border border-emerald-500/40 font-semibold'
                        : 'bg-zinc-900 text-zinc-300 hover:bg-zinc-800 border border-zinc-800'
                    }`}
                  >
                    {m.month} ({m.count})
                  </button>
                ))}
              </div>
            )}

            <div className="inline-flex rounded-lg bg-zinc-950 p-1 border border-zinc-800 font-medium">
              <button
                type="button"
                onClick={() => {
                  setPreviewTab('REVIEW');
                  setVisibleLimit(100);
                }}
                className={`px-3 py-1.5 rounded-md transition cursor-pointer ${
                  previewTab === 'REVIEW'
                    ? 'bg-zinc-800 text-amber-300 font-semibold'
                    : 'text-zinc-400 hover:text-zinc-200'
                }`}
              >
                {t('expenses.upload.tabNeedsReview', { count: grouped.needsReview.length })}
              </button>
              <button
                type="button"
                onClick={() => {
                  setPreviewTab('READY');
                  setVisibleLimit(100);
                }}
                className={`px-3 py-1.5 rounded-md transition cursor-pointer ${
                  previewTab === 'READY'
                    ? 'bg-zinc-800 text-emerald-400 font-semibold'
                    : 'text-zinc-400 hover:text-zinc-200'
                }`}
              >
                {t('expenses.upload.tabReady', { count: grouped.ready.length })}
              </button>
              <button
                type="button"
                onClick={() => {
                  setPreviewTab('DUPLICATES');
                  setVisibleLimit(100);
                }}
                className={`px-3 py-1.5 rounded-md transition cursor-pointer ${
                  previewTab === 'DUPLICATES'
                    ? 'bg-zinc-800 text-zinc-200 font-semibold'
                    : 'text-zinc-400 hover:text-zinc-200'
                }`}
              >
                {t('expenses.upload.tabDuplicates', { count: grouped.duplicates.length })}
              </button>
            </div>

            <div className="max-h-80 overflow-y-auto border border-zinc-800 rounded-lg divide-y divide-zinc-800/60">
              {visibleRows.length === 0 ? (
                <div className="py-8 text-center text-zinc-500">
                  {t('expenses.upload.noRowsInTab')}
                </div>
              ) : (
                visibleRows.map((row) => {
                  const ov = overrides[row.id];
                  return (
                    <div
                      key={row.id}
                      className={`p-3 flex flex-col sm:flex-row sm:items-center justify-between gap-2 ${
                        row.duplicate ? 'opacity-60 bg-zinc-950/40' : 'bg-zinc-900/30'
                      }`}
                    >
                      <div className="min-w-0 flex-1">
                        <div className="flex items-center gap-2">
                          <span className="font-mono text-zinc-500">{row.txDate}</span>
                          <span className="font-semibold text-zinc-200 truncate">
                            {row.merchantDisplay}
                          </span>
                          <span className="text-[10px] font-mono px-1.5 py-0.5 rounded bg-zinc-800 text-zinc-400">
                            {row.classificationSource}
                          </span>
                        </div>
                        <p className="text-[11px] text-zinc-500 truncate mt-0.5" title={row.rawDescription}>
                          {row.rawDescription}
                        </p>
                      </div>

                      <div className="flex flex-wrap items-center gap-2 shrink-0">
                        <span
                          className={`font-mono font-semibold tabular-nums ${
                            row.baseAmount > 0 ? 'text-emerald-400' : 'text-zinc-100'
                          }`}
                        >
                          {formatExpenseCurrency(row.baseAmount, true, row.baseCurrency, locale)}
                        </span>

                        {!row.duplicate && ov && (
                          <>
                            <select
                              value={ov.category}
                              onChange={(e) => {
                                const nextCat = e.target.value as ExpenseCategory;
                                updateRowOverride(row.id, {
                                  category: nextCat,
                                  rememberRule: shouldAutoRememberRule(
                                    nextCat,
                                    row.category,
                                    row.amount
                                  ),
                                });
                              }}
                              className="bg-zinc-950 border border-zinc-700 text-zinc-100 rounded px-2 py-1 text-xs focus:outline-none focus:border-emerald-500"
                            >
                              {getSelectableCategoriesForAmount(row.amount).map((cat) => (
                                <option key={cat} value={cat}>
                                  {t(getCategoryMeta(cat).labelKey)}
                                </option>
                              ))}
                            </select>

                            <label className="inline-flex items-center gap-1 text-[11px] text-zinc-300 cursor-pointer">
                              <input
                                type="checkbox"
                                checked={ov.rememberRule}
                                onChange={(e) =>
                                  updateRowOverride(row.id, { rememberRule: e.target.checked })
                                }
                                className="accent-emerald-500"
                              />
                              <span>{t('expenses.upload.rememberRule')}</span>
                            </label>

                            <label className="inline-flex items-center gap-1 text-[11px] text-zinc-400 cursor-pointer">
                              <input
                                type="checkbox"
                                checked={ov.excluded}
                                onChange={(e) =>
                                  updateRowOverride(row.id, { excluded: e.target.checked })
                                }
                                className="accent-rose-500"
                              />
                              <span>{t('expenses.upload.excludeRow')}</span>
                            </label>
                          </>
                        )}
                      </div>
                    </div>
                  );
                })
              )}
              {remainingRowsCount > 0 && (
                <div className="p-2.5 text-center bg-zinc-950/60">
                  <button
                    type="button"
                    onClick={() => setVisibleLimit((n) => n + 100)}
                    className="px-3 py-1.5 rounded-lg bg-zinc-800 hover:bg-zinc-700 text-xs font-medium text-zinc-200 transition cursor-pointer"
                  >
                    {t('expenses.table.showMore', { remaining: remainingRowsCount })}
                  </button>
                </div>
              )}
            </div>

            <div className="flex items-center justify-between gap-2 pt-3 border-t border-zinc-800">
              <button
                type="button"
                onClick={() => {
                  setPreview(null);
                  setError(null);
                }}
                className="inline-flex items-center gap-1.5 px-3 py-2 rounded-lg bg-zinc-800 hover:bg-zinc-700 text-zinc-300 font-medium transition cursor-pointer"
              >
                <ArrowLeft className="w-3.5 h-3.5" />
                <span>{t('expenses.upload.backToFile')}</span>
              </button>

              <button
                type="button"
                disabled={committing}
                onClick={handleCommit}
                className="inline-flex items-center gap-1.5 px-4 py-2 rounded-lg bg-emerald-600 hover:bg-emerald-500 text-white font-semibold transition disabled:opacity-50 cursor-pointer"
              >
                {committing && <Loader2 className="w-3.5 h-3.5 animate-spin" />}
                <span>
                  {committing
                    ? t('expenses.upload.committing')
                    : t('expenses.upload.commitAction', { count: newRowsCount })}
                </span>
              </button>
            </div>
          </div>
        )}
      </div>
    </Modal>
  );
};
