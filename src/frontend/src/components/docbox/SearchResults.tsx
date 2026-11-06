import { useState } from "react";
import { docDownloadUrl, docViewUrl, type DocSearchHit } from "@/api/docbox";
import { FileIcon, FolderIcon } from "@/icons";
import { useTranslation } from "react-i18next";
import FilePreviewDialog from "@/components/file/preview/FilePreviewDialog";
import { previewKind } from "@/components/file/preview/previewKind";
import { Link } from "react-router";
import { formatTime, pathLastId, pathText } from "./docUtil";

const head = "px-4 py-3 text-start font-medium whitespace-nowrap text-gray-500 dark:text-gray-400";
const cell = "px-4 py-3 align-top text-gray-700 dark:text-gray-300";
const action = "rounded-md px-2 py-1 text-sm font-medium whitespace-nowrap text-brand-600 hover:bg-brand-50 dark:text-brand-400 dark:hover:bg-white/5";
const nameLink = "text-start font-medium wrap-break-word text-gray-800 hover:underline dark:text-white/90";

/** 찾은 것이 있는 곳을 여는 주소: 폴더는 그 폴더, 파일은 든 폴더를 열고 그 줄을 강조한다 */
function openLink(hit: DocSearchHit) {
  const params = new URLSearchParams();
  if (hit.via === "SHARED") params.set("shared", "1");
  else params.set("box", String(hit.departmentId));
  const folder = hit.node.kind === "FOLDER" ? hit.node.id : pathLastId(hit.path);
  if (folder !== null) params.set("folder", String(folder));
  if (hit.node.kind === "FILE") params.set("file", String(hit.node.id));
  return `/department/docs?${params}`;
}

/** 이름 찾기 결과 (REQ-A03): 이름, 있는 곳(문서함 > 폴더), 공유받은 것 표시, 여는 링크·내려받기. 최대 200건(서버) */
export default function SearchResults({ hits, query }: { hits: DocSearchHit[]; query: string }) {
  const { t } = useTranslation("docbox", { keyPrefix: "search" });
  // 미리보기 창: PDF·그림·txt·한글·워드는 새 탭 대신 체계 안에서 본다
  const [previewing, setPreviewing] = useState<{ id: number; name: string } | null>(null);
  return (
    // 문서함 내용 카드(Panel) 안에 꽉 차게 둔다: 상자 테두리·모서리 없음
    <div className="relative overflow-x-auto">
      <table className="min-w-full text-sm break-keep">
        <caption className="sr-only">{t("caption", { q: query })}</caption>
        <thead className="border-b border-gray-200 dark:border-gray-800">
          <tr>
            <th className={head}>{t("name")}</th>
            <th className={head}>{t("location")}</th>
            <th className={head}>{t("updated")}</th>
            <th className={head}>
              <span className="sr-only">{t("actions")}</span>
            </th>
          </tr>
        </thead>
        <tbody className="divide-y divide-gray-100 dark:divide-gray-800">
          {hits.length === 0 && (
            <tr>
              <td colSpan={4} className="px-5 py-6 text-center text-gray-500 dark:text-gray-400">
                {t("none")}
              </td>
            </tr>
          )}
          {hits.map((hit) => {
            const Icon = hit.node.kind === "FOLDER" ? FolderIcon : FileIcon;
            const folders = pathText(hit.path);
            return (
              <tr key={`${hit.via}-${hit.node.id}`}>
                <td className={cell}>
                  <div className="flex min-w-48 items-start gap-2">
                    <Icon aria-hidden="true" className="mt-0.5 size-4.5 shrink-0 text-gray-400" />
                    {/* 이름이 주 동작: 폴더는 그 폴더를 열고, 파일은 바로 보기(새 탭) 또는 내려받기 */}
                    {hit.node.kind === "FOLDER" ? (
                      <Link to={openLink(hit)} className={nameLink}>
                        {hit.node.name}
                      </Link>
                    ) : previewKind(hit.node.name) ? (
                      <button type="button" onClick={() => setPreviewing({ id: hit.node.id, name: hit.node.name })} className={nameLink}>
                        {hit.node.name}
                      </button>
                    ) : (
                      <a href={docDownloadUrl(hit.node.id)} className={nameLink}>
                        {hit.node.name}
                      </a>
                    )}
                  </div>
                </td>
                <td className={cell}>
                  <div className="min-w-40 wrap-break-word">{[hit.boxName, folders].filter(Boolean).join(" > ")}</div>
                  {hit.via === "SHARED" && <div className="text-theme-xs text-blue-light-700 dark:text-blue-light-400">{t("viaShared")}</div>}
                </td>
                <td className={`${cell} whitespace-nowrap`}>{formatTime(hit.node.updatedAt)}</td>
                <td className={cell}>
                  <div className="flex gap-1">
                    <Link to={openLink(hit)} className={action} aria-label={t("openNamed", { name: hit.node.name })}>
                      {t(hit.node.kind === "FOLDER" ? "openFolder" : "openLocation")}
                    </Link>
                    {hit.node.kind === "FILE" && (
                      <a href={docDownloadUrl(hit.node.id)} className={action} aria-label={t("downloadNamed", { name: hit.node.name })}>
                        {t("download")}
                      </a>
                    )}
                  </div>
                </td>
              </tr>
            );
          })}
        </tbody>
      </table>
      {previewing && (
        <FilePreviewDialog key={previewing.id} name={previewing.name} viewUrl={docViewUrl(previewing.id)} downloadUrl={docDownloadUrl(previewing.id)} onClose={() => setPreviewing(null)} />
      )}
    </div>
  );
}
