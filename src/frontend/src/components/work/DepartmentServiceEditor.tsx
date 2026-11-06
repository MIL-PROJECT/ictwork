import type { DepartmentService } from "@/api/departmentServices";
import { INPUT_CLASS, LABEL_CLASS, useWorkError } from "@/components/work/format";
import { useEffect, useState } from "react";
import { useTranslation } from "react-i18next";

const FIELDS = ["systems", "responsibilities", "symptoms", "exclusions"] as const;

/**
 * 부서 업무 안내 문구 편집 (부서원 화면·관리 ▸ 부서 업무 안내 공용).
 * 불러오기·저장 함수만 다르고, 같은 판(version)을 보고 고친다: 먼저 저장한 사람이 있으면 서버가 409로 알린다.
 * 업무 유형별 일정 방식은 여기서 고치지 않는다(관리 ▸ 부서 업무 안내의 업무 유형 표).
 */
export default function DepartmentServiceEditor({
  load,
  save,
  heading,
  onLoaded,
}: {
  load: () => Promise<DepartmentService>;
  save: (value: DepartmentService) => Promise<DepartmentService>;
  heading?: (value: DepartmentService) => string;
  onLoaded?: (value: DepartmentService) => void;
}) {
  const { t } = useTranslation("work");
  const describeError = useWorkError();
  const [value, setValue] = useState<DepartmentService | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [saved, setSaved] = useState(false);
  const [busy, setBusy] = useState(false);
  const [retry, setRetry] = useState(0);

  useEffect(() => {
    let active = true;
    load().then(
      (data) => {
        if (!active) return;
        setSaved(false);
        setValue(data);
        setError(null);
        onLoaded?.(data);
      },
      (err) => {
        if (active) setError(describeError(err));
      },
    );
    return () => {
      active = false;
    };
    // load가 바뀌면(관리 화면에서 부서를 바꾸면) 새로 읽는다. onLoaded는 알림일 뿐이라 다시 읽을 까닭이 아니다
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [load, retry, describeError]);

  const change = (patch: Partial<DepartmentService>) => {
    setValue((previous) => (previous ? { ...previous, ...patch } : previous));
    setSaved(false);
  };

  return (
    <div className="space-y-4">
      {error && (
        <p role="alert" className="text-sm text-error-600 dark:text-error-400">
          {error}{" "}
          <button type="button" className="underline" onClick={() => setRetry((n) => n + 1)}>
            {t("service.reload")}
          </button>
        </p>
      )}
      {value ? (
        <form
          className="space-y-5"
          onSubmit={async (event) => {
            event.preventDefault();
            if (busy) return;
            setBusy(true);
            setError(null);
            try {
              const next = await save(value);
              setValue(next);
              onLoaded?.(next);
              setSaved(true);
            } catch (err) {
              setError(describeError(err));
            } finally {
              setBusy(false);
            }
          }}
        >
          {heading && <h2 className="font-semibold text-gray-900 dark:text-white">{heading(value)}</h2>}
          <p className="text-sm text-gray-500 dark:text-gray-400">{t("service.visibility")}</p>
          {FIELDS.map((key) => (
            <div key={key}>
              <label className={LABEL_CLASS} htmlFor={`service-${key}`}>
                {t(`service.${key}`)}
              </label>
              <textarea
                className={INPUT_CLASS}
                id={`service-${key}`}
                rows={3}
                maxLength={2000}
                value={value[key]}
                placeholder={t(`service.${key}Example`)}
                onChange={(event) => change({ [key]: event.target.value })}
              />
            </div>
          ))}
          <div className="flex items-center gap-4">
            <button disabled={busy} className="rounded-lg bg-brand-500 px-5 py-3 font-semibold text-white disabled:opacity-50">
              {t(busy ? "service.saving" : "service.save")}
            </button>
            {saved && (
              <p role="status" className="text-sm text-success-600">
                {t("service.saved")}
              </p>
            )}
          </div>
        </form>
      ) : (
        !error && <p className="text-sm text-gray-500 dark:text-gray-400">{t("loading")}</p>
      )}
    </div>
  );
}
