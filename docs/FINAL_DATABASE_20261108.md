# 최종 DB: 기존 자료 + ictwork main seed

기준 코드는 `MIL-PROJECT/ictwork` main `36a71b908aed69136c8941d5134a991e15ae74f4`이다. 원격 main 확인과 `git fetch origin main`을 수행했고 checkout이 같은 커밋임을 확인했다.

## 원본과 최종본

- 원본: `%APPDATA%\OMNIS Desktop Main\service`의 MySQL `ictwork`.
- 원본 파일 백업: `output/final-db/20261108-main-merged/source-backup/service/`.
- 원본 SQL 백업: `output/final-db/20261108-main-merged/original-db.sql`.
- main의 독립 seed SQL: `output/final-db/20261108-main-merged/main-seed.sql`.
- 최종 DB SQL: `output/final-db/20261108-main-merged/final-db.sql`.
- 설치본에 포함할 자료: `output/final-db/20261108-main-merged/bootstrap/` 전체.
- 통합 폴더형 실행본: `output/OMNIS-Final-Database-Windows-x64/OMNIS.exe`.

원본 서비스의 308개 파일을 SHA-256으로 기록하고 동일한 복사본을 만들었다. 원본에는 MySQL·Java를 실행하거나 SQL을 적용하지 않았다. 기존 SQLite OMNIS와 별도의 개발용 MySQL은 이 병합의 입력으로 사용하지 않았다.

최종 SQL에는 앱의 테이블·자료·첨부 본문이 들어 있다. PC별 MySQL root/app 접속 암호와 MySQL 시스템 계정은 포함하지 않는다. 새 설치 프로필은 자체 DB 접속 암호를 생성한다. `bootstrap/qa-config.yml`에는 기존 QA 마스터 아이디와 bcrypt 해시만 보존하며 평문 암호를 추가하지 않았다.

## 병합 규칙

기존 DB와 최신 seed는 같은 숫자 ID를 다른 자료에 사용한다. 단순한 `INSERT IGNORE`나 ID 기준 덮어쓰기를 사용하지 않았다.

1. 계정은 로그인 ID·연결 인원, 인원은 계정과 인원 식별, 조직은 검증된 유형·상위 조직 계보, 장비는 모델명·시리얼번호 등으로 기존 대상을 찾았다. 기존 대상의 행은 변경하지 않았다.
2. 업무요청·처리 건·결재·일정·구성도·근무편성·정보통신 현황 등 최신 seed의 업무 자료는 별도 ID로 추가했다. FK, 알림 링크, 양식 파일 답, 추가 특성 파일, 구성도 장비 ID, 정보통신 현황의 JSON 참조도 함께 변환했다.
3. 이름이 충돌하는 새 구성도·근무표 등은 시연 표시를 붙였다. 새 문서함 자료는 부서별 `최신 main 시연 자료` 폴더에 넣었다.
4. 기존 조직명 17개와 변경된 기존 로그인명 6개를 우선 보존했다. 이름·계정 대응 내역은 `evidence/merge-plan.json`에 남겼다.
5. 기존 MAC 주소를 보존하고, 새 합성 장비 인터페이스에서 충돌한 74개 MAC만 `02-FE-*`의 locally administered address로 바꿨다. 실제 네트워크나 장비 설정을 변경하지 않았다.
6. 구성도 내용 해시는 Java/Kotlin 백엔드의 `DiagramScene`을 직접 호출해 계산했다. 전체 JSON 문자열의 단순 SHA-256과 구분한다.

병합은 트랜잭션 안에서 수행하며, 유일 제약조건과 모든 FK의 고아 참조 검사를 통과해야 commit한다. 원본 행의 전체 컬럼 지문이 최종 DB에도 그대로 존재하는지 검사했다. 기존 계정의 암호·활성 상태·개인 설정을 초기화하지 않았다.

## 최종 수량

| 항목 | 원본 | 최종 |
|---|---:|---:|
| 계정 | 62 | 62 |
| 조직 | 25 | 25 |
| 인원 | 62 | 62 |
| 장비 | 175 | 286 |
| 운영체계 | 38 | 38 |
| 업무요청 | 767 | 1,533 |
| 처리 건 | 810 | 1,616 |
| 결재 문서 | 1,459 | 2,942 |
| 개인 일정 | 93 | 188 |
| 구성도 | 4 | 9 |
| 첨부파일 | 50 | 107 |

Flyway 버전은 main의 V44다. V43은 설명 표시 설정, V44는 운영현황이다. 기존 Flyway 이력 행도 보존했다.

## 실행과 설치파일 제작

통합 실행본은 기본적으로 `%APPDATA%\OMNIS Desktop Final\service`를 사용한다. 첫 실행 시에만 최종 DB와 기존 QA 설정을 복원한다. 기존 `OMNIS Desktop Main` DB는 사용하거나 덮어쓰지 않는다. Final 프로필에 이미 DB가 있으면 다시 가져오지 않는다. 복원이 중단된 프로필은 자동 덮어쓰기 대신 오류로 중단하고 원본을 보존한다.

QA 마스터는 기존 `admin`과 기존 암호를 유지한다. 일반 시연 계정 `bn1-co1-admin`의 기존 시연 암호도 유지한다. QA 마스터의 계정 선택 목록은 26개이며 사용 중지 계정은 전환할 수 없다.

폴더형 실행본은 `resources`를 포함해 폴더 전체가 필요하다. 이 작업에서는 새 `Setup.exe`를 생성하지 않았다. 설치파일 제작 입력은 다음과 같다.

```powershell
.\desktop\Build-Setup.ps1 `
  -BundlePath .\output\OMNIS-Final-Database-Windows-x64 `
  -OutputDirectory .\output\OMNIS-Final-Database-Setup-20261108 `
  -ReleaseDate 2026-11-08
```

다른 실행본에 포함하려면 `Package-Windows.ps1`의 `-FinalDatabase`에 위 `bootstrap` 디렉터리를 지정한다. SQL과 QA 설정의 해시 검증을 통과해야 패키징한다.

## 검증과 재현 자료

`evidence/`에 원본 파일 해시, 원본/seed/병합 행 지문, ID 매핑, 병합 SQL, 일반 main 기동 로그, 첨부·JSON·구성도 검사 결과를 보관했다. 파일 본문 107개의 SHA-256·크기, 구성도 장비 참조와 실제 백엔드의 정규화 해시를 검사했다.

`verify-final-db.cjs`를 output 내부의 새 프로필에서 실행해 전체 테이블의 복원 수량·행 해시, 원본/추가 업무 조회, QA 26개 조회와 활성 계정 25개 전환, 문서함·구성도·정보통신 현황·근무·일정 조회를 통과했다. 일정 추가 후 두 번째 실행의 보존, 정상 종료, 원본 308개 파일의 해시 보존도 통과했다. 결과는 `evidence/installed-validation.json`과 두 실행의 화면에 기록했다. 이 검증에서 만든 일정은 테스트 프로필에만 있고 배포 SQL에는 없다. DB 복원·보존·중단 처리와 ID 배열 변환 등을 포함한 Node 검사 13개 및 최신 main WAR 빌드도 통과했다.

생성 절차는 아래 세 단계다. 원본 MySQL이 중지된 상태에서 새 output 경로를 사용한다. 같은 최종 결과 디렉터리에 병합을 반복 적용하면 중단한다.

```powershell
node desktop/prepare-final-db.cjs inspect output/final-db/<새-작업명>
node desktop/finalize-db.cjs output/final-db/<새-작업명> --apply
node desktop/export-final-db.cjs output/final-db/<새-작업명>
```

현재 Windows PC의 격리 프로필에서 검증한다. 다른 PC 설치, 새 Windows 환경, 실제 통신망·하드웨어, 실제 AI 모델 추론 검증을 뜻하지 않는다. 이 작업의 코드 변경은 로컬에 있으며 commit·push하지 않았다.
