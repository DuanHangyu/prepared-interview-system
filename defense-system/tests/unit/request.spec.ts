import { AxiosError, type AxiosResponse, type InternalAxiosRequestConfig } from "axios";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

const mocks = vi.hoisted(() => ({
  messageError: vi.fn(),
  notificationError: vi.fn(),
  modalConfirm: vi.fn(),
  logout: vi.fn(),
}));

vi.mock("@/stores/auth", () => ({
  useAuthStore: () => ({ logout: mocks.logout }),
}));

vi.mock("ant-design-vue", () => ({
  message: { error: mocks.messageError },
  notification: { error: mocks.notificationError },
  Modal: { confirm: mocks.modalConfirm },
}));

import {
  apiFailureDetails,
  isRelogin,
  isLoginRequest,
  shouldRetryDatabaseFailure,
  default as request,
  type CallerOwnedRequestConfig,
} from "@/utils/request";

describe("request HTTP error contract", () => {
  const originalAdapter = request.defaults.adapter;

  beforeEach(() => {
    mocks.messageError.mockReset();
    mocks.notificationError.mockReset();
    mocks.modalConfirm.mockReset();
    mocks.logout.mockReset();
    isRelogin.show = false;
  });

  afterEach(() => {
    request.defaults.adapter = originalAdapter;
    vi.useRealTimers();
  });
  it.each([401,403,409,503])('keeps caller-owned rejection %s without stale global dialogs or notifications',async status=>{
    request.defaults.adapter=async config=>response(config,{code:status,message:'原范围操作失败'},status);
    await expect(request({url:'/backend/prepared-reviews/21/retention-protection',method:'post',data:{idempotencyKey:'original'},callerOwnsFailure:true} as CallerOwnedRequestConfig)).rejects.toMatchObject({status,code:status,message:'原范围操作失败'});
    expect(mocks.messageError).not.toHaveBeenCalled();expect(mocks.notificationError).not.toHaveBeenCalled();expect(mocks.modalConfirm).not.toHaveBeenCalled();expect(isRelogin.show).toBe(false);
  });
  it('keeps caller-owned network uncertainty without a global stale-scope toast or fabricated rejection',async()=>{
    request.defaults.adapter=async config=>{throw new AxiosError('Network Error','ERR_NETWORK',config);};
    let caught:unknown;try{await request({url:'/backend/prepared-reviews/21/retention-protection',callerOwnsFailure:true} as CallerOwnedRequestConfig);}catch(cause){caught=cause;}
    expect(caught).toBeInstanceOf(Error);expect(caught).toMatchObject({message:'后端接口连接异常'});expect(caught).not.toHaveProperty('status');expect(mocks.messageError).not.toHaveBeenCalled();
  });
  it.each([
    ['ERR_NETWORK','Network Error','NETWORK'],
    ['ECONNABORTED','timeout of 10000ms exceeded','TIMEOUT'],
    ['ETIMEDOUT','request timed out','TIMEOUT'],
    ['ERR_CANCELED','canceled','CANCELLED'],
    ['ERR_BAD_OPTION','invalid setup','UNKNOWN'],
    [undefined,'Network Error','UNKNOWN'],
  ])('keeps transport failure %s distinct from cancellation, unknown errors and server identity rejection',async(code,message,kind)=>{
    request.defaults.adapter=async config=>{throw new AxiosError(message,code,config);};
    let caught:unknown;try{await request({url:'/backend/prepared-interviews/8/preflight-context',callerOwnsFailure:true} as CallerOwnedRequestConfig);}catch(cause){caught=cause;}
    expect(caught).toBeInstanceOf(Error);expect(caught).toMatchObject({name:'ApiTransportError',kind});
    expect(caught).not.toHaveProperty('status');expect(caught).not.toHaveProperty('code');
    expect(mocks.messageError).not.toHaveBeenCalled();expect(mocks.modalConfirm).not.toHaveBeenCalled();
  });
  it('leaves caller-owned recovery explicit and never automatically retransmits an original write after database failure',async()=>{
    vi.useFakeTimers();let calls=0;request.defaults.adapter=async config=>{calls++;throw new AxiosError('Service unavailable','ERR_BAD_RESPONSE',config,undefined,response(config,{code:500,message:'数据库连接失败，请检查本地数据库隧道或 DB_JDBC_URL 配置'},503));};
    const refused=expect(request({url:'/backend/prepared-reviews/21/retention-protection',method:'post',data:{idempotencyKey:'original'},callerOwnsFailure:true} as CallerOwnedRequestConfig)).rejects.toMatchObject({status:503,code:500});await vi.advanceTimersByTimeAsync(4000);await refused;
    expect(calls).toBe(1);expect(mocks.messageError).not.toHaveBeenCalled();
  });
  it('retains a definitive HTTP conflict separately from an ambiguous network error',async()=>{
    request.defaults.adapter=async config=>response(config,{code:409,message:'评阅版本已变化'},409);
    let caught:unknown;try{await request.post('/backend/prepared-reviews/21/confirm',{expectedRevision:2});}catch(cause){caught=cause;}
    expect(caught).toBeInstanceOf(Error);expect(caught).toMatchObject({status:409,code:409,message:'评阅版本已变化'});
  });

  it("prefers the structured backend envelope for non-2xx responses", () => {
    expect(
      apiFailureDetails({
        response: {
          status: 413,
          data: { code: 140, message: "文件大小超过允许限制" },
        },
        message: "Request failed with status code 413",
      })
    ).toEqual({
      status: 413,
      code: 140,
      message: "文件大小超过允许限制",
    });
  });

  it("uses the HTTP status when an upstream failure has no envelope", () => {
    expect(
      apiFailureDetails({
        response: { status: 403, data: "" },
        message: "Request failed with status code 403",
      })
    ).toEqual({
      status: 403,
      code: 403,
      message: "当前操作没有权限",
    });
  });

  it("retries only the bounded database-unavailable contract", () => {
    const databaseFailure = {
      status: 503,
      code: 500,
      message: "数据库连接失败，请检查本地数据库隧道或 DB_JDBC_URL 配置",
    };

    expect(shouldRetryDatabaseFailure(databaseFailure, 0)).toBe(true);
    expect(shouldRetryDatabaseFailure(databaseFailure, 1)).toBe(true);
    expect(shouldRetryDatabaseFailure(databaseFailure, 2)).toBe(false);
    expect(
      shouldRetryDatabaseFailure({ ...databaseFailure, status: 500 }, 0)
    ).toBe(false);
  });

  it("distinguishes invalid login credentials from an expired session", () => {
    expect(isLoginRequest({ url: "/login" })).toBe(true);
    expect(isLoginRequest({ url: "/login?source=expired" })).toBe(true);
    expect(isLoginRequest({ url: "/user-detail" })).toBe(false);
  });

  it("uses browser cookies, removes only blank fields and unwraps success envelopes", async () => {
    let capturedConfig: InternalAxiosRequestConfig | undefined;
    request.defaults.adapter = async (config) => {
      capturedConfig = config;
      return response(config, {
        code: 200,
        data: { accepted: true },
      });
    };

    const result = await request({
      url: "/front/test",
      method: "post",
      data: { blank: "", missing: null, zero: 0, disabled: false, keep: "yes" },
    });

    expect(result).toEqual({ code: 200, data: { accepted: true } });
    expect(capturedConfig?.headers.Authorization).toBeUndefined();
    expect(capturedConfig?.withCredentials).toBe(true);
    expect(JSON.parse(String(capturedConfig?.data))).toEqual({
      zero: 0,
      disabled: false,
      keep: "yes",
    });
  });

  it("shows login failures inline without opening the expired-session modal", async () => {
    request.defaults.adapter = async (config) =>
      response(config, { code: 401, message: "账号或密码错误", data: null }, 401);

    await expect(
      request.post("/login", { account: "student", password: "bad" })
    ).rejects.toThrow("账号或密码错误");

    expect(mocks.messageError).toHaveBeenCalledWith({
      duration: 3,
      content: "账号或密码错误",
    });
    expect(mocks.modalConfirm).not.toHaveBeenCalled();
  });

  it("opens only one relogin dialog for concurrent expired-session responses", async () => {
    request.defaults.adapter = async (config) =>
      response(config, { code: 401, message: "登录已过期", data: null }, 401);

    await Promise.allSettled([
      request.get("/user-detail"),
      request.get("/front/student-assessment/statistic"),
    ]);

    expect(mocks.modalConfirm).toHaveBeenCalledOnce();
    expect(isRelogin.show).toBe(true);
  });

  it("retries the database-unavailable response and resolves after recovery", async () => {
    vi.useFakeTimers();
    let attempts = 0;
    request.defaults.adapter = async (config) => {
      attempts += 1;
      if (attempts === 1) {
        throw new AxiosError(
          "Service unavailable",
          "ERR_BAD_RESPONSE",
          config,
          undefined,
          response(
            config,
            {
              code: 500,
              message:
                "数据库连接失败，请检查本地数据库隧道或 DB_JDBC_URL 配置",
            },
            503
          )
        );
      }
      return response(config, { code: 200, data: { recovered: true } });
    };

    const pending = request.get("/front/student-assessment/statistic");
    await vi.advanceTimersByTimeAsync(1_500);

    await expect(pending).resolves.toEqual({
      code: 200,
      data: { recovered: true },
    });
    expect(attempts).toBe(2);
  });

  it("translates a network failure into the user-facing connectivity message", async () => {
    request.defaults.adapter = async (config) => {
      throw new AxiosError("Network Error", "ERR_NETWORK", config);
    };

    await expect(request.get("/user-detail")).rejects.toThrow("后端接口连接异常");
    expect(mocks.messageError).toHaveBeenCalledWith({
      duration: 3,
      content: "后端接口连接异常",
    });
  });

  it("extracts JSON envelopes returned through blob download endpoints", async () => {
    request.defaults.adapter = async (config) =>
      response(
        config,
        new Blob(
          [JSON.stringify({ code: 403, message: "无权导出该记录" })],
          { type: "application/json" }
        ),
        403
      );

    await expect(
      request.get("/backend/study-record/export-record", {
        responseType: "blob",
      })
    ).rejects.toThrow("无权导出该记录");
    expect(mocks.notificationError).toHaveBeenCalledWith({
      message: "无权导出该记录",
    });
  });
});

function response(
  config: InternalAxiosRequestConfig,
  data: unknown,
  status = 200
): AxiosResponse {
  return {
    data,
    status,
    statusText: status === 200 ? "OK" : "ERROR",
    headers: {},
    config,
  };
}
