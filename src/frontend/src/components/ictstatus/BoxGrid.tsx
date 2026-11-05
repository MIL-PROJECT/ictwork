import type { BoxCard as BoxCardData } from "@/api/ictStatus";
import { cn } from "@/utils";
import { useCallback, useEffect, useRef, useState } from "react";
import { DndProvider, useDrag, useDrop } from "react-dnd";
import { HTML5Backend } from "react-dnd-html5-backend";
import { useTranslation } from "react-i18next";
import BoxCard from "./BoxCard";

const DRAG_TYPE = "ict-box";

/** md(768px) 미만이면 참: 한 줄에 박스 하나, 끌기 대신 위·아래 단추 (REQ-503) */
function useNarrow() {
  const query = "(max-width: 767.98px)";
  const [narrow, setNarrow] = useState(() => window.matchMedia(query).matches);
  useEffect(() => {
    const list = window.matchMedia(query);
    const change = () => setNarrow(list.matches);
    list.addEventListener("change", change);
    return () => list.removeEventListener("change", change);
  }, []);
  return narrow;
}

/**
 * 박스 판 (REQ-503, slices/15 13.2): 넓은 화면 4칸·태블릿 2칸 격자, "크게"는 2칸 너비. 끌어 옮기기(react-dnd, 놓은 박스 앞으로, 끝 자리에 놓으면 맨 끝),
 * 위·아래 단추(좁은 화면은 늘 보이고 넓은 화면은 키보드로 닿으면 보임). boxes는 숨기지 않은 박스(배치 순서)
 */
export default function BoxGrid({ boxes, onOrder, onSize, onHide, onOpen }: {
  boxes: BoxCardData[];
  onOrder: (ids: number[]) => void;
  onSize: (box: BoxCardData) => void;
  onHide: (box: BoxCardData) => void;
  onOpen: (box: BoxCardData) => void;
}) {
  const narrow = useNarrow();
  const ids = boxes.map((b) => b.id);
  const listRef = useRef<HTMLUListElement>(null);
  // 단추로 옮긴 뒤 순서가 바뀌면 옮긴 박스의 같은 단추(끝에 닿았으면 반대 단추)로 초점을 돌린다
  const moved = useRef<{ id: number; by: -1 | 1 } | null>(null);
  useEffect(() => {
    const last = moved.current;
    if (!last || !listRef.current) return;
    moved.current = null;
    const find = (by: number) => listRef.current?.querySelector<HTMLButtonElement>(`[data-move="${last.id}:${by}"]:not(:disabled)`);
    (find(last.by) ?? find(-last.by))?.focus();
  }, [boxes]);

  const moveTo = useCallback(
    (from: number, before: number | null) => {
      const without = ids.filter((id) => id !== from);
      const at = before === null ? without.length : without.indexOf(before);
      onOrder([...without.slice(0, at), from, ...without.slice(at)]);
    },
    [ids, onOrder],
  );
  const moveBy = (id: number, by: -1 | 1) => {
    const next = [...ids];
    const i = next.indexOf(id);
    [next[i], next[i + by]] = [next[i + by], next[i]];
    moved.current = { id, by };
    onOrder(next);
  };
  return (
    <DndProvider backend={HTML5Backend}>
      <ul ref={listRef} className="grid grid-cols-1 gap-[18px] md:grid-cols-2 xl:grid-cols-4">
        {boxes.map((box, i) => (
          <DraggableBox key={box.id} box={box} enabled={!narrow} onDrop={(from) => from !== box.id && moveTo(from, box.id)}>
            {(handle) => (
              <BoxCard
                box={box}
                handle={handle}
                onOpen={() => onOpen(box)}
                menu={{ first: i === 0, last: i === boxes.length - 1, onSize: () => onSize(box), onHide: () => onHide(box), onMove: (by) => moveBy(box.id, by) }}
              />
            )}
          </DraggableBox>
        ))}
        {!narrow && boxes.length > 1 && <EndDrop onDrop={(from) => from !== ids[ids.length - 1] && moveTo(from, null)} />}
      </ul>
    </DndProvider>
  );
}

/** 끌어 옮길 수 있는 칸: 다른 박스 위에 놓으면 그 앞으로 간다. 연결은 실제 DOM 요소에 건다(React 19) */
function DraggableBox({ box, enabled, onDrop, children }: {
  box: BoxCardData;
  enabled: boolean;
  onDrop: (from: number) => void;
  children: (handle: React.ReactNode) => React.ReactNode;
}) {
  const { t } = useTranslation("ictStatus");
  const [{ dragging }, drag, preview] = useDrag({ type: DRAG_TYPE, item: { id: box.id }, canDrag: enabled, collect: (m) => ({ dragging: m.isDragging() }) }, [box.id, enabled]);
  const [{ over }, drop] = useDrop<{ id: number }, void, { over: boolean }>(
    { accept: DRAG_TYPE, drop: (item) => onDrop(item.id), collect: (m) => ({ over: m.isOver() && m.getItem<{ id: number }>()?.id !== box.id }) },
    [box.id, onDrop],
  );
  const cellRef = useCallback(
    (node: HTMLLIElement | null) => {
      drop(node);
      preview(node);
    },
    [drop, preview],
  );
  const handleRef = useCallback(
    (node: HTMLSpanElement | null) => {
      drag(node);
    },
    [drag],
  );
  // 손잡이는 마우스 끌기용이라 화면 읽기에서 감춘다(키보드는 위·아래 단추)
  const handle = enabled ? (
    <span ref={handleRef} className="cursor-move px-1 text-theme-xs text-gray-500 select-none dark:text-gray-400" title={t("card.drag")} aria-hidden>
      ⠿
    </span>
  ) : null;
  return (
    <li ref={cellRef} className={cn(box.size === "LARGE" && "md:col-span-2", dragging && "opacity-50", over && "rounded-[20px] ring-2 ring-brand-500")}>
      {children(handle)}
    </li>
  );
}

/** 맨 끝 자리: 끄는 동안만 보이고, 놓으면 그 박스를 맨 끝으로 옮긴다 */
function EndDrop({ onDrop }: { onDrop: (from: number) => void }) {
  const { t } = useTranslation("ictStatus");
  const [{ active, over }, drop] = useDrop<{ id: number }, void, { active: boolean; over: boolean }>(
    { accept: DRAG_TYPE, drop: (item) => onDrop(item.id), collect: (m) => ({ active: m.canDrop(), over: m.isOver() }) },
    [onDrop],
  );
  const ref = useCallback(
    (node: HTMLLIElement | null) => {
      drop(node);
    },
    [drop],
  );
  return (
    <li
      ref={ref}
      aria-hidden
      className={cn(
        "flex min-h-24 items-center justify-center rounded-[20px] border-2 border-dashed text-sm text-gray-500 dark:text-gray-400",
        active ? "border-gray-300 dark:border-gray-700" : "hidden",
        over && "border-brand-500 text-brand-600 dark:text-brand-400",
      )}
    >
      {t("card.dropEnd")}
    </li>
  );
}
