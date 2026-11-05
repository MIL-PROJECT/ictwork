import Calendar from "@/components/calendar/Calendar";
import PageBreadcrumb from "@/components/common/PageBreadCrumb";
import PageMeta from "@/components/common/PageMeta";
import { Panel } from "@/components/common/Panel";
import "@fullcalendar/react/skeleton.css";
import "@fullcalendar/react/themes/classic/palette.css";
import "@fullcalendar/react/themes/classic/theme.css";
import { useTranslation } from "react-i18next";

export default function CalendarPage() {
  const { t } = useTranslation();
  const { t: tf } = useTranslation("frameGeneral");
  return (
    <div>
      <PageMeta titleKey="sidebar.items.calendar" />
      <PageBreadcrumb pageTitle={t("sidebar.items.calendar")} description={tf("calendar.description")} />
      {/* OMNIS 카드(.panel) 안에 달력을 꽉 채운다 */}
      <Panel flush>
        <Calendar />
      </Panel>
    </div>
  );
}
