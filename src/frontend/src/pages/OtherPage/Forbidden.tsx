import { Panel } from "@/components/common/Panel";
import PageMeta from "@/components/common/PageMeta";
import { LockIcon } from "@/icons";
import { useTranslation } from "react-i18next";

// 권한이 없는 화면 주소로 들어왔을 때 (REQ-002). OMNIS 빈 상태 모양: 원 안 아이콘 + 굵은 제목 + 설명
export default function Forbidden() {
  const { t } = useTranslation();
  return (
    <div>
      <PageMeta titleKey="forbidden.title" />
      <Panel>
        <div className="flex flex-col items-center justify-center gap-1.5 px-5 py-12 text-center xl:py-16">
          <span aria-hidden className="mb-1.5 grid size-14 place-items-center rounded-full bg-gray-100 text-gray-400 [&_svg]:size-7 dark:bg-white/5">
            <LockIcon />
          </span>
          <h1 className="text-[19px] font-extrabold tracking-[-0.4px] text-gray-800 dark:text-white/90">{t("forbidden.title")}</h1>
          <p className="max-w-[360px] text-[13.5px] leading-normal text-gray-500 dark:text-gray-400">{t("forbidden.message")}</p>
        </div>
      </Panel>
    </div>
  );
}
