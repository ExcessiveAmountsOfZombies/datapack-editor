import { defineConfig } from "vite";
import preact from "@preact/preset-vite";
import tailwindcss from "@tailwindcss/vite";

export default defineConfig({
  base: "./",
  plugins: [preact(), tailwindcss()],
  build: {
    outDir: "../common/src/main/resources/epherolib/editor",
    emptyOutDir: true,
    sourcemap: false,
    target: "es2022"
  }
});
