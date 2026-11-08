# 업무 통계·통합상황판 배치 정리

2026-11-08, `feat/final-mvp`의 기존 OMNIS UI를 유지한 후속 변경이다.

## 화면 변경

- 업무 통계의 보기 조건은 모든 창 크기에서 본문 위에 표시한다. 오른쪽 사이드 패널·모바일 모달을 제거하고, 현황·실적·차트·표가 본문 전체 폭을 사용한다.
- 소속 부서만 조회하는 계정은 기간을 가로로 표시한다. 부서 선택 권한이 있는 계정은 같은 상단 카드 안에서 범위·보기·기간을 구분한다. 좁은 창에서는 자연스럽게 줄바꿈하며 긴 조직 트리는 카드 안에서 스크롤한다.
- 기존 보기 조건 버튼으로 접고 펼칠 수 있다. 닫으면 버튼으로 초점을 돌려주며, 조건·주소·조회 결과를 유지한다.
- 통합상황판 제목 위의 `운용 현황`은 제거했다.
- CPCON·위기경보단계의 세로 색상 막대와 주의 현황의 빨간 왼쪽 테두리를 제거했다. 중립색 둥근 카드·안쪽 요약줄·상태별 건수 배지·전체 보기 버튼으로 정돈했다. 상태 글자와 색상, 주의 우선순위와 전체 조회·상세 연결은 유지한다.

## 유지 범위

API·Java/MySQL·권한·집계·저장·메뉴·Electron 기동/종료 코드는 변경하지 않는다. 현재 사용자 프로필의 운영현황 시연 자료 38개도 추가·수정하지 않는다. 인쇄·엑셀, 주소에 저장한 부서·기간·통합/부서별 보기, 업무 목록 이동과 기존 주의 현황의 실패/오래된 자료/권한 회수 처리를 재사용한다.

## 검증과 산출물

새 실행본은 `output/OMNIS-Layout-Polish-Windows-x64/OMNIS.exe`다. `resources`를 포함한 **폴더 전체**가 필요하다. 기존 앱을 닫고 실행하면 같은 Windows 사용자 프로필을 사용하며 Java·MySQL을 자동 시작한다. 일반 창 닫기에서 해당 서비스도 종료한다.

검증 명령은 저장소 루트에서 실행한다. 각 전체 검증에는 아직 존재하지 않는 새 evidence 경로를 사용한다.

```powershell
pnpm --dir src/frontend exec tsc -b
pnpm --dir src/frontend exec eslint src/pages/Status/WorkStatus.tsx src/components/status/ConditionPanel.tsx src/pages/Board/ControlBoard.tsx
node --experimental-strip-types --test src/frontend/scripts/*.test.ts
node desktop/setup-smoke.cjs output/OMNIS-Layout-Polish-Windows-x64 output/layout-polish/verified2
node desktop/verify-board-roles.cjs output/OMNIS-Layout-Polish-Windows-x64 output/layout-polish/verified2
```

`verify-layout-polish.cjs`는 실제 조회·기간 입력·주소 복원·엑셀 응답·인쇄 호출/인쇄 CSS·상단 배치/전체 폭·모바일/영문/다크를 검사한다. 시스템 인쇄 대화상자는 테스트에서 열지 않는다. 역할 검사는 성공한 독립 합성 프로필에서만 네 계정의 실제 권한과 상단 조직 선택·부서별 비교를 확인한다. 상황판의 기존 18개 크기/배율/AI 패널 조합 및 주의 현황의 오류·권한·복구 검사를 함께 실행한다. 합성 경보 fixture와 실제 API 검증은 각각 결과에 구분한다.

실행 결과·화면·정적 검사·패키지 무결성 근거는 `output/layout-polish/`에 보관한다. 다른 PC 설치와 실제 통신체계 자동 계측, 실제 AI 모델 추론은 이 UI 변경의 검증 대상이 아니다.

이번 실행 결과: TypeScript·변경 TSX ESLint·프런트엔드 104개 테스트, 새 프로필 두 차례 기동/재시작/정상 종료, 실제 업무 요청·접수·계획·완료 결재·반려·등록대장 복귀, 역할 네 계정의 실제 부서 범위·조직 검색·통계 비교, 상황판 18개 데스크톱 조합과 모바일 흐름, 통계 4개 너비와 영문/다크 검사가 통과했다. 실제 엑셀 파일은 6개 시트와 ZIP 무결성을 확인했으며, 인쇄 호출과 인쇄 CSS는 시스템 대화상자를 열지 않고 확인했다.

실제 사용자 프로필의 읽기 검증은 `current-profile/result.json`에 기록했다. 체계 38개·정상 30개·정상 운용률 79%·이력 3개가 유지되고, 상단 보기 조건과 본문 폭은 모두 1266px이었다(1600×900 창). 기존 보호 대상 소스 321개 및 WAR 안 서버 코드·라이브러리 파일 1190개는 변경 전과 동일했다. 최종 전체 회귀 근거는 `verified2/installed-smoke-result.json`, 역할 근거는 `verified2/board-roles-result.json`이다. 앞선 테스트 대기·다운로드 수집 보완 과정의 실패 기록은 별도 디렉터리에 보존했다.
