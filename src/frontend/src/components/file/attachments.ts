import type { Attachment } from "@/api/files";

// 첨부파일 부품의 보조 함수·형 (slices/11 2.3.3)

/** 파일 크기 글: "512 B", "12.3 KB", "4.5 MB" */
export function formatSize(bytes: number): string {
  if (bytes < 1024) return `${bytes} B`;
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`;
  return `${(bytes / 1024 / 1024).toFixed(1)} MB`;
}

/** 고른 파일 한 줄: 올리는 중·올림(id)·실패(이유). temp: 이번에 올린 임시 파일(빼면 서버에서도 지움) */
export interface AttachmentItem {
  key: string;
  name: string;
  size: number;
  state: "uploading" | "done" | "failed";
  id?: number;
  temp: boolean;
  error?: string;
}

/** 이미 묶인 첨부로 시작한다(다시 올리기) */
export const fromAttachments = (list: Attachment[]): AttachmentItem[] =>
  list.map((a) => ({ key: `f-${a.id}`, name: a.name, size: a.size, state: "done", id: a.id, temp: false }));

/** 보낼 첨부 id (올린 것만, 고른 순서대로) */
export const attachmentIds = (items: AttachmentItem[]) => items.flatMap((i) => (i.state === "done" && i.id !== undefined ? [i.id] : []));

export const attachmentsBusy = (items: AttachmentItem[]) => items.some((i) => i.state === "uploading");
