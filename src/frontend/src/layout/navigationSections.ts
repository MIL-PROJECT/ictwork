export type NavigationLink = { key: string; path: string; feature?: string; tabKey?: string; inPage?: boolean };
type Screen = { key: string; owner: string; tabs: NavigationLink[]; actions?: NavigationLink[] };
export const screens: Screen[] = [
  { key: "overview", owner: "overview", tabs: [{ key: "overview", path: "/board" }] },
  { key: "operations", owner: "overview", tabs: [
    { key: "operationSystems", path: "/operations?view=systems" },
    { key: "operationSignals", path: "/operations?view=signals" },
  ] },
  { key: "workStatus", owner: "overview", tabs: [
    { key: "workStatus", path: "/status", feature: "WORK_DASHBOARD" },
  ] },
  { key: "directives", owner: "overview", tabs: [{ key: "directives", path: "/directives" }] },
  { key: "duty", owner: "overview", tabs: [{ key: "duty", path: "/duty" }] },
  { key: "newRequest", owner: "requests", tabs: [
    { key: "otherDepartmentRequest", path: "/approvals/request" },
  ], actions: [
    { key: "departmentService", path: "/department/services", inPage: true },
  ] },
  { key: "requestStatus", owner: "requests", tabs: [
    { key: "requestedTasks", path: "/tasks?tab=mine" },
    { key: "sharedTasks", path: "/tasks?tab=viewable" },
  ] },
  { key: "taskReceipt", owner: "processing", tabs: [{ key: "taskReceipt", path: "/approvals/receipts" }] },
  { key: "approvals", owner: "processing", tabs: [
    { key: "myTurn", path: "/approvals/todo?tab=myTurn" },
    { key: "approvalInProgress", path: "/approvals/in-progress" },
    { key: "handledApprovals", path: "/approvals/todo?tab=handled" },
  ], actions: [{ key: "approvalRoutes", path: "/admin/approval-routes", feature: "ADMIN_SYSTEM" }] },
  { key: "departmentTasks", owner: "processing", tabs: [{ key: "departmentTasks", path: "/tasks?tab=department" }], actions: [
    { key: "ownDepartmentRegister", path: "/approvals/new", inPage: true },
  ] },
  { key: "calendar", owner: "schedule", tabs: [{ key: "calendar", path: "/calendar" }] },
  { key: "organizationPeople", owner: "departmentInfo", tabs: [
    { key: "orgChart", path: "/department/org-chart" },
    { key: "people", path: "/department/people" },
  ], actions: [
    { key: "orgManage", path: "/admin/organization", feature: "ADMIN_ORG", tabKey: "orgChart" },
    { key: "personnelManage", path: "/admin/personnel", feature: "ADMIN_PERSONNEL", tabKey: "people" },
    { key: "personnelAttributes", path: "/admin/attributes/personnel", feature: "ADMIN_ATTRIBUTE", tabKey: "people" },
  ] },
  { key: "equipmentDiagrams", owner: "departmentInfo", tabs: [
    { key: "equipment", path: "/department/equipment" },
    { key: "diagrams", path: "/department/diagrams" },
  ], actions: [
    { key: "equipmentManage", path: "/admin/equipment", feature: "ADMIN_EQUIPMENT", tabKey: "equipment" },
    { key: "equipmentAttributes", path: "/admin/attributes/equipment", feature: "ADMIN_ATTRIBUTE", tabKey: "equipment" },
  ] },
  { key: "docs", owner: "departmentInfo", tabs: [{ key: "docs", path: "/department/docs" }] },
  { key: "workTypes", owner: "departmentInfo", tabs: [{ key: "workTypes", path: "/admin/work-types", feature: "ADMIN_WORK_TYPE" }] },
  { key: "aiChat", owner: "aiChat", tabs: [{ key: "aiChat", path: "/ai" }] },
  { key: "systemAdmin", owner: "systemAdmin", tabs: [{ key: "systemAdmin", path: "/admin/system", feature: "ADMIN_SYSTEM" }] },
];

export const topMenus = [
  { key: "overview", screen: "overview", children: ["operations", "workStatus", "directives", "duty"] },
  { key: "requests", screen: "newRequest", children: ["newRequest", "requestStatus"] },
  { key: "processing", screen: "departmentTasks", children: ["departmentTasks", "taskReceipt", "approvals"] },
  { key: "schedule", screen: "calendar", children: [] },
  { key: "departmentInfo", screen: "organizationPeople", children: ["organizationPeople", "equipmentDiagrams", "docs", "workTypes"] },
  { key: "aiChat", screen: "aiChat", children: [] },
  { key: "systemAdmin", screen: "systemAdmin", children: [] },
];

export function visibleLinks(links: readonly NavigationLink[], features: readonly string[]) {
  return links.filter(({ feature }) => !feature || features.includes(feature));
}

export function matchesMenuPath(pathname: string, path: string) {
  return pathname === path || pathname.startsWith(path + "/");
}

function normalizedSearch(pathname: string, search: string) {
  const params = new URLSearchParams(search);
  if (pathname === "/operations" && params.get("view") !== "signals") params.set("view", "systems");
  if (pathname === "/tasks" && !["mine", "viewable", "department"].includes(params.get("tab") ?? "")) params.set("tab", "department");
  if (pathname === "/approvals/todo" && params.get("tab") !== "handled") params.set("tab", "myTurn");
  return params;
}

export function matchesTarget(pathname: string, search: string, target: string) {
  const [path, query = ""] = target.split("?");
  if (!matchesMenuPath(pathname, path)) return false;
  const params = normalizedSearch(pathname, search);
  return [...new URLSearchParams(query)].every(([key, value]) => params.get(key) === value);
}

export function safeListReturn(value: string | null): string | null {
  if (!value || value.length > 4096 || !value.startsWith("/") || value.startsWith("//")) return null;
  try {
    const url = new URL(value, "http://omnis.local");
    if (url.origin !== "http://omnis.local" || ![
      "/tasks", "/approvals/receipts", "/approvals/todo", "/approvals/in-progress",
    ].includes(url.pathname)) return null;
    return url.pathname + url.search;
  } catch {
    return null;
  }
}

export function withListReturn(target: string, list: string) {
  const safe = safeListReturn(list);
  if (!safe) return target;
  const [path, query = ""] = target.split("?");
  const params = new URLSearchParams(query);
  params.set("returnTo", safe);
  return path + "?" + params.toString();
}

// Registration belongs to the department list, even when a hand-edited URL names another list.
export function safeDepartmentListReturn(value: string | null): string | null {
  const safe = safeListReturn(value);
  if (!safe) return null;
  const url = new URL(safe, "http://omnis.local");
  return url.pathname === "/tasks" && normalizedSearch(url.pathname, url.search).get("tab") === "department" ? safe : null;
}

export function listPathOf(pathname: string, search = ""): string | null {
  if (pathname === "/approvals/new") return safeDepartmentListReturn(new URLSearchParams(search).get("returnTo")) ?? "/tasks?tab=department";
  if (!/^\/(work\/(items|requests)|approvals\/docs)\/\d+(\/edit)?$/.test(pathname)) return null;
  const params = new URLSearchParams(search);
  const saved = safeListReturn(params.get("returnTo"));
  if (saved) return saved;
  if (pathname.startsWith("/approvals/docs/")) return "/approvals/todo?tab=myTurn";
  const from = params.get("from");
  if (from === "receipts") return "/approvals/receipts";
  return "/tasks?tab=" + (from === "mine" || from === "viewable" ? from : "department");
}

export function navigationContext(pathname: string, search = "") {
  const list = pathname === "/approvals/new" ? null : listPathOf(pathname, search);
  if (list) {
    const url = new URL(list, "http://omnis.local");
    pathname = url.pathname;
    search = url.search;
  }
  for (const screen of screens) {
    const action = screen.actions?.find(({ path }) => matchesTarget(pathname, search, path));
    if (action) return { screen, active: action, action: true };
    const tab = screen.tabs.find(({ path }) => matchesTarget(pathname, search, path));
    if (tab) return { screen, active: tab, action: false };
  }
  return null;
}

export function screenPath(key: string, features: readonly string[]) {
  const screen = screens.find((item) => item.key === key);
  return screen && visibleLinks(screen.tabs, features)[0]?.path;
}

export type MenuTrail = { group: string; item: string; sub?: string };
export function menuTrail(pathname: string, search = ""): MenuTrail | null {
  const context = navigationContext(pathname, search);
  if (!context) return null;
  const { screen, active } = context;
  return {
    group: screen.owner === screen.key ? "home" : screen.owner,
    item: screen.key,
    sub: screen.tabs.length > 1 || context.action ? active.key : undefined,
  };
}

export function menuItemPath(trail: MenuTrail) {
  const screen = screens.find((item) => item.key === trail.item);
  if (!screen) return undefined;
  return trail.sub
    ? [...screen.tabs, ...(screen.actions ?? [])].find(({ key }) => key === trail.sub)?.path
    : screen.tabs[0]?.path;
}

// Show management actions next to the corresponding organization or equipment tab.
export function contextualActions(screen: Screen, activeKey: string) {
  const associations: Record<string, string[]> = {
    orgChart: ["orgManage"],
    people: ["personnelManage"],
    equipment: ["equipmentManage"],
  };
  const actions = (screen.actions ?? []).filter(({ inPage }) => !inPage);
  return screen.owner === "departmentInfo"
    ? actions.filter(({ key }) => associations[activeKey]?.includes(key))
    : actions.filter(({ key }) => key !== activeKey);
}
