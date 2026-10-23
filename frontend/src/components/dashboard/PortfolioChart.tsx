import React, { useState, useMemo, useRef, useEffect } from 'react';
import { ChevronDown, ChevronUp, LineChart, Sparkles, Check, Info, X, Settings2, Maximize2, Target } from 'lucide-react';
import {
  createChart,
  ColorType,
  CrosshairMode,
  LineStyle,
  AreaSeries,
  LineSeries,
  type IChartApi,
  type ISeriesApi,
  type UTCTimestamp,
  type Time,
  type TickMarkType,
} from 'lightweight-charts';
import type { 
  ChartPoint, 
  TaxMode, 
  TimeframeOption, 
  ChartMetricMode,
  ProjectionHorizonOption,
  InflationAdjustMode,
} from '../../types/dashboard.ts';
import type { PortfolioStatsResponse } from '../../types/analytics.types.ts';

interface PortfolioChartProps {
  data: ChartPoint[];
  baseCurrency: string;
  taxMode: TaxMode;
  timeframe: TimeframeOption;
  onTimeframeChange: (tf: TimeframeOption) => void;
  metricMode: ChartMetricMode;
  onMetricModeChange: (m: ChartMetricMode) => void;
  projectionHorizon: ProjectionHorizonOption;
  onProjectionHorizonChange: (horizon: ProjectionHorizonOption) => void;
  monthlyDca: number;
  onMonthlyDcaChange: (val: number) => void;
  dcaBondShare: number;
  onDcaBondShareChange: (val: number) => void;
  expectedEtfReturn: number;
  onExpectedEtfReturnChange: (val: number) => void;
  bondRollover: boolean;
  onBondRolloverChange: (val: boolean) => void;
  inflationAdjustMode?: InflationAdjustMode;
  onInflationAdjustModeChange?: (mode: InflationAdjustMode) => void;
  targetGoal?: number | null;
  onTargetGoalChange?: (goal: number | null) => void;
  empiricalStats?: PortfolioStatsResponse | null;
}

const TIMEFRAMES: TimeframeOption[] = ['1M', '3M', 'YTD', '1R', 'MAX'];
const PROJECTIONS: ProjectionHorizonOption[] = ['3Y', '5Y', '10Y', '20Y'];

function formatNumberWithSpaces(val: number): string {
  const rounded = Math.round(val);
  return rounded.toString().replace(/\B(?=(\d{3})+(?!\d))/g, ' ');
}

function formatSignedNumberWithSpaces(val: number): string {
  const rounded = Math.round(val);
  const sign = rounded > 0 ? '+' : rounded < 0 ? '-' : '';
  const abs = Math.abs(rounded).toString().replace(/\B(?=(\d{3})+(?!\d))/g, ' ');
  return `${sign}${abs}`;
}

const POLISH_MONTHS = ['sty', 'lut', 'mar', 'kwi', 'maj', 'cze', 'lip', 'sie', 'wrz', 'paź', 'lis', 'gru'];

export const PortfolioChart: React.FC<PortfolioChartProps> = ({
  data,
  baseCurrency,
  taxMode,
  timeframe,
  onTimeframeChange,
  metricMode,
  onMetricModeChange,
  projectionHorizon,
  onProjectionHorizonChange,
  monthlyDca,
  onMonthlyDcaChange,
  dcaBondShare,
  onDcaBondShareChange,
  expectedEtfReturn,
  onExpectedEtfReturnChange,
  bondRollover,
  onBondRolloverChange,
  inflationAdjustMode = 'NOMINAL',
  onInflationAdjustModeChange,
  targetGoal = null,
  onTargetGoalChange,
  empiricalStats,
}) => {
  const chartContainerRef = useRef<HTMLDivElement | null>(null);
  const chartRef = useRef<IChartApi | null>(null);
  const [hoveredPoint, setHoveredPoint] = useState<ChartPoint | null>(null);
  const [todayXCoord, setTodayXCoord] = useState<number | null>(null);
  const [isCollapsed, setIsCollapsed] = useState(false);
  const [showAdvanced, setShowAdvanced] = useState(false);

  const isProjectionActive = projectionHorizon !== 'OFF';

  const processedData = useMemo(() => {
    if (!data || data.length === 0) return [];
    let lastTime = 0;
    return data.map((d) => {
      let t = Math.floor(d.timestamp / 1000);
      if (t <= lastTime) {
        t = lastTime + 86400;
      }
      lastTime = t;
      return {
        ...d,
        utcTime: t as UTCTimestamp,
      };
    });
  }, [data]);

  const latestHistoricalPoint = useMemo(() => {
    if (processedData.length === 0) return null;
    const historyPoints = processedData.filter((d) => !d.isFuture);
    const lastP = historyPoints.length > 0 ? historyPoints[historyPoints.length - 1] : processedData[processedData.length - 1];
    return lastP ? {
      ...lastP,
      returnP90: lastP.returnPct,
      returnP10: lastP.returnPct,
      netP90: lastP.valuation,
      netP10: lastP.valuation,
    } : null;
  }, [processedData]);

  const todayUtcTime = latestHistoricalPoint?.utcTime;

  const finalFuturePoint = useMemo(() => {
    return data.slice().reverse().find((d) => d.isFuture);
  }, [data]);
  const goalProb = finalFuturePoint?.goalProbability;

  const firstFuturePoint = useMemo(() => {
    return data.find((d) => d.isFuture);
  }, [data]);
  const weightedTer = firstFuturePoint?.weightedTer ?? 0.20;
  const weightedWhtDrag = firstFuturePoint?.weightedWhtDrag ?? 0.25;
  const diversifiedVol = firstFuturePoint?.diversifiedVolatility ?? 15.0;

  useEffect(() => {
    if (!chartContainerRef.current || processedData.length === 0 || isCollapsed) return;

    if (chartRef.current) {
      chartRef.current.remove();
      chartRef.current = null;
    }

    const container = chartContainerRef.current;
    const chart = createChart(container, {
      width: container.clientWidth || 800,
      height: 290,
      layout: {
        background: { type: ColorType.Solid, color: '#10121a' },
        textColor: '#71717a',
        fontFamily: 'monospace',
        fontSize: 11,
      },
      grid: {
        vertLines: { color: '#161923' },
        horzLines: { color: '#161923' },
      },
      crosshair: {
        mode: CrosshairMode.Normal,
        vertLine: {
          color: '#38bdf8',
          width: 1,
          style: LineStyle.Dashed,
          labelBackgroundColor: '#0284c7',
        },
        horzLine: {
          color: '#38bdf8',
          width: 1,
          style: LineStyle.Dashed,
          labelBackgroundColor: '#0284c7',
        },
      },
      timeScale: {
        borderColor: '#27272a',
        timeVisible: false,
        secondsVisible: false,
        rightOffset: 20,
        minBarSpacing: 0.02,
        fixLeftEdge: true,
        tickMarkFormatter: (time: Time, tickMarkType: TickMarkType) => {
          const tSec = typeof time === 'number' ? time : Math.floor(new Date(String(time)).getTime() / 1000);
          if (todayUtcTime && Math.abs(tSec - todayUtcTime) < 86400 * 25) {
            return '';
          }
          const d = new Date(tSec * 1000);
          if (tickMarkType === 0) {
            return `${d.getFullYear()}`;
          }
          if (tickMarkType === 1) {
            if (d.getMonth() === 0) {
              return `${d.getFullYear()}`;
            }
            return POLISH_MONTHS[d.getMonth()] || '';
          }
          if (tickMarkType === 2) {
            return '';
          }
          return d.getMonth() === 0 ? `${d.getFullYear()}` : POLISH_MONTHS[d.getMonth()] || null;
        },
      },
      rightPriceScale: {
        borderColor: '#27272a',
        autoScale: true,
        scaleMargins: {
          top: 0.12,
          bottom: 0.12,
        },
      },
      localization: {
        priceFormatter: (val: number) => {
          if (metricMode === 'PLN') {
            return `${formatNumberWithSpaces(val)} ${baseCurrency}`;
          }
          return `${val >= 0 ? '+' : ''}${val.toFixed(2)}%`;
        },
      },
    });

    chartRef.current = chart;

    const pastData = processedData.filter((d) => !d.isFuture);
    const futureData = processedData.filter((d) => d.isFuture);

    if (latestHistoricalPoint && futureData.length > 0) {
      futureData.unshift(latestHistoricalPoint);
    }

    const areaSeries: ISeriesApi<'Area'> = chart.addSeries(AreaSeries, {
      lineColor: '#34d399',
      topColor: 'rgba(52, 211, 153, 0.35)',
      bottomColor: 'rgba(52, 211, 153, 0.0)',
      lineWidth: 2,
      lastValueVisible: false,
      priceLineVisible: false,
      priceFormat: {
        type: 'custom',
        formatter: (val: number) =>
          metricMode === 'PLN'
            ? `${formatNumberWithSpaces(val)} ${baseCurrency}`
            : `${val >= 0 ? '+' : ''}${val.toFixed(2)}%`,
      },
    });

    areaSeries.setData(
      pastData.map((d) => ({
        time: d.utcTime,
        value: metricMode === 'PLN' ? d.valuation : d.returnPct,
      }))
    );

    if (metricMode === 'PLN') {
      const costSeries = chart.addSeries(LineSeries, {
        color: '#71717a',
        lineStyle: LineStyle.Dashed,
        lineWidth: 1,
        lastValueVisible: false,
        priceLineVisible: false,
        title: '',
      });
      costSeries.setData(
        processedData.map((d) => ({
          time: d.utcTime,
          value: d.cost,
        }))
      );
    }

    if (isProjectionActive && futureData.length > 0) {
      const medianSeries = chart.addSeries(LineSeries, {
        color: '#38bdf8',
        lineWidth: 2,
        lastValueVisible: true,
        priceLineVisible: true,
        priceLineColor: '#38bdf8',
        priceLineStyle: LineStyle.Dashed,
        title: 'Mediana P50',
      });
      medianSeries.setData(
        futureData.map((d) => ({
          time: d.utcTime,
          value: metricMode === 'PLN' ? (d.netMedian ?? d.valuation) : d.returnPct,
        }))
      );

      const p90Series = chart.addSeries(LineSeries, {
        color: '#38bdf8',
        lineStyle: LineStyle.Dotted,
        lineWidth: 1,
        lastValueVisible: false,
        priceLineVisible: false,
        title: '',
      });
      p90Series.setData(
        futureData.map((d) => ({
          time: d.utcTime,
          value: metricMode === 'PLN' ? (d.netP90 ?? d.valuation) : (d.returnP90 ?? d.returnPct),
        }))
      );

      const p10Series = chart.addSeries(LineSeries, {
        color: '#38bdf8',
        lineStyle: LineStyle.Dotted,
        lineWidth: 1,
        lastValueVisible: false,
        priceLineVisible: false,
        title: '',
      });
      p10Series.setData(
        futureData.map((d) => ({
          time: d.utcTime,
          value: metricMode === 'PLN' ? (d.netP10 ?? d.valuation) : (d.returnP10 ?? d.returnPct),
        }))
      );

      if (targetGoal && targetGoal > 0 && metricMode === 'PLN') {
        medianSeries.createPriceLine({
          price: targetGoal,
          color: '#c084fc',
          lineWidth: 1,
          lineStyle: LineStyle.Dashed,
          axisLabelVisible: true,
          title: 'Cel',
        });
      }
    } else if (targetGoal && targetGoal > 0 && metricMode === 'PLN') {
      areaSeries.createPriceLine({
        price: targetGoal,
        color: '#c084fc',
        lineWidth: 1,
        lineStyle: LineStyle.Dashed,
        axisLabelVisible: true,
        title: 'Cel',
      });
    }

    chart.timeScale().fitContent();

    const updateTodayCoord = () => {
      if (todayUtcTime && chartRef.current) {
        const coord = chartRef.current.timeScale().timeToCoordinate(todayUtcTime);
        setTodayXCoord(coord != null && coord > 0 ? coord : null);
      }
    };

    updateTodayCoord();
    chart.timeScale().subscribeVisibleTimeRangeChange(updateTodayCoord);
    chart.timeScale().subscribeVisibleLogicalRangeChange(updateTodayCoord);

    const normalizeTime = (time: Time | unknown): number => {
      if (!time) return 0;
      if (typeof time === 'number') return time;
      if (typeof time === 'string') return Math.floor(new Date(time).getTime() / 1000);
      if (typeof time === 'object' && 'year' in (time as Record<string, unknown>) && 'month' in (time as Record<string, unknown>) && 'day' in (time as Record<string, unknown>)) {
        const t = time as { year: number; month: number; day: number };
        return Math.floor(Date.UTC(t.year, t.month - 1, t.day) / 1000);
      }
      return 0;
    };

    const pointByTime = new Map<number, ChartPoint>();
    for (const p of processedData) {
      pointByTime.set(Number(p.utcTime), p);
    }

    chart.subscribeCrosshairMove((param) => {
      if (!param.time) {
        setHoveredPoint(null);
        return;
      }
      const targetT = normalizeTime(param.time);
      let found = pointByTime.get(targetT) ?? pointByTime.get(Number(param.time));
      if (!found && targetT > 0 && processedData.length > 0) {
        let low = 0;
        let high = processedData.length - 1;
        while (low <= high) {
          const mid = (low + high) >> 1;
          const midT = Number(processedData[mid].utcTime);
          if (Math.abs(midT - targetT) <= 86400 * 3) {
            found = processedData[mid];
            break;
          }
          if (midT < targetT) low = mid + 1;
          else high = mid - 1;
        }
      }
      setHoveredPoint(found || null);
    });

    const handleResize = () => {
      if (chartContainerRef.current && chartRef.current) {
        chartRef.current.applyOptions({
          width: chartContainerRef.current.clientWidth,
        });
        updateTodayCoord();
      }
    };

    window.addEventListener('resize', handleResize);

    return () => {
      window.removeEventListener('resize', handleResize);
      chart.remove();
      chartRef.current = null;
    };
  }, [processedData, metricMode, baseCurrency, isProjectionActive, targetGoal, isCollapsed, todayUtcTime, latestHistoricalPoint]);

  if (!data || data.length === 0) return null;

  return (
    <div className="bg-[#141720] border border-zinc-800 rounded-xl overflow-hidden shadow-sm transition-all">
      <div className="px-4 py-3 flex flex-col md:flex-row md:items-center justify-between gap-3 border-b border-zinc-800/80">
        <div className="min-w-0 flex-1">
          <div className="flex flex-wrap items-center gap-2">
            <LineChart className="w-4 h-4 text-emerald-400" />
            <span className="text-xs font-bold text-zinc-200">
              {isProjectionActive ? 'Historia & Prognoza Portfela (Monte Carlo)' : 'Historia wyceny portfela'}
            </span>
            <span className="text-[10px] font-mono text-zinc-400 bg-zinc-800/80 px-1.5 py-0.5 rounded border border-zinc-700/60">
              {taxMode === 'NETTO' ? 'Netto (Belka 19%)' : 'Brutto'}
            </span>
            {inflationAdjustMode === 'REAL' && (
              <span className="text-[10px] font-mono text-amber-300 bg-amber-950/70 px-1.5 py-0.5 rounded border border-amber-800/60">
                Realnie
              </span>
            )}
            {isProjectionActive && (
              <span className="text-[10px] font-mono text-sky-400 bg-sky-950/60 px-1.5 py-0.5 rounded border border-sky-800/60 flex items-center gap-1">
                <Sparkles className="w-3 h-3" />
                +{projectionHorizon.replace('Y', 'L')}
              </span>
            )}
            {targetGoal && isProjectionActive && goalProb !== undefined && (
              <span
                className={`text-[10px] font-mono px-1.5 py-0.5 rounded border flex items-center gap-1 ${
                  goalProb >= 70
                    ? 'bg-emerald-950/80 text-emerald-300 border-emerald-700/60'
                    : goalProb >= 40
                    ? 'bg-amber-950/80 text-amber-300 border-amber-700/60'
                    : 'bg-rose-950/80 text-rose-300 border-rose-700/60'
                }`}
              >
                <Target className="w-3 h-3 text-purple-400" /> Cel {formatNumberWithSpaces(targetGoal / 1000)}k: <span className="font-bold">{goalProb}% szans</span>
              </span>
            )}
            {isProjectionActive && (
              <div className="relative group inline-flex items-center">
                <button
                  type="button"
                  className="text-zinc-500 hover:text-zinc-300 transition cursor-pointer p-0.5"
                  aria-label="Informacje o parametrach ETF"
                >
                  <Info className="w-3.5 h-3.5" />
                </button>
                <div className="absolute left-0 top-full mt-1.5 hidden group-hover:flex flex-col gap-1 z-50 bg-zinc-900 border border-zinc-700/80 rounded-lg p-2.5 shadow-2xl text-[11px] font-mono text-zinc-300 w-64 backdrop-blur-md pointer-events-none">
                  <span className="font-semibold text-white border-b border-zinc-800 pb-1">Parametry części akcyjnej</span>
                  <div className="flex justify-between">
                    <span className="text-zinc-400">Średni ważony TER:</span>
                    <span className="font-semibold text-sky-300">{weightedTer.toFixed(2)}%</span>
                  </div>
                  <div className="flex justify-between">
                    <span className="text-zinc-400">Podatek u źródła (WHT):</span>
                    <span className="font-semibold text-amber-300">{weightedWhtDrag.toFixed(2)}%</span>
                  </div>
                  <div className="flex justify-between">
                    <span className="text-zinc-400">Łączny drag kosztowy:</span>
                    <span className="font-semibold text-rose-300">{(weightedTer + weightedWhtDrag).toFixed(2)}%</span>
                  </div>
                  <div className="flex justify-between pt-1 border-t border-zinc-800">
                    <span className="text-zinc-400">Zmienność (Kowariancja):</span>
                    <span className="font-semibold text-emerald-300">{diversifiedVol.toFixed(1)}%</span>
                  </div>
                  <span className="text-[10px] text-zinc-500 mt-0.5 leading-tight">
                    Wyliczane automatycznie na podstawie posiadanych ETF i macierzy korelacji.
                  </span>
                </div>
              </div>
            )}
          </div>

          <div className="flex flex-wrap items-baseline gap-3 mt-1.5 min-h-[26px]">
            {latestHistoricalPoint && (
              <div className="flex items-baseline gap-1.5 shrink-0">
                {metricMode === 'PERCENT' ? (
                  <>
                    <span className="text-[10px] font-mono uppercase text-zinc-400 font-medium">BIEŻĄCA STOPA ZWROTU:</span>
                    <span className={`text-base font-bold font-mono ${latestHistoricalPoint.returnPct >= 0 ? 'text-emerald-400' : 'text-rose-400'}`}>
                      {latestHistoricalPoint.returnPct >= 0 ? '+' : ''}{latestHistoricalPoint.returnPct.toFixed(2)}%
                    </span>
                    <span className="text-xs font-mono font-medium text-zinc-400">
                      ({formatSignedNumberWithSpaces(latestHistoricalPoint.profit)} {baseCurrency})
                    </span>
                  </>
                ) : (
                  <>
                    <span className="text-[10px] font-mono uppercase text-zinc-400 font-medium">BIEŻĄCA WYCENA:</span>
                    <span className="text-base font-bold font-mono text-white">
                      {formatNumberWithSpaces(latestHistoricalPoint.valuation)} {baseCurrency}
                    </span>
                    <span className={`text-xs font-mono font-semibold ${latestHistoricalPoint.profit >= 0 ? 'text-emerald-400' : 'text-rose-400'}`}>
                      {formatSignedNumberWithSpaces(latestHistoricalPoint.profit)} {baseCurrency} (
                      {latestHistoricalPoint.returnPct >= 0 ? '+' : ''}
                      {latestHistoricalPoint.returnPct.toFixed(2)}%)
                    </span>
                  </>
                )}
              </div>
            )}

            {hoveredPoint && (
              <div className="flex items-baseline gap-2 bg-[#1c2233] px-2 py-0.5 rounded-md border border-sky-800/80 text-xs font-mono shrink-0">
                <span className="text-sky-300 font-semibold text-[11px]">
                  {hoveredPoint.isFuture ? 'Prognoza: ' : ''}{hoveredPoint.dateLabel}
                </span>
                <span className="text-white font-bold">
                  {formatNumberWithSpaces(hoveredPoint.valuation)} {baseCurrency}
                </span>
                <span className="text-zinc-400 text-[11px]">
                  (wkład: {formatNumberWithSpaces(hoveredPoint.cost)})
                </span>
              </div>
            )}
          </div>
        </div>

        <div className="flex flex-wrap items-center gap-2 self-start md:self-auto">
          <div className="flex items-center bg-[#10121a] border border-zinc-800 rounded-lg p-0.5 text-xs font-mono">
            <button
              type="button"
              onClick={() => onMetricModeChange('PLN')}
              className={`px-2 py-0.5 rounded-md text-[11px] font-medium transition cursor-pointer ${
                metricMode === 'PLN'
                  ? 'bg-zinc-800 text-emerald-400 font-bold border border-zinc-700/80 shadow-xs'
                  : 'text-zinc-400 hover:text-zinc-200'
              }`}
            >
              PLN
            </button>
            <button
              type="button"
              onClick={() => onMetricModeChange('PERCENT')}
              className={`px-2 py-0.5 rounded-md text-[11px] font-medium transition cursor-pointer ${
                metricMode === 'PERCENT'
                  ? 'bg-zinc-800 text-emerald-400 font-bold border border-zinc-700/80 shadow-xs'
                  : 'text-zinc-400 hover:text-zinc-200'
              }`}
            >
              % Zwrot
            </button>
          </div>

          {isProjectionActive && (
            <div className="flex items-center bg-[#10121a] border border-zinc-800 rounded-lg p-0.5 text-xs font-mono">
              <button
                type="button"
                onClick={() => onInflationAdjustModeChange?.('NOMINAL')}
                className={`px-2 py-0.5 rounded-md text-[11px] font-medium transition cursor-pointer ${
                  inflationAdjustMode === 'NOMINAL'
                    ? 'bg-zinc-800 text-zinc-200 font-bold border border-zinc-700/80 shadow-xs'
                    : 'text-zinc-400 hover:text-zinc-200'
                }`}
              >
                Nominalnie
              </button>
              <button
                type="button"
                onClick={() => onInflationAdjustModeChange?.('REAL')}
                className={`px-2 py-0.5 rounded-md text-[11px] font-medium transition cursor-pointer ${
                  inflationAdjustMode === 'REAL'
                    ? 'bg-amber-950 text-amber-300 font-bold border border-amber-800/80 shadow-xs'
                    : 'text-zinc-400 hover:text-zinc-200'
                }`}
                title="Wartości przyszłe zdyskontowane o inflację (siła nabywcza)"
              >
                Po inflacji
              </button>
            </div>
          )}

          <div className="flex items-center bg-[#10121a] border border-zinc-800 rounded-lg p-0.5 text-xs font-mono">
            {TIMEFRAMES.map((tf) => {
              const active = timeframe === tf;
              return (
                <button
                  key={tf}
                  type="button"
                  onClick={() => onTimeframeChange(tf)}
                  className={`px-2 py-0.5 rounded-md text-[11px] font-medium transition cursor-pointer ${
                    active
                      ? 'bg-emerald-400 text-zinc-950 font-bold shadow-xs'
                      : 'text-zinc-400 hover:text-zinc-200'
                  }`}
                >
                  {tf}
                </button>
              );
            })}

            <div className="w-[1px] h-3.5 bg-zinc-700/80 mx-1 select-none" />

            {PROJECTIONS.map((h) => {
              const active = projectionHorizon === h;
              const label = `+${h.replace('Y', 'L')}`;
              return (
                <button
                  key={h}
                  type="button"
                  onClick={() => onProjectionHorizonChange(active ? 'OFF' : h)}
                  title={active ? 'Kliknij, aby wyłączyć prognozę' : `Włącz prognozę na ${label}`}
                  className={`px-2 py-0.5 rounded-md text-[11px] font-medium transition cursor-pointer ${
                    active
                      ? 'bg-sky-500 text-zinc-950 font-bold shadow-xs'
                      : 'text-sky-400/80 hover:text-sky-300 hover:bg-sky-950/40'
                  }`}
                >
                  {label}
                </button>
              );
            })}
          </div>

          <button
            type="button"
            onClick={() => chartRef.current?.timeScale().fitContent()}
            title="Dopasuj wykres do ekranu (Reset zoom)"
            className="p-1 rounded-lg bg-zinc-900 border border-zinc-800 text-zinc-400 hover:text-zinc-200 transition cursor-pointer"
          >
            <Maximize2 className="w-3.5 h-3.5" />
          </button>

          <button
            type="button"
            onClick={() => setIsCollapsed(!isCollapsed)}
            title={isCollapsed ? 'Rozwiń wykres' : 'Zwiń wykres'}
            className="p-1 rounded-lg bg-zinc-900 border border-zinc-800 text-zinc-400 hover:text-zinc-200 transition cursor-pointer"
          >
            {isCollapsed ? <ChevronDown className="w-3.5 h-3.5" /> : <ChevronUp className="w-3.5 h-3.5" />}
          </button>
        </div>
      </div>

      {!isCollapsed && isProjectionActive && (
        <div className="px-4 py-2 bg-[#0e1017] border-b border-zinc-800/80 text-xs font-mono flex flex-wrap items-center justify-between gap-3">
          <div className="flex flex-wrap items-center gap-3 sm:gap-4">
            <div className="flex items-center gap-1.5">
              <span className="text-zinc-400 text-[11px] font-medium select-none">Dopłata:</span>
              <div className="flex items-center rounded-md border border-zinc-750 bg-zinc-900/90 focus-within:border-sky-500 focus-within:ring-1 focus-within:ring-sky-500/20 transition">
                <input
                  type="number"
                  min="0"
                  step="100"
                  value={monthlyDca === 0 ? '' : monthlyDca}
                  placeholder="0"
                  onChange={(e) => onMonthlyDcaChange(Math.max(0, Number(e.target.value) || 0))}
                  className="w-20 bg-transparent px-2 py-1 text-xs font-mono font-medium text-white text-right focus:outline-none [appearance:textfield] [&::-webkit-outer-spin-button]:appearance-none [&::-webkit-inner-spin-button]:appearance-none"
                />
                <span className="px-1.5 py-1 text-[10px] text-zinc-400 font-semibold border-l border-zinc-800 select-none">
                  {baseCurrency}/mc
                </span>
              </div>
            </div>

            <div className="flex items-center gap-1.5">
              <span className="text-zinc-400 text-[11px] font-medium select-none">Alokacja:</span>
              <div className="flex items-center gap-2 bg-zinc-900/90 border border-zinc-750 px-2.5 py-1 rounded-md">
                <button
                  type="button"
                  onClick={() => onDcaBondShareChange(1)}
                  title="100% Obligacje"
                  className="text-amber-400 hover:text-amber-300 transition font-semibold text-[10px] min-w-[48px] text-right select-none"
                >
                  Obl. {Math.round(dcaBondShare * 100)}%
                </button>
                <input
                  type="range"
                  min="0"
                  max="100"
                  step="5"
                  value={Math.round((1 - dcaBondShare) * 100)}
                  onChange={(e) => onDcaBondShareChange(Number(((100 - Number(e.target.value)) / 100).toFixed(2)))}
                  style={{
                    background: 'linear-gradient(to right, rgb(245 158 11 / 0.4), rgb(16 185 129 / 0.4))',
                  }}
                  className="w-20 sm:w-24 accent-sky-400 cursor-pointer h-1.5 rounded-lg appearance-none"
                />
                <button
                  type="button"
                  onClick={() => onDcaBondShareChange(0)}
                  title="100% ETF"
                  className="text-emerald-400 hover:text-emerald-300 transition font-semibold text-[10px] min-w-[48px] select-none text-left"
                >
                  ETF {Math.round((1 - dcaBondShare) * 100)}%
                </button>
              </div>
            </div>

            <div className="flex items-center gap-1.5">
              <div className="flex items-center rounded-md border border-purple-900/60 bg-zinc-900/90 focus-within:border-purple-400 transition">
                <span className="pl-2 pr-1 text-[11px] text-purple-300 flex items-center gap-1.5 select-none font-medium">
                  <Target className="w-3.5 h-3.5 text-purple-400" /> Cel:
                </span>
                <input
                  type="text"
                  inputMode="numeric"
                  value={targetGoal != null ? formatNumberWithSpaces(targetGoal) : ''}
                  placeholder="opcjonalny"
                  onChange={(e) => {
                    const digits = e.target.value.replace(/\D/g, '');
                    onTargetGoalChange?.(digits ? Number(digits) : null);
                  }}
                  className="w-24 bg-transparent px-1.5 py-1 text-xs font-mono font-medium text-white text-right focus:outline-none"
                />
                <span className="px-1.5 py-1 text-[10px] text-purple-300 font-semibold border-l border-purple-900/60 select-none">
                  {baseCurrency}
                </span>
                {targetGoal && (
                  <button
                    type="button"
                    onClick={() => onTargetGoalChange?.(null)}
                    title="Usuń cel"
                    className="px-1.5 py-1 text-zinc-400 hover:text-white transition cursor-pointer"
                  >
                    <X className="w-3 h-3" />
                  </button>
                )}
              </div>
            </div>

            <div className="flex items-center gap-1.5">
              <span className="text-zinc-400 text-[11px] font-medium select-none">CAGR ETF:</span>
              <div className="relative">
                <select
                  value={expectedEtfReturn}
                  onChange={(e) => onExpectedEtfReturnChange(Number(e.target.value))}
                  className="bg-zinc-900/90 border border-zinc-750 text-emerald-400 font-bold text-xs rounded-md px-2 py-1 pr-6 focus:outline-none focus:border-emerald-500 cursor-pointer appearance-none"
                >
                  {empiricalStats && (
                    <option
                      value={Math.round(empiricalStats.weightedReturn * 1000) / 10}
                      className="bg-zinc-900 text-zinc-200"
                    >
                      {(empiricalStats.weightedReturn * 100).toFixed(1)}% (Portfel)
                    </option>
                  )}
                  {(!empiricalStats || Math.round(empiricalStats.weightedReturn * 1000) / 10 !== expectedEtfReturn) &&
                    ![6.0, 7.5, 8.5, 10.0, 12.0].includes(expectedEtfReturn) && (
                      <option value={expectedEtfReturn} className="bg-zinc-900 text-zinc-200">
                        {expectedEtfReturn.toFixed(1)}% (Własny)
                      </option>
                    )}
                  <option value={6.0} className="bg-zinc-900 text-zinc-200">6.0% (Konserwatywny)</option>
                  <option value={7.5} className="bg-zinc-900 text-zinc-200">7.5% (Zrównoważony)</option>
                  <option value={8.5} className="bg-zinc-900 text-zinc-200">8.5% (MSCI World)</option>
                  <option value={10.0} className="bg-zinc-900 text-zinc-200">10.0% (S&P 500)</option>
                  <option value={12.0} className="bg-zinc-900 text-zinc-200">12.0% (Dynamiczny)</option>
                </select>
                <ChevronDown className="w-3 h-3 text-zinc-400 absolute right-1.5 top-1/2 -translate-y-1/2 pointer-events-none" />
              </div>
            </div>

            {empiricalStats && (
              <div className="px-2 py-0.5 rounded-md bg-zinc-900 border border-zinc-750 text-[10px] text-zinc-300 font-mono flex items-center gap-1.5 select-none">
                <span>ETF:</span>
                <span className="text-zinc-400">TER {(empiricalStats.weightedTer * 100).toFixed(2)}%</span>
                <span>·</span>
                <span className="text-zinc-400">σ {(empiricalStats.weightedVolatility * 100).toFixed(1)}%</span>
                <span>·</span>
                <span className="text-zinc-400">P/E {empiricalStats.weightedPe.toFixed(1)}</span>
              </div>
            )}
          </div>

          <div className="relative">
            <button
              type="button"
              onClick={() => setShowAdvanced(!showAdvanced)}
              className={`px-2 py-1 rounded-md border text-[11px] flex items-center gap-1.5 transition cursor-pointer ${
                showAdvanced
                  ? 'bg-zinc-800 border-zinc-600 text-white'
                  : 'bg-zinc-900/90 border-zinc-750 text-zinc-400 hover:text-zinc-200'
              }`}
              title="Opcje zaawansowane symulacji"
            >
              <Settings2 className="w-3 h-3" />
              <span className="text-[10px]">Zaawansowane</span>
            </button>

            {showAdvanced && (
              <div className="absolute right-0 top-full mt-1.5 z-40 bg-zinc-900 border border-zinc-750 rounded-lg p-3 shadow-2xl text-xs font-mono min-w-[260px]">
                <span className="text-[10px] text-zinc-400 font-semibold uppercase tracking-wider block mb-2">
                  Opcje zaawansowane
                </span>
                <label className="flex items-center gap-2 cursor-pointer select-none">
                  <input
                    type="checkbox"
                    checked={bondRollover}
                    onChange={(e) => onBondRolloverChange(e.target.checked)}
                    className="sr-only"
                  />
                  <div
                    className={`w-3.5 h-3.5 rounded border flex items-center justify-center transition ${
                      bondRollover
                        ? 'bg-sky-500 border-sky-400 text-zinc-950'
                        : 'bg-zinc-950 border-zinc-700'
                    }`}
                  >
                    {bondRollover && <Check className="w-2.5 h-2.5 stroke-[3]" />}
                  </div>
                  <span className="text-[11px] text-zinc-200">Automatyczny rollover obligacji</span>
                </label>
                <p className="text-[10px] text-zinc-500 mt-1 leading-normal">
                  Reinwestycja kapitału po zapadnięciu (3 lata TOS / 10 lat EDO) w nową serię obligacji.
                </p>
              </div>
            )}
          </div>
        </div>
      )}

      {!isCollapsed && (
        <div className="p-4 pt-2 pb-5 bg-[#10121a] relative">
          {/* Floating On-Chart HUD Tooltip */}
          {hoveredPoint && (
            <div className="absolute top-4 left-4 z-30 pointer-events-none bg-zinc-950/95 border border-zinc-750/90 rounded-lg p-2.5 shadow-2xl text-xs font-mono backdrop-blur-md min-w-[230px]">
              <div className="flex items-center justify-between border-b border-zinc-800 pb-1 mb-1.5 text-zinc-300">
                <span className="font-semibold text-sky-400">
                  {hoveredPoint.isFuture ? 'Prognoza: ' : ''}{hoveredPoint.dateLabel}
                </span>
                {hoveredPoint.isFuture && (
                  <span className="text-[10px] text-zinc-500 uppercase">Monte Carlo</span>
                )}
              </div>

              <div className="space-y-1 text-[11px]">
                <div className="flex justify-between items-center text-white">
                  <span className="text-zinc-400">
                    {hoveredPoint.isFuture ? 'Mediana (P50):' : 'Wycena portfela:'}
                  </span>
                  <span className="font-bold text-sm text-sky-300">
                    {metricMode === 'PLN'
                      ? `${formatNumberWithSpaces(hoveredPoint.valuation)} ${baseCurrency}`
                      : `${hoveredPoint.returnPct >= 0 ? '+' : ''}${hoveredPoint.returnPct.toFixed(2)}%`}
                  </span>
                </div>

                <div className="flex justify-between items-center text-zinc-400">
                  <span>Wkład własny (DCA):</span>
                  <span className="font-mono text-zinc-300">
                    {formatNumberWithSpaces(hoveredPoint.cost)} {baseCurrency}
                  </span>
                </div>

                <div className="flex justify-between items-center pt-1 border-t border-zinc-800/60">
                  <span className="text-zinc-400">Zysk netto:</span>
                  <span
                    className={`font-semibold ${
                      hoveredPoint.profit >= 0 ? 'text-emerald-400' : 'text-rose-400'
                    }`}
                  >
                    {formatSignedNumberWithSpaces(hoveredPoint.profit)} {baseCurrency} (
                    {hoveredPoint.returnPct >= 0 ? '+' : ''}
                    {hoveredPoint.returnPct.toFixed(2)}%)
                  </span>
                </div>

                {hoveredPoint.isFuture && (
                  <div className="pt-1.5 mt-1 border-t border-zinc-800/80 space-y-0.5 text-[10px]">
                    <div className="flex justify-between text-sky-300/80">
                      <span>Hossa (P90):</span>
                      <span className="font-mono font-medium">
                        {metricMode === 'PLN'
                          ? `${formatNumberWithSpaces(hoveredPoint.netP90 ?? 0)} ${baseCurrency}`
                          : `${(hoveredPoint.returnP90 ?? hoveredPoint.returnPct) >= 0 ? '+' : ''}${(hoveredPoint.returnP90 ?? hoveredPoint.returnPct).toFixed(2)}%`}
                      </span>
                    </div>
                    <div className="flex justify-between text-sky-400/70">
                      <span>Bessa (P10):</span>
                      <span className="font-mono font-medium">
                        {metricMode === 'PLN'
                          ? `${formatNumberWithSpaces(hoveredPoint.netP10 ?? 0)} ${baseCurrency}`
                          : `${(hoveredPoint.returnP10 ?? hoveredPoint.returnPct) >= 0 ? '+' : ''}${(hoveredPoint.returnP10 ?? hoveredPoint.returnPct).toFixed(2)}%`}
                      </span>
                    </div>
                  </div>
                )}
              </div>
            </div>
          )}

          <div className="relative w-full">
            <div ref={chartContainerRef} className="w-full" />

            {/* Dziś Vertical Line & Axis Badge Overlay */}
            {isProjectionActive && todayXCoord !== null && (
              <div className="pointer-events-none absolute inset-0">
                <div
                  className="absolute top-0 bottom-[26px] w-[1px] border-l border-sky-400/80 border-dashed"
                  style={{ left: `${todayXCoord}px` }}
                />
                <div
                  className="absolute bottom-1 -translate-x-1/2 z-10"
                  style={{ left: `${todayXCoord}px` }}
                >
                  <span className="text-[9px] font-mono font-bold text-sky-300 bg-[#0c1e30] px-1 py-0.5 rounded border border-sky-500/70 shadow-xs">
                    Dziś
                  </span>
                </div>
              </div>
            )}
          </div>

          <div className="flex flex-wrap items-center justify-between text-[11px] text-zinc-400 mt-4 pt-3 px-1 border-t border-zinc-800/80 font-mono gap-4">
            <div className="flex flex-wrap items-center gap-5">
              <div className="flex items-center gap-1.5">
                <span className="w-2.5 h-0.5 bg-emerald-400 inline-block rounded-full"></span>
                <span>Historia wyceny</span>
              </div>

              {isProjectionActive && (
                <>
                  <div className="flex items-center gap-1.5">
                    <span className="w-2.5 h-0.5 bg-sky-400 inline-block rounded-full"></span>
                    <span>Mediana P50</span>
                  </div>
                  <div className="flex items-center gap-1.5">
                    <span className="w-2.5 h-0.5 border-b border-sky-400 border-dotted inline-block"></span>
                    <span>Wstęga P10-P90</span>
                  </div>
                </>
              )}

              {metricMode === 'PLN' && (
                <div className="flex items-center gap-1.5">
                  <span className="w-2.5 h-0.5 border-b border-zinc-400 border-dashed inline-block"></span>
                  <span>{isProjectionActive ? 'Wkład (+DCA)' : 'Wkład (kapitał)'}</span>
                </div>
              )}

              {targetGoal && metricMode === 'PLN' && (
                <div className="flex items-center gap-1.5">
                  <span className="w-2.5 h-0.5 border-b border-purple-400 border-dashed inline-block"></span>
                  <span className="text-purple-300 font-medium">Cel ({formatNumberWithSpaces(targetGoal / 1000)}k {baseCurrency})</span>
                </div>
              )}
            </div>
          </div>
        </div>
      )}
    </div>
  );
};
