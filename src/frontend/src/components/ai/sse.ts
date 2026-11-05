// text/event-stream 읽기 (slices/12 6.3): fetch 응답 본문을 줄 단위로 읽어 이벤트(event 이름 + data)로 넘긴다.
// 이벤트가 조각 사이에서 끊겨 와도 남은 글을 다음 조각과 이어 읽는다

export interface SseEvent {
  event: string;
  data: string;
}

export function createSseParser(onEvent: (e: SseEvent) => void) {
  let buffer = "";
  let name = "";
  let data: string[] = [];

  const dispatch = () => {
    if (data.length > 0) onEvent({ event: name || "message", data: data.join("\n") });
    name = "";
    data = [];
  };
  const line = (text: string) => {
    if (text === "") return dispatch();
    if (text.startsWith(":")) return; // 주석(연결 유지용)
    const colon = text.indexOf(":");
    const field = colon < 0 ? text : text.slice(0, colon);
    let value = colon < 0 ? "" : text.slice(colon + 1);
    if (value.startsWith(" ")) value = value.slice(1);
    if (field === "event") name = value;
    else if (field === "data") data.push(value);
  };

  return {
    push(chunk: string) {
      buffer += chunk;
      for (;;) {
        const match = /\r\n|\r|\n/.exec(buffer);
        if (!match) break;
        // 끝의 \r은 다음 조각의 \n과 한 줄바꿈일 수 있어 기다린다
        if (match[0] === "\r" && match.index === buffer.length - 1) break;
        line(buffer.slice(0, match.index));
        buffer = buffer.slice(match.index + match[0].length);
      }
    },
    end() {
      if (buffer) line(buffer);
      buffer = "";
      dispatch();
    },
  };
}

/** 응답 본문을 끝까지 읽는다. 멈춤(AbortController)이면 AbortError로 끝난다 */
export async function readEventStream(response: Response, onEvent: (e: SseEvent) => void): Promise<void> {
  if (!response.body) return;
  const reader = response.body.getReader();
  const decoder = new TextDecoder();
  const parser = createSseParser(onEvent);
  for (;;) {
    const { done, value } = await reader.read();
    if (done) break;
    parser.push(decoder.decode(value, { stream: true }));
  }
  parser.push(decoder.decode());
  parser.end();
}

/** data의 JSON. JSON이 아니면 글 그대로 */
export function parseData(data: string): unknown {
  try {
    return JSON.parse(data);
  } catch {
    return data;
  }
}
