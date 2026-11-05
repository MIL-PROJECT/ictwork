import type { DirectiveItem } from "@/api/directive";
import { fetchDirectives } from "@/api/directive";
import { fetchDutyToday } from "@/api/duty";
import type { BoxCard } from "@/api/ictStatus";
import { fetchIctTab } from "@/api/ictStatus";
import type { WorkStatus } from "@/api/status";
import { fetchStatusScope, fetchWorkStatus } from "@/api/status";
import type { ItemListItem } from "@/api/work";
import { fetchDepartmentItems, fetchMyTurn, fetchReceipts } from "@/api/work";
import { useCallback, useEffect, useRef, useState } from "react";

// 통합상황판이 읽는 값. 기존 API만 쓴다 (서버 변경 없음). 못 읽은 묶음은 null로 두고 화면이 "—"로 보인다
export interface BoardData {
  status: WorkStatus | null; // 업무 현황: 기본 보는 범위, 통합 보기, 이번 달
  urgent: ItemListItem[] | null; // 같은 범위의 끝나지 않은 긴급 업무 (업무 현황 바로가기 urgent와 같은 판정)
  overdue: ItemListItem[] | null; // 같은 범위의 지금 지연 중인 업무 (overdue)
  planned: ItemListItem[] | null; // 같은 범위의 계획 상태 업무
  receipts: ItemListItem[] | null; // 업무 접수 대기
  myTurn: number | null; // 내 차례 결재 수
  boxes: BoxCard[] | null; // 정보통신 현황 박스 (숨기지 않은 것)
  directives: DirectiveItem[] | null; // 지시사항 박스 순서 (직책마다 최신 1건)
  dutyToday: number | null; // 오늘 근무자 수 (근무표를 합쳐 사람 기준)
}

const pad = (n: number) => String(n).padStart(2, "0");
const ymd = (d: Date) =>
  `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`;

function thisMonth(now = new Date()) {
  const from = new Date(now.getFullYear(), now.getMonth(), 1);
  const to = new Date(now.getFullYear(), now.getMonth() + 1, 0);
  return { from: ymd(from), to: ymd(to) };
}

const settle = <T>(p: Promise<T>): Promise<T | null> =>
  p.then(
    (v) => v,
    () => null,
  );

async function loadBoard(): Promise<BoardData> {
  const month = thisMonth();
  // 업무 현황과 같은 보는 범위(기본 부서)로 묶어 숫자가 서로 맞게 한다
  const scope = await settle(fetchStatusScope());
  const ids = scope?.initialIds ?? [];
  const items = (extra: Record<string, string>) => {
    if (!ids.length) return Promise.resolve(null);
    const q = new URLSearchParams(extra);
    ids.forEach((id) => q.append("departmentIds", String(id)));
    return settle(fetchDepartmentItems(q));
  };
  const [
    status,
    urgent,
    overdue,
    planned,
    receipts,
    myTurn,
    ict,
    directives,
    duty,
  ] = await Promise.all([
    ids.length
      ? settle(
          fetchWorkStatus({
            departmentIds: ids,
            view: "TOTAL",
            period: "MONTH",
            ...month,
          }),
        )
      : Promise.resolve(null),
    items({ urgent: "true" }),
    items({ overdue: "true" }),
    items({ statuses: "PLANNED" }),
    settle(fetchReceipts()),
    settle(fetchMyTurn()),
    settle(fetchIctTab()),
    settle(fetchDirectives()),
    settle(fetchDutyToday()),
  ]);
  const dutyToday = duty
    ? new Set(
        duty.rosters.flatMap((r) =>
          r.days
            .filter((d) => d.date === duty.today)
            .flatMap((d) => d.people.map((p) => p.personnelId)),
        ),
      ).size
    : null;
  // 하나도 못 읽었으면(서버 끊김 등) 실패로 보고 마지막 값을 둔다
  if (
    [
      status,
      urgent,
      overdue,
      planned,
      receipts,
      myTurn,
      ict,
      directives,
      duty,
    ].every((v) => v === null)
  )
    throw new Error("board");
  return {
    status,
    urgent,
    overdue,
    planned,
    receipts,
    myTurn: myTurn ? myTurn.length : null,
    boxes: ict ? ict.boxes.filter((b) => !b.hidden) : null,
    directives: directives
      ? directives.boxes
          .map((id) => directives.items.find((d) => d.id === id))
          .filter((d): d is DirectiveItem => !!d)
      : null,
    dutyToday,
  };
}

// 30초마다, 창으로 돌아오거나 화면이 다시 보일 때 다시 읽는다. 실패하면 마지막 값을 두고 실패를 알린다 (OMNIS useVisibleRefresh)
export function useBoardData() {
  const [data, setData] = useState<BoardData | null>(null);
  const [error, setError] = useState(false);
  const [updatedAt, setUpdatedAt] = useState<Date | null>(null);
  const busy = useRef(false);

  const refresh = useCallback(async () => {
    if (busy.current) return;
    busy.current = true;
    try {
      const next = await loadBoard();
      setData(next);
      setUpdatedAt(new Date());
      setError(false);
    } catch {
      setError(true);
    } finally {
      busy.current = false;
    }
  }, []);

  useEffect(() => {
    // 첫 읽기도 effect 밖(다음 차례)에서: effect 안에서 바로 상태를 바꾸지 않는다
    const first = window.setTimeout(() => void refresh(), 0);
    const id = window.setInterval(
      () => document.visibilityState === "visible" && void refresh(),
      30_000,
    );
    const onVisible = () =>
      document.visibilityState === "visible" && void refresh();
    window.addEventListener("focus", onVisible);
    document.addEventListener("visibilitychange", onVisible);
    return () => {
      window.clearTimeout(first);
      window.clearInterval(id);
      window.removeEventListener("focus", onVisible);
      document.removeEventListener("visibilitychange", onVisible);
    };
  }, [refresh]);

  return { data, error, updatedAt, refresh };
}
