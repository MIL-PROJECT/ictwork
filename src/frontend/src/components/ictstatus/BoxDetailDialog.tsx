import { ApiError } from "@/api/client";
import { type BoxDetail, deleteBox, fetchBox } from "@/api/ictStatus";
import Button from "@/components/ui/button/Button";
import { Modal } from "@/components/ui/modal";
import { cn } from "@/utils";
import { useEffect, useState } from "react";
import { useTranslation } from "react-i18next";
import AssigneeDialog from "./AssigneeDialog";
import { BOX_TONE } from "./boxColors";
import BoxEditor from "./BoxEditor";
import { DETAIL_TABS, type DetailTab, timeText, useIctError } from "./boxFields";
import RecordPanel from "./RecordPanel";
import RevisionPanel from "./RevisionPanel";
import { BoxFieldsView } from "./TableView";


/**
 * 박스 상세 창 (REQ-503, slices/15 13.4): 내용(부서·제목·대표 내용·색·마지막 고친 사람·시각, 칸 값, 고치는 사람에게 [고치기], 관리자에게 [담당자]·[박스 지우기]),
 * 지난 판, 기록. 좁은 화면은 전체 화면. tick이 바뀌면(1분마다) 읽기 상태일 때만 다시 부르고, 고치는 중이면 그대로 둔다(저장 때 409로 나중 저장을 막음)
 */
export default function BoxDetailDialog({ boxId, tab, onTab, tick, onClose, onChanged }: {
  boxId: number;
  tab: DetailTab;
  onTab: (tab: DetailTab) => void;
  tick: number;
  onClose: () => void;
  onChanged: () => void;
}) {
  const { t } = useTranslation("ictStatus");
  const describe = useIctError();
  const [detail, setDetail] = useState<BoxDetail | null>(null);
  const [missing, setMissing] = useState(false);
  const [error, setError] = useState("");
  const [editing, setEditing] = useState(false);
  const [assigning, setAssigning] = useState(false);
  const [confirmDelete, setConfirmDelete] = useState(false);
  const [reload, setReload] = useState(0);

  useEffect(() => {
    if (editing) return;
    let cancelled = false;
    fetchBox(boxId).then(
      (d) => {
        if (cancelled) return;
        setDetail(d);
        setMissing(false);
      },
      (e) => {
        if (cancelled) return;
        if (e instanceof ApiError && e.status === 404) setMissing(true);
        else setError(describe(e));
      },
    );
    return () => {
      cancelled = true;
    };
  }, [boxId, tick, reload, editing, describe]);

  const remove = async () => {
    try {
      await deleteBox(boxId);
      onChanged();
      onClose();
    } catch (e) {
      setError(describe(e));
    }
  };

  // 탭 줄 키보드: ←·→로 앞뒤 탭, Home·End로 처음·끝 (쓸 수 없는 탭은 건너뜀). 고른 탭으로 초점을 옮긴다
  const tabKey = (e: React.KeyboardEvent<HTMLDivElement>) => {
    const usable = DETAIL_TABS.filter((x) => !(editing && x !== "content"));
    const i = usable.indexOf(tab);
    const next =
      e.key === "ArrowRight" ? usable[(i + 1) % usable.length]
      : e.key === "ArrowLeft" ? usable[(i - 1 + usable.length) % usable.length]
      : e.key === "Home" ? usable[0]
      : e.key === "End" ? usable[usable.length - 1]
      : null;
    if (!next) return;
    e.preventDefault();
    onTab(next);
    document.getElementById(`box-tab-${next}`)?.focus();
  };

  const tone = detail ? BOX_TONE[detail.shownColor] : BOX_TONE.GRAY;
  return (
    <Modal
      isOpen
      onClose={onClose}
      labelledBy="box-detail-title"
      className="custom-scrollbar max-h-screen max-w-4xl overflow-y-auto p-5 max-sm:min-h-screen max-sm:rounded-none sm:max-h-[90vh] sm:p-8"
    >
      {missing ? (
        <p id="box-detail-title" role="alert" className="pe-10 text-sm text-gray-700 dark:text-gray-300">
          {t("notFound")}
        </p>
      ) : !detail ? (
        <p id="box-detail-title" className="text-sm text-gray-500 dark:text-gray-400">
          {error || t("loading")}
        </p>
      ) : (
        <div className="space-y-5">
          <div className="pe-12">
            <p className="text-theme-xs text-gray-500 dark:text-gray-400">{detail.departmentLabel}</p>
            <h3 id="box-detail-title" className="text-lg font-semibold text-gray-800 dark:text-white/90">
              {detail.title}
            </h3>
          </div>
          <div role="tablist" aria-label={detail.title} onKeyDown={tabKey} className="flex gap-1 border-b border-gray-200 dark:border-gray-800">
            {DETAIL_TABS.map((x) => (
              <button
                key={x}
                id={`box-tab-${x}`}
                type="button"
                role="tab"
                aria-selected={tab === x}
                aria-controls="box-tabpanel"
                tabIndex={tab === x ? 0 : -1}
                disabled={editing && x !== "content"}
                onClick={() => onTab(x)}
                className={cn(
                  "-mb-px border-b-2 px-3 py-2 text-sm font-medium disabled:opacity-50",
                  tab === x ? "border-brand-500 text-brand-600 dark:text-brand-400" : "border-transparent text-gray-500 hover:text-gray-800 dark:text-gray-400 dark:hover:text-white/90",
                )}
              >
                {t(`detail.tabs.${x}`)}
              </button>
            ))}
          </div>
          <div role="tabpanel" id="box-tabpanel" aria-labelledby={`box-tab-${tab}`}>
            {tab === "content" &&
              (editing ? (
                <BoxEditor
                  detail={detail}
                  onSaved={(saved) => {
                    setDetail(saved);
                    setEditing(false);
                    onChanged();
                  }}
                  onCancel={() => setEditing(false)}
                  onReopen={() => {
                    setEditing(false);
                    setReload((n) => n + 1);
                  }}
                />
              ) : (
                <div className="space-y-5">
                  <dl className="grid gap-3 sm:grid-cols-2">
                    <div>
                      <dt className="text-theme-xs text-gray-500 dark:text-gray-400">{t("detail.summary")}</dt>
                      <dd className={cn("mt-1 inline-flex rounded-md px-3 py-1.5 text-base font-semibold", tone.card, tone.text)}>
                        {detail.shownSummary}
                        <span className="sr-only">{t("colorLabel", { color: t(`colors.${detail.shownColor}`) })}</span>
                      </dd>
                    </div>
                    <div>
                      <dt className="text-theme-xs text-gray-500 dark:text-gray-400">{t("detail.updated")}</dt>
                      <dd className="mt-1 text-sm text-gray-700 dark:text-gray-300">{`${detail.updatedByName} · ${timeText(detail.updatedAt)}`}</dd>
                    </div>
                  </dl>
                  <BoxFieldsView definition={detail.definition} values={detail.values} tables={detail.tables} files={detail.files} />
                  {(detail.canEdit || detail.canManage) && (
                    <div className="flex flex-wrap gap-2 border-t border-gray-200 pt-4 dark:border-gray-800">
                      {detail.canEdit && (
                        <Button size="sm" onClick={() => setEditing(true)}>
                          {t("detail.edit")}
                        </Button>
                      )}
                      {detail.canManage && (
                        <Button size="sm" variant="outline" onClick={() => setAssigning(true)}>
                          {t("detail.assignees")}
                        </Button>
                      )}
                      {detail.canManage && (
                        <Button size="sm" variant="outline" onClick={() => setConfirmDelete(true)}>
                          {t("detail.delete")}
                        </Button>
                      )}
                    </div>
                  )}
                  {confirmDelete && (
                    <div role="alertdialog" aria-labelledby="box-delete-confirm" className="space-y-2 rounded-lg bg-error-50 p-3 dark:bg-error-500/15">
                      <p id="box-delete-confirm" className="text-sm text-error-700 dark:text-error-400">
                        {t("detail.deleteConfirm", { title: detail.title })}
                      </p>
                      <div className="flex gap-2">
                        <Button size="sm" onClick={() => void remove()}>
                          {t("detail.deleteYes")}
                        </Button>
                        <Button size="sm" variant="outline" onClick={() => setConfirmDelete(false)}>
                          {t("detail.cancel")}
                        </Button>
                      </div>
                    </div>
                  )}
                  {error && (
                    <p role="alert" className="text-sm text-error-600 dark:text-error-400">
                      {error}
                    </p>
                  )}
                </div>
              ))}
            {tab === "revisions" && <RevisionPanel boxId={boxId} />}
            {tab === "record" && <RecordPanel boxId={boxId} />}
          </div>
        </div>
      )}
      {assigning && detail && (
        <AssigneeDialog
          boxId={boxId}
          departmentId={detail.departmentId}
          current={detail.assignees ?? []}
          onSaved={(list) => setDetail({ ...detail, assignees: list })}
          onClose={() => setAssigning(false)}
        />
      )}
    </Modal>
  );
}
