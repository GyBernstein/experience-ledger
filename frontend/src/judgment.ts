export interface JudgmentRule {
  title: string;
  futureDecision: string;
  situation: string;
  questions: string[];
  judgment: string;
  hardConstraints: string[];
  tradeoff: string;
  verification: string;
  boundaries: string;
  reviseWhen: string;
  counterexample: string;
  authorConfidence: number;
  negative: boolean;
}
export interface RetentionGate {
  changesFutureDecision: boolean;
  reusable: boolean;
  readilyRecoverable: boolean;
  decisionImpact: string;
}
export const emptyRule: JudgmentRule = {
  title: "",
  futureDecision: "",
  situation: "",
  questions: [],
  judgment: "",
  hardConstraints: [],
  tradeoff: "",
  verification: "",
  boundaries: "",
  reviseWhen: "",
  counterexample: "",
  authorConfidence: 0.5,
  negative: false,
};
export const emptyGate: RetentionGate = {
  changesFutureDecision: false,
  reusable: false,
  readilyRecoverable: false,
  decisionImpact: "",
};
export function worthKeeping(g: RetentionGate) {
  return (
    g.changesFutureDecision &&
    g.reusable &&
    !g.readilyRecoverable &&
    g.decisionImpact.trim().length > 0
  );
}
export function lines(s: string) {
  return s
    .split("\n")
    .map((x) => x.trim())
    .filter(Boolean);
}
export const reuseLabels: Record<string, string> = {
  NATIVE_ONLY: "仅原生侧保留",
  CROSS_REFERENCE: "跨侧参考",
  CROSS_REUSABLE: "已验证跨侧复用",
  NATIVE: "原生经验",
};
export const ruleLabels: Record<string, string> = {
  futureDecision: "以后会改变什么决策",
  situation: "什么情况下会遇到这个问题",
  questions: "先问哪些问题",
  judgment: "判断规则",
  hardConstraints: "不能丢的约束",
  tradeoff: "多个方案如何权衡",
  verification: "怎样判断 AI 方案是否可靠",
  boundaries: "哪些情况下不能套用",
  reviseWhen: "出现什么信号需要重审",
  counterexample: "反例或试过但无效的方法",
};
