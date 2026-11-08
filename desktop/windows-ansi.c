/* Convert our UTF-8 MySQL option file to the host's ANSI code page.
 * Static CRT: no separately installed .NET or Visual C++ runtime is needed.
 * Input/output paths use UTF-16 Windows APIs, including Korean and spaces.
 */
#include <windows.h>
#include <stdlib.h>
#include <stdio.h>

int wmain(int argc, wchar_t **argv) {
    HANDLE input, output;
    LARGE_INTEGER size;
    DWORD readCount, writeCount;
    char *utf8, *ansi;
    wchar_t *wide;
    int wideLength, ansiLength;
    BOOL replacement = FALSE;
    UINT codePage = GetACP();
    if (argc != 3) return 2;
    input = CreateFileW(argv[1], GENERIC_READ, FILE_SHARE_READ, NULL, OPEN_EXISTING, FILE_ATTRIBUTE_NORMAL, NULL);
    if (input == INVALID_HANDLE_VALUE) return 3;
    if (!GetFileSizeEx(input, &size) || size.QuadPart <= 0 || size.QuadPart > 1048576) { CloseHandle(input); return 3; }
    utf8 = (char *)malloc((size_t)size.QuadPart);
    if (!utf8) { CloseHandle(input); return 3; }
    if (!ReadFile(input, utf8, (DWORD)size.QuadPart, &readCount, NULL) || readCount != (DWORD)size.QuadPart) {
        CloseHandle(input); free(utf8); return 3;
    }
    CloseHandle(input);
    wideLength = MultiByteToWideChar(CP_UTF8, MB_ERR_INVALID_CHARS, utf8, (int)readCount, NULL, 0);
    if (!wideLength) { free(utf8); return 4; }
    wide = (wchar_t *)malloc((size_t)wideLength * sizeof(wchar_t));
    if (!wide) { free(utf8); return 3; }
    MultiByteToWideChar(CP_UTF8, MB_ERR_INVALID_CHARS, utf8, (int)readCount, wide, wideLength);
    free(utf8);
    ansiLength = WideCharToMultiByte(codePage, codePage == CP_UTF8 ? 0 : WC_NO_BEST_FIT_CHARS, wide, wideLength, NULL, 0, NULL, codePage == CP_UTF8 ? NULL : &replacement);
    if (!ansiLength || replacement) { free(wide); return 4; }
    ansi = (char *)malloc((size_t)ansiLength);
    if (!ansi) { free(wide); return 3; }
    if (!WideCharToMultiByte(codePage, codePage == CP_UTF8 ? 0 : WC_NO_BEST_FIT_CHARS, wide, wideLength, ansi, ansiLength, NULL, codePage == CP_UTF8 ? NULL : &replacement) || replacement) {
        free(wide); free(ansi); return 4;
    }
    free(wide);
    output = CreateFileW(argv[2], GENERIC_WRITE, 0, NULL, CREATE_ALWAYS, FILE_ATTRIBUTE_NORMAL, NULL);
    if (output == INVALID_HANDLE_VALUE) { free(ansi); return 5; }
    if (!WriteFile(output, ansi, (DWORD)ansiLength, &writeCount, NULL) || writeCount != (DWORD)ansiLength) {
        CloseHandle(output); free(ansi); return 5;
    }
    CloseHandle(output);
    free(ansi);
    printf("Windows ANSI code page: %u\n", codePage);
    return 0;
}
