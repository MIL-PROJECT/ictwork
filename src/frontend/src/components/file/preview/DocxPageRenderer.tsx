import { useEffect, useMemo, useRef, useState } from "react";
import { useTranslation } from "react-i18next";
import zipScript from "jszip/dist/jszip.min.js?raw";
// docx-preview는 exports에 dist 파일을 열어 두지 않아 node_modules 경로로 글자 그대로 읽는다
import docxScript from "../../../../node_modules/docx-preview/dist/docx-preview.js?raw";

/**
 * 워드(DOCX) 원본을 쪽 모양으로 보인다. 그리는 코드는 바깥과 막힌 sandbox iframe(불투명 출처) 안에서만 돌아
 * 문서 속 링크·글꼴·내장 내용이 체계나 네트워크에 닿지 못한다. 문서가 바뀌면 부르는 쪽이 key로 새로 만든다 (bnoc-team-inpyo에서 옮김)
 */
export default function DocxPageRenderer({ bytes, fileName }: { bytes: Uint8Array; fileName: string }) {
  const { t } = useTranslation("file");
  const frame = useRef<HTMLIFrameElement>(null);
  const [status, setStatus] = useState<"loading" | "ready" | "failed">("loading");
  const channel = useMemo(() => crypto.randomUUID(), []);
  const source = useMemo(() => {
    const script = (value: string) => value.replace(/<\/script/gi, '<\\/script');
    return `<!doctype html><html lang="ko"><head><meta charset="utf-8">
<meta http-equiv="Content-Security-Policy" content="default-src 'none'; script-src 'nonce-${channel}'; style-src 'unsafe-inline'; img-src data: blob:; font-src data: blob:; connect-src 'none'; base-uri 'none'; form-action 'none'">
<style>html,body{margin:0;color:#172033;background:#f2f4f7;font-family:Arial,sans-serif}#pages{overflow:auto}#pages .docx-wrapper{padding:16px}#pages section.docx{margin:0 auto 16px;box-shadow:0 1px 5px #0002}a{pointer-events:none}</style>
<script nonce="${channel}">${script(zipScript)}</script><script nonce="${channel}">${script(docxScript)}</script></head><body><main id="pages"></main>
<script nonce="${channel}">
let rendered=false;
addEventListener('message',async event=>{
  if(rendered||event.source!==parent||event.data?.channel!==${JSON.stringify(channel)}||!(event.data.buffer instanceof ArrayBuffer))return;
  rendered=true;
  try{
    await docx.renderAsync(event.data.buffer,document.getElementById('pages'),undefined,{useBase64URL:true,ignoreFonts:true,renderAltChunks:false,renderComments:false,breakPages:true,ignoreLastRenderedPageBreak:false});
    const pages=document.querySelectorAll('section.docx');
    if(!pages.length)throw new Error('empty');
    document.querySelectorAll('a').forEach(link=>{link.removeAttribute('href');link.removeAttribute('target');});
    parent.postMessage({channel:${JSON.stringify(channel)},status:'ready'},'*');
  }catch{parent.postMessage({channel:${JSON.stringify(channel)},status:'failed'},'*');}
});
</script></body></html>`;
  }, [channel]);

  useEffect(() => {
    const timer = window.setTimeout(() => setStatus("failed"), 30000);
    const receive = (event: MessageEvent) => {
      if (event.source !== frame.current?.contentWindow || event.data?.channel !== channel) return;
      if (event.data.status === "ready" || event.data.status === "failed") {
        window.clearTimeout(timer);
        setStatus(event.data.status);
      }
    };
    window.addEventListener("message", receive);
    return () => {
      window.clearTimeout(timer);
      window.removeEventListener("message", receive);
    };
  }, [channel]);

  return (
    <div className="relative h-full min-h-0">
      {status === "loading" && (
        <p role="status" className="absolute top-3 left-3 z-10 rounded-lg bg-white/90 px-3 py-1.5 text-sm text-gray-600 shadow-theme-xs dark:bg-gray-900/90 dark:text-gray-300">
          {t("preview.docxOpening")}
        </p>
      )}
      {status === "failed" ? (
        <p role="alert" className="p-5 text-sm text-error-600 dark:text-error-400">
          {t("preview.docxFailed")}
        </p>
      ) : (
        <iframe
          ref={frame}
          title={t("preview.frameTitle", { name: fileName })}
          sandbox="allow-scripts"
          referrerPolicy="no-referrer"
          srcDoc={source}
          className="h-full w-full rounded-xl border-0 bg-gray-100"
          onLoad={() => {
            const buffer = bytes.slice().buffer;
            frame.current?.contentWindow?.postMessage({ channel, buffer }, "*", [buffer]);
          }}
        />
      )}
    </div>
  );
}
