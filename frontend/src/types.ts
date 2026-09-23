export type Json =
  null | boolean | number | string | Json[] | { [key: string]: Json };
export type Row = Record<string, any>;
export type CandidateStatus =
  | "NEW"
  | "ENRICHED"
  | "PENDING_REVIEW"
  | "VERIFIED"
  | "DUPLICATE"
  | "MERGED"
  | "REJECTED"
  | "EXPIRED";
export interface EvidenceLink {
  evidenceId: string;
  supportType: string;
}
export interface Claim {
  claimType: string;
  content: string;
  originType: string;
  derivationMethod?: string;
  evidence?: EvidenceLink[];
}
export interface Draft {
  title: string;
  summary: string;
  problem?: string;
  decision?: string;
  action?: string;
  outcomeSummary?: string;
  lesson: string;
  validFrom: string;
  validTo?: string | null;
  applicability?: Row;
  constraints?: Row;
  claims: Claim[];
  contextRefs?: { refType: string; refValue: string; sourceSystem?: string }[];
  episodes?: { episodeId: string; relationType: string }[];
}
export interface Candidate {
  id: string;
  status: CandidateStatus;
  revision: number;
  raw_content: string;
  candidate_type: string;
  source_type: string;
  source_system?: string;
  source_ref?: string;
  created_at: string;
  episode_id?: string;
  target_version_id?: string;
  extracted_json: Row;
  processing_status: string;
  processing_error?: string;
}
export interface Experience {
  experienceId: string;
  versionId: string;
  versionNo: number;
  title: string;
  summary: string;
  lesson: string;
  problem: string;
  decision: string;
  action: string;
  outcomeSummary: string;
  status: string;
  validFrom: string;
  validTo?: string;
  recordedAt: string;
  invalidatedAt?: string;
  supersedesId?: string;
  applicability: Row;
  constraints: Row;
  keyClaims: Row[];
  keyEvidence: Row[];
  contextRefs: Row[];
  episodes: Row[];
  usageStats: Row;
  outcomeStats: Row;
  confidenceSummary: Row;
  contradictionWarnings: Row[];
  mergedCandidates: Row[];
  score?: number;
  whyMatched?: Row;
}
export interface SearchResult {
  mode: string;
  results: Experience[];
  candidateCount: number;
  candidatePoolPerLane: number;
  validAt: string;
  knownAt: string;
}
export interface AuthoringDraftContent {
  title: string;
  summary: string;
  problem: string;
  context: string;
  rootCause: string;
  decision: string;
  actions: string[];
  outcome: string;
  lesson: string;
  reusablePrinciple: string;
  applicability: string[];
  boundaryConditions: string[];
  constraints: string[];
  alternatives: string[];
  tradeoffs: string[];
  claims: Row[];
  evidenceMappings: Row[];
  missingInformation: { field: string; message: string }[];
  possibleCounterExamples: string[];
  tags: string[];
  domain: string;
  taskType: string;
  confidence: Row;
}
export interface AuthoringDraft {
  id: string;
  candidateId: string;
  draftVersion: number;
  status: string;
  provider: string;
  model: string;
  promptCode: string;
  promptVersion: number;
  revisionSource: string;
  revisionInstruction?: string;
  errorSummary?: string;
  inputTokens?: number;
  outputTokens?: number;
  estimatedCost?: number;
  createdAt: string;
  missingCount: number;
  structuredContent: AuthoringDraftContent;
  candidate?: Candidate;
  history?: Row[];
  similar?: Row[];
  diff?: Row[];
}
export interface ReviewInboxItem {
  id: string;
  candidateId: string;
  draftVersion: number;
  status: string;
  title: string;
  summary: string;
  domain: string;
  taskType: string;
  confidence: number;
  missingCount: number;
  captureChannel: string;
  agentRole?: string;
  sourceType: string;
  sourceRef?: string;
  provider: string;
  model: string;
  error?: string;
  createdAt: string;
}
export const experienceTypes = [
  "BEST_PRACTICE",
  "PROBLEM_SOLUTION",
  "FAILURE",
  "DECISION",
  "WORKAROUND",
  "OPTIMIZATION",
  "WARNING",
  "INVESTIGATION",
];
export const candidateStatuses: CandidateStatus[] = [
  "NEW",
  "ENRICHED",
  "PENDING_REVIEW",
  "VERIFIED",
  "DUPLICATE",
  "MERGED",
  "REJECTED",
  "EXPIRED",
];
export const evidenceTypes = [
  "MES_RECORD",
  "ERP_RECORD",
  "EMS_RECORD",
  "SQL_RESULT",
  "TEST_RESULT",
  "DOCUMENT",
  "CHAT",
  "TICKET",
  "GIT_COMMIT",
  "PULL_REQUEST",
  "CODE",
  "USER_NOTE",
  "ENGINEER_CONFIRMATION",
  "AGENT_OBSERVATION",
  "AGENT_TOOL_RESULT",
  "EXTERNAL_SOURCE",
];
export const claimTypes = [
  "OBSERVATION",
  "RULE",
  "LESSON",
  "RECOMMENDATION",
  "CONSTRAINT",
  "CAUSAL_HYPOTHESIS",
  "WARNING",
];
export const originTypes = [
  "OBSERVED",
  "HUMAN_ASSERTED",
  "AGENT_DERIVED",
  "SYSTEM_DERIVED",
];
export const outcomeTypes = [
  "SUCCESS",
  "PARTIAL_SUCCESS",
  "FAILURE",
  "INCONCLUSIVE",
];
export const relationTypes = [
  "SIMILAR_TO",
  "SUPPORTS",
  "CONTRADICTS",
  "DERIVED_FROM",
  "CAUSED_BY",
  "RELATED_TO",
];
