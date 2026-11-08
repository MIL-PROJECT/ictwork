# WAR 웹 실행 및 소스 이관 (2026-11-09)

`output/OMNIS-Web-20261109/`에 Setup/Electron 없이 실행할 WAR, 전체 소스, 기존 DB 논리 백업, QA 설정, 대상 환경용 설정 예시와 실행 스크립트를 정리한다. `web/Package-Web.ps1`로 새 출력 폴더를 생성하며, 기존 출력 폴더나 원본 프로필을 덮어쓰지 않는다.

## 전달 구성

- `source/`: Git 커밋의 전체 프로젝트 소스. 백엔드는 `source/src/backend`, 프런트는 `source/src/frontend`.
- `OMNIS-source.zip`: 같은 커밋의 소스 압축본. `.git`·의존성 폴더·개인 DB 설정 제외.
- `app/OMNIS.war`: Setup에 사용한 WAR와 바이트 동일. SHA-256 `9bfd763cb8c9072c2be58bf412a3274fc0ba85a5aafa75d9a8c59373a94e7eb6`.
- `database/final-db.sql`: Setup과 같은 데이터 스냅샷. SHA-256 `46d8d8e8b4b388fb04d94260a2b854457b711bbe682b9caeee18043e8242a812`.
- `database/manifest.json`, `config/qa-config.yml`: 원래 테이블 정보/QA 로그인 해시 보존. DB 암호와 외부 AI API 키를 소스에 넣지 않는다.
- 설정 예시와 Windows용 실행/CMD/복원 스크립트, Linux/macOS 실행 예시 및 README.

WAR는 production 프런트엔드를 함께 제공한다. 웹 실행에는 Java 17, MySQL 8.0 서버/클라이언트가 필요하며 Node.js·Vite·Electron·별도 Tomcat은 필요 없다. 이 묶음에 Java/MySQL 설치 프로그램은 중복 포함하지 않는다. 소스 재빌드에는 JDK 17·Node.js·pnpm과 Gradle 의존성/캐시가 필요하다.

## 실행과 데이터 경계

1. 대상 MySQL에 새 DB/앱 계정을 만든다. 생성 예시는 `database/create-database.example.sql`.
2. `Restore-Database.ps1`로 SQL을 가져온다. SHA-256과 대상 DB의 빈 상태를 먼저 확인하며, 비어 있지 않은 DB를 거부한다. UTF-8 SQL 바이트를 디코딩/재인코딩하지 않고 mysql 표준입력으로 전달한다. 복원 후 90개 테이블의 행 수를 확인한다.
3. `config/application.properties.example`을 `application.properties`로 복사해 DB 주소·계정·암호·초기화용 암호를 설정한다. 대상 자격증명은 전달 소스에 고정하지 않는다.
4. `Run-Web.cmd` 또는 `Run-Web.ps1`로 Java WAR를 실행한다. 기본 접속은 `http://127.0.0.1:8080/signin`이며 포트 변경을 지원한다. QA 설정을 추가 읽고 demo Flyway 이력을 유지한다.
5. Ctrl+C로 Java를 종료한다. MySQL은 외부 서버로서 계속 실행된다. 이 작업은 원래 설치 앱/DB를 시작하거나 설정을 바꾸지 않는다.

데이터는 해당 MySQL 서버에 저장되며, Setup 제작 후 입력한 자료를 동기화하지 않는다. 기존 데이터/계정/첨부 본문이 SQL에 포함된다. 원본 프로필의 외부 AI 암호화 키 파일은 존재하지 않았으며, 이후 외부 키를 등록한다면 키 파일도 별도로 보관해야 한다. 기본 AI는 LOCAL이며 자동 업무 완료 등 scheduler는 이관용 예시 설정에서 껐다. LAN 서비스는 별도 바인딩/방화벽/HTTPS 설정이 필요하고 이번 작업에서 외부 공개는 하지 않았다.

## Windows 실측 검증

증거는 `output/OMNIS-Web-Validation-20261109/evidence/`에 있으며, 검증용 자격증명과 변경된 시험 DB는 최종 전달본에서 제외한다.

- 원본 service 대신 기존 검증 백업의 복사본을 기동하고, 별도의 빈 `omnis_web_transfer` DB와 시험 앱 계정에 Windows PowerShell 5.1 복원 스크립트를 실행했다.
- 복원 직후 90개 테이블의 행 수·모든 행 지문이 manifest와 일치했다. 외래키와 첨부파일 크기/해시 검사도 통과했다.
- 같은 DB로 두 번째 복원을 시도했을 때 기존 데이터가 있다는 이유로 중단했으며, 전후 DB 스냅샷이 동일했다.
- `Run-Web.ps1`에서 번들 Java 17을 지정해 실제 WAR를 실행했다. MySQL은 격리된 로컬 포트를 사용했고 Flyway V45/71개 migration 검증 후 시작했다.
- Electron/Vite 없이 WAR에서 `/signin`과 production JS를 제공했다. 기존 `bn1-co1-admin` 암호 로그인, 운영 체계 38개, 업무·접수·지시사항·구성도·일정·문서함 조회를 확인했다.
- LOCAL SUMMARY/PRIORITY/SIMILAR/TRIAGE 4종 응답, 일반 계정의 시스템 관리자 API 차단, 일정 저장/재조회까지 HTTP 검사 15개를 통과했다.
- Ctrl+C 때 Hibernate/Hikari shutdown 완료 로그를 확인했다. Windows CMD 래퍼로 재시작해 저장된 일정과 기존 로그인이 유지되는지 확인했다.
- PowerShell 구문 분석 및 `git diff --check`를 실행했다. 최종 전달 WAR·SQL·QA 설정과 패키징 원본 해시를 비교한다.

Windows PowerShell 5.1 자식 실행 검증에서는 부모 PowerShell 7의 PSModulePath를 제거해 기본 Windows 모듈을 사용했다. CMD 래퍼도 자기 프로세스 환경의 PSModulePath만 초기화한다. 실제 사용자 시스템 환경 설정은 바꾸지 않는다.

다른 물리 PC나 Linux/macOS 실행은 이번 검증 범위에 포함하지 않는다. Linux/macOS용 스크립트와 SQL은 이관 예시이며, 해당 OS용 Java/MySQL을 준비한 후 검증해야 한다. 기존 Setup 바이너리는 이 작업에서 변경하지 않았다.

## 재생성

```powershell
.\web\Package-Web.ps1 -Destination .\output\OMNIS-Web-새버전
```

기존 SQL과 다른 WAR로 재배포할 때는 그 버전의 schema/seed와 SQL manifest를 함께 다시 검증한다. 빌드 도구는 WAR/SQL/QA의 명세 해시가 다르면 중단한다. 출력 폴더의 `package-manifest.json`에 소스 커밋과 dirty 여부, 파일별 SHA-256을 기록한다.
