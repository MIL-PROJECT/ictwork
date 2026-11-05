// 서버 호출 공통 처리: 쿠키 전송, CSRF 헤더, 오류 JSON(05 5장), 401 알림

export class ApiError extends Error {
  readonly status: number;
  readonly code: string;
  readonly field?: string; // 틀린 입력 항목 (장비 등록·수정, slices/10 4.1)
  readonly rows?: unknown[]; // 엑셀 일괄 등록의 줄별 오류

  constructor(status: number, code: string, message: string, field?: string, rows?: unknown[]) {
    super(message);
    this.status = status;
    this.code = code;
    this.field = field;
    this.rows = rows;
  }
}

let unauthorizedListener: (() => void) | null = null;

// 401을 받으면 부를 함수 (로그인 상태를 비워 로그인 화면으로 보냄)
export function setUnauthorizedListener(listener: (() => void) | null) {
  unauthorizedListener = listener;
}

let forbiddenListener: (() => void) | null = null;

// 403을 받으면 부를 함수. 역할이 바뀌었거나 비밀번호를 바꿔야 할 수 있으므로 현재 사용자를 다시 읽는다 (02 6장)
export function setForbiddenListener(listener: (() => void) | null) {
  forbiddenListener = listener;
}

function readCookie(name: string): string | undefined {
  return document.cookie
    .split("; ")
    .find((cookie) => cookie.startsWith(`${name}=`))
    ?.slice(name.length + 1);
}

// apiFetch를 거치지 않는 요청(파일 받기)도 401·403을 같은 방식으로 알린다
export function notifyAuthStatus(status: number) {
  if (status === 401) unauthorizedListener?.();
  if (status === 403) forbiddenListener?.();
}

export async function apiFetch<T>(path: string, init: RequestInit = {}): Promise<T> {
  const headers = new Headers(init.headers);
  headers.set("Accept", "application/json");
  // 로그인 후 토큰이 바뀌므로 요청마다 쿠키를 다시 읽는다 (05 6장)
  if ((init.method ?? "GET").toUpperCase() !== "GET") {
    const token = readCookie("XSRF-TOKEN");
    if (token) headers.set("X-XSRF-TOKEN", decodeURIComponent(token));
  }

  const response = await fetch(path, { ...init, headers, credentials: "same-origin" });

  if (response.status === 401) unauthorizedListener?.();
  if (response.status === 403) forbiddenListener?.();
  if (!response.ok) {
    const body = (await response.json().catch(() => null)) as {
      code?: string;
      message?: string;
      field?: string;
      rows?: unknown[];
    } | null;
    throw new ApiError(
      response.status,
      body?.code ?? "UNKNOWN",
      body?.message ?? response.statusText,
      body?.field,
      body?.rows,
    );
  }
  if (response.status === 204) return undefined as T;
  return (await response.json()) as T;
}
