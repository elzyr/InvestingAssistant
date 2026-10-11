import apiClient from '../api/client.ts';

export interface MonteCarloPoint {
  year: number;
  worstCase: number;
  medianCase: number;
  bestCase: number;
}

export interface MonteCarloResponse {
  worstCaseFinal: number;
  medianFinal: number;
  bestCaseFinal: number;
  timeline: MonteCarloPoint[];
}

export interface MonteCarloRequest {
  initialInvestment: number;
  monthlyContribution: number;
  timeHorizonYears: number;
  simulationsCount: number;
  expectedAnnualReturn: number;
  annualVolatility: number;
}

export const analyticsService = {
  async runMonteCarlo(request: MonteCarloRequest): Promise<MonteCarloResponse> {
    const response = await apiClient.post<MonteCarloResponse>(
      '/api/analytics/monte-carlo',
      request
    );
    return response.data;
  },
};
