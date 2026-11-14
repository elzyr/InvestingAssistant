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

function clearAuthSession(state: AuthState): void {
    state.user = null;
    state.isAuthenticated = false;
    state.loading = false;
    state.error = null;
}

export const authSlice = createSlice({
    name: 'auth',
    initialState,
    reducers: {
        clearAuthError: (state) => {
            state.error = null;
        },
        forceLogout: (state) => {
            clearAuthSession(state);
        }
    },
    extraReducers: (builder) => {
        for (const authThunk of [loginThunk, registerThunk]) {
            builder
                .addCase(authThunk.pending, (state) => {
                    state.loading = true;
                    state.error = null;
                })
                .addCase(authThunk.fulfilled, (state, action) => {
                    state.loading = false;
                    state.isAuthenticated = true;
                    state.user = action.payload.user;
                    state.error = null;
                })
                .addCase(authThunk.rejected, (state, action) => {
                    state.loading = false;
                    state.error = action.payload || null;
                });
        }
        builder
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
                clearAuthSession(state);
            })
            .addCase(logoutThunk.rejected, (state) => {
                clearAuthSession(state);
            });
    }
});

export const { clearAuthError, forceLogout } = authSlice.actions;
export default authSlice.reducer;
