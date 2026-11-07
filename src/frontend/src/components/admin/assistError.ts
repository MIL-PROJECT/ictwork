import type { AssistError } from "@/api/assist";
import { ApiError } from "@/api/client";

/**
 * 저장 오류 → AI 업무지원 lastError (REQ-C06, slices/21 12.6): 코드, 화면에 보인 문구, 업무지원 칸 id.
 * fields: 서버 오류 칸(ApiError.field) → 업무지원 칸 id. 모르는 칸은 null
 */
export function assistErrorOf(err: unknown, message: string, fields: Record<string, string> = {}): AssistError {
  const field = err instanceof ApiError ? (err.field ?? null) : null;
  return { code: err instanceof ApiError ? err.code : "UNKNOWN", message, field: field === null ? null : (fields[field] ?? null) };
}
