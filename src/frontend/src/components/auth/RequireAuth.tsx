import ForcedPasswordChange from "@/components/auth/ForcedPasswordChange";
import { useAuth } from "@/context/AuthContext";
import type { ReactNode } from "react";
import { Navigate, useLocation } from "react-router";

// 로그인하지 않았으면 로그인 화면으로 보낸다 (REQ-001). 초기 비밀번호면 비밀번호를 바꾸기 전까지 변경 창만 보인다 (DEC-056)
// QA 마스터로 로그인했으면 계정 고르기 화면으로 보낸다 (DEC-067)
// 로그인 뒤 보던 화면(경로+주소 조건)으로 돌아가게 넘긴다. 쓰다가 세션이 끊겼으면 안내도 띄운다. 로그아웃 단추로 나갔으면 둘 다 없음
export default function RequireAuth({ children }: { children: ReactNode }) {
  const { user, signedOutReason } = useAuth();
  const location = useLocation();
  if (user === undefined) return null;
  if (user === null) {
    if (signedOutReason === "signedOut") return <Navigate to="/signin" replace />;
    return <Navigate to="/signin" replace state={{ from: `${location.pathname}${location.search}`, expired: signedOutReason === "expired" }} />;
  }
  if (user.qaMode === "MASTER") return <Navigate to="/qa" replace />;
  if (user.passwordChangeRequired) return <ForcedPasswordChange />;
  return children;
}
