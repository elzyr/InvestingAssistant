import React, { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { Loader2, FolderPlus } from 'lucide-react';
import { Modal } from '../common/Modal.tsx';
import { ErrorBanner } from '../common/ErrorBanner.tsx';
import { useAppDispatch } from '../../store/store.ts';
import { createPortfolio } from '../../store/thunks/portfolio.thunk.ts';
import { SUPPORTED_CURRENCIES, type Currency } from '../../types/auth.ts';

interface CreatePortfolioModalProps {
  isOpen: boolean;
  onClose: () => void;
  defaultCurrency?: Currency;
}

interface CreatePortfolioFormProps {
  onClose: () => void;
  defaultCurrency: Currency;
}

const CreatePortfolioForm: React.FC<CreatePortfolioFormProps> = ({
  onClose,
  defaultCurrency,
}) => {
  const { t } = useTranslation();
  const dispatch = useAppDispatch();
  const [name, setName] = useState('');
  const [description, setDescription] = useState('');
  const [baseCurrency, setBaseCurrency] = useState<Currency>(defaultCurrency);
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<string | null>(null);

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
        })
      ).unwrap();

      onClose();
    } catch (err: unknown) {
      setError(typeof err === 'string' ? err : t('portfolioModal.createError'));
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <form onSubmit={handleSubmit} className="p-5 space-y-4">
      <ErrorBanner
        error={error}
        className="p-2.5 rounded-lg bg-rose-500/10 border border-rose-500/20 text-rose-400 text-xs font-mono"
      />

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

      <div>
        <label className="block text-xs font-medium text-zinc-300 mb-1">
          {t('portfolioModal.currencyLabel')}
        </label>
        <select
          value={baseCurrency}
          onChange={(e) => setBaseCurrency(e.target.value as Currency)}
          className="w-full px-3 py-2 text-xs font-mono bg-zinc-900 border border-zinc-800 rounded-lg text-zinc-200 focus:outline-none focus:border-indigo-500 transition"
        >
          {SUPPORTED_CURRENCIES.map((cur) => (
            <option key={cur} value={cur}>
              {t(`common.currencies.${cur.toLowerCase()}`)}
            </option>
          ))}
        </select>
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
  );
};

export const CreatePortfolioModal: React.FC<CreatePortfolioModalProps> = ({
  isOpen,
  onClose,
  defaultCurrency = 'PLN',
}) => {
  const { t } = useTranslation();

  if (!isOpen) return null;

  return (
    <Modal
      isOpen={true}
      onClose={onClose}
      panelClassName="relative bg-zinc-950 border border-zinc-800 rounded-xl shadow-2xl overflow-hidden"
      headerClassName="flex items-center justify-between px-5 py-4 border-b border-zinc-800/80 bg-zinc-900/40"
      headerIcon={
        <div className="p-1.5 rounded-lg bg-indigo-500/10 text-indigo-400 border border-indigo-500/20">
          <FolderPlus className="w-4 h-4" />
        </div>
      }
      title={<h2 className="text-sm font-semibold text-zinc-100">{t('portfolioModal.createTitle')}</h2>}
      subtitle={t('portfolioModal.createSubtitle')}
      closeTitle={t('common.cancel')}
    >
      <CreatePortfolioForm onClose={onClose} defaultCurrency={defaultCurrency} />
    </Modal>
  );
};
