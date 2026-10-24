import { useState, useMemo, useEffect, useRef } from 'react';
import { AlertTriangle, ShieldCheck, Flame, Zap, ArrowDownRight, CheckCircle2 } from 'lucide-react';
import {
  createChart,
  ColorType,
  CrosshairMode,
  LineSeries,
  LineStyle,
  type IChartApi,
  type Time,
} from 'lightweight-charts';

interface TimelinePoint {
  m: number;
  userVal: number;
  allStockVal: number;
  allBondVal: number;
  userCost: number;
  cpi: number;
  drawdownPct: number;
}

interface StressTestingViewProps {
  currentBondVal: number;
  currentEquityVal: number;
  currentCostVal: number;
  baseCurrency: string;
  isIkeAccount: boolean;
  weightedBondRate: number;
  defaultTosDca?: number;
  defaultEdoDca?: number;
  defaultEtfDca?: number;
}

interface CrisisScenario {
  id: string;
  title: string;
  shortTitle: string;
  period: string;
  durationMonths: number;
  description: string;
  icon: typeof Flame;
  color: string;
  getMarketMultiplier: (m: number) => number;
  getInflationRate: (m: number) => number;
}

const normalizeDateStr = (time: Time | unknown): string => {
  if (!time) return '';
  if (typeof time === 'string') return time;
  if (typeof time === 'object' && time !== null && 'year' in time && 'month' in time) {
    const t = time as { year: number; month: number; day?: number };
    return `${t.year}-${String(t.month).padStart(2, '0')}-01`;
  }
  if (typeof time === 'number') {
    const d = new Date(time * 1000);
    return `${d.getUTCFullYear()}-${String(d.getUTCMonth() + 1).padStart(2, '0')}-01`;
  }
  return '';
};

const formatMonths = (m: number): string => {
  if (m === 1) return '1 miesiąc';
  const rem10 = m % 10;
  const rem100 = m % 100;
  if (rem10 >= 2 && rem10 <= 4 && (rem100 < 10 || rem100 >= 20)) {
    return `${m} miesiące`;
  }
  return `${m} miesięcy`;
};

export const StressTestingView = ({
  currentBondVal,
  currentEquityVal,
  currentCostVal,
  baseCurrency,
  isIkeAccount,
  weightedBondRate,
  defaultTosDca = 0,
  defaultEdoDca = 0,
  defaultEtfDca = 0,
}: StressTestingViewProps) => {
  const [selectedScenarioId, setSelectedScenarioId] = useState<string>('covid-2020');
  const [prevDefaults, setPrevDefaults] = useState({ defaultTosDca, defaultEdoDca, defaultEtfDca });
  const [tosDca, setTosDca] = useState<number>(defaultTosDca);
  const [edoDca, setEdoDca] = useState<number>(defaultEdoDca);
  const [etfDca, setEtfDca] = useState<number>(defaultEtfDca);
  const [hoveredPt, setHoveredPt] = useState<TimelinePoint | null>(null);

  if (
    prevDefaults.defaultTosDca !== defaultTosDca ||
    prevDefaults.defaultEdoDca !== defaultEdoDca ||
    prevDefaults.defaultEtfDca !== defaultEtfDca
  ) {
    setPrevDefaults({ defaultTosDca, defaultEdoDca, defaultEtfDca });
    setTosDca(defaultTosDca);
    setEdoDca(defaultEdoDca);
    setEtfDca(defaultEtfDca);
  }

  const chartContainerRef = useRef<HTMLDivElement | null>(null);
  const chartRef = useRef<IChartApi | null>(null);

  const scenarios: CrisisScenario[] = [
    {
      id: 'covid-2020',
      title: 'Flash-Crash COVID-19 (Marzec 2020)',
      shortTitle: 'COVID-19 (2020)',
      period: '24 miesiące (2 lata)',
      durationMonths: 24,
      description:
        'Błyskawiczna panika pandemiczna: rynki akcji zanurkowały o -34% w 33 dni, po czym nastąpiło spektakularne odbicie w kształcie litery V.',
      icon: Zap,
      color: '#38bdf8',
      getMarketMultiplier: (m: number) => {
        if (m === 0) return 1.0;
        if (m === 1) return 0.78;
        if (m === 2) return 0.66;
        if (m <= 8) {
          const rec = (m - 2) / 6;
          return 0.66 + (1.02 - 0.66) * rec;
        }
        const bull = (m - 8) / (24 - 8);
        return 1.02 + 0.28 * bull;
      },
      getInflationRate: () => 3.2,
    },
    {
      id: 'gfc-2008',
      title: 'Globalny Kryzys Finansowy (2007–2009)',
      shortTitle: 'GFC (2008)',
      period: '48 miesięcy (4 lata)',
      durationMonths: 48,
      description:
        'Najgłębsza bessa nowoczesnej giełdy. Globalne akcje tąpnęły o -55% w ciągu 17 miesięcy, a powrót do szczytów zajął 4 lata. Obligacje skarbowe stabilizowały portfel.',
      icon: Flame,
      color: '#fb7185',
      getMarketMultiplier: (m: number) => {
        if (m === 0) return 1.0;
        if (m <= 17) {
          return 1.0 - 0.555 * Math.sin((m / 17) * (Math.PI / 2));
        }
        const recoveryProgress = (m - 17) / (48 - 17);
        return 0.445 + (1.15 - 0.445) * Math.pow(recoveryProgress, 0.85);
      },
      getInflationRate: (m: number) => {
        if (m <= 12) return 4.0;
        if (m <= 24) return 0.5;
        return 2.5;
      },
    },
    {
      id: 'stagflation-2022',
      title: 'Szok Stagflacyjny & Wojna (2021–2023)',
      shortTitle: 'Stagflacja (2022)',
      period: '36 miesięcy (3 lata)',
      durationMonths: 36,
      description:
        'Gwałtowny skok inflacji w Polsce do 18.4% połączony z bessą technologiczną i spadkiem akcji o -28%. EDO indeksowane inflacją osiągały rekordowe stopy zwrotu.',
      icon: AlertTriangle,
      color: '#fbbf24',
      getMarketMultiplier: (m: number) => {
        if (m === 0) return 1.0;
        if (m <= 12) {
          return 1.0 - 0.28 * Math.sin((m / 12) * (Math.PI / 2));
        }
        const rec = (m - 12) / (36 - 12);
        return 0.72 + (1.12 - 0.72) * rec;
      },
      getInflationRate: (m: number) => {
        if (m <= 6) return 5.0 + (m / 6) * 5.0;
        if (m <= 15) return 10.0 + ((m - 6) / 9) * 8.4;
        if (m <= 27) return 18.4 - ((m - 15) / 12) * 8.0;
        return 10.4 - ((m - 27) / 9) * 4.0;
      },
    },
  ];

  const currentScenario = scenarios.find((s) => s.id === selectedScenarioId) || scenarios[0];

  const simulation = useMemo(() => {
    const totalMonths = currentScenario.durationMonths;
    const totalDca = tosDca + edoDca + etfDca;
    const dt = 1 / 12;

    const initialTotal = currentBondVal + currentEquityVal;
    const baseTotal = initialTotal;
    const bondBase = currentBondVal;
    const equityBase = currentEquityVal;
    const costBase = currentCostVal > 0 ? currentCostVal : baseTotal;

    let minMktMult = 1.0;
    let mktTroughMonth = 0;
    for (let m = 0; m <= totalMonths; m++) {
      const mult = currentScenario.getMarketMultiplier(m);
      if (mult < minMktMult) {
        minMktMult = mult;
        mktTroughMonth = m;
      }
    }
    const troughMonth = mktTroughMonth > 0 ? mktTroughMonth : 1;
    const maxStockDrawdownPct = Math.max(0, (1 - minMktMult) * 100);

    const initialBondRate = Math.pow(1 + (weightedBondRate || 6.5) / 100, dt) - 1;
    const tosRate = Math.pow(1 + 0.065, dt) - 1;

    const bondAtTrough = bondBase * Math.pow(1 + initialBondRate, troughMonth);
    const stockAtTrough = equityBase * minMktMult;
    const troughVal = bondAtTrough + stockAtTrough;
    const maxDrawdownPct = Math.max(0, ((baseTotal - troughVal) / baseTotal) * 100);
    const maxDrawdownPln = Math.max(0, baseTotal - troughVal);

    let userCost = costBase;
    let dcaTos = 0;
    let dcaEdo = 0;
    let dcaEtfUnits = 0;
    let allStockDcaUnits = 0;
    let allBondDca = 0;
    let recoveryMonth: number | null = null;

    const timeline: Array<{
      m: number;
      label: string;
      userVal: number;
      userCost: number;
      allStockVal: number;
      allBondVal: number;
      drawdownPct: number;
      cpi: number;
    }> = [];

    for (let m = 0; m <= totalMonths; m++) {
      const cpiAtM = currentScenario.getInflationRate(m);
      const edoRate = Math.pow(1 + (Math.max(0, cpiAtM) + 2.0) / 100, dt) - 1;
      const mkt = currentScenario.getMarketMultiplier(m);
      const bondCompounded = Math.pow(1 + initialBondRate, m);

      if (m > troughMonth) {
        userCost += totalDca;
        dcaTos = (dcaTos + tosDca) * (1 + tosRate);
        dcaEdo = (dcaEdo + edoDca) * (1 + edoRate);
        dcaEtfUnits += etfDca / mkt;
        allStockDcaUnits += totalDca / mkt;
        allBondDca = (allBondDca + totalDca) * (1 + tosRate * 0.6 + edoRate * 0.4);
      }

      const userBonds = bondBase * bondCompounded + dcaTos + dcaEdo;
      const userStock = equityBase * mkt + dcaEtfUnits * mkt;
      const userGross = userBonds + userStock;
      const userProfit = userGross - userCost;
      const userBelka = isIkeAccount ? 0 : Math.max(0, userProfit * 0.19);
      const userNet = userGross - userBelka;

      const allStockGross = baseTotal * mkt + allStockDcaUnits * mkt;
      const allStockProfit = allStockGross - userCost;
      const allStockBelka = isIkeAccount ? 0 : Math.max(0, allStockProfit * 0.19);
      const allStockNet = allStockGross - allStockBelka;

      const allBondGross = baseTotal * bondCompounded + allBondDca;
      const allBondProfit = allBondGross - userCost;
      const allBondBelka = isIkeAccount ? 0 : Math.max(0, allBondProfit * 0.19);
      const allBondNet = allBondGross - allBondBelka;

      const dd = m <= troughMonth ? Math.max(0, ((baseTotal - userNet) / baseTotal) * 100) : 0;

      if (m > troughMonth && recoveryMonth === null) {
        if (mkt >= 1.0 || (mkt >= 0.96 && userNet >= userCost)) {
          recoveryMonth = m;
        }
      }

      const yr = Math.floor(m / 12);
      const remM = m % 12;
      const label = yr === 0 ? (m === 0 ? 'Start' : `${m}m`) : remM === 0 ? `${yr}L` : `${yr}L ${remM}m`;

      timeline.push({
        m,
        label,
        userVal: userNet,
        userCost,
        allStockVal: allStockNet,
        allBondVal: allBondNet,
        drawdownPct: dd,
        cpi: cpiAtM,
      });
    }

    if (recoveryMonth === null) {
      recoveryMonth = Math.min(totalMonths, troughMonth + 7);
    }

    const finalPt = timeline[timeline.length - 1];

    return {
      timeline,
      maxDrawdownPct,
      maxDrawdownPln,
      maxStockDrawdownPct,
      troughMonth,
      troughVal,
      recoveryMonth,
      finalPt,
    };
  }, [
    currentScenario,
    tosDca,
    edoDca,
    etfDca,
    currentBondVal,
    currentEquityVal,
    currentCostVal,
    weightedBondRate,
    isIkeAccount,
  ]);

  const getMonthDateStr = (m: number) => {
    const yr = 2026 + Math.floor(m / 12);
    const mo = String((m % 12) + 1).padStart(2, '0');
    return `${yr}-${mo}-01`;
  };

  useEffect(() => {
    if (!chartContainerRef.current || simulation.timeline.length === 0) return;

    if (chartRef.current) {
      chartRef.current.remove();
      chartRef.current = null;
    }

    const container = chartContainerRef.current;
    const chart = createChart(container, {
      width: container.clientWidth || 800,
      height: 290,
      layout: {
        background: { type: ColorType.Solid, color: '#090a0f' },
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
      },
      timeScale: {
        borderColor: '#27272a',
        timeVisible: false,
        secondsVisible: false,
        barSpacing: 26,
        minBarSpacing: 8,
        tickMarkFormatter: (time: Time) => {
          const d = new Date(normalizeDateStr(time));
          const m = (d.getFullYear() - 2026) * 12 + d.getMonth();
          if (m === 0) return 'Start';
          if (m % 12 === 0) return `${m / 12}L`;
          return `${m}m`;
        },
      },
      rightPriceScale: {
        borderColor: '#27272a',
        autoScale: true,
      },
      localization: {
        priceFormatter: (val: number) =>
          `${Math.round(val).toLocaleString('pl-PL')} ${baseCurrency}`,
      },
    });

    chartRef.current = chart;

    const costSeries = chart.addSeries(LineSeries, {
      color: '#71717a',
      lineWidth: 1,
      lineStyle: LineStyle.Dashed,
      lastValueVisible: false,
      priceLineVisible: false,
      title: 'Wkład własny',
    });
    costSeries.setData(
      simulation.timeline.map((pt) => ({
        time: getMonthDateStr(pt.m),
        value: Math.round(pt.userCost),
      }))
    );

    const stockSeries = chart.addSeries(LineSeries, {
      color: '#fb7185',
      lineWidth: 2,
      lastValueVisible: true,
      priceLineVisible: false,
      title: '100% Akcje',
    });
    stockSeries.setData(
      simulation.timeline.map((pt) => ({
        time: getMonthDateStr(pt.m),
        value: Math.round(pt.allStockVal),
      }))
    );

    const bondSeries = chart.addSeries(LineSeries, {
      color: '#fbbf24',
      lineWidth: 2,
      lastValueVisible: true,
      priceLineVisible: false,
      title: '100% Obligacje',
    });
    bondSeries.setData(
      simulation.timeline.map((pt) => ({
        time: getMonthDateStr(pt.m),
        value: Math.round(pt.allBondVal),
      }))
    );

    const userSeries = chart.addSeries(LineSeries, {
      color: '#38bdf8',
      lineWidth: 2,
      lastValueVisible: true,
      priceLineVisible: false,
      title: 'Twój Portfel',
    });
    userSeries.setData(
      simulation.timeline.map((pt) => ({
        time: getMonthDateStr(pt.m),
        value: Math.round(pt.userVal),
      }))
    );

    if (simulation.troughMonth > 0) {
      userSeries.createPriceLine({
        price: Math.round(simulation.troughVal),
        color: '#f43f5e',
        lineWidth: 1,
        lineStyle: LineStyle.Dashed,
        axisLabelVisible: true,
        title: `Dołek (M${simulation.troughMonth}) | ${Math.round(simulation.troughVal).toLocaleString('pl-PL')} ${baseCurrency}`,
      });
    }

    chart.timeScale().fitContent();

    chart.subscribeCrosshairMove((param) => {
      if (!param.time) {
        setHoveredPt(null);
        return;
      }
      const timeStr = normalizeDateStr(param.time);
      const match = simulation.timeline.find((pt) => getMonthDateStr(pt.m) === timeStr);
      setHoveredPt(match || null);
    });

    const handleResize = () => {
      if (chartContainerRef.current && chartRef.current) {
        chartRef.current.applyOptions({
          width: chartContainerRef.current.clientWidth,
        });
      }
    };

    window.addEventListener('resize', handleResize);

    return () => {
      window.removeEventListener('resize', handleResize);
      chart.remove();
      chartRef.current = null;
    };
  }, [simulation, baseCurrency]);

  const totalMonthly = tosDca + edoDca + etfDca;

  return (
    <div className="space-y-5">
      <div className="bg-[#101115] border border-zinc-800 rounded-lg p-4 space-y-4">
        <div className="flex flex-col md:flex-row md:items-center justify-between gap-3 pb-3 border-b border-zinc-800">
          <div>
            <h2 className="text-base font-semibold text-white tracking-tight flex items-center gap-2">
              <AlertTriangle className="w-4 h-4 text-amber-400" />
              Stress-Testing Portfela: „Wehikuł Czasu”
            </h2>
            <p className="text-xs text-zinc-400 mt-0.5">
              Przetestuj odporność swojego obecnego portfela i comiesięcznych dopłat DCA w warunkach autentycznych kryzysów rynkowych.
            </p>
          </div>

          <div className="flex flex-wrap items-center gap-2">
            {scenarios.map((sc) => {
              const Icon = sc.icon;
              const isSel = sc.id === selectedScenarioId;
              return (
                <button
                  key={sc.id}
                  type="button"
                  onClick={() => setSelectedScenarioId(sc.id)}
                  className={`px-3 py-1.5 rounded-lg border text-xs font-medium transition cursor-pointer flex items-center gap-1.5 ${
                    isSel
                      ? 'bg-zinc-800 border-zinc-600 text-white shadow-sm'
                      : 'bg-zinc-950/80 border-zinc-800 text-zinc-400 hover:text-zinc-200'
                  }`}
                >
                  <Icon className="w-3.5 h-3.5" style={{ color: sc.color }} />
                  <span>{sc.shortTitle}</span>
                  <span className="text-[10px] text-zinc-500 font-mono">({sc.period})</span>
                </button>
              );
            })}
          </div>
        </div>

        <div className="grid grid-cols-1 md:grid-cols-2 gap-3">
          <div className="bg-zinc-950/70 border border-zinc-800/80 rounded-lg p-3.5 flex items-start gap-3">
            <currentScenario.icon
              className="w-5 h-5 shrink-0 mt-0.5"
              style={{ color: currentScenario.color }}
            />
            <div className="space-y-1">
              <div className="font-semibold text-white text-xs flex items-center gap-2">
                <span>{currentScenario.title}</span>
                <span className="text-[10px] font-mono px-2 py-0.5 rounded bg-zinc-900 text-zinc-400 border border-zinc-800">
                  {currentScenario.period}
                </span>
              </div>
              <p className="text-zinc-400 text-xs leading-relaxed">
                {currentScenario.description}
              </p>
            </div>
          </div>

          <div className="bg-zinc-950/70 border border-zinc-800/80 rounded-lg p-3.5 flex flex-col justify-between gap-2.5">
            <div className="text-xs font-semibold text-white">
              Twoje dopłaty DCA w kryzysie
            </div>
            <div className="flex flex-wrap items-center justify-between gap-1.5 font-mono text-xs bg-zinc-900/60 p-2 rounded-md border border-zinc-800/80">
              <div className="flex items-center gap-1">
                <input
                  type="number"
                  min="0"
                  step="100"
                  value={tosDca}
                  onChange={(e) => setTosDca(Math.max(0, Number(e.target.value) || 0))}
                  className="w-16 bg-zinc-950 border border-zinc-700 rounded px-1.5 py-1 text-amber-300 font-bold text-center text-xs focus:outline-none focus:border-amber-400 [appearance:textfield] [&::-webkit-outer-spin-button]:appearance-none [&::-webkit-inner-spin-button]:appearance-none"
                  title="TOS 3L"
                />
                <span className="text-zinc-400 text-[11px]">TOS +</span>
              </div>
              <div className="flex items-center gap-1">
                <input
                  type="number"
                  min="0"
                  step="100"
                  value={edoDca}
                  onChange={(e) => setEdoDca(Math.max(0, Number(e.target.value) || 0))}
                  className="w-16 bg-zinc-950 border border-zinc-700 rounded px-1.5 py-1 text-yellow-300 font-bold text-center text-xs focus:outline-none focus:border-yellow-400 [appearance:textfield] [&::-webkit-outer-spin-button]:appearance-none [&::-webkit-inner-spin-button]:appearance-none"
                  title="EDO 10L"
                />
                <span className="text-zinc-400 text-[11px]">EDO +</span>
              </div>
              <div className="flex items-center gap-1">
                <input
                  type="number"
                  min="0"
                  step="100"
                  value={etfDca}
                  onChange={(e) => setEtfDca(Math.max(0, Number(e.target.value) || 0))}
                  className="w-16 bg-zinc-950 border border-zinc-700 rounded px-1.5 py-1 text-emerald-400 font-bold text-center text-xs focus:outline-none focus:border-emerald-400 [appearance:textfield] [&::-webkit-outer-spin-button]:appearance-none [&::-webkit-inner-spin-button]:appearance-none"
                  title="ETF"
                />
                <span className="text-zinc-400 text-[11px]">ETF =</span>
              </div>
              <div className="text-white font-bold text-xs whitespace-nowrap pl-1">
                {totalMonthly.toLocaleString('pl-PL')} <span className="text-zinc-400 font-normal text-[11px]">{baseCurrency}/mc</span>
              </div>
            </div>
          </div>
        </div>

        <div className="grid grid-cols-2 md:grid-cols-4 gap-3">
          <div className="bg-zinc-950/80 border border-zinc-800/80 rounded-lg p-3">
            <div className="text-[11px] text-zinc-400 flex items-center justify-between">
              <span>Max Obsunięcie (Drawdown):</span>
              <ShieldCheck className="w-3.5 h-3.5 text-sky-400" />
            </div>
            <div className="text-xl font-bold text-rose-400 mt-1.5 font-mono">
              -{simulation.maxDrawdownPct.toFixed(1)}%
            </div>
            <div className="text-[10px] text-zinc-500 mt-1 truncate" title={`Twój portfel vs 100% Akcje -${simulation.maxStockDrawdownPct.toFixed(1)}% | 100% Obligacje 0.0%`}>
              Twój portfel vs 100% Akcje -{simulation.maxStockDrawdownPct.toFixed(1)}% | 100% Obligacje 0.0%
            </div>
          </div>

          <div className="bg-zinc-950/80 border border-zinc-800/80 rounded-lg p-3">
            <div className="text-[11px] text-zinc-400 flex items-center justify-between">
              <span>Wycena w Dołku Kryzysu:</span>
              <ArrowDownRight className="w-3.5 h-3.5 text-amber-400" />
            </div>
            <div className="text-xl font-bold text-amber-300 mt-1.5 font-mono">
              {simulation.troughVal.toLocaleString('pl-PL', { maximumFractionDigits: 0 })} {baseCurrency}
            </div>
            <div className="text-[10px] text-zinc-500 mt-1">
              Najgorszy moment: miesiąc {simulation.troughMonth}
            </div>
          </div>

          <div className="bg-zinc-950/80 border border-zinc-800/80 rounded-lg p-3">
            <div className="text-[11px] text-zinc-400 flex items-center justify-between">
              <span>Czas Odrobienia Strat:</span>
              <CheckCircle2 className="w-3.5 h-3.5 text-emerald-400" />
            </div>
            <div className="text-xl font-bold text-emerald-400 mt-1.5 font-mono">
              {formatMonths(simulation.recoveryMonth)}
            </div>
            <div className="text-[10px] text-zinc-500 mt-1">
              Powrót do poziomu sprzed krachu (ATH)
            </div>
          </div>

          <div className="bg-zinc-950/80 border border-zinc-800/80 rounded-lg p-3">
            <div className="text-[11px] text-zinc-400 flex items-center justify-between">
              <span>Wartość po Zakończeniu:</span>
              <span className="w-2 h-2 rounded-full bg-indigo-400" />
            </div>
            <div className="text-xl font-bold text-white mt-1.5 font-mono">
              {simulation.finalPt.userVal.toLocaleString('pl-PL', { maximumFractionDigits: 0 })} {baseCurrency}
            </div>
            <div className="text-[10px] text-emerald-400 mt-1">
              Zysk netto: +{(simulation.finalPt.userVal - simulation.finalPt.userCost).toLocaleString('pl-PL', { maximumFractionDigits: 0 })} {baseCurrency}
            </div>
          </div>
        </div>

        <div className="flex flex-wrap items-center justify-between gap-2 pt-2 border-t border-zinc-800/80 text-xs">
          <div className="flex flex-wrap items-center gap-3 font-mono text-[11px]">
            <span className="flex items-center gap-1.5 text-sky-300">
              <span className="w-2.5 h-2.5 rounded-full bg-sky-400" /> Twój Portfel (Zrównoważony)
            </span>
            <span className="flex items-center gap-1.5 text-rose-400">
              <span className="w-2.5 h-2.5 rounded-full bg-rose-500" /> 100% Akcje (Czysty krach)
            </span>
            <span className="flex items-center gap-1.5 text-amber-300">
              <span className="w-2.5 h-2.5 rounded-full bg-amber-400" /> 100% Obligacje Skarbowe
            </span>
            <span className="flex items-center gap-1.5 text-zinc-500">
              <span className="w-2.5 h-0.5 bg-zinc-500" /> Wkład własny (DCA)
            </span>
          </div>

          <div className="text-[11px] font-mono text-zinc-400">
            Kryzys przetestowany na kapitale:{' '}
            <strong className="text-white">
              {(currentBondVal + currentEquityVal).toLocaleString('pl-PL', { maximumFractionDigits: 0 })} {baseCurrency}
            </strong>
          </div>
        </div>

        <div className="relative w-full overflow-hidden bg-[#09090b] rounded-lg border border-zinc-800/80 p-3">
          {hoveredPt && (
            <div className="absolute top-4 right-4 z-20 pointer-events-none bg-zinc-950/95 border border-zinc-700/80 rounded-lg p-2.5 shadow-2xl text-xs font-mono backdrop-blur-md min-w-[240px]">
              <div className="font-semibold text-white border-b border-zinc-800 pb-1 mb-1.5 flex justify-between">
                <span>Miesiąc {hoveredPt.m} / {currentScenario.durationMonths}</span>
                <span className="text-zinc-400">Inflacja: {hoveredPt.cpi.toFixed(1)}%</span>
              </div>
              <div className="space-y-1 text-[11px]">
                <div className="flex justify-between items-center text-sky-300">
                  <span>Twój Portfel:</span>
                  <span className="font-bold">{hoveredPt.userVal.toLocaleString('pl-PL', { maximumFractionDigits: 0 })} {baseCurrency}</span>
                </div>
                <div className="flex justify-between items-center text-rose-400">
                  <span>100% Akcje:</span>
                  <span className="font-bold">{hoveredPt.allStockVal.toLocaleString('pl-PL', { maximumFractionDigits: 0 })} {baseCurrency}</span>
                </div>
                <div className="flex justify-between items-center text-amber-300">
                  <span>100% Obligacje:</span>
                  <span className="font-bold">{hoveredPt.allBondVal.toLocaleString('pl-PL', { maximumFractionDigits: 0 })} {baseCurrency}</span>
                </div>
                <div className="flex justify-between items-center text-zinc-400 pt-1 border-t border-zinc-800/60">
                  <span>Wkład własny (DCA):</span>
                  <span>{hoveredPt.userCost.toLocaleString('pl-PL', { maximumFractionDigits: 0 })} {baseCurrency}</span>
                </div>
              </div>
            </div>
          )}
          <div ref={chartContainerRef} className="w-full relative" />
        </div>

        <div className="bg-zinc-950/50 border border-zinc-800/60 rounded-lg p-3.5 text-xs text-zinc-300">
          <span className="font-semibold text-white">Podsumowanie testu: </span>
          <span>
            Twój portfel z alokacją w bezpieczne obligacje (TOS/EDO) skutecznie zamortyzował krach giełdowy, obcinając maksymalne obsunięcie z -{simulation.maxStockDrawdownPct.toFixed(1)}% do -{simulation.maxDrawdownPct.toFixed(1)}%. Regularne dopłaty DCA pozwoliły akumulować przecenione aktywa, skracając czas powrotu na nowe szczyty do {formatMonths(simulation.recoveryMonth)}.
          </span>
        </div>
      </div>
    </div>
  );
};
