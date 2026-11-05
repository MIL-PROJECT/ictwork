import type { DirectiveItem } from "@/api/directive";
import Badge from "@/components/ui/badge/Badge";
import { cn } from "@/utils";
import { useTranslation } from "react-i18next";
import { DirectiveImages } from "./DirectiveBoxes";

/** 접힌 상태에서도 다 보이는가: 줄바꿈이 없고 80자 이하 (slices/13 6.3) */
const isShort = (content: string) => !content.includes("\n") && Array.from(content).length <= 80;

/** "2026-10-31T14:05:00" → "2026-10-31 14:05" */
const minute = (time: string) => time.replace("T", " ").slice(0, 16);

const action = "text-sm font-medium text-brand-600 hover:underline dark:text-brand-400";
const danger = "text-sm font-medium text-error-600 hover:underline dark:text-error-400";

/** "내림" 표시 (G-146) */
function TakenDownBadge() {
  const { t } = useTranslation("directive");
  return (
    <Badge size="sm" color="light">
      {t("takenDown")}
    </Badge>
  );
}

/**
 * 지시사항 목록 ([기록] 탭, REQ-601, slices/13 6.3·14.5·15.4): 넓은 화면(md 이상)은 표, 좁은 화면은 카드. 내용은 줄바꿈을 살려 2줄로 접어 보이고 [더 보기]로 펼친다.
 * 그림이 있으면 접힌 줄에 "그림 n장", 펼친 줄에 내용 아래 그림을 세로로 보인다(그림이 있으면 내용이 짧아도 [더 보기]를 둔다, G-147).
 * 고친 건은 "고침(시각)", 명의자 본인에게는 "시스템 관리자가 대신 올림", 내린 건은 "내림". highlightId: 알림·출처로 연 건 (6.4). 줄·카드에 data-directive로 id를 단다(강조한 건으로 스크롤).
 * 단추: 고칠 수 있으면 [고치기][지우기], 내릴 수 있으면 올라 있는 건 [내리기]·내린 건 [다시 올림](요청 중인 건 pendingId는 막음: 단추를 끄지 않고
 * aria-disabled로 누름만 막아 키보드 초점이 남고, 다 되면 같은 단추가 [내리기]로 바뀐다)
 */
export default function DirectiveList({ items, expanded, highlightId, pendingId, onToggle, onSelect, onEdit, onDelete, onTakeDown, onRestore }: {
  items: DirectiveItem[];
  expanded: Set<number>;
  highlightId: number | null;
  onToggle: (id: number) => void;
  onSelect: (id: number) => void; // 다른 줄을 누르면 강조를 거둔다
  onEdit: (item: DirectiveItem) => void;
  onDelete: (item: DirectiveItem) => void;
  pendingId: number | null;
  onTakeDown: (item: DirectiveItem) => void;
  onRestore: (item: DirectiveItem) => void;
}) {
  const { t } = useTranslation("directive");
  const { t: ta } = useTranslation("admin");
  const named = (d: DirectiveItem) => (d.namedRankCode ? `${ta(`ranks.${d.namedRankCode}`)} ${d.namedName}` : d.namedName);
  const highlight = (d: DirectiveItem) => d.id === highlightId;
  const highlightClass = "bg-brand-25 shadow-[inset_3px_0_0_var(--color-brand-500)] dark:bg-brand-500/10";

  // where: 표와 카드가 함께 그려지므로 id를 나눈다
  const content = (d: DirectiveItem, where: "row" | "card") => {
    const open = expanded.has(d.id);
    const contentId = `directive-${where}-content-${d.id}`;
    const short = isShort(d.content) && d.images.length === 0;
    return (
      <div>
        <p id={contentId} className={cn("break-words whitespace-pre-line text-gray-800 dark:text-white/90", !open && !short && "line-clamp-2")}>
          {d.content}
        </p>
        {d.images.length > 0 &&
          (open ? (
            <DirectiveImages directiveId={d.id} images={d.images} />
          ) : (
            <p className="mt-1 text-sm text-gray-500 dark:text-gray-400">{t("images.count", { n: d.images.length })}</p>
          ))}
        {!short && (
          <button
            type="button"
            aria-expanded={open}
            aria-controls={contentId}
            onClick={(e) => {
              e.stopPropagation();
              onToggle(d.id);
            }}
            className={cn(action, "mt-1")}
          >
            {open ? t("less") : t("more")}
          </button>
        )}
      </div>
    );
  };

  const hasButtons = (d: DirectiveItem) => d.canEdit || d.canTakeDown;
  const buttons = (d: DirectiveItem) =>
    hasButtons(d) && (
      <div className="flex flex-wrap gap-3">
        {d.canEdit && (
          <>
            <button type="button" onClick={() => onEdit(d)} className={action}>
              {t("edit")}
            </button>
            <button type="button" onClick={() => onDelete(d)} className={danger}>
              {t("delete")}
            </button>
          </>
        )}
        {d.canTakeDown && (
          <button
            type="button"
            onClick={() => {
              if (pendingId === d.id) return;
              if (d.takenDown) onRestore(d);
              else onTakeDown(d);
            }}
            aria-disabled={pendingId === d.id}
            aria-busy={pendingId === d.id}
            className={cn(action, "aria-disabled:cursor-not-allowed aria-disabled:opacity-60")}
          >
            {d.takenDown ? t("restore") : t("takeDown")}
          </button>
        )}
      </div>
    );

  const takenDownNote = (d: DirectiveItem) =>
    d.takenDown && (
      <span className="mt-1 block">
        <TakenDownBadge />
      </span>
    );

  const editedNote = (d: DirectiveItem) =>
    d.editedAt && <span className="block text-xs text-gray-500 dark:text-gray-400">{t("edited", { time: minute(d.editedAt) })}</span>;

  const proxyNote = (d: DirectiveItem) =>
    d.proxy && (
      <span className="mt-1 block">
        <Badge size="sm" color="info">
          {t("proxy")}
        </Badge>
      </span>
    );

  return (
    <>
      {/* 넓은 화면: 표 */}
      <div className="hidden overflow-x-auto md:block">
        <table className="w-full text-start text-sm">
          <thead>
            <tr className="border-b border-gray-200 text-gray-500 dark:border-gray-800 dark:text-gray-400">
              <th scope="col" className="w-36 px-3 py-3 text-start font-medium">{t("columns.date")}</th>
              <th scope="col" className="w-44 px-3 py-3 text-start font-medium">{t("columns.department")}</th>
              <th scope="col" className="w-44 px-3 py-3 text-start font-medium">{t("columns.named")}</th>
              <th scope="col" className="px-3 py-3 text-start font-medium">{t("columns.content")}</th>
              {/* [고치기][지우기][다시 올림]이 ko·en 모두 한 줄에 들어가는 너비 */}
              <th scope="col" className="w-56 px-3 py-3 text-start font-medium">
                <span className="sr-only">{t("columns.actions")}</span>
              </th>
            </tr>
          </thead>
          <tbody>
            {items.map((d) => (
              <tr
                key={d.id}
                data-directive={d.id}
                aria-current={highlight(d) ? "true" : undefined}
                onClick={() => onSelect(d.id)}
                className={cn("border-b border-gray-100 align-top last:border-b-0 dark:border-gray-800", highlight(d) && highlightClass)}
              >
                <td className="px-3 py-3 whitespace-nowrap text-gray-700 dark:text-gray-300">
                  {d.directiveDate}
                  {editedNote(d)}
                  {takenDownNote(d)}
                </td>
                <td className="px-3 py-3 text-gray-700 dark:text-gray-300">{d.departmentName}</td>
                <td className="px-3 py-3 text-gray-700 dark:text-gray-300">
                  {named(d)}
                  {proxyNote(d)}
                </td>
                <td className="px-3 py-3">{content(d, "row")}</td>
                <td className="px-3 py-3">{buttons(d)}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>

      {/* 좁은 화면: 카드 */}
      <ul className="space-y-3 p-4 md:hidden">
        {items.map((d) => (
          <li
            key={d.id}
            data-directive={d.id}
            aria-current={highlight(d) ? "true" : undefined}
            onClick={() => onSelect(d.id)}
            className={cn("rounded-2xl border border-gray-200 border-s-3 border-s-gray-300 p-4 text-sm dark:border-gray-800 dark:border-s-gray-700", highlight(d) && "border-s-brand-500 bg-brand-25 dark:bg-brand-500/10")}
          >
            <p className="flex flex-wrap items-center gap-2 font-medium text-gray-800 dark:text-white/90">
              <span>
                {d.directiveDate} · {d.departmentName}
              </span>
              {d.takenDown && <TakenDownBadge />}
            </p>
            {editedNote(d)}
            <p className="mt-1 text-gray-700 dark:text-gray-300">
              <span className="sr-only">{t("columns.named")} </span>
              {named(d)}
            </p>
            {proxyNote(d)}
            <div className="mt-2">{content(d, "card")}</div>
            {hasButtons(d) && <div className="mt-3">{buttons(d)}</div>}
          </li>
        ))}
      </ul>
    </>
  );
}
