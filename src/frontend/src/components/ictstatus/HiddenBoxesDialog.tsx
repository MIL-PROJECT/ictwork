import type { BoxCard } from "@/api/ictStatus";
import { RowButton } from "@/components/admin/DataTable";
import { Modal } from "@/components/ui/modal";
import { useTranslation } from "react-i18next";

/** 숨긴 박스 (REQ-503): 목록과 [다시 보이기]. 다시 보이면 예전 자리로 돌아간다 */
export default function HiddenBoxesDialog({ boxes, onShow, onClose }: { boxes: BoxCard[]; onShow: (box: BoxCard) => void; onClose: () => void }) {
  const { t } = useTranslation("ictStatus", { keyPrefix: "hiddenDialog" });
  return (
    <Modal isOpen onClose={onClose} labelledBy="hidden-boxes-title" className="max-w-lg p-5 sm:p-8">
      <h3 id="hidden-boxes-title" className="mb-4 pe-10 text-lg font-semibold text-gray-800 dark:text-white/90">
        {t("title")}
      </h3>
      {boxes.length === 0 ? (
        <p className="text-sm text-gray-500 dark:text-gray-400">{t("none")}</p>
      ) : (
        <ul className="divide-y divide-gray-100 dark:divide-gray-800">
          {boxes.map((b) => (
            <li key={b.id} className="flex flex-wrap items-center justify-between gap-2 py-2 text-sm">
              <span className="text-gray-800 dark:text-white/90">{`${b.departmentLabel} · ${b.title}`}</span>
              <RowButton onClick={() => onShow(b)}>{t("show")}</RowButton>
            </li>
          ))}
        </ul>
      )}
    </Modal>
  );
}
