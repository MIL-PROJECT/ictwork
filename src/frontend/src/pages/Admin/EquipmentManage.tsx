import { createLocation, deactivateLocation, fetchLocations, type LocationItem, renameLocation } from "@/api/admin";
import ActionModal from "@/components/admin/ActionModal";
import { AssistSelectField, AssistTextField } from "@/components/admin/AssistFields";
import { assistErrorOf } from "@/components/admin/assistError";
import type { AssistError } from "@/api/assist";
import DataTable, { RowButton } from "@/components/admin/DataTable";
import { useAssistAdapter, useAssistPage } from "@/components/assist/useAssistAdapter";
import PageBreadcrumb from "@/components/common/PageBreadCrumb";
import { Panel } from "@/components/common/Panel";
import PageMeta from "@/components/common/PageMeta";
import EquipmentBrowser from "@/components/equipment/EquipmentBrowser";
import Button from "@/components/ui/button/Button";
import { useAuth } from "@/context/AuthContext";
import { MapIcon, PlusIcon } from "@/icons";
import { useOrgData } from "@/hooks/useOrgData";
import { useCallback, useEffect, useState } from "react";
import { useTranslation } from "react-i18next";
import { Link } from "react-router";

type Dialog = { kind: "locationCreate" } | { kind: "locationRename"; location: LocationItem } | { kind: "locationDeactivate"; location: LocationItem };

// 관리 ▸ 장비관리 (REQ-501·502·512, slices/10 9.4): 관리 범위 장비 목록(불용이 늘 보임)과 설치장소 관리.
// 장비 등록·고치기·불용 처리·관리부서 옮기기는 장비 상세 화면이나 표 편집 모드(REQ-518)에서 한다. "추가 특성 관리" 단추로 특성 정의 화면에 간다(REQ-519)
export default function EquipmentManage() {
  const { t } = useTranslation();
  const { t: ta } = useTranslation("admin");
  const { t: tf } = useTranslation("frameAdmin");
  const { user } = useAuth();
  const { departments, paths } = useOrgData({});
  const [locations, setLocations] = useState<LocationItem[] | null>(null);
  const [failed, setFailed] = useState(false);
  const [dialog, setDialog] = useState<Dialog | null>(null);
  const [name, setName] = useState("");
  const [departmentId, setDepartmentId] = useState("");
  // 설치장소를 바꾸면(이름 바꾸기·사용 중지) 위 장비 표의 설치장소·위치도 바뀌므로 장비 표도 다시 읽는다
  const [equipmentReloads, setEquipmentReloads] = useState(0);

  const load = useCallback(
    () =>
      fetchLocations().then(
        (list) => {
          setLocations(list);
          setFailed(false);
        },
        () => setFailed(true),
      ),
    [],
  );
  useEffect(() => {
    void load();
  }, [load]);

  // 설치장소는 부서 관리자 역할로 관리하는 부서에 등록한다 (DEC-039·059)
  const managed = new Set(user?.equipmentDepartmentIds ?? []);
  const departmentOptions = departments.filter((d) => managed.has(d.id)).map((d) => ({ value: String(d.id), label: paths.get(d.id) ?? d.name }));
  const managedLocations = (locations ?? []).filter((l) => managed.has(l.departmentId));

  const afterLocationChange = () => {
    setEquipmentReloads((n) => n + 1);
    return load();
  };

  // AI 업무지원 lastError: 설치장소 창의 마지막 저장 오류
  const [saveError, setSaveError] = useState<AssistError | null>(null);
  const open = (next: Dialog) => {
    setName(next.kind === "locationRename" ? next.location.name : "");
    setDepartmentId("");
    setSaveError(null);
    setDialog(next);
  };

  // AI 업무지원 (REQ-C07 장비관리, slices/21 8장 equipmentAdmin.location): 설치장소 등록 창(대상 없음)·이름 바꾸기 창(대상 설치장소, 관리하는 사용 중 설치장소만).
  // [저장]은 사용자가 누른다
  useAssistPage(["equipmentAdmin.location"], {
    ready: locations !== null || failed,
    open: (_screen, target) => {
      if (target === null) {
        if (departmentOptions.length === 0) return false;
        open({ kind: "locationCreate" });
        return true;
      }
      const location = managedLocations.find((l) => String(l.id) === String(target));
      if (!location?.active) return false;
      open({ kind: "locationRename", location });
      return true;
    },
  });
  const locationDialog = dialog?.kind === "locationCreate" || dialog?.kind === "locationRename" ? dialog : null;
  const assist = useAssistAdapter("equipmentAdmin.location", {
    enabled: locationDialog !== null,
    target: locationDialog?.kind === "locationRename" ? locationDialog.location.id : null,
    ready: true,
    // 이름 바꾸기 창은 부서를 고르지 않는다: 칸은 이름만(서버도 대상이 있으면 dept를 내지 않음)
    values: locationDialog?.kind === "locationRename" ? { name } : { dept: departmentId === "" ? null : Number(departmentId), name },
    apply: (v) => {
      if (locationDialog?.kind === "locationCreate" && typeof v.dept === "number" && departmentOptions.some((o) => o.value === String(v.dept))) setDepartmentId(String(v.dept));
      if (typeof v.name === "string") setName([...v.name].slice(0, 50).join(""));
    },
    labels: { dept: ta("columns.department"), name: ta("columns.locationName") },
    format: (field, value) => (field === "dept" && typeof value === "number" ? (paths.get(value) ?? null) : null),
    ids: { dept: "location-department", name: "location-name" },
    lastError: saveError,
  });

  return (
    <div>
      <PageMeta titleKey="sidebar.items.equipmentManage" />
      {/* OMNIS 페이지 머리: 설명 한 줄 + 오른쪽 화면 동작. 추가 특성 만들기 화면은 장비관리 안의 단추로 연다 (REQ-519, G-70) */}
      <PageBreadcrumb
        pageTitle={t("sidebar.items.equipmentManage")}
        description={tf("equipment.description")}
        actions={
          user?.features.includes("ADMIN_ATTRIBUTE") && (
            <Link
              to="/admin/attributes/equipment"
              className="inline-flex h-[34px] items-center justify-center rounded-lg bg-white px-3.5 text-sm font-medium text-gray-700 ring-1 ring-gray-300 ring-inset hover:bg-gray-50 dark:bg-gray-800 dark:text-gray-300 dark:ring-gray-700 dark:hover:bg-white/3"
            >
              {ta("attributeManage")}
            </Link>
          )
        }
      />
      <EquipmentBrowser source={{ kind: "MANAGED" }} storageKind="manage" defaultStatus="" reloadKey={equipmentReloads} />

      {/* 설치장소 = 카드 하나: 머리(아이콘·제목·안내 / 등록 단추) + 꽉 찬 표 */}
      <Panel
        className="mt-6"
        icon={<MapIcon />}
        title={ta("location.title")}
        titleId="location-list-title"
        subtitle={
          <>
            {locations && <span className="block">{tf("location.summary", { total: managedLocations.length, active: managedLocations.filter((l) => l.active).length })}</span>}
            <span className="block">{ta("location.help")}</span>
          </>
        }
        actions={
          <Button size="sm" variant="outline" startIcon={<PlusIcon className="size-4" aria-hidden="true" />} onClick={() => open({ kind: "locationCreate" })}>
            {ta("location.add")}
          </Button>
        }
        flush
      >
        <DataTable
          flush
          caption={ta("location.title")}
          columns={[ta("columns.locationName"), ta("columns.department"), ta("columns.inUse"), ta("columns.actions")]}
          emptyText={failed ? ta("loadFailed") : locations ? ta("empty") : ta("loading")}
          error={failed && locations ? ta("loadFailed") : undefined}
          rows={managedLocations.map((l) => ({
            key: l.id,
            inactive: !l.active,
            cells: [
              l.name,
              paths.get(l.departmentId) ?? "",
              ta(l.active ? "status.active" : "status.inactive"),
              <span key="actions">
                {l.active && (
                  <>
                    <RowButton onClick={() => open({ kind: "locationRename", location: l })}>{ta("location.rename")}</RowButton>
                    <RowButton onClick={() => open({ kind: "locationDeactivate", location: l })}>{ta("location.deactivate")}</RowButton>
                  </>
                )}
              </span>,
            ],
          }))}
        />
      </Panel>

      {(dialog?.kind === "locationCreate" || dialog?.kind === "locationRename") && (
        <ActionModal
          title={ta(dialog.kind === "locationCreate" ? "location.add" : "location.rename")}
          description={ta("location.help")}
          submitLabel={ta("save")}
          canSubmit={name.trim() !== "" && (dialog.kind === "locationRename" || departmentId !== "")}
          onClose={() => setDialog(null)}
          onSubmit={() => (dialog.kind === "locationCreate" ? createLocation(name, Number(departmentId)) : renameLocation(dialog.location.id, name))}
          onDone={afterLocationChange}
          onError={(err, message) => setSaveError(assistErrorOf(err, message, { name: "name", departmentId: "dept" }))}
        >
          {dialog.kind === "locationCreate" && (
            <AssistSelectField
              assist={assist}
              field="dept"
              id="location-department" required
              label={ta("columns.department")}
              value={departmentId}
              placeholder={ta("choose")}
              options={departmentOptions}
              onChange={setDepartmentId}
            />
          )}
          <AssistTextField id="location-name" required label={ta("columns.locationName")} value={name} onChange={setName} assist={assist} field="name" />
        </ActionModal>
      )}

      {dialog?.kind === "locationDeactivate" && (
        <ActionModal
          title={ta("location.deactivate")}
          description={ta("location.deactivateConfirm", { name: dialog.location.name })}
          submitLabel={ta("location.deactivate")}
          danger
          onClose={() => setDialog(null)}
          onSubmit={() => deactivateLocation(dialog.location.id)}
          onDone={afterLocationChange}
        />
      )}
    </div>
  );
}
