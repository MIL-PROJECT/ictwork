import { apiFetch } from "./client";

// 첨부파일 (REQ-115·116, slices/11 2.3): 파일은 하나씩 먼저 올려 임시로 두었다가 요청을 보낼 때 묶는다(G-67). 종류 판정은 서버가 한다

// 먼저 올려 둘 수 있는 용도: 요청 첨부·양식 파일 답·지시사항 그림(PNG·JPG 10MB, G-147). 처리 건 첨부·안내문 그림은 올리는 순간 묶는다(api/work.ts)
export type FilePurpose = "REQUEST" | "FORM_ANSWER" | "DIRECTIVE_IMAGE";

export interface UploadedFile {
  id: number;
  name: string;
  size: number;
  extension: string;
}

export interface Attachment {
  id: number;
  name: string;
  size: number;
  uploadedByName: string;
  createdAt: string;
}

// 파일 하나 10MB (서버와 같은 값). 넘는 파일과 0바이트 파일은 올리기 전에 알린다
export const MAX_FILE_SIZE = 10 * 1024 * 1024;

// 파일 고르기 창의 거르기: 문서·그림(PDF, 한글, 워드·엑셀·파워포인트, 글, PNG·JPG)
export const DOCUMENT_ACCEPT = ".pdf,.hwp,.hwpx,.docx,.xlsx,.pptx,.txt,.png,.jpg,.jpeg";

export function uploadFile(purpose: FilePurpose, file: File) {
  const form = new FormData();
  form.append("purpose", purpose);
  form.append("file", file, file.name);
  return apiFetch<UploadedFile>("/api/files", { method: "POST", body: form });
}

// 내려받기 주소: 서버가 attachment로 보내 브라우저가 열지 않고 저장한다
export const fileUrl = (id: number) => `/api/files/${id}`;

export const deleteTempFile = (id: number) => apiFetch<void>(`/api/files/${id}`, { method: "DELETE" });
