import react from "@vitejs/plugin-react";
import { defineConfig } from "vite";
import svgr from "vite-plugin-svgr";

// https://vite.dev/config/
export default defineConfig({
  resolve: {
    alias: {
      "@": "/src",
    },
  },
  plugins: [
    react(),
    svgr({
      svgrOptions: {
        icon: true,
        // This will transform your SVG to a React component
        exportType: "named",
        namedExport: "ReactComponent",
      },
    }),
  ],
  // 개발 중 /api는 백엔드(8080)로 넘긴다. 같은 출처가 되어 CORS 설정이 필요 없다 (07 1장)
  server: {
    proxy: {
      "/api": "http://localhost:8080",
    },
  },
});
