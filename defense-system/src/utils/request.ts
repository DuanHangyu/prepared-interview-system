import axios, {
  AxiosError,
  AxiosRequestConfig,
  AxiosResponse,
  InternalAxiosRequestConfig,
} from "axios";
import { notification, message, Modal } from "ant-design-vue";
import errorCode from "@/utils/errorCode";
import { useAuthStore } from "@/stores/auth";

type ApiPayload = {
  code?: number;
  message?: string;
  data?: unknown;
};

type RetryableConfig = InternalAxiosRequestConfig & {
  __dbRetryCount?: number;
};
/** UI caller must present errors against its original identity/resource and own
 * explicit recovery. This changes no HTTP status, authorization or acceptance. */
export type CallerOwnedRequestConfig = AxiosRequestConfig & { callerOwnsFailure?: boolean };
const callerOwnsFailure=(config?:AxiosRequestConfig)=>
  (config as CallerOwnedRequestConfig|undefined)?.callerOwnsFailure===true;

export type ApiFailure = {
  status?: number;
  code: number;
  message: string;
};
/** Preserve a definitive server rejection; a connectivity error must never masquerade
 * as an accepted/rejected command or authorize discarding an uncertain original. */
export class ApiRequestError extends Error {
  readonly status?:number;
  readonly code:number;
  constructor(failure:ApiFailure){super(failure.message);this.name='ApiRequestError';this.status=failure.status;this.code=failure.code;}
}
/** A transport failure proves neither the current server identity nor acceptance
 * of a command. Recovery uses Axios' reason, never translated message matching. */
export class ApiTransportError extends Error {
  readonly kind:'NETWORK'|'TIMEOUT'|'CANCELLED'|'UNKNOWN';
  constructor(message:string,kind:ApiTransportError['kind']){super(message);this.name='ApiTransportError';this.kind=kind;}
}

export let isRelogin = { show: false };

const DATABASE_CONNECTION_ERROR =
  "数据库连接失败，请检查本地数据库隧道或 DB_JDBC_URL 配置";
const DB_RETRY_DELAY = 1500;
const DB_RETRY_LIMIT = 2;

function sleep(ms: number) {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

function fallbackMessage(code: number, fallback?: string) {
  const configured = errorCode[String(code) as keyof typeof errorCode];
  return configured || fallback || errorCode.default;
}

export function apiFailureDetails(error: {
  response?: { status?: number; data?: unknown };
  message?: string;
}): ApiFailure {
  const status = error.response?.status;
  const body =
    error.response?.data && typeof error.response.data === "object"
      ? (error.response.data as ApiPayload)
      : undefined;
  const code = typeof body?.code === "number" ? body.code : status || 500;

  return {
    status,
    code,
    message: body?.message || fallbackMessage(code, error.message),
  };
}

export function isLoginRequest(config?: Pick<AxiosRequestConfig, "url">) {
  const path = String(config?.url || "").split("?")[0].replace(/\/+$/, "");
  return path === "/login" || path.endsWith("/login");
}

export function shouldRetryDatabaseFailure(
  failure: ApiFailure,
  retryCount: number
) {
  return (
    failure.status === 503 &&
    failure.message === DATABASE_CONNECTION_ERROR &&
    retryCount < DB_RETRY_LIMIT
  );
}

const service = axios.create({
  baseURL: import.meta.env.VUE_APP_BASE_API,
  timeout: 30000,
  withCredentials: true,
  headers: {
    "Content-Type": "application/json;charset=utf-8",
  },
});

function retryDatabaseRequest(config: RetryableConfig | undefined) {
  if (!config) {
    return null;
  }
  const retryCount = config.__dbRetryCount || 0;
  if (retryCount >= DB_RETRY_LIMIT) {
    return null;
  }
  config.__dbRetryCount = retryCount + 1;
  return sleep(DB_RETRY_DELAY).then(() => service(config));
}

function showExpiredSession() {
  if (isRelogin.show) {
    return;
  }
  isRelogin.show = true;
  Modal.confirm({
    title: "系统提示",
    content: "登录状态已过期，您可以继续留在该页面，或者重新登录",
    okText: "重新登录",
    cancelText: "取消",
    type: "warning",
    onOk: () => {
      isRelogin.show = false;
      useAuthStore().logout(false);
      window.location.href = "/login";
    },
    onCancel: () => {
      isRelogin.show = false;
    },
  });
}

function rejectBusinessFailure(
  failure: ApiFailure,
  config?: AxiosRequestConfig
): never {
  if (callerOwnsFailure(config)) throw new ApiRequestError(failure);
  if (failure.status === 401 || failure.code === 401) {
    if (isLoginRequest(config)) {
      message.error({ duration: 3, content: failure.message });
    } else {
      showExpiredSession();
    }
    throw new ApiRequestError(failure);
  }

  if ([400, 409, 413, 429, 500, 503].includes(failure.status || failure.code)) {
    message.error({ duration: 3, content: failure.message });
  } else {
    notification.error({ message: failure.message });
  }
  throw new ApiRequestError(failure);
}

async function payloadFromBlob(data: unknown): Promise<ApiPayload | undefined> {
  if (!(data instanceof Blob) || !String(data.type).includes("application/json")) {
    return undefined;
  }
  try {
    return JSON.parse(await data.text()) as ApiPayload;
  } catch {
    return undefined;
  }
}

service.interceptors.request.use(
  (config) => {
    if (typeof config.data === "object" && config.data !== null) {
      for (const key in config.data) {
        if (Object.prototype.hasOwnProperty.call(config.data, key)) {
          const element = config.data[key];
          if (!element && typeof element !== "number" && element !== false) {
            delete config.data[key];
          }
        }
      }
    }
    return config;
  },
  (error) => Promise.reject(error)
);

service.interceptors.response.use(
  // Axios' interceptor declaration assumes responses retain AxiosResponse,
  // while this client intentionally unwraps JSON envelopes for all callers.
  async (res: AxiosResponse<ApiPayload | Blob>): Promise<any> => {
    const responseType = res.config.responseType;
    const isBinaryResponse =
      responseType === "blob" || responseType === "arraybuffer";
    if (isBinaryResponse) {
      const blobPayload = await payloadFromBlob(res.data);
      if (blobPayload && blobPayload.code !== 200) {
        const failure: ApiFailure = {
          status: res.status,
          code: blobPayload.code || res.status,
          message:
            blobPayload.message ||
            fallbackMessage(blobPayload.code || res.status),
        };
        return rejectBusinessFailure(failure, res.config);
      }
      return res;
    }

    const payload = res.data as ApiPayload;
    const code = typeof payload?.code === "number" ? payload.code : 200;
    if (code !== 200) {
      const failure: ApiFailure = {
        status: res.status,
        code,
        message: payload?.message || fallbackMessage(code),
      };
      return rejectBusinessFailure(failure, res.config);
    }
    return payload;
  },
  async (error: AxiosError<ApiPayload | Blob>) => {
    let normalizedError: AxiosError<ApiPayload> = error as AxiosError<ApiPayload>;
    const blobPayload = await payloadFromBlob(error.response?.data);
    if (blobPayload && error.response) {
      normalizedError = {
        ...error,
        response: { ...error.response, data: blobPayload },
      } as AxiosError<ApiPayload>;
    }

    if (!normalizedError.response) {
      let networkMessage = normalizedError.message;
      if (networkMessage === "Network Error") {
        networkMessage = "后端接口连接异常";
      } else if (networkMessage.includes("timeout")) {
        networkMessage = "系统接口请求超时";
      }
      if (!callerOwnsFailure(normalizedError.config)) {
        message.error({ duration: 3, content: networkMessage });
      }
      const kind:ApiTransportError['kind']=axios.isCancel(normalizedError)||normalizedError.code==='ERR_CANCELED'?'CANCELLED':
        normalizedError.code==='ERR_NETWORK'?'NETWORK':['ECONNABORTED','ETIMEDOUT'].includes(normalizedError.code??'')?'TIMEOUT':'UNKNOWN';
      return Promise.reject(new ApiTransportError(networkMessage,kind));
    }

    const failure = apiFailureDetails(normalizedError);
    const config = normalizedError.config as RetryableConfig | undefined;
    const retryCount = config?.__dbRetryCount || 0;
    if (!callerOwnsFailure(config) && shouldRetryDatabaseFailure(failure, retryCount)) {
      const retry = retryDatabaseRequest(config);
      if (retry) {
        return retry;
      }
    }
    return rejectBusinessFailure(failure, config);
  }
);

export default service;
