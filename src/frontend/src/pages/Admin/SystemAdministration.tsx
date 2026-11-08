import { apiFetch } from "@/api/client";
import { departmentPaths, fetchDepartments, type Department } from "@/api/org";
import { adminJson, rankLabels, registrationStatus, roleLabels, systemCall, systemAdminApi, type AiConfiguration, type AuditEntry, type PageResult, type Registration, type SecurityPolicy, type SystemAccount } from "@/api/systemAdmin";
import PageMeta from "@/components/common/PageMeta";
import { Modal } from "@/components/ui/modal";
import { useAuth } from "@/context/AuthContext";
import { useEffect, useState, type FormEvent, type ReactNode } from "react";
import { Link, useSearchParams } from "react-router";
import "./system-admin.css";

function useResource<T>(path: string) {
  const [revision, setRevision] = useState(0);
  const key = `${path}\u0000${revision}`;
  const [response, setResponse] = useState<{ key: string; data: T | null; error: string } | null>(null);
  useEffect(() => {
    const controller = new AbortController();
    apiFetch<T>(path, { signal: controller.signal }).then(result => {
      if (!controller.signal.aborted) setResponse({ key, data: result, error: "" });
    }, e => {
      if (!controller.signal.aborted) setResponse({ key, data: null, error: e instanceof Error ? e.message : "자료를 불러오지 못했습니다." });
    });
    return () => controller.abort();
  }, [path, key]);
  const current = response?.key === key ? response : null;
  return { data: current?.data ?? null, error: current?.error ?? "", loading: current === null, reload: () => setRevision(v => v + 1) };
}

function Notice({ error, loading }: { error: string; loading: boolean }) {
  return error ? <p className="admin-error" role="alert">{error}</p> : loading ? <p className="admin-note" role="status">불러오는 중…</p> : null;
}
function Pager({ page, total, onChange }: { page: number; total: number; onChange: (page: number) => void }) {
  return <div className="admin-pagination"><span>전체 {total}개 · {page + 1} / {Math.max(1, Math.ceil(total / 25))}쪽</span><button className="admin-secondary" disabled={page === 0} onClick={() => onChange(page - 1)}>이전</button><button className="admin-secondary" disabled={(page + 1) * 25 >= total} onClick={() => onChange(page + 1)}>다음</button></div>;
}
function DepartmentSelect({ departments, value, onChange }: { departments: Department[]; value: string; onChange: (value: string) => void }) {
  const paths = departmentPaths(departments);
  return <label>소속 / 대상 부서<select value={value} onChange={e => onChange(e.target.value)} required><option value="">부서 선택</option>{departments.map(d => <option key={d.id} value={d.id}>{paths.get(d.id) ?? d.name}</option>)}</select></label>;
}
function RoleSelect({ value, onChange }: { value: string; onChange: (value: string) => void }) {
  return <label>관리 역할<select value={value} onChange={e => onChange(e.target.value)}>{Object.entries(roleLabels).map(([key, label]) => <option key={key} value={key}>{label}</option>)}</select><small>일반 사용자는 기존 업무 권한을 유지합니다. 부서 관리자는 지정한 부서를, 최고 관리자는 체계 전체를 관리합니다.</small></label>;
}

function ConfirmAction({ title, children, onClose, onApply }: { title: string; children?: ReactNode; onClose: () => void; onApply: (password: string, reason: string) => Promise<void> }) {
  const [password, setPassword] = useState(""), [reason, setReason] = useState(""), [busy, setBusy] = useState(false), [error, setError] = useState("");
  async function submit(event: FormEvent) {
    event.preventDefault(); setBusy(true); setError("");
    try { await onApply(password, reason); onClose(); } catch (e) { setError(e instanceof Error ? e.message : "처리하지 못했습니다."); } finally { setBusy(false); }
  }
  return <Modal isOpen onClose={() => { if (!busy) onClose(); }} className="system-admin admin-dialog" labelledBy="admin-action-title">
    <h2 id="admin-action-title">{title}</h2><form className="admin-form" onSubmit={event => void submit(event)}>{children}
      <label>처리 / 변경 사유<textarea required minLength={2} maxLength={300} value={reason} onChange={e => setReason(e.target.value)} rows={2} /></label>
      <label>현재 관리자 비밀번호<input required type="password" autoComplete="current-password" maxLength={128} value={password} onChange={e => setPassword(e.target.value)} /><small>현재 로그인한 관리자 계정의 비밀번호로 확인합니다.</small></label>
      {error && <p role="alert" className="admin-error">{error}</p>}
      <div className="admin-actions"><button type="button" className="admin-secondary" onClick={onClose} disabled={busy}>취소</button><button type="submit" className="admin-primary" disabled={busy}>{busy ? "처리 중…" : "확인"}</button></div>
    </form>
  </Modal>;
}

function Overview() {
  const resource = useResource<Record<string, number>>(systemAdminApi + "/summary");
  const labels: Record<string, string> = { accounts: "전체 계정", active: "사용 중", pending: "가입 승인 대기", manualLocked: "관리자 잠금", autoLocked: "실패 잠금", passwordChange: "비밀번호 변경 대기" };
  return <><Notice {...resource} /><div className="admin-summary">{Object.entries(labels).map(([key, title]) => <div className="admin-surface" key={key}><span>{title}</span><strong>{resource.data?.[key] ?? "—"}</strong></div>)}</div>
    <section className="admin-surface"><h2>조직·업무 관리</h2><p className="admin-description">기존 조직·인원·장비·양식·결재 기능으로 바로 이동합니다.</p><div className="admin-links">{[
      ["조직 관리", "/admin/organization"], ["인원·기존 역할 관리", "/admin/personnel"], ["장비·설치장소 관리", "/admin/equipment"], ["업무 유형·양식", "/admin/work-types"], ["인원 추가 특성", "/admin/attributes/personnel"], ["장비 추가 특성", "/admin/attributes/equipment"], ["기본 결재 경로", "/admin/approval-routes"],
    ].map(([title, href]) => <Link key={href} to={href}>{title}<span aria-hidden>↗</span></Link>)}</div></section></>;
}

function Accounts() {
  const { user } = useAuth();
  const [search, setSearch] = useState(""), [query, setQuery] = useState(""), [state, setState] = useState("ALL"), [page, setPage] = useState(0);
  const resource = useResource<PageResult<SystemAccount>>(systemAdminApi + "/accounts?" + new URLSearchParams({ search: query, state, page: String(page) }));
  const [selected, setSelected] = useState<SystemAccount | null>(null), [action, setAction] = useState("PROFILE"), [role, setRole] = useState("USER"), [department, setDepartment] = useState("");
  const [name, setName] = useState(""), [rank, setRank] = useState(""), [contact, setContact] = useState(""), [serviceNo, setServiceNo] = useState("");
  const [departments, setDepartments] = useState<Department[]>([]);
  useEffect(() => { let active = true; fetchDepartments().then(value => { if (active) setDepartments(value); }).catch(() => undefined); return () => { active = false; }; }, []);
  function open(row: SystemAccount) {
    setSelected(row); setAction("PROFILE"); setRole(row.roles.some(r => r.roleCode === "SYS_ADMIN") ? "SYS_ADMIN" : row.roles.some(r => r.roleCode === "DEPT_ADMIN") ? "DEPT_ADMIN" : "USER");
    setDepartment(String(row.departmentId)); setName(row.displayName); setRank(row.rankCode); setContact(row.contact ?? ""); setServiceNo(row.serviceNo ?? "");
  }
  return <section className="admin-surface"><h2>사용자·계정 관리</h2><p className="admin-description">잠금·활성 상태·관리 역할·초기화를 관리합니다. 자기 계정과 마지막 최고 관리자는 보호됩니다.</p>
    <form className="admin-toolbar admin-form" onSubmit={event => { event.preventDefault(); setQuery(search); setPage(0); }}>
      <input aria-label="계정 검색" value={search} maxLength={100} placeholder="성명·아이디·부서 검색" onChange={e => setSearch(e.target.value)} />
      <select aria-label="계정 상태" value={state} onChange={e => { setState(e.target.value); setPage(0); }}>{Object.entries({ ALL: "전체", ACTIVE: "사용 중", INACTIVE: "사용 중지", MANUAL_LOCK: "관리자 잠금", AUTO_LOCK: "실패 잠금", PASSWORD_CHANGE: "비밀번호 변경 대기" }).map(([value, label]) => <option key={value} value={value}>{label}</option>)}</select>
      <button className="admin-primary" type="submit">검색</button><button className="admin-secondary" type="button" onClick={resource.reload}>새로고침</button>
    </form><Notice {...resource} /><div className="admin-table-wrap"><table className="admin-table"><thead><tr>{["사용자", "소속", "관리 역할", "상태", "최근 로그인", "작업"].map(label => <th key={label}>{label}</th>)}</tr></thead><tbody>{resource.data?.items.map(row => <tr key={row.id}>
      <td>{row.displayName}<small>{row.loginId} · {rankLabels[row.rankCode] ?? row.rankCode}</small></td><td>{row.departmentName}<small>{row.serviceNo || "군번 미등록"}</small></td>
      <td>{row.roles.filter(r => r.roleCode === "SYS_ADMIN" || r.roleCode === "DEPT_ADMIN").map(r => roleLabels[r.roleCode]).join(" · ") || "일반 사용자"}</td>
      <td>{!row.active ? "사용 중지" : row.manualLocked ? "관리자 잠금" : row.lockUntil && new Date(row.lockUntil) > new Date() ? "실패 잠금" : "사용 중"}{row.passwordChangeRequired && <small>비밀번호 변경 필요</small>}</td>
      <td>{row.lastLoginAt ? new Date(row.lastLoginAt).toLocaleString() : "—"}</td><td><button className="admin-secondary" onClick={() => open(row)}>관리</button></td>
    </tr>)}</tbody></table></div>{resource.data && <Pager page={page} total={resource.data.total} onChange={setPage} />}
    {selected && <ConfirmAction key={selected.id} title={`${selected.displayName} · ${selected.loginId}`} onClose={() => setSelected(null)} onApply={async (adminPassword, reason) => {
      await systemCall(`/accounts/${selected.id}`, adminJson("POST", { expectedVersion: selected.version, action, adminPassword, reason, role, departmentId: department ? Number(department) : null, displayName: name, rankCode: rank, contact, serviceNo })); resource.reload();
    }}><label>관리 작업<select value={action} onChange={e => setAction(e.target.value)}>{Object.entries({ PROFILE: "사용자 정보 수정", ROLE: "관리 역할 변경", LOCK: "계정 잠금", UNLOCK: "잠금 해제", DISABLE: "사용 중지", ENABLE: "다시 사용", CLEAR_AUTO_LOCK: "로그인 실패 잠금 해제", RESET_PASSWORD: "비밀번호 초기화" }).filter(([key]) => selected.id !== user?.accountId || key === "PROFILE").map(([key, label]) => <option key={key} value={key}>{label}</option>)}</select></label>
      {action === "ROLE" && <RoleSelect value={role} onChange={setRole} />}
      {(action === "PROFILE" || action === "ROLE" && role === "DEPT_ADMIN") && <DepartmentSelect departments={departments} value={department} onChange={setDepartment} />}
      {action === "PROFILE" && <><label>성명<input value={name} onChange={e => setName(e.target.value)} required minLength={2} maxLength={50} /></label><label>계급<select value={rank} onChange={e => setRank(e.target.value)}>{Object.entries(rankLabels).map(([key, label]) => <option key={key} value={key}>{label}</option>)}</select></label><label>군번<input value={serviceNo} onChange={e => setServiceNo(e.target.value)} maxLength={20} /></label><label>연락처<input value={contact} onChange={e => setContact(e.target.value)} maxLength={30} /></label></>}
      {action === "RESET_PASSWORD" && <p className="admin-note">공통 초기 비밀번호로 초기화하며, 다음 로그인에서 비밀번호 변경을 요구합니다. 기존 세션은 종료됩니다.</p>}
    </ConfirmAction>}
  </section>;
}

function Registrations() {
  const resource = useResource<Registration[]>(systemAdminApi + "/registrations");
  const [filter, setFilter] = useState("PENDING"), [selected, setSelected] = useState<Registration | null>(null), [approve, setApprove] = useState(true), [role, setRole] = useState("USER"), [department, setDepartment] = useState("");
  const [departments, setDepartments] = useState<Department[]>([]);
  useEffect(() => { let active = true; fetchDepartments().then(value => { if (active) setDepartments(value); }).catch(() => undefined); return () => { active = false; }; }, []);
  function open(row: Registration, approval: boolean) { setSelected(row); setApprove(approval); setRole("USER"); setDepartment(""); }
  return <section className="admin-surface"><h2>사용자 등록 신청</h2><p className="admin-description">신청한 소속을 확인하고 실제 부서와 관리 역할을 지정합니다. 승인 전에는 로그인 계정이 생성되지 않습니다.</p>
    <div className="admin-toolbar admin-form"><select aria-label="신청 상태" value={filter} onChange={e => setFilter(e.target.value)}><option value="">전체</option>{Object.entries(registrationStatus).map(([key, label]) => <option key={key} value={key}>{label}</option>)}</select><button className="admin-secondary" onClick={resource.reload}>새로고침</button></div><Notice {...resource} />
    <div className="admin-table-wrap"><table className="admin-table"><thead><tr>{["신청자", "신청 소속", "연락처·신청 사유", "상태", "처리"].map(label => <th key={label}>{label}</th>)}</tr></thead><tbody>{resource.data?.filter(row => !filter || row.status === filter).map(row => <tr key={row.id}>
      <td>{row.displayName} · {rankLabels[row.rankCode]}<small>{row.loginId} / {row.serviceNo}</small><small>{new Date(row.createdAt).toLocaleString()}</small></td><td>{row.unitName}</td><td className="wrap">{row.contact || "—"}<small>{row.reason || "사유 미기재"}</small></td><td><span className={`admin-badge ${row.status === "PENDING" ? "pending" : ""}`}>{registrationStatus[row.status]}</span><small>{row.rejectReason}</small></td>
      <td>{row.status === "PENDING" ? <div className="admin-actions"><button className="admin-primary" onClick={() => open(row, true)}>승인</button><button className="admin-danger" onClick={() => open(row, false)}>반려</button></div> : <>{row.reviewerName}<small>{row.approvedRole && roleLabels[row.approvedRole]}</small></>}</td>
    </tr>)}</tbody></table></div>
    {selected && <ConfirmAction key={`${selected.id}-${approve}`} title={`${selected.displayName} · 등록 신청 ${approve ? "승인" : "반려"}`} onClose={() => setSelected(null)} onApply={async (adminPassword, reason) => {
      await systemCall(`/registrations/${selected.id}/review`, adminJson("POST", { expectedVersion: selected.version, approve, departmentId: department ? Number(department) : null, role, reason, adminPassword })); resource.reload();
    }}>{approve && <><DepartmentSelect departments={departments} value={department} onChange={setDepartment} /><RoleSelect value={role} onChange={setRole} /></>}</ConfirmAction>}
  </section>;
}

function Security() {
  const resource = useResource<SecurityPolicy>(systemAdminApi + "/security");
  const [editing, setEditing] = useState(false), [limit, setLimit] = useState(5), [minutes, setMinutes] = useState(15);
  return <section className="admin-surface"><h2>로그인 보안 정책</h2><p className="admin-description">연속 실패 횟수와 자동 잠금 시간을 설정합니다. 승인·권한 변경·정책 변경은 관리자 재인증과 감사 기록을 남깁니다.</p><Notice {...resource} />
    {resource.data && <><div className="admin-note">로그인 실패 {resource.data.failedLimit}회 → {resource.data.lockMinutes}분 잠금 · 정책 버전 {resource.data.version}</div><button className="admin-primary" onClick={() => { setLimit(resource.data!.failedLimit); setMinutes(resource.data!.lockMinutes); setEditing(true); }}>정책 변경</button></>}
    {editing && resource.data && <ConfirmAction title="로그인 보안 정책 변경" onClose={() => setEditing(false)} onApply={async (adminPassword, reason) => { await systemCall("/security", adminJson("PUT", { expectedVersion: resource.data!.version, failedLimit: limit, lockMinutes: minutes, adminPassword, reason })); resource.reload(); }}>
      <label>실패 허용 횟수<input type="number" min={3} max={10} value={limit} onChange={e => setLimit(Number(e.target.value))} required /></label><label>잠금 시간(분)<input type="number" min={1} max={60} value={minutes} onChange={e => setMinutes(Number(e.target.value))} required /></label>
    </ConfirmAction>}
  </section>;
}

function Audit() {
  const [search, setSearch] = useState(""), [query, setQuery] = useState(""), [page, setPage] = useState(0);
  const resource = useResource<PageResult<AuditEntry>>(systemAdminApi + "/audit?" + new URLSearchParams({ search: query, page: String(page) }));
  return <section className="admin-surface"><h2>감사 기록</h2><p className="admin-description">로그인·등록 신청·승인·계정·정책·AI 설정 변경 기록입니다. 비밀번호와 API 키는 기록하지 않습니다.</p>
    <form className="admin-toolbar admin-form" onSubmit={e => { e.preventDefault(); setQuery(search); setPage(0); }}><input aria-label="감사 기록 검색" value={search} onChange={e => setSearch(e.target.value)} maxLength={100} placeholder="작업·대상·처리자 검색" /><button className="admin-primary">검색</button><button type="button" className="admin-secondary" onClick={resource.reload}>새로고침</button></form><Notice {...resource} />
    <div className="admin-table-wrap"><table className="admin-table"><thead><tr>{["시각", "처리자", "작업", "대상", "결과·사유"].map(label => <th key={label}>{label}</th>)}</tr></thead><tbody>{resource.data?.items.map(row => <tr key={row.id}><td>{new Date(row.createdAt).toLocaleString()}</td><td>{row.actorName || "로그인 전 / 시스템"}</td><td>{row.action}</td><td>{row.target}</td><td className="wrap">{row.outcome}<small>{row.reason}</small></td></tr>)}</tbody></table></div>{resource.data && <Pager page={page} total={resource.data.total} onChange={setPage} />}
  </section>;
}

function AiSettings() {
  const { refresh } = useAuth();
  const resource = useResource<AiConfiguration>(systemAdminApi + "/ai");
  const [editing, setEditing] = useState(false), [draft, setDraft] = useState<AiConfiguration["settings"] | null>(null), [keyProvider, setKeyProvider] = useState("OPENAI"), [key, setKey] = useState(""), [clearKey, setClearKey] = useState(false), [test, setTest] = useState(""), [testing, setTesting] = useState(false);
  async function testConnection() { setTesting(true); setTest(""); try { const result = await systemCall<{ reachable: boolean; provider: string }>("/ai/test", adminJson("POST", {})); setTest(`${result.provider} · ${result.reachable ? "연결 확인" : "연결하지 못했습니다. 설정과 실행기를 확인하세요."}`); } catch (e) { setTest(e instanceof Error ? e.message : "연결 시험 실패"); } finally { setTesting(false); } }
  return <section className="admin-surface"><h2>AI 제공자·실행 설정</h2><p className="admin-description">기존 OMNIS의 로컬 규칙·Ollama·OpenAI·Anthropic 선택을 제공합니다. 기본 LOCAL은 모델·API 키 없이 체계 자료와 조회 도구로 답합니다.</p><Notice {...resource} />
    {resource.data && <><div className="admin-note">현재 {resource.data.settings.provider} · {resource.data.settings.enabled ? "사용" : "꺼짐"} · 외부 요청 오늘 {resource.data.usedToday} / {resource.data.settings.dailyRequestLimit}회<br />OpenAI 키 {resource.data.credentials.OPENAI ? "등록됨" : "없음"} · Anthropic 키 {resource.data.credentials.ANTHROPIC ? "등록됨" : "없음"}</div>
      <div className="admin-actions"><button className="admin-primary" onClick={() => { setDraft({ ...resource.data!.settings }); setKey(""); setClearKey(false); setEditing(true); }}>AI 설정 변경</button><button className="admin-secondary" disabled={testing || ["OPENAI", "ANTHROPIC"].includes(resource.data.settings.provider)} onClick={() => void testConnection()}>{testing ? "확인 중…" : "로컬 연결 확인"}</button><Link to="/ai" className="admin-secondary">질의응답 열기 ↗</Link></div><small className="admin-description">외부 AI 연결은 실제 질문을 보낼 때 수행합니다. 외부 전송 동의와 API 키가 필요하며 제공자 사용료가 발생할 수 있습니다.</small>{test && <p className="admin-note" role="status">{test}</p>}</>}
    {editing && draft && resource.data && <ConfirmAction title="AI 실행 설정 변경" onClose={() => { setEditing(false); setKey(""); }} onApply={async (adminPassword, reason) => {
      await systemCall("/ai", adminJson("PUT", { expectedVersion: resource.data!.version, settings: draft, adminPassword, reason, credentialProvider: keyProvider, apiKey: key || null, clearCredential: clearKey })); setKey(""); await refresh(); resource.reload();
    }}><label className="admin-check"><input type="checkbox" checked={draft.enabled} onChange={e => setDraft({ ...draft, enabled: e.target.checked })} />AI 질의응답 사용</label>
      <label>제공자<select value={draft.provider} onChange={e => setDraft({ ...draft, provider: e.target.value })}>{["LOCAL", "OLLAMA", "OPENAI", "ANTHROPIC"].map(provider => <option key={provider}>{provider}</option>)}</select></label>
      <label>Ollama 주소<input value={draft.ollamaEndpoint} onChange={e => setDraft({ ...draft, ollamaEndpoint: e.target.value })} maxLength={200} /></label>
      {(["ollamaModel", "openaiModel", "anthropicModel"] as const).map((field, index) => <label key={field}>{["Ollama", "OpenAI", "Anthropic"][index]} 모델 ID<input value={draft[field]} onChange={e => setDraft({ ...draft, [field]: e.target.value })} maxLength={120} /></label>)}
      <div className="admin-form-grid"><label>시간 제한(초)<input type="number" min={1} max={120} value={draft.timeoutSeconds} onChange={e => setDraft({ ...draft, timeoutSeconds: Number(e.target.value) })} /></label><label>하루 외부 요청 한도<input type="number" min={1} max={10000} value={draft.dailyRequestLimit} onChange={e => setDraft({ ...draft, dailyRequestLimit: Number(e.target.value) })} /></label></div>
      <label className="admin-check"><input type="checkbox" checked={draft.externalAiEnabled} onChange={e => setDraft({ ...draft, externalAiEnabled: e.target.checked, externalAiConsentAt: e.target.checked ? new Date().toISOString() : null })} />권한 범위 안의 질문·자료를 선택한 외부 AI 제공자에게 전송하는 데 동의</label>
      <label>API 키 제공자<select value={keyProvider} onChange={e => setKeyProvider(e.target.value)}><option>OPENAI</option><option>ANTHROPIC</option></select></label><label>API 키<input type="password" autoComplete="off" value={key} onChange={e => setKey(e.target.value)} maxLength={500} placeholder="비워 두면 기존 키 유지" /><small>서버에서 암호화해 보관하며 다시 표시하지 않습니다.</small></label><label className="admin-check"><input type="checkbox" checked={clearKey} onChange={e => setClearKey(e.target.checked)} />선택한 제공자의 등록된 키 제거</label>
    </ConfirmAction>}
  </section>;
}

const tabs = { overview: "관리 개요", accounts: "사용자·계정", requests: "등록 신청", security: "보안 정책", audit: "감사 기록", ai: "AI 설정" };
export default function SystemAdministration() {
  const [params] = useSearchParams();
  const tab = params.get("tab") ?? "overview";
  const active = tab in tabs ? tab as keyof typeof tabs : "overview";
  return <div className="system-admin"><PageMeta titleKey="sidebar.items.systemAdmin" /><div className="admin-heading"><div><h1>시스템</h1><p className="admin-description">체계 전체의 사용자 등록·접근 권한·보안·AI 설정을 관리합니다.</p></div></div>
    <nav className="admin-tabs" aria-label="관리자 기능">{Object.entries(tabs).map(([key, label]) => <Link key={key} to={`/admin/system?tab=${key}`} aria-current={active === key ? "page" : undefined}>{label}</Link>)}</nav>
    {active === "overview" ? <Overview /> : active === "accounts" ? <Accounts /> : active === "requests" ? <Registrations /> : active === "security" ? <Security /> : active === "audit" ? <Audit /> : <AiSettings />}
  </div>;
}
