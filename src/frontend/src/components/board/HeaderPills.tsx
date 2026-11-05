import { useEffect, useState } from "react";
import { useTranslation } from "react-i18next";

// 상단바 오른쪽 알약 (OMNIS .pill-offline·.clock): 1초마다 바뀌는 시계 (가상 데이터 알약은 사용자 요청으로 뺌). 넓은 화면에서만.
const pill =
  "hidden h-9 items-center gap-[7px] rounded-full bg-white px-3.5 text-[12.5px] font-medium whitespace-nowrap text-gray-600 shadow-theme-md xl:inline-flex dark:bg-gray-800 dark:text-gray-300";

export function HeaderPills() {
  const { i18n } = useTranslation("board");
  const [now, setNow] = useState(() => new Date());
  useEffect(() => {
    const id = window.setInterval(() => setNow(new Date()), 1000);
    return () => window.clearInterval(id);
  }, []);
  const date = now.toLocaleDateString(i18n.language, {
    month: "long",
    day: "numeric",
    weekday: "short",
  });
  const time = now.toLocaleTimeString(i18n.language, {
    hour: "2-digit",
    minute: "2-digit",
    second: "2-digit",
    hour12: false,
  });
  return (
    <>
      <span className={`${pill} tabular-nums`} aria-hidden>
        <svg
          viewBox="0 0 24 24"
          className="size-4 text-brand-500"
          fill="none"
          stroke="currentColor"
          strokeWidth={2}
          strokeLinecap="round"
          aria-hidden
        >
          <circle cx="12" cy="12" r="9" />
          <path d="M12 7v5l3 2" />
        </svg>
        {date} {time}
      </span>
    </>
  );
}
