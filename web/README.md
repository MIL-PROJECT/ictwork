# OMNIS 웹 실행·소스 이관 묶음

Setup 설치와 Electron 없이 **Java로 WAR를 실행하고 브라우저로 접속**하는 배포본이다. WAR 안에 production 프런트엔드가 포함되어 있어 화면을 띄우는 데 Vite나 별도 프런트 서버는 필요 없다.

## 폴더

| 경로 | 내용 |
|---|---|
| `source/src/backend` | Spring Boot/Kotlin 백엔드 원본, Gradle Wrapper 및 migration |
| `source/src/frontend` | React/TypeScript 프런트 원본, pnpm lockfile |
| `source/desktop` | 기존 Windows 설치본 제작 소스 |
| `OMNIS-source.zip` | Git 커밋 기준 전체 소스 압축본 |
| `app/OMNIS.war` | 현재 Setup과 동일한 최신 production WAR |
| `database/final-db.sql` | Setup에 포함한 기존 데이터의 논리 백업 |
| `database/manifest.json` | SQL 해시·기존 테이블별 건수와 지문 |
| `database/create-database.example.sql` | 대상 MySQL DB·앱 계정 생성 예시 |
| `config/application.properties.example` | 대상 DB 접속/서버 설정 예시 |
| `config/qa-config.yml` | 기존 QA 계정 설정(암호 해시, 기존 암호 보존) |
| `Run-Web.cmd`, `Run-Web.ps1` | Windows WAR 실행 |
| `Run-Web.sh` | Linux/macOS WAR 실행 예시 |
| `Restore-Database.ps1` | Windows에서 빈 DB에만 복원하고 90개 테이블 건수 확인 |
| `package-manifest.json`, `SHA256SUMS.txt` | 소스 커밋과 전달 파일 무결성 |

**이 폴더 전체를 옮기면 된다.** 소스만 필요한 경우 `source` 또는 `OMNIS-source.zip`을 가져가면 된다. `.git`, node_modules, 개인 환경 설정, DB 접속 암호는 소스 압축본에 넣지 않았다. 데이터 SQL과 QA 해시는 기존 자료이므로 공개 소스 저장소에 올리지 않는다.

## 대상 환경 준비

- Java 17, MySQL 8.0 서버와 mysql 클라이언트가 필요하다. 이 웹 묶음에는 실행 환경 설치 파일을 중복 포함하지 않았다.
- 이미 빌드된 WAR를 실행할 때는 Node.js/pnpm/Gradle이 필요 없다. 재빌드에는 Node.js·pnpm·JDK 17과 Gradle/Maven/npm 의존성 다운로드 또는 캐시가 필요하다.
- 초기 DB 복원과 WAR 실행은 필요한 실행 환경이 준비되어 있으면 인터넷 없이 가능하다. 기본 AI는 LOCAL 규칙 분석이며 외부 AI와 Ollama 모델은 별도로 구성해야 한다.
- 기존 Setup/원본 프로필의 MySQL에 바로 연결하지 말고 **새 빈 DB**로 시작한다. 이 배포본은 Setup 제작 시점의 데이터 복사본이며 이후 수정 데이터의 실시간 동기화가 아니다.

## Windows 실행 순서

1. 대상 PC에 Java 17과 MySQL 8.0을 준비하고 MySQL을 실행한다.
2. MySQL 관리자로 접속한다. `mysql -u root -p` (클라이언트가 PATH에 없으면 mysql.exe 전체 경로 사용).
3. `database/create-database.example.sql`을 참고해 **사용하지 않는 새 DB와 앱 계정**을 만든다. `CHANGE_ME_DB_PASSWORD`를 직접 정한 DB 암호로 바꾸고 실행한다. 아래 기본 예시의 DB 이름/계정은 모두 `omnis`다. 기존 DB와 이름이 겹치면 새 이름을 정하고 이후 설정도 맞춘다.
4. 이 배포 폴더에서 PowerShell을 열고 복원한다. 대상 DB가 비어 있지 않으면 중단하며 기존 자료를 덮어쓰지 않는다.

   ```powershell
   powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\Restore-Database.ps1 -Database omnis -User omnis
   # mysql.exe가 PATH에 없는 경우 -MySql "C:\Program Files\MySQL\MySQL Server 8.0\bin\mysql.exe" 추가
   ```

   물어보는 암호는 MySQL 앱 계정 암호다. OMNIS 로그인 암호와 다르다. 복원이 중단되면 해당 DB를 보존하고 원인을 확인한 뒤 다른 새 빈 DB에 복원한다.

5. `config/application.properties.example`을 같은 폴더의 **`application.properties`**로 복사한다. MySQL 주소·포트·DB 이름·사용자·암호를 맞추고 `ictwork.initial-password`를 새 계정 생성/초기화용 정책에 맞는 암호로 지정한다. 기존 로그인 암호를 바꾸는 설정은 아니다.
6. **`Run-Web.cmd`를 실행**한다. JAVA_HOME 또는 PATH에서 Java를 찾는다. Java를 별도 경로로 지정하려면 아래처럼 실행한다.

   ```powershell
   powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\Run-Web.ps1 -JavaHome "C:\Program Files\Java\jdk-17" -Port 8080
   ```

7. 서버 로그에 `Started IctworkApplicationKt`가 나오면 브라우저에서 **http://127.0.0.1:8080/signin**을 연다. 기본 8080이 사용 중이면 다른 포트를 지정한다.
8. 기존 계정과 암호로 로그인한다. 포함 데이터의 시연 계정은 `bn1-co1-admin` / `Local-Demo-Init!26`이다. sysadmin과 QA 마스터는 기존 암호를 유지한다.
9. 종료는 실행 터미널에서 **Ctrl+C**다. MySQL은 별도 서버이므로 이 스크립트가 종료하지 않는다. 데이터는 대상 MySQL DB에 저장된다. 브라우저를 닫아도 서버는 계속 실행된다.

기본 바인딩은 `127.0.0.1`이므로 같은 PC에서 접속한다. 여러 PC에서 접근하는 LAN 서버로 사용할 때는 `application.properties`의 `server.address=0.0.0.0`과 서버 방화벽/HTTPS를 대상 환경에 맞춰 설정하고 `http://서버IP:포트`로 접속한다. 이 작업에서 외부 공개나 방화벽 변경은 수행하지 않았다.

## WAR 직접 실행

위 DB 복원과 설정을 마친 뒤 **이 배포 폴더를 현재 디렉터리로** 실행한다.

```text
java -Duser.timezone=Asia/Seoul -jar app/OMNIS.war --spring.profiles.active=demo --spring.config.additional-location=file:./config/qa-config.yml
```

8080으로 열리며 별도 Tomcat 설치는 필요 없다. `Run-Web.ps1`은 같은 명령을 실행한다. 이 DB는 demo repeatable migration 이력을 포함한 Setup 데이터이므로 demo 프로필을 유지한다.

## Linux/macOS로 옮길 때

WAR·소스·SQL은 그대로 사용할 수 있다. Java 17/MySQL 8.0 준비 후 새 빈 DB에 UTF-8로 복원한다. Windows용 Java/MySQL 바이너리를 옮겨 쓰지 않는다.

```sh
mysql -h 127.0.0.1 -u omnis -p --default-character-set=utf8mb4 omnis < database/final-db.sql
cp config/application.properties.example config/application.properties
# config/application.properties의 대상 DB 암호/설정을 편집
sh Run-Web.sh
```

복원 전 반드시 빈 DB인지 확인한다. Windows 복원 스크립트는 이를 자동 검사하며, 위 수동 명령은 같은 보호 검사를 대신 수행하지 않는다. Linux/macOS에서의 실제 실행은 이번 Windows 검증 범위에 포함하지 않는다.

## 소스 재빌드

```powershell
cd source\src\backend
.\gradlew.bat bootWar
```

프런트 production build가 함께 실행되고 `build/libs/ictwork-0.0.1.war`가 만들어진다. Linux/macOS에서는 `sh gradlew bootWar`를 사용한다. 재빌드 WAR를 `app/OMNIS.war`에 반영하기 전 실행 중인 Java를 정상 종료한다.

## 데이터와 로그

- 첨부파일·문서 본문은 DB에 포함된다. 복원 검증에서 원래 행과 첨부 데이터의 SHA-256을 대조했다.
- SQL은 계정/업무/일정/구성도/문서 등을 포함한 Setup과 동일한 스냅샷이다. 실제 DB를 옮길 때는 대상 DB를 먼저 백업하고 추가 자료가 생긴 시점의 새 mysqldump를 사용한다.
- 외부 AI 키를 별도로 등록했다면 암호화 키 파일 `config/ai-secrets.key`도 보존해야 한다. 이번 원본 데이터에는 그 키 파일이 없다. 대상 환경에서 새로 설정한 키는 별도로 보관한다.
- 실행 로그는 `logs/omnis.log`다. 로컬 설정과 키 파일은 소스와 분리한다.
- SQLite 기반 구형 OMNIS DB를 이 MySQL SQL로 직접 대체하거나 혼합하지 않는다.

구현/배포 상세 검증: 소스의 `docs/OMNIS_WEB_TRANSFER_20261109.md` 참조.
