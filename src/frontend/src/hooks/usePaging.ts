import { useEffect, useRef, useState } from "react";
import { useSearchParams } from "react-router";

/** 한 쪽에 보일 개수 고르기 (REQ-007, G-117): 처음 20개 */
export const PAGE_SIZES = [10, 20, 30, 50, 100] as const;
const DEFAULT_SIZE = 20;

const storageKey = (list: string) => `page-size-${list}`;

/** 고른 개수는 목록마다 그 브라우저에 기억한다(읽지 못하면 처음 값) */
function readSize(list: string): number {
  try {
    const saved = Number(localStorage.getItem(storageKey(list)));
    return PAGE_SIZES.some((s) => s === saved) ? saved : DEFAULT_SIZE;
  } catch {
    return DEFAULT_SIZE;
  }
}

function writeSize(list: string, size: number) {
  try {
    localStorage.setItem(storageKey(list), String(size));
  } catch {
    // 저장하지 못해도 화면은 지금 고른 값으로 둔다
  }
}

export interface Paging {
  total: number; // 걸러 본 전체 건수
  page: number; // 1부터
  pageCount: number;
  size: number;
  start: number; // 이 쪽 첫 줄의 순번(0부터)
  setPage: (page: number) => void;
  setSize: (size: number) => void;
  /** 이 쪽에 보일 줄만 */
  slice: <T>(list: T[]) => T[];
}

/**
 * 목록 쪽 나누기 (REQ-007, G-117). 서버는 전체를 주고 화면이 나눈다. 쪽 번호는 주소의 page에 담아 상세를 보고 돌아와도 그 쪽이다.
 * 쪽이 줄어 주소의 쪽이 넘치면 마지막 쪽을 보인다(주소는 그대로).
 * resetKey: 주소 밖(화면 상태)에 둔 걸러 보기·검색·정렬을 한 값으로 묶어 주면, 바뀔 때 1쪽으로 간다. 주소에 담는 조건은 그 조건을 바꾸는 곳에서 page를 뺀다
 */
export function usePaging(list: string, total: number, resetKey?: string): Paging {
  const [params, setParams] = useSearchParams();
  const [size, setSizeState] = useState(() => readSize(list));
  const pageCount = Math.max(1, Math.ceil(total / size));
  const raw = Number(params.get("page"));
  const page = Number.isInteger(raw) && raw >= 1 ? Math.min(raw, pageCount) : 1;

  // 쪽 번호만 바꾸는 이동은 방문 기록을 늘리지 않는다(뒤로 가기는 앞 화면으로)
  const setPage = (next: number) =>
    setParams(
      (prev) => {
        const out = new URLSearchParams(prev);
        if (next <= 1) out.delete("page");
        else out.set("page", String(next));
        return out;
      },
      { replace: true },
    );

  const setSize = (next: number) => {
    setSizeState(next);
    writeSize(list, next);
    setPage(1);
  };

  const lastKey = useRef(resetKey);
  const hasPage = params.has("page");
  useEffect(() => {
    if (lastKey.current === resetKey) return;
    lastKey.current = resetKey;
    if (!hasPage) return;
    setParams(
      (prev) => {
        const out = new URLSearchParams(prev);
        out.delete("page");
        return out;
      },
      { replace: true },
    );
  }, [resetKey, hasPage, setParams]);

  const start = (page - 1) * size;
  return { total, page, pageCount, size, start, setPage, setSize, slice: (rows) => rows.slice(start, start + size) };
}
