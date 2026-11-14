import { describe, it } from 'node:test';
import assert from 'node:assert/strict';
import {
  horizonToYears,
  mapProjectionResponseToChartPoints,
  unifyProjectionTimeScale,
} from './projectionSimulator.ts';

function makeFuturePoints(startMs, months) {
  const startDate = new Date(startMs);
  const points = Array.from({ length: months }, (_, idx) => {
    const d = new Date(Date.UTC(startDate.getUTCFullYear(), startDate.getUTCMonth() + idx + 1, startDate.getUTCDate()));
    return {
      monthOffset: idx + 1,
      date: d.toISOString().slice(0, 10),
      p10: 95000 + idx * 500,
      p50: 100000 + idx * 1000,
      p90: 105000 + idx * 1500,
      invested: 90000 + idx * 500,
    };
  });
  return mapProjectionResponseToChartPoints(
    {
      points,
      proxyWarnings: [],
      summary: {
        terminalP10: points[points.length - 1].p10,
        terminalP50: points[points.length - 1].p50,
        terminalP90: points[points.length - 1].p90,
        totalInvested: points[points.length - 1].invested,
        goalProbability: 72,
      },
    },
    { locale: 'pl-PL', weightedTer: 0.2, diversifiedVolatility: 14.5 }
  );
}

describe('projectionSimulator', () => {
  it('maps horizonToYears and empty/null projection responses cleanly', () => {
    assert.strictEqual(horizonToYears('OFF'), 0);
    assert.strictEqual(horizonToYears('3Y'), 3);
    assert.strictEqual(horizonToYears('5Y'), 5);
    assert.strictEqual(horizonToYears('10Y'), 10);
    assert.strictEqual(horizonToYears('20Y'), 20);
    assert.deepStrictEqual(mapProjectionResponseToChartPoints(null, { locale: 'en-US' }), []);
  });

  it('maps server Monte Carlo response points with bands and terminal goalProbability', () => {
    const pts = makeFuturePoints(Date.UTC(2026, 0, 1), 12);
    assert.strictEqual(pts.length, 12);
    assert.strictEqual(pts[0].isFuture, true);
    assert.strictEqual(pts[0].goalProbability, undefined);
    assert.strictEqual(pts[11].goalProbability, 72);
    assert.strictEqual(pts[0].weightedTer, 0.2);
    assert.strictEqual(pts[0].diversifiedVolatility, 14.5);
    assert.ok((pts[0].netP10 ?? 0) <= pts[0].valuation && pts[0].valuation <= (pts[0].netP90 ?? 0));
  });

  it('unifyProjectionTimeScale downsamples dense daily history to monthly cadence', () => {
    const startMs = Date.UTC(2025, 9, 14);
    const endMs = Date.UTC(2026, 10, 14);
    const totalHistoryDays = (endMs - startMs) / 86400_000;
    const history = Array.from({ length: 120 }, (_, idx) => ({
      timestamp: Math.round(startMs + (totalHistoryDays * 86400_000 * idx) / 119),
      dateLabel: `h${idx}`,
      valuation: 10000 + idx * 250,
      cost: 10000 + idx * 200,
      profit: idx * 50,
      returnPct: 4.87,
    }));

    const future = makeFuturePoints(endMs, 36);
    const unified = unifyProjectionTimeScale([...history, ...future]);
    const unifiedPast = unified.filter((p) => !p.isFuture);
    const unifiedFuture = unified.filter((p) => p.isFuture);

    assert.strictEqual(unifiedFuture.length, future.length);
    assert.strictEqual(unifiedPast[0].timestamp, history[0].timestamp);
    assert.strictEqual(unifiedPast[unifiedPast.length - 1].timestamp, history[history.length - 1].timestamp);
    assert.ok(unifiedPast.length >= 12 && unifiedPast.length <= 16);

    for (let i = 1; i < unified.length; i++) {
      assert.ok(unified[i].timestamp > unified[i - 1].timestamp);
    }
  });

  it('unifyProjectionTimeScale collapses dense 1M daily history to boundary points for monthly forecast', () => {
    const startMs = Date.UTC(2026, 9, 14);
    const history = Array.from({ length: 30 }, (_, idx) => ({
      timestamp: startMs + idx * 86400_000,
      dateLabel: `d${idx}`,
      valuation: 40000 + idx * 30,
      cost: 39000,
      profit: 1000 + idx * 30,
      returnPct: 2.5,
    }));
    const endMs = history[history.length - 1].timestamp;
    const future = makeFuturePoints(endMs, 36);

    const unified = unifyProjectionTimeScale([...history, ...future]);
    const unifiedPast = unified.filter((p) => !p.isFuture);

    assert.strictEqual(unifiedPast.length, 2);
    assert.strictEqual(unifiedPast[0].timestamp, history[0].timestamp);
    assert.strictEqual(unifiedPast[1].timestamp, endMs);
    assert.strictEqual(unified.length, 2 + future.length);
  });

  it('unifyProjectionTimeScale preserves history when historical step is already coarser than future step', () => {
    const startMs = Date.UTC(2006, 0, 1);
    const endMs = Date.UTC(2026, 0, 1);
    const history = Array.from({ length: 120 }, (_, idx) => ({
      timestamp: Math.round(startMs + ((endMs - startMs) * idx) / 119),
      dateLabel: `h${idx}`,
      valuation: 10000 + idx * 500,
      cost: 10000,
      profit: idx * 500,
      returnPct: idx * 5,
    }));
    const future = makeFuturePoints(endMs, 36);

    const combined = [...history, ...future];
    const unified = unifyProjectionTimeScale(combined);
    assert.strictEqual(unified.length, combined.length);
  });
});
