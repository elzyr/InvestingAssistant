import React, { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { useTranslation } from 'react-i18next';
import {
  HistogramSeries,
  type DeepPartial,
  type ChartOptions,
  type ISeriesApi,
  type Time,
  type HistogramData,
} from 'lightweight-charts';
import type { MonthlyReportRecord } from '../../types/reports.types.ts';
import { formatCurrency, formatPercent, normalizeZero } from '../../utils/reportsFormatters.ts';
import {
  getShortMonthName,
  normalizeChartDateStr,
  parseUtcDate,
} from '../../utils/domain/dates.ts';
import { createDarkChartOptions, useLightweightChart } from '../../hooks/useLightweightChart.ts';

interface MonthlyProfitBarChartProps {
  records: MonthlyReportRecord[];
  baseCurrency?: string;
  onSelectMonth?: (monthIndex: number) => void;
  selectedMonthIndex?: number;
}

export const MonthlyProfitBarChart: React.FC<MonthlyProfitBarChartProps> = ({
  records,
  baseCurrency,
  onSelectMonth,
  selectedMonthIndex,
}) => {
  const { t, i18n } = useTranslation();
  const containerRef = useRef<HTMLDivElement | null>(null);
  const seriesRef = useRef<ISeriesApi<'Histogram'> | null>(null);
  const [hoveredRecord, setHoveredRecord] = useState<MonthlyReportRecord | null>(null);

  const recordedRecords = useMemo(
    () => records.filter((r) => r.status === 'RECORDED'),
    [records]
  );
  const currency = baseCurrency || recordedRecords[0]?.baseCurrency || 'PLN';

  const recordedRecordsRef = useRef(recordedRecords);
  const onSelectMonthRef = useRef(onSelectMonth);
  useEffect(() => {
    recordedRecordsRef.current = recordedRecords;
    onSelectMonthRef.current = onSelectMonth;
  }, [recordedRecords, onSelectMonth]);

  const formatBarProfit = useCallback(
    (val: number) => {
      const rounded = normalizeZero(Math.round(val));
      return `${rounded > 0 ? '+' : ''}${rounded.toLocaleString(i18n.language)} ${currency}`;
    },
    [currency, i18n.language]
  );

  const chartOptions = useMemo<DeepPartial<ChartOptions>>(
    () =>
      createDarkChartOptions({
        height: 180,
        backgroundColor: 'transparent',
        gridColor: '#181b26',
        timeScale: {
          barSpacing: 36,
          minBarSpacing: 16,
          tickMarkFormatter: (time: Time) => {
            const d = parseUtcDate(normalizeChartDateStr(time));
            if (Number.isNaN(d.getTime())) return '';
            return getShortMonthName(d.getUTCMonth(), i18n.language);
          },
        },
        priceFormatter: formatBarProfit,
      }),
    [formatBarProfit, i18n.language]
  );

  const findRecordByTime = (rawTime: Time): MonthlyReportRecord | undefined => {
    const timeStr = normalizeChartDateStr(rawTime);
    return recordedRecordsRef.current.find(
      (r) => `${r.year}-${String(r.monthIndex + 1).padStart(2, '0')}-01` === timeStr
    );
  };

  const handleChartCreated = useCallback(
    (chart: Parameters<NonNullable<Parameters<typeof useLightweightChart>[0]['onChartCreated']>>[0]) => {
      const series = chart.addSeries(HistogramSeries, {
        base: 0,
        priceFormat: {
          type: 'custom',
          formatter: formatBarProfit,
        },
      });
      seriesRef.current = series;

      chart.subscribeCrosshairMove((param) => {
        setHoveredRecord(param.time ? findRecordByTime(param.time) || null : null);
      });

      chart.subscribeClick((param) => {
        if (!param.time || !onSelectMonthRef.current) return;
        const match = findRecordByTime(param.time);
        if (match) {
          onSelectMonthRef.current(match.monthIndex);
        }
      });

      return () => {
        seriesRef.current = null;
      };
    },
    [formatBarProfit]
  );

  const chartRef = useLightweightChart({
    containerRef,
    options: chartOptions,
    enabled: recordedRecords.length > 0,
    onChartCreated: handleChartCreated,
  });

  useEffect(() => {
    const series = seriesRef.current;
    if (!series || recordedRecords.length === 0) return;

    series.applyOptions({
      priceFormat: {
        type: 'custom',
        formatter: formatBarProfit,
      },
    });

    const chartData = recordedRecords.map((r: MonthlyReportRecord) => {
      const monthStr = String(r.monthIndex + 1).padStart(2, '0');
      const timeStr = `${r.year}-${monthStr}-01`;
      const isPositive = r.profitNetMom >= 0;
      const isSelected = selectedMonthIndex !== undefined && r.monthIndex === selectedMonthIndex;
      return {
        time: timeStr,
        value: r.profitNetMom,
        color: isSelected
          ? isPositive
            ? '#38bdf8'
            : '#fb7185'
          : isPositive
          ? '#10b981'
          : '#f43f5e',
      };
    });
    series.setData(chartData as HistogramData[]);
    chartRef.current?.timeScale().fitContent();
  }, [recordedRecords, selectedMonthIndex, formatBarProfit, chartRef]);

  if (recordedRecords.length === 0) return null;

  return (
    <div className="bg-zinc-900/50 border border-zinc-800/80 rounded-xl p-4 sm:p-5">
      <div className="flex flex-col sm:flex-row sm:items-center justify-between gap-2 mb-3">
        <div>
          <span className="text-xs font-semibold text-zinc-400 uppercase tracking-wider">
            {t('reports.momDynamics')}
          </span>
          <h3 className="text-sm font-medium text-zinc-200 mt-0.5">
            {t('reports.momDynamicsSubtitle')}
          </h3>
        </div>
        <div className="flex items-center gap-4 text-xs font-mono">
          <div className="flex items-center gap-1.5 text-zinc-400">
            <span className="w-2.5 h-2.5 rounded-sm bg-emerald-500 inline-block" />
            <span>{t('reports.momGain')}</span>
          </div>
          <div className="flex items-center gap-1.5 text-zinc-400">
            <span className="w-2.5 h-2.5 rounded-sm bg-rose-500 inline-block" />
            <span>{t('reports.momLoss')}</span>
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
              {formatCurrency(hoveredRecord.profitNetMom, true, hoveredRecord.baseCurrency)}
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
