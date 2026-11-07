import type { DocFolderNode } from "../../api/docbox.ts";

// 문서함 폴더 트리 (api/docbox fetchFolderTree, DocBoxService.folderTree, slices/21 13.8).
// AI 업무지원이 옮기기 창에서 고른 폴더의 위 폴더들을 펼치고, 이름 바꾸기·옮기기·공유 대상의 위 폴더를 찾는 데 쓴다

/** 그 폴더의 위 폴더 id (맨 위 쪽부터, 자기는 빼고). 트리에 없으면 빈 목록 */
export function ancestorIds(tree: readonly DocFolderNode[], id: number): number[] {
  const byId = new Map(tree.map((f) => [f.id, f]));
  const out: number[] = [];
  let parent = byId.get(id)?.parentId ?? null;
  while (parent !== null && !out.includes(parent)) {
    out.unshift(parent);
    parent = byId.get(parent)?.parentId ?? null;
  }
  return out;
}
