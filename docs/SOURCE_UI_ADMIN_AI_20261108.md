# OMNIS UI·최고 관리자·AI 소스 변경 (2026-11-08)

## 적용 범위

대상은 `military_project_final`의 React / Spring Boot / MySQL 소스다. `military_project`와 GitHub `alex50027319/military_project`의 관리자·등록·AI 기능을 참고하되, 기존 Electron IPC / SQLite 코드를 현재 인증·API·트랜잭션에 맞춰 구현했다. Setup.exe, 배포 폴더의 resources, 설치된 사용자 프로필은 이 작업에서 갱신하지 않는다.

## 사용 흐름

- 상황판 운영 현황: 화면 크기와 무관하게 페이지당 4개. 마지막 페이지는 남은 항목만 표시한다.
- QA 사이드바: `QA 모드 · 계정 바꾸기`를 한 줄로 표시하고 AI 상태 카드와 같은 높이·여백을 사용한다. 이름·아이디는 생략하고 기존 계정 전환은 유지한다.
- 상단 지표: 하단 설명의 줄바꿈과 최소 높이를 확보한다. 작은 화면은 필요한 세로 스크롤로 내용과 페이지 조작부를 보존한다.
- 로그인 → 사용자 등록 신청(`/signup`) → 최고 관리자 → 등록 신청 → 소속·역할 지정 후 승인 또는 사유 입력 후 반려. 승인 전에는 계정이 없으며 신청 비밀번호는 해시로만 보관하고 처리 후 신청 해시는 지운다.
- `sysadmin`의 기존 SYS_ADMIN 역할을 최고 관리자로 사용한다. 이름만으로 권한을 부여하지 않는다. 해당 역할은 체계 전체의 조직·인원·장비·설치장소·업무 유형·추가 특성·현황·근무표 관리 권한을 포함한다. 일반 사용자·부서 관리자 범위, 개인 AI 대화 소유권과 명시적으로 공유된 구성도 접근은 유지한다. 공백 구성도 복구는 최고 관리자도 가능하다.
- 시스템 메뉴(`/admin/system`, SYS_ADMIN 전용): 계정 검색, 정보·역할 수정, 잠금·해제, 활성·중지, 비밀번호 초기화, 가입 승인·반려, 로그인 실패 정책, 감사 기록, AI 설정. 민감 작업은 현재 관리자 비밀번호·변경 사유·version 재확인이 필요하다. 자기 계정의 사용·역할 변경 및 마지막 관리자 제거를 방지한다.

## AI 제공자

기본 LOCAL은 API 키나 생성형 모델 없이 기존 권한별 조회 도구와 규칙으로 체계·업무·장비·인원·근무·지시사항·현황을 조회하고 자료를 발췌한다. 일반적인 자유 대화 모델의 품질과 동일하지 않다. 업무 작성 제안은 LOCAL에서 자동 생성하지 않으며 안내를 표시한다.

Ollama는 같은 PC의 HTTP loopback 주소만 허용하고 기존 조회·색인·도구 호출 흐름을 재사용한다. OpenAI는 Responses API, Anthropic은 Messages API 어댑터를 제공한다. 외부 호출은 관리자 동의·모델 ID·API 키가 모두 필요하다. 키는 AES-GCM으로 암호화하고 별도 프로필의 `config/ai-secrets.key`로 복호화한다. DB를 옮길 때 키 파일도 함께 보존해야 한다. 설정 조회·감사에는 키나 비밀번호를 포함하지 않는다. 일일 호출 한도는 비용의 달러 상한이 아니며, 추론/도구 반복 요청을 각각 센다. 유료 자동 재시도는 하지 않는다.

외부 모델 장애 시 로컬 규칙으로 전환했다는 안내를 표시한다. 실제 키·외부 서버·Ollama 모델 추론은 이번 검증 범위가 아니다. 외부 프로토콜은 네트워크 대신 모의 응답으로 검증한다.

## DB와 dev 실행

V45는 `system_setting`, `account_security`, `signup_request`, `system_audit`, `ai_daily_usage`만 추가한다. 기존 계정·역할·비밀번호를 초기화하지 않는다. 개발 서버가 새 WAR로 시작하면 개발 프로필 DB에 마이그레이션된다.

```powershell
# 저장소 루트에서, 기존 dev 창을 닫은 뒤
pnpm --dir src/frontend build
# JAVA_HOME과 GRADLE_USER_HOME은 사용 가능한 Java 17 / Gradle 8.6 환경으로 설정
src/backend/gradlew.bat -p src/backend bootWar -x buildFrontend -x installFrontend --offline
pnpm --dir desktop dev
```

`desktop/dev.cjs`는 빌드한 `src/backend/build/libs/ictwork-0.0.1.war`가 있으면 우선 사용한다. `OMNIS_DEV_WAR`로 경로를 지정할 수도 있다. 프런트는 저장 시 HMR, Kotlin 변경은 WAR 재빌드와 dev 재시작이 필요하다. 개발 DB와 로그는 `output/dev/profile/service`에 있으며 설치 프로필과 다르다.

## 재현 가능한 검증

`node desktop/start-source-validation.cjs`는 최종 시연 SQL을 새로운 `output/source-validation/<시각>/profile`에 초기화하고 빌드 WAR 사본·Vite·Java·MySQL을 실행한다. 테스트용 비밀번호와 DB 자격 증명은 Git 제외 fixture.json에만 저장한다. `node desktop/verify-source-api.cjs`는 해당 복제 프로필만 변경한다. 프로필 안의 별도 테스트 스키마를 SPRING_DATASOURCE_URL / USERNAME / PASSWORD에 전달하여 Gradle test를 실행한다. 검증 실행기 표준입력에 `quit`를 보내 정상 종료한다.

검증 결과와 화면은 `output/source-validation/` 및 `output/dev/source-*.log`에 보관한다. 테스트는 실제 운영·다른 PC·실제 AI 추론의 보증이 아니다.

## 이번 실행 결과

- TypeScript/Vite production build 및 변경 프런트 파일 ESLint: 통과.
- 백엔드 총 844개 사례, 전체 실행 뒤 86개 관련 사례를 보완·재검증해 최신 결과 실패 0. 단일 전체 실행에서 한 번에 전부 통과한 결과는 아니다. 기존 FakeOllama 비동기 HW 조회 사례 한 건은 전체 실행에서 30초 대기 초과가 났으나 동일 사례 재실행은 통과했다. 이 간헐 대기의 근본 원인이 해결됐다고 단정하지 않는다.
- 최종 WAR의 새 복제 프로필에서 실제 HTTP 검증 7개 그룹 전부 통과. 익명/부서 관리자 차단, 가입 신청·중복·CSRF·동시 승인·반려, 세션 잠금 및 역할 회수, 실패 잠금 정책, 가상 키 암호화·마스킹·제거, 로컬 AI의 실제 수치/권한 범위/개인 대화 보호, 최고 관리자 진입점·감사 기록을 확인했다. 실제 외부 전송은 0회다.
- 1440×900, 1366×768, 1280×720, 390×844에서 4행의 조상 clipping 및 지표 설명 잘림·가로 넘침 없음. 1600×1000에서 QA·AI 카드 각각 41.25 CSS px.
- 기존 dev DB의 V45 적용 후 계정 62개·운영 체계 38개를 보존했다. 개발 창은 새 소스 WAR로 재시작했고 AI 화면에 LOCAL 제공자가 표시됐다.
- 통합 근거: `output/dev/source-verification.json`. 백엔드와 화면 근거: `output/source-validation/2026-11-08T12-24-15-465Z/`. 최종 WAR API·화면 근거: `output/source-validation/2026-11-08T12-47-30-959Z/`.

외부 어댑터 구현 참고: [OpenAI Responses](https://developers.openai.com/api/docs/guides/migrate-to-responses), [Anthropic Messages](https://platform.claude.com/docs/en/api/messages/create). 실제 모델 응답 품질·요금·다른 PC 설치는 검증하지 않았다.
