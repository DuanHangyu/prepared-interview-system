/// <reference types="vite/client" />

interface ImportMetaEnv {
  readonly VUE_APP_TITLE: string;
  readonly VUE_APP_BASE_API: string;
  readonly VUE_APP_ENV: string;
  readonly VUE_APP_BASE_TEXT_WS: string;
  readonly VUE_APP_BASE_VOICE_WS: string;
  readonly VUE_APP_BASE_PLAYVOICE_WS: string;
  // 其他自定义环境变量...
}

interface ImportMeta {
  readonly env: ImportMetaEnv;
}
