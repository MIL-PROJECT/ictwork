import {
  type AttributeConfig,
  type AttributeDef,
  type AttributeDepartment,
  type AttributeList,
  type AttributeRevision,
  type AttributeRevisionDetail,
  type AttributeTarget,
  createAttribute,
  deleteAttribute,
  fetchAttributeDepartments,
  fetchAttributeRevision,
  fetchAttributeRevisions,
  fetchAttributes,
  fetchAttributeUsage,
  reorderAttributes,
  restoreAttribute,
  updateAttribute,
} from "@/api/attributes";
import { ApiError } from "@/api/client";
import DataTable, { RowButton } from "@/components/admin/DataTable";
import PageBreadcrumb from "@/components/common/PageBreadCrumb";
import { EmptyState, Panel } from "@/components/common/Panel";
import PageMeta from "@/components/common/PageMeta";
import Button from "@/components/ui/button/Button";
import { Modal } from "@/components/ui/modal";
import { INPUT_CLASS, LABEL_CLASS } from "@/components/work/format";
import { ItemEditor } from "@/components/workform/FormEditor";
import { InfoIcon, ListIcon, PencilIcon, PlusIcon, TimeIcon } from "@/icons";
import { cn } from "@/utils";
import { emptyItem, type FormItem, itemProblem, length } from "@/components/workform/questions";
import { useCallback, useEffect, useState } from "react";
import { useTranslation } from "react-i18next";
import { Link } from "react-router";

const BUTTON_CLASS =
  "inline-flex h-[34px] items-center justify-center rounded-lg bg-white px-3.5 text-sm font-medium text-gray-700 ring-1 ring-gray-300 ring-inset hover:bg-gray-50 disabled:opacity-50 dark:bg-gray-800 dark:text-gray-300 dark:ring-gray-700 dark:hover:bg-white/3";

/** 질문 하나에서 서버에 보낼 설정(이름·종류는 따로) */
const configOf = (item: FormItem): AttributeConfig => {
  const { key: _key, kind: _kind, title: _title, ...config } = item;
  void _key;
  void _kind;
  void _title;
  return config;
};

/**
 * 관리 ▸ 장비관리·인원관리 안의 "추가 특성 관리" (REQ-405·519, slices/11 3.7.7, G-70): 부서 관리자가 역할의 대상 부서(하위 부서 제외)의
 * 장비·인원 특성을 더하고 고치고 순서를 바꾸고 삭제한다(지울 값 건수를 알리고 확인). 바꿀 때마다 판이 남고(최근 20개),
 * 삭제 판에서 특성 하나씩 되살린다. 같은 부서 관리자가 동시에 고치면 나중 저장은 거부된다(setVersion, G-66)
 */
export default function AttributeManage({ target }: { target: AttributeTarget }) {
  const { t, i18n } = useTranslation("attribute");
  const { t: tw } = useTranslation("workform");
  const { t: tf } = useTranslation("frameAdmin");
  const [departments, setDepartments] = useState<AttributeDepartment[] | null>(null);
  const [departmentId, setDepartmentId] = useState<number | null>(null);
  const [list, setList] = useState<AttributeList | null>(null);
  const [revisions, setRevisions] = useState<AttributeRevision[] | null>(null);
  const [failed, setFailed] = useState(false);
  const [message, setMessage] = useState<{ text: string; error: boolean } | null>(null);
  // original: 편집을 연 때의 특성. 취소할 때 저장하지 않은 변경이 있으면 묻는다(양식 편집기 닫기와 같게, G-83)
  const [editing, setEditing] = useState<{ id: number | null; item: FormItem; hasValues: boolean; original: FormItem } | null>(null);
  const [editError, setEditError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [revision, setRevision] = useState<AttributeRevisionDetail | null>(null);

  // 서버 문구는 한국어다(어느 칸이 틀렸는지 알려 줌). 영어 화면에서는 코드별 문구를 쓴다
  const describe = useCallback(
    (err: unknown) =>
      err instanceof ApiError && err.code === "ATTRIBUTE_INVALID" && !i18n.language.startsWith("en")
        ? err.message
        : t(`errors.${err instanceof ApiError ? err.code : "UNKNOWN"}`, { defaultValue: t("errors.UNKNOWN") }),
    [i18n.language, t],
  );

  useEffect(() => {
    let cancelled = false;
    fetchAttributeDepartments(target).then(
      (res) => {
        if (cancelled) return;
        setDepartments(res);
        setDepartmentId(res[0]?.id ?? null);
      },
      () => !cancelled && setFailed(true),
    );
    return () => {
      cancelled = true;
    };
  }, [target]);

  const load = useCallback(
    () =>
      departmentId === null
        ? Promise.resolve()
        : Promise.all([fetchAttributes(target, departmentId), fetchAttributeRevisions(target, departmentId)]).then(
            ([defs, revs]) => {
              setList(defs);
              setRevisions(revs);
              setFailed(false);
            },
            () => setFailed(true),
          ),
    [target, departmentId],
  );
  useEffect(() => {
    void load();
  }, [load]);

  const backTo = target === "equipment" ? "/admin/equipment" : "/admin/personnel";
  const title = t(`title.${target}`);
  const setVersion = list?.setVersion ?? 0;

  // 쓰기 뒤: 목록은 응답으로, 판은 다시 읽는다. 동시 수정 거부(409)면 새로 불러온다
  const run = async (action: () => Promise<AttributeList>, done: string) => {
    setBusy(true);
    setMessage(null);
    try {
      setList(await action());
      setMessage({ text: done, error: false });
      if (departmentId !== null) setRevisions(await fetchAttributeRevisions(target, departmentId).catch(() => revisions));
      return true;
    } catch (err) {
      setMessage({ text: describe(err), error: true });
      if (err instanceof ApiError && err.code === "ATTRIBUTE_CONFLICT") void load();
      return false;
    } finally {
      setBusy(false);
    }
  };

  const openEditor = async (def: AttributeDef | null) => {
    setEditError(null);
    setMessage(null);
    if (!def) {
      const item = { ...emptyItem("SHORT_TEXT"), key: "attr" };
      setEditing({ id: null, item, hasValues: false, original: item });
      return;
    }
    // 값이 있는 특성은 종류를 바꾸지 못한다(새로 만들고 옛 것은 삭제, REQ-405·519)
    const usage = await fetchAttributeUsage(target, def.id).catch(() => null);
    const item = { ...def.item, title: def.name };
    setEditing({ id: def.id, item, hasValues: (usage?.valueCount ?? 0) > 0, original: item });
  };

  const cancelEditor = () => {
    if (editing && JSON.stringify(editing.item) !== JSON.stringify(editing.original) && !window.confirm(t("cancelConfirm"))) return;
    setEditing(null);
  };

  const saveEditor = async () => {
    if (!editing || departmentId === null) return;
    const name = editing.item.title.trim();
    // 서버(ATTRIBUTE_INVALID)와 같은 규칙을 화면에서 먼저 본다(영어 화면도 무엇이 틀렸는지 알게): 글자 수는 코드 포인트로 세고,
    // 괄호로 끝나는 이름·체크박스 선택지의 ;는 엑셀 열 이름·구분자와 겹쳐 막는다 (REQ-519)
    const problem = length(name) > 50 ? { message: "problems.titleTooLong", values: { max: 50 } } : itemProblem({ ...editing.item, title: name });
    const rule =
      name.endsWith(")") && name.includes("(") ? "nameParen" : editing.item.kind === "CHECKBOXES" && editing.item.options.some((o) => o.label.includes(";")) ? "optionSemicolon" : null;
    const problemText = problem ? tw(problem.message, problem.values) : rule ? t(`problems.${rule}`) : null;
    // 고쳐 다시 저장하면 앞 오류는 지운다
    setEditError(problemText);
    if (problemText) return;
    const { item } = editing;
    const ok = await run(
      () => (editing.id === null ? createAttribute(target, departmentId, name, item.kind as never, configOf(item), setVersion) : updateAttribute(target, editing.id, name, item.kind as never, configOf(item), setVersion)),
      t(editing.id === null ? "added" : "saved"),
    );
    if (ok) setEditing(null);
  };

  const move = (index: number, delta: number) => {
    if (!list || departmentId === null) return;
    const ids = list.definitions.map((d) => d.id);
    [ids[index], ids[index + delta]] = [ids[index + delta], ids[index]];
    void run(() => reorderAttributes(target, departmentId, ids, setVersion), t("reordered"));
  };

  const remove = async (def: AttributeDef) => {
    const usage = await fetchAttributeUsage(target, def.id).catch(() => null);
    // 지울 값 건수를 알리고 확인받는다 (REQ-405·519)
    if (!window.confirm(t("deleteConfirm", { name: def.name, n: usage?.valueCount ?? 0 }))) return;
    void run(() => deleteAttribute(target, def.id, setVersion), t("deleted", { name: def.name }));
  };

  const openRevision = async (r: AttributeRevision) => {
    try {
      setRevision(await fetchAttributeRevision(target, r.id));
    } catch (err) {
      setMessage({ text: describe(err), error: true });
    }
  };

  const restore = async () => {
    if (!revision?.deleted) return;
    const ok = await run(() => restoreAttribute(target, revision.summary.id, revision.deleted?.id ?? 0, setVersion), t("restored", { name: revision.deleted?.name ?? "" }));
    if (ok) setRevision(null);
  };

  const canEdit = list?.canEdit ?? false;
  const kindLabel = (kind: string) => tw(`kinds.${kind}`);
  const actionText = (r: AttributeRevision) => t(`actions.${r.action}`, { name: r.attributeName ?? "" });

  return (
    <div>
      <PageMeta titleKey="attribute:title.page" />
      {/* OMNIS 페이지 머리: 설명 한 줄 + 오른쪽 화면 동작(돌아가기·특성 더하기) */}
      <PageBreadcrumb
        pageTitle={title}
        description={t(`help.${target}`)}
        actions={
          <>
            <Link to={backTo} className={BUTTON_CLASS}>
              {t(`back.${target}`)}
            </Link>
            {!editing && canEdit && (
              <Button size="sm" startIcon={<PlusIcon className="size-4" aria-hidden="true" />} onClick={() => void openEditor(null)}>
                {t("add")}
              </Button>
            )}
          </>
        }
      />
      {departments !== null && departments.length === 0 && (
        <Panel>
          <EmptyState icon={<InfoIcon className="[&_path]:fill-current" />} title={t("noDepartment")} />
        </Panel>
      )}
      {failed && (
        <p role="alert" className="mb-4 rounded-xl bg-error-50 px-4 py-3 text-[13px] text-error-600 ring-1 ring-error-500/20 ring-inset dark:bg-error-500/10 dark:text-error-400">
          {t("loadFailed")}
        </p>
      )}
      {message && (
        <p
          role={message.error ? "alert" : "status"}
          className={`mb-4 rounded-xl px-4 py-3 text-[13px] ring-1 ring-inset ${message.error ? "bg-error-50 text-error-600 ring-error-500/20 dark:bg-error-500/10 dark:text-error-400" : "bg-success-50 text-success-700 ring-success-500/25 dark:bg-success-500/10 dark:text-success-500"}`}
        >
          {message.text}
        </p>
      )}

      {/* 입력 카드: 특성 하나를 더하거나 고친다. 아래 동작 줄은 OMNIS 대화상자 바닥처럼 옅은 띠 */}
      {editing && (
        <Panel
          className="mb-6"
          icon={<PencilIcon />}
          title={t(editing.id === null ? "addTitle" : "editTitle")}
          titleId="attr-editor-title"
          subtitle={editing.hasValues ? t("kindLocked") : undefined}
          bodyClassName="space-y-3"
          footer={
            <div className="flex justify-end gap-2 border-t border-gray-100 bg-gray-50 px-[22px] py-3.5 dark:border-gray-800 dark:bg-white/3">
              <button type="button" onClick={cancelEditor} className={BUTTON_CLASS}>
                {t("cancel")}
              </button>
              <Button size="sm" disabled={busy} onClick={() => void saveEditor()}>
                {busy ? t("saving") : t("save")}
              </Button>
            </div>
          }
        >
          <ItemEditor
            typeId={0}
            item={editing.item}
            invalid={editError !== null}
            targets={[]}
            mode="ATTRIBUTE"
            titleLabel={t("name")}
            onChange={(item) => setEditing({ ...editing, item: editing.hasValues ? { ...item, kind: editing.item.kind } : item })}
            actions={null}
          />
          {editError && (
            <p role="alert" className="text-sm text-error-600 dark:text-error-400">
              {editError}
            </p>
          )}
        </Panel>
      )}

      {/* 목록 = 카드 하나: 머리(아이콘·제목·부서·건수) + 걸러 보기 줄(부서 고르기) + 꽉 찬 표 */}
      {departmentId !== null && (
        <Panel
          icon={<ListIcon />}
          title={tf("attributes.listTitle")}
          titleId="attr-list-title"
          subtitle={
            <>
              {departments?.length === 1 && <span className="font-medium text-gray-600 dark:text-gray-300">{departments[0].path} · </span>}
              {list && tf("attributes.summary", { count: list.definitions.length })}
            </>
          }
          toolbar={
            departments !== null &&
            departments.length > 1 && (
              <div className="flex flex-wrap items-center gap-2">
                <label htmlFor="attr-department" className={cn(LABEL_CLASS, "mb-0")}>
                  {t("department")}
                </label>
                <select
                  id="attr-department"
                  value={departmentId ?? ""}
                  onChange={(e) => {
                    setList(null);
                    setRevisions(null);
                    setEditing(null);
                    setDepartmentId(Number(e.target.value));
                  }}
                  className={cn(INPUT_CLASS, "w-auto min-w-60")}
                >
                  {departments.map((d) => (
                    <option key={d.id} value={d.id}>
                      {d.path}
                    </option>
                  ))}
                </select>
              </div>
            )
          }
          flush
        >
          <DataTable
            flush
            caption={title}
            columns={[t("name"), t("kind"), t("description"), ...(canEdit ? [t("actionsColumn")] : [])]}
            emptyText={list === null ? t("loading") : t("empty")}
            rows={(list?.definitions ?? []).map((d, index, all) => ({
              key: d.id,
              cells: [
                d.name,
                kindLabel(d.kind),
                d.item.description,
                ...(canEdit
                  ? [
                      <span key="actions">
                        <RowButton onClick={() => void openEditor(d)} disabled={busy}>
                          {t("edit")}
                        </RowButton>
                        <RowButton onClick={() => move(index, -1)} disabled={busy || index === 0}>
                          {t("up")}
                        </RowButton>
                        <RowButton onClick={() => move(index, 1)} disabled={busy || index === all.length - 1}>
                          {t("down")}
                        </RowButton>
                        <RowButton onClick={() => void remove(d)} disabled={busy}>
                          {t("delete")}
                        </RowButton>
                      </span>,
                    ]
                  : []),
              ],
            }))}
          />
        </Panel>
      )}

      {/* 판: 그 부서의 부서 관리자만 본다 (G-58). 순서 바꾸기는 판을 남기지 않는다 */}
      {departmentId !== null && (
        <Panel className="mt-6" icon={<TimeIcon />} title={t("revisions")} titleId="attr-revisions-title" subtitle={t("revisionsHelp")} flush>
          <DataTable
            flush
            caption={t("revisions")}
            columns={[t("revisionNo"), t("change"), t("changedBy"), t("changedAt"), ""]}
            emptyText={revisions === null ? t("loading") : t("noRevisions")}
            rows={(revisions ?? []).map((r) => ({
              key: r.id,
              cells: [
                String(r.revisionNo),
                actionText(r),
                r.changedByName,
                r.changedAt.replace("T", " ").slice(0, 16),
                <RowButton key="view" onClick={() => void openRevision(r)}>
                  {t("view")}
                  <span className="sr-only"> {t("revisionNo")} {r.revisionNo}</span>
                </RowButton>,
              ],
            }))}
          />
        </Panel>
      )}

      {revision && (
        <Modal isOpen onClose={() => setRevision(null)} labelledBy="attr-revision-title" className="custom-scrollbar max-h-[90vh] max-w-2xl overflow-y-auto">
          {/* OMNIS 대화상자: 제목 줄 → 내용(여백 22) → 바닥 띠(닫기) */}
          <h2 id="attr-revision-title" className="px-[22px] pt-6 pe-16 text-[17px] leading-snug font-bold tracking-[-0.3px] text-gray-900 sm:pe-20 dark:text-white/90">
            {t("revisionTitle", { n: revision.summary.revisionNo })}
          </h2>
          <div className="space-y-4 px-[22px] pt-2 pb-6">
            <p className="text-[13px] text-gray-500 tabular-nums dark:text-gray-400">
              {`${actionText(revision.summary)} · ${revision.summary.changedByName} · ${revision.summary.changedAt.replace("T", " ").slice(0, 16)}`}
            </p>
            <div>
              <h3 className="mb-1.5 text-[11.5px] font-bold text-gray-600 dark:text-gray-400">{t("revisionDefinitions")}</h3>
              {revision.definitions.length === 0 ? (
                <p className="text-sm text-gray-500 dark:text-gray-400">{t("empty")}</p>
              ) : (
                <ul className="list-disc space-y-0.5 rounded-lg bg-gray-50 py-2.5 ps-8 pe-3 text-[13.5px] text-gray-700 dark:bg-white/5 dark:text-gray-300">
                  {revision.definitions.map((d) => (
                    <li key={d.id}>{`${d.name} (${kindLabel(d.kind)})`}</li>
                  ))}
                </ul>
              )}
            </div>
            {revision.deleted && (
              <div className="rounded-xl bg-warning-50 px-4 py-3 ring-1 ring-warning-500/25 ring-inset dark:bg-warning-500/10">
                <p className="text-[13.5px] text-gray-800 dark:text-white/90">
                  {t("deletedAttribute", { name: revision.deleted.name, kind: kindLabel(revision.deleted.kind), n: revision.deletedValueCount })}
                </p>
                {revision.summary.restorable && canEdit && (
                  <div className="mt-2 space-y-2">
                    <p className="text-theme-xs text-gray-500 dark:text-gray-400">{t("restoreHelp")}</p>
                    <Button size="sm" disabled={busy} onClick={() => void restore()}>
                      {t("restore")}
                    </Button>
                  </div>
                )}
              </div>
            )}
          </div>
          <div className="sticky bottom-0 flex justify-end border-t border-gray-100 bg-gray-50 px-[22px] py-3.5 dark:border-gray-800 dark:bg-gray-900">
            <button type="button" onClick={() => setRevision(null)} className={BUTTON_CLASS}>
              {t("close")}
            </button>
          </div>
        </Modal>
      )}
    </div>
  );
}
