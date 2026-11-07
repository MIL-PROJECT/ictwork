import { useTranslation } from "react-i18next";
import type { Resource } from "./boardState";

export default function BoardResourceNote({ resource }: { resource: Resource<unknown> }) {
  const { t, i18n } = useTranslation("board");
  const time = resource.checkedAt ? new Date(resource.checkedAt).toLocaleString(i18n.language, { hour12: false }) : "";
  return <p className={`board-resource-note state-${resource.state}`} data-state={resource.state}>
    {t(`health.${resource.state}`, { time })}
  </p>;
}
