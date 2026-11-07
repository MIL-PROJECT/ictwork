export type PreviewKind = "pdf" | "image" | "text" | "hwp" | "docx" | "xlsx" | "pptx";

/** 이름으로 미리보기 방식을 정한다. 서버도 본문 서명을 다시 본다(PDF·그림·txt) */
export function previewKind(name: string): PreviewKind | null {
  const extension = name.slice(name.lastIndexOf(".") + 1).toLowerCase();
  if (extension === "pdf") return "pdf";
  if (extension === "png" || extension === "jpg" || extension === "jpeg") return "image";
  if (extension === "txt") return "text";
  if (extension === "hwp" || extension === "hwpx") return "hwp";
  if (extension === "docx") return "docx";
  if (extension === "xlsx") return "xlsx";
  if (extension === "pptx") return "pptx";
  return null;
}
