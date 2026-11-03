export type CrisisScenarioId = 'covid-2020' | 'gfc-2008' | 'stagflation-2022';

export interface CrisisScenarioDefinition {
  id: CrisisScenarioId;
  durationMonths: number;
  color: string;
  translationKey: string;
  getMarketMultiplier: (m: number) => number;
  getInflationRate: (m: number) => number;
}

export const CRISIS_SCENARIOS: CrisisScenarioDefinition[] = [
  {
    id: 'covid-2020',
    durationMonths: 24,
    color: '#38bdf8',
    translationKey: 'monteCarlo.scenarios.covid2020',
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
    durationMonths: 48,
    color: '#fb7185',
    translationKey: 'monteCarlo.scenarios.gfc2008',
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
    durationMonths: 36,
    color: '#fbbf24',
    translationKey: 'monteCarlo.scenarios.stagflation2022',
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
