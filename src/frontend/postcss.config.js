import { fileURLToPath } from 'node:url'

export default {
  plugins: {
    // Scan this app's sources even when Vite is started from another folder (desktop/dev.cjs).
    '@tailwindcss/postcss': { base: fileURLToPath(new URL('.', import.meta.url)) },
  },
}
