import React, { useState, useEffect } from 'react';
import { Link, useNavigate, useLocation } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import { useAppDispatch, useAppSelector } from '../store/hooks.ts';
import { loginThunk } from '../store/thunks/auth.thunk.ts';
import { clearAuthError } from '../store/slices/auth.slice.ts';
import { Globe } from 'lucide-react';

export const LoginPage = () => {
  const { t, i18n } = useTranslation();
  const dispatch = useAppDispatch();
  const navigate = useNavigate();
  const location = useLocation();

  const { loading, error, isAuthenticated } = useAppSelector((state) => state.auth);

  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');

  const from = (location.state as { from?: { pathname: string } })?.from?.pathname || '/';

  useEffect(() => {
    if (isAuthenticated) {
      navigate(from, { replace: true });
    }
    return () => {
      dispatch(clearAuthError());
    };
  }, [isAuthenticated, navigate, from, dispatch]);

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!email || !password) return;

    const result = await dispatch(loginThunk({ email, password }));
    if (loginThunk.fulfilled.match(result)) {
      navigate(from, { replace: true });
    }
  };

  const toggleLanguage = (lang: string) => {
    i18n.changeLanguage(lang);
  };

  return (
    <div className="min-h-screen bg-[#09090b] flex flex-col items-center justify-center p-4 antialiased">
      <div className="w-full max-w-sm">
        
                <div className="mb-6 text-center">
          <div className="inline-flex items-center justify-center w-8 h-8 rounded bg-zinc-100 text-zinc-950 font-bold text-xs mb-3">
            IA
          </div>
          <h1 className="text-xl font-semibold text-zinc-100 tracking-tight">
            {t('auth.loginTitle')}
          </h1>
          <p className="text-xs text-zinc-400 mt-1">
            {t('auth.loginSubtitle')}
          </p>
        </div>

                <div className="bg-[#101115] border border-zinc-800 rounded-lg p-6 shadow-xl">
          {error && (
            <div className="mb-4 p-2.5 rounded bg-rose-500/10 border border-rose-500/20 text-rose-400 text-xs">
              {t(error, { defaultValue: error })}
            </div>
          )}

          <form onSubmit={handleSubmit} className="space-y-4">
            <div>
              <label className="block text-xs font-medium text-zinc-300 mb-1.5">
                {t('auth.emailLabel')}
              </label>
              <input
                type="email"
                required
                value={email}
                onChange={(e) => setEmail(e.target.value)}
                placeholder={t('auth.emailPlaceholder')}
                className="w-full bg-zinc-900 border border-zinc-800 rounded px-3 py-2 text-sm text-zinc-100 placeholder-zinc-500 focus:outline-none focus:border-zinc-500 transition"
              />
            </div>

            <div>
              <div className="flex items-center justify-between mb-1.5">
                <label className="block text-xs font-medium text-zinc-300">
                  {t('auth.passwordLabel')}
                </label>
              </div>
              <input
                type="password"
                required
                value={password}
                onChange={(e) => setPassword(e.target.value)}
                placeholder={t('auth.passwordPlaceholder')}
                className="w-full bg-zinc-900 border border-zinc-800 rounded px-3 py-2 text-sm text-zinc-100 placeholder-zinc-500 focus:outline-none focus:border-zinc-500 transition"
              />
            </div>

            <button
              type="submit"
              disabled={loading}
              className="w-full mt-2 py-2 px-4 rounded bg-zinc-100 hover:bg-white text-zinc-950 text-sm font-semibold transition disabled:opacity-50 cursor-pointer"
            >
              {loading ? t('common.loading') : t('auth.submitLogin')}
            </button>
          </form>

          <div className="mt-5 pt-4 border-t border-zinc-800/80 text-center text-xs text-zinc-400">
            {t('auth.noAccount')}{' '}
            <Link to="/register" className="text-zinc-200 hover:underline font-medium ml-1">
              {t('auth.registerLink')}
            </Link>
          </div>
        </div>

                <div className="mt-6 flex items-center justify-center gap-3 text-xs text-zinc-500">
          <Globe className="w-3.5 h-3.5 text-zinc-500" />
          <button
            onClick={() => toggleLanguage('pl')}
            className={`transition ${i18n.language.startsWith('pl') ? 'text-zinc-200 font-semibold' : 'hover:text-zinc-300'}`}
          >
            Polski
          </button>
          <span>·</span>
          <button
            onClick={() => toggleLanguage('en')}
            className={`transition ${i18n.language.startsWith('en') ? 'text-zinc-200 font-semibold' : 'hover:text-zinc-300'}`}
          >
            English
          </button>
        </div>

      </div>
    </div>
  );
};
