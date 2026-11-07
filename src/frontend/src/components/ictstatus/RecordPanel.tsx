import { type BoxStats, downloadStatsExcel, fetchStats } from "@/api/ictStatus";
import { zoomChartFonts } from "@/components/status/StatusCharts";
import Button from "@/components/ui/button/Button";
import { INPUT_CLASS, LABEL_CLASS } from "@/components/work/format";
import { useAppZoom } from "@/context/AuthContext";
import { useTheme } from "@/context/ThemeContext";
import { cn } from "@/utils";
import type { ApexOptions } from "apexcharts";
import { useEffect, useState } from "react";
import Chart from "react-apexcharts";
import { useTranslation } from "react-i18next";
import { BOX_TONE } from "./boxColors";
import { timeText, useIctError } from "./boxFields";

const pad = (n: number) => String(n).padStart(2, "0");
const day = (d: Date) => `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`;
const VALID_DAY = /^\d{4}-\d{2}-\d{2}$/;

/** n개월 앞 같은 날(그 달에 없으면 말일, 서버 LocalDate.minusMonths와 같음) */
const monthsAgo = (d: Date, months: number) => {
  const first = new Date(d.getFullYear(), d.getMonth() - months, 1);
  const last = new Date(first.getFullYear(), first.getMonth() + 1, 0).getDate();
  return new Date(first.getFullYear(), first.getMonth(), Math.min(d.getDate(), last));
};

/** 서버(IctStatsService.period)와 같은 기간 규칙: 최근 13개월 안, 오늘까지, 시작 ≤ 끝, 1년 이하 */
const periodValid = (from: string, to: string, min: string, max: string) => {
  if (!VALID_DAY.test(from) || !VALID_DAY.test(to) || from < min || to > max || from > to) return false;
  const [y, m, d] = from.split("-").map(Number);
  return to <= day(new Date(y + 1, m - 1, d - 1));
};

/**
 * [기록] (REQ-503, slices/15 7장·13.4): 기간(처음 이번 달, 13개월 안 1년 이하, 끝은 지금까지), 객관식·드롭다운 칸은 값마다 머문 시간 막대(선택지 색)와 바뀐 횟수,
 * 숫자 칸은 계단 추이 그래프, [엑셀 내려받기]. 그래프·막대는 같은 값을 글로도 보인다
 */
export default function RecordPanel({ boxId }: { boxId: number }) {
  const { t, i18n } = useTranslation("ictStatus");
  const describe = useIctError();
  const today = new Date();
  const [from, setFrom] = useState(day(new Date(today.getFullYear(), today.getMonth(), 1)));
  const [to, setTo] = useState(day(today));
  const [stats, setStats] = useState<BoxStats | null>(null);
  const [error, setError] = useState("");
  const [downloading, setDownloading] = useState(false);
  const min = day(monthsAgo(today, 13));
  const max = day(today);

  // 기간이 틀리면 서버에 묻기 전에 안내한다
  const checked = () => {
    if (periodValid(from, to, min, max)) return true;
    setError(t("record.invalid"));
    return false;
  };

  const load = (f: string, tt: string) => {
    if (!checked()) return;
    setError("");
    fetchStats(boxId, f, tt).then(setStats, (e) => setError(describe(e)));
  };

  // 엑셀은 파일로 받아 저장하고, 실패하면 JSON 오류 화면 대신 이 칸에 안내한다
  const download = async () => {
    if (downloading || !checked()) return;
    setError("");
    setDownloading(true);
    try {
      await downloadStatsExcel(boxId, from, to, i18n.language.startsWith("en") ? "en" : "ko");
    } catch (e) {
      setError(describe(e));
    } finally {
      setDownloading(false);
    }
  };
  useEffect(() => {
    // 처음 기간(이번 달)으로 한 번 읽는다. 그 뒤는 [보기]로
    const now = new Date();
    fetchStats(boxId, day(new Date(now.getFullYear(), now.getMonth(), 1)), day(now)).then(setStats, (e) => setError(describe(e)));
  }, [boxId, describe]);

  const duration = (seconds: number) => {
    const days = Math.floor(seconds / 86400);
    const hours = Math.floor((seconds % 86400) / 3600);
    const minutes = Math.floor((seconds % 3600) / 60);
    return t("record.duration", { days, hours, minutes });
  };

  return (
    <div className="space-y-5">
      <div className="flex flex-wrap items-end gap-3">
        <div>
          <label htmlFor="record-from" className={LABEL_CLASS}>
            {t("record.from")}
          </label>
          <input id="record-from" type="date" min={min} max={max} value={from} onChange={(e) => setFrom(e.target.value)} aria-describedby="record-help" className={INPUT_CLASS} />
        </div>
        <div>
          <label htmlFor="record-to" className={LABEL_CLASS}>
            {t("record.to")}
          </label>
          <input id="record-to" type="date" min={min} max={max} value={to} onChange={(e) => setTo(e.target.value)} aria-describedby="record-help" className={INPUT_CLASS} />
        </div>
        <Button size="sm" onClick={() => load(from, to)}>
          {t("record.show")}
        </Button>
        <button
          type="button"
          onClick={() => void download()}
          disabled={downloading}
          aria-busy={downloading}
          className="inline-flex h-10 items-center rounded-lg border border-gray-300 px-4 text-sm font-medium text-gray-700 hover:bg-gray-50 disabled:cursor-not-allowed disabled:opacity-60 dark:border-gray-700 dark:text-gray-300 dark:hover:bg-white/5"
        >
          {t("record.excel")}
        </button>
      </div>
      <p id="record-help" className="text-theme-xs text-gray-500 dark:text-gray-400">
        {t("record.help")}
      </p>
      {error && (
        <p role="alert" className="text-sm text-error-600 dark:text-error-400">
          {error}
        </p>
      )}
      {stats && stats.choices.length === 0 && stats.numbers.length === 0 && <p className="text-sm text-gray-500 dark:text-gray-400">{t("record.none")}</p>}
      {stats?.choices.map((c) => (
        <section key={`${c.key}-${c.kind}`} className="space-y-2">
          <h5 className="text-sm font-medium text-gray-800 dark:text-white/90">
            {c.title}
            {c.deleted && <span className="ms-1 text-gray-500 dark:text-gray-400">{t("record.deletedField")}</span>}
            <span className="ms-2 text-theme-xs font-normal text-gray-500 dark:text-gray-400">{t("record.changes", { count: c.changes })}</span>
          </h5>
          <div className="flex h-4 w-full overflow-hidden rounded bg-gray-100 dark:bg-gray-800" aria-hidden>
            {c.values.map((v) => (
              <div key={v.value} className={BOX_TONE[v.color].bar} style={{ width: `${v.ratio * 100}%` }} />
            ))}
          </div>
          <ul className="space-y-1">
            {c.values.map((v) => (
              <li key={v.value} className="flex flex-wrap items-center gap-2 text-sm text-gray-700 dark:text-gray-300">
                <span className={cn("size-2.5 rounded-full", BOX_TONE[v.color].dot)} aria-hidden />
                <span className="font-medium">
                  {v.value === "OTHER" ? t("record.other") : v.label}
                  {v.deletedOption && <span className="ms-1 text-gray-500 dark:text-gray-400">{t("record.deletedOption")}</span>}
                </span>
                <span>{duration(v.seconds)}</span>
                <span className="text-gray-500 dark:text-gray-400">{t("record.ratio", { ratio: (v.ratio * 100).toFixed(1) })}</span>
              </li>
            ))}
          </ul>
        </section>
      ))}
      {stats?.numbers.map((n) => <NumberTrend key={n.key} stats={n} />)}
    </div>
  );
}

/** 숫자 칸 계단 추이 (stepline). 화면낭독기는 아래 숨은 목록을 읽는다 */
function NumberTrend({ stats }: { stats: BoxStats["numbers"][number] }) {
  const { t } = useTranslation("ictStatus");
  const { theme } = useTheme();
  const dark = theme === "dark";
  const zoom = useAppZoom(); // 글자 크기 "크게"면 배율 밖에서 그리고 높이·글자를 키운다 (REQ-008, StatusCharts.zoomChartFonts)
  // 색은 테마 토큰에서 읽고(업무 현황 그래프 StatusCharts와 같게, theme.mode를 쓰면 다크에서 회색 판이 깔림),
  // 가로축 날짜는 숫자로만 써서 언어와 관계없이 같게 보인다(apexcharts 기본은 "01 Oct")
  const token = (name: string) => getComputedStyle(document.documentElement).getPropertyValue(name).trim();
  const options: ApexOptions = {
    chart: { toolbar: { show: false }, animations: { enabled: false }, accessibility: { enabled: false }, fontFamily: "inherit", foreColor: token(dark ? "--color-gray-400" : "--color-gray-500") },
    grid: { borderColor: token(dark ? "--color-gray-700" : "--color-gray-200") },
    stroke: { curve: "stepline", width: 2 },
    colors: [token("--color-brand-500")],
    dataLabels: { enabled: false },
    xaxis: { type: "datetime", labels: { datetimeUTC: false, datetimeFormatter: { year: "yyyy", month: "yyyy-MM", day: "MM-dd", hour: "HH:mm", minute: "HH:mm" } } },
    tooltip: { theme: dark ? "dark" : "light", x: { format: "yyyy-MM-dd HH:mm" } },
  };
  return (
    <section className="space-y-2">
      <h5 className="text-sm font-medium text-gray-800 dark:text-white/90">
        {`${stats.title} ${t("record.trend")}`}
        {stats.deleted && <span className="ms-1 text-gray-500 dark:text-gray-400">{t("record.deletedField")}</span>}
      </h5>
      <div aria-hidden="true" className="zoom-reset">
        {/* 테마가 바뀌면 다시 만든다(그대로 두면 툴팁이 옛 테마 바탕에 새 글자색으로 남음) */}
        <Chart key={theme} type="line" height={220 * zoom} options={zoomChartFonts(options, zoom)} series={[{ name: stats.title, data: stats.points.map((p) => ({ x: new Date(p.at).getTime(), y: p.value })) }]} />
      </div>
      <ul className="sr-only">
        {stats.points.map((p, i) => (
          <li key={i}>{`${timeText(p.at)}: ${p.value ?? t("record.noValue")}`}</li>
        ))}
      </ul>
    </section>
  );
}
