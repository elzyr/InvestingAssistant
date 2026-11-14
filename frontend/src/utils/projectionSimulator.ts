import type { ChartPoint, ProjectionHorizonOption } from '../types/dashboard.ts';
import type { ProjectionResponse } from '../types/valuation.types.ts';
import { parseUtcNoonMs } from './domain/dates.ts';
import { normalizeZero } from './reportsFormatters.ts';

export function horizonToYears(horizon: ProjectionHorizonOption): number {
  switch (horizon) {
    case '3Y':
      return 3;
    case '5Y':
      return 5;
    case '10Y':
      return 10;
    case '20Y':
      return 20;
    default:
      return 0;
  }
}

export function mapProjectionResponseToChartPoints(
  response: ProjectionResponse | null | undefined,
  options: {
    locale: string;
    weightedTer?: number;
    diversifiedVolatility?: number;
  }
): ChartPoint[] {
  if (!response || !Array.isArray(response.points) || response.points.length === 0) {
    return [];
  }
  const { locale, weightedTer, diversifiedVolatility } = options;
  const lastIdx = response.points.length - 1;
  const goalProbability =
    response.summary?.goalProbability != null ? response.summary.goalProbability : undefined;

  return response.points.map((pt, idx) => {
    const timestamp = parseUtcNoonMs(pt.date);
    const dateLabel = new Date(timestamp).toLocaleDateString(locale, {
      month: 'short',
      year: 'numeric',
    });
    const valuation = normalizeZero(Math.round(pt.p50));
    const cost = normalizeZero(Math.round(pt.invested));
    const profit = normalizeZero(valuation - cost);
    const returnPct = normalizeZero(
      Number((cost > 0 ? ((pt.p50 - pt.invested) / pt.invested) * 100 : 0).toFixed(2))
    );
    const returnP90 = normalizeZero(
      Number((cost > 0 ? ((pt.p90 - pt.invested) / pt.invested) * 100 : 0).toFixed(2))
    );
    const returnP10 = normalizeZero(
      Number((cost > 0 ? ((pt.p10 - pt.invested) / pt.invested) * 100 : 0).toFixed(2))
    );

    return {
      timestamp,
      dateLabel,
      valuation,
      cost,
      profit,
      returnPct,
      returnP90,
      returnP10,
      isFuture: true,
      netP90: normalizeZero(Math.round(pt.p90)),
      netP10: normalizeZero(Math.round(pt.p10)),
      goalProbability: idx === lastIdx ? goalProbability : undefined,
      weightedTer: weightedTer != null ? Number(weightedTer.toFixed(2)) : null,
      diversifiedVolatility:
        diversifiedVolatility != null ? Number(diversifiedVolatility.toFixed(1)) : null,
    };
  });
}

export function unifyProjectionTimeScale(data: ChartPoint[]): ChartPoint[] {
  const firstFutureIdx = data.findIndex((d) => d.isFuture);
  if (firstFutureIdx <= 1) return data;

  const past = data.slice(0, firstFutureIdx);
  const future = data.slice(firstFutureIdx);
  const firstPast = past[0];
  const lastPast = past[past.length - 1];
  const lastFuture = future[future.length - 1];

  const futureStepMs = (lastFuture.timestamp - lastPast.timestamp) / future.length;
  const pastAvgStepMs = (lastPast.timestamp - firstPast.timestamp) / (past.length - 1);
  if (futureStepMs <= 0 || pastAvgStepMs >= futureStepMs * 0.9) return data;

  const selected: ChartPoint[] = [lastPast];
  let cursor = past.length - 1;

  for (let step = 1; cursor > 0; step++) {
    const targetTs = lastPast.timestamp - step * futureStepMs;
    if (targetTs < firstPast.timestamp + futureStepMs * 0.45) break;
    if (past[cursor - 1].timestamp < targetTs - futureStepMs * 0.5) continue;

    let bestIdx = cursor - 1;
    let bestDiff = Math.abs(past[bestIdx].timestamp - targetTs);
    for (let i = cursor - 2; i >= 0; i--) {
      const diff = Math.abs(past[i].timestamp - targetTs);
      if (diff <= bestDiff) {
        bestDiff = diff;
        bestIdx = i;
      } else {
        break;
      }
    }

    selected.push(past[bestIdx]);
    cursor = bestIdx;
    if (bestIdx === 0) break;
  }

  if (selected[selected.length - 1] !== firstPast) {
    selected.push(firstPast);
  }

  selected.reverse();
  return [...selected, ...future];
}
