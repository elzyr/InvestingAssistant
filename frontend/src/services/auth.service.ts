import apiClient from "../api/client.ts";
import type {AuthResponse, LoginRequest, RegisterRequest, UserProfileResponse} from "../types/auth.ts";

export const authService = {
    login: async (credentials: LoginRequest): Promise<AuthResponse> => {
        return (await apiClient.post('/api/auth/login', credentials)).data;
    },
    register: async (credentials: RegisterRequest): Promise<AuthResponse> => {
        return (await apiClient.post('/api/auth/register', credentials)).data;
    },
    getCurrentUser: async (): Promise<UserProfileResponse> => {
        return (await apiClient.get(`/api/auth/me`)).data;
    },
    logout: async (): Promise<void> => {
        return (await apiClient.post('/api/auth/logout')).data;
    },
    refreshToken: async (): Promise<AuthResponse> => {
        return (await apiClient.post(`/api/auth/refresh`)).data;
    }
};