import { useAuth } from "@/context/AuthContext";
import Forbidden from "@/pages/OtherPage/Forbidden";
import type { ReactNode } from "react";

// 관리 화면: 그 기능 코드가 있는 사용자만 (REQ-002). 막는 것은 서버 API이고, 이 가드는 화면 안내용이다.
// fallback: 코드가 없을 때 권한 없음 대신 보일 화면 (AI를 끈 서버의 안내, REQ-B09·H-27)
export default function RequireFeature({ feature, fallback, children }: { feature: string; fallback?: ReactNode; children: ReactNode }) {
  const { user } = useAuth();
  if (!user?.features.includes(feature)) return fallback ?? <Forbidden />;
  return children;
}
