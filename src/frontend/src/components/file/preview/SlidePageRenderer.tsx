import { useEffect, useState } from "react";
import { useTranslation } from "react-i18next";
import { child, children, descendants, openZip, relations } from "./officeZip";

const R_NS = "http://schemas.openxmlformats.org/officeDocument/2006/relationships";
const MAX_SLIDES = 200;
// 그림은 체계가 그대로 보일 수 있는 형식만(svg·emf 등은 뺀다)
const IMAGE_TYPES: Record<string, string> = { png: "image/png", jpg: "image/jpeg", jpeg: "image/jpeg", gif: "image/gif", bmp: "image/bmp" };

type Block =
  | { kind: "text"; title: boolean; paragraphs: { text: string; level: number }[] }
  | { kind: "table"; rows: { text: string; colSpan: number; rowSpan: number }[][] }
  | { kind: "image"; url: string };
type Slide = { blocks: Block[] };
// xsd:boolean("1"·"true")과 병합 칸 수(잘못된 값은 1)
const on = (value: string | null) => value === "1" || value === "true";
const span = (value: string | null) => Math.max(1, Math.floor(Number(value ?? 1)) || 1);
// 꼬리말·날짜·쪽 번호 자리표시자는 본문이 아니라 뺀다
const SKIPPED_PLACEHOLDERS = new Set(["sldNum", "dt", "ftr"]);

/** 글상자 하나의 문단: 줄 바꿈(br)은 줄로, 필드(fld, 쪽 번호 등)는 글로 */
function paragraphs(body: Element) {
  return children(body, "p")
    .map((p) => ({
      text: Array.from(p.childNodes)
        .filter((n): n is Element => n.nodeType === 1)
        .map((n) => (n.localName === "br" ? "\n" : n.localName === "r" || n.localName === "fld" ? (child(n, "t")?.textContent ?? "") : ""))
        .join(""),
      level: Number(child(p, "pPr")?.getAttribute("lvl") ?? 0),
    }))
    .filter((p) => p.text.trim() !== "");
}

/**
 * 파워포인트(pptx): 슬라이드마다 글상자·표·그림을 놓인 순서대로 읽는다. 원본 배치·서식·도형·애니메이션은 보이지 않고 내용만 보인다.
 * 그림은 이 화면의 blob 주소로 보인다(끝나면 부르는 쪽이 revoke)
 */
async function readPresentation(bytes: Uint8Array, urls: string[]): Promise<{ slides: Slide[]; truncated: boolean }> {
  const zip = await openZip(bytes);
  const presentation = await zip.xml("ppt/presentation.xml");
  if (!presentation) throw new Error("NotPresentation");
  const rels = await relations(zip, "ppt/presentation.xml");
  const slides: Slide[] = [];
  // 같은 그림(ppt/media/…)을 여러 슬라이드가 쓰면 한 번만 읽어 주소를 같이 쓴다
  const images = new Map<string, string | null>();
  const imageUrl = async (target: string, mime: string) => {
    if (!images.has(target)) {
      const data = await zip.bytes(target);
      const url = data ? URL.createObjectURL(new Blob([data], { type: mime })) : null;
      if (url) urls.push(url);
      images.set(target, url);
    }
    return images.get(target) ?? null;
  };
  const ids = descendants(presentation, "sldId");
  for (const id of ids.slice(0, MAX_SLIDES)) {
    const path = rels.get(id.getAttributeNS(R_NS, "id") ?? "");
    const doc = path ? await zip.xml(path) : null;
    if (!path || !doc) continue;
    const slideRels = await relations(zip, path);
    const blocks: Block[] = [];
    const walk = async (node: Element) => {
      for (const shape of Array.from(node.children)) {
        switch (shape.localName) {
          case "sp": {
            const body = child(shape, "txBody");
            const type = descendants(shape, "ph")[0]?.getAttribute("type");
            if (type && SKIPPED_PLACEHOLDERS.has(type)) break;
            const list = body ? paragraphs(body) : [];
            if (list.length) blocks.push({ kind: "text", title: type === "title" || type === "ctrTitle", paragraphs: list });
            break;
          }
          case "pic": {
            const embed = descendants(shape, "blip")[0]?.getAttributeNS(R_NS, "embed");
            const target = embed ? slideRels.get(embed) : undefined;
            const mime = target ? IMAGE_TYPES[target.slice(target.lastIndexOf(".") + 1).toLowerCase()] : undefined;
            const url = target && mime ? await imageUrl(target, mime) : null;
            if (url) blocks.push({ kind: "image", url });
            break;
          }
          case "graphicFrame": {
            const table = descendants(shape, "tbl")[0];
            if (table) {
              // 병합: 덮인 칸(hMerge·vMerge)은 빼고 첫 칸이 gridSpan·rowSpan만큼 넓어진다
              const rows = descendants(table, "tr").map((tr) => children(tr, "tc")
                .filter((tc) => !on(tc.getAttribute("hMerge")) && !on(tc.getAttribute("vMerge")))
                .map((tc) => {
                  const body = child(tc, "txBody");
                  return {
                    text: body ? paragraphs(body).map((p) => p.text).join("\n") : "",
                    colSpan: span(tc.getAttribute("gridSpan")),
                    rowSpan: span(tc.getAttribute("rowSpan")),
                  };
                }));
              if (rows.length) blocks.push({ kind: "table", rows });
            }
            break;
          }
          case "grpSp":
            await walk(shape);
            break;
        }
      }
    };
    const tree = descendants(doc, "spTree")[0];
    if (tree) await walk(tree);
    slides.push({ blocks });
  }
  if (slides.length === 0) throw new Error("NoSlides");
  return { slides, truncated: ids.length > MAX_SLIDES };
}

/** 파워포인트 미리보기: 슬라이드를 차례로 카드로 보인다(제목·글·표·그림). 문서가 바뀌면 부르는 쪽이 key로 새로 만든다 */
export default function SlidePageRenderer({ bytes, fileName }: { bytes: Uint8Array; fileName: string }) {
  const { t } = useTranslation("file");
  const [slides, setSlides] = useState<Slide[] | null>(null);
  const [truncated, setTruncated] = useState(false);
  const [failed, setFailed] = useState(false);
  useEffect(() => {
    let active = true;
    const urls: string[] = [];
    readPresentation(bytes, urls)
      .then(
        (result) => {
          if (!active) return;
          setSlides(result.slides);
          setTruncated(result.truncated);
        },
        () => active && setFailed(true),
      )
      // 다 읽기 전에 닫혔으면 그사이 만든 그림 주소도 놓는다
      .finally(() => {
        if (!active) urls.forEach((url) => URL.revokeObjectURL(url));
      });
    return () => {
      active = false;
      urls.forEach((url) => URL.revokeObjectURL(url));
    };
  }, [bytes]);
  if (failed) return <p role="alert" className="p-5 text-sm text-error-600 dark:text-error-400">{t("preview.slideFailed")}</p>;
  if (!slides) return <p role="status" className="text-sm text-gray-500 dark:text-gray-400">{t("preview.slideOpening")}</p>;
  return (
    <div className="h-full overflow-auto rounded-xl bg-gray-100 p-4 dark:bg-gray-800">
      <p className="mb-3 text-xs text-gray-500 dark:text-gray-400">{t("preview.slideNotice")}</p>
      {truncated && <p className="mb-3 text-xs text-gray-500 dark:text-gray-400">{t("preview.slideTruncated", { count: MAX_SLIDES })}</p>}
      <ol className="space-y-4">
        {slides.map((slide, index) => (
          <li key={index} aria-label={t("preview.slideLabel", { name: fileName, n: index + 1, total: slides.length })}
            className="mx-auto max-w-4xl space-y-3 rounded-xl bg-white p-6 shadow-theme-xs dark:bg-gray-900">
            <p className="text-xs font-medium text-gray-400">{index + 1} / {slides.length}</p>
            {slide.blocks.length === 0 && <p className="text-sm text-gray-500 dark:text-gray-400">{t("preview.slideEmpty")}</p>}
            {slide.blocks.map((block, b) => {
              if (block.kind === "image") return <img key={b} src={block.url} alt={t("preview.slideImage", { n: index + 1 })} className="max-h-80 max-w-full rounded-md object-contain" />;
              if (block.kind === "table")
                return (
                  <div key={b} className="overflow-x-auto">
                    <table className="border-collapse text-sm text-gray-800 dark:text-white/90">
                      <tbody>
                        {block.rows.map((row, r) => (
                          <tr key={r}>{row.map((cell, c) => <td key={c} colSpan={cell.colSpan} rowSpan={cell.rowSpan} className="border border-gray-200 px-2 py-1 align-top whitespace-pre-wrap dark:border-gray-700">{cell.text}</td>)}</tr>
                        ))}
                      </tbody>
                    </table>
                  </div>
                );
              if (block.title) return <h3 key={b} className="text-lg font-semibold whitespace-pre-wrap text-gray-900 dark:text-white">{block.paragraphs.map((p) => p.text).join("\n")}</h3>;
              return (
                <ul key={b} className="space-y-1 text-sm text-gray-700 dark:text-gray-300">
                  {block.paragraphs.map((p, i) => <li key={i} className="whitespace-pre-wrap" style={{ paddingInlineStart: `${p.level * 1.25}rem` }}>{p.text}</li>)}
                </ul>
              );
            })}
          </li>
        ))}
      </ol>
    </div>
  );
}
