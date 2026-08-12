import { defineConfig } from "vite";

// The Tauri webview loads the built files from disk, so relative asset URLs keep
// working no matter what origin the webview serves them from.
export default defineConfig({
  base: "./",
  clearScreen: false,
  build: {
    outDir: "dist",
    emptyOutDir: true,
    // macOS WKWebView. Anything newer than this is safe here.
    target: "safari15",
    assetsInlineLimit: 0,
  },
  server: {
    port: 1420,
    strictPort: true,
  },
});
