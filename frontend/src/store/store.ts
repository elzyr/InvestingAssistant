import {configureStore} from "@reduxjs/toolkit";
import authReducer, { forceLogout } from './slices/auth.slice.ts';
import portfolioReducer, { resetPortfolio } from './slices/portfolio.slice.ts';
import { setOnAuthExpired } from '../api/client.ts';

export const store = configureStore({
    reducer: {
        auth: authReducer,
        portfolio: portfolioReducer,
    },
    devTools: import.meta.env.DEV,
});

setOnAuthExpired(() => {
    store.dispatch(forceLogout());
    store.dispatch(resetPortfolio());
});

export type RootState = ReturnType<typeof store.getState>;
export type AppDispatch = typeof store.dispatch;