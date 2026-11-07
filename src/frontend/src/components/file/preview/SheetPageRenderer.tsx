import { cn } from "@/utils";
import { useEffect, useState } from "react";
import { useTranslation } from "react-i18next";
import { child, children, descendants, openZip, relations } from "./officeZip";

const R_NS = "http://schemas.openxmlformats.org/officeDocument/2006/relationships";
// 화면이 느려지지 않게 시트마다 앞부분만 보인다
const MAX_ROWS = 500;
const MAX_COLS = 50;

type Cell = { text: string; colSpan: number; rowSpan: number } | null; // null = 병합으로 덮인 칸
type Sheet = { name: string; rows: Cell[][]; truncated: boolean };

/** "B12" → [열 1, 행 11] (0부터) */
function position(ref: string) {
  const match = /^([A-Z]+)(\d+)$/.exec(ref);
  if (!match) return null;
  const col = [...match[1]].reduce((n, ch) => n * 26 + ch.charCodeAt(0) - 64, 0) - 1;
  return [col, Number(match[2]) - 1] as const;
}
const columnName = (index: number) => {
  let name = "";
  for (let n = index + 1; n > 0; n = Math.floor((n - 1) / 26)) name = String.fromCharCode(65 + ((n - 1) % 26)) + name;
  return name;
};
// 글자(t)만 이어 붙인다. 읽는 법(rPh)은 뺀다
const textOf = (node: Element) =>
  descendants(node, "t").filter((t) => (t.parentNode as Element | null)?.localName !== "rPh").map((t) => t.textContent ?? "").join("");

// 날짜·시각 서식: 기본 서식 번호(27~36·50~58은 한국어 등 동아시아 판의 기본 날짜 서식)이거나, 따옴표·[] 안을 뺀 서식의 글자로 가린다.
// 연·일이 있으면 날짜, 시·초가 있으면 시각을 보인다(월 m은 분과 같은 글자라 따로 보지 않는다)
type DateStyle = { date: boolean; time: boolean; elapsed?: boolean } | null;
const BUILTIN_DATE_ONLY = new Set([14, 15, 16, 17, 27, 28, 29, 30, 31, 34, 35, 36, 50, 51, 52, 53, 54, 57, 58]);
const BUILTIN_TIME_ONLY = new Set([18, 19, 20, 21, 32, 33, 45, 46, 47, 55, 56]);
function dateStyle(id: number, code: string | undefined): DateStyle {
  if (BUILTIN_DATE_ONLY.has(id)) return { date: true, time: false };
  if (BUILTIN_TIME_ONLY.has(id)) return { date: false, time: true };
  if (id === 22) return { date: true, time: true };
  if (code === undefined) return null;
  // 경과 시간([h]:mm 등): 24시간을 넘겨도 시간 수로 보인다
  if (/\[(h+|m+|s+)\]/i.test(code.replace(/"[^"]*"/g, ""))) return { date: false, time: true, elapsed: true };
  const letters = code.replace(/"[^"]*"|\[[^\]]*\]|\\./g, "");
  const date = /[yd]/i.test(letters);
  const time = /[hs]/i.test(letters);
  if (date || time) return { date, time };
  return /m/i.test(letters) ? { date: true, time: false } : null;
}
const pad = (n: number) => String(n).padStart(2, "0");
function excelDate(serial: number, date1904: boolean, style: { date: boolean; time: boolean; elapsed?: boolean }) {
  if (style.elapsed) {
    const minutes = Math.round(serial * 1440);
    return `${Math.floor(minutes / 60)}:${pad(minutes % 60)}`;
  }
  // 일련값은 정확한 값보다 조금 작게 저장되기도 해 초 단위로 반올림한다(14:30이 14:29로 보이지 않게)
  const seconds = Math.round((serial + (date1904 ? 1462 : 0)) * 86400);
  const date = new Date(Date.UTC(1899, 11, 30) + seconds * 1000);
  const day = `${date.getUTCFullYear()}-${pad(date.getUTCMonth() + 1)}-${pad(date.getUTCDate())}`;
  const time = `${pad(date.getUTCHours())}:${pad(date.getUTCMinutes())}`;
  if (style.date && style.time) return `${day} ${time}`;
  return style.time ? time : day;
}

/** 엑셀(xlsx): 시트마다 칸의 값(수식은 저장된 결과)과 병합을 읽는다. 글꼴·색·그림·차트는 보이지 않는다 */
async function readWorkbook(bytes: Uint8Array): Promise<Sheet[]> {
  const zip = await openZip(bytes);
  const workbook = await zip.xml("xl/workbook.xml");
  if (!workbook) throw new Error("NotWorkbook");
  const rels = await relations(zip, "xl/workbook.xml");
  const date1904 = ["1", "true"].includes(descendants(workbook, "workbookPr")[0]?.getAttribute("date1904") ?? "");
  const shared = await zip.xml("xl/sharedStrings.xml");
  const strings = shared ? children(shared.documentElement, "si").map(textOf) : [];
  const styles = await zip.xml("xl/styles.xml");
  const formats = new Map(styles ? descendants(styles, "numFmt").map((f) => [Number(f.getAttribute("numFmtId")), f.getAttribute("formatCode") ?? ""]) : []);
  const cellXfs = styles ? descendants(styles, "cellXfs")[0] : undefined;
  const dateStyles: DateStyle[] = cellXfs ? children(cellXfs, "xf").map((xf) => { const id = Number(xf.getAttribute("numFmtId") ?? 0); return dateStyle(id, formats.get(id)); }) : [];

  const sheets: Sheet[] = [];
  for (const entry of descendants(workbook, "sheet")) {
    if (entry.getAttribute("state") === "hidden" || entry.getAttribute("state") === "veryHidden") continue;
    const path = rels.get(entry.getAttributeNS(R_NS, "id") ?? "");
    const doc = path ? await zip.xml(path) : null;
    if (!doc) continue;
    const values = new Map<string, string>();
    let maxRow = -1;
    let maxCol = -1;
    let truncated = false;
    // 칸 위치(r)는 생략될 수 있다: 없으면 앞 행·앞 칸 다음으로 본다
    const cells: { cell: Element; col: number; row: number }[] = [];
    let rowIndex = -1;
    for (const rowElement of descendants(doc, "row")) {
      rowIndex = rowElement.getAttribute("r") ? Number(rowElement.getAttribute("r")) - 1 : rowIndex + 1;
      let colIndex = -1;
      for (const cell of children(rowElement, "c")) {
        const at = position(cell.getAttribute("r") ?? "");
        colIndex = at ? at[0] : colIndex + 1;
        cells.push({ cell, col: colIndex, row: at ? at[1] : rowIndex });
      }
    }
    for (const { cell, col, row } of cells) {
      if (row >= MAX_ROWS || col >= MAX_COLS) {
        truncated = true;
        continue;
      }
      const type = cell.getAttribute("t");
      const raw = child(cell, "v")?.textContent ?? "";
      let text: string;
      if (raw === "" && type !== "inlineStr") text = "";
      else if (type === "s") text = strings[Number(raw)] ?? "";
      else if (type === "inlineStr") text = child(cell, "is") ? textOf(child(cell, "is") as Element) : "";
      else if (type === "b") text = raw === "1" ? "TRUE" : "FALSE";
      else if (type === "str" || type === "e") text = raw;
      else {
        const n = Number(raw);
        const style = dateStyles[Number(cell.getAttribute("s") ?? 0)];
        text = Number.isNaN(n) ? raw : style ? excelDate(n, date1904, style) : String(Number(n.toPrecision(15)));
      }
      if (text === "") continue;
      values.set(`${row}:${col}`, text);
      maxRow = Math.max(maxRow, row);
      maxCol = Math.max(maxCol, col);
    }
    // 병합: 왼쪽 위 칸이 넓어지고 나머지 칸은 그리지 않는다
    const spans = new Map<string, [number, number]>();
    const covered = new Set<string>();
    for (const merge of descendants(doc, "mergeCell")) {
      const [from, to] = (merge.getAttribute("ref") ?? "").split(":").map(position);
      if (!from || !to) continue;
      const [c1, r1] = from;
      const [c2, r2] = [Math.min(to[0], MAX_COLS - 1), Math.min(to[1], MAX_ROWS - 1)];
      if (c1 >= MAX_COLS || r1 >= MAX_ROWS) continue;
      spans.set(`${r1}:${c1}`, [c2 - c1 + 1, r2 - r1 + 1]);
      for (let r = r1; r <= r2; r++) for (let c = c1; c <= c2; c++) if (r !== r1 || c !== c1) covered.add(`${r}:${c}`);
      maxRow = Math.max(maxRow, r2);
      maxCol = Math.max(maxCol, c2);
    }
    const rows: Cell[][] = [];
    for (let r = 0; r <= maxRow; r++) {
      const line: Cell[] = [];
      for (let c = 0; c <= maxCol; c++) {
        const key = `${r}:${c}`;
        const span = spans.get(key);
        line.push(covered.has(key) ? null : { text: values.get(key) ?? "", colSpan: span?.[0] ?? 1, rowSpan: span?.[1] ?? 1 });
      }
      rows.push(line);
    }
    sheets.push({ name: entry.getAttribute("name") ?? "", rows, truncated });
  }
  if (sheets.length === 0) throw new Error("NoSheets");
  return sheets;
}

/** 엑셀 미리보기: 시트 단추로 고르고 표로 본다(열 이름·행 번호 포함). 문서가 바뀌면 부르는 쪽이 key로 새로 만든다 */
export default function SheetPageRenderer({ bytes, fileName }: { bytes: Uint8Array; fileName: string }) {
  const { t } = useTranslation("file");
  const [sheets, setSheets] = useState<Sheet[] | null>(null);
  const [failed, setFailed] = useState(false);
  const [current, setCurrent] = useState(0);
  useEffect(() => {
    let active = true;
    readWorkbook(bytes).then(
      (list) => active && setSheets(list),
      () => active && setFailed(true),
    );
    return () => {
      active = false;
    };
  }, [bytes]);
  if (failed) return <p role="alert" className="p-5 text-sm text-error-600 dark:text-error-400">{t("preview.sheetFailed")}</p>;
  if (!sheets) return <p role="status" className="text-sm text-gray-500 dark:text-gray-400">{t("preview.sheetOpening")}</p>;
  const sheet = sheets[current];
  const width = sheet.rows[0]?.length ?? 0;
  return (
    <div className="flex h-full min-h-0 flex-col gap-3">
      {sheets.length > 1 && (
        <div role="group" aria-label={t("preview.sheets")} className="flex flex-wrap gap-1.5">
          {sheets.map((item, index) => (
            <button key={index} type="button" aria-pressed={index === current} onClick={() => setCurrent(index)}
              className={cn("rounded-md px-3 py-1 text-sm", index === current ? "bg-brand-500 font-medium text-white" : "border border-gray-200 text-gray-700 hover:bg-gray-50 dark:border-gray-700 dark:text-gray-300 dark:hover:bg-white/5")}>
              {item.name}
            </button>
          ))}
        </div>
      )}
      {sheet.truncated && <p className="text-xs text-gray-500 dark:text-gray-400">{t("preview.sheetTruncated", { rows: MAX_ROWS, cols: MAX_COLS })}</p>}
      <div className="min-h-0 flex-1 overflow-auto rounded-xl border border-gray-200 bg-white dark:border-gray-700 dark:bg-gray-900">
        {sheet.rows.length === 0 ? (
          <p className="p-5 text-sm text-gray-500 dark:text-gray-400">{t("preview.sheetEmpty")}</p>
        ) : (
          <table className="border-collapse text-sm text-gray-800 dark:text-white/90">
            <caption className="sr-only">{t("preview.sheetCaption", { name: fileName, sheet: sheet.name })}</caption>
            <thead>
              <tr>
                <td aria-hidden="true" className="sticky top-0 left-0 z-20 border border-gray-200 bg-gray-100 px-2 dark:border-gray-700 dark:bg-gray-800" />
                {Array.from({ length: width }, (_, c) => (
                  <th key={c} scope="col" className="sticky top-0 z-10 min-w-20 border border-gray-200 bg-gray-100 px-2 py-1 text-xs font-medium text-gray-500 dark:border-gray-700 dark:bg-gray-800 dark:text-gray-400">{columnName(c)}</th>
                ))}
              </tr>
            </thead>
            <tbody>
              {sheet.rows.map((row, r) => (
                <tr key={r}>
                  <th scope="row" className="sticky left-0 z-10 border border-gray-200 bg-gray-100 px-2 text-xs font-medium text-gray-500 dark:border-gray-700 dark:bg-gray-800 dark:text-gray-400">{r + 1}</th>
                  {row.map((cell, c) => cell && (
                    <td key={c} colSpan={cell.colSpan} rowSpan={cell.rowSpan} className="max-w-80 border border-gray-200 px-2 py-1 align-top break-words whitespace-pre-wrap dark:border-gray-700">{cell.text}</td>
                  ))}
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </div>
    </div>
  );
}
