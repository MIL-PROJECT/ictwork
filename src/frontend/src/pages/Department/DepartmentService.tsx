import {
  fetchOwnDepartmentService,
  saveOwnDepartmentService,
  type DepartmentService as Service,
} from "@/api/departmentServices";
import PageBreadcrumb from "@/components/common/PageBreadCrumb";
import PageMeta from "@/components/common/PageMeta";
import {
  INPUT_CLASS,
  LABEL_CLASS,
  useWorkError,
} from "@/components/work/format";
import { useOrgData } from "@/hooks/useOrgData";
import { useEffect, useState } from "react";
import { useTranslation } from "react-i18next";

export default function DepartmentService() {
  const { t } = useTranslation("work");
  const { paths } = useOrgData({});
  const describeError = useWorkError();
  const [value, setValue] = useState<Service | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [saved, setSaved] = useState(false);
  const [busy, setBusy] = useState(false);
  const [retry, setRetry] = useState(0);
  useEffect(() => {
    let active = true;
    fetchOwnDepartmentService().then(
      (data) => {
        if (active) {
          setValue(data);
          setError(null);
        }
      },
      (err) => {
        if (active) setError(describeError(err));
      },
    );
    return () => {
      active = false;
    };
  }, [retry, describeError]);
  const change = (patch: Partial<Service>) => {
    setValue((previous) => (previous ? { ...previous, ...patch } : previous));
    setSaved(false);
  };
  return (
    <div className="space-y-5">
      <PageMeta titleKey="work:service.title" />
      <PageBreadcrumb
        pageTitle={t("service.title")}
        description={t("service.help")}
      />
      {error && (
        <p role="alert" className="text-sm text-error-600">
          {error}{" "}
          <button
            type="button"
            className="underline"
            onClick={() => setRetry((n) => n + 1)}
          >
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
              setValue(await saveOwnDepartmentService(value));
              setSaved(true);
            } catch (err) {
              setError(describeError(err));
            } finally {
              setBusy(false);
            }
          }}
        >
          <section className="space-y-5 rounded-2xl border border-gray-200 bg-white p-6 dark:border-gray-800 dark:bg-gray-900">
            <h2 className="font-semibold text-gray-900 dark:text-white">
              {paths.get(value.departmentId) ?? t("service.own")}
            </h2>
            <p className="text-sm text-gray-500">{t("service.visibility")}</p>
            {(
              ["systems", "responsibilities", "symptoms", "exclusions"] as const
            ).map((key) => (
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
          </section>
          <section className="space-y-4 rounded-2xl border border-gray-200 bg-white p-6 dark:border-gray-800 dark:bg-gray-900">
            <h2 className="font-semibold text-gray-900 dark:text-white">
              {t("service.scheduleTitle")}
            </h2>
            <p className="text-sm text-gray-500">{t("service.scheduleHelp")}</p>
            {value.types.length === 0 && (
              <p className="text-sm text-gray-500">{t("service.noTypes")}</p>
            )}
            {value.types.map((type) => (
              <div
                key={type.typeId}
                className="grid gap-2 sm:grid-cols-2 sm:items-center"
              >
                <label
                  htmlFor={`service-type-${type.typeId}`}
                  className={LABEL_CLASS}
                >
                  {type.name}
                </label>
                <select
                  id={`service-type-${type.typeId}`}
                  className={INPUT_CLASS}
                  value={type.scheduleMode}
                  onChange={(event) =>
                    change({
                      types: value.types.map((row) =>
                        row.typeId === type.typeId
                          ? {
                              ...row,
                              scheduleMode: event.target
                                .value as typeof row.scheduleMode,
                            }
                          : row,
                      ),
                    })
                  }
                >
                  <option value="OPTIONAL">{t("service.optional")}</option>
                  <option value="EXACT_REQUIRED">{t("service.exact")}</option>
                </select>
              </div>
            ))}
          </section>
          <div className="flex items-center gap-4">
            <button
              disabled={busy}
              className="rounded-lg bg-brand-500 px-5 py-3 font-semibold text-white disabled:opacity-50"
            >
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
        !error && <p>{t("loading")}</p>
      )}
    </div>
  );
}
