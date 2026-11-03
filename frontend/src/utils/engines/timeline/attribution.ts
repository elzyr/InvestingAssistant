import i18n from '../../../i18n/config.ts';
import type { ChartPoint, ProfitContributor } from '../../../types/dashboard.ts';
import type { RawContributor } from './types.ts';

export function attachContributors(
  points: ChartPoint[],
  rawStepContributors: Map<string, RawContributor>[],
  keyMetaMap: Map<string, { label: string; assetType: 'ETF' | 'BOND' | 'STOCK' | 'OTHER' }>,
  totalHistorySteps: number,
  _locale = 'pl-PL'
): void {
  const allDistinctKeys = new Set<string>();
  const maxAbsProfitMap = new Map<string, number>();

  for (let s = 0; s < rawStepContributors.length; s++) {
    const map = rawStepContributors[s];
    for (const [k, c] of map.entries()) {
      allDistinctKeys.add(k);
      const absP = Math.abs(c.netProfit);
      const prevMax = maxAbsProfitMap.get(k) || 0;
      if (absP > prevMax) {
        maxAbsProfitMap.set(k, absP);
      }
    }
  }

  const latestStepMap = rawStepContributors[rawStepContributors.length - 1];

  const sortedKeys = Array.from(allDistinctKeys).sort((a, b) => {
    const pA = Math.abs(latestStepMap?.get(a)?.netProfit ?? 0);
    const pB = Math.abs(latestStepMap?.get(b)?.netProfit ?? 0);
    if (Math.abs(pB - pA) > 1e-4) {
      return pB - pA;
    }
    const maxA = maxAbsProfitMap.get(a) ?? 0;
    const maxB = maxAbsProfitMap.get(b) ?? 0;
    if (Math.abs(maxB - maxA) > 1e-4) {
      return maxB - maxA;
    }
    return a.localeCompare(b);
  });

  const hasOther = sortedKeys.length > 5;
  const topKeys = hasOther ? sortedKeys.slice(0, 5) : sortedKeys;
  const otherKeys = hasOther ? sortedKeys.slice(5) : [];

  for (let i = 0; i <= totalHistorySteps; i++) {
    const map = rawStepContributors[i];
    const point = points[i];
    const pointProfit = point.profit;
    const pointCost = point.cost;

    const contributors: ProfitContributor[] = [];

    for (const k of topKeys) {
      const c = map.get(k);
      const meta = keyMetaMap.get(k);
      contributors.push({
        key: k,
        label: c?.label ?? meta?.label ?? k,
        assetType: c?.assetType ?? meta?.assetType ?? 'OTHER',
        valuation: Math.round(c?.valuation ?? 0),
        cost: Math.round(c?.cost ?? 0),
        profit: Math.round(c?.netProfit ?? 0),
        returnContributionPct: 0,
        sharePct: 0,
      });
    }

    if (hasOther) {
      let otherVal = 0;
      let otherCost = 0;
      let otherNetProfit = 0;
      for (const ok of otherKeys) {
        const c = map.get(ok);
        if (c) {
          otherVal += c.valuation;
          otherCost += c.cost;
          otherNetProfit += c.netProfit;
        }
      }
      contributors.push({
        key: 'OTHER',
        label: i18n.t('chart.otherContributors'),
        assetType: 'OTHER',
        valuation: Math.round(otherVal),
        cost: Math.round(otherCost),
        profit: Math.round(otherNetProfit),
        returnContributionPct: 0,
        sharePct: 0,
      });
    }

    if (contributors.length > 0) {
      const sumProfit = contributors.reduce((acc, c) => acc + c.profit, 0);
      const residual = pointProfit - sumProfit;
      if (residual !== 0) {
        let bestIdx = 0;
        let bestAbs = -1;
        let bestBase = -1;
        for (let k = 0; k < contributors.length; k++) {
          const a = Math.abs(contributors[k].profit);
          const base = contributors[k].valuation + contributors[k].cost;
          if (a > bestAbs || (a === bestAbs && base > bestBase)) {
            bestAbs = a;
            bestBase = base;
            bestIdx = k;
          }
        }
        contributors[bestIdx].profit += residual;
      }

      const absSum = contributors.reduce((acc, c) => acc + Math.abs(c.profit), 0);
      for (const c of contributors) {
        c.returnContributionPct = pointCost > 0 ? Number(((c.profit / pointCost) * 100).toFixed(2)) : 0;
        c.sharePct = absSum > 0 ? Number(((c.profit / absSum) * 100).toFixed(1)) : 0;
      }

      if (contributors.length > 0) {
        if (absSum > 0) {
          const targetShareSum = Number(((sumProfit / absSum) * 100).toFixed(1));
          const sumShare = Number(contributors.reduce((s, c) => s + c.sharePct, 0).toFixed(1));
          const shareDiff = Number((targetShareSum - sumShare).toFixed(1));
          if (Math.abs(shareDiff) > 0.0001 && Math.abs(shareDiff) <= 1.0) {
            let maxIdx = 0;
            for (let k = 1; k < contributors.length; k++) {
              if (Math.abs(contributors[k].profit) > Math.abs(contributors[maxIdx].profit)) {
                maxIdx = k;
              }
            }
            contributors[maxIdx].sharePct = Number((contributors[maxIdx].sharePct + shareDiff).toFixed(1));
          }
        }

        if (pointCost > 0) {
          const targetReturn = Number(point.returnPct.toFixed(2));
          const sumReturn = Number(contributors.reduce((s, c) => s + c.returnContributionPct, 0).toFixed(2));
          const returnDiff = Number((targetReturn - sumReturn).toFixed(2));
          if (Math.abs(returnDiff) > 0.0001 && Math.abs(returnDiff) <= 0.5) {
            let maxIdx = 0;
            for (let k = 1; k < contributors.length; k++) {
              if (Math.abs(contributors[k].profit) > Math.abs(contributors[maxIdx].profit)) {
                maxIdx = k;
              }
            }
            contributors[maxIdx].returnContributionPct = Number((contributors[maxIdx].returnContributionPct + returnDiff).toFixed(2));
          }
        }
      }
    }

    point.contributors = contributors;
  }
}
