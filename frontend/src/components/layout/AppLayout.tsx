import { NavLink, Outlet, useNavigate } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import { useAppDispatch, useAppSelector } from '../../store/hooks.ts';
import { logoutThunk } from '../../store/thunks/auth.thunk.ts';
import { 
  LayoutDashboard, 
  LogOut, 
  Globe,
  AlertTriangle,
  CalendarDays
} from 'lucide-react';

export const AppLayout = () => {
  const { t, i18n } = useTranslation();
  const dispatch = useAppDispatch();
  const navigate = useNavigate();
  const { user } = useAppSelector((state) => state.auth);

  const handleLogout = async () => {
    await dispatch(logoutThunk());
    navigate('/login');
  };

  const toggleLanguage = () => {
    const nextLang = i18n.language.startsWith('pl') ? 'en' : 'pl';
    i18n.changeLanguage(nextLang);
  };

  const navItems = [
    { to: '/', label: t('nav.overview', 'Przegląd portfela'), icon: LayoutDashboard },
    { to: '/reports', label: 'Raporty miesięczne', icon: CalendarDays },
    { to: '/stress-testing', label: 'Stress-Testing', icon: AlertTriangle },
  ];

  return (
    <div className="min-h-screen bg-[#09090b] text-zinc-100 flex flex-col md:flex-row antialiased">
      {/* Sidebar for Desktop */}
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
          </div>

          <nav className="space-y-1 text-xs">
            {navItems.map((item) => {
              const Icon = item.icon;
              return (
                <NavLink
                  key={item.to}
                  to={item.to}
                  end={item.to === '/'}
                  className={({ isActive }) =>
                    `flex items-center gap-2.5 px-3 py-2 rounded-md font-medium transition ${
                      isActive
                        ? 'bg-zinc-800 text-white'
                        : 'text-zinc-400 hover:text-zinc-200 hover:bg-zinc-800/40'
                    }`
                  }
                >
                  <Icon className="w-4 h-4 text-zinc-400" />
                  <span>{item.label}</span>
                </NavLink>
              );
            })}
          </nav>
        </div>

        {/* User profile & controls */}
        <div className="pt-3 border-t border-zinc-800/80 space-y-3">
          <div className="flex items-center justify-between px-1">
            <button
              onClick={toggleLanguage}
              className="flex items-center gap-1.5 text-xs text-zinc-400 hover:text-zinc-200 transition"
              title="Przełącz język / Switch language"
            >
              <Globe className="w-3.5 h-3.5" />
              <span className="uppercase font-mono text-[11px]">{i18n.language.slice(0, 2)}</span>
            </button>
            <span className="text-[11px] font-mono text-zinc-500">
              {t('nav.base')}: {user?.baseCurrency || 'PLN'}
            </span>
          </div>

          <div className="flex items-center justify-between bg-zinc-900/60 p-2.5 rounded-lg border border-zinc-800/60 text-xs">
            <div className="truncate mr-2">
              <div className="font-medium text-zinc-200 truncate">
                {user?.firstName ? `${user.firstName} ${user.lastName}` : user?.email || 'Użytkownik'}
              </div>
              <div className="text-[11px] text-zinc-500 truncate">{user?.email}</div>
            </div>
            <button
              onClick={handleLogout}
              className="text-zinc-400 hover:text-rose-400 p-1.5 rounded hover:bg-zinc-800 transition"
              title={t('common.logout')}
            >
              <LogOut className="w-4 h-4" />
            </button>
          </div>
        </div>
      </aside>

      {/* Mobile Header */}
      <header className="md:hidden border-b border-zinc-800 bg-[#0c0d12] px-4 py-3 flex items-center justify-between">
        <div className="flex items-center gap-2">
          <div className="w-6 h-6 rounded bg-zinc-100 text-zinc-950 font-bold text-xs flex items-center justify-center">
            IA
          </div>
          <span className="font-semibold text-sm text-zinc-200">InvestingAssistant</span>
        </div>
        <div className="flex items-center gap-3">
          <button onClick={toggleLanguage} className="text-xs uppercase font-mono text-zinc-400">
            {i18n.language.slice(0, 2)}
          </button>
          <button onClick={handleLogout} className="text-zinc-400 hover:text-rose-400">
            <LogOut className="w-4 h-4" />
          </button>
        </div>
      </header>

      {/* Mobile Navigation Bar */}
      <nav className="md:hidden border-b border-zinc-800 bg-[#0c0d12] px-2 py-1.5 flex items-center justify-around text-xs overflow-x-auto">
        {navItems.map((item) => {
          const Icon = item.icon;
          return (
            <NavLink
              key={item.to}
              to={item.to}
              end={item.to === '/'}
              className={({ isActive }) =>
                `flex items-center gap-1.5 px-2.5 py-1.5 rounded-md font-medium text-xs whitespace-nowrap transition ${
                  isActive
                    ? 'bg-zinc-800 text-white font-semibold'
                    : 'text-zinc-400 hover:text-zinc-200'
                }`
              }
            >
              <Icon className="w-3.5 h-3.5" />
              <span>{item.label}</span>
            </NavLink>
          );
        })}
      </nav>

      {/* Main Content Area */}
      <main className="flex-1 flex flex-col min-w-0 overflow-y-auto">
        <Outlet />
      </main>
    </div>
  );
};
