import { useState, useMemo, useRef, useEffect, useCallback } from 'react';
import type { TFunction } from 'i18next';
import {
  LineStyle,
  AreaSeries,
  LineSeries,
  type DeepPartial,
  type ChartOptions,
  type IChartApi,
  type ISeriesApi,
  type IPriceLine,
  type MouseEventParams,
  type UTCTimestamp,
  type Time,
  type TickMarkType,
} from 'lightweight-charts';
import type {
  ChartPoint,
  ChartMetricMode,
  ProjectionHorizonOption,
} from '../../types/dashboard.ts';
import {
  formatNumberWithSpaces,
  formatSignedFixedPercent,
  formatSignedNumberWithSpaces,
  formatSignedPercentPoints,
} from '../../utils/reportsFormatters.ts';
import { getShortMonthName, normalizeChartTime } from '../../utils/domain/dates.ts';
import { createDarkChartOptions, useLightweightChart } from '../../hooks/useLightweightChart.ts';
import { unifyProjectionTimeScale } from '../../utils/projectionSimulator.ts';

export const CONTRIBUTOR_COLORS = ['#f59e0b', '#38bdf8', '#a855f7', '#f43f5e', '#14b8a6', '#94a3b8'];

interface ProcessedChartPoint extends ChartPoint {
  utcTime: UTCTimestamp;
}

interface UsePortfolioChartParams {
  data: ChartPoint[];
  baseCurrency: string;
  metricMode: ChartMetricMode;
  projectionHorizon: ProjectionHorizonOption;
  targetGoal: number | null;
  t: TFunction;
  locale: string;
}

function findNearestChartPoint(
  processedData: ProcessedChartPoint[],
  pointByTime: Map<number, ProcessedChartPoint>,
  rawTime: Time | unknown
): ProcessedChartPoint | null {
  const targetT = normalizeChartTime(rawTime);
  const direct = pointByTime.get(targetT) ?? pointByTime.get(Number(rawTime));
  if (direct) return direct;
  if (targetT <= 0 || processedData.length === 0) return null;

  let low = 0;
  let high = processedData.length - 1;
  while (low <= high) {
    const mid = (low + high) >> 1;
    const midT = Number(processedData[mid].utcTime);
    if (midT === targetT) {
      return processedData[mid];
    }
    if (midT < targetT) low = mid + 1;
    else high = mid - 1;
  }

  let bestPoint: ProcessedChartPoint | null = null;
  let bestDiff = Infinity;
  for (const idx of [high, low]) {
    if (idx >= 0 && idx < processedData.length) {
      const diff = Math.abs(Number(processedData[idx].utcTime) - targetT);
      if (diff <= 86400 * 3 && diff < bestDiff) {
        bestDiff = diff;
        bestPoint = processedData[idx];
      }
    }
  }
  return bestPoint;
}

function formatChartPrice(
  val: number,
  metricMode: ChartMetricMode,
  showAttribution: boolean,
  baseCurrency: string
): string {
  if (metricMode === 'PLN') {
    return showAttribution
      ? `${formatSignedNumberWithSpaces(val)} ${baseCurrency}`
      : `${formatNumberWithSpaces(val)} ${baseCurrency}`;
  }
  return formatSignedFixedPercent(val, 2);
}

function pickProjectionValue(
  point: ProcessedChartPoint,
  netValue: number | undefined,
  returnValue: number | undefined,
  metricMode: ChartMetricMode,
  showAttribution: boolean
): number {
  if (metricMode !== 'PLN') return returnValue ?? point.returnPct;
  const val = netValue ?? point.valuation;
  return showAttribution ? val - point.cost : val;
}

export function usePortfolioChart({
  data,
  baseCurrency,
  metricMode,
  projectionHorizon,
  targetGoal,
  t,
  locale,
}: UsePortfolioChartParams) {
  const [showAttribution, setShowAttribution] = useState(false);

  const chartContainerRef = useRef<HTMLDivElement | null>(null);
  const [hoveredPoint, setHoveredPoint] = useState<ProcessedChartPoint | null>(null);
  const [todayXCoord, setTodayXCoord] = useState<number | null>(null);
  const [isCollapsed, setIsCollapsed] = useState(false);

  const isProjectionActive = projectionHorizon !== 'OFF';

  const processedData = useMemo<ProcessedChartPoint[]>(() => {
    if (!data || data.length === 0) return [];
    const scaledData = isProjectionActive ? unifyProjectionTimeScale(data) : data;
    let lastTime = 0;
    return scaledData.map((d) => {
      let utc = Math.floor(d.timestamp / 1000);
      if (utc <= lastTime) {
        utc = lastTime + 86400;
      }
      lastTime = utc;
      return { ...d, utcTime: utc as UTCTimestamp };
    });
  }, [data, isProjectionActive]);

  const pointByTime = useMemo(() => {
    const map = new Map<number, ProcessedChartPoint>();
    for (const p of processedData) {
      map.set(Number(p.utcTime), p);
    }
    return map;
  }, [processedData]);

  const latestHistoricalPoint = useMemo(() => {
    if (processedData.length === 0) return null;
    const historyPoints = processedData.filter((d) => !d.isFuture);
    const lastP = historyPoints.length > 0 ? historyPoints[historyPoints.length - 1] : null;
    return lastP
      ? {
          ...lastP,
          returnP90: lastP.returnPct,
          returnP10: lastP.returnPct,
          netP90: lastP.valuation,
          netP10: lastP.valuation,
        }
      : null;
  }, [processedData]);

  const todayUtcTime = latestHistoricalPoint?.utcTime;

  const finalFuturePoint = useMemo(
    () => data.slice().reverse().find((d) => d.isFuture),
    [data]
  );
  const firstFuturePoint = useMemo(() => data.find((d) => d.isFuture), [data]);

  const clearTodayRadiusSec = useMemo(() => {
    if (!isProjectionActive || !todayUtcTime || !finalFuturePoint) return 0;
    const futurePointsCount = processedData.reduce(
      (count, point) => (point.isFuture ? count + 1 : count),
      0
    );
    if (futurePointsCount <= 0) return 86400 * 25;
    const finalFutureSec = Math.floor(finalFuturePoint.timestamp / 1000);
    const stepSec = Math.max(86400, (finalFutureSec - todayUtcTime) / futurePointsCount);
    return Math.max(86400 * 25, stepSec * Math.max(2.2, processedData.length * 0.042));
  }, [isProjectionActive, todayUtcTime, finalFuturePoint, processedData]);

  const goalProb = finalFuturePoint?.goalProbability;
  const weightedTer = firstFuturePoint?.weightedTer;
  const diversifiedVol = firstFuturePoint?.diversifiedVolatility;

  const processedDataRef = useRef(processedData);
  const pointByTimeRef = useRef(pointByTime);
  const todayUtcTimeRef = useRef(todayUtcTime);

  const areaSeriesRef = useRef<ISeriesApi<'Area'> | null>(null);
  const costSeriesRef = useRef<ISeriesApi<'Line'> | null>(null);
  const medianSeriesRef = useRef<ISeriesApi<'Line'> | null>(null);
  const p90SeriesRef = useRef<ISeriesApi<'Line'> | null>(null);
  const p10SeriesRef = useRef<ISeriesApi<'Line'> | null>(null);
  const contributorSeriesRef = useRef<ISeriesApi<'Line'>[]>([]);
  const targetPriceLineRef = useRef<{
    series: ISeriesApi<'Area' | 'Line'>;
    line: IPriceLine;
  } | null>(null);
  const lastFittedChartRef = useRef<IChartApi | null>(null);
  const lastFittedDataRef = useRef<ProcessedChartPoint[] | null>(null);

  useEffect(() => {
    processedDataRef.current = processedData;
    pointByTimeRef.current = pointByTime;
    todayUtcTimeRef.current = todayUtcTime;
  }, [processedData, pointByTime, todayUtcTime]);

  const chartOptions = useMemo<DeepPartial<ChartOptions>>(
    () =>
      createDarkChartOptions({
        height: 290,
        backgroundColor: '#10121a',
        crosshair: {
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
          rightOffset: 20,
          minBarSpacing: 0.02,
          fixLeftEdge: true,
          tickMarkFormatter: (time: Time, tickMarkType: TickMarkType) => {
            const tSec = normalizeChartTime(time);
            if (clearTodayRadiusSec > 0 && todayUtcTime && Math.abs(tSec - todayUtcTime) < clearTodayRadiusSec) {
              return '';
            }
            const d = new Date(tSec * 1000);
            if (tickMarkType === 0) return `${d.getUTCFullYear()}`;
            if (tickMarkType === 2) return '';
            return d.getUTCMonth() === 0
              ? `${d.getUTCFullYear()}`
              : getShortMonthName(d.getUTCMonth(), locale);
          },
        },
        rightPriceScale: {
          scaleMargins: { top: 0.12, bottom: 0.12 },
        },
        priceFormatter: (val: number) =>
          formatChartPrice(val, metricMode, showAttribution, baseCurrency),
      }),
    [clearTodayRadiusSec, todayUtcTime, locale, metricMode, showAttribution, baseCurrency]
  );

  const syncTodayCoord = useCallback((chart: IChartApi | null) => {
    const tSec = todayUtcTimeRef.current;
    if (!tSec || !chart) {
      setTodayXCoord(null);
      return;
    }
    const coord = chart.timeScale().timeToCoordinate(tSec);
    setTodayXCoord(coord != null && coord > 0 ? coord : null);
  }, []);

  const onChartCreated = useCallback(
    (chart: IChartApi) => {
      areaSeriesRef.current = chart.addSeries(AreaSeries, {
        lineWidth: 2,
        lastValueVisible: false,
        priceLineVisible: false,
      });
      costSeriesRef.current = chart.addSeries(LineSeries, {
        color: '#71717a',
        lineStyle: LineStyle.Dashed,
        lineWidth: 1,
        lastValueVisible: false,
        priceLineVisible: false,
        title: '',
      });
      medianSeriesRef.current = chart.addSeries(LineSeries, {
        color: '#38bdf8',
        lineWidth: 2,
        priceLineColor: '#38bdf8',
        priceLineStyle: LineStyle.Dashed,
      });
      p90SeriesRef.current = chart.addSeries(LineSeries, {
        color: '#38bdf8',
        lineStyle: LineStyle.Dotted,
        lineWidth: 1,
        lastValueVisible: false,
        priceLineVisible: false,
        title: '',
      });
      p10SeriesRef.current = chart.addSeries(LineSeries, {
        color: '#38bdf8',
        lineStyle: LineStyle.Dotted,
        lineWidth: 1,
        lastValueVisible: false,
        priceLineVisible: false,
        title: '',
      });
      contributorSeriesRef.current = [];
      targetPriceLineRef.current = null;
      lastFittedChartRef.current = null;

      const handleTimeScaleChange = () => syncTodayCoord(chart);
      const handleCrosshairMove = (param: MouseEventParams<Time>) => {
        if (!param.time) {
          setHoveredPoint(null);
          return;
        }
        setHoveredPoint(
          findNearestChartPoint(processedDataRef.current, pointByTimeRef.current, param.time)
        );
      };

      chart.timeScale().subscribeVisibleTimeRangeChange(handleTimeScaleChange);
      chart.timeScale().subscribeVisibleLogicalRangeChange(handleTimeScaleChange);
      chart.subscribeCrosshairMove(handleCrosshairMove);

      return () => {
        chart.timeScale().unsubscribeVisibleTimeRangeChange(handleTimeScaleChange);
        chart.timeScale().unsubscribeVisibleLogicalRangeChange(handleTimeScaleChange);
        chart.unsubscribeCrosshairMove(handleCrosshairMove);
        areaSeriesRef.current = null;
        costSeriesRef.current = null;
        medianSeriesRef.current = null;
        p90SeriesRef.current = null;
        p10SeriesRef.current = null;
        contributorSeriesRef.current = [];
        targetPriceLineRef.current = null;
        lastFittedChartRef.current = null;
        lastFittedDataRef.current = null;
      };
    },
    [syncTodayCoord]
  );

  const chartRef = useLightweightChart({
    containerRef: chartContainerRef,
    options: chartOptions,
    enabled: processedData.length > 0 && !isCollapsed,
    onChartCreated,
    onResize: syncTodayCoord,
  });

  useEffect(() => {
    const chart = chartRef.current;
    const areaSeries = areaSeriesRef.current;
    const costSeries = costSeriesRef.current;
    const medianSeries = medianSeriesRef.current;
    const p90Series = p90SeriesRef.current;
    const p10Series = p10SeriesRef.current;
    if (
      !chart ||
      !areaSeries ||
      !costSeries ||
      !medianSeries ||
      !p90Series ||
      !p10Series ||
      processedData.length === 0 ||
      isCollapsed
    ) {
      return;
    }

    const pastData = processedData.filter((d) => !d.isFuture);
    const futureData = processedData.filter((d) => d.isFuture);
    if (latestHistoricalPoint && futureData.length > 0) {
      futureData.unshift(latestHistoricalPoint);
    }

    areaSeries.applyOptions({
      lineColor: showAttribution ? '#10b981' : '#34d399',
      topColor: showAttribution ? 'rgba(16, 185, 129, 0.25)' : 'rgba(52, 211, 153, 0.35)',
      bottomColor: 'rgba(52, 211, 153, 0.0)',
      title: showAttribution ? t('chart.totalProfitSeries') : '',
      priceFormat: {
        type: 'custom',
        formatter: (val: number) =>
          formatChartPrice(val, metricMode, showAttribution, baseCurrency),
      },
    });
    areaSeries.setData(
      pastData.map((d) => ({
        time: d.utcTime,
        value: showAttribution
          ? metricMode === 'PLN'
            ? d.profit
            : d.returnPct
          : metricMode === 'PLN'
            ? d.valuation
            : d.returnPct,
      }))
    );

    const showCost = metricMode === 'PLN' && !showAttribution;
    costSeries.applyOptions({ visible: showCost });
    costSeries.setData(
      showCost ? processedData.map((d) => ({ time: d.utcTime, value: d.cost })) : []
    );

    for (const s of contributorSeriesRef.current) {
      chart.removeSeries(s);
    }
    contributorSeriesRef.current = [];

    if (showAttribution && latestHistoricalPoint?.contributors) {
      latestHistoricalPoint.contributors.forEach((c, idx) => {
        const color = CONTRIBUTOR_COLORS[idx % CONTRIBUTOR_COLORS.length];
        const cSeries = chart.addSeries(LineSeries, {
          color,
          lineWidth: 2,
          lastValueVisible: false,
          priceLineVisible: false,
          title: c.label,
          priceFormat: {
            type: 'custom',
            formatter: (val: number) =>
              metricMode === 'PLN'
                ? `${formatSignedNumberWithSpaces(val)} ${baseCurrency}`
                : formatSignedPercentPoints(val, 2),
          },
        });
        cSeries.setData(
          pastData.map((d) => {
            const match = d.contributors?.find((item) => item.key === c.key);
            return {
              time: d.utcTime,
              value: match
                ? metricMode === 'PLN'
                  ? match.profit
                  : match.returnContributionPct
                : 0,
            };
          })
        );
        contributorSeriesRef.current.push(cSeries);
      });
    }

    const showProjectionSeries = isProjectionActive && futureData.length > 0;
    medianSeries.applyOptions({
      visible: showProjectionSeries,
      lastValueVisible: showProjectionSeries,
      priceLineVisible: showProjectionSeries,
      title: showProjectionSeries ? t('chart.medianP50') : '',
    });
    p90Series.applyOptions({ visible: showProjectionSeries });
    p10Series.applyOptions({ visible: showProjectionSeries });

    if (showProjectionSeries) {
      medianSeries.setData(
        futureData.map((d) => ({
          time: d.utcTime,
          value: pickProjectionValue(
            d,
            d.valuation,
            d.returnPct,
            metricMode,
            showAttribution
          ),
        }))
      );
      p90Series.setData(
        futureData.map((d) => ({
          time: d.utcTime,
          value: pickProjectionValue(
            d,
            d.netP90,
            d.returnP90,
            metricMode,
            showAttribution
          ),
        }))
      );
      p10Series.setData(
        futureData.map((d) => ({
          time: d.utcTime,
          value: pickProjectionValue(
            d,
            d.netP10,
            d.returnP10,
            metricMode,
            showAttribution
          ),
        }))
      );
    } else {
      medianSeries.setData([]);
      p90Series.setData([]);
      p10Series.setData([]);
    }

    if (targetPriceLineRef.current) {
      targetPriceLineRef.current.series.removePriceLine(targetPriceLineRef.current.line);
      targetPriceLineRef.current = null;
    }

    if (targetGoal && targetGoal > 0 && metricMode === 'PLN' && !showAttribution) {
      const hostSeries: ISeriesApi<'Area' | 'Line'> = showProjectionSeries
        ? medianSeries
        : areaSeries;
      const line = hostSeries.createPriceLine({
        price: targetGoal,
        color: '#c084fc',
        lineWidth: 1,
        lineStyle: LineStyle.Dashed,
        axisLabelVisible: true,
        title: t('chart.goal'),
      });
      targetPriceLineRef.current = { series: hostSeries, line };
    }

    if (lastFittedChartRef.current !== chart || lastFittedDataRef.current !== processedData) {
      chart.timeScale().fitContent();
      lastFittedChartRef.current = chart;
      lastFittedDataRef.current = processedData;
    }
    syncTodayCoord(chart);
  }, [
    chartRef,
    processedData,
    metricMode,
    baseCurrency,
    isProjectionActive,
    targetGoal,
    isCollapsed,
    latestHistoricalPoint,
    showAttribution,
    t,
    syncTodayCoord,
  ]);

  const activeHoveredPoint =
    hoveredPoint && processedData.includes(hoveredPoint) ? hoveredPoint : null;
  const activeTodayXCoord =
    todayUtcTime && !isCollapsed && processedData.length > 0 ? todayXCoord : null;
  const activeAttributionPoint =
    activeHoveredPoint && !activeHoveredPoint.isFuture
      ? activeHoveredPoint
      : latestHistoricalPoint;

  const fitContent = () => {
    chartRef.current?.timeScale().fitContent();
  };

  return {
    showAttribution,
    setShowAttribution,
    chartContainerRef,
    fitContent,
    hoveredPoint: activeHoveredPoint,
    todayXCoord: activeTodayXCoord,
    isCollapsed,
    setIsCollapsed,
    isProjectionActive,
    latestHistoricalPoint,
    goalProb,
    weightedTer,
    diversifiedVol,
    activeAttributionPoint,
  };
}
