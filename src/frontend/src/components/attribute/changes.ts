import { attachmentIds, type AttachmentItem } from "@/components/file/attachments";
import { hasValue } from "@/components/workform/questions";

/**
 * 보낼 특성 값: 고친 것만(지운 값은 null). 고치지 않은 값은 보내지 않아, 정의에서 선택지를 빼거나 검증을 더해도 그 값은 그대로 남는다 (REQ-405·519).
 * files: 파일 특성의 올린 파일 목록(값은 올린 파일 id). before가 비면(등록) 값이 있는 것 모두
 */
export function changedAttributeValues(values: Record<string, unknown>, files: Record<string, AttachmentItem[]>, before: Record<string, unknown>): Record<string, unknown> {
  const current: Record<string, unknown> = { ...values };
  Object.entries(files).forEach(([k, items]) => {
    current[k] = attachmentIds(items);
  });
  const out: Record<string, unknown> = {};
  new Set([...Object.keys(current), ...Object.keys(before)]).forEach((k) => {
    const now = hasValue(current[k]) ? current[k] : null;
    const was = hasValue(before[k]) ? before[k] : null;
    if (JSON.stringify(now) !== JSON.stringify(was)) out[k] = now;
  });
  return out;
}

/** 파일 특성의 고르기 상태가 올리는 중인가 */
export const filesBusy = (files: Record<string, AttachmentItem[]>) => Object.values(files).some((list) => list.some((i) => i.state === "uploading"));
