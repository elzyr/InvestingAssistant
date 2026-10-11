import { Navigate, Outlet, useLocation } from 'react-router-dom';
import { useAppSelector } from '../store/hooks.ts';

export const ProtectedRoute = () => {
  const { isAuthenticated, isInitialized } = useAppSelector((state) => state.auth);
  const location = useLocation();

  if (!isInitialized) {
    return (
      <div className="min-h-screen bg-[#09090b] flex items-center justify-center">
        <div className="flex flex-col items-center gap-3">
          <div className="w-8 h-8 rounded-full border-2 border-zinc-700 border-t-zinc-200 animate-spin" />
          <span className="text-xs text-zinc-500 font-mono">InvestingAssistant...</span>
        </div>
      </div>
    );
  }

  if (!isAuthenticated) {
    return <Navigate to="/login" replace state={{ from: location }} />;
  }

  return <Outlet />;
};
