import axios, { AxiosError, type InternalAxiosRequestConfig } from 'axios';
import type { AuthResponse } from '../types/auth.ts';

interface CustomAxiosRequestConfig extends InternalAxiosRequestConfig {
  _retry?: boolean;
}

const API_BASE_URL = import.meta.env.VITE_API_BASE_URL || '';

let memoryToken: string | null = null;
let onAuthExpiredCallback: (() => void) | null = null;

export const setOnAuthExpired = (callback: () => void): void => {
  onAuthExpiredCallback = callback;
};

export const setAccessToken = (token: string | null): void => {
  memoryToken = token;
};

export const getAccessToken = (): string | null => {
  return memoryToken;
};

export const apiClient = axios.create({
  baseURL: API_BASE_URL,
  headers: {
    'Content-Type': 'application/json',
  },
  withCredentials: true,
});

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
    refreshPromise = axios
      .post<AuthResponse>(
        `${API_BASE_URL}/api/auth/refresh`,
        {},
        { withCredentials: true }
      )
      .then((res) => {
        const authData = res.data;
        setAccessToken(authData.accessToken);
        return authData;
      })
      .catch((err) => {
        setAccessToken(null);
        if (onAuthExpiredCallback) {
          onAuthExpiredCallback();
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
