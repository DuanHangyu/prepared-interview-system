import { fileURLToPath, URL } from "node:url";
import tailwindcss from "@tailwindcss/vite";
import vue from "@vitejs/plugin-vue";
import { defineConfig, loadEnv } from "vite";

export default defineConfig(({ mode }) => {
  const environment = loadEnv(mode, process.cwd(), "VUE_APP_");
  const port = Number(process.env.PREPARED_FRONTEND_PORT || process.env.PORT || 13015);

  return {
    plugins: [tailwindcss(), vue()],
    envPrefix: "VUE_APP_",
    define: {
      __VUE_OPTIONS_API__: true,
      __VUE_PROD_DEVTOOLS__: false,
      __VUE_PROD_HYDRATION_MISMATCH_DETAILS__: false,
    },
    resolve: {
      alias: {
        "@": fileURLToPath(new URL("./src", import.meta.url)),
      },
    },
    server: {
      host: "0.0.0.0",
      port,
      proxy: {
        "/dev-api": {
          target:
            process.env.VUE_APP_PROXY_TARGET ||
            environment.VUE_APP_PROXY_TARGET ||
            "http://127.0.0.1:13014/",
          changeOrigin: true,
          rewrite: (path) => path.replace(/^\/dev-api/, ""),
        },
      },
    },
    build: {
      sourcemap: false,
      target: "es2020",
      chunkSizeWarningLimit: 512,
    },
  };
});
