import { apiFetch } from "./client";

export type AnalysisFeature = "SUMMARY" | "TRIAGE" | "PRIORITY" | "SIMILAR";
export interface AnalysisResult {
  feature: AnalysisFeature;
  summary: string;
  asOf: string;
  provider: string;
  model: string;
  ruleVersion: string;
  conditions: string[];
  metrics: { label: string; value: number }[];
  items: { id: number; title: string; status: string; priority: string; dueDate: string | null; overdue: boolean; href: string; reason: string }[];
  advice: { category: string; priority: string; rationale: string; recommendation: string; questions: string[] } | null;
  links: { label: string; href: string | null }[];
}

export function analyzeWork(feature: AnalysisFeature, input: { title: string; description: string; serviceImpact: string }, signal: AbortSignal) {
  return apiFetch<AnalysisResult>("/api/ai/analysis", { method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify({ feature, ...input }), signal: AbortSignal.any([signal, AbortSignal.timeout(30000)]) });
}
