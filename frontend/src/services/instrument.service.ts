import apiClient from '../api/client.ts';
import type { InstrumentResponse, InstrumentType } from '../types/instrument.ts';

export const instrumentService = {
  async getInstruments(
    search?: string,
    type?: InstrumentType,
    signal?: AbortSignal
  ): Promise<InstrumentResponse[]> {
    const params: Record<string, string> = {};
    if (search) params.search = search;
    if (type) params.type = type;
    const response = await apiClient.get<InstrumentResponse[]>('/api/instruments', {
      params,
      signal,
    });
    return response.data;
  },

  async discoverInstruments(
    search?: string,
    type?: InstrumentType,
    signal?: AbortSignal
  ): Promise<InstrumentResponse[]> {
    const params: Record<string, string> = {};
    if (search) params.search = search;
    if (type) params.type = type;
    const response = await apiClient.post<InstrumentResponse[]>('/api/instruments/discover', null, {
      params,
      signal,
    });
    return response.data;
  },
};
