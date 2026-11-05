import type { DocListItem } from "@/api/work";
import { useEffect, useState } from "react";

/** 결재 문서 목록을 읽는다. key가 바뀔 때만 다시 읽고, 읽는 동안은 이전 key의 결과를 보이지 않는다 */
export function useDocList(load: () => Promise<DocListItem[]>, key: string) {
  const [state, setState] = useState<{ key: string; docs: DocListItem[] | null; failed: boolean }>({ key, docs: null, failed: false });
  useEffect(() => {
    let cancelled = false;
    load().then(
      (list) => !cancelled && setState({ key, docs: list, failed: false }),
      () => !cancelled && setState({ key, docs: null, failed: true }),
    );
    return () => {
      cancelled = true;
    };
    // key가 바뀔 때만 다시 읽는다
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [key]);
  return state.key === key ? { docs: state.docs, failed: state.failed } : { docs: null, failed: false };
}
