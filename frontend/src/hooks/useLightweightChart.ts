import { useEffect, useRef, type RefObject } from 'react';
import {
  ColorType,
  CrosshairMode,
  createChart,
  type DeepPartial,
  type ChartOptions,
  type IChartApi,
} from 'lightweight-charts';

interface DarkChartOptionsConfig {
  height: number;
  backgroundColor?: string;
  gridColor?: string;
  crosshair?: DeepPartial<ChartOptions['crosshair']>;
  timeScale?: DeepPartial<ChartOptions['timeScale']>;
  rightPriceScale?: DeepPartial<ChartOptions['rightPriceScale']>;
  priceFormatter?: (val: number) => string;
}

export function createDarkChartOptions({
  height,
  backgroundColor = '#090a0f',
  gridColor = '#161923',
  crosshair,
  timeScale,
  rightPriceScale,
  priceFormatter,
}: DarkChartOptionsConfig): DeepPartial<ChartOptions> {
  return {
    height,
    layout: {
      background: { type: ColorType.Solid, color: backgroundColor },
      textColor: '#71717a',
      fontFamily: 'monospace',
      fontSize: 11,
    },
    grid: {
      vertLines: { color: gridColor },
      horzLines: { color: gridColor },
    },
    crosshair: {
      mode: CrosshairMode.Normal,
      ...crosshair,
    },
    timeScale: {
      borderColor: '#27272a',
      timeVisible: false,
      secondsVisible: false,
      ...timeScale,
    },
    rightPriceScale: {
      borderColor: '#27272a',
      autoScale: true,
      ...rightPriceScale,
    },
    ...(priceFormatter ? { localization: { priceFormatter } } : {}),
  };
}

interface UseLightweightChartOptions {
  containerRef: RefObject<HTMLDivElement | null>;
  options: DeepPartial<ChartOptions>;
  enabled?: boolean;
  onChartCreated?: (chart: IChartApi) => (() => void) | void;
  onResize?: (chart: IChartApi, width: number) => void;
}

export function useLightweightChart({
  containerRef,
  options,
  enabled = true,
  onChartCreated,
  onResize,
}: UseLightweightChartOptions): RefObject<IChartApi | null> {
  const chartRef = useRef<IChartApi | null>(null);
  const optionsRef = useRef(options);
  const appliedOptionsRef = useRef<DeepPartial<ChartOptions> | null>(null);
  const onCreatedRef = useRef(onChartCreated);
  const onResizeRef = useRef(onResize);

  useEffect(() => {
    optionsRef.current = options;
    onCreatedRef.current = onChartCreated;
    onResizeRef.current = onResize;
  });

  useEffect(() => {
    const container = containerRef.current;
    if (!enabled || !container) return;

    const initialOptions = optionsRef.current;
    const chart = createChart(container, {
      ...initialOptions,
      ...(container.clientWidth > 0 ? { width: container.clientWidth } : {}),
    });
    chartRef.current = chart;
    appliedOptionsRef.current = initialOptions;

    const extraCleanup = onCreatedRef.current?.(chart);

    const handleResize = () => {
      if (!containerRef.current || !chartRef.current) return;
      const width = containerRef.current.clientWidth;
      if (width > 0) {
        chartRef.current.applyOptions({ width });
        onResizeRef.current?.(chartRef.current, width);
      }
    };

    const observer =
      typeof ResizeObserver !== 'undefined' ? new ResizeObserver(handleResize) : null;
    observer?.observe(container);
    window.addEventListener('resize', handleResize);

    return () => {
      window.removeEventListener('resize', handleResize);
      observer?.disconnect();
      if (typeof extraCleanup === 'function') {
        extraCleanup();
      }
      chart.remove();
      chartRef.current = null;
      appliedOptionsRef.current = null;
    };
  }, [containerRef, enabled]);

  useEffect(() => {
    if (!chartRef.current || appliedOptionsRef.current === options) return;
    appliedOptionsRef.current = options;
    chartRef.current.applyOptions(options);
  }, [options]);

  return chartRef;
}
