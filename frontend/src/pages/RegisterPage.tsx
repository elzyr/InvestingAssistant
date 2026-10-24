import React, { useState, useEffect } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import { useAppDispatch, useAppSelector } from '../store/hooks.ts';
import { registerThunk } from '../store/thunks/auth.thunk.ts';
import { clearAuthError } from '../store/slices/auth.slice.ts';
import type { Currency } from '../types/auth.ts';
import { Globe } from 'lucide-react';

export const RegisterPage = () => {
  const { t, i18n } = useTranslation();
  const dispatch = useAppDispatch();
  const navigate = useNavigate();

  const { loading, error, isAuthenticated } = useAppSelector((state) => state.auth);

  const [formData, setFormData] = useState({
    firstName: '',
    lastName: '',
    email: '',
    password: '',
    baseCurrency: 'PLN' as Currency,
  });

  useEffect(() => {
    if (isAuthenticated) {
      navigate('/', { replace: true });
    }
    return () => {
      dispatch(clearAuthError());
    };
  }, [isAuthenticated, navigate, dispatch]);

  const handleChange = (e: React.ChangeEvent<HTMLInputElement | HTMLSelectElement>) => {
    setFormData((prev) => ({
      ...prev,
      [e.target.name]: e.target.value,
    }));
  };

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    const result = await dispatch(registerThunk(formData));
    if (registerThunk.fulfilled.match(result)) {
      navigate('/', { replace: true });
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
            {t('auth.registerTitle')}
          </h1>
          <p className="text-xs text-zinc-400 mt-1">
            {t('auth.registerSubtitle')}
          </p>
        </div>

                <div className="bg-[#101115] border border-zinc-800 rounded-lg p-6 shadow-xl">
          {error && (
            <div className="mb-4 p-2.5 rounded bg-rose-500/10 border border-rose-500/20 text-rose-400 text-xs">
              {t(error, { defaultValue: error })}
            </div>
          )}

          <form onSubmit={handleSubmit} className="space-y-3.5">
            <div className="grid grid-cols-2 gap-3">
              <div>
                <label className="block text-xs font-medium text-zinc-300 mb-1.5">
                  {t('auth.firstNameLabel')}
                </label>
                <input
                  type="text"
                  required
                  name="firstName"
                  value={formData.firstName}
                  onChange={handleChange}
                  placeholder={t('auth.firstNamePlaceholder')}
                  className="w-full bg-zinc-900 border border-zinc-800 rounded px-3 py-2 text-sm text-zinc-100 placeholder-zinc-500 focus:outline-none focus:border-zinc-500 transition"
                />
              </div>

              <div>
                <label className="block text-xs font-medium text-zinc-300 mb-1.5">
                  {t('auth.lastNameLabel')}
                </label>
                <input
                  type="text"
                  required
                  name="lastName"
                  value={formData.lastName}
                  onChange={handleChange}
                  placeholder={t('auth.lastNamePlaceholder')}
                  className="w-full bg-zinc-900 border border-zinc-800 rounded px-3 py-2 text-sm text-zinc-100 placeholder-zinc-500 focus:outline-none focus:border-zinc-500 transition"
                />
              </div>
            </div>

            <div>
              <label className="block text-xs font-medium text-zinc-300 mb-1.5">
                {t('auth.emailLabel')}
              </label>
              <input
                type="email"
                required
                name="email"
                value={formData.email}
                onChange={handleChange}
                placeholder={t('auth.emailPlaceholder')}
                className="w-full bg-zinc-900 border border-zinc-800 rounded px-3 py-2 text-sm text-zinc-100 placeholder-zinc-500 focus:outline-none focus:border-zinc-500 transition"
              />
            </div>

            <div>
              <label className="block text-xs font-medium text-zinc-300 mb-1.5">
                {t('auth.passwordLabel')}
              </label>
              <input
                type="password"
                required
                name="password"
                value={formData.password}
                onChange={handleChange}
                placeholder={t('auth.passwordPlaceholder')}
                className="w-full bg-zinc-900 border border-zinc-800 rounded px-3 py-2 text-sm text-zinc-100 placeholder-zinc-500 focus:outline-none focus:border-zinc-500 transition"
              />
            </div>

            <div>
              <label className="block text-xs font-medium text-zinc-300 mb-1.5">
                {t('auth.baseCurrencyLabel')}
              </label>
              <select
                name="baseCurrency"
                value={formData.baseCurrency}
                onChange={handleChange}
                className="w-full bg-zinc-900 border border-zinc-800 rounded px-3 py-2 text-sm text-zinc-100 focus:outline-none focus:border-zinc-500 transition cursor-pointer"
              >
                <option value="PLN">PLN (Polski Złoty)</option>
                <option value="EUR">EUR (Euro)</option>
                <option value="USD">USD (Dolar Amerykański)</option>
              </select>
            </div>

            <button
              type="submit"
              disabled={loading}
              className="w-full mt-2 py-2 px-4 rounded bg-zinc-100 hover:bg-white text-zinc-950 text-sm font-semibold transition disabled:opacity-50 cursor-pointer"
            >
              {loading ? t('common.loading') : t('auth.submitRegister')}
            </button>
          </form>

          <div className="mt-5 pt-4 border-t border-zinc-800/80 text-center text-xs text-zinc-400">
            {t('auth.hasAccount')}{' '}
            <Link to="/login" className="text-zinc-200 hover:underline font-medium ml-1">
              {t('auth.loginLink')}
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
