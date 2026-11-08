import { type BoxCard, fetchIctTab, type IctTab, saveLayout, saveLayoutOrder } from "@/api/ictStatus";
import PageBreadcrumb from "@/components/common/PageBreadCrumb";
import PageMeta from "@/components/common/PageMeta";
import { useAssistPage } from "@/components/assist/useAssistAdapter";
import { EmptyState, Panel } from "@/components/common/Panel";
import AddBoxDialog from "@/components/ictstatus/AddBoxDialog";
import BoxDetailDialog from "@/components/ictstatus/BoxDetailDialog";
import { DETAIL_TABS, type DetailTab, useIctError } from "@/components/ictstatus/boxFields";
import BoxGrid from "@/components/ictstatus/BoxGrid";
import HiddenBoxesDialog from "@/components/ictstatus/HiddenBoxesDialog";
import Button from "@/components/ui/button/Button";
import { useMinuteRefresh } from "@/hooks/useMinuteRefresh";
import { BoxCubeIcon, EyeCloseIcon, PlusIcon } from "@/icons";
import { useCallback, useEffect, useState } from "react";
import { useTranslation } from "react-i18next";
import { useSearchParams } from "react-router";

/**
 * 정보통신 현황 탭 (REQ-503·602, slices/15 13.2): 내가 볼 수 있는 박스를 개인 배치(순서·크기·숨김, 서버 저장)대로 놓는다.
 * [박스 추가](관리하는 부서가 있을 때), [숨긴 박스 (N)]. 주소의 box·tab으로 상세를 연다(AI 출처·새로 고침). 화면이 보이는 동안 1분마다 다시 부른다
 */
export default function IctStatus({ integrated = false }: { integrated?: boolean }) {
  const { t } = useTranslation("ictStatus");
  const { t: tc } = useTranslation();
  const { t: tf } = useTranslation("frameGeneral");
  const describe = useIctError();
  const tick = useMinuteRefresh();
  const [params, setParams] = useSearchParams();
  const [tab, setTab] = useState<IctTab | null>(null);
  const [error, setError] = useState("");
  const [adding, setAdding] = useState(false);
  const [showHidden, setShowHidden] = useState(false);
  // AI 업무지원이 고치기로 열 박스 id (상세 창이 읽은 뒤 고치기 모드로)
  const [editRequest, setEditRequest] = useState<number | null>(null);
  const clearEditRequest = useCallback(() => setEditRequest(null), []);

  const boxParam = Number(params.get("box"));
  const openBox = Number.isInteger(boxParam) && boxParam > 0 ? boxParam : null;
  const tabParam = params.get("tab") as DetailTab | null;
  const detailTab: DetailTab = tabParam && DETAIL_TABS.includes(tabParam) ? tabParam : "content";

  const load = useCallback(() => {
    fetchIctTab().then(
      (data) => {
        setTab(data);
        setError("");
      },
      () => setError(t("loadFailed")),
    );
  }, [t]);
  useEffect(() => load(), [load, tick]);

  const open = (id: number | null, nextTab: DetailTab = "content") => {
    const next = new URLSearchParams(params);
    if (id === null) {
      next.delete("box");
      next.delete("tab");
    } else {
      next.set("box", String(id));
      if (nextTab === "content") next.delete("tab");
      else next.set("tab", nextTab);
    }
    setParams(next, { replace: id === null });
  };

  // 배치는 동작마다 바로 저장하고 화면을 먼저 바꾼다(실패하면 다시 읽음)
  const change = (patch: (boxes: BoxCard[]) => BoxCard[], save: () => Promise<void>) => {
    setTab((prev) => (prev ? { ...prev, boxes: patch(prev.boxes) } : prev));
    save().catch((e) => {
      setError(describe(e));
      load();
    });
  };
  // AI 업무지원 페이지 어댑터 (REQ-C07 정보통신 현황, slices/21 12.5): 박스 추가 창(관리하는 부서가 있을 때), 고칠 박스의 상세를 열어 고치기 모드로(고칠 수 있을 때).
  // 배치 끌기·담당자·지우기·파일 칸은 사용자만 한다
  useAssistPage(["ict.new", "ict.edit"], {
    ready: tab !== null,
    open: (screen, target) => {
      if (!tab) return false;
      if (screen === "ict.new") {
        if (tab.addDepartments.length === 0) return false;
        setAdding(true);
        return true;
      }
      const box = tab.boxes.find((b) => String(b.id) === String(target));
      if (!box || !box.canEdit) return false;
      setEditRequest(box.id);
      open(box.id);
      return true;
    },
  });

  const visible = tab?.boxes.filter((b) => !b.hidden) ?? [];
  const hidden = tab?.boxes.filter((b) => b.hidden) ?? [];

  return (
    <>
      <PageMeta titleKey={integrated ? "sidebar.items.operations" : "sidebar.items.ictStatus"} />
      {/* 페이지 머리: 제목·설명, 오른쪽에 [숨긴 박스 (N)]·[박스 추가] (OMNIS 제목 오른쪽 화면 동작) */}
      <PageBreadcrumb
        pageTitle={integrated ? tc("sidebar.items.operations") : t("title")}
        description={tf("ictStatus.description")}
        actions={
          (hidden.length > 0 || (tab && tab.addDepartments.length > 0)) && (
            <>
              {hidden.length > 0 && (
                <Button size="sm" variant="outline" startIcon={<EyeCloseIcon className="size-4" aria-hidden />} onClick={() => setShowHidden(true)}>
                  {t("hidden", { count: hidden.length })}
                </Button>
              )}
              {tab && tab.addDepartments.length > 0 && (
                <Button size="sm" startIcon={<PlusIcon className="size-4" aria-hidden />} onClick={() => setAdding(true)}>
                  {t("add")}
                </Button>
              )}
            </>
          )
        }
      />
      {error && (
        <p role="alert" className="mb-4 text-sm text-error-600 dark:text-error-400">
          {error}
        </p>
      )}
      {!tab ? (
        !error && <p className="text-sm text-gray-500 dark:text-gray-400">{t("loading")}</p>
      ) : tab.boxes.length === 0 ? (
        <Panel>
          <EmptyState icon={<BoxCubeIcon />} title={t("empty")} description={tab.addDepartments.length > 0 ? t("emptyAdd") : undefined} />
        </Panel>
      ) : (
        <BoxGrid
          boxes={visible}
          onOpen={(b) => open(b.id)}
          onOrder={(ids) =>
            change(
              (boxes) => [...ids.flatMap((id) => boxes.filter((b) => b.id === id)), ...boxes.filter((b) => !ids.includes(b.id))],
              () => saveLayoutOrder(ids),
            )
          }
          onSize={(b) => {
            const size = b.size === "LARGE" ? "SMALL" : "LARGE";
            change((boxes) => boxes.map((x) => (x.id === b.id ? { ...x, size } : x)), () => saveLayout(b.id, { size }));
          }}
          onHide={(b) => change((boxes) => boxes.map((x) => (x.id === b.id ? { ...x, hidden: true } : x)), () => saveLayout(b.id, { hidden: true }))}
        />
      )}
      {adding && tab && (
        <AddBoxDialog
          departments={tab.addDepartments}
          onAdded={(box) => {
            load();
            open(box.id);
          }}
          onClose={() => setAdding(false)}
        />
      )}
      {showHidden && (
        <HiddenBoxesDialog
          boxes={hidden}
          onShow={(b) => change((boxes) => boxes.map((x) => (x.id === b.id ? { ...x, hidden: false } : x)), () => saveLayout(b.id, { hidden: false }))}
          onClose={() => setShowHidden(false)}
        />
      )}
      {openBox !== null && (
        <BoxDetailDialog
          key={openBox}
          boxId={openBox}
          tab={detailTab}
          onTab={(x) => open(openBox, x)}
          tick={tick}
          onClose={() => open(null)}
          onChanged={load}
          startEditing={editRequest === openBox}
          onStartedEditing={clearEditRequest}
        />
      )}
    </>
  );
}
