import { createOperation, deleteOperation, fetchOperation, fetchOperations, updateOperation, type OperationDetail, type OperationItem, type OperationList, type OperationStatus } from "@/api/operations";
import PageBreadcrumb from "@/components/common/PageBreadCrumb";
import PageMeta from "@/components/common/PageMeta";
import { Modal } from "@/components/ui/modal";
import { Meter, Pill } from "@/components/board/ui";
import { operationStates, operationStats, operationTone, filterOperations, prioritizeOperations } from "@/components/board/operationModel";
import "@/components/board/board.css";
import "./operations.css";
import { useAuth } from "@/context/AuthContext";
import { useEffect, useId, useState } from "react";
import { createPortal } from "react-dom";
import { useTranslation } from "react-i18next";
import { useSearchParams } from "react-router";

type Form = { item?: OperationItem; statusOnly: boolean; departmentId: string; code: string; name: string; category: string; status: OperationStatus; uptimePct: string; remarks: string };
const message = (error: unknown) => error instanceof Error ? error.message : String(error);

export default function Operations() {
  const { user } = useAuth();
  return <OperationsContent key={JSON.stringify([user?.loginId,user?.departmentId,user?.features,user?.managedDepartmentIds])} />;
}

function OperationsContent() {
  const { t, i18n } = useTranslation("operations");
  const [params, setParams] = useSearchParams();
  const [data, setData] = useState<OperationList | null>(null);
  const [error, setError] = useState("");
  const [tick, setTick] = useState(0);
  const [form, setForm] = useState<Form | null>(null);
  const [busy, setBusy] = useState(false);
  const [formError, setFormError] = useState("");
  const [detailResult, setDetailResult] = useState<{ id: number; data?: OperationDetail; error?: string } | null>(null);
  const [confirmDelete, setConfirmDelete] = useState(false);
  const gradient = useId().replace(/:/g, "");
  const selected = Number(params.get("system")) || null;
  const query = params.get("q") ?? "";
  const status = params.get("status") ?? "";
  const attention = params.get("attention") === "1";
  const detail = detailResult?.id === selected ? detailResult.data : undefined;
  const detailError = detailResult?.id === selected ? detailResult.error : undefined;
  const patchParams = (patch: Record<string, string | null>) => {
    const next = new URLSearchParams(params);
    Object.entries(patch).forEach(([key,value]) => value === null || value === "" ? next.delete(key) : next.set(key,value));
    setParams(next);
  };
  useEffect(() => {
    const controller = new AbortController();
    fetchOperations(AbortSignal.any([controller.signal,AbortSignal.timeout(15000)])).then(value => {
      if (!controller.signal.aborted) { setData(value); setError(""); }
    }, err => { if (!controller.signal.aborted) { setData(null); setError(message(err)); } });
    return () => controller.abort();
  }, [tick]);
  useEffect(() => {
    if (!selected) return;
    const controller = new AbortController();
    fetchOperation(selected,AbortSignal.any([controller.signal,AbortSignal.timeout(15000)])).then(value => {
      if (!controller.signal.aborted) setDetailResult({ id: selected, data: value });
    }, err => { if (!controller.signal.aborted) setDetailResult({ id: selected, error: message(err) }); });
    return () => controller.abort();
  }, [selected,tick]);
  useEffect(() => {
    if (form || selected) return;
    const id = window.setInterval(() => { if (document.visibilityState === "visible") setTick(value => value + 1); },30000);
    return () => window.clearInterval(id);
  }, [form,selected]);

  const stats = data ? operationStats(data.items) : null;
  const filtered = prioritizeOperations(filterOperations(data?.items ?? [],query,status,attention));
  const pages = Math.max(1,Math.ceil(filtered.length / 10));
  const page = Math.min(pages,Math.max(1,Math.floor(Number(params.get("page"))) || 1));
  const openForm = (item?: OperationItem, statusOnly = false) => {
    setFormError("");setConfirmDelete(false);
    if (selected) patchParams({ system: null });
    setForm({ item,statusOnly,departmentId:String(item?.departmentId ?? data?.createDepartments[0]?.id ?? ""),code:item?.code ?? "",name:item?.name ?? "",category:item?.category ?? "기반통신",status:item?.status ?? "NORMAL",uptimePct:String(item?.uptimePct ?? 100),remarks:item?.remarks ?? "" });
  };
  const save = async () => {
    if (!form || busy) return;
    if (!/^\d{1,3}(\.\d{1,2})?$/.test(form.uptimePct) || Number(form.uptimePct) > 100) { setFormError(t("uptimeInvalid"));return; }
    setBusy(true);setFormError("");
    try {
      const body = { departmentId:Number(form.departmentId),code:form.code,name:form.name,category:form.category,status:form.status,uptimePct:Number(form.uptimePct),remarks:form.remarks,version:form.item?.version };
      const result = form.item ? await updateOperation(form.item.id,body,form.statusOnly) : await createOperation(body);
      setForm(null);setTick(value => value + 1);setDetailResult({ id:result.item.id,data:result });patchParams({ system:String(result.item.id) });
    } catch (err) { setFormError(err instanceof Error && err.name === "TimeoutError" ? t("saveUncertain") : message(err)); }
    finally { setBusy(false); }
  };
  const remove = async () => {
    if (!detail || busy) return;
    setBusy(true);setFormError("");
    try { await deleteOperation(detail.item.id,detail.item.version);setConfirmDelete(false);patchParams({ system:null });setTick(value => value + 1); }
    catch (err) { setFormError(err instanceof Error && err.name === "TimeoutError" ? t("saveUncertain") : message(err)); }
    finally { setBusy(false); }
  };
  const closeDetail = () => { if (!busy) { setConfirmDelete(false);setFormError("");patchParams({ system:null }); } };
  const time = (value: string) => new Date(value).toLocaleString(i18n.language,{hour12:false});
  const history = [...(detail?.history ?? [])].reverse();
  const points = history.map((entry,index) => `${history.length < 2 ? 280 : 40+index*480/(history.length-1)},${125-entry.uptimePct}`).join(" ");
  return <div className="board ops-page">
    <PageMeta titleKey="sidebar.items.operations" />
    <PageBreadcrumb pageTitle={t("title")} description={t("description")} actions={<><button type="button" className="board-btn" onClick={() => setTick(value => value + 1)}>{t("refresh")}</button>{!!data?.createDepartments.length && <button type="button" className="board-btn ops-primary" onClick={() => openForm()}>{t("create")}</button>}</>} />
    {error && <p role="alert" className="ops-error">{error} <button type="button" onClick={() => setTick(value => value + 1)}>{t("retry")}</button></p>}
    <section className="ops-panel" aria-label={t("systems")}>
      <div className="ops-toolbar"><h2>{t("systems")} · {data ? data.items.length : "—"}</h2><label>{t("search")}<input type="search" value={query} onChange={event => patchParams({ q:event.target.value,page:null })} /></label></div>
      <div className="ops-filters"><button className={!status && !attention ? "active" : ""} type="button" onClick={() => patchParams({ status:null,attention:null,page:null })}>{t("all")} {stats?.total ?? "—"}</button>{operationStates.map(state => <button className={state === status ? "active" : ""} type="button" key={state} onClick={() => patchParams({ status:state,attention:null,page:null })}>{t(`status.${state}`)} {stats?.counts[state] ?? "—"}</button>)}{attention && <span>{t("attentionFilter")}</span>}</div>
      {!data ? <p role="status" className="ops-empty">{error ? t("unavailable") : t("loading")}</p> : <>
        <div className="ops-table-scroll"><table className="ops-table"><thead><tr>{["code","name","department","category","state","uptime","remarks","actions"].map(key => <th key={key} scope="col">{t(key)}</th>)}</tr></thead>
          <tbody>{filtered.slice((page-1)*10,page*10).map(item => <tr key={item.id}><td>{item.code}</td><td><button type="button" className="ops-name" onClick={() => {setConfirmDelete(false);setFormError("");patchParams({system:String(item.id)});}}>{item.name}</button></td><td>{item.departmentLabel}</td><td>{item.category}</td><td><Pill tone={operationTone[item.status]}>{t(`status.${item.status}`)}</Pill></td><td><Meter value={item.uptimePct} tone={item.uptimePct >= 97 ? "green" : item.uptimePct >= 95 ? "sky" : "orange"} /></td><td>{item.remarks || "—"}</td><td>{item.canManage && <button type="button" onClick={() => openForm(item)}>{t("edit")}</button>}</td></tr>)}</tbody>
        </table>{!filtered.length && <p className="ops-empty">{data.items.length ? t("noMatch") : t("empty")}</p>}</div>
        <div className="ops-paging"><span>{t("count",{count:filtered.length})}</span><button type="button" disabled={page === 1} onClick={() => patchParams({page:String(page-1)})}>{t("previous")}</button><span>{page}/{pages}</span><button type="button" disabled={page === pages} onClick={() => patchParams({page:String(page+1)})}>{t("next")}</button></div>
      </>}
    </section>
    {form && createPortal(<Modal isOpen onClose={() => {if(!busy)setForm(null);}} labelledBy="operation-form-title" className="board ops-dialog">
      <h2 id="operation-form-title">{t(form.item ? form.statusOnly ? "changeStatus" : "edit" : "create")}</h2>
      <form onSubmit={event => {event.preventDefault();void save();}}><div className="ops-fields">
        {!form.statusOnly && <><label>{t("department")}<select aria-label={t("department")} required disabled={!!form.item} value={form.departmentId} onChange={event => setForm({...form,departmentId:event.target.value})}>{form.item ? <option value={form.departmentId}>{form.item.departmentLabel}</option> : data?.createDepartments.map(department => <option key={department.id} value={department.id}>{department.label}</option>)}</select></label><label>{t("code")}<input aria-label={t("code")} required maxLength={64} disabled={!!form.item} value={form.code} onChange={event => setForm({...form,code:event.target.value})} /></label><label>{t("name")}<input aria-label={t("name")} required maxLength={100} value={form.name} onChange={event => setForm({...form,name:event.target.value})} /></label><label>{t("category")}<input aria-label={t("category")} required maxLength={60} value={form.category} onChange={event => setForm({...form,category:event.target.value})} /></label></>}
        {(!form.item || form.statusOnly) && <label>{t("state")}<select aria-label={t("state")} value={form.status} onChange={event => setForm({...form,status:event.target.value as OperationStatus})}>{operationStates.map(state => <option key={state} value={state}>{t(`status.${state}`)}</option>)}</select></label>}
        <label>{t("uptime")} (%)<input aria-label={`${t("uptime")} (%)`} required type="number" min="0" max="100" step="0.01" value={form.uptimePct} onChange={event => setForm({...form,uptimePct:event.target.value})} /></label><label>{t("remarks")}<textarea aria-label={t("remarks")} maxLength={2000} value={form.remarks} onChange={event => setForm({...form,remarks:event.target.value})} /></label>
      </div><p className="ops-help">{t("manualRate")}</p>{formError && <p role="alert" className="ops-error">{formError}</p>}
      <div className="ops-actions"><button type="button" className="board-btn" disabled={busy} onClick={() => setForm(null)}>{t("cancel")}</button><button className="board-btn ops-primary" type="submit" disabled={busy}>{t("save")}</button></div></form>
    </Modal>,document.body)}
    {selected && !form && createPortal(<Modal isOpen onClose={closeDetail} labelledBy="operation-detail-title" className="board ops-dialog ops-detail">
      <h2 id="operation-detail-title">{detail?.item.name ?? t("detail")}</h2>
      {!detail ? <p role={detailError ? "alert" : "status"}>{detailError ?? t("loading")} {detailError && <button type="button" onClick={() => setTick(value => value + 1)}>{t("retry")}</button>}</p> : <>
        <p>{detail.item.code} · {detail.item.departmentLabel} · {detail.item.category}</p><div className="ops-detail-summary"><Pill tone={operationTone[detail.item.status]}>{t(`status.${detail.item.status}`)}</Pill><Meter value={detail.item.uptimePct} tone="green" /><span>{t("updated")} {time(detail.item.updatedAt)} · {detail.item.updatedByName}</span></div>
        <p className="ops-help">{t("manualRate")}</p><p className="ops-remarks">{detail.item.remarks}</p><h3>{t("history")}</h3>
        <svg role="img" aria-label={t("chart")} viewBox="0 0 560 155" className="ops-chart"><title>{t("chart")}</title><defs><linearGradient id={gradient} x1="0" y1="0" x2="0" y2="1"><stop offset="0%" stopColor="#2fb872" stopOpacity=".3" /><stop offset="100%" stopColor="#2fb872" stopOpacity="0" /></linearGradient></defs>{[0,50,100].map(value => <g key={value}><line x1="40" y1={125-value} x2="520" y2={125-value} stroke="currentColor" opacity=".15" /><text x="5" y={129-value}>{value}%</text></g>)}{history.length > 0 && <>{history.length > 1 && <polygon points={`40,125 ${points} 520,125`} fill={`url(#${gradient})`} />}<polyline points={points} fill="none" stroke="#2fb872" strokeWidth="2" />{history.map((entry,index) => <circle key={entry.id} cx={history.length<2?280:40+index*480/(history.length-1)} cy={125-entry.uptimePct} r="3" fill="#2fb872"><title>{time(entry.recordedAt)} · {entry.uptimePct}%</title></circle>)}</>}<text x="220" y="150">{t("changeOrder")}</text></svg>
        <div className="ops-table-scroll"><table className="ops-table"><thead><tr>{["updated","state","uptime","editor","remarks"].map(key => <th key={key}>{t(key)}</th>)}</tr></thead><tbody>{detail.history.map(entry => <tr key={entry.id}><td>{time(entry.recordedAt)}</td><td>{t(`status.${entry.status}`)}</td><td>{entry.uptimePct}%</td><td>{entry.recordedByName}</td><td>{entry.remarks || "—"}</td></tr>)}</tbody></table></div>
        {formError && <p role="alert" className="ops-error">{formError}</p>}
        {confirmDelete && <div role="alert" className="ops-delete-confirm"><p>{t("deleteConfirm")}</p><button type="button" className="board-btn" disabled={busy} onClick={() => setConfirmDelete(false)}>{t("cancel")}</button><button type="button" className="board-btn" disabled={busy} onClick={() => void remove()}>{t("confirmDelete")}</button></div>}
        <div className="ops-actions">{detail.item.canManage && <button type="button" className="board-btn" disabled={busy} onClick={() => setConfirmDelete(true)}>{t("delete")}</button>}<button type="button" className="board-btn" onClick={closeDetail} disabled={busy}>{t("close")}</button>{detail.item.canUpdateStatus && <button type="button" className="board-btn ops-primary" disabled={busy} onClick={() => openForm(detail.item,true)}>{t("changeStatus")}</button>}</div>
      </>}
    </Modal>,document.body)}
  </div>;
}
