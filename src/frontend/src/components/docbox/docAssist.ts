import { type DocFolderView, type DocNodeView, fetchDocFolder, fetchFolderTree } from "@/api/docbox";

// AI 업무지원 문서함 (REQ-C07 문서함, slices/21 8장 docbox.*·13.8): 고칠 노드 찾기

/**
 * 고칠 노드(폴더·파일)와 그 노드가 든 폴더 내용을 찾는다: 폴더는 트리의 위 폴더에서, 파일은 맨 위와 폴더를 차례로 읽어 찾는다.
 * 없으면(그사이 지워짐 등) null
 */
export async function locateDocNode(departmentId: number, nodeId: number): Promise<{ view: DocFolderView; node: DocNodeView } | null> {
  const tree = await fetchFolderTree(departmentId);
  const folder = tree.find((f) => f.id === nodeId);
  const parents = folder ? [folder.parentId] : [null, ...tree.map((f) => f.id)];
  for (const parent of parents) {
    const view = await fetchDocFolder(departmentId, parent);
    const node = view.items.find((i) => i.id === nodeId);
    if (node) return { view, node };
  }
  return null;
}
