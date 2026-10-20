import { Component, StrictMode } from 'react';
import type { ErrorInfo, ReactNode } from 'react';
import { createRoot } from 'react-dom/client';
import App from './App';
import './index.css';

// If a screen ever crashes, show a way back instead of a blank page. Saved lists and settings are not affected.
class Guard extends Component<{ children: ReactNode }, { failed: boolean }> {
  state = { failed: false };
  static getDerivedStateFromError() {
    return { failed: true };
  }
  componentDidCatch(_e: Error, _i: ErrorInfo) {}
  render() {
    if (!this.state.failed) return this.props.children;
    return (
      <div className="flex h-full flex-col items-center justify-center gap-4 p-8 text-center">
        <div className="text-xl font-bold">Something went wrong on this screen</div>
        <div className="text-sm text-slate-600">Your saved list and settings are safe.</div>
        <button onClick={() => location.reload()} className="rounded-2xl bg-brand px-6 py-4 text-base font-bold text-white">
          Reload the app screen
        </button>
      </div>
    );
  }
}

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <Guard>
      <App />
    </Guard>
  </StrictMode>,
);
