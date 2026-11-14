import React from 'react';
import { Link } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import { Globe } from 'lucide-react';
import { ErrorBanner } from '../common/ErrorBanner.tsx';

interface AuthCardLayoutProps {
  title: string;
  subtitle: string;
  error: string | null;
  footerPrompt: string;
  footerLinkText: string;
  footerLinkTo: string;
  children: React.ReactNode;
}

export const AuthCardLayout: React.FC<AuthCardLayoutProps> = ({
  title,
  subtitle,
  error,
  footerPrompt,
  footerLinkText,
  footerLinkTo,
  children,
}) => {
  const { t, i18n } = useTranslation();

  return (
    <div className="min-h-screen bg-[#09090b] flex flex-col items-center justify-center p-4 antialiased">
      <div className="w-full max-w-sm">
        <div className="mb-6 text-center">
          <div className="inline-flex items-center justify-center w-8 h-8 rounded bg-zinc-100 text-zinc-950 font-bold text-xs mb-3">
            IA
          </div>
          <h1 className="text-xl font-semibold text-zinc-100 tracking-tight">{title}</h1>
          <p className="text-xs text-zinc-400 mt-1">{subtitle}</p>
        </div>

        <div className="bg-[#101115] border border-zinc-800 rounded-lg p-6 shadow-xl">
          <ErrorBanner
            error={error ? t(error, { defaultValue: error }) : null}
            className="mb-4 p-2.5 rounded bg-rose-500/10 border border-rose-500/20 text-rose-400 text-xs"
          />

          {children}

          <div className="mt-5 pt-4 border-t border-zinc-800/80 text-center text-xs text-zinc-400">
            {footerPrompt}{' '}
            <Link to={footerLinkTo} className="text-zinc-200 hover:underline font-medium ml-1">
              {footerLinkText}
            </Link>
          </div>
        </div>

        <div className="mt-6 flex items-center justify-center gap-3 text-xs text-zinc-500">
          <Globe className="w-3.5 h-3.5 text-zinc-500" />
          {[
            { code: 'pl', label: 'Polski' },
            { code: 'en', label: 'English' },
          ].map((lang, idx) => (
            <React.Fragment key={lang.code}>
              {idx > 0 && <span>·</span>}
              <button
                type="button"
                onClick={() => i18n.changeLanguage(lang.code)}
                className={`transition ${
                  i18n.language.startsWith(lang.code)
                    ? 'text-zinc-200 font-semibold'
                    : 'hover:text-zinc-300'
                }`}
              >
                {lang.label}
              </button>
            </React.Fragment>
          ))}
        </div>
      </div>
    </div>
  );
};
