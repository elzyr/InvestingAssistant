import React from 'react';
import { ChevronDown } from 'lucide-react';
import type { PortfolioResponse } from '../../types/portfolio.ts';

interface PortfolioSelectorProps {
  portfolios: PortfolioResponse[];
  activePortfolioId: string | null;
  onSelectPortfolio: (id: string) => void;
  ariaLabel?: string;
  showCurrency?: boolean;
  leadingIcon?: React.ReactNode;
  selectClassName?: string;
}

export const PortfolioSelector: React.FC<PortfolioSelectorProps> = ({
  portfolios,
  activePortfolioId,
  onSelectPortfolio,
  ariaLabel,
  showCurrency = false,
  leadingIcon,
  selectClassName = 'appearance-none bg-zinc-900 border border-zinc-700/80 rounded-lg px-2.5 py-1 pr-7 text-xs font-mono font-medium text-zinc-200 hover:border-zinc-600 focus:outline-none focus:border-emerald-500 transition cursor-pointer',
}) => (
  <div className="relative">
    <select
      value={activePortfolioId || ''}
      onChange={(e) => onSelectPortfolio(e.target.value)}
      aria-label={ariaLabel}
      className={selectClassName}
    >
      {portfolios.map((p) => (
        <option key={p.id} value={p.id} className="bg-zinc-900 text-zinc-200">
          {showCurrency ? `${p.name} (${p.baseCurrency})` : p.name}
        </option>
      ))}
    </select>
    {leadingIcon}
    <ChevronDown className="w-3.5 h-3.5 text-zinc-400 absolute right-2 top-1/2 -translate-y-1/2 pointer-events-none" />
  </div>
);
