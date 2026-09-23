import {
  useEffect,
  useMemo,
  useRef,
  useState,
  Component,
  type ReactNode,
} from "react";
import {
  BookOpen,
  Search,
  Plus,
  ClipboardCheck,
  Fingerprint,
  Repeat2,
  ScrollText,
  LogOut,
  ArrowUpRight,
  PanelLeftClose,
  PanelLeftOpen,
  KeyRound,
} from "lucide-react";
import { LedgerApi } from "./api";
import { ErrorBox, Field, useAction, navigationGuard } from "./components/ui";
import { SearchPage, ExperiencePage } from "./pages/Experiences";
import { CapturePageV12, ReviewInboxPage, DraftReviewPage } from "./pages/Authoring";
import { EvidencePage, UsagePage, AuditPage } from "./pages/Records";
import {
  PolicyPage,
  CompactPage,
  ContextPage,
  OperationsPage,
} from "./pages/AgentContext";
const navigation = [
  ["/context", "上下文供给", Search],
  ["/compacts", "知识压缩", BookOpen],
  ["/policies", "检索策略", ClipboardCheck],
  ["/operations", "Agent 运营", ScrollText],
  ["/search", "经验检索", Search],
  ["/capture", "AI 采集", Plus],
  ["/review", "审核工作箱", ClipboardCheck],
  ["/evidence", "证据记录", Fingerprint],
  ["/usage", "使用与反馈", Repeat2],
  ["/audit", "审计日志", ScrollText],
] as const;
class Boundary extends Component<{ children: ReactNode }, { failed: boolean }> {
  state = { failed: false };
  static getDerivedStateFromError() {
    return { failed: true };
  }
  render() {
    return this.state.failed ? (
      <div className="panel">
        <h2>页面无法显示</h2>
        <p>可能收到不兼容的响应，请检查后端版本并重新加载。</p>
        <button onClick={() => window.location.reload()}>重新加载</button>
      </div>
    ) : (
      this.props.children
    );
  }
}
export default function App() {
  const [token, setToken] = useState(""),
    [input, setInput] = useState(""),
    [location, setLocation] = useState(
      window.location.hash.slice(1) || "/search",
    ),
    [collapsed, setCollapsed] = useState(false);
  const api = useMemo(() => new LedgerApi(token), [token]);
  const connect = useAction();
  const currentLocation = useRef(location);
  useEffect(() => {
    const f = () => {
      const next = window.location.hash.slice(1) || "/search";
      if (next === currentLocation.current) return;
      if (
        navigationGuard.dirty &&
        !window.confirm("当前审核草稿尚未保存。确认离开并丢弃修改？")
      ) {
        window.history.replaceState(null, "", `#${currentLocation.current}`);
        return;
      }
      currentLocation.current = next;
      setLocation(next);
    };
    window.addEventListener("hashchange", f);
    return () => window.removeEventListener("hashchange", f);
  }, []);
  const [path, search = ""] = location.split("?");
  const params = new URLSearchParams(search);
  const current =
    navigation.find(([prefix]) => path.startsWith(prefix)) || navigation[0];
  const logout = () => {
    if (
      !window.confirm("断开连接会清除当前凭证，并丢弃未保存的页面草稿。继续？")
    )
      return;
    setToken("");
    setInput("");
  };
  let page: ReactNode;
  if (path === "/context") page = <ContextPage api={api} />;
  else if (path === "/compacts") page = <CompactPage api={api} />;
  else if (path === "/policies") page = <PolicyPage api={api} />;
  else if (path === "/operations") page = <OperationsPage api={api} />;
  else if (path === "/search") page = <SearchPage api={api} />;
  else if (path.startsWith("/experiences/"))
    page = (
      <ExperiencePage
        api={api}
        id={path.split("/")[2]}
        initialVersion={params.get("version") || undefined}
      />
    );
  else if (path === "/capture") page = <CapturePageV12 api={api} />;
  else if (path.startsWith("/drafts/")) page = <DraftReviewPage api={api} id={path.split("/")[2]} />;
  else if (path === "/review") page = <ReviewInboxPage api={api} />;
  else if (path === "/evidence")
    page = <EvidencePage api={api} initialId={params.get("id") || ""} />;
  else if (path === "/usage")
    page = <UsagePage api={api} initialVersion={params.get("version") || ""} />;
  else if (path === "/audit")
    page = <AuditPage api={api} initialTarget={params.get("target") || ""} />;
  else
    page = (
      <div className="panel">
        <h1>页面不存在</h1>
        <a href="#/search">返回经验检索</a>
      </div>
    );
  return (
    <div className={`app ${collapsed ? "nav-collapsed" : ""}`}>
      <aside className="sidebar">
        <a className="brand" href="#/search">
          <span className="brand-mark">
            <img src="/experience-ledger-mark.svg" alt="" />
          </span>
          <span>
            Experience
            <br />
            <b>Ledger</b>
          </span>
        </a>
        <div className="sidebar-label">工作空间</div>
        <nav aria-label="主导航">
          {navigation.map(([href, label, Icon]) => (
            <a
              key={href}
              href={`#${href}`}
              className={
                path.startsWith(href) ||
                (href === "/search" && path.startsWith("/experiences"))
                  ? "active"
                  : ""
              }
              aria-current={path.startsWith(href) ? "page" : undefined}
            >
              <Icon size={19} />
              <span>{label}</span>
            </a>
          ))}
        </nav>
        <div className="sidebar-note">
          <span className="edition">V1.2 / AI-ASSISTED</span>
          <p>记录依据，保留演进。</p>
          <div>Frozen Specification</div>
        </div>
      </aside>
      <div className="workspace">
        <header className="topbar">
          <div className="topbar-left">
            <button
              className="icon-button nav-toggle"
              aria-label={collapsed ? "展开导航" : "折叠导航"}
              onClick={() => setCollapsed(!collapsed)}
            >
              {collapsed ? (
                <PanelLeftOpen size={20} />
              ) : (
                <PanelLeftClose size={20} />
              )}
            </button>
            <span>经验工作台</span>
            <span className="crumb">/</span>
            <b>{current[1]}</b>
          </div>
          <div className="topbar-right">
            <span className={`connection ${token ? "connected" : ""}`}>
              {token ? "已连接后端" : "未连接"}
            </span>
            {token && (
              <button className="text-button" onClick={logout}>
                <LogOut size={16} />
                断开
              </button>
            )}
          </div>
        </header>
        <main id="main" key={token ? "connected" : "disconnected"}>
          {!token ? (
            <div className="connection-layout">
              <section className="connection-intro">
                <div className="eyebrow">EXPERIENCE LEDGER</div>
                <h1>
                  让每一次经验
                  <br />
                  有据可循。
                </h1>
                <p>
                  从采集到验证，从复用到反馈。
                  <br />
                  在同一条记录中追溯结论的来处与变化。
                </p>
                <div className="flow-labels">
                  <span>01 采集</span>
                  <span>02 验证</span>
                  <span>03 复用</span>
                </div>
              </section>
              <form
                className="panel connection-form"
                onSubmit={(e) => {
                  e.preventDefault();
                  void connect.run(async () => {
                    const next = input.trim();
                    await new LedgerApi(next).request("/candidates?limit=1");
                    setToken(next);
                    setInput("");
                  });
                }}
              >
                <KeyRound className="accent" size={28} />
                <h2>连接你的工作空间</h2>
                <p>使用后端配置的访问凭证。身份与空间由后端确定。</p>
                <Field
                  label="访问凭证"
                  hint="凭证仅保留在页面内存中；刷新或断开后需重新输入。"
                >
                  <input
                    type="password"
                    value={input}
                    onChange={(e) => setInput(e.target.value)}
                    autoComplete="off"
                    required
                    minLength={16}
                    placeholder="输入 Bearer Token"
                  />
                </Field>
                <ErrorBox error={connect.error} />
                <button className="primary wide" disabled={connect.busy}>
                  {connect.busy ? "正在连接…" : "连接工作空间"}
                  <ArrowUpRight size={17} />
                </button>
                <p className="micro">
                  请先启动后端与代理。启动方式见压缩包中的 frontend/README.md。
                </p>
              </form>
            </div>
          ) : (
            <Boundary key={location}>
              <div key={location}>{page}</div>
            </Boundary>
          )}
        </main>
        <footer>
          Experience Ledger <span>·</span> 经验是可追溯的记录
        </footer>
      </div>
    </div>
  );
}
