// 구성도 그리기 도구(Excalidraw)의 글꼴을 체계 안에서 내보내도록 public/excalidraw-assets로 복사한다 (slices/8a 7·10장).
// 외부 CDN을 쓰지 않는다(src/frontend/CLAUDE.md 10장). dev·build 전에 돈다. 복사본은 Git에서 뺀다
import { cpSync, existsSync, rmSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

const root = join(dirname(fileURLToPath(import.meta.url)), "..");
// 패키지 exports가 package.json을 열어 두지 않아 node_modules 경로로 찾는다(pnpm도 이 자리에 연결을 둔다)
const source = join(root, "node_modules", "@excalidraw", "excalidraw", "dist", "prod", "fonts");
const target = join(root, "public", "excalidraw-assets", "fonts");

if (!existsSync(source)) {
  console.error(`Excalidraw 글꼴을 찾지 못했습니다: ${source}`);
  process.exit(1);
}
rmSync(target, { recursive: true, force: true });
cpSync(source, target, { recursive: true });
console.log(`Excalidraw 글꼴 복사: ${target}`);
