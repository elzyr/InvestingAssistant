import {createAsyncThunk} from "@reduxjs/toolkit";
import type {LoginRequest, RegisterRequest, UserProfileResponse} from "../../types/auth.ts";
import {authService} from "../../services/auth.service.ts";
import {setAccessToken} from "../../api/client.ts";
import axios from "axios";

export const loginThunk = createAsyncThunk<{ accessToken: string; user: UserProfileResponse }, LoginRequest, {
    rejectValue: string
}>(
    'auth/login',
    async (credentials, {rejectWithValue}) => {
        try {
            const authData = await authService.login(credentials);
            setAccessToken(authData.accessToken);
            const user = authData.user ?? (await authService.getCurrentUser());
            return {accessToken: authData.accessToken, user};
        } catch (error) {
            if (axios.isAxiosError(error) && error.response?.data?.message) {
                return rejectWithValue(error.response.data.message);
            }
            return rejectWithValue('auth.errors.defaultLoginError');
        }
    }
);


export const registerThunk = createAsyncThunk<{ accessToken: string; user: UserProfileResponse }, RegisterRequest, {
    rejectValue: string
}>(
    'auth/register',
    async (credentials, {rejectWithValue}) => {
        try {
            const authData = await authService.register(credentials);
            setAccessToken(authData.accessToken);
            const user = authData.user ?? (await authService.getCurrentUser());
            return {accessToken: authData.accessToken, user};
        } catch (error) {
            if (axios.isAxiosError(error) && error.response?.data?.message) {
                return rejectWithValue(error.response.data.message);
            }
            return rejectWithValue('auth.errors.defaultRegisterError');
        }
    }
);

export const checkAuthThunk = createAsyncThunk<{ accessToken: string; user: UserProfileResponse }, void, {
    rejectValue: null
}>(
    'auth/checkAuth',
    async (_, {rejectWithValue}) => {
        try {
            const authData = await authService.refreshToken();
            setAccessToken(authData.accessToken);
            const user = authData.user ?? (await authService.getCurrentUser());
            return {accessToken: authData.accessToken, user};
        } catch {
            setAccessToken(null);
            return rejectWithValue(null);
        }
    }
);

export const logoutThunk = createAsyncThunk<void, void>(
    'auth/logout',
    async () => {
        try {
            await authService.logout();
        } finally {
            setAccessToken(null);
        }
    }
);