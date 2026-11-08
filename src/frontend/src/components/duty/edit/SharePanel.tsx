import { type DutyRosterDetail, saveShares } from "@/api/duty";
import type { Department } from "@/api/org";
import { useAssistAdapter } from "@/components/assist/useAssistAdapter";
import OrgPicker from "@/components/work/OrgPicker";
import { useState } from "react";
import { useTranslation } from "react-i18next";
import { type DutyWrite, dangerLink, useDutyError } from "../dutyUtil";

/**
 * 공유 부서 탭 (REQ-404, slices/14 5.4·7.4·11.1): 조직 트리에서 부서만(사용 중인 부서) 고르고, 고른 부서마다 "하위 포함" 스위치(처음 끔)·빼기.
 * 바꿀 때마다 목록 전체를 저장한다. 근무표 부서 소속(하위 제외)은 늘 보고, 공유 부서 소속이 더해진다
 */
export default function SharePanel({ detail, departments, write }: { detail: DutyRosterDetail; departments: Department[]; write: DutyWrite }) {
  const { t } = useTranslation("duty", { keyPrefix: "share" });
  const describeError = useDutyError();
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const shares = detail.shares;
  // AI 업무지원 (REQ-C07 근무자 현황, slices/21 8장·12.6 duty.share): [더하기]·[빼기]는 누르면 바로 저장되므로 칸을 넣지 않는다.
  // 탭이 열린 것만 알려, 패널이 단추 위치를 안내한다
  useAssistAdapter("duty.share", { target: detail.id, ready: true, values: {}, apply: () => undefined });

  const save = async (next: { departmentId: number; includeSub: boolean }[]) => {
    if (busy) return;
    setBusy(true);
    setError(null);
    try {
      await write((version) => saveShares(detail.id, next, version));
    } catch (err) {
      setError(describeError(err));
    } finally {
      setBusy(false);
    }
  };

  const current = shares.map((s) => ({ departmentId: s.departmentId, includeSub: s.includeSub }));

  return (
    <div className="space-y-4">
      <p data-guide="" className="text-sm break-keep text-gray-500 dark:text-gray-400">{t("help")}</p>
      {shares.length === 0 ? (
        <p className="text-sm text-gray-500 dark:text-gray-400">{t("none")}</p>
      ) : (
        <ul className="divide-y divide-gray-100 rounded-xl border border-gray-200 dark:divide-gray-800 dark:border-gray-800">
          {shares.map((s) => (
            <li key={s.departmentId} className="flex flex-wrap items-center justify-between gap-3 px-4 py-3 text-sm">
              <span className="min-w-0 break-keep text-gray-800 dark:text-white/90">{s.path}</span>
              <span className="flex items-center gap-4">
                {/* 하위 포함: 키보드로도 켜고 끄며, 화면 읽기 이름에 부서를 넣어 줄마다 구별한다(공개 범위 고르기 ScopePicker와 같게). 저장된 값을 그대로 보인다.
                    저장 중에도 disabled로 하지 않아 초점이 남는다(두 번 저장은 save가 막음, slices/19 C12 ③) */}
                <label className="flex cursor-pointer items-center gap-2 text-sm font-medium text-gray-700 select-none dark:text-gray-400">
                  <input
                    type="checkbox"
                    role="switch"
                    checked={s.includeSub}
                    aria-busy={busy}
                    aria-label={t("includeSubOf", { name: s.path })}
                    onChange={(e) => void save(current.map((c) => (c.departmentId === s.departmentId ? { ...c, includeSub: e.target.checked } : c)))}
                    className="size-4 cursor-pointer rounded accent-brand-500 disabled:cursor-not-allowed"
                  />
                  {t("includeSub")}
                </label>
                <button type="button" disabled={busy} onClick={() => void save(current.filter((c) => c.departmentId !== s.departmentId))} className={dangerLink}>
                  {t("remove")}
                </button>
              </span>
            </li>
          ))}
        </ul>
      )}
      {error && (
        <p role="alert" className="text-sm text-error-600 dark:text-error-400">
          {error}
        </p>
      )}
      <OrgPicker
        label={t("pick")}
        departments={departments}
        departmentAllowed={(d) => !current.some((c) => c.departmentId === d.id)}
        pickDepartment={(d) => void save([...current, { departmentId: d.id, includeSub: false }])}
      />
    </div>
  );
}
