# OMNIS Desktop

Windows x64 시연 실행본. 배포 폴더 전체를 유지한 상태에서 **OMNIS.exe**를 실행한다. 첫 실행은 DB 초기화와 마이그레이션 때문에 시간이 걸릴 수 있다. 앱 창에서 시연 계정 **bn1-co1-admin**과 암호 **Local-Demo-Init!26**으로 로그인한다.

Electron이 포함된 Java 17로 Spring Boot WAR를 실행하고 MySQL 8.0의 시연 DB를 관리한다. 두 서비스는 매 실행에 정한 포트를 사용하며 127.0.0.1에서만 연결된다. 일반 창 닫기에서 Java 프로세스를 종료한 뒤 MySQL에 정상 종료를 요청한다.

시연 데이터와 로그는 %APPDATA%\OMNIS Desktop Main\service 에 저장된다. 기존 웹 시연 DB와 분리된다. 폴더를 옮겨도 같은 Windows 사용자 계정에서 다시 열면 데이터가 유지된다. 합성 데이터 시연용 계정이 포함되어 있다.

## 재빌드

Java 17과 pnpm을 사용해 src/backend에서 gradlew.bat bootWar를 실행한다. 그 WAR와 정식 Windows x64 Electron 배포 폴더, Java 17 런타임, MySQL 8.0 ZIP 배포본 압축 해제 폴더를 Package-Windows.ps1의 인수로 전달한다. Destination은 새 디렉터리를 지정한다.

Windows 실행 파일에 OMNIS 아이콘을 넣으려면 ResourceEditor 인수에 Windows용 rcedit 실행 파일을 지정한다.

## 단일 설치 파일 (2026-11-07)

`Build-Setup.ps1`은 인수로 지정한 폴더형 실행본을 오프라인 NSIS 설치 파일 `Setup.exe` 하나로 묶는다. Electron·Java·MySQL·WAR와 Microsoft 서명된 Visual C++ x64 재배포 패키지를 포함한다. 기본 설치 위치는 현재 사용자의 `%LOCALAPPDATA%\Programs\OMNIS Final`이다. 필수 C++ 런타임이 없는 PC에서만 해당 런타임 설치를 위한 관리자 승인이 필요하다.

Windows MySQL은 현재 ANSI 코드 페이지에 호환되는 경로를 사용해야 한다. 공백이 있는 실행 파일의 `argv[0]`을 이름만 전달하고, 한글 설치·사용자 경로는 `windows-ansi.exe`로 Windows 기본 인코딩의 MySQL 옵션 파일에 기록한다. 이 작은 변환기는 `windows-ansi.c`에서 MSVC `/MT`로 빌드하며 `KERNEL32.dll`만 의존한다. 변환할 수 없는 문자를 조용히 바꾸지 않고 오류로 처리한다. `Build-PathHelper.ps1`은 패키징 전 변환기를 빌드하고, `Package-Windows.ps1`이 실행본에 포함한다.

변환기 재빌드에는 MSVC Build Tools와 Windows 10 SDK가 필요하다. 변환기 회귀 검사는 `python desktop/test-path-helper.py`로 한글 입출력 경로, 설정 내용의 정확성, 잘못된 UTF-8·표현할 수 없는 경로의 거부와 이전 설정 보존을 확인한다.

빌드 도구는 공식 NSIS 3.13 ZIP 배포본을 `output/installer-tools/nsis-3.13`에 압축 해제하고, Microsoft 공식 `vc_redist.x64.exe`를 `output/installer-tools`에 준비한다. 설치 프로그램은 네트워크 다운로드 없이 포함된 파일만 사용한다.

```powershell
./desktop/Build-Setup.ps1 -BundlePath <이번-빌드-실행폴더> -OutputDirectory <새-설치본-폴더>
./desktop/Verify-Setup.ps1 -Distribution <새-설치본-폴더>
```

기본 결과는 `output/OMNIS-Final-Setup-20261107/DVD/Setup.exe`다. DVD에 담을 파일은 `DVD` 폴더의 내용이며, `evidence`에는 원본/설치 파일 해시, 빌드·설치·실행 시험 결과를 보관한다. 빌드는 700,000,000바이트 및 4,700,000,000바이트 기준으로 전달 폴더의 실제 크기를 판정한다. `debug.log`와 화면 확인용 `sidebar.png`를 제외하고 기존 배포본의 파일을 유지한다.

검증은 한글·공백이 있는 작업 폴더에 `/TESTMODE`로 설치한다. 이 모드는 실제 설치 파일의 압축 해제와 제거 경로를 사용하면서 사용자 바탕화면·시작 메뉴·프로그램 등록을 생략한다. 실행 시험은 완전히 새 사용자 프로필, 포함된 Java/MySQL, 로컬 주소만 허용하는 브라우저 요청으로 로그인·업무 화면·개인 일정 저장·재시작 후 유지·정상 종료를 확인한다. 제거 시험은 설치된 파일만 제거하고 추가한 파일과 사용자 프로필이 남는지 확인한다. 물리적인 망 분리나 다른 PC/새 Windows의 검증과는 구분한다.

설치본에는 원본 PC의 입력 자료나 사용자 DB를 넣지 않는다. 첫 실행에 시연 DB를 만들며, 실제 자료와 로그는 `%APPDATA%\OMNIS Desktop Main\service`에 유지한다. 기존 SQLite OMNIS 1.1.12에서 자동 이관하지 않는다. demo 배포의 AI는 꺼져 있고 Ollama·모델은 포함하지 않는다. 설치 프로그램과 제거 프로그램은 이 사용자 데이터 폴더를 삭제하지 않는다.

최신 main(V41) 기반 실행본은 OMNIS Desktop Main 프로필을 사용한다. 기존 feature/fault-report 시연 DB(V35/V36의 의미가 다름)는 기존 OMNIS Desktop 폴더에 보존되며 자동 연결하지 않는다.

설치·실행 검증은 pnpm --dir desktop install --frozen-lockfile로 준비한 Playwright를 사용한다. Verify-Setup.ps1은 설치·재설치·제거와 격리된 사용자 데이터 보존을 확인한다.
