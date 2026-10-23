import { lazy, Suspense, useEffect } from 'react';
import { BrowserRouter, Routes, Route, Navigate } from 'react-router-dom';
import { useAppDispatch } from './store/hooks.ts';
import { checkAuthThunk } from './store/thunks/auth.thunk.ts';

import { ProtectedRoute } from './routes/ProtectedRoute.tsx';
import { AppLayout } from './components/layout/AppLayout.tsx';

const LoginPage = lazy(() => import('./pages/LoginPage.tsx').then((m) => ({ default: m.LoginPage })));
const RegisterPage = lazy(() => import('./pages/RegisterPage.tsx').then((m) => ({ default: m.RegisterPage })));
const DashboardPage = lazy(() => import('./pages/DashboardPage.tsx').then((m) => ({ default: m.DashboardPage })));
const MonteCarloPage = lazy(() => import('./pages/MonteCarloPage.tsx').then((m) => ({ default: m.MonteCarloPage })));
const MonthlyReportsPage = lazy(() => import('./pages/MonthlyReportsPage.tsx').then((m) => ({ default: m.MonthlyReportsPage })));

function App() {
  const dispatch = useAppDispatch();

  useEffect(() => {
    dispatch(checkAuthThunk());
  }, [dispatch]);

  return (
    <BrowserRouter>
      <Suspense fallback={<div className="min-h-screen bg-slate-950 flex items-center justify-center text-slate-400">Ładowanie...</div>}>
        <Routes>
          {/* Public auth routes */}
          <Route path="/login" element={<LoginPage />} />
          <Route path="/register" element={<RegisterPage />} />

          {/* Protected application routes */}
          <Route element={<ProtectedRoute />}>
            <Route element={<AppLayout />}>
              <Route path="/" element={<DashboardPage />} />
              <Route path="/reports" element={<MonthlyReportsPage />} />
              <Route path="/stress-testing" element={<MonteCarloPage />} />
            </Route>
          </Route>

          {/* Fallback route */}
          <Route path="*" element={<Navigate to="/" replace />} />
        </Routes>
      </Suspense>
    </BrowserRouter>
  );
}

export default App;
