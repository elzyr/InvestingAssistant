import { Plus, RefreshCw, ChevronDown, FolderPlus } from 'lucide-react';
import type { PortfolioResponse } from '../../types/portfolio.ts';
import type { TaxMode } from '../../types/dashboard.ts';

interface DashboardHeaderProps {
  title?: string;
  positionCount: number;
  portfolios: PortfolioResponse[];
  activePortfolioId: string | null;
  loading: boolean;
  taxMode: TaxMode;
  onTaxModeChange: (mode: TaxMode) => void;
  onSelectPortfolio: (id: string) => void;
  onCreatePortfolio: () => void;
  onRefresh: () => void;
  onAddTransaction: () => void;
}

export const DashboardHeader = ({
  title = 'Pulpit Portfela',
  positionCount,
  portfolios,
  activePortfolioId,
  loading,
  taxMode,
  onTaxModeChange,
  onSelectPortfolio,
  onCreatePortfolio,
  onRefresh,
  onAddTransaction,
}: DashboardHeaderProps) => {
  return (
    <div className="flex flex-col sm:flex-row sm:items-center justify-between gap-3 border-b border-zinc-800/80 pb-3.5">
      <div className="flex flex-wrap items-center gap-2.5">
        <h1 className="text-base font-bold text-white tracking-tight">{title}</h1>

        {portfolios.length > 0 && (
          <div className="flex items-center gap-1.5">
            <div className="relative">
              <select
                value={activePortfolioId || ''}
                onChange={(e) => onSelectPortfolio(e.target.value)}
                className="appearance-none bg-zinc-900 border border-zinc-750 border-zinc-700/80 rounded-lg px-2.5 py-1 pr-7 text-xs font-mono font-medium text-zinc-200 hover:border-zinc-600 focus:outline-none focus:border-emerald-500 transition cursor-pointer"
              >
                {portfolios.map((p) => (
                  <option key={p.id} value={p.id} className="bg-zinc-900 text-zinc-200">
                    {p.name} ({p.baseCurrency})
                  </option>
                ))}
              </select>
              <ChevronDown className="w-3.5 h-3.5 text-zinc-400 absolute right-2 top-1/2 -translate-y-1/2 pointer-events-none" />
            </div>

            <button
              onClick={onCreatePortfolio}
              title="Utwórz nowy portfel"
              className="p-1 rounded-lg bg-zinc-900 hover:bg-zinc-800 border border-zinc-700/80 text-zinc-400 hover:text-zinc-200 transition cursor-pointer"
            >
              <FolderPlus className="w-3.5 h-3.5" />
            </button>
          </div>
        )}

        <span className="text-xs font-mono font-medium text-zinc-300 bg-zinc-800/90 border border-zinc-700/60 px-2 py-0.5 rounded-md">
          {positionCount} pozycji
        </span>

        <div className="flex items-center bg-[#161922] border border-zinc-800 rounded-lg p-0.5 text-xs font-mono">
          <button
            type="button"
            onClick={() => onTaxModeChange('NETTO')}
            title="Zysk netto po uwzględnieniu podatku Belki (19%)"
            className={`px-2 py-0.5 rounded-md text-[11px] transition cursor-pointer ${
              taxMode === 'NETTO'
                ? 'bg-emerald-400 text-zinc-950 font-bold shadow-xs'
                : 'text-zinc-400 hover:text-zinc-200'
            }`}
          >
            Netto
          </button>
          <button
            type="button"
            onClick={() => onTaxModeChange('BRUTTO')}
            title="Zysk brutto (przed podatkiem Belki)"
            className={`px-2 py-0.5 rounded-md text-[11px] transition cursor-pointer ${
              taxMode === 'BRUTTO'
                ? 'bg-emerald-400 text-zinc-950 font-bold shadow-xs'
                : 'text-zinc-400 hover:text-zinc-200'
            }`}
          >
            Brutto
          </button>
        </div>
      </div>


      <div className="flex items-center gap-2">
        <button
          onClick={onRefresh}
          disabled={loading}
          title="Odśwież dane i wyceny"
          className="p-1.5 rounded-lg bg-zinc-900 hover:bg-zinc-800 border border-zinc-700/80 text-zinc-300 transition cursor-pointer disabled:opacity-50"
        >
          <RefreshCw className={`w-3.5 h-3.5 ${loading ? 'animate-spin text-emerald-400' : ''}`} />
        </button>

        <button
          onClick={onAddTransaction}
          className="flex items-center gap-1.5 px-3.5 py-1.5 rounded-lg bg-emerald-400 hover:bg-emerald-300 text-zinc-950 font-bold text-xs tracking-tight shadow-md hover:shadow-emerald-500/20 transition-all cursor-pointer"
        >
          <Plus className="w-3.5 h-3.5 stroke-[2.5]" />
          <span>Transakcja</span>
        </button>
      </div>
    </div>
  );
};
