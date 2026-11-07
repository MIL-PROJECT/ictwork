# OMNIS Desktop

Windows x64 시연 실행본. 배포 폴더 전체를 유지한 상태에서 **OMNIS.exe**를 실행한다. 첫 실행은 DB 초기화와 마이그레이션 때문에 시간이 걸릴 수 있다. 앱 창에서 시연 계정 **bn1-co1-admin**과 암호 **Local-Demo-Init!26**으로 로그인한다.

Electron이 포함된 Java 17로 Spring Boot WAR를 실행하고 MySQL 8.0의 시연 DB를 관리한다. 두 서비스는 매 실행에 정한 포트를 사용하며 127.0.0.1에서만 연결된다. 일반 창 닫기에서 Java 프로세스를 종료한 뒤 MySQL에 정상 종료를 요청한다.

시연 데이터와 로그는 %APPDATA%\OMNIS Desktop\service 에 저장된다. 기존 웹 시연 DB와 분리된다. 폴더를 옮겨도 같은 Windows 사용자 계정에서 다시 열면 데이터가 유지된다. 합성 데이터 시연용 계정이 포함되어 있다.

## 재빌드

Java 17과 pnpm을 사용해 src/backend에서 gradlew.bat bootWar를 실행한다. 그 WAR와 정식 Windows x64 Electron 배포 폴더, Java 17 런타임, MySQL 8.0 ZIP 배포본 압축 해제 폴더를 Package-Windows.ps1의 인수로 전달한다. Destination은 새 디렉터리를 지정한다.

Windows 실행 파일에 OMNIS 아이콘을 넣으려면 ResourceEditor 인수에 Windows용 rcedit 실행 파일을 지정한다.
