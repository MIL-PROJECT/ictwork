import type { AiSource } from "@/api/ai";
import { type AssistAdapterSpec, type NextResult, type PageSpec, useAssistRegistry } from "@/context/AssistContext";
import { useEffect, useRef, useState } from "react";
import { type FieldValues, type PendingApply, deriveAssist, expandFields, fieldOfElement, markedItem, settlePending } from "./applyFill";

// AI 업무지원 화면 어댑터 (REQ-C03·C07·C09, slices/21 6.2·12.5·13.3). 두 층이다.
// - 페이지 어댑터 useAssistPage(화면 id 목록, { ready, open }): 그 화면 안의 창·탭·편집 모드를 연다
// - 창 어댑터 useAssistAdapter(화면 id, spec): 칸 값 읽기·넣기. 창이 열려 있는 동안 등록되고, 등록되면 채우기를 시작한다
// 사용자 칸·dirty·"AI가 채움"은 값 비교로 정한다(baseline·aiApplied, applyFill.ts). 그래서 칸의 onChange에 따로 부를 것이 없다

export interface AssistHandle {
  /** "AI가 채움": 칸 값이(목록 칸은 item이) AI가 넣은 값 그대로인가. 목록 부품의 marked?: (key) => boolean에 (key) => marked(field, key)로 넘긴다 */
  marked: (field: string, item?: unknown) => boolean;
  /** 그 칸에 옮겨 쓴 자료의 출처 (REQ-C03 ⑤) */
  sources: (field: string) => AiSource[];
  /** 화면의 확인 창(window.confirm 등)을 열기 전에 부른다: 채우는 중이면 멈춘다 (REQ-C09) */
  pause: () => void;
}

const NO_SOURCES: AiSource[] = [];
/** AI 값을 넣은 뒤 화면이 다시 준비되기를 기다리는 시간. 준비가 돌아오지 않으면(처음 값 읽기 실패 등) 그때 값으로 마무리한다 */
const SETTLE_TIMEOUT = 10_000;

/** 창 어댑터. 예: useAssistAdapter("calendar", { enabled: isOpen, target, ready, values, apply, labels, ids }) */
export function useAssistAdapter(screen: string, spec: AssistAdapterSpec): AssistHandle {
  const registry = useAssistRegistry();
  const enabled = spec.enabled !== false;
  const target = spec.target ?? null;
  const ready = enabled && spec.ready;
  const [baseline, setBaseline] = useState<FieldValues | null>(null);
  const [aiApplied, setAiApplied] = useState<FieldValues>({});
  const [sources, setSources] = useState<Record<string, AiSource[]>>({});
  // AI가 값을 넣고 아직 마무리하지 않음(13.3): 넣기 바로 전 값·보낸 칸. 넣은 뒤 한 번 그려지고(armed) 화면이 준비되어 있으면 마무리한다.
  // 그 사이 화면이 불러오기로 준비를 풀면(받는 부서 유형·양식, 월별 채우기 처음 값, 장비 관리부서 특성) 다시 준비될 때 마무리해서, 늦게 들어간 AI 값도 그 차례 AI 값으로 적는다
  // 마무리 전에 사용자가 입력한 칸(typed)은 사용자 칸으로 남기고, 준비가 SETTLE_TIMEOUT 동안 돌아오지 않으면(expired) 그때 값으로 마무리한다
  const [pending, setPending] = useState<(PendingApply & { seq: number; sent: string[]; typed: string[]; armed: boolean; expired: boolean }) | null>(null);
  // 창이 다시 열리거나 대상이 바뀌면 새로 시작하고, 처음 준비될 때 baseline을 찍는다. AI와 무관하게 다시 준비될 때는 다시 찍지 않는다(사용자 편집 유지)
  const windowKey = `${enabled}|${target ?? ""}`;
  const [seen, setSeen] = useState(windowKey);
  if (seen !== windowKey) {
    setSeen(windowKey);
    setBaseline(null);
    setAiApplied({});
    setSources({});
    setPending(null);
  } else if (ready && baseline === null) {
    setBaseline({ ...spec.values });
  } else if (baseline !== null && pending?.armed && (ready || pending.expired)) {
    const settled = settlePending(baseline, aiApplied, pending, spec.values);
    setBaseline(settled.baseline);
    setAiApplied(settled.aiApplied);
    setPending(null);
  }
  // 마무리 전에도 사용자 칸·표시는 마무리했을 때와 같게 본다
  const effective = pending && baseline !== null ? settlePending(baseline, aiApplied, pending, spec.values) : { baseline, aiApplied };

  const specRef = useRef(spec);
  const stateRef = useRef(effective);
  const pendingSeq = useRef(0);
  const entryId = useRef<number | null>(null);
  const pendingNext = useRef<((result: NextResult | null) => void) | null>(null);
  // 준비된 뒤 사용자가 입력한 칸 (13.3 focused는 초점만이 아니라 입력이 있어야 사용자 칸, 검토 A1)
  const typedRef = useRef(new Set<string>());
  // settled()를 기다리는 쪽: 마무리 전 넣기가 없어지면 부른다(값 비교 상태가 마무리된 뒤)
  const settleWaiters = useRef<(() => void)[]>([]);
  const pendingActive = useRef(false);
  useEffect(() => {
    specRef.current = spec;
    stateRef.current = effective;
    pendingActive.current = pending !== null;
    if (pending === null && settleWaiters.current.length > 0) {
      const waiters = settleWaiters.current;
      settleWaiters.current = [];
      waiters.forEach((resolve) => resolve());
    }
  });

  // 넣은 값이 한 번 그려지고 그 그리기의 화면 효과(불러오기 시작 등)가 돈 뒤에 마무리할 수 있게 한다. 그때 값을 넣은 직후 값(after)으로 둔다
  useEffect(() => {
    if (!pending || pending.armed) return;
    const seq = pending.seq;
    void Promise.resolve().then(() => setPending((p) => (p && p.seq === seq ? { ...p, armed: true, after: { ...specRef.current.values } } : p)));
  }, [pending]);
  // 준비가 끝내 돌아오지 않으면(처음 값 읽기 실패 등) 영원히 마무리 전으로 두지 않는다
  const armedSeq = pending?.armed ? pending.seq : null;
  useEffect(() => {
    if (armedSeq === null || ready) return;
    const timer = setTimeout(() => setPending((p) => (p && p.seq === armedSeq ? { ...p, expired: true } : p)), SETTLE_TIMEOUT);
    return () => clearTimeout(timer);
  }, [armedSeq, ready]);

  useEffect(() => {
    typedRef.current = new Set();
  }, [windowKey]);

  // 마무리 전(넣은 뒤 다시 준비되기 전 포함)에도 듣는다: 그 사이 입력한 칸은 마무리할 때 사용자 칸으로 남긴다
  const listening = ready || pending !== null;
  useEffect(() => {
    if (!listening) return;
    const mark = (e: Event) => {
      const el = e.target;
      const field = el instanceof Element ? fieldOfElement(specRef.current.ids, el.id) : null;
      if (!field) return;
      typedRef.current.add(field);
      setPending((p) => (p && !p.typed.includes(field) ? { ...p, typed: [...p.typed, field] } : p));
    };
    // 글자·지우기 키만 입력으로 센다(Tab·화살표·복사 같은 키는 세지 않음)
    const onKey = (e: KeyboardEvent) => {
      if (e.isComposing || ((e.key.length === 1 || e.key === "Backspace" || e.key === "Delete") && !e.ctrlKey && !e.metaKey && !e.altKey)) mark(e);
    };
    document.addEventListener("input", mark, true);
    document.addEventListener("paste", mark, true);
    document.addEventListener("keydown", onKey, true);
    return () => {
      document.removeEventListener("input", mark, true);
      document.removeEventListener("paste", mark, true);
      document.removeEventListener("keydown", onKey, true);
    };
  }, [listening]);

  useEffect(() => {
    if (!registry || !enabled) return;
    const { id, unregister } = registry.registerField({
      screen,
      read: () => specRef.current,
      derive: () => deriveAssist(stateRef.current.baseline, stateRef.current.aiApplied, specRef.current.values),
      typed: () => typedRef.current,
      apply: (values, next) => {
        if (Object.keys(values).length > 0) {
          // 넣기 바로 전 값을 찍어 두고 넣는다. aiApplied에는 넣은 뒤 화면이 실제로 바꾼 값만 적는다(settleApply)
          const before = { ...specRef.current.values };
          const sent = expandFields(specRef.current.groups, Object.keys(values));
          const seq = ++pendingSeq.current;
          // apply가 돌려준 값은 화면이 AI 값을 얹기 전에 스스로 넣은 값(base, 결재 경로의 불러온 저장 경로)이다
          const base = specRef.current.apply(values) ?? {};
          pendingActive.current = true;
          setPending((p) =>
            p
              ? { ...p, seq, sent: [...new Set([...p.sent, ...sent])], base: { ...p.base, ...base }, after: null, armed: false, expired: false }
              : { seq, before, sent, base, after: null, typed: [], armed: false, expired: false },
          );
        }
        if (!next || !specRef.current.next) return Promise.resolve(null);
        // 값이 화면 상태에 들어간 뒤(다음 그리기 뒤) [다음]을 누른다
        return new Promise<NextResult | null>((resolve) => {
          pendingNext.current = resolve;
          setSources((cur) => ({ ...cur }));
        });
      },
      setSources: (next) => setSources(next),
      settled: () => (pendingActive.current ? new Promise<void>((resolve) => settleWaiters.current.push(resolve)) : Promise.resolve()),
    });
    entryId.current = id;
    return () => {
      entryId.current = null;
      unregister();
    };
  }, [registry, screen, enabled]);

  // 준비·대상이 바뀌면 알린다(작업 시작은 준비가 끝난 창에서)
  useEffect(() => {
    registry?.notify();
  }, [registry, ready, target]);

  useEffect(() => {
    const resolve = pendingNext.current;
    if (!resolve) return;
    pendingNext.current = null;
    const next = specRef.current.next;
    if (!next) resolve(null);
    else void Promise.resolve(next()).then(resolve, () => resolve(null));
  });

  const derived = deriveAssist(effective.baseline, effective.aiApplied, spec.values);
  return {
    marked: (field, item) => (item === undefined ? derived.marked.includes(field) : markedItem(effective.aiApplied, spec.values, field, item)),
    sources: (field) => sources[field] ?? NO_SOURCES,
    pause: () => {
      if (entryId.current !== null) registry?.pause(entryId.current);
    },
  };
}

/**
 * 페이지 어댑터. 예: useAssistPage(["calendar"], { ready: loaded, open: (screen, target) => openModalFor(target) }).
 * open은 boolean이나 Promise<boolean>을 돌려줄 수 있다(늦은 찾기). 셋째 인자 still()은 그 작업이 아직 살아 있고 같은 화면인가다(늦은 찾기 뒤 창을 열기 전에 본다).
 * 화면 id 없이(screens: []) dirty만 알릴 수도 있다: 창 어댑터가 없는 창의 저장하지 않은 내용([작성 시작] 전 확인, REQ-C02)
 */
export function useAssistPage(screens: readonly string[], spec: PageSpec) {
  const registry = useAssistRegistry();
  const specRef = useRef(spec);
  useEffect(() => {
    specRef.current = spec;
  });
  const key = screens.join(",");
  useEffect(() => {
    if (!registry) return;
    return registry.registerPage({ screens: key ? key.split(",") : [], read: () => specRef.current });
  }, [registry, key]);
  const ready = spec.ready !== false;
  useEffect(() => {
    registry?.notify();
  }, [registry, ready]);
}
