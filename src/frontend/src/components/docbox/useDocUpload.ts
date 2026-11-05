import { ApiError } from "@/api/client";
import { type DocBoxInfo, uploadDocFile } from "@/api/docbox";
import { formatSize } from "@/components/file/attachments";
import { useState } from "react";
import { useTranslation } from "react-i18next";
import { uploadProblem, useDocError } from "./docUtil";

export interface UploadFailure {
  name: string;
  reason: string;
}

export interface UploadReport {
  total: number; // 고른 것 모두(거른 것 포함)
  sending: number; // 보낼 파일 수(먼저 거른 것 빼고)
  current: number; // 지금 보내는 차례(1부터)
  done: number;
  failed: UploadFailure[];
  running: boolean;
}

/**
 * 여러 파일 올리기 (REQ-A02): 크기·0바이트·종류를 먼저 보고, 통과한 파일만 하나씩 보낸다.
 * 일부가 안 되어도 된 것은 남기고, 끝나면 안 된 파일과 까닭을 모아 보인다. rejected: 고를 때 이미 거른 것(끌어다 놓은 폴더)
 * 용량 초과(REQ-A06)는 문서함 정보(쓴 용량·상한)에서 이번에 올린 만큼을 빼 남은 용량을 셈해 보인다
 */
export function useDocUpload(onFinished: () => void) {
  const { t } = useTranslation("docbox", { keyPrefix: "upload" });
  const { t: tf } = useTranslation("file");
  const describe = useDocError();
  const [report, setReport] = useState<UploadReport | null>(null);

  const upload = async (box: DocBoxInfo, parentId: number | null, files: File[], rejected: UploadFailure[] = []) => {
    const failed: UploadFailure[] = [...rejected];
    const ready: File[] = [];
    for (const file of files) {
      const problem = uploadProblem(file);
      if (problem === "tooLarge") failed.push({ name: file.name, reason: tf("tooLarge") });
      else if (problem === "empty") failed.push({ name: file.name, reason: tf("empty") });
      else if (problem === "typeNotAllowed") failed.push({ name: file.name, reason: t("typeNotAllowed") });
      else ready.push(file);
    }
    const total = files.length + rejected.length;
    let done = 0;
    let sentBytes = 0; // 이번에 올린 크기 (문서함 정보를 읽은 뒤 늘어난 쓴 용량)
    const reason = (err: unknown) => {
      const left = (used: number | null, limit: number | null) => (used === null || limit === null ? null : { limit: formatSize(limit), left: formatSize(Math.max(0, limit - used - sentBytes)) });
      const boxLeft = err instanceof ApiError && err.code === "DOC_BOX_FULL" ? left(box.usedBytes, box.limitBytes) : null;
      if (boxLeft) return t("boxFull", boxLeft);
      const totalLeft = err instanceof ApiError && err.code === "DOC_TOTAL_FULL" ? left(box.totalUsedBytes, box.totalLimitBytes) : null;
      if (totalLeft) return t("totalFull", totalLeft);
      return describe(err);
    };
    setReport({ total, sending: ready.length, current: 0, done, failed: [...failed], running: ready.length > 0 });
    for (const [index, file] of ready.entries()) {
      setReport((r) => r && { ...r, current: index + 1 });
      try {
        await uploadDocFile(box.departmentId, parentId, file);
        done += 1;
        sentBytes += file.size;
      } catch (err) {
        failed.push({ name: file.name, reason: reason(err) });
      }
      setReport((r) => r && { ...r, done, failed: [...failed] });
    }
    setReport({ total, sending: ready.length, current: ready.length, done, failed, running: false });
    if (done > 0) onFinished();
  };

  return { report, upload, clear: () => setReport(null) };
}
