import type { AiMessage } from "@/api/ai";
import type { AssistAsk, AssistOption, AssistTarget, AssistView } from "@/api/assist";

// AI 업무지원 화면 맥락·이동·패널 글 (REQ-C01·C02·C08, slices/21 6.1·13.1).
// 화면(React)과 떨어진 순수 함수라 node 시험(scripts/assist-nav.test.ts)으로 확인한다

/** 화면 id(없으면 그 앞 마디, 예: "docbox.move" → "docbox") → 그 화면이 있는 메뉴 경로와 대상 상세 경로({id}). 서버 AssistScreens의 경로와 같다 */
const SCREEN_ROUTES: Record<string, { menu: string; detail?: string }> = {
  request: { menu: "/approvals/request" },
  "request.edit": { menu: "/approvals/request", detail: "/work/requests/{id}" },
  register: { menu: "/approvals/new" },
  directive: { menu: "/directives" },
  duty: { menu: "/duty/edit" },
  ict: { menu: "/operations?view=signals" },
  calendar: { menu: "/calendar" },
  equipment: { menu: "/department/equipment", detail: "/department/equipment/{id}" },
  diagram: { menu: "/department/diagrams", detail: "/department/diagrams/{id}" },
  docbox: { menu: "/department/docs" },
  people: { menu: "/department/people" },
  worktype: { menu: "/admin/work-types" },
  personnel: { menu: "/admin/personnel" },
  equipmentAdmin: { menu: "/admin/equipment" },
  route: { menu: "/admin/approval-routes" },
};

const routesOf = (screen: string) => SCREEN_ROUTES[screen] ?? SCREEN_ROUTES[screen.split(".")[0]] ?? null;

/**
 * 이동 제안(kind=navigate)의 경로: 대상이 있고 그 화면에 대상 상세 경로가 있으면 그 경로, 아니면 메뉴 첫 화면.
 * 표에 없는 화면은 서버가 준 경로(있으면), 그것도 없으면 null
 */
export function navigateRoute(option: Pick<AssistOption, "screen" | "target" | "route">): string | null {
  const routes = routesOf(option.screen);
  if (!routes) return option.route || null;
  const target = option.target;
  if (target !== null && String(target) !== "" && routes.detail) return routes.detail.replace("{id}", encodeURIComponent(String(target)));
  return routes.menu;
}

/** 대상 상세 경로에서 대상 id를 읽는다(예: /department/equipment/12 → "12"). 상세 경로가 아니면 null */
export function targetFromPath(screen: string, pathname: string): string | null {
  const detail = routesOf(screen)?.detail;
  if (!detail) return null;
  const [before, after] = detail.split("{id}");
  if (!pathname.startsWith(before) || !pathname.endsWith(after) || pathname.length <= before.length + after.length) return null;
  const id = pathname.slice(before.length, pathname.length - after.length);
  return /^\d+$/.test(id) ? id : null;
}

export interface ViewSources {
  pathname: string;
  /** 열려 있는(enabled) 창 어댑터 */
  fields: { id: number; screen: string; target: AssistTarget }[];
  /** 페이지 어댑터 */
  pages: { id: number; screens: readonly string[] }[];
  /** 채우기 작업의 창 어댑터 id */
  sessionAdapterId: number | null;
}

/**
 * 패널 질문에 함께 보낼 지금 보는 지원 화면(view, 13.1): 채우기 작업의 창 → 가장 늦게 열린 창 → 페이지 어댑터의 첫 화면(대상은 상세 경로에서).
 * AI 질의응답 화면이거나 등록된 지원 화면이 없으면 null(보내지 않음)
 */
export function pickView({ pathname, fields, pages, sessionAdapterId }: ViewSources): AssistView | null {
  if (pathname === "/ai") return null;
  const latest = <T extends { id: number }>(list: T[]) => list.reduce<T | null>((best, x) => (best === null || x.id > best.id ? x : best), null);
  const field = fields.find((f) => f.id === sessionAdapterId) ?? latest(fields);
  if (field) return { screen: field.screen, target: field.target === null || field.target === undefined ? null : String(field.target) };
  const page = latest(pages.filter((p) => p.screens.length > 0));
  if (!page) return null;
  const screen = page.screens[0];
  return { screen, target: targetFromPath(screen, pathname) };
}

/** 되묻기 답의 칸 이름: 서버 되묻기의 칸 이름 → 화면 어댑터의 칸 이름 → 되묻기 질문 글. 모르면 null */
export function askFieldName(field: string, asks: readonly AssistAsk[], labels: Record<string, string>): string | null {
  const ask = asks.find((a) => a.field === field);
  return ask?.label || labels[field] || ask?.question.trim() || null;
}

/**
 * 되묻기 답 말풍선 글 ("칸 이름: 값" / ...). 칸 이름을 모르는 답은 값만 둔다(차례가 끝나면 서버가 저장한 글로 바뀐다).
 * line: 이름과 값으로 한 줄을 만든다(번역 글 "answerLine")
 */
export function answerText(
  answers: Record<string, string | null>,
  asks: readonly AssistAsk[],
  labels: Record<string, string>,
  declinedValue: string,
  line: (name: string, value: string) => string,
): string {
  return Object.entries(answers)
    .map(([field, value]) => {
      const shown = value === null ? declinedValue : value;
      const name = askFieldName(field, asks, labels);
      return name ? line(name, shown) : shown;
    })
    .join(" / ");
}

/**
 * 패널에 그릴 메시지: 이번 채우기 답(live)만 글을 빼고 요약 카드(FillSummary)로 보인다(검토 C3).
 * 지난 차례 답은 저장된 글(content) 그대로 보인다
 */
export function panelMessages(messages: AiMessage[], liveMessageId: number | null): AiMessage[] {
  if (liveMessageId === null) return messages;
  return messages.map((m) => (m.id === liveMessageId && m.role === "ASSISTANT" && m.content ? { ...m, content: "" } : m));
}

/**
 * 상단바 [AI 업무지원]을 누름 (REQ-C01): 어느 메뉴에서든 패널을 열고, 열려 있으면 닫는다.
 * AI 질의응답 화면(/ai)에서는 그 화면의 지금 대화를 패널에 이어 연다. open: 열 대화 id(undefined면 패널의 지금 대화)
 */
export function assistButtonAction(pathname: string, isOpen: boolean, pageConversation: number | null, threadId: number | null): { open: number | null | undefined } | "close" {
  if (pathname === "/ai" && (!isOpen || pageConversation !== threadId)) return { open: pageConversation };
  return isOpen ? "close" : { open: undefined };
}
