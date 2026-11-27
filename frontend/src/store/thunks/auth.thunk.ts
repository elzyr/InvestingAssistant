import { createAsyncThunk } from '@reduxjs/toolkit';
import type { AuthResponse, LoginRequest, RegisterRequest, UserProfileResponse } from '../../types/auth.ts';
import { authService } from '../../services/auth.service.ts';
import {
  extractApiErrorMessage,
  getAuthEpoch,
  refreshAuthResponse,
  setAccessToken,
} from '../../api/client.ts';

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
    return rejectWithValue(extractApiErrorMessage(error, 'auth.errors.defaultLoginError', true));
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
    return rejectWithValue(extractApiErrorMessage(error, 'auth.errors.defaultRegisterError', true));
  }
});

export const checkAuthThunk = createAsyncThunk<
  { accessToken: string; user: UserProfileResponse },
  void,
  { rejectValue: null }
>('auth/checkAuth', async (_, { rejectWithValue }) => {
  const startEpoch = getAuthEpoch();
  try {
    const authData = await refreshAuthResponse();
    if (getAuthEpoch() !== startEpoch) {
      return rejectWithValue(null);
    }
    return toAuthPayload(authData);
  } catch {
    setAccessToken(null, startEpoch);
    return rejectWithValue(null);
  }
});

export const logoutThunk = createAsyncThunk<void, void>('auth/logout', async () => {
  setAccessToken(null);
  try {
    await authService.logout();
  } finally {
    setAccessToken(null);
  }
});
