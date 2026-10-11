import {configureStore} from "@reduxjs/toolkit";
import authReducer from './slices/auth.slice.ts';

import portfolioReducer from './slices/portfolio.slice.ts';

export const store = configureStore({
    reducer: {
        auth: authReducer,
        portfolio: portfolioReducer,
    }
});

export type RootState = ReturnType<typeof store.getState>;
export type AppDispatch = typeof store.dispatch;