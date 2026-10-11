import { Search, ChevronDown } from 'lucide-react';
import type { AssetFilterType, SortOption } from '../../types/dashboard.ts';

interface FilterBarProps {
  searchQuery: string;
  onSearchChange: (query: string) => void;
  activeFilter: AssetFilterType;
  onFilterChange: (filter: AssetFilterType) => void;
  sortBy: SortOption;
  onSortChange: (sort: SortOption) => void;
}

const FILTER_LABELS: Array<{ id: AssetFilterType; label: string }> = [
  { id: 'ALL', label: 'Wszystkie' },
  { id: 'ETF', label: 'ETF' },
  { id: 'BOND', label: 'Obligacje' },
  { id: 'STOCK', label: 'Akcje' },
];

export const FilterBar = ({
  searchQuery,
  onSearchChange,
  activeFilter,
  onFilterChange,
  sortBy,
  onSortChange,
}: FilterBarProps) => {
  return (
    <div className="flex flex-col sm:flex-row sm:items-center justify-between gap-2.5 pt-1">
      <div className="flex flex-wrap items-center gap-2">
        <div className="relative">
          <Search className="w-3.5 h-3.5 text-zinc-400 absolute left-2.5 top-1/2 -translate-y-1/2 pointer-events-none" />
          <input
            type="text"
            value={searchQuery}
            onChange={(e) => onSearchChange(e.target.value)}
            placeholder="Szukaj pozycji..."
            className="bg-[#161922] border border-zinc-800 rounded-lg pl-8 pr-3 py-1.5 text-xs text-zinc-200 placeholder-zinc-400 focus:outline-none focus:border-emerald-400 w-44 transition"
          />
        </div>

        <div className="flex items-center gap-1.5 text-xs font-mono">
          {FILTER_LABELS.map(({ id, label }) => {
            const isActive = activeFilter === id;
            return (
              <button
                key={id}
                type="button"
                onClick={() => onFilterChange(id)}
                className={`px-3 py-1.5 rounded-lg border transition cursor-pointer ${
                  isActive
                    ? 'bg-emerald-400 text-zinc-950 font-bold border-emerald-400 shadow-sm shadow-emerald-500/20'
                    : 'bg-[#161922] text-zinc-300 hover:text-white border-zinc-800'
                }`}
              >
                {label}
              </button>
            );
          })}
        </div>
      </div>

      <div className="flex items-center gap-1.5 text-xs text-zinc-300">
        <div className="relative">
          <select
            value={sortBy}
            onChange={(e) => onSortChange(e.target.value as SortOption)}
            className="appearance-none bg-[#161922] border border-zinc-800 hover:border-zinc-700 rounded-lg px-2.5 py-1.5 pr-6 text-xs text-zinc-300 hover:text-white focus:outline-none focus:border-emerald-400 transition cursor-pointer"
          >
            <option value="value" className="bg-zinc-900">Sortuj po: Wartości</option>
            <option value="profit" className="bg-zinc-900">Sortuj po: Zysku</option>
            <option value="share" className="bg-zinc-900">Sortuj po: Udziału</option>
            <option value="name" className="bg-zinc-900">Sortuj po: Nazwy</option>
          </select>
          <ChevronDown className="w-3.5 h-3.5 text-zinc-400 absolute right-2 top-1/2 -translate-y-1/2 pointer-events-none" />
        </div>
      </div>
    </div>
  );
};
