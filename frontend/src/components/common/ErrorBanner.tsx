import React from 'react';
import { useTranslation } from 'react-i18next';
import { AlertTriangle, X } from 'lucide-react';

interface ErrorBannerProps {
  error: string | null;
  onDismiss?: () => void;
  className?: string;
}

export const ErrorBanner: React.FC<ErrorBannerProps> = ({
  error,
  onDismiss,
  className,
}) => {
  const { t } = useTranslation();
  if (!error) return null;

  const displayError =
    error.startsWith('errors.') || error.startsWith('auth.errors.') ? t(error) : error;

  if (!onDismiss) {
    return (
      <div
        role="alert"
        className={
          className || 'p-2.5 rounded bg-rose-950/40 border border-rose-800/60 text-xs text-rose-300'
        }
      >
        {displayError}
      </div>
    );
  }

  return (
    <div
      role="alert"
      className={
        className ||
        'flex items-center justify-between gap-3 px-4 py-2.5 rounded-xl bg-rose-950/50 border border-rose-800/60 text-xs text-rose-200'
      }
    >
      <div className="flex items-center gap-2 min-w-0">
        <AlertTriangle className="w-4 h-4 text-rose-400 shrink-0" />
        <span className="truncate">{displayError}</span>
      </div>
      <button
        type="button"
        onClick={onDismiss}
        aria-label={t('common.close')}
        className="p-1 rounded hover:bg-rose-900/50 text-rose-300 hover:text-white transition cursor-pointer shrink-0"
      >
        <X className="w-3.5 h-3.5" />
      </button>
    </div>
  );
};
