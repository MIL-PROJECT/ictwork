import type { ListSource } from "@/api/equipment";
import { readDrill } from "@/api/status";
import PageBreadcrumb from "@/components/common/PageBreadCrumb";
import PageMeta from "@/components/common/PageMeta";
import EquipmentBrowser, { EquipmentAddLink } from "@/components/equipment/EquipmentBrowser";
import ConditionBanner from "@/components/status/ConditionBanner";
import { useTranslation } from "react-i18next";
import { useSearchParams } from "react-router";

// 부서관리 ▸ 장비 (REQ-502, slices/10 9.2): 소속 범위 장비(불용은 상태 걸러 보기로 골라야 보임).
// 업무 현황에서 장비 숫자를 눌러 오면(주소의 d·carryOut) 업무 현황 범위로 서버가 거르고 조건 띠를 보인다 (T-26)
export default function EquipmentList() {
  const { t } = useTranslation();
  const { t: te } = useTranslation("equipment");
  const { t: tf } = useTranslation("frameDept");
  const [params, setParams] = useSearchParams();
  const drill = readDrill(params);
  const source: ListSource = drill ? { kind: "STATUS", departmentIds: drill.departmentIds, carryOut: drill.carryOut } : { kind: "AFFILIATED" };

  return (
    <div>
      <PageMeta titleKey="sidebar.items.equipment" />
      {/* 화면 단위 주 동작(장비 등록)은 OMNIS처럼 제목 오른쪽에 둔다 */}
      <PageBreadcrumb pageTitle={t("sidebar.items.equipment")} description={tf("equipment.description")} actions={<EquipmentAddLink source={source} />} />
      <EquipmentBrowser
        // 조건이 바뀌면 걸러 보기·정렬을 처음으로 (업무 현황에서 다른 숫자를 눌러 온 경우)
        key={drill ? `${drill.departmentIds.join(",")}|${drill.carryOut.join(",")}` : "affiliated"}
        source={source}
        storageKind="list"
        showAdd={false}
        defaultStatus={drill ? "" : "ALL_BUT_DISPOSED"}
        banner={
          drill && (
            <ConditionBanner departmentIds={drill.departmentIds} parts={drill.carryOut.map((c) => te(`carryOuts.${c}`))} onClear={() => setParams({})} />
          )
        }
      />
    </div>
  );
}
