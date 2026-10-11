import apiClient from '../api/client.ts';
import type { InstrumentResponse, InstrumentType } from '../types/instrument.ts';

export const instrumentService = {
  async getInstruments(
    search?: string,
    type?: InstrumentType
  ): Promise<InstrumentResponse[]> {
    const params: Record<string, string> = {};
    if (search) params.search = search;
    if (type) params.type = type;
    const response = await apiClient.get<InstrumentResponse[]>('/api/instruments', {
      params,
    });
    return response.data;
  },
};
