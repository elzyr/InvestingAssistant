import type { ChartPoint } from '../../../types/dashboard.ts';
import type { UnifiedEngineParams } from './types.ts';
import { prepareTimelineState } from './prepare.ts';
import { buildHistoryPoints } from './history.ts';
import { attachContributors } from './attribution.ts';
import { appendProjection } from './projection.ts';

export type { UnifiedEngineParams };

export function generateUnifiedPortfolioTimeline(params: UnifiedEngineParams): ChartPoint[] {
  const state = prepareTimelineState(params);
  const { points, rawStepContributors, keyMetaMap } = buildHistoryPoints(state, params);
  attachContributors(points, rawStepContributors, keyMetaMap, state.totalHistorySteps, params.locale);
  appendProjection(points, state, params);
  return points;
}
