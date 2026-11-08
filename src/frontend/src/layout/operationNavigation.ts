export type OperationView = "systems" | "signals";

// `tab` belongs to the existing ICT detail dialog (content/history/files...).
// Use a separate key for the workspace so saved detail links remain compatible.
export function operationView(search: string): OperationView {
  return new URLSearchParams(search).get("view") === "signals" ? "signals" : "systems";
}

export function operationViewPath(search: string, view: OperationView): string {
  const params = new URLSearchParams(search);
  params.set("view", view);
  return "/operations?" + params.toString();
}

export const ictStatusPath = (id?: number) => operationViewPath(id === undefined ? "" : `box=${id}`, "signals");

export function canonicalOperationRoute(route: string): string {
  const legacy = /^\/ict-status(?:\?([^#]*))?(#.*)?$/.exec(route);
  return legacy ? operationViewPath(legacy[1] ?? "", "signals") + (legacy[2] ?? "") : route;
}
