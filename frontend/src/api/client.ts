import axios, { AxiosError, type InternalAxiosRequestConfig } from 'axios';
import type { AuthResponse } from '../types/auth.ts';

interface CustomAxiosRequestConfig extends InternalAxiosRequestConfig {
  _retry?: boolean;
}

const API_BASE_URL = import.meta.env?.VITE_API_BASE_URL || '';

let memoryToken: string | null = null;
let authEpoch = 0;
let onAuthExpiredCallback: (() => void) | null = null;

export const setOnAuthExpired = (callback: () => void): void => {
  onAuthExpiredCallback = callback;
};

export const getAuthEpoch = (): number => authEpoch;

export const setAccessToken = (token: string | null, expectedEpoch?: number): void => {
  if (expectedEpoch !== undefined && expectedEpoch !== authEpoch) {
    return;
  }
  if (expectedEpoch === undefined) {
    authEpoch += 1;
  }
  memoryToken = token;
};

export const getAccessToken = (): string | null => {
  return memoryToken;
};

export const apiClient = axios.create({
  baseURL: API_BASE_URL,
  timeout: 45000,
  headers: {
    'Content-Type': 'application/json',
  },
  withCredentials: true,
});

export function extractApiErrorMessage(
  error: unknown,
  fallback: string,
  preferFallbackOnTransport = false
): string {
  if (axios.isAxiosError<{ message?: string }>(error)) {
    const serverMessage = error.response?.data?.message;
    if (serverMessage) return serverMessage;
    if (!preferFallbackOnTransport && error.message) return error.message;
  }
  return fallback;
}

apiClient.interceptors.request.use(
  (config: InternalAxiosRequestConfig) => {
    const token = getAccessToken();
    if (token) {
      config.headers.Authorization = `Bearer ${token}`;
    }
    return config;
  },
  (error: AxiosError) => Promise.reject(error)
);

let refreshPromise: Promise<AuthResponse> | null = null;

export const refreshAuthResponse = (): Promise<AuthResponse> => {
  if (!refreshPromise) {
    const requestEpoch = authEpoch;
    refreshPromise = axios
      .post<AuthResponse>(
        `${API_BASE_URL}/api/auth/refresh`,
        {},
        { withCredentials: true, timeout: 15000 }
      )
      .then((res) => {
        const authData = res.data;
        setAccessToken(authData.accessToken, requestEpoch);
        return authData;
      })
      .catch((err) => {
        const status = axios.isAxiosError(err) ? err.response?.status : undefined;
        if ((status === 401 || status === 403) && requestEpoch === authEpoch) {
          setAccessToken(null);
          onAuthExpiredCallback?.();
        }
        return Promise.reject(err);
      })
      .finally(() => {
        refreshPromise = null;
      });
  }
  return refreshPromise;
};

apiClient.interceptors.response.use(
  (response) => response,
  async (error: AxiosError) => {
    const originalRequest = error.config as CustomAxiosRequestConfig;

    if (error.response?.status === 401 && originalRequest && !originalRequest._retry) {
      if (originalRequest.url?.includes('/api/auth/')) {
        return Promise.reject(error);
      }

      originalRequest._retry = true;

      try {
        const authData = await refreshAuthResponse();
        originalRequest.headers.Authorization = `Bearer ${authData.accessToken}`;
        return apiClient(originalRequest);
      } catch (refreshError) {
        return Promise.reject(refreshError);
      }
    }

    return Promise.reject(error);
  }
);

export default apiClient;
