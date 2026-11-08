import IctStatus from "@/pages/IctStatus/IctStatus";
import { operationView, operationViewPath } from "@/layout/operationNavigation";
import { Navigate, useLocation } from "react-router";
import Operations from "./Operations";

export default function OperationsWorkspace() {
  const { search } = useLocation();
  return operationView(search) === "signals" ? <IctStatus integrated /> : <Operations />;
}

export function LegacyIctRedirect() {
  const { search, hash } = useLocation();
  return <Navigate replace to={operationViewPath(search, "signals") + hash} />;
}
