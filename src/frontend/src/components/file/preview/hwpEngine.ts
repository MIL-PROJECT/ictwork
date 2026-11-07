import wasmUrl from "@rhwp/core/rhwp_bg.wasm?url";

// 한글 문서 그리기 엔진(WASM)을 한 번만 받아 컴파일해 둔다. 문서 본문은 문서마다 따로 띄운 worker 안에만 둔다
// (bnoc-team-inpyo의 같은 구성을 옮김)
let prepared: Promise<WebAssembly.Module | undefined> | undefined;
export function prepareHwpEngine(): Promise<WebAssembly.Module | undefined> {
  if (prepared) return prepared;
  const controller = new AbortController();
  let timer: number | undefined;
  const compile = (async () => {
    const response = await fetch(wasmUrl, { credentials: "same-origin", signal: controller.signal });
    if (!response.ok) throw new Error("HwpEngineUnavailable");
    try {
      return await WebAssembly.compileStreaming(response.clone());
    } catch {
      return WebAssembly.compile(await response.arrayBuffer());
    }
  })();
  const deadline = new Promise<never>((_, reject) => {
    timer = window.setTimeout(() => {
      controller.abort();
      reject(new Error("HwpEngineTimeout"));
    }, 30000);
  });
  const pending = Promise.race([compile, deadline])
    .catch(() => {
      if (prepared === pending) prepared = undefined;
      return undefined;
    })
    .finally(() => window.clearTimeout(timer));
  prepared = pending;
  return pending;
}
