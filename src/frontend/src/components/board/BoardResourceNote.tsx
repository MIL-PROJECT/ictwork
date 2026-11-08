import { useTranslation } from "react-i18next";
import type { Resource } from "./boardState";

export default function BoardResourceNote({ resource, compact = false }: { resource: Resource<unknown>; compact?: boolean }) {
  const { t, i18n } = useTranslation("board");
  const time = resource.checkedAt ? new Date(resource.checkedAt).toLocaleString(i18n.language, { hour12: false }) : "";
  const full = t(`health.${resource.state}`, { time });
  const shortTime = resource.checkedAt ? new Date(resource.checkedAt).toLocaleTimeString(i18n.language, { hour12: false, hour: "2-digit", minute: "2-digit" }) : "";
  return <p className={`board-resource-note state-${resource.state}${compact ? " is-compact" : ""}`} data-state={resource.state} title={full} aria-label={full}>
    {compact ? t(`healthCompact.${resource.state}`, { time: shortTime }) : full}
  </p>;
}
