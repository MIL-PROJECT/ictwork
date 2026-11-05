import { type AdminAccount, fetchAccounts } from "@/api/admin";
import { type Department, departmentPaths, fetchDepartments, fetchPersonnel, type PersonnelItem } from "@/api/org";
import { useCallback, useEffect, useMemo, useState } from "react";

// 관리·명단 화면이 함께 쓰는 조직 데이터. 필요한 것만 불러오고, 바꾼 뒤 reload()로 다시 읽는다.
// 장비는 장비 화면이 따로 읽는다(api/equipment.ts, slices/10)
export function useOrgData(need: { personnel?: boolean; accounts?: boolean }) {
  const [departments, setDepartments] = useState<Department[]>([]);
  const [personnel, setPersonnel] = useState<PersonnelItem[]>([]);
  const [accounts, setAccounts] = useState<AdminAccount[]>([]);
  const [loaded, setLoaded] = useState(false);
  const [failed, setFailed] = useState(false);
  const { personnel: needPersonnel, accounts: needAccounts } = need;

  const load = useCallback(
    () =>
      Promise.all([
        fetchDepartments(),
        needPersonnel ? fetchPersonnel() : Promise.resolve([]),
        needAccounts ? fetchAccounts() : Promise.resolve([]),
      ] as const),
    [needPersonnel, needAccounts],
  );

  const apply = useCallback(([d, p, a]: Awaited<ReturnType<typeof load>>) => {
    setDepartments(d);
    setPersonnel(p);
    setAccounts(a);
    setLoaded(true);
    setFailed(false);
  }, []);

  // 다시 읽기가 실패하면 목록 대신 실패 문구를 보인다(저장은 이미 끝났으므로 오류를 던지지 않음)
  const reload = async () => {
    try {
      apply(await load());
    } catch {
      setFailed(true);
    }
  };

  useEffect(() => {
    let cancelled = false;
    load().then(
      (data) => {
        if (!cancelled) apply(data);
      },
      () => {
        if (!cancelled) setFailed(true);
      },
    );
    return () => {
      cancelled = true;
    };
  }, [load, apply]);

  const paths = useMemo(() => departmentPaths(departments), [departments]);

  return { departments, personnel, accounts, paths, loaded, failed, reload };
}
