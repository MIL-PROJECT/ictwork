import OperationsWorkspace, { LegacyIctRedirect } from "./pages/Operations/OperationsWorkspace";
import { Navigate, Route, BrowserRouter as Router, Routes, useLocation } from "react-router";
import ControlBoard from "./pages/Board/ControlBoard";
import HomeRedirect from "./components/auth/HomeRedirect";
import RequireAuth from "./components/auth/RequireAuth";
import RequireFeature from "./components/auth/RequireFeature";
import { ScrollToTop } from "./components/common/ScrollToTop";
import AppLayout from "./layout/AppLayout";
import ApprovalDoc from "./pages/Approval/ApprovalDoc";
import ApprovalInbox from "./pages/Approval/ApprovalInbox";
import MyDrafts from "./pages/Approval/MyDrafts";
import ApprovalRouteManage from "./pages/Admin/ApprovalRouteManage";
import EquipmentManage from "./pages/Admin/EquipmentManage";
import OrganizationManage from "./pages/Admin/OrganizationManage";
import PersonnelManage from "./pages/Admin/PersonnelManage";
import QaAccountPicker from "./pages/Qa/QaAccountPicker";
import WorkGuidance from "./pages/Department/WorkGuidance";
import WorkTypeManage from "./pages/Admin/WorkTypeManage";
import AttributeManage from "./pages/Admin/AttributeManage";
import AiChat from "./pages/Ai/AiChat";
import SignIn from "./pages/AuthPages/SignIn";
import WorkStatus from "./pages/Status/WorkStatus";
import Calendar from "./pages/Calendar";
import DiagramList from "./pages/Department/DiagramList";
import DiagramView from "./pages/Department/DiagramView";
import DocBox from "./pages/Department/DocBox";
import EquipmentDetail from "./pages/Department/EquipmentDetail";
import EquipmentList from "./pages/Department/EquipmentList";
import OrgChart from "./pages/Department/OrgChart";
import PeopleList from "./pages/Department/PeopleList";
import NotFound from "./pages/OtherPage/NotFound";
import NotSupported from "./pages/OtherPage/NotSupported";
import MySettings from "./pages/Settings/MySettings";
import ItemDetail from "./pages/Work/ItemDetail";
import Receipts from "./pages/Work/Receipts";
import RequestDetail from "./pages/Work/RequestDetail";
import RequestForm from "./pages/Work/RequestForm";
import TaskList from "./pages/Work/TaskList";
import Directives from "./pages/Work/Directives";
import DutyEdit from "./pages/Work/DutyEdit";
import DutyStatus from "./pages/Work/DutyStatus";

/** 옛 주소를 새 주소로 옮긴다(주소 조건은 그대로, 저장된 AI 답 링크, slices/14 14.8.1) */
function MovedTo({ to }: { to: string }) {
  const { search } = useLocation();
  return <Navigate replace to={to + search} />;
}

export default function App() {
  return (
    <>
      <Router>
        <ScrollToTop />
        <Routes>
          {/* Dashboard Layout — 로그인한 사용자만 (REQ-001) */}
          <Route
            element={
              <RequireAuth>
                <AppLayout />
              </RequireAuth>
            }
          >
            {/* 첫 화면은 업무 현황 (REQ-901, DEC-070, 2026-10-09 "달력" 결정을 바꿈, 외부 부서 예외는 없앰 G-21) */}
            <Route index path="/" element={<HomeRedirect />} />
            {/* 업무 현황 (REQ-901~909, DEC-075): 기능 코드는 로그인한 모두에게 있다(G-21). 막는 것은 서버 API */}
            <Route path="/status" element={<RequireFeature feature="WORK_DASHBOARD"><WorkStatus /></RequireFeature>} />
            {/* 지시사항 (REQ-601, slices/13 6.1): 로그인한 모두. 보는 범위는 서버가 정한다 */}
            {/* 통합상황판 (OMNIS 역설계 시제품) */}
            <Route path="/board" element={<ControlBoard />} />
            <Route path="/operations" element={<OperationsWorkspace />} />
            <Route path="/directives" element={<Directives />} />
            {/* 근무자 현황(로그인한 모두)·근무표 수정(manage가 403이면 권한 없음 화면) (REQ-404, slices/14 14.8.1). 옛 주소는 옮기기만 */}
            <Route path="/duty" element={<DutyStatus />} />
            <Route path="/duty/edit" element={<DutyEdit />} />
            <Route path="/directives/duty" element={<MovedTo to="/duty/edit" />} />
            <Route path="/directives/duty/calendar" element={<MovedTo to="/duty" />} />

            {/* 정보통신 현황 (REQ-503·602, slices/15 13.1): 로그인한 모두. 박스마다 판정은 서버 API */}
            <Route path="/ict-status" element={<LegacyIctRedirect />} />

            {/* 업무·결재 (B 업무·결재, DEC-060) */}
            <Route path="/tasks" element={<TaskList />} />
            <Route path="/work/items/:id" element={<ItemDetail />} />
            <Route path="/work/requests/:id" element={<RequestDetail />} />
            {/* 두 경로가 같은 화면이라 key를 달리해 오갈 때 입력 값을 새로 시작한다 */}
            <Route path="/work/requests/:id/edit" element={<RequestForm key="edit" />} />
            {/* 업무 등록(우리 부서, 접수 건너뜀)과 업무 요청(받는 부서 고름) (DEC-061) */}
            <Route path="/approvals/new" element={<RequestForm key="register" kind="REGISTER" />} />
            <Route path="/approvals/request" element={<RequestForm key="request" kind="REQUEST" />} />
            <Route path="/approvals/receipts" element={<Receipts />} />
            <Route path="/approvals/todo" element={<ApprovalInbox />} />
            <Route path="/approvals/in-progress" element={<MyDrafts />} />
            <Route path="/approvals/docs/:id" element={<ApprovalDoc />} />
            <Route path="/department/services" element={<WorkGuidance />} />
            <Route path="/admin/department-services" element={<MovedTo to="/admin/work-types" />} />
            <Route path="/department/org-chart" element={<OrgChart />} />
            <Route path="/department/people" element={<PeopleList />} />
            <Route path="/department/equipment" element={<EquipmentList />} />
            {/* 등록과 상세가 같은 화면이라 key를 달리해 등록 뒤 상세로 갈 때 상태를 새로 시작한다 */}
            <Route path="/department/equipment/new" element={<EquipmentDetail key="new" creating />} />
            <Route path="/department/equipment/:id" element={<EquipmentDetail key="detail" />} />
            <Route path="/department/diagrams" element={<DiagramList />} />
            <Route path="/department/diagrams/:id" element={<DiagramView />} />
            <Route path="/department/docs" element={<DocBox />} />
            {/* AI 질의응답 (REQ-B01·B09): AI를 켠 서버만 기능 코드 AI_QA를 준다. 끈 서버는 안내만(H-27). 막는 것은 서버 API(꺼지면 404) */}
            <Route path="/ai" element={<RequireFeature feature="AI_QA" fallback={<NotSupported titleKey="aiChat" />}><AiChat /></RequireFeature>} />
            <Route path="/settings/me" element={<MySettings />} />

            {/* 관리: 권한 있는 사용자만 (REQ-002, A1-b). 막는 것은 서버 API */}
            <Route path="/admin/organization" element={<RequireFeature feature="ADMIN_ORG"><OrganizationManage /></RequireFeature>} />
            <Route path="/admin/personnel" element={<RequireFeature feature="ADMIN_PERSONNEL"><PersonnelManage /></RequireFeature>} />
            <Route path="/admin/equipment" element={<RequireFeature feature="ADMIN_EQUIPMENT"><EquipmentManage /></RequireFeature>} />
            {/* 업무 유형: 부서 관리자(역할 대상 부서, REQ-808). 요청 유형(REQ-805)은 없앰 (G-32) */}
            <Route path="/admin/work-types" element={<RequireFeature feature="ADMIN_WORK_TYPE"><WorkTypeManage /></RequireFeature>} />
            {/* 특성 대상은 장비·인원 둘뿐이라 경로를 따로 둔다. 그 밖의 대상(/admin/attributes/xyz)은 없는 주소(NotFound) */}
            <Route path="/admin/attributes/equipment" element={<RequireFeature feature="ADMIN_ATTRIBUTE"><AttributeManage target="equipment" /></RequireFeature>} />
            <Route path="/admin/attributes/personnel" element={<RequireFeature feature="ADMIN_ATTRIBUTE"><AttributeManage target="personnel" /></RequireFeature>} />
            <Route path="/admin/approval-routes" element={<RequireFeature feature="ADMIN_SYSTEM"><ApprovalRouteManage /></RequireFeature>} />

            <Route path="/calendar" element={<Calendar />} />
          </Route>

          {/* Auth Layout */}
          <Route path="/signin" element={<SignIn />} />
          {/* QA 마스터의 계정 고르기 (DEC-067, 명세 밖 QA·시연 도구). 마스터만, 막는 것은 서버 API */}
          <Route path="/qa" element={<QaAccountPicker />} />

          {/* Fallback Route */}
          <Route path="*" element={<NotFound />} />
        </Routes>
      </Router>
    </>
  );
}
