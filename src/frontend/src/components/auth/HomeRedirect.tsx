import { Navigate } from "react-router";

// 첫 화면: 로그인하면 모두 통합상황판 (REQ-901, DEC-123. 이전에는 업무 현황, G-21)
export default function HomeRedirect() {
  return <Navigate to="/board" replace />;
}
