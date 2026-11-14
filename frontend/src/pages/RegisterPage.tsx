import React, { useState, useEffect } from 'react';
import { useNavigate } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import { useAppDispatch, useAppSelector } from '../store/store.ts';
import { registerThunk } from '../store/thunks/auth.thunk.ts';
import { clearAuthError } from '../store/slices/auth.slice.ts';
import { SUPPORTED_CURRENCIES, type Currency } from '../types/auth.ts';
import { AuthCardLayout } from '../components/auth/AuthCardLayout.tsx';

export const RegisterPage = () => {
  const { t } = useTranslation();
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

  return (
    <AuthCardLayout
      title={t('auth.registerTitle')}
      subtitle={t('auth.registerSubtitle')}
      error={error}
      footerPrompt={t('auth.hasAccount')}
      footerLinkText={t('auth.loginLink')}
      footerLinkTo="/login"
    >
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
            {SUPPORTED_CURRENCIES.map((cur) => (
              <option key={cur} value={cur}>
                {t(`common.currencies.${cur.toLowerCase()}`)}
              </option>
            ))}
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
    </AuthCardLayout>
  );
};
