import tailwindcss from '@tailwindcss/vite';
import react from '@vitejs/plugin-react';
import { defineConfig } from 'vite';

// base "./" so the built files load from the app's own assets
export default defineConfig({
  base: './',
  plugins: [react(), tailwindcss()],
});
