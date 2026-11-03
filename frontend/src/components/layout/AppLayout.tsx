import React, { useState } from 'react';
import { NavLink, Outlet, useNavigate } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import { useAppSelector, useAppDispatch } from '../../store/hooks.ts';
import { logoutThunk } from '../../store/thunks/auth.thunk.ts';
import {
  LayoutDashboard,
  CalendarDays,
  AlertTriangle,
  LogOut,
  Menu,
  X,
  Globe,
} from 'lucide-react';

export const AppLayout: React.FC = () => {
  const { t, i18n } = useTranslation();
  const dispatch = useAppDispatch();
  const navigate = useNavigate();
  const user = useAppSelector((state) => state.auth.user);
  const [mobileMenuOpen, setMobileMenuOpen] = useState(false);

  const handleLogout = async () => {
    await dispatch(logoutThunk());
    navigate('/login');
  };

  const toggleLanguage = () => {
    const nextLang = i18n.language.startsWith('pl') ? 'en' : 'pl';
    i18n.changeLanguage(nextLang);
  };

  const navItems = [
    { to: '/', label: t('nav.overview'), icon: LayoutDashboard },
    { to: '/reports', label: t('nav.reports'), icon: CalendarDays },
    { to: '/stress-testing', label: t('nav.stressTesting'), icon: AlertTriangle },
  ];

  return (
    <div className="min-h-screen bg-[#09090b] text-zinc-100 flex flex-col md:flex-row antialiased">
      <aside className="w-60 border-r border-zinc-800/80 bg-[#0c0d12] p-4 flex flex-col justify-between hidden md:flex shrink-0">
        <div className="space-y-6">
          <div className="flex items-center justify-between px-2">
            <div className="flex items-center gap-2.5">
              <div className="w-7 h-7 rounded bg-zinc-100 text-zinc-950 font-bold text-xs flex items-center justify-center">
                IA
              </div>
              <span className="font-semibold text-sm text-zinc-200 tracking-tight">
                InvestingAssistant
              </span>
            </div>
            <button
              onClick={toggleLanguage}
              className="p-1.5 rounded-lg border border-zinc-800 bg-zinc-900/50 hover:bg-zinc-800 text-zinc-400 hover:text-zinc-200 transition text-[11px] font-mono flex items-center gap-1 cursor-pointer"
              title={t('common.language')}
            >
              <Globe className="w-3.5 h-3.5" />
              <span className="uppercase font-semibold">{i18n.language.slice(0, 2)}</span>
            </button>
          </div>

          <nav className="space-y-1">
            {navItems.map((item) => (
              <NavLink
                key={item.to}
                to={item.to}
                className={({ isActive }) =>
                  `flex items-center gap-3 px-3 py-2 rounded-lg text-xs font-medium transition ${
                    isActive
                      ? 'bg-zinc-800/80 text-white font-semibold'
                      : 'text-zinc-400 hover:text-zinc-200 hover:bg-zinc-900/60'
                  }`
                }
              >
                <item.icon className="w-4 h-4" />
                {item.label}
              </NavLink>
            ))}
          </nav>
        </div>

        <div className="border-t border-zinc-800/80 pt-4 px-2">
          <div className="flex items-center justify-between">
            <div className="overflow-hidden">
              <p className="text-xs font-semibold text-zinc-200 truncate">
                {user ? `${user.firstName} ${user.lastName}` : t('common.user')}
              </p>
              <p className="text-[11px] text-zinc-400 font-mono truncate">
                {t('nav.base')}: {user?.baseCurrency || 'PLN'}
              </p>
            </div>
            <button
              onClick={handleLogout}
              className="p-1.5 rounded-lg text-zinc-400 hover:text-rose-400 hover:bg-zinc-900/80 transition cursor-pointer"
              title={t('common.logout')}
            >
              <LogOut className="w-4 h-4" />
            </button>
          </div>
        </div>
      </aside>

      <header className="md:hidden border-b border-zinc-800/80 bg-[#0c0d12] p-4 flex items-center justify-between">
        <div className="flex items-center gap-2">
          <div className="w-6 h-6 rounded bg-zinc-100 text-zinc-950 font-bold text-xs flex items-center justify-center">
            IA
          </div>
          <span className="font-semibold text-sm text-zinc-200 tracking-tight">
            InvestingAssistant
          </span>
        </div>
        <div className="flex items-center gap-2">
          <button
            onClick={toggleLanguage}
            className="p-1.5 rounded-lg border border-zinc-800 bg-zinc-900/50 hover:bg-zinc-800 text-zinc-400 hover:text-zinc-200 transition text-[11px] font-mono flex items-center gap-1 cursor-pointer"
            title={t('common.language')}
          >
            <Globe className="w-3.5 h-3.5" />
            <span className="uppercase font-semibold">{i18n.language.slice(0, 2)}</span>
          </button>
          <button
            onClick={() => setMobileMenuOpen(!mobileMenuOpen)}
            className="p-1 rounded-lg text-zinc-400 hover:text-zinc-200 hover:bg-zinc-900 transition"
          >
            {mobileMenuOpen ? <X className="w-5 h-5" /> : <Menu className="w-5 h-5" />}
          </button>
        </div>
      </header>

      {mobileMenuOpen && (
        <div className="md:hidden border-b border-zinc-800/80 bg-[#0c0d12] p-4 space-y-3">
          <nav className="space-y-1">
            {navItems.map((item) => (
              <NavLink
                key={item.to}
                to={item.to}
                onClick={() => setMobileMenuOpen(false)}
                className={({ isActive }) =>
                  `flex items-center gap-3 px-3 py-2 rounded-lg text-xs font-medium transition ${
                    isActive
                      ? 'bg-zinc-800/80 text-white font-semibold'
                      : 'text-zinc-400 hover:text-zinc-200 hover:bg-zinc-900/60'
                  }`
                }
              >
                <item.icon className="w-4 h-4" />
                {item.label}
              </NavLink>
            ))}
          </nav>
          <div className="border-t border-zinc-800/80 pt-3 flex items-center justify-between">
            <span className="text-xs text-zinc-400">
              {user?.email} ({user?.baseCurrency})
            </span>
            <button
              onClick={handleLogout}
              className="flex items-center gap-1.5 text-xs text-zinc-400 hover:text-rose-400 transition"
            >
              <LogOut className="w-3.5 h-3.5" />
              {t('common.logout')}
            </button>
          </div>
        </div>
      )}

      <main className="flex-1 flex flex-col min-w-0 bg-[#0a0a0e] overflow-y-auto">
        <Outlet />
      </main>
    </div>
  );
};
