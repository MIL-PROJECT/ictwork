/** 열 고르기 설정은 보는 사람의 브라우저에 저장한다(읽지 못하면 처음 열) (REQ-402·405) */
export function readSavedColumns(key: string, defaults: string[]): string[] {
  try {
    const raw = localStorage.getItem(key);
    if (!raw) return defaults;
    const saved = (JSON.parse(raw) as unknown[]).filter((k): k is string => typeof k === "string");
    return saved.length > 0 ? saved : defaults;
  } catch {
    return defaults;
  }
}

export function writeSavedColumns(key: string, columns: string[]) {
  try {
    localStorage.setItem(key, JSON.stringify(columns));
  } catch {
    // 저장하지 못해도 화면은 지금 설정대로 둔다
  }
}
