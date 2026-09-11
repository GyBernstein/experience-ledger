import { useEffect, useRef, useState, type ReactNode } from "react";
import { AlertCircle, Inbox, LoaderCircle, CheckCircle2 } from "lucide-react";
import { ApiError } from "../api";
import { pretty } from "../domain";
export const labels: Record<string, string> = {
  NEW: "新采集",
  ENRICHED: "已增强",
  PENDING_REVIEW: "待审核",
  VERIFIED: "已验证",
  DUPLICATE: "重复",
  MERGED: "已合并",
  REJECTED: "已拒绝",
  EXPIRED: "已过期",
  SUPERSEDED: "已替代",
  INVALIDATED: "已撤销",
  READY: "就绪",
  PENDING: "等待处理",
  RUNNING: "处理中",
  FAILED: "处理失败",
  OBSERVED: "直接观察",
  HUMAN_ASSERTED: "人工陈述",
  AGENT_DERIVED: "Agent 推导",
  SYSTEM_DERIVED: "系统推导",
  SUCCESS: "成功",
  PARTIAL_SUCCESS: "部分成功",
  FAILURE: "失败",
  INCONCLUSIVE: "尚无结论",
};
Object.assign(labels, {
  ACTIVE: "有效",
  BEST_PRACTICE: "最佳实践",
  PROBLEM_SOLUTION: "问题解决",
  DECISION: "决策",
  WORKAROUND: "临时方案",
  OPTIMIZATION: "优化",
  WARNING: "警示",
  INVESTIGATION: "调查",
  OBSERVATION: "观察",
  RULE: "规则",
  LESSON: "经验结论",
  RECOMMENDATION: "建议",
  CONSTRAINT: "约束",
  CAUSAL_HYPOTHESIS: "因果假设",
  USER_NOTE: "用户记录",
  ENGINEER_CONFIRMATION: "工程师确认",
  TEST_RESULT: "测试结果",
  DOCUMENT: "文档",
  CHAT: "对话",
  TICKET: "工单",
  MES_RECORD: "MES 记录",
  ERP_RECORD: "ERP 记录",
  EMS_RECORD: "EMS 记录",
  SQL_RESULT: "查询结果",
  GIT_COMMIT: "代码提交",
  PULL_REQUEST: "合并请求",
  CODE: "代码",
  AGENT_OBSERVATION: "Agent 观察",
  AGENT_TOOL_RESULT: "Agent 工具结果",
  EXTERNAL_SOURCE: "外部来源",
  SUPPORTS: "支持",
  CONTRADICTS: "矛盾",
  CONTEXT: "上下文",
  SIMILAR_TO: "相似",
  DERIVED_FROM: "推导自",
  CAUSED_BY: "由其导致",
  RELATED_TO: "相关",
});
export function Badge({ value }: { value: string }) {
  return (
    <span className={`badge b-${value.toLowerCase()}`}>
      {labels[value] || value}
    </span>
  );
}
export function Field({
  label,
  children,
  hint,
}: {
  label: string;
  children: ReactNode;
  hint?: string;
}) {
  return (
    <label className="field">
      <span>{label}</span>
      {children}
      {hint && <small>{hint}</small>}
    </label>
  );
}
export function SelectOptions({
  values,
  blank,
}: {
  values: string[];
  blank?: string;
}) {
  return (
    <>
      {blank !== undefined && <option value="">{blank}</option>}
      {values.map((x) => (
        <option key={x} value={x}>
          {labels[x] ? `${labels[x]} · ${x}` : x}
        </option>
      ))}
    </>
  );
}
export function JsonView({
  value,
  title = "详细数据",
}: {
  value: unknown;
  title?: string;
}) {
  return (
    <details className="json">
      <summary>{title}</summary>
      <pre>{pretty(value)}</pre>
    </details>
  );
}
export function Empty({
  title = "暂无记录",
  children,
}: {
  title?: string;
  children?: ReactNode;
}) {
  return (
    <div className="empty">
      <Inbox size={34} />
      <h3>{title}</h3>
      {children && <p>{children}</p>}
    </div>
  );
}
export function ErrorBox({ error }: { error: unknown }) {
  if (!error) return null;
  const e = error as Error;
  return (
    <div className="notice error" role="alert">
      <AlertCircle size={19} />
      <div>
        <strong>{e.message || "操作失败"}</strong>
        {error instanceof ApiError && (
          <>
            <p>
              {error.code}
              {error.traceId && ` · 追踪号 ${error.traceId}`}
            </p>
            {error.uncertain && (
              <p>
                提交结果尚不确定。请先查询记录或审计；系统不会自动重试写入，避免重复记录。
              </p>
            )}
          </>
        )}
      </div>
    </div>
  );
}
export function Success({ children }: { children?: ReactNode }) {
  return children ? (
    <div className="notice success" role="status">
      <CheckCircle2 size={18} />
      <span>{children}</span>
    </div>
  ) : null;
}
export function Loading() {
  return (
    <div className="loading" role="status">
      <LoaderCircle className="spin" size={20} />
      正在读取…
    </div>
  );
}
export function PageTitle({
  eyebrow,
  title,
  children,
  actions,
}: {
  eyebrow: string;
  title: string;
  children?: ReactNode;
  actions?: ReactNode;
}) {
  return (
    <div className="page-title">
      <div>
        <div className="eyebrow">{eyebrow}</div>
        <h1>{title}</h1>
        {children && <p>{children}</p>}
      </div>
      {actions && <div className="actions">{actions}</div>}
    </div>
  );
}
export function useAction() {
  const [busy, setBusy] = useState(false),
    [error, setError] = useState<unknown>(null),
    [message, setMessage] = useState("");
  const locked = useRef(false),
    alive = useRef(true);
  useEffect(() => {
    alive.current = true;
    return () => {
      alive.current = false;
    };
  }, []);
  async function run(action: () => Promise<void>) {
    if (locked.current) return;
    locked.current = true;
    setBusy(true);
    setError(null);
    setMessage("");
    try {
      await action();
    } catch (e) {
      if (alive.current) setError(e);
    } finally {
      locked.current = false;
      if (alive.current) setBusy(false);
    }
  }
  return { busy, error, message, run, setMessage, setError };
}
export function useLoad<T>(
  loader: (signal: AbortSignal) => Promise<T>,
  dependencies: unknown[],
) {
  const [data, setData] = useState<T>(),
    [error, setError] = useState<unknown>(null),
    [loading, setLoading] = useState(true),
    [tick, setTick] = useState(0);
  useEffect(() => {
    const c = new AbortController();
    setLoading(true);
    setError(null);
    setData(undefined);
    Promise.resolve()
      .then(() => loader(c.signal))
      .then((v) => {
        if (!c.signal.aborted) setData(v);
      })
      .catch((e) => {
        if (!c.signal.aborted) setError(e);
      })
      .finally(() => {
        if (!c.signal.aborted) setLoading(false);
      });
    return () => c.abort();
  }, [...dependencies, tick]);
  return { data, error, loading, reload: () => setTick((t) => t + 1) };
}
export function date(value?: string | null) {
  if (!value) return "—";
  return new Date(value).toLocaleString("zh-CN", { hour12: false });
}
export function short(value: string) {
  return value.slice(0, 8);
}
export function go(path: string) {
  window.location.hash = path;
}
export function Id({ value }: { value?: string }) {
  return value ? (
    <code className="id" title={value}>
      {value}
    </code>
  ) : (
    <span>—</span>
  );
}

export const navigationGuard = { dirty: false };
