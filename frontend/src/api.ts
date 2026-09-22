export class ApiError extends Error {
  constructor(
    public status: number,
    public code: string,
    message: string,
    public traceId = "",
    public uncertain = false,
  ) {
    super(message);
    this.name = "ApiError";
  }
}
export const errorText: Record<string, string> = {
  UNAUTHORIZED: "凭证无效或已失效，请重新连接。",
  FORBIDDEN:
    "当前身份无权执行此操作。审核与发布需要 HUMAN 或 TRUSTED_WORKFLOW 身份。",
  CANDIDATE_REVISION_CONFLICT:
    "候选已被更新。请先保留你的草稿，重新加载候选并核对，再保存。",
  VERSION_CONFLICT: "经验版本链已变化。请重新查看最新版本后再决定如何合并。",
  CANDIDATE_STATE_CONFLICT: "候选状态已变化，当前操作不可执行。请刷新候选。",
  DRAFT_REVISION_CONFLICT: "草稿已产生新版本，请重新加载后再操作。",
  DRAFT_SCHEMA_INVALID: "AI 返回的草稿结构不完整，请重新生成或手工修订。",
  LLM_NOT_CONFIGURED: "尚未配置 LLM，当前只能使用保守的本地草稿。",
  LLM_INVOCATION_FAILED: "LLM 调用失败，原始记录已保留，可稍后重新生成。",
  CONSTRAINT_VIOLATION:
    "提交内容与数据约束冲突，请检查关联 ID、状态和必填字段。",
  NOT_FOUND: "当前空间内找不到该记录。",
};
export class LedgerApi {
  constructor(
    private readonly token: string,
    private readonly transport: typeof fetch = fetch,
    private readonly timeoutMs = 30000,
  ) {}
  request<T>(path: string, body?: unknown, signal?: AbortSignal): Promise<T> {
    return this.send<T>("v1", path, body, signal);
  }
  v2<T>(path: string, body?: unknown, signal?: AbortSignal): Promise<T> {
    return this.send<T>("v2", path, body, signal);
  }
  private async send<T>(
    version: "v1" | "v2",
    path: string,
    body?: unknown,
    signal?: AbortSignal,
  ): Promise<T> {
    const mutating =
      body !== undefined &&
      !/^\/experiences\/(search|[^/]+\/similar)$/.test(path);
    const controller = new AbortController();
    const cancel = () => controller.abort();
    signal?.addEventListener("abort", cancel, { once: true });
    if (signal?.aborted) controller.abort();
    const timer = setTimeout(cancel, this.timeoutMs);
    try {
      // Native fetch brand-checks its receiver: calling the stored reference as a
      // method (`this.transport(...)`) throws Illegal invocation before the request
      // is dispatched. Invoke it with the global receiver instead.
      const response = await this.transport.call(globalThis, `/api/${version}${path}`, {
        method: body === undefined ? "GET" : "POST",
        headers: {
          Authorization: `Bearer ${this.token}`,
          ...(body === undefined ? {} : { "Content-Type": "application/json" }),
        },
        body: body === undefined ? undefined : JSON.stringify(body),
        signal: controller.signal,
        cache: "no-store",
        credentials: "omit",
        redirect: "error",
      });
      const raw = await response.text();
      let data: any;
      try {
        data = raw ? JSON.parse(raw) : null;
      } catch {
        throw new ApiError(
          response.status,
          "INVALID_RESPONSE",
          "服务返回了非 JSON 内容，请检查 API 代理地址。",
          "",
          mutating,
        );
      }
      if (!response.ok) {
        const code =
          data?.code ||
          (response.status === 401
            ? "UNAUTHORIZED"
            : response.status === 403
              ? "FORBIDDEN"
              : `HTTP_${response.status}`);
        throw new ApiError(
          response.status,
          code,
          errorText[code] || data?.message || "请求失败",
          data?.traceId || response.headers.get("X-Trace-Id") || "",
          mutating && response.status >= 500,
        );
      }
      return data as T;
    } catch (error) {
      if (error instanceof ApiError) throw error;
      if (signal?.aborted) throw error;
      throw new ApiError(
        0,
        "NETWORK_ERROR",
        "请求中断或超时，请检查后端连接。",
        "",
        mutating,
      );
    } finally {
      clearTimeout(timer);
      signal?.removeEventListener("abort", cancel);
    }
  }
}
export function query(
  params: Record<string, string | number | undefined>,
): string {
  const search = new URLSearchParams();
  for (const [key, value] of Object.entries(params))
    if (value !== undefined && value !== "") search.set(key, String(value));
  return search.size ? `?${search}` : "";
}
