import { apiFetch } from "@/api/client";
import { adminJson, rankLabels } from "@/api/systemAdmin";
import PageMeta from "@/components/common/PageMeta";
import { useState, type FormEvent } from "react";
import { Link } from "react-router";
import "../Admin/system-admin.css";

export default function Registration() {
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  const [done, setDone] = useState<number | null>(null);
  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const form = new FormData(event.currentTarget);
    if (form.get("password") !== form.get("confirmPassword")) { setError("비밀번호 확인이 일치하지 않습니다."); return; }
    setBusy(true); setError("");
    try {
      // The anonymous /api/me response supplies the CSRF cookie as on the sign-in screen.
      await apiFetch("/api/me").catch(() => undefined);
      form.delete("confirmPassword");
      const body = Object.fromEntries(form);
      const result = await apiFetch<{ id: number }>("/api/registrations", adminJson("POST", body));
      setDone(result.id);
    } catch (e) { setError(e instanceof Error ? e.message : "신청하지 못했습니다. 다시 시도하세요."); }
    finally { setBusy(false); }
  }
  return <div className="system-admin registration-page">
    <PageMeta titleKey="sidebar.items.registration" />
    <div className="admin-surface registration-card">
      <Link to="/signin" className="admin-back">← 로그인으로</Link>
      <h1>사용자 등록 신청</h1><p className="admin-description">관리자가 소속과 역할을 확인해 승인한 뒤, 신청한 아이디와 비밀번호로 로그인할 수 있습니다.</p>
      {done !== null ? <div role="status" className="admin-success"><strong>등록 신청이 접수되었습니다.</strong><p>접수번호 #{done} · 현재 승인 대기 상태입니다.</p><Link to="/signin">로그인 화면으로 돌아가기 →</Link></div> : <form onSubmit={event => void submit(event)} className="admin-form">
        <div className="admin-form-grid">
          <label>군번<input name="serviceNo" placeholder="22-12345678" pattern="[0-9]{2}-[0-9]{5,8}" maxLength={20} required /></label>
          <label>계급<select name="rankCode" defaultValue="" required><option value="" disabled>계급 선택</option>{Object.entries(rankLabels).map(([code, label]) => <option key={code} value={code}>{label}</option>)}</select></label>
          <label>성명<input name="displayName" minLength={2} maxLength={50} autoComplete="name" required /></label>
          <label>소속<input name="unitName" minLength={2} maxLength={100} placeholder="대대 / 중대 / 반" required /></label>
          <label>아이디<input name="loginId" autoComplete="username" pattern="[a-z][a-z0-9_\-]{3,49}" minLength={4} maxLength={50} required /><small>소문자로 시작하는 4~50자, 영문·숫자·-·_</small></label>
          <label>연락처<input name="contact" type="tel" autoComplete="tel" maxLength={30} placeholder="010-1234-5678" /></label>
          <label>비밀번호<input name="password" type="password" minLength={8} maxLength={72} autoComplete="new-password" required /><small>영문 대·소문자, 숫자, 특수문자 중 3종류 8자 또는 2종류 10자 이상</small></label>
          <label>비밀번호 확인<input name="confirmPassword" type="password" minLength={8} maxLength={72} autoComplete="new-password" required /></label>
        </div>
        <label>신청 사유<textarea name="reason" maxLength={300} rows={3} /></label>
        {error && <p role="alert" className="admin-error">{error}</p>}
        <button className="admin-primary" type="submit" disabled={busy}>{busy ? "신청 중…" : "등록 신청"}</button>
      </form>}
    </div>
  </div>;
}
