import type { RouteStep, StepKind } from "../../api/work.ts";

// AI 업무지원 결재 경로 칸 (REQ-C07 결재 경로, slices/21 8장 route). 서버 줄 칸 steps[{position, kind}] ↔ 화면 단계 줄.
// 화면(React)과 떨어진 순수 함수라 node 시험(scripts/assist-admin.test.ts)으로 확인한다

export const ROUTE_POSITIONS = ["SEC_LEAD", "CO_CMDR", "BN_CMDR"];
const KINDS: StepKind[] = ["REVIEW", "APPROVE", "FULL", "PROXY_FULL", "PROXY", "VACANT"];
const MAX_STEPS = 10;

/** 단계 줄 → 서버 칸 모양 */
export const routeStepValues = (steps: readonly RouteStep[]) => steps.map((s) => ({ position: s.position, kind: s.kind }));

/** 받은 단계 줄 → 화면 단계 줄(stepOrder는 차례 + 1). 직위·구분이 틀린 줄은 뺀다. 마지막 단계 구분 맞추기는 화면(normalize)이 한다 */
export function routeStepsOf(raw: unknown): RouteStep[] | null {
  if (!Array.isArray(raw)) return null;
  return raw
    .map((r) => (typeof r === "object" && r !== null ? (r as Record<string, unknown>) : {}))
    .filter((r) => typeof r.position === "string" && ROUTE_POSITIONS.includes(r.position) && typeof r.kind === "string" && (KINDS as string[]).includes(r.kind))
    .slice(0, MAX_STEPS)
    .map((r, i) => ({ stepOrder: i + 1, position: r.position as string, kind: r.kind as StepKind }));
}
