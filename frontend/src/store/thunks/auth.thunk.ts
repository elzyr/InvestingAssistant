import { createAsyncThunk } from '@reduxjs/toolkit';
import { isAxiosError } from 'axios';
import type { AuthResponse, LoginRequest, RegisterRequest, UserProfileResponse } from '../../types/auth.ts';
import { authService } from '../../services/auth.service.ts';
import { setAccessToken } from '../../api/client.ts';

function toAuthPayload(authData: AuthResponse): { accessToken: string; user: UserProfileResponse } {
  setAccessToken(authData.accessToken);
  return { accessToken: authData.accessToken, user: authData.user };
}

export const loginThunk = createAsyncThunk<
  { accessToken: string; user: UserProfileResponse },
  LoginRequest,
  { rejectValue: string }
>('auth/login', async (credentials, { rejectWithValue }) => {
  try {
    return toAuthPayload(await authService.login(credentials));
  } catch (error) {
    if (isAxiosError<{ message?: string }>(error) && error.response?.data?.message) {
      return rejectWithValue(error.response.data.message);
    }
    return rejectWithValue('auth.errors.defaultLoginError');
  }
});

export const registerThunk = createAsyncThunk<
  { accessToken: string; user: UserProfileResponse },
  RegisterRequest,
  { rejectValue: string }
>('auth/register', async (credentials, { rejectWithValue }) => {
  try {
    return toAuthPayload(await authService.register(credentials));
  } catch (error) {
    if (isAxiosError<{ message?: string }>(error) && error.response?.data?.message) {
      return rejectWithValue(error.response.data.message);
    }
    return rejectWithValue('auth.errors.defaultRegisterError');
  }
});

export const checkAuthThunk = createAsyncThunk<
  { accessToken: string; user: UserProfileResponse },
  void,
  { rejectValue: null }
>('auth/checkAuth', async (_, { rejectWithValue }) => {
  try {
    return toAuthPayload(await authService.refreshToken());
  } catch {
    setAccessToken(null);
    return rejectWithValue(null);
  }
});

export const logoutThunk = createAsyncThunk<void, void>('auth/logout', async () => {
  try {
    await authService.logout();
  } finally {
    setAccessToken(null);
  }
});
