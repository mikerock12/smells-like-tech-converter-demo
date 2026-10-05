import vinext from "vinext";
import { defineConfig } from "vite";
const headers = { "Cross-Origin-Opener-Policy": "same-origin", "Cross-Origin-Embedder-Policy": "require-corp", "Cross-Origin-Resource-Policy": "same-origin" };
export default defineConfig({ build: { target: "es2022" }, worker: { format: "es" }, server: { headers }, preview: { headers }, plugins: [vinext()] });
