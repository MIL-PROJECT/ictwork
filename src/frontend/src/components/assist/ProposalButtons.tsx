import type { AssistOption } from "@/api/assist";
import { useTranslation } from "react-i18next";

/**
 * 제안 단추 (REQ-C02, slices/21 6.1·13.6): "업무 요청을 작성할까요?" 답 아래의 [작성 시작] 단추들.
 * 지난 대화의 옛 단추도 같다(누르면 체계가 그때의 권한·대상을 다시 확인한다). 고칠 대상 후보가 여럿이면 후보마다 단추 하나.
 * 이동 제안(kind=navigate)은 테두리 단추로 보이고, 누르면 그 화면으로 옮기기만 한다(onPick이 kind로 나눈다)
 */
export default function ProposalButtons({ options, disabled, onPick }: {
  options: AssistOption[];
  disabled: boolean;
  onPick: (option: AssistOption) => void;
}) {
  const { t } = useTranslation("assist");
  if (options.length === 0) return null;
  return (
    <div role="group" aria-label={t("proposal.group")} className="mt-3 flex flex-wrap gap-2">
      {options.map((o, i) => (
        <button
          key={`${o.screen}-${String(o.target)}-${i}`}
          type="button"
          disabled={disabled}
          onClick={() => onPick(o)}
          className={
            o.kind === "navigate"
              ? "rounded-lg bg-white px-3.5 py-2 text-start text-sm font-medium text-brand-600 ring-1 ring-brand-300 ring-inset hover:bg-brand-50 disabled:opacity-50 disabled:hover:bg-white dark:bg-transparent dark:text-brand-300 dark:ring-brand-500/40 dark:hover:bg-white/5"
              : "rounded-lg bg-brand-500 px-3.5 py-2 text-start text-sm font-medium text-white hover:bg-brand-600 disabled:opacity-50 disabled:hover:bg-brand-500"
          }
        >
          {o.label || (o.kind === "navigate" ? t("proposal.navigate") : t("proposal.start", { task: o.task }))}
        </button>
      ))}
    </div>
  );
}
