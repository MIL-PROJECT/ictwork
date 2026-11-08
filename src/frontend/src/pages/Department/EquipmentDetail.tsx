import { type AttributeDef, fetchAttributes } from "@/api/attributes";
import { ApiError } from "@/api/client";
import {
  createEquipment,
  type EquipmentStatus,
  type EquipmentView,
  fetchEquipment,
  fetchEquipmentAttributeLoss,
  fetchSelectableLocations,
  type LocationOption,
  updateEquipment,
} from "@/api/equipment";
import { applyAttributeFill, attributeFields, attributeFieldValues } from "@/components/attribute/attributeAssist";
import { AttributeSheet } from "@/components/attribute/AttributeFields";
import { useAssistAdapter, useAssistPage } from "@/components/assist/useAssistAdapter";
import PageBreadcrumb from "@/components/common/PageBreadCrumb";
import PageMeta from "@/components/common/PageMeta";
import { Panel } from "@/components/common/Panel";
import CarryOutDialog from "@/components/equipment/CarryOutDialog";
import { applyEquipmentFill, EQUIPMENT_TEXT_FIELDS, equipmentFieldValues } from "@/components/equipment/equipmentAssist";
import EquipmentForm from "@/components/equipment/EquipmentForm";
import EquipmentMaterials from "@/components/equipment/EquipmentMaterials";
import { emptyValues, type FormValues, inputOf, valuesOf } from "@/components/equipment/equipmentFormValues";
import Badge from "@/components/ui/badge/Badge";
import { AssignedWorkList } from "@/components/work/common";
import { idParam } from "@/components/work/format";
import { useAuth } from "@/context/AuthContext";
import { useOrgData } from "@/hooks/useOrgData";
import { BoltIcon, BoxCubeIcon, ChipIcon, GlobeIcon, GridIcon, ListIcon, MapIcon } from "@/icons";
import { type FormEvent, Fragment, type ReactNode, type SetStateAction, useEffect, useState } from "react";
import { useTranslation } from "react-i18next";
import { Link, useLocation, useNavigate, useParams } from "react-router";

const BUTTON_CLASS =
  "inline-flex h-10 items-center justify-center rounded-lg bg-white px-4 text-sm font-medium text-gray-700 ring-1 ring-gray-300 ring-inset hover:bg-gray-50 disabled:opacity-50 dark:bg-gray-800 dark:text-gray-300 dark:ring-gray-700 dark:hover:bg-white/3";
const PRIMARY_CLASS = "inline-flex h-10 items-center justify-center rounded-lg bg-brand-500 px-4 text-sm font-medium text-white hover:bg-brand-600 disabled:opacity-50";
// 상태 알약 색 (OMNIS 상태 칩): 사용중 초록, 정비입고·불용대기 주황, 고장 빨강, 미사용·불용 회색
// 확인 창에서 취소해 값을 되돌렸다는 알림 (저장 오류 아님)
const KEPT_CODES = new Set(["STATUS_KEPT", "DEPARTMENT_KEPT"]);
const STATUS_COLOR: Record<EquipmentStatus, "success" | "warning" | "error" | "light"> = {
  IN_USE: "success",
  UNUSED: "light",
  MAINTENANCE: "warning",
  BROKEN: "error",
  DISPOSAL_PENDING: "warning",
  DISPOSED: "light",
};

/**
 * 장비 상세·등록 (REQ-501·502·513·520·521, slices/10 9.3·20.4). 보기 → 다룰 수 있는 사람은 고치기·반출 여부 바꾸기.
 * 저장 때 화면을 연 때의 고친 시각을 보내고, 다른 사람이 먼저 고쳤으면 새로 불러와 다시 고치게 한다 (E-39)
 */
export default function EquipmentDetail({ creating = false }: { creating?: boolean }) {
  const { t } = useTranslation("equipment");
  const { t: tf } = useTranslation("frameDept");
  const { user } = useAuth();
  const navigate = useNavigate();
  const location = useLocation();
  const params = useParams();
  // 주소의 번호가 숫자가 아니면(/department/equipment/abc) 서버를 부르지 않고 다른 상세처럼 "찾을 수 없음"
  const id = creating ? null : idParam(params.id);
  const badId = !creating && id === null;
  const navState = location.state as { from?: string; created?: boolean } | null;
  const backTo = navState?.from ?? "/department/equipment";
  const { departments, paths } = useOrgData({});
  const { i18n } = useTranslation();
  // 서버 문구는 한국어다. 영어 화면에서는 코드별 문구를 쓴다(구성도 오류와 같은 방식)
  const serverMessage = (err: ApiError) => (i18n.language.startsWith("en") ? t(`errors.${err.code}`, { defaultValue: t("errors.UNKNOWN") }) : err.message);

  const [equipment, setEquipment] = useState<EquipmentView | null>(null);
  const [loadError, setLoadError] = useState<"detail.notFound" | "detail.loadFailed" | null>(null);
  const [editing, setEditing] = useState(creating);
  const [values, setValues] = useState<FormValues>(() => emptyValues(user?.departmentId ?? null));
  const [locations, setLocations] = useState<LocationOption[]>([]);
  const [locationsRead, setLocationsRead] = useState(false);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<{ message: string; field: string | null; code: string } | null>(null);
  const [notice, setNotice] = useState<string | null>(navState?.created ? t("detail.created") : null);
  const [carryOutOpen, setCarryOutOpen] = useState(false);
  // 추가 특성 (REQ-519): 보기는 그 장비 관리부서, 고칠 때는 고른 관리부서의 특성
  const [attributeDefs, setAttributeDefs] = useState<{ departmentId: number; list: AttributeDef[] } | null>(null);
  // AI 업무지원이 관리부서와 함께 준 새 부서 특성 값: 그 부서 특성 정의를 받은 뒤에 넣는다 (slices/21 12.1 dependsOn 관리부서 → 특성)
  const [pendingAttrs, setPendingAttrs] = useState<{ departmentId: string; values: Record<string, unknown> } | null>(null);
  // 같은 상세 화면에서 주소의 장비가 바뀌면(/department/equipment/5 → 7) 앞 장비의 보기·편집 상태를 버리고 새로 읽는다
  const [shownId, setShownId] = useState(id);
  if (shownId !== id) {
    setShownId(id);
    setEquipment(null);
    setLoadError(null);
    setEditing(creating);
    setValues(emptyValues(user?.departmentId ?? null));
    setError(null);
    setNotice(navState?.created ? t("detail.created") : null);
    setCarryOutOpen(false);
    setPendingAttrs(null);
  }
  const attrDepartmentId = editing ? (values.departmentId === "" ? null : Number(values.departmentId)) : (equipment?.departmentId ?? null);
  useEffect(() => {
    if (attrDepartmentId === null) return;
    let cancelled = false;
    fetchAttributes("equipment", attrDepartmentId).then(
      (res) => !cancelled && setAttributeDefs({ departmentId: attrDepartmentId, list: res.definitions }),
      () => !cancelled && setAttributeDefs({ departmentId: attrDepartmentId, list: [] }),
    );
    return () => {
      cancelled = true;
    };
  }, [attrDepartmentId]);
  const defs = attributeDefs && attributeDefs.departmentId === attrDepartmentId ? attributeDefs.list : [];

  // 관리부서를 바꾸면 원래 부서 특성 값은 서버가 지우므로 칸을 비운다(원래 부서로 되돌리면 지금 값으로)
  const changeValues = (next: SetStateAction<FormValues>) =>
    setValues((prev) => {
      const v = typeof next === "function" ? next(prev) : next;
      if (v.departmentId === prev.departmentId) return v;
      const original = equipment && v.departmentId === String(equipment.departmentId) ? valuesOf(equipment) : emptyValues(null);
      return { ...v, attributes: original.attributes, attrFiles: original.attrFiles };
    });

  // 반출 창에서 충돌·업무 반출 오류가 나면 최신 장비를 다시 읽어 창의 잠금·고친 시각을 맞춘다 (E-39)
  const reloadEquipment = async () => {
    if (id === null) return;
    const fresh = await fetchEquipment(id).catch(() => null);
    if (fresh) setEquipment(fresh);
  };

  useEffect(() => {
    if (id === null) return;
    let cancelled = false;
    fetchEquipment(id).then(
      (e) => {
        if (cancelled) return;
        setEquipment(e);
        setLoadError(null);
      },
      (err) => !cancelled && setLoadError(err instanceof ApiError && err.status === 404 ? "detail.notFound" : "detail.loadFailed"),
    );
    return () => {
      cancelled = true;
    };
    // 문구 키만 담아 언어가 바뀌어도 다시 읽지 않는다(고치는 중 다시 읽으면 고친 시각이 바뀌어 동시 수정을 못 알아챔, E-39)
  }, [id]);

  // 설치장소 고르기 목록: 고칠 때만 (다룰 수 있는 범위가 없으면 서버가 막으므로 빈 목록)
  useEffect(() => {
    if (!editing) return;
    let cancelled = false;
    fetchSelectableLocations().then(
      (list) => {
        if (cancelled) return;
        setLocations(list);
        setLocationsRead(true);
      },
      () => {
        if (cancelled) return;
        setLocations([]);
        setLocationsRead(true);
      },
    );
    return () => {
      cancelled = true;
    };
  }, [editing]);

  const editIds = new Set(user?.equipmentEditDepartmentIds ?? []);
  const managedIds = new Set(user?.equipmentDepartmentIds ?? []);
  const isAdmin = managedIds.size > 0;
  // 관리부서 고르기: 등록은 부서 관리자가 다룰 수 있는 범위에서, 고치기는 옮길 수 있을 때 관리 범위에서 (그 밖은 글로)
  const toOption = (d: { id: number; name: string }) => ({ value: String(d.id), label: paths.get(d.id) ?? d.name });
  const baseOptions = creating
    ? isAdmin
      ? departments.filter((d) => editIds.has(d.id)).map(toOption)
      : null
    : equipment?.movable
      ? departments.filter((d) => managedIds.has(d.id) || d.id === equipment.departmentId).map(toOption)
      : null;
  // 지금 값이 선택지에 없으면(삭제된 부서의 불용 장비, 소속이 없는 부서 관리자의 등록) 그 값을 선택지로 넣어 보이는 값과 실제 값을 맞춘다
  const departmentOptions =
    baseOptions && !baseOptions.some((o) => o.value === values.departmentId)
      ? [
          {
            value: values.departmentId,
            label: values.departmentId === "" ? t("detail.chooseDepartment") : (equipment?.departmentPath ?? paths.get(Number(values.departmentId)) ?? values.departmentId),
          },
          ...baseOptions,
        ]
      : baseOptions;
  // 설치장소 목록을 받기 전·못 받았을 때도 지금 값이 칸에 보이게 그 값을 선택지에 넣는다
  const locationOptions =
    equipment?.storageLocationId != null && !locations.some((l) => l.id === equipment.storageLocationId)
      ? [{ id: equipment.storageLocationId, name: equipment.storageLocationName ?? String(equipment.storageLocationId), departmentId: equipment.departmentId, active: true }, ...locations]
      : locations;
  const carryOutLocked = !!equipment && (equipment.carryOutByWork || (equipment.carryOut === "NONE" && equipment.inWork));
  const canCreate = editIds.size > 0;
  const departmentFixedText = creating ? (user?.departmentId ? (paths.get(user.departmentId) ?? "") : "") : (equipment?.departmentPath ?? "");

  const startEdit = () => {
    if (!equipment) return;
    setValues(valuesOf(equipment));
    setError(null);
    setNotice(null);
    setEditing(true);
  };

  // AI 업무지원 페이지 어댑터 (REQ-C07 장비, slices/21 8장 equipment): 등록 화면은 이미 칸이 열려 있고, 상세는 [고치기]를 누른 것처럼 연다
  // 상세는 주소의 장비를 불러온 뒤에 준비된다(앞 장비가 남은 동안은 준비 전)
  const current = equipment !== null && equipment.id === id ? equipment : null;
  useAssistPage(["equipment"], {
    ready: creating || badId || current !== null || loadError !== null,
    open: (_screen, target) => {
      if (target === null) return creating && canCreate;
      if (creating || !current?.editable || String(current.id) !== String(target)) return false;
      if (!editing) startEdit();
      return true;
    },
  });
  // 창 어댑터: 장비 칸과 고른 관리부서의 추가 특성(attrs.{특성 id}, 파일 특성 제외). [등록]·[저장]은 사용자가 누른다.
  // 관리부서를 AI가 바꾸면 같은 차례의 특성 값은 새 부서 특성 정의를 받은 뒤에 넣는다(그동안 준비 전). 관리부서가 또 바뀌거나 편집을 끝내면 버린다
  if (pendingAttrs && (!editing || values.departmentId !== pendingAttrs.departmentId)) setPendingAttrs(null);
  else if (pendingAttrs && attributeDefs?.departmentId === Number(pendingAttrs.departmentId)) {
    const { list } = attributeDefs;
    setPendingAttrs(null);
    setValues((v) => ({ ...v, attributes: applyAttributeFill(list, v.attributes, pendingAttrs.values) }));
  }
  const attrFields = attributeFields(defs);
  const assistIds: Record<string, string | string[]> = {
    dept: "eq-departmentId",
    location: "eq-storageLocationId",
    status: "eq-status",
    networkKind: "eq-networkKind",
    software: values.software.flatMap((_, i) => [`eq-sw-${i}-name`, `eq-sw-${i}-version`, `eq-sw-${i}-date`]),
    networks: values.networks.flatMap((_, i) => [`eq-net-${i}-name`, `eq-net-${i}-ip`, `eq-net-${i}-mac`]),
    ...Object.fromEntries(Object.entries(EQUIPMENT_TEXT_FIELDS).map(([field, key]) => [field, `eq-${key}`])),
    ...Object.fromEntries(attrFields.map((f) => [f.field, `eq-attr-${f.def.item.key}`])),
  };
  // 서버 오류 칸(화면 칸 이름) → 업무지원 칸 id
  const assistFieldOf = (field: string | null): string | null => {
    if (field === null) return null;
    const base = field.replace(/\[.*$/, "");
    const text = Object.entries(EQUIPMENT_TEXT_FIELDS).find(([, key]) => key === base)?.[0];
    if (text) return text;
    if (base === "departmentId") return "dept";
    if (base === "storageLocationId") return "location";
    if (base === "status" || base === "networkKind" || base === "software" || base === "networks") return base;
    return attrFields.find((f) => f.def.item.key === field)?.field ?? null;
  };
  const assist = useAssistAdapter("equipment", {
    enabled: editing && (!creating || canCreate),
    target: creating ? null : (equipment?.id ?? null),
    // 설치장소 목록과 고른 관리부서의 특성 정의를 받은 뒤 (관리부서가 바뀌면 다시 준비되어 새 특성 칸의 처음 값을 찍는다)
    ready: locationsRead && (attrDepartmentId === null || attributeDefs?.departmentId === attrDepartmentId) && pendingAttrs === null,
    values: { ...equipmentFieldValues(values), ...attributeFieldValues(defs, values.attributes) },
    apply: (v) => {
      const choices = {
        departments: departmentOptions?.map((o) => o.value) ?? null,
        locations: locationOptions.map((l) => l.id),
        original: equipment ? valuesOf(equipment) : null,
      };
      setValues((prev) => {
        const next = applyEquipmentFill(prev, v, choices);
        return next.departmentId === prev.departmentId ? { ...next, attributes: applyAttributeFill(defs, next.attributes, v) } : next;
      });
      const movedTo = applyEquipmentFill(values, v, choices).departmentId;
      const attrs = Object.fromEntries(Object.entries(v).filter(([field]) => field.startsWith("attrs.")));
      if (movedTo !== values.departmentId && Object.keys(attrs).length > 0) setPendingAttrs({ departmentId: movedTo, values: attrs });
    },
    labels: {
      dept: t("fields.DEPARTMENT"),
      location: t("fields.LOCATION"),
      system: t("fields.SYSTEM_NAME"),
      name: t("fields.NAME"),
      model: t("fields.MODEL_NAME"),
      serial: t("fields.SERIAL_NUMBER"),
      commonName: t("fields.COMMON_NAME"),
      status: t("fields.STATUS"),
      hwVersion: t("fields.HW_VERSION"),
      hwInstalledOn: t("fields.HW_INSTALLED_ON"),
      osType: t("fields.OS_TYPE"),
      osVersion: t("fields.OS_VERSION"),
      osInstalledOn: t("fields.OS_INSTALLED_ON"),
      networkKind: t("fields.NETWORK_KIND"),
      introduced: t("fields.INTRODUCED_ON"),
      introProject: t("fields.INTRO_PROJECT"),
      maintProject: t("fields.MAINTENANCE_PROJECT"),
      memo: t("fields.MEMO"),
      software: t("detail.sections.software"),
      networks: t("detail.sections.network"),
      ...Object.fromEntries(attrFields.map((f) => [f.field, f.label])),
    },
    format: (field, value) => {
      if (field === "status" && typeof value === "string") return t(`statuses.${value}`);
      if (field === "networkKind" && typeof value === "string") return t(`networkKinds.${value}`);
      if (field === "dept" && typeof value === "number") return paths.get(value) ?? null;
      if (field === "location" && typeof value === "number") return locationOptions.find((l) => l.id === value)?.name ?? null;
      return null;
    },
    ids: assistIds,
    // 확인 창에서 사용자가 취소한 일(상태·관리부서를 되돌림)은 저장 오류가 아니라 보내지 않는다
    lastError: error && !KEPT_CODES.has(error.code) ? { code: error.code, message: error.message, field: assistFieldOf(error.field) } : null,
  });

  const save = async (e: FormEvent<HTMLFormElement>) => {
    e.preventDefault();
    if (saving) return;
    setSaving(true);
    setError(null);
    try {
      if (creating) {
        const created = await createEquipment(inputOf(values, null));
        navigate(`/department/equipment/${created.id}`, { replace: true, state: { from: backTo, created: true } });
        return;
      }
      if (!equipment) return;
      // 불용으로 바꾸면 저장 전에 확인받는다 (C-5). 취소하면 표 편집 모드처럼 상태만 되돌리고 알린다
      if (values.status === "DISPOSED" && equipment.status !== "DISPOSED" && (assist.pause(), !window.confirm(t("detail.disposeConfirm")))) {
        setValues((v) => ({ ...v, status: equipment.status }));
        setError({ message: t("edit.statusKept"), field: "status", code: "STATUS_KEPT" });
        return;
      }
      // 관리부서를 옮기면 원래 부서 특성 값이 지워진다: 지우기 전에 건수를 알리고 확인받는다 (REQ-501·519). 취소하면 표 편집 모드처럼 관리부서만 되돌리고 알린다
      if (values.departmentId !== String(equipment.departmentId)) {
        const loss = await fetchEquipmentAttributeLoss(equipment.id, Number(values.departmentId)).catch(() => null);
        if (loss && loss.valueCount > 0 && (assist.pause(), !window.confirm(t("detail.attributeLoss", { n: loss.valueCount })))) {
          changeValues((v) => ({ ...v, departmentId: String(equipment.departmentId) }));
          setError({ message: t("edit.departmentKept"), field: "departmentId", code: "DEPARTMENT_KEPT" });
          return;
        }
      }
      const updated = await updateEquipment(equipment.id, inputOf(values, equipment.editedAt, equipment));
      setEquipment(updated);
      setEditing(false);
      setNotice(t("detail.saved"));
    } catch (err) {
      if (err instanceof ApiError && err.code === "EQUIPMENT_EDIT_CONFLICT" && equipment) {
        // 다른 사람이 먼저 고쳤다: 새로 불러와 그 값으로 다시 고치게 한다
        const fresh = await fetchEquipment(equipment.id).catch(() => null);
        if (fresh) {
          setEquipment(fresh);
          setValues(valuesOf(fresh));
        }
        setError({ message: t("detail.conflictReload"), field: null, code: err.code });
      } else if (err instanceof ApiError) {
        // 칸 오류·겹침은 서버 문구가 어느 칸·장비인지 알려 준다
        const known = ["EQUIPMENT_FIELD_INVALID", "EQUIPMENT_SERIAL_DUPLICATE", "EQUIPMENT_MAC_DUPLICATE", "ANSWER_INVALID", "ATTRIBUTE_NOT_OF_DEPARTMENT", "FILE_NOT_TEMP"].includes(err.code);
        setError({ message: known ? serverMessage(err) : t(`errors.${err.code}`, { defaultValue: t("errors.UNKNOWN") }), field: err.field ?? null, code: err.code });
      } else {
        setError({ message: t("errors.UNKNOWN"), field: null, code: "UNKNOWN" });
      }
    } finally {
      setSaving(false);
    }
  };

  const title = creating ? t("detail.newTitle") : (equipment?.displayName ?? t("detail.title"));

  return (
    <div>
      <PageMeta titleKey="sidebar.items.equipment" />
      {/* OMNIS 상세 머리: 제목 아래 요약(상태 알약·관리부서·모델명·시리얼넘버), 오른쪽에 화면 동작(목록·고치기·반출 여부 바꾸기) */}
      <PageBreadcrumb
        pageTitle={title}
        descriptionGuide={creating || editing}
        description={
          creating ? (
            tf("equipment.newDescription")
          ) : editing ? (
            tf("equipment.editDescription")
          ) : equipment ? (
            <span className="inline-flex flex-wrap items-center gap-x-2 gap-y-1">
              <Badge color={STATUS_COLOR[equipment.status]} size="sm">
                {t(`statuses.${equipment.status}`)}
              </Badge>
              <span>{[equipment.departmentPath, equipment.modelName, equipment.serialNumber].filter(Boolean).join(" · ")}</span>
            </span>
          ) : undefined
        }
        actions={
          <>
            <Link to={backTo} className={BUTTON_CLASS}>
              {t("detail.back")}
            </Link>
            {!creating && equipment?.editable && !editing && (
              <>
                {/* 업무가 정했거나 업무 계획·결재 중 제안에 든 장비는 단추를 막고 이유를 보인다 (slices/10 9.3) */}
                <button type="button" onClick={() => setCarryOutOpen(true)} disabled={carryOutLocked} aria-describedby={carryOutLocked ? "carry-out-locked" : undefined} className={BUTTON_CLASS}>
                  {t("detail.carryOutChange")}
                </button>
                <button type="button" onClick={startEdit} className={PRIMARY_CLASS}>
                  {t("detail.edit")}
                </button>
              </>
            )}
          </>
        }
      />
      {!creating && equipment?.editable && !editing && carryOutLocked && (
        <p id="carry-out-locked" className="-mt-3 mb-4 text-end text-sm text-gray-500 dark:text-gray-400">
          {equipment.carryOutByWork ? t("carryOut.byWorkNote") : t("carryOut.inWorkNote")}
        </p>
      )}
      {creating && !canCreate && (
        <p role="alert" className="mb-4 text-sm text-error-600 dark:text-error-400">
          {t("errors.ACCESS_DENIED")}
        </p>
      )}
      {notice && (
        <p role="status" className="mb-4 text-sm text-success-600 dark:text-success-500">
          {notice}
        </p>
      )}
      {(badId || loadError) && (
        <p role="alert" className="text-sm text-error-600 dark:text-error-400">
          {t(badId ? "detail.notFound" : (loadError ?? "detail.notFound"))}
        </p>
      )}

      {editing && (!creating || canCreate) ? (
        <form onSubmit={save} noValidate className="space-y-5">
          <EquipmentForm
            values={values}
            onChange={changeValues}
            departmentOptions={departmentOptions}
            departmentFixedText={departmentFixedText}
            locations={locationOptions}
            creating={creating}
            errorField={error?.field ?? null}
            errorId="eq-error"
            attributeDefs={defs}
            assist={assist}
          />
          {error && (
            <p id="eq-error" role="alert" className="text-sm text-error-600 dark:text-error-400">
              {error.message}
            </p>
          )}
          {/* OMNIS 대화상자 바닥처럼 오른쪽 정렬 동작 줄 + 옅은 띠 */}
          <div className="flex justify-end gap-3 rounded-[20px] border-t border-gray-100 bg-gray-50 px-[22px] py-3 dark:border-gray-800 dark:bg-white/3">
            <button
              type="button"
              onClick={() => (creating ? navigate(backTo) : setEditing(false))}
              className={BUTTON_CLASS}
            >
              {t("detail.cancel")}
            </button>
            <button type="submit" disabled={saving} className={PRIMARY_CLASS}>
              {saving ? t("detail.saving") : t("detail.save")}
            </button>
          </div>
        </form>
      ) : (
        equipment && (
          <div className="space-y-4">
            <EquipmentSheet equipment={equipment} attributeDefs={defs} />
            {/* 장비 사진·관련자료 (REQ-520·521): 항목 고치기와 따로 저장되므로 보기 화면에만 */}
            <EquipmentMaterials key={equipment.id} equipmentId={equipment.id} name={equipment.displayName} editable={equipment.editable} />
          </div>
        )
      )}

      {carryOutOpen && equipment && (
        <CarryOutDialog
          equipment={equipment}
          departments={departments}
          paths={paths}
          onClose={() => setCarryOutOpen(false)}
          onDone={(updated) => setEquipment(updated)}
          onStale={() => void reloadEquipment()}
        />
      )}
    </div>
  );
}

// 장비 보기: 항목을 묶어 보인다(기본·위치·HW/OS·SW·네트워크·상태와 반출). 구역마다 OMNIS 카드(아이콘 + 제목),
// 안은 OMNIS 이름표·값 격자(.dd-grid: 2열, 이름표 11.5px 굵게 위·값 13.5px 아래), 긴 글(비고·반출 수정사유)은 두 칸을 차지하는 옅은 상자
function EquipmentSheet({ equipment: e, attributeDefs }: { equipment: EquipmentView; attributeDefs: AttributeDef[] }) {
  const { t } = useTranslation("equipment");
  const row = (label: string, value: ReactNode, long = false) => (
    <div className={long ? "flex min-w-0 flex-col gap-1 sm:col-span-2" : "flex min-w-0 flex-col gap-1"}>
      <dt className="text-[11.5px] font-bold text-gray-600 dark:text-gray-400">{label}</dt>
      <dd
        className={
          long
            ? "rounded-lg border border-gray-100 bg-gray-50 px-3 py-2.5 text-[13.5px] break-words text-gray-700 dark:border-gray-800 dark:bg-white/3 dark:text-white/90"
            : "text-[13.5px] break-words text-gray-700 dark:text-white/90"
        }
      >
        {value === "" || value === null ? "—" : value}
      </dd>
    </div>
  );
  // empty: 목록이 비었을 때의 문구(dl 밖에 둔다)
  const card = (icon: ReactNode, title: string, children: ReactNode, empty?: string) => (
    <Panel icon={icon} title={title}>
      {empty ? <p className="text-sm text-gray-500 dark:text-gray-400">{empty}</p> : <dl className="grid grid-cols-1 gap-x-5 gap-y-3 sm:grid-cols-2">{children}</dl>}
    </Panel>
  );
  const note = "rounded-lg border border-gray-100 bg-gray-50 px-3 py-2.5 text-sm text-gray-600 dark:border-gray-800 dark:bg-white/3 dark:text-gray-400";
  return (
    <div className="space-y-4">
      {e.status === "DISPOSED" && <p className={note}>{t("detail.disposedNote")}</p>}
      {e.inWork && <p className={note}>{t("detail.inWorkNote")}</p>}
      <div className="grid gap-4 lg:grid-cols-2">
        {card(
          <BoxCubeIcon />,
          t("detail.sections.basic"),
          <>
            {row(t("fields.DEPARTMENT"), e.departmentPath)}
            {row(t("fields.SYSTEM_NAME"), e.systemName)}
            {row(t("fields.NAME"), e.name)}
            {row(t("fields.MODEL_NAME"), e.modelName)}
            {row(t("fields.SERIAL_NUMBER"), e.serialNumber)}
            {row(t("fields.COMMON_NAME"), e.commonName)}
            {row(t("fields.QUANTITY"), t("list.quantityOne"))}
            {row(t("fields.MEMO"), e.memo, true)}
          </>,
        )}
        {card(
          <BoltIcon />,
          t("detail.sections.state"),
          <>
            {row(t("fields.STATUS"), t(`statuses.${e.status}`))}
            {row(t("fields.CARRY_OUT"), `${t(`carryOuts.${e.carryOut}`)}${e.carryOut !== "NONE" ? ` (${e.carryOutByWork ? t("carryOutByWork") : t("carryOutByPerson")})` : ""}`)}
            {row(t("fields.CURRENT_LOCATION"), [e.currentDepartmentPath, e.currentPlace].filter(Boolean).join(" "))}
            {row(
              t("fields.CARRY_OUT_REASON"),
              e.carryOutReason
                ? `${e.carryOutReason} — ${e.carryOutByName ? t("detail.carryOutBy", { name: e.carryOutByName, at: e.carryOutAt?.replace("T", " ").slice(0, 16) }) : t("detail.carryOutBySystem", { at: e.carryOutAt?.replace("T", " ").slice(0, 16) })}`
                : "",
              true,
            )}
            {row(t("fields.ASSIGNED_WORK"), e.assignedWork.length === 0 ? "" : <AssignedWorkList items={e.assignedWork} />, true)}
          </>,
        )}
        {card(
          <MapIcon />,
          t("detail.sections.place"),
          <>
            {row(t("fields.LOCATION"), e.storageLocationName)}
            {row(t("fields.NETWORK_KIND"), e.networkKind ? t(`networkKinds.${e.networkKind}`) : "")}
            {row(t("fields.INTRODUCED_ON"), e.introducedOn)}
            {row(t("fields.INTRO_PROJECT"), e.introProject)}
            {row(t("fields.MAINTENANCE_PROJECT"), e.maintenanceProject)}
          </>,
        )}
        {card(
          <ChipIcon />,
          t("detail.sections.system"),
          <>
            {row(t("fields.HW_VERSION"), e.hwVersion)}
            {row(t("fields.HW_INSTALLED_ON"), e.hwInstalledOn)}
            {row(t("fields.OS_TYPE"), e.osType)}
            {row(t("fields.OS_VERSION"), e.osVersion)}
            {row(t("fields.OS_INSTALLED_ON"), e.osInstalledOn)}
          </>,
        )}
        {card(
          <GridIcon />,
          t("detail.sections.software"),
          e.software.map((s, i) => <Fragment key={i}>{row(s.name, [s.version, s.installedOn].filter(Boolean).join(" · "))}</Fragment>),
          e.software.length === 0 ? t("software.none") : undefined,
        )}
        {card(
          <GlobeIcon />,
          t("detail.sections.network"),
          e.networks.map((n, i) => <Fragment key={i}>{row(n.name ?? t("network.name"), [n.ip, n.mac].filter(Boolean).join(" · "))}</Fragment>),
          e.networks.length === 0 ? t("network.none") : undefined,
        )}
        {attributeDefs.length > 0 && (
          <Panel icon={<ListIcon />} title={t("detail.sections.attributes")} className="lg:col-span-2">
            <AttributeSheet definitions={attributeDefs} values={e.attributes} files={e.attributeFiles} />
          </Panel>
        )}
      </div>
    </div>
  );
}
