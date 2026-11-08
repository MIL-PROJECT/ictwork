import { analyzeWork, type AnalysisFeature, type AnalysisResult } from "@/api/aiAnalysis";
import { AiIcon, LegacyBuildIcon, SearchIcon, PieChartIcon } from "@/icons";
import { useEffect, useRef, useState, type FormEvent } from "react";
import { useTranslation } from "react-i18next";
import { Link } from "react-router";

const features = [
  { key: "SUMMARY", icon: PieChartIcon }, { key: "TRIAGE", icon: AiIcon },
  { key: "PRIORITY", icon: LegacyBuildIcon }, { key: "SIMILAR", icon: SearchIcon },
] as const;

/** Restored original OMNIS analysis actions, with current API permission scopes. */
export default function AiWorkbench({ disabled }: { disabled: boolean }) {
  const { t } = useTranslation("ai");
  const [feature, setFeature] = useState<AnalysisFeature>("SUMMARY");
  const [title, setTitle] = useState(""), [description, setDescription] = useState(""), [serviceImpact, setImpact] = useState("");
  const [result, setResult] = useState<AnalysisResult | null>(null), [busy, setBusy] = useState(false), [error, setError] = useState("");
  const pending = useRef<AbortController | null>(null);
  const epoch = useRef(0);
  useEffect(() => () => { epoch.current++; pending.current?.abort(); }, []);
  function invalidate() { epoch.current++; pending.current?.abort(); setResult(null); setError(""); setBusy(false); }
  function choose(next: AnalysisFeature) { invalidate(); setFeature(next); }
  async function submit(event: FormEvent) {
    event.preventDefault();
    if (busy || disabled) return;
    pending.current?.abort();
    const controller = new AbortController(); pending.current = controller;
    const version = ++epoch.current;
    setBusy(true); setError(""); setResult(null);
    try {
      const response = await analyzeWork(feature, { title, description, serviceImpact }, controller.signal);
      if (version === epoch.current) setResult(response);
    } catch (e) {
      if (version === epoch.current) setError(e instanceof Error ? e.message : t("errors.default"));
    } finally { if (version === epoch.current) setBusy(false); }
  }
  const needsTitle = feature === "TRIAGE" || feature === "SIMILAR";
  return <section className="ai-workbench" aria-label={t("workspace.analysis")}>
    <div className="ai-analysis-features" role="group" aria-label={t("analysis.features")}>
      {features.map(({ key, icon: Icon }) => <button type="button" key={key} aria-pressed={key === feature} onClick={() => choose(key)}><Icon aria-hidden /><span>{t(`analysis.${key}.label`)}</span></button>)}
    </div>
    <div className="ai-analysis-layout">
      <form className="ai-analysis-input omnis-panel" onSubmit={event => void submit(event)}>
        <div className="ai-analysis-fields custom-scrollbar">
          <h2>{t(`analysis.${feature}.label`)}</h2><p>{t(`analysis.${feature}.description`)}</p>
          {needsTitle && <label>{t("analysis.title")}<input value={title} minLength={2} maxLength={200} required onChange={e => { invalidate(); setTitle(e.target.value); }} placeholder={t("analysis.titlePlaceholder")} /></label>}
          {feature === "TRIAGE" && <><label>{t("analysis.symptoms")}<textarea rows={3} maxLength={2000} value={description} onChange={e => { invalidate(); setDescription(e.target.value); }} /></label><label>{t("analysis.impact")}<input maxLength={500} value={serviceImpact} onChange={e => { invalidate(); setImpact(e.target.value); }} /></label></>}
          <p className="ai-analysis-note">{t("analysis.rulesHelp")}</p>
        </div>
        <div className="ai-analysis-submit"><button type="submit" disabled={disabled || busy || needsTitle && title.trim().length < 2}>{busy ? t("analysis.running") : t("analysis.run")}</button>{busy && <button type="button" onClick={invalidate}>{t("input.cancel")}</button>}</div>
      </form>
      <section className="ai-analysis-output omnis-panel" aria-labelledby="ai-analysis-result-title" aria-busy={busy}>
        <header><h2 id="ai-analysis-result-title">{t("analysis.result")}</h2><span>{result ? `${result.provider} · ${result.asOf.replace("T", " ").slice(0, 16)}` : t("workspace.localAnalysis")}</span></header>
        <div className="ai-analysis-results custom-scrollbar">
          {error && <p role="alert" className="text-error-600 dark:text-error-400">{error}</p>}
          {busy && <p role="status">{t("analysis.running")}</p>}
          {!result && !busy && !error && <div className="ai-analysis-empty"><AiIcon aria-hidden /><h3>{t("analysis.empty")}</h3><p>{t("analysis.emptyHelp")}</p></div>}
          {result && <><p className="ai-analysis-summary" role="status">{result.summary}</p>
            {!!result.metrics.length && <dl className="ai-analysis-metrics">{result.metrics.map(metric => <div key={metric.label}><dt>{metric.label}</dt><dd>{metric.value.toLocaleString()}</dd></div>)}</dl>}
            {result.advice && <div className="ai-triage-result"><dl><div><dt>{t("analysis.category")}</dt><dd>{result.advice.category}</dd></div><div><dt>{t("analysis.priority")}</dt><dd>{t(result.advice.priority === "URGENT" ? "analysis.urgent" : "analysis.normal")}</dd></div></dl><p>{result.advice.recommendation}</p><p><strong>{t("analysis.rationale")}</strong> {result.advice.rationale}</p><ul>{result.advice.questions.map(q => <li key={q}>{q}</li>)}</ul></div>}
            {!!result.items.length && <ol className="ai-analysis-items">{result.items.map(item => <li key={item.id}><div><span>{item.priority === "URGENT" ? t("analysis.urgent") : t("analysis.normal")}{item.overdue ? ` · ${t("analysis.overdue")}` : ""}</span><Link to={item.href}>{item.title}</Link><small>{item.reason}</small></div><span className="ai-analysis-status">{t(`analysis.status.${item.status}`, { defaultValue: item.status })}</span></li>)}</ol>}
            {!result.items.length && (feature === "SIMILAR" || feature === "PRIORITY") && <p>{t("analysis.noMatches")}</p>}
            <ul className="ai-analysis-conditions">{result.conditions.map(condition => <li key={condition}>{condition}</li>)}</ul>
            <div className="ai-analysis-links">{result.links.filter(link => link.href).map(link => <Link key={link.href} to={link.href!}>{link.label} ↗</Link>)}</div>
          </>}
        </div>
      </section>
    </div>
  </section>;
}
