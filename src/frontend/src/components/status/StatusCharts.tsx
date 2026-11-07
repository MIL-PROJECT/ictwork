import type { TrendBucket } from "@/api/status";
import { useAppZoom } from "@/context/AuthContext";
import { useTheme } from "@/context/ThemeContext";
import type { ApexOptions } from "apexcharts";
import Chart from "react-apexcharts";
import { useTranslation } from "react-i18next";
import { SERIES_LIMIT, seriesColors } from "./statusUtil";

// 업무 현황 그래프 (slices/9_업무현황.md 3.3·3.4, 6장). apexcharts는 사용자 결정으로 템플릿에 있던 것을 쓴다(T-28).
// 그래프마다 같은 값을 담은 숨은 표를 두어 화면낭독기가 읽는다. 애니메이션은 끈다(인쇄)

// apexcharts는 범례·툴팁에 계열 이름을 HTML로 넣는다. 부서 이름에 <·& 등이 있어도 글 그대로 보이게 계열 이름만 넘기기 전에 바꾼다.
// 가로축 글(categories)은 SVG 글자로 그려 바꾸면 &lt; 가 그대로 보이므로 바꾸지 않는다(날짜만 들어감)
const HTML_ESCAPES: Record<string, string> = { "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" };
const escapeHtml = (text: string) => text.replace(/[&<>"']/g, (c) => HTML_ESCAPES[c]);

const bucketLabel = (b: TrendBucket) => (b.start === b.end ? b.start.slice(5) : `${b.start.slice(5)}~${b.end.slice(5)}`);

// 글자·격자 색은 테마 토큰(index.css)에서 읽는다
const token = (name: string) => getComputedStyle(document.documentElement).getPropertyValue(name).trim();

// apexcharts 접근성(키보드 초점·영어 안내)은 끈다: 그래프는 aria-hidden이고 화면낭독기는 숨은 표를 읽는다
function baseOptions(dark: boolean): ApexOptions {
  return {
    chart: { toolbar: { show: false }, animations: { enabled: false }, accessibility: { enabled: false }, fontFamily: "inherit", foreColor: token(dark ? "--color-gray-400" : "--color-gray-500") },
    grid: { borderColor: token(dark ? "--color-gray-700" : "--color-gray-200") },
    dataLabels: { enabled: false },
    legend: { position: "top", horizontalAlign: "left" },
    tooltip: { theme: dark ? "dark" : "light" },
  };
}

// 글자 크기 "크게"(REQ-008): apexcharts는 마우스 좌표를 화면 픽셀로 재므로 그래프를 배율 밖(zoom-reset)에서 그리고,
// 그 대신 높이와 축·범례 글자를 배율만큼 키운다(기본값 x축 12px·y축 11px·범례 12px). 인쇄용(light)은 배율 1
export function zoomChartFonts(options: ApexOptions, zoom: number): ApexOptions {
  if (zoom === 1) return options;
  const px = (base: number) => `${base * zoom}px`;
  const yaxis = Array.isArray(options.yaxis) ? options.yaxis : { ...options.yaxis, labels: { ...options.yaxis?.labels, style: { ...options.yaxis?.labels?.style, fontSize: px(11) } } };
  return {
    ...options,
    legend: { ...options.legend, fontSize: px(12) },
    xaxis: { ...options.xaxis, labels: { ...options.xaxis?.labels, style: { ...options.xaxis?.labels?.style, fontSize: px(12) } } },
    yaxis,
  };
}

function HiddenTable({ title, head, rows }: { title: string; head: string[]; rows: (string | number)[][] }) {
  const { t } = useTranslation("status");
  return (
    <table className="sr-only">
      <caption>{t("chartTable", { title })}</caption>
      <thead>
        <tr>{head.map((h) => <th key={h} scope="col">{h}</th>)}</tr>
      </thead>
      <tbody>
        {rows.map((r, i) => (
          <tr key={i}>{r.map((c, j) => <td key={j}>{c}</td>)}</tr>
        ))}
      </tbody>
    </table>
  );
}

// 통합 보기 추이: 구간마다 요청·완료 막대
export function TrendChart({ buckets, title, light }: { buckets: TrendBucket[]; title: string; light?: boolean }) {
  const { t } = useTranslation("status", { keyPrefix: "trend" });
  const { theme } = useTheme();
  const dark = theme === "dark" && !light;
  const appZoom = useAppZoom();
  const zoom = light ? 1 : appZoom;
  const [requestColor, completedColor] = seriesColors();
  const options: ApexOptions = {
    ...baseOptions(dark),
    colors: [requestColor, completedColor],
    plotOptions: { bar: { columnWidth: "55%", borderRadius: 3 } },
    xaxis: { categories: buckets.map(bucketLabel) },
    yaxis: { labels: { formatter: (v) => String(Math.round(v)) } },
  };
  return (
    <div className="print-avoid-break">
      <div aria-hidden="true" className="zoom-reset">
        <Chart
          type="bar"
          height={240 * zoom}
          options={zoomChartFonts(options, zoom)}
          series={[
            { name: escapeHtml(t("requests")), data: buckets.map((b) => b.requests) },
            { name: escapeHtml(t("completed")), data: buckets.map((b) => b.completed) },
          ]}
        />
      </div>
      <HiddenTable title={title} head={["", t("requests"), t("completed")]} rows={buckets.map((b) => [bucketLabel(b), b.requests, b.completed])} />
    </div>
  );
}

// 업무 유형별 가로 막대 (REQ-903): 유형(ID)마다, 이름은 "1대대 1중대 1반 · 전화"처럼 길어 넓게 두고 잘리면 전체 이름을 띄운다
export function TypeBars({ types, title }: { types: { typeId: number; name: string; count: number }[]; title: string }) {
  const max = Math.max(1, ...types.map((x) => x.count));
  return (
    <ul aria-label={title} className="space-y-2 print-avoid-break">
      {types.map((type) => (
        <li key={type.typeId} className="grid grid-cols-[minmax(0,14rem)_1fr_3rem] items-center gap-3 text-sm">
          <span className="truncate text-gray-700 dark:text-gray-300" title={type.name}>
            {type.name}
          </span>
          <span className="h-3 rounded-e bg-brand-500 print-exact" style={{ width: `${(type.count / max) * 100}%` }} aria-hidden="true" />
          <span className="text-end text-gray-800 dark:text-white/90">{type.count}</span>
        </li>
      ))}
    </ul>
  );
}

const DASHES = [0, 6, 2, 10, 4, 8, 3, 12];

// 부서별 주별 완료 추이 (T-25 늘 주별): 8곳 이하는 한 그래프에 부서 색 선(선 모양도 다르게), 9곳 이상은 부서마다 작은 그래프(같은 세로 눈금)
export function CompareTrend({ units, title, light }: { units: { name: string; weekly: TrendBucket[] }[]; title: string; light?: boolean }) {
  const { t } = useTranslation("status", { keyPrefix: "trend" });
  const { theme } = useTheme();
  const dark = theme === "dark" && !light;
  const appZoom = useAppZoom();
  const zoom = light ? 1 : appZoom;
  const weeks = units[0]?.weekly ?? [];
  const table = (
    <HiddenTable
      title={title}
      head={["", ...units.map((u) => u.name)]}
      rows={weeks.map((w, i) => [bucketLabel(w), ...units.map((u) => u.weekly[i]?.completed ?? 0)])}
    />
  );
  if (units.length <= SERIES_LIMIT) {
    const options: ApexOptions = {
      ...baseOptions(dark),
      colors: seriesColors().slice(0, units.length),
      stroke: { width: 2, curve: "straight", dashArray: units.map((_, i) => DASHES[i]) },
      markers: { size: 3 },
      xaxis: { categories: weeks.map(bucketLabel) },
      yaxis: { labels: { formatter: (v) => String(Math.round(v)) } },
    };
    return (
      <div className="print-avoid-break">
        <div aria-hidden="true" className="zoom-reset">
          <Chart type="line" height={260 * zoom} options={zoomChartFonts(options, zoom)} series={units.map((u) => ({ name: escapeHtml(u.name), data: u.weekly.map((w) => w.completed) }))} />
        </div>
        {table}
      </div>
    );
  }
  const max = Math.max(1, ...units.flatMap((u) => u.weekly.map((w) => w.completed)));
  return (
    <div>
      <div aria-hidden="true" className="grid grid-cols-2 gap-3 sm:grid-cols-3 print:grid-cols-3">
        {units.map((u) => (
          <div key={u.name} className="rounded-lg border border-gray-200 p-2 print-avoid-break dark:border-gray-800">
            <p className="truncate text-xs text-gray-700 dark:text-gray-300">{u.name}</p>
            <Sparkline values={u.weekly.map((w) => w.completed)} max={max} />
          </div>
        ))}
      </div>
      <p className="mt-2 text-xs text-gray-500 dark:text-gray-400">{t("week")}</p>
      {table}
    </div>
  );
}

// 작은 추이 선 (그래프 객체를 부서마다 만들지 않으려고 직접 그린다, 6장)
function Sparkline({ values, max }: { values: number[]; max: number }) {
  const width = 160;
  const height = 40;
  const step = values.length > 1 ? width / (values.length - 1) : 0;
  const points = values.map((v, i) => `${(i * step).toFixed(1)},${(height - 4 - (v / max) * (height - 8)).toFixed(1)}`).join(" ");
  return (
    <svg viewBox={`0 0 ${width} ${height}`} className="h-10 w-full text-brand-500" preserveAspectRatio="none">
      <polyline points={points} fill="none" stroke="currentColor" strokeWidth="2" strokeLinejoin="round" strokeLinecap="round" vectorEffect="non-scaling-stroke" />
    </svg>
  );
}

// 지표 하나 골라 보기: 부서마다 한 줄 가로 막대, 조직 순서 그대로(정렬하지 않음, REQ-908)
export function MetricBars({ rows, title, colors }: { rows: { name: string; value: number | null; text: string }[]; title: string; colors: string[] | null }) {
  const max = Math.max(1, ...rows.map((r) => r.value ?? 0));
  return (
    <ul aria-label={title} className="space-y-2 print-avoid-break">
      {rows.map((row, i) => (
        <li key={row.name} className="grid grid-cols-[10rem_1fr_4rem] items-center gap-3 text-sm">
          <span className="truncate text-gray-700 dark:text-gray-300">{row.name}</span>
          <span
            aria-hidden="true"
            className="h-3 rounded-e bg-brand-500 print-exact"
            style={{ width: `${((row.value ?? 0) / max) * 100}%`, ...(colors ? { backgroundColor: colors[i] } : {}) }}
          />
          <span className="text-end text-gray-800 dark:text-white/90">{row.text}</span>
        </li>
      ))}
    </ul>
  );
}
