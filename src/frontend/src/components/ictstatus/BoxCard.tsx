import type { BoxCard as BoxCardData } from "@/api/ictStatus";
import { Dropdown } from "@/components/ui/dropdown/Dropdown";
import { DropdownItem } from "@/components/ui/dropdown/DropdownItem";
import { HorizontaLDots } from "@/icons";
import { cn } from "@/utils";
import { indicatorSummary } from "@/utils/ictStatusPresentation";
import { type ReactNode, useState } from "react";
import { useTranslation } from "react-i18next";
import { BOX_TONE } from "./boxColors";
import { timeText } from "./boxFields";

const menuItem = "block w-full px-4 py-2 text-start text-sm whitespace-nowrap text-gray-700 hover:bg-gray-100 focus:bg-gray-100 focus:outline-hidden dark:text-gray-300 dark:hover:bg-white/5 dark:focus:bg-white/5";
const moveButton =
  "inline-flex h-8 items-center justify-center rounded-lg px-3 text-[13px] font-medium text-gray-700 ring-1 ring-gray-200 ring-inset hover:bg-gray-50 disabled:cursor-not-allowed disabled:opacity-40 dark:text-gray-300 dark:ring-gray-700 dark:hover:bg-white/5";

export interface CardMenu {
  first: boolean;
  last: boolean;
  onSize: () => void;
  onHide: () => void;
  onMove: (by: -1 | 1) => void;
}

/**
 * 박스 카드 (REQ-503, slices/15 13.2): 색 띠·배경, 부서 이름, 제목, 대표 내용, 마지막 고친 사람·시각. "크게"는 대표 내용을 크게.
 * "…" 메뉴: 크게·작게, 숨기기. 위·아래 단추(좁은 화면은 늘, 넓은 화면은 키보드 초점일 때 보임). 누르면 상세. 색은 글자(화면 읽기·툴팁)로도 보인다
 */
export default function BoxCard({ box, menu, onOpen, handle }: { box: BoxCardData; menu: CardMenu; onOpen: () => void; handle?: ReactNode }) {
  const { t } = useTranslation("ictStatus");
  const [open, setOpen] = useState(false);
  const tone = BOX_TONE[box.shownColor];
  const large = box.size === "LARGE";
  const colorName = t(`colors.${box.shownColor}`);
  const select = (action: () => void) => {
    setOpen(false);
    action();
  };
  return (
    // OMNIS 카드(.panel·.metric): 흰 면·그림자·모서리 20, 왼쪽 색 띠. 대표 내용은 박스 색 옅은 바탕 칸에 굵게
    <div className="ict-status-card relative flex h-full overflow-visible rounded-[20px] bg-white shadow-[0_1px_2px_rgba(35,46,107,0.04),0_10px_30px_-14px_rgba(35,46,107,0.14)] transition-shadow duration-200 hover:shadow-[0_14px_34px_-14px_rgba(35,46,107,0.22)] dark:ring-1 dark:ring-gray-800 dark:ring-inset dark:bg-white/3 dark:shadow-none">
      <span className="ict-status-accent pointer-events-none absolute inset-0 overflow-hidden rounded-[inherit]" aria-hidden>
        <span className={cn("absolute inset-y-0 start-0 w-1.5", tone.bar)} />
      </span>
      <span className="w-1.5 shrink-0" aria-hidden />
      <div className="flex min-w-0 flex-1 flex-col gap-1.5 px-5 pt-4 pb-4">
        <div className="flex items-start justify-between gap-2">
          <p className="min-w-0 text-xs font-semibold break-keep text-gray-400 dark:text-gray-500">{box.departmentLabel}</p>
          <div className="flex shrink-0 items-center gap-1">
            {handle}
            <div className="relative">
              <button
                type="button"
                onClick={() => setOpen(!open)}
                aria-haspopup="menu"
                aria-expanded={open}
                aria-label={t("card.menu", { title: box.title })}
                className="dropdown-toggle inline-flex size-8 items-center justify-center rounded-lg text-gray-400 hover:bg-gray-100 hover:text-gray-700 dark:text-gray-400 dark:hover:bg-white/5"
              >
                <HorizontaLDots className="size-5" aria-hidden />
              </button>
              <Dropdown isOpen={open} onClose={() => setOpen(false)} className="min-w-32 py-1">
                <div role="menu" aria-label={t("card.menu", { title: box.title })}>
                  <DropdownItem role="menuitem" baseClassName={menuItem} onClick={() => select(menu.onSize)}>
                    {large ? t("card.small") : t("card.large")}
                  </DropdownItem>
                  <DropdownItem role="menuitem" baseClassName={menuItem} onClick={() => select(menu.onHide)}>
                    {t("card.hide")}
                  </DropdownItem>
                </div>
              </Dropdown>
            </div>
          </div>
        </div>
        {(!menu.first || !menu.last) && (
          // 위·아래 단추: 좁은 화면은 늘 보이고, 넓은 화면(끌기)은 키보드로 닿을 때만 보인다
          <div className="flex gap-2 md:sr-only md:focus-within:not-sr-only md:focus-within:flex">
            <button type="button" data-move={`${box.id}:-1`} disabled={menu.first} onClick={() => menu.onMove(-1)} aria-label={t("card.moveUp", { title: box.title })} className={moveButton}>
              {t("card.up")}
            </button>
            <button type="button" data-move={`${box.id}:1`} disabled={menu.last} onClick={() => menu.onMove(1)} aria-label={t("card.moveDown", { title: box.title })} className={moveButton}>
              {t("card.down")}
            </button>
          </div>
        )}
        <button type="button" onClick={onOpen} className="flex flex-1 flex-col items-stretch gap-2 text-start" title={t("card.open", { title: box.title })}>
          <span className="text-[15.5px] font-bold tracking-[-0.3px] break-keep text-gray-900 dark:text-white/90">{box.title}</span>
          <span className={cn("rounded-xl px-3.5 py-2.5 font-extrabold tracking-[-0.5px] break-keep", tone.card, tone.text, large ? "text-title-sm" : "text-lg")}>{indicatorSummary(box, { normal: t("status.normal"), abnormal: t("status.abnormal") })}</span>
          <span className="sr-only">{t("colorLabel", { color: colorName })}</span>
          <span className={cn("mt-auto border-t border-gray-100 pt-2 text-gray-500 dark:border-gray-800 dark:text-gray-400", large ? "text-sm" : "text-xs")}>
            {t("card.updated", { name: box.updatedByName, time: timeText(box.updatedAt) })}
          </span>
        </button>
      </div>
    </div>
  );
}
