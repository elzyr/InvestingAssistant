import React, { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { X, Loader2, FolderPlus } from 'lucide-react';
import { useAppDispatch } from '../../store/hooks.ts';
import { createPortfolio } from '../../store/thunks/portfolio.thunk.ts';
import type { Currency } from '../../types/auth.ts';
import type { AccountType } from '../../types/portfolio.ts';

interface CreatePortfolioModalProps {
  isOpen: boolean;
  onClose: () => void;
  defaultCurrency?: Currency;
}

export const CreatePortfolioModal: React.FC<CreatePortfolioModalProps> = ({
  isOpen,
  onClose,
  defaultCurrency = 'PLN',
}) => {
  const { t } = useTranslation();
  const dispatch = useAppDispatch();
  const [name, setName] = useState('');
  const [description, setDescription] = useState('');
  const [baseCurrency, setBaseCurrency] = useState<Currency>(defaultCurrency);
  const [accountType, setAccountType] = useState<AccountType>('STANDARD');
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<string | null>(null);

  if (!isOpen) return null;

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!name.trim()) {
      setError(t('portfolioModal.nameRequired'));
      return;
    }

    setSubmitting(true);
    setError(null);

    try {
      await dispatch(
        createPortfolio({
          name: name.trim(),
          description: description.trim() || undefined,
          baseCurrency,
          accountType,
        })
      ).unwrap();

      setName('');
      setDescription('');
      setBaseCurrency(defaultCurrency);
      setAccountType('STANDARD');
      onClose();
    } catch (err: unknown) {
      setError(typeof err === 'string' ? err : t('portfolioModal.createError'));
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center p-4 bg-black/80 backdrop-blur-sm animate-in fade-in duration-150">
      <div className="relative w-full max-w-md bg-zinc-950 border border-zinc-800 rounded-xl shadow-2xl overflow-hidden">
        <div className="flex items-center justify-between px-5 py-4 border-b border-zinc-800/80 bg-zinc-900/40">
          <div className="flex items-center gap-2">
            <div className="p-1.5 rounded-lg bg-indigo-500/10 text-indigo-400 border border-indigo-500/20">
              <FolderPlus className="w-4 h-4" />
            </div>
            <div>
              <h2 className="text-sm font-semibold text-zinc-100">{t('portfolioModal.createTitle')}</h2>
              <p className="text-[11px] text-zinc-400">{t('portfolioModal.createSubtitle')}</p>
            </div>
          </div>
          <button
            onClick={onClose}
            className="p-1 text-zinc-400 hover:text-zinc-200 hover:bg-zinc-800 rounded-md transition"
          >
            <X className="w-4 h-4" />
          </button>
        </div>

        <form onSubmit={handleSubmit} className="p-5 space-y-4">
          {error && (
            <div className="p-2.5 rounded-lg bg-rose-500/10 border border-rose-500/20 text-rose-400 text-xs font-mono">
              {error}
            </div>
          )}

          <div>
            <label className="block text-xs font-medium text-zinc-300 mb-1">
              {t('portfolioModal.nameLabel')} <span className="text-rose-400">*</span>
            </label>
            <input
              type="text"
              value={name}
              onChange={(e) => setName(e.target.value)}
              placeholder={t('portfolioModal.namePlaceholder')}
              className="w-full px-3 py-2 text-xs bg-zinc-900 border border-zinc-800 rounded-lg text-zinc-200 placeholder-zinc-500 focus:outline-none focus:border-indigo-500 transition"
              autoFocus
            />
          </div>

          <div>
            <label className="block text-xs font-medium text-zinc-300 mb-1">
              {t('portfolioModal.descLabel')}
            </label>
            <input
              type="text"
              value={description}
              onChange={(e) => setDescription(e.target.value)}
              placeholder={t('portfolioModal.descPlaceholder')}
              className="w-full px-3 py-2 text-xs bg-zinc-900 border border-zinc-800 rounded-lg text-zinc-200 placeholder-zinc-500 focus:outline-none focus:border-indigo-500 transition"
            />
          </div>

          <div className="grid grid-cols-2 gap-3">
            <div>
              <label className="block text-xs font-medium text-zinc-300 mb-1">
                {t('portfolioModal.currencyLabel')}
              </label>
              <select
                value={baseCurrency}
                onChange={(e) => setBaseCurrency(e.target.value as Currency)}
                className="w-full px-3 py-2 text-xs font-mono bg-zinc-900 border border-zinc-800 rounded-lg text-zinc-200 focus:outline-none focus:border-indigo-500 transition"
              >
                <option value="PLN">{t('common.currencies.pln')}</option>
                <option value="EUR">{t('common.currencies.eur')}</option>
                <option value="USD">{t('common.currencies.usd')}</option>
              </select>
            </div>

            <div>
              <label className="block text-xs font-medium text-zinc-300 mb-1">
                {t('portfolioModal.accountTypeLabel')}
              </label>
              <select
                value={accountType}
                onChange={(e) => setAccountType(e.target.value as AccountType)}
                className="w-full px-3 py-2 text-xs bg-zinc-900 border border-zinc-800 rounded-lg text-zinc-200 focus:outline-none focus:border-indigo-500 transition"
              >
                <option value="STANDARD">{t('portfolioModal.accountTypeStandard')}</option>
                <option value="IKE">{t('portfolioModal.accountTypeIke')}</option>
                <option value="IKZE">{t('portfolioModal.accountTypeIkze')}</option>
              </select>
            </div>
          </div>

          <div className="pt-2 flex items-center justify-end gap-2 border-t border-zinc-800/60">
            <button
              type="button"
              onClick={onClose}
              disabled={submitting}
              className="px-3 py-1.5 text-xs font-medium text-zinc-400 hover:text-zinc-200 hover:bg-zinc-900 rounded-lg transition"
            >
              {t('common.cancel')}
            </button>
            <button
              type="submit"
              disabled={submitting || !name.trim()}
              className="flex items-center gap-1.5 px-4 py-1.5 text-xs font-medium text-white bg-indigo-600 hover:bg-indigo-500 disabled:opacity-50 disabled:hover:bg-indigo-600 rounded-lg shadow-sm transition"
            >
              {submitting && <Loader2 className="w-3.5 h-3.5 animate-spin" />}
              <span>{t('portfolioModal.submit')}</span>
            </button>
          </div>
        </form>
      </div>
    </div>
  );
};
