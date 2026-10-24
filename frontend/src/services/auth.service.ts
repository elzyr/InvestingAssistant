import apiClient, { refreshAuthResponse } from '../api/client.ts';
import type { AuthResponse, LoginRequest, RegisterRequest } from '../types/auth.ts';

export const authService = {
  login: async (credentials: LoginRequest): Promise<AuthResponse> => {
    return (await apiClient.post('/api/auth/login', credentials)).data;
  },
  register: async (credentials: RegisterRequest): Promise<AuthResponse> => {
    return (await apiClient.post('/api/auth/register', credentials)).data;
  },
  logout: async (): Promise<void> => {
    return (await apiClient.post('/api/auth/logout')).data;
  },
  refreshToken: async (): Promise<AuthResponse> => {
    return await refreshAuthResponse();
  },
};
