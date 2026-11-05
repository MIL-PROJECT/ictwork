import { type DirectiveImage, type DirectiveItem, directiveImageUrl } from "@/api/directive";
import Badge from "@/components/ui/badge/Badge";
import { cn } from "@/utils";
import { useState } from "react";
import { useTranslation } from "react-i18next";

/** 접힌 4줄 안에 다 보이는가 (어림): 줄이 4개 이하이고 160자 이하 */
const isShort = (content: string) => content.split("\n").length <= 4 && Array.from(content).length <= 160;

/** "2026-10-31T14:05:00" → "2026-10-31 14:05" */
const minute = (time: string) => time.replace("T", " ").slice(0, 16);

const action = "text-base font-medium text-brand-600 hover:underline dark:text-brand-400";

/**
 * 지시사항 그림 (REQ-601, G-147, slices/13 15.4): 더한 순서대로 한 장씩 세로로, 폭을 다 쓰되 화면 높이보다 크면 화면 높이에 맞춘다.
 * 대체 글은 "그림 n". 지시사항이 지워졌거나 볼 수 없게 되어 받지 못하면 그 자리에 안내 글을 보인다. [지시사항] 박스와 [기록] 펼친 줄이 함께 쓴다.
 * 못 받은 그림은 받은 images(목록 응답)에 묶어 두어, 목록을 다시 받으면(새 images) 실패 상태가 지워지고 다시 받는다
 */
export function DirectiveImages({ directiveId, images }: { directiveId: number; images: DirectiveImage[] }) {
  const { t } = useTranslation("directive");
  const [failedOf, setFailedOf] = useState<{ images: DirectiveImage[]; ids: Set<number> } | null>(null);
  if (images.length === 0) return null;
  const failed = failedOf?.images === images ? failedOf.ids : new Set<number>();
  return (
    <div className="mt-3 flex flex-col gap-3">
      {images.map((image, i) =>
        failed.has(image.id) ? (
          <p
            key={image.id}
            className="rounded-lg border border-dashed border-gray-300 px-4 py-6 text-center text-sm text-gray-500 dark:border-gray-700 dark:text-gray-400"
          >
            {t("images.loadFailed")}
          </p>
        ) : (
          <img
            key={image.id}
            src={directiveImageUrl(directiveId, image.id)}
            alt={t("images.alt", { n: i + 1 })}
            loading="lazy"
            onError={() => setFailedOf((prev) => ({ images, ids: new Set(prev?.images === images ? prev.ids : []).add(image.id) }))}
            className="block h-auto max-h-[80vh] w-full rounded-lg border border-gray-200 object-contain dark:border-gray-800"
          />
        ),
      )}
    </div>
  );
}

/**
 * [지시사항] 탭 직책별 박스 (REQ-601, G-146·G-147, slices/13 14.5·15.4). items는 서버 boxes 순서(대대 → 중대 → 반, 같은 단계는 조직 순서)로 받은 건이다.
 * 카드: 머리 "명의 부서 + 직책"(예: "1중대 중대장"), 명의자, 지시 일자, "고침(시각)", 명의자 본인에게 "시스템 관리자가 대신 올림",
 * 내용(줄바꿈 살림, 4줄로 접고 [더 보기]/[접기]), 내용 아래 그림(접기와 관계없이 늘), 내릴 수 있으면 [내리기].
 * 화면 너비와 관계없이 한 줄에 하나, 박스 안 글자는 한 단계 크게(내용은 화면 본문 17px)
 */
export default function DirectiveBoxes({ items, expanded, onToggle, onTakeDown }: {
  items: DirectiveItem[];
  expanded: Set<number>;
  onToggle: (id: number) => void;
  onTakeDown: (item: DirectiveItem) => void;
}) {
  const { t } = useTranslation("directive");
  const { t: ta } = useTranslation("admin");
  const named = (d: DirectiveItem) => (d.namedRankCode ? `${ta(`ranks.${d.namedRankCode}`)} ${d.namedName}` : d.namedName);
  const title = (d: DirectiveItem) =>
    d.positionName ? t("boxTitle", { department: d.departmentName, position: t(`position.${d.positionName}`) }) : d.departmentName;

  return (
    <ul aria-label={t("boxes.label")} className="flex flex-col gap-4">
      {items.map((d) => {
        const open = expanded.has(d.id);
        const short = isShort(d.content);
        const contentId = `directive-box-content-${d.id}`;
        const titleId = `directive-box-title-${d.id}`;
        return (
          <li key={d.id} data-directive={d.id}>
            {/* OMNIS 지시 카드(.directive-card): 가는 테두리·모서리 16·왼쪽 3px 남색 선, 마우스를 올리면 옅은 바탕 */}
            <article
              aria-labelledby={titleId}
              className="flex flex-col rounded-2xl border border-gray-200 border-s-3 border-s-brand-500 px-5 py-4 transition-colors hover:bg-gray-50/70 dark:border-gray-800 dark:border-s-brand-400 dark:hover:bg-white/3"
            >
              {/* tabIndex -1: 앞 박스를 내린 뒤 초점을 받는다 (Directives.tsx) */}
              <h4 id={titleId} tabIndex={-1} className="text-lg font-bold tracking-[-0.3px] text-gray-900 dark:text-white/90">
                {title(d)}
              </h4>
              <p className="mt-1 font-medium text-gray-700 dark:text-gray-300">
                <span className="sr-only">{t("columns.named")} </span>
                {named(d)}
              </p>
              <p className="mt-0.5 text-[13px] text-gray-400 tabular-nums dark:text-gray-500">
                <span className="sr-only">{t("columns.date")} </span>
                {d.directiveDate}
                {d.editedAt && <span className="ms-2">{t("edited", { time: minute(d.editedAt) })}</span>}
              </p>
              {d.proxy && (
                <span className="mt-1 block">
                  <Badge size="md" color="info">
                    {t("proxy")}
                  </Badge>
                </span>
              )}
              <div className="mt-3 flex-1 rounded-xl bg-gray-50 px-4 py-3 dark:bg-white/3">
                <p id={contentId} className={cn("break-words whitespace-pre-line text-gray-800 dark:text-white/90", !open && !short && "line-clamp-4")}>
                  {d.content}
                </p>
                {!short && (
                  <button type="button" aria-expanded={open} aria-controls={contentId} onClick={() => onToggle(d.id)} className={cn(action, "mt-1")}>
                    {open ? t("less") : t("more")}
                  </button>
                )}
                <DirectiveImages directiveId={d.id} images={d.images} />
              </div>
              {d.canTakeDown && (
                <div className="mt-3 flex justify-end border-t border-gray-100 pt-2.5 dark:border-gray-800">
                  <button type="button" onClick={() => onTakeDown(d)} className={action}>
                    {t("takeDown")}
                  </button>
                </div>
              )}
            </article>
          </li>
        );
      })}
    </ul>
  );
}
