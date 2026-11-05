import { departmentPaths, fetchDepartments } from "@/api/org";
import { fetchStatusScope } from "@/api/status";
import { useEffect, useState } from "react";
import { useTranslation } from "react-i18next";

// 업무 현황에서 숫자를 눌러 연 목록 위의 띠 (slices/9_업무현황.md 3.5): "업무 현황에서 온 조건: 1중대(하위 포함) · 기한 지남 · 지금 기준 [조건 지우기]".
// 부서 이름은 업무 현황 범위 API의 구별된 이름(2.4)을 쓴다. 트리 없이 고정된 부서는 그 이름이 부서 이름뿐("1반")이라 업무 현황 머리말처럼 조직 경로를 쓴다(못 읽으면 그 이름).
// closedRange: 끝난 건 기간이 있으면(G-119) "지금 기준" 대신 "진행 중은 지금, 끝난 건은 그 기간"
export default function ConditionBanner({ departmentIds, parts, closedRange = false, onClear }: { departmentIds: number[]; parts: string[]; closedRange?: boolean; onClear: () => void }) {
  const { t } = useTranslation("status");
  const [names, setNames] = useState<Map<number, string> | null>(null);
  const [parents, setParents] = useState<Set<number>>(new Set());

  useEffect(() => {
    let cancelled = false;
    fetchStatusScope().then(
      (scope) => {
        if (cancelled) return;
        const scopeNames = new Map(scope.nodes.map((n) => [n.id, n.name]));
        setNames(scopeNames);
        setParents(new Set(scope.nodes.filter((n) => n.parentId !== null).map((n) => n.parentId!)));
        const fixedId = scope.showTree ? null : scope.departmentId;
        if (fixedId === null) return;
        fetchDepartments().then(
          (list) => {
            const path = departmentPaths(list).get(fixedId);
            if (!cancelled && path) setNames(new Map(scopeNames).set(fixedId, path));
          },
          () => {},
        );
      },
      () => !cancelled && setNames(new Map()),
    );
    return () => {
      cancelled = true;
    };
  }, []);

  const departments = departmentIds
    .map((id) => {
      const name = names?.get(id);
      if (!name) return null;
      return parents.has(id) ? `${name}${t("scope.withChildren")}` : name;
    })
    .filter(Boolean);

  return (
    <div role="status" className="mb-4 flex flex-wrap items-center gap-x-3 gap-y-1 rounded-lg border border-brand-200 bg-brand-50 px-4 py-2 text-sm text-brand-800 dark:border-brand-500/30 dark:bg-brand-500/10 dark:text-brand-300">
      <span className="font-medium">{t("banner.from")}</span>
      <span>{[departments.join(", "), ...parts, t(closedRange ? "banner.closedBasis" : "banner.nowBasis")].filter(Boolean).join(" · ")}</span>
      <button type="button" onClick={onClear} className="ms-auto font-medium underline underline-offset-2">
        {t("banner.clear")}
      </button>
    </div>
  );
}
