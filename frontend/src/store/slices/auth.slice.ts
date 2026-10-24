import type {AuthState} from "../../types/auth.ts";
import {createSlice} from "@reduxjs/toolkit";
import {checkAuthThunk, loginThunk, logoutThunk, registerThunk} from "../thunks/auth.thunk.ts";

const initialState: AuthState = {
    user: null,
    isAuthenticated: false,
    isInitialized: false,
    loading: false,
    error: null,
};

export const authSlice = createSlice({
    name: 'auth',
    initialState,
    reducers: {
        clearAuthError: (state) => {
            state.error = null;
        },
        forceLogout: (state) => {
            state.user = null;
            state.isAuthenticated = false;
            state.loading = false;
            state.error = null;
        }
    },
    extraReducers: (builder) => {
        builder
            .addCase(loginThunk.pending, (state) => {
                state.loading = true;
                state.error = null;
            })
            .addCase(loginThunk.fulfilled, (state, action) => {
                state.loading = false;
                state.isAuthenticated = true;
                state.user = action.payload.user;
                state.error = null;
            })
            .addCase(loginThunk.rejected, (state, action) => {
                state.loading = false;
                state.error = action.payload || null;
            })
            .addCase(registerThunk.pending, (state) => {
                state.loading = true;
                state.error = null;
            })
            .addCase(registerThunk.fulfilled, (state, action) => {
                state.loading = false;
                state.isAuthenticated = true;
                state.user = action.payload.user;
                state.error = null;
            })
            .addCase(registerThunk.rejected, (state, action) => {
                state.loading = false;
                state.error = action.payload || null;
            })
            .addCase(checkAuthThunk.fulfilled, (state, action) => {
                state.loading = false;
                state.isInitialized = true;
                state.isAuthenticated = true;
                state.user = action.payload.user;
            })
            .addCase(checkAuthThunk.rejected, (state) => {
                state.loading = false;
                state.isInitialized = true;
                state.isAuthenticated = false;
                state.user = null;
                })
            .addCase(logoutThunk.fulfilled, (state) => {
                state.user = null;
                    state.isAuthenticated = false;
                state.loading = false;
                state.error = null;
            });
    }
});

export const { clearAuthError, forceLogout } = authSlice.actions;
export default authSlice.reducer;
