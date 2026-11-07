type SectionLink = { key: string; path: string; feature?: string };

export const sectionLinks = {
  dutyDirectives: [
    { key: "duty", path: "/duty" },
    { key: "directives", path: "/directives" },
  ],
  organizationPeople: [
    { key: "orgChart", path: "/department/org-chart" },
    { key: "people", path: "/department/people" },
  ],
  equipmentDiagrams: [
    { key: "equipment", path: "/department/equipment" },
    { key: "diagrams", path: "/department/diagrams" },
  ],
  organizationPersonnelManage: [
    { key: "orgManage", path: "/admin/organization", feature: "ADMIN_ORG" },
    { key: "personnelManage", path: "/admin/personnel", feature: "ADMIN_PERSONNEL" },
  ],
} satisfies Record<string, SectionLink[]>;

export type SectionKey = keyof typeof sectionLinks;

export function visibleSectionLinks(section: SectionKey, features: readonly string[]) {
  const links: readonly SectionLink[] = sectionLinks[section];
  return links.filter(({ feature }) => !feature || features.includes(feature));
}

export function matchesMenuPath(pathname: string, path: string) {
  return pathname === path || pathname.startsWith(path + "/");
}
