import Pagination, { PageSizeBar, PagingFooter } from "@/components/common/Pagination";
import NoBreakDates from "@/components/common/NoBreakDates";
import { Table, TableBody, TableCell, TableHeader, TableRow } from "@/components/ui/table";
import type { Paging } from "@/hooks/usePaging";
import { cn } from "@/utils";
import type { ReactNode } from "react";

// 관리·명단 화면의 표. 행마다 key와 칸 내용을 받는다. 사용 중지 행은 흐리게.
// error: 불러오기 실패 문구. 행이 남아 있어도 표 위에 보여, 저장 뒤 다시 읽기가 실패한 것을 알린다
// 감싼 상자의 relative: 칸 안 화면낭독기용 숨김 글자(sr-only, 절대 위치)를 이 상자에 가둬, 좁은 화면에서 쪽 전체가 옆으로 밀리지 않게 한다
// paging: 쪽 나누기(REQ-007, G-117)를 켠다. rows는 이 쪽 줄만 넘기고(paging.slice), 표 위에 건수·"N개씩 보기", 아래에 쪽 번호를 둔다. totalText는 건수 글 대신
// flush: OMNIS 카드(Panel flush) 안에 넣는 표. 자기 테두리·카드 없이 꽉 차고, 쪽 나누기는 표 아래 띠(PagingFooter) 하나로 보인다
export default function DataTable({
  caption,
  columns,
  rows,
  emptyText,
  error,
  paging,
  totalText,
  flush = false,
}: {
  caption: string;
  columns: string[];
  rows: { key: string | number; cells: ReactNode[]; inactive?: boolean }[];
  emptyText: string;
  error?: string;
  paging?: Paging;
  totalText?: string;
  flush?: boolean;
}) {
  const box = <TableBox caption={caption} columns={columns} rows={rows} emptyText={emptyText} error={error} flush={flush} />;
  if (flush) {
    return (
      <div>
        {box}
        {paging && paging.total > 0 && <PagingFooter paging={paging} totalText={totalText} />}
      </div>
    );
  }
  if (!paging) return box;
  return (
    <div>
      {paging.total > 0 && <PageSizeBar paging={paging} totalText={totalText} />}
      {box}
      <Pagination paging={paging} />
    </div>
  );
}

function TableBox({
  caption,
  columns,
  rows,
  emptyText,
  error,
  flush,
}: {
  caption: string;
  columns: string[];
  rows: { key: string | number; cells: ReactNode[]; inactive?: boolean }[];
  emptyText: string;
  error?: string;
  flush: boolean;
}) {
  return (
    <div
      className={cn(
        "relative overflow-x-auto",
        // OMNIS 카드 안의 표: 테두리 없이 카드 폭을 꽉 채운다(머리글 띠·행 hover는 전역 표 모양)
        flush ? "" : "rounded-2xl border border-gray-200 bg-white dark:border-gray-800 dark:bg-white/3",
      )}
    >
      {error && (
        <p
          role="alert"
          className={cn(
            "border-b px-5 py-3 text-sm text-error-600 dark:text-error-400",
            flush ? "border-error-100 bg-error-50 ps-[22px] dark:border-error-500/20 dark:bg-error-500/10" : "border-gray-200 dark:border-gray-800",
          )}
        >
          {error}
        </p>
      )}
      {/* 한글은 낱말 단위로만 줄을 바꾼다(break-keep). 좁은 화면에서 칸이 모자라면 글자마다 꺾이지 않고 표가 옆으로 밀린다.
          칸 내용은 너비를 28rem까지로 두고 그보다 긴 낱말(띄어쓰기 없는 긴 제목 등)만 칸 안에서 꺾어, 한 칸이 표를 넓혀 다른 열을 밀지 않게 한다 */}
      <Table className="text-sm break-keep">
        <caption className="sr-only">{caption}</caption>
        <TableHeader className="border-b border-gray-200 dark:border-gray-800">
          <TableRow>
            {columns.map((column) => (
              <TableCell key={column} isHeader className="px-5 py-3 text-start font-medium text-gray-500 dark:text-gray-400">
                {column}
              </TableCell>
            ))}
          </TableRow>
        </TableHeader>
        <TableBody className="divide-y divide-gray-100 dark:divide-gray-800">
          {rows.length === 0 && (
            <TableRow>
              <TableCell colSpan={columns.length} className={cn("px-5 text-center text-gray-500 dark:text-gray-400", flush ? "py-10 text-[13px]" : "py-6")}>
                {error ?? emptyText}
              </TableCell>
            </TableRow>
          )}
          {rows.map((row) => (
            <TableRow key={row.key} className={cn(row.inactive && "opacity-60")}>
              {row.cells.map((cell, index) => (
                <TableCell key={index} className={cn("px-5 py-3 text-gray-700 dark:text-gray-300", flush && "h-[42px] text-[13.5px]")}>
                  {/* 칸 폭을 다 쓰도록 최대 폭 제한(448px)은 두지 않는다. 날짜는 하이픈에서 끊지 않는다 */}
                  <div className="wrap-break-word">{typeof cell === "string" ? <NoBreakDates text={cell} /> : cell}</div>
                </TableCell>
              ))}
            </TableRow>
          ))}
        </TableBody>
      </Table>
    </div>
  );
}

// 표 안의 작은 버튼 (폼 제출이 되지 않게 type="button")
export function RowButton({ onClick, disabled, children }: { onClick: () => void; disabled?: boolean; children: ReactNode }) {
  return (
    <button
      type="button"
      onClick={onClick}
      disabled={disabled}
      className="me-1.5 rounded-lg px-2 py-1 text-[13px] font-semibold text-brand-500 hover:bg-brand-50 disabled:opacity-50 dark:text-brand-400 dark:hover:bg-white/5"
    >
      {children}
    </button>
  );
}
