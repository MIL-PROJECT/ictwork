import {
  activateDiagram,
  createDiagram,
  type DiagramSummary,
  fetchDeleted,
  fetchDiagrams,
  fetchOrphans,
  type NamedDiagram,
  recoverDiagram,
} from "@/api/diagram";
import DataTable, { RowButton } from "@/components/admin/DataTable";
import PageBreadcrumb from "@/components/common/PageBreadCrumb";
import PageMeta from "@/components/common/PageMeta";
import { Panel } from "@/components/common/Panel";
import { useDiagramError } from "@/components/diagram/errors";
import Button from "@/components/ui/button/Button";
import { LayoutIcon, TimeIcon, TrashBinIcon } from "@/icons";
import { clip } from "@/utils";
import { useCallback, useEffect, useState } from "react";
import { useTranslation } from "react-i18next";
import { Link, useNavigate } from "react-router";

// 카드(Panel) 안 공용 표(DataTable)의 상자 모양을 지워 표가 카드에 꽉 차게 한다 (전역 카드 그림자·모서리도 덮음)
const FLUSH_TABLE = "[&>div]:rounded-none! [&>div]:border-0 [&>div]:bg-transparent [&>div]:shadow-none!";

// 부서관리 ▸ 구성도 (REQ-504·509·510): 볼 수 있는 구성도 목록, 새로 만들기.
// 부서 관리자에게는 복구가 필요한 구성도(S-34)와 지운 구성도(S-45) 칸을 더 보인다
export default function DiagramList() {
  const { t } = useTranslation();
  const { t: td } = useTranslation("diagram");
  const { t: tf } = useTranslation("frameDept");
  const navigate = useNavigate();
  const [diagrams, setDiagrams] = useState<DiagramSummary[]>([]);
  const [orphans, setOrphans] = useState<NamedDiagram[]>([]);
  const [deleted, setDeleted] = useState<NamedDiagram[]>([]);
  const [name, setName] = useState("");
  const [error, setError] = useState("");
  const [loaded, setLoaded] = useState(false);

  const load = useCallback(() => {
    Promise.all([fetchDiagrams(), fetchOrphans(), fetchDeleted()])
      .then(([d, o, x]) => {
        setDiagrams(d);
        setOrphans(o);
        setDeleted(x);
        setLoaded(true);
      })
      .catch(() => setError(td("errors.load")));
  }, [td]);

  useEffect(load, [load]);

  const errorText = useDiagramError();
  const fail = (e: unknown) => setError(errorText(e));

  const create = () => {
    if (!name.trim()) return;
    createDiagram(name.trim())
      .then(({ id }) => navigate(`/department/diagrams/${id}`))
      .catch(fail);
  };

  return (
    <div>
      <PageMeta titleKey="sidebar.items.diagrams" />
      {/* 화면 단위 주 동작(새 구성도 만들기: 이름 칸 + 만들기)은 OMNIS처럼 제목 오른쪽에 둔다 */}
      <PageBreadcrumb
        pageTitle={t("sidebar.items.diagrams")}
        description={tf("diagrams.description")}
        actions={
          <>
            <label className="flex min-w-60 items-center gap-2 text-sm text-gray-700 dark:text-gray-300">
              <span className="shrink-0">{td("list.newName")}</span>
              <input
                value={name}
                onChange={(e) => setName(clip(e, 100))}
                onKeyDown={(e) => e.key === "Enter" && create()}
                className="h-10 min-w-0 flex-1 rounded-lg border border-gray-300 bg-transparent px-3 text-sm text-gray-800 focus:border-brand-300 focus:ring-3 focus:ring-brand-500/10 focus:outline-hidden dark:border-gray-700 dark:bg-gray-900 dark:text-white/90"
              />
            </label>
            <Button size="sm" onClick={create} disabled={!name.trim()}>
              {td("list.create")}
            </Button>
          </>
        }
      />
      {error && (
        <p role="alert" className="mb-3 text-sm text-error-600 dark:text-error-400">
          {error}
        </p>
      )}
      <Panel icon={<LayoutIcon />} title={tf("diagrams.listTitle")} subtitle={loaded ? tf("diagrams.count", { n: diagrams.length }) : undefined} flush>
        <div className={FLUSH_TABLE}>
          <DataTable
            caption={t("sidebar.items.diagrams")}
            columns={[td("list.name"), td("list.owner"), td("list.permission"), td("list.editing")]}
            emptyText={loaded ? td("list.empty") : error ? "" : td("loading")}
            rows={diagrams.map((d) => ({
              key: d.id,
              cells: [
                <Link key="n" to={`/department/diagrams/${d.id}`} className="font-medium text-brand-600 hover:underline dark:text-brand-400">
                  {d.name}
                </Link>,
                d.ownerDepartmentName,
                td(`permission.${d.permission}`),
                d.lockedByName ?? "",
              ],
            }))}
          />
        </div>
      </Panel>
      {orphans.length > 0 && (
        <Panel icon={<TimeIcon />} title={td("list.orphans")} subtitle={td("list.orphansHelp")} subtitleGuide className="mt-6" flush>
          <div className={FLUSH_TABLE}>
            <DataTable
              caption={td("list.orphans")}
              columns={[td("list.name"), td("list.owner"), ""]}
              emptyText=""
              rows={orphans.map((d) => ({
                key: d.id,
                cells: [
                  d.name,
                  d.ownerDepartmentName,
                  <RowButton key="r" onClick={() => recoverDiagram(d.id).then(() => navigate(`/department/diagrams/${d.id}`)).catch(fail)}>
                    {td("list.recover")}
                  </RowButton>,
                ],
              }))}
            />
          </div>
        </Panel>
      )}
      {deleted.length > 0 && (
        <Panel icon={<TrashBinIcon />} title={td("list.deleted")} subtitle={tf("diagrams.count", { n: deleted.length })} className="mt-6" flush>
          <div className={FLUSH_TABLE}>
            <DataTable
              caption={td("list.deleted")}
              columns={[td("list.name"), td("list.owner"), ""]}
              emptyText=""
              rows={deleted.map((d) => ({
                key: d.id,
                inactive: true,
                cells: [
                  d.name,
                  d.ownerDepartmentName,
                  <RowButton key="a" onClick={() => activateDiagram(d.id).then(load).catch(fail)}>
                    {td("list.restore")}
                  </RowButton>,
                ],
              }))}
            />
          </div>
        </Panel>
      )}
    </div>
  );
}
