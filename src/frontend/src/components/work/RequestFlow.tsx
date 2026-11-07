import { useState, type ReactNode } from "react";
import { useTranslation } from "react-i18next";
import { cn } from "@/utils";

export function RequestDisclosure({
  title,
  children,
  initiallyOpen = false,
  open: controlledOpen,
  onOpenChange,
}: {
  title: string;
  children: ReactNode;
  initiallyOpen?: boolean;
  open?: boolean;
  onOpenChange?: (open: boolean) => void;
}) {
  const [open, setOpen] = useState(initiallyOpen);
  return (
    <details
      open={controlledOpen ?? open}
      onToggle={(e) => { setOpen(e.currentTarget.open); onOpenChange?.(e.currentTarget.open); }}
      className="rounded-xl border border-gray-200 bg-white dark:border-gray-700 dark:bg-gray-900"
    >
      <summary className="cursor-pointer px-4 py-3 text-sm font-semibold text-gray-700 focus-visible:outline-brand-500 dark:text-gray-200">
        {title}
      </summary>
      <div className="space-y-4 border-t border-gray-100 p-4 dark:border-gray-800">
        {children}
      </div>
    </details>
  );
}

export const FLOW_BUTTON =
  "inline-flex min-h-10 items-center justify-center gap-2 rounded-lg px-4 py-2 text-sm font-semibold transition focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-brand-500 disabled:cursor-not-allowed disabled:opacity-50";

/** Navigation only: all request values and submission rules belong to RequestForm. */
export default function RequestFlow({
  step,
  panels,
  complete,
  onStep,
  onNext,
  applicant,
  summary,
  error,
  busy,
  uploading,
  submitLabel,
  intake = false,
}: {
  step: number;
  panels: ReactNode[];
  complete: boolean[];
  onStep: (step: number) => void;
  onNext: () => void;
  applicant: ReactNode;
  summary: ReactNode;
  error: string | null;
  busy: boolean;
  uploading: boolean;
  submitLabel: string;
  intake?: boolean;
}) {
  const { t } = useTranslation("work");
  const copy = intake ? "guidance.flow" : "flow";
  return (
    <div className="space-y-5">
      <nav
        aria-label={t("flow.navigation")}
        className="rounded-2xl border border-gray-200 bg-white p-2 dark:border-gray-800 dark:bg-gray-900"
      >
        <ol className={cn("grid gap-1", panels.length === 3 ? "grid-cols-1 sm:grid-cols-3" : "grid-cols-2 sm:grid-cols-4")}>
          {panels.map((_, index) => (
            <li key={index}>
              <button
                type="button"
                disabled={busy}
                aria-current={step === index ? "step" : undefined}
                onClick={() => onStep(index)}
                className={cn(
                  "flex w-full items-center gap-3 rounded-xl px-3 py-3 text-start transition focus-visible:outline-brand-500 sm:px-4",
                  step === index
                    ? "bg-brand-50 dark:bg-brand-500/15"
                    : "hover:bg-gray-50 dark:hover:bg-white/5",
                )}
              >
                <span
                  aria-hidden="true"
                  className={cn(
                    "flex size-8 shrink-0 items-center justify-center rounded-full text-sm font-bold",
                    step === index
                      ? "bg-brand-500 text-white"
                      : complete[index]
                        ? "bg-success-50 text-success-700 dark:bg-success-500/15 dark:text-success-400"
                        : "bg-gray-100 text-gray-500 dark:bg-gray-800",
                  )}
                >
                  {complete[index] && step !== index ? "✓" : `0${index + 1}`}
                </span>
                <span
                  className={cn(
                    "text-sm font-semibold",
                    step === index
                      ? "text-brand-700 dark:text-brand-300"
                      : "text-gray-600 dark:text-gray-400",
                  )}
                >
                  {t(`${copy}.steps.${index}`)}
                </span>
              </button>
            </li>
          ))}
        </ol>
      </nav>
      <div className="grid items-start gap-5 xl:grid-cols-[minmax(0,1fr)_260px]">
        <div className="min-w-0 space-y-5">
          <div className="px-1">
            <p className="mb-1 text-xs font-semibold tracking-wide text-brand-600 dark:text-brand-400">
              {t("flow.stepOf", { step: step + 1, total: panels.length })}
            </p>
            <h2
              id="request-flow-heading"
              tabIndex={-1}
              className="text-xl font-bold tracking-tight text-gray-900 outline-none dark:text-white"
            >
              {t(`${copy}.headings.${step}`)}
            </h2>
            <p className="mt-1.5 text-sm leading-relaxed text-gray-500 dark:text-gray-400">
              {t(`${copy}.descriptions.${step}`)}
            </p>
          </div>
          {panels.map((panel, index) => (
            <div key={index} hidden={step !== index} className="space-y-5">
              {panel}
            </div>
          ))}
        </div>
        <aside
          aria-label={t("flow.summary")}
          className="space-y-5 rounded-2xl border border-gray-200 bg-white p-5 xl:sticky xl:top-24 dark:border-gray-800 dark:bg-gray-900"
        >
          <div>
            <h3 className="text-xs font-semibold text-gray-500 dark:text-gray-400">
              {t("applicant.title")}
            </h3>
            <div className="mt-2 text-sm leading-relaxed text-gray-800 dark:text-gray-200">
              {applicant}
            </div>
          </div>
          <div className="border-t border-gray-100 pt-4 dark:border-gray-800">
            <h3 className="mb-3 text-xs font-semibold text-gray-500 dark:text-gray-400">
              {t("flow.destination")}
            </h3>
            {summary}
          </div>
          <p className="rounded-lg bg-gray-50 p-3 text-xs leading-relaxed text-gray-500 dark:bg-white/5 dark:text-gray-400">
            {t("flow.help")}
          </p>
        </aside>
      </div>
      <div className="sticky bottom-3 z-20 flex flex-wrap items-center gap-3 rounded-2xl border border-gray-200 bg-white/95 px-5 py-3 shadow-lg backdrop-blur-sm dark:border-gray-700 dark:bg-gray-900/95">
        <div className="min-w-0 flex-1">
          {error ? (
            <p
              id="request-error"
              role="alert"
              className="text-sm text-error-600 dark:text-error-400"
            >
              {error}
            </p>
          ) : (
            <p
              className="text-xs text-gray-500 dark:text-gray-400"
              aria-live="polite"
            >
              {uploading ? t("register.uploading") : t("flow.footer")}
            </p>
          )}
        </div>
        {step > 0 && (
          <button
            type="button"
            disabled={busy}
            onClick={() => onStep(step - 1)}
            className={cn(
              FLOW_BUTTON,
              "text-gray-600 ring-1 ring-gray-200 dark:text-gray-300 dark:ring-gray-700",
            )}
          >
            {t("flow.previous")}
          </button>
        )}
        {step < panels.length - 1 ? (
          <button
            type="button"
            disabled={busy || uploading}
            onClick={onNext}
            className={cn(
              FLOW_BUTTON,
              "bg-brand-500 text-white hover:bg-brand-600",
            )}
          >
            {t("flow.next")} <span aria-hidden="true">→</span>
          </button>
        ) : (
          <button
            type="submit"
            disabled={busy || uploading}
            className={cn(
              FLOW_BUTTON,
              "bg-brand-500 text-white hover:bg-brand-600",
            )}
          >
            {busy ? t("flow.sending") : submitLabel}
          </button>
        )}
      </div>
    </div>
  );
}
