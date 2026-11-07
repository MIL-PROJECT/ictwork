import assert from "node:assert/strict";
import test from "node:test";
import { createSseParser, type SseEvent } from "../src/components/ai/sse.ts";

// AI 질의응답 답 흐름 읽기 (REQ-B01, slices/12 6.3)
const read = (chunks: string[]): SseEvent[] => {
  const events: SseEvent[] = [];
  const parser = createSseParser((e) => events.push(e));
  chunks.forEach((c) => parser.push(c));
  parser.end();
  return events;
};

test("joins an event split across chunks and multi-line data", () => {
  assert.deepEqual(read(["event:del", 'ta\ndata:{"text":"안', '녕"}\n\n', "data:a\ndata:b\n\n"]), [
    { event: "delta", data: '{"text":"안녕"}' },
    { event: "message", data: "a\nb" },
  ]);
});

test("drops a last event cut off before its blank line", () => {
  assert.deepEqual(read(['event:delta\ndata:{"text":"안녕"}\n\n', 'event:delta\ndata:{"text":"반']), [{ event: "delta", data: '{"text":"안녕"}' }]);
  assert.deepEqual(read(["event:done\ndata:{}\n"]), []);
});

test("a CR at the very end is a line break", () => {
  assert.deepEqual(read(["data:a\r\n\r"]), [{ event: "message", data: "a" }]);
});
