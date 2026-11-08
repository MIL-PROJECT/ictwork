Unicode true
!include "MUI2.nsh"
!include "LogicLib.nsh"
!include "FileFunc.nsh"
!include "x64.nsh"
!include "WinVer.nsh"

Name "OMNIS Final"
OutFile "${OUTPUT}"
InstallDir "$LOCALAPPDATA\Programs\OMNIS Final"
InstallDirRegKey HKCU "Software\OMNIS Final" "InstallDir"
RequestExecutionLevel user
ManifestDPIAware true
SetCompressor zlib
SetCompressionLevel 7
ShowInstDetails show
ShowUninstDetails show
BrandingText "OMNIS Final | Windows x64 | ${RELEASE_DATE}"
VIProductVersion "${FILE_VERSION}"
VIAddVersionKey "ProductName" "OMNIS Final"
VIAddVersionKey "FileDescription" "OMNIS Final Offline Setup"
VIAddVersionKey "FileVersion" "${FILE_VERSION}"
VIAddVersionKey "ProductVersion" "${RELEASE_VERSION}"
VIAddVersionKey "LegalCopyright" "OMNIS Team"

Var TestMode
Var RuntimePresent

!define MUI_ICON "${ICON}"
!define MUI_UNICON "${ICON}"
!define MUI_ABORTWARNING
!define MUI_WELCOMEPAGE_TEXT "최신 OMNIS Final과 Java·MySQL 실행 환경을 설치합니다.$\r$\n$\r$\n설치 전에 실행 중인 OMNIS Final을 종료하세요.$\r$\n$\r$\n기본 업무 기능은 인터넷 없이 실행됩니다."
!define MUI_FINISHPAGE_RUN
!define MUI_FINISHPAGE_RUN_TEXT "OMNIS Final 실행"
!define MUI_FINISHPAGE_RUN_FUNCTION "RunOmnis"
!define MUI_FINISHPAGE_TEXT "설치가 완료되었습니다.$\r$\n$\r$\n첫 실행은 시연 DB 초기화와 서버 시작 때문에 시간이 걸릴 수 있습니다.$\r$\n$\r$\n시연 계정: bn1-co1-admin$\r$\n암호: Local-Demo-Init!26"
!insertmacro MUI_PAGE_WELCOME
!insertmacro MUI_PAGE_DIRECTORY
!insertmacro MUI_PAGE_INSTFILES
!insertmacro MUI_PAGE_FINISH
!define MUI_UNCONFIRMPAGE_TEXT_TOP "OMNIS Final 프로그램을 제거합니다. 사용자 데이터와 업무 파일은 보존됩니다. 먼저 실행 중인 OMNIS Final을 종료하세요."
!insertmacro MUI_UNPAGE_CONFIRM
!insertmacro MUI_UNPAGE_INSTFILES
!insertmacro MUI_LANGUAGE "Korean"

Function .onInit
    SetShellVarContext current
    SetRegView 64
    StrCpy $TestMode "0"
    ${GetParameters} $0
    ClearErrors
    ${GetOptions} $0 "/TESTMODE" $1
    ${IfNot} ${Errors}
        StrCpy $TestMode "1"
    ${EndIf}
    ${IfNot} ${RunningX64}
        MessageBox MB_OK|MB_ICONSTOP "Windows x64 PC에서 설치할 수 있습니다."
        SetErrorLevel 1
        Quit
    ${EndIf}
    ${IfNot} ${AtLeastWin10}
        MessageBox MB_OK|MB_ICONSTOP "Windows 10 또는 Windows 11이 필요합니다."
        SetErrorLevel 1
        Quit
    ${EndIf}
FunctionEnd

Function .onVerifyInstDir
    ; Do not overwrite a portable copy or an unrelated nonempty directory.
    IfFileExists "$INSTDIR\omnis-final-install.ini" valid
    FindFirst $0 $1 "$INSTDIR\*"
    loop:
        StrCmp $1 "" empty
        StrCmp $1 "." next
        StrCmp $1 ".." next
        FindClose $0
        Abort
    next:
        FindNext $0 $1
        Goto loop
    empty:
        FindClose $0
    valid:
FunctionEnd

Function CheckRuntime
    StrCpy $RuntimePresent "0"
    ClearErrors
    ReadRegDWORD $0 HKLM "SOFTWARE\Microsoft\VisualStudio\14.0\VC\Runtimes\x64" "Installed"
    ReadRegDWORD $1 HKLM "SOFTWARE\Microsoft\VisualStudio\14.0\VC\Runtimes\x64" "Major"
    ReadRegDWORD $2 HKLM "SOFTWARE\Microsoft\VisualStudio\14.0\VC\Runtimes\x64" "Minor"
    ReadRegDWORD $3 HKLM "SOFTWARE\Microsoft\VisualStudio\14.0\VC\Runtimes\x64" "Bld"
    ${IfNot} ${Errors}
    ${AndIf} $0 == 1
    ${AndIf} $1 >= 14
        ${If} $1 > 14
        ${OrIf} $2 > 44
            StrCpy $RuntimePresent "1"
        ${ElseIf} $2 == 44
        ${AndIf} $3 >= 35208
            StrCpy $RuntimePresent "1"
        ${EndIf}
    ${EndIf}
FunctionEnd

Section "OMNIS Final" SEC_MAIN
    SetShellVarContext current
    SetRegView 64
    Call CheckRuntime
    ${If} $RuntimePresent != "1"
        InitPluginsDir
        SetOutPath "$PLUGINSDIR"
        File /oname=vc_redist.x64.exe "${VCREDIST}"
        DetailPrint "Microsoft Visual C++ 런타임 설치 중 (관리자 승인 필요)..."
        ClearErrors
        ExecShellWait "runas" "$PLUGINSDIR\vc_redist.x64.exe" "/install /passive /norestart"
        ${If} ${Errors}
            MessageBox MB_OK|MB_ICONSTOP "Visual C++ 런타임 설치가 완료되지 않았습니다. 관리자 승인을 확인한 뒤 다시 실행하세요."
            SetErrorLevel 2
            Abort
        ${EndIf}
        Call CheckRuntime
        ${If} $RuntimePresent != "1"
            MessageBox MB_OK|MB_ICONSTOP "필수 런타임을 확인하지 못했습니다. PC를 재시작하거나 관리자에게 문의한 뒤 다시 설치하세요."
            SetErrorLevel 2
            Abort
        ${EndIf}
    ${EndIf}
    SetOutPath "$INSTDIR"
    ; Mark a partial installation so it can be retried at the same location.
    WriteINIStr "$INSTDIR\omnis-final-install.ini" "Installer" "Product" "OMNIS Final ${RELEASE_VERSION}"
    WriteINIStr "$INSTDIR\omnis-final-install.ini" "Installer" "TestMode" "$TestMode"
    WriteUninstaller "$INSTDIR\Uninstall.exe"
    SetOverwrite on
    ClearErrors
    File /r /x debug.log /x sidebar.png "${BUNDLE}\*.*"
    File /oname=INSTALL-KO.txt "${GUIDE}"
    ${If} ${Errors}
        MessageBox MB_OK|MB_ICONSTOP "파일 설치에 실패했습니다. OMNIS를 종료하고 디스크 여유 공간과 설치 위치를 확인하세요."
        SetErrorLevel 3
        Abort
    ${EndIf}
    ${If} $TestMode != "1"
        CreateDirectory "$SMPROGRAMS\OMNIS Final"
        CreateShortcut "$SMPROGRAMS\OMNIS Final\OMNIS Final.lnk" "$INSTDIR\OMNIS.exe" "" "$INSTDIR\OMNIS.exe"
        CreateShortcut "$SMPROGRAMS\OMNIS Final\설치 및 사용 안내.lnk" "$INSTDIR\INSTALL-KO.txt"
        CreateShortcut "$SMPROGRAMS\OMNIS Final\OMNIS Final 제거.lnk" "$INSTDIR\Uninstall.exe"
        CreateShortcut "$DESKTOP\OMNIS Final.lnk" "$INSTDIR\OMNIS.exe" "" "$INSTDIR\OMNIS.exe"
        WriteRegStr HKCU "Software\OMNIS Final" "InstallDir" "$INSTDIR"
        WriteRegStr HKCU "Software\Microsoft\Windows\CurrentVersion\Uninstall\OMNIS Final" "DisplayName" "OMNIS Final"
        WriteRegStr HKCU "Software\Microsoft\Windows\CurrentVersion\Uninstall\OMNIS Final" "DisplayVersion" "${RELEASE_VERSION}"
        WriteRegStr HKCU "Software\Microsoft\Windows\CurrentVersion\Uninstall\OMNIS Final" "Publisher" "OMNIS Team"
        WriteRegStr HKCU "Software\Microsoft\Windows\CurrentVersion\Uninstall\OMNIS Final" "InstallLocation" "$INSTDIR"
        WriteRegStr HKCU "Software\Microsoft\Windows\CurrentVersion\Uninstall\OMNIS Final" "DisplayIcon" "$INSTDIR\OMNIS.exe"
        WriteRegStr HKCU "Software\Microsoft\Windows\CurrentVersion\Uninstall\OMNIS Final" "UninstallString" '$\"$INSTDIR\Uninstall.exe$\"'
        WriteRegStr HKCU "Software\Microsoft\Windows\CurrentVersion\Uninstall\OMNIS Final" "QuietUninstallString" '$\"$INSTDIR\Uninstall.exe$\" /S'
        WriteRegDWORD HKCU "Software\Microsoft\Windows\CurrentVersion\Uninstall\OMNIS Final" "NoModify" 1
        WriteRegDWORD HKCU "Software\Microsoft\Windows\CurrentVersion\Uninstall\OMNIS Final" "NoRepair" 1
    ${EndIf}
SectionEnd

Function RunOmnis
    SetOutPath "$INSTDIR"
    Exec '"$INSTDIR\OMNIS.exe"'
FunctionEnd

Function un.onInit
    SetShellVarContext current
    SetRegView 64
    IfFileExists "$INSTDIR\omnis-final-install.ini" +3
        SetErrorLevel 4
        Quit
    ReadINIStr $TestMode "$INSTDIR\omnis-final-install.ini" "Installer" "TestMode"
FunctionEnd

Section "Uninstall"
    ; Delete only files shipped by this installer. Never recursively remove $INSTDIR or userData.
    !include "${REMOVEFILES}"
    Delete "$INSTDIR\debug.log"
    Delete "$INSTDIR\INSTALL-KO.txt"
    Delete "$INSTDIR\omnis-final-install.ini"
    Delete "$INSTDIR\Uninstall.exe"
    ${If} $TestMode != "1"
        Delete "$DESKTOP\OMNIS Final.lnk"
        Delete "$SMPROGRAMS\OMNIS Final\OMNIS Final.lnk"
        Delete "$SMPROGRAMS\OMNIS Final\설치 및 사용 안내.lnk"
        Delete "$SMPROGRAMS\OMNIS Final\OMNIS Final 제거.lnk"
        RMDir "$SMPROGRAMS\OMNIS Final"
        DeleteRegKey HKCU "Software\OMNIS Final"
        DeleteRegKey HKCU "Software\Microsoft\Windows\CurrentVersion\Uninstall\OMNIS Final"
    ${EndIf}
    SetOutPath "$TEMP"
    RMDir "$INSTDIR"
SectionEnd
