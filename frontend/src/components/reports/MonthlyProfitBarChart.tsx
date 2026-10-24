import React, { useEffect, useMemo, useRef, useState } from 'react';
import {
  createChart,
  ColorType,
  CrosshairMode,
  HistogramSeries,
  type IChartApi,
  type ISeriesApi,
  type Time,
  type HistogramData,
} from 'lightweight-charts';
import type { MonthlyReportRecord } from '../../types/reports.types.ts';
import { formatPLN, formatPercent } from '../../utils/reportsFormatters.ts';

interface MonthlyProfitBarChartProps {
  records: MonthlyReportRecord[];
  onSelectMonth?: (monthIndex: number) => void;
  selectedMonthIndex?: number;
}

const POLISH_MONTHS = ['Sty', 'Lut', 'Mar', 'Kwi', 'Maj', 'Cze', 'Lip', 'Sie', 'Wrz', 'Paź', 'Lis', 'Gru'];

const normalizeDateStr = (time: Time | unknown): string => {
  if (!time) return '';
  if (typeof time === 'string') return time;
  if (typeof time === 'object' && time !== null && 'year' in time && 'month' in time) {
    const obj = time as { year: number; month: number; day?: number };
    return `${obj.year}-${String(obj.month).padStart(2, '0')}-01`;
  }
  if (typeof time === 'number') {
    const d = new Date(time * 1000);
    return `${d.getUTCFullYear()}-${String(d.getUTCMonth() + 1).padStart(2, '0')}-01`;
  }
  return '';
};

export const MonthlyProfitBarChart: React.FC<MonthlyProfitBarChartProps> = ({
  records,
  onSelectMonth,
  selectedMonthIndex,
}) => {
  const containerRef = useRef<HTMLDivElement | null>(null);
  const chartRef = useRef<IChartApi | null>(null);
  const seriesRef = useRef<ISeriesApi<'Histogram'> | null>(null);
  const [hoveredRecord, setHoveredRecord] = useState<MonthlyReportRecord | null>(null);

  const recordedRecords = useMemo(
    () => records.filter((r) => r.status === 'RECORDED'),
    [records]
  );
  const onSelectMonthRef = useRef(onSelectMonth);
  useEffect(() => {
    onSelectMonthRef.current = onSelectMonth;
  }, [onSelectMonth]);

  useEffect(() => {
    if (!containerRef.current || recordedRecords.length === 0) return;

    if (chartRef.current) {
      chartRef.current.remove();
      chartRef.current = null;
    }

    const container = containerRef.current;
    const chart = createChart(container, {
      width: container.clientWidth || 800,
      height: 180,
      layout: {
        background: { type: ColorType.Solid, color: 'transparent' },
        textColor: '#71717a',
        fontFamily: 'monospace',
        fontSize: 11,
      },
      grid: {
        vertLines: { color: '#181b26' },
        horzLines: { color: '#181b26' },
      },
      crosshair: {
        mode: CrosshairMode.Normal,
      },
      timeScale: {
        borderColor: '#27272a',
        timeVisible: false,
        secondsVisible: false,
        barSpacing: 36,
        minBarSpacing: 16,
        tickMarkFormatter: (time: Time) => {
          const dateStr = normalizeDateStr(time);
          const d = new Date(dateStr);
          return POLISH_MONTHS[d.getMonth()] || '';
        },
      },
      rightPriceScale: {
        borderColor: '#27272a',
        autoScale: true,
      },
      localization: {
        priceFormatter: (val: number) => `${val >= 0 ? '+' : ''}${Math.round(val).toLocaleString('pl-PL')} PLN`,
      },
    });

    chartRef.current = chart;

    const series = chart.addSeries(HistogramSeries, {
      base: 0,
      priceFormat: {
        type: 'custom',
        formatter: (val: number) => `${val >= 0 ? '+' : ''}${Math.round(val).toLocaleString('pl-PL')} PLN`,
      },
    });
    seriesRef.current = series;

    chart.timeScale().fitContent();

    chart.subscribeCrosshairMove((param) => {
      if (!param.time) {
        setHoveredRecord(null);
        return;
      }
      const timeStr = normalizeDateStr(param.time);
      const match = recordedRecords.find((r: MonthlyReportRecord) => {
        const expected = `${r.year}-${String(r.monthIndex + 1).padStart(2, '0')}-01`;
        return expected === timeStr;
      });
      setHoveredRecord(match || null);
    });

    chart.subscribeClick((param) => {
      if (!param.time || !onSelectMonthRef.current) return;
      const timeStr = normalizeDateStr(param.time);
      const match = recordedRecords.find((r: MonthlyReportRecord) => {
        const expected = `${r.year}-${String(r.monthIndex + 1).padStart(2, '0')}-01`;
        return expected === timeStr;
      });
      if (match) {
        onSelectMonthRef.current(match.monthIndex);
      }
    });

    const handleResize = () => {
      if (containerRef.current && chartRef.current) {
        chartRef.current.applyOptions({
          width: containerRef.current.clientWidth,
        });
      }
    };

    window.addEventListener('resize', handleResize);

    return () => {
      window.removeEventListener('resize', handleResize);
      chart.remove();
      chartRef.current = null;
      seriesRef.current = null;
    };
  }, [recordedRecords]);

  useEffect(() => {
    if (!seriesRef.current || recordedRecords.length === 0) return;
    const chartData = recordedRecords.map((r: MonthlyReportRecord) => {
      const year = r.year;
      const monthStr = String(r.monthIndex + 1).padStart(2, '0');
      const timeStr = `${year}-${monthStr}-01`;
      const isPositive = r.profitNetMom >= 0;
      const isSelected = selectedMonthIndex !== undefined && r.monthIndex === selectedMonthIndex;
      return {
        time: timeStr,
        value: r.profitNetMom,
        color: isSelected
          ? (isPositive ? '#38bdf8' : '#fb7185')
          : (isPositive ? '#10b981' : '#f43f5e'),
      };
    });
    seriesRef.current.setData(chartData as HistogramData[]);
  }, [recordedRecords, selectedMonthIndex]);

  if (recordedRecords.length === 0) return null;

  return (
    <div className="bg-zinc-900/50 border border-zinc-800/80 rounded-xl p-4 sm:p-5">
      <div className="flex flex-col sm:flex-row sm:items-center justify-between gap-2 mb-3">
        <div>
          <span className="text-xs font-semibold text-zinc-400 uppercase tracking-wider">
            Dynamika miesiąc do miesiąca (m/m)
          </span>
          <h3 className="text-sm font-medium text-zinc-200 mt-0.5">
            Zysk netto wygenerowany w poszczególnych miesiącach
          </h3>
        </div>
        <div className="flex items-center gap-4 text-xs font-mono">
          <div className="flex items-center gap-1.5 text-zinc-400">
            <span className="w-2.5 h-2.5 rounded-sm bg-emerald-500 inline-block" />
            <span>Zysk m/m</span>
          </div>
          <div className="flex items-center gap-1.5 text-zinc-400">
            <span className="w-2.5 h-2.5 rounded-sm bg-rose-500 inline-block" />
            <span>Spadek m/m</span>
          </div>
        </div>
      </div>

      <div className="relative">
        <div ref={containerRef} className="w-full relative" />

        {hoveredRecord && (
          <div className="absolute top-2 left-4 pointer-events-none bg-zinc-950/95 border border-zinc-700/80 rounded-lg px-3 py-1.5 shadow-xl z-20 text-xs font-mono backdrop-blur-md">
            <span className="font-semibold text-white mr-2">
              {hoveredRecord.monthName} {hoveredRecord.year}:
            </span>
            <span
              className={`font-bold mr-2 ${
                hoveredRecord.profitNetMom >= 0 ? 'text-emerald-400' : 'text-rose-400'
              }`}
            >
              {formatPLN(hoveredRecord.profitNetMom, true, hoveredRecord.baseCurrency)}
            </span>
            <span className="text-zinc-400">
              ({formatPercent(hoveredRecord.returnNetMomPct, true)})
            </span>
          </div>
        )}
      </div>
    </div>
  );
};
