"""Regression checks for the packaged Windows ANSI path converter."""
import json
import pathlib
import re
import subprocess

root = pathlib.Path(__file__).resolve().parent.parent
work = root / 'output' / 'installer-tools' / 'path-helper-checks' / '한글 경로 변환 시험'
work.mkdir(parents=True, exist_ok=True)
binary = root / 'output' / 'installer-tools' / 'native' / 'windows-ansi.exe'
source, output = work / '입력.utf8', work / '설정.ini'
text = '[mysqld]\r\ndatadir="C:/한글 사용자/OMNIS Desktop/service/mysql-data"\r\n'
source.write_bytes(text.encode('utf-8'))
run = subprocess.run([str(binary), str(source), str(output)], capture_output=True)
assert run.returncode == 0, run
codepage = int(re.search(rb'code page: (\d+)', run.stdout).group(1))
assert output.read_bytes().decode('cp' + str(codepage)) == text
valid = output.read_bytes()
source.write_bytes(b'\xff\xfeINVALID-UTF8')
invalid = subprocess.run([str(binary), str(source), str(output)], capture_output=True)
assert invalid.returncode == 4
assert output.read_bytes() == valid, 'An error must preserve the last valid configuration'
result = {'codepage': codepage, 'unicodeInputOutputPaths': True, 'exactConfigRoundtrip': True,
          'rejectInvalidUtf8': True, 'preservePreviousConfigOnError': True}
source.write_text('datadir="C:/\U0001f680"\n', encoding='utf-8')
try:
    source.read_text(encoding='utf-8').encode('cp' + str(codepage))
except UnicodeEncodeError:
    unsupported = subprocess.run([str(binary), str(source), str(output)], capture_output=True)
    assert unsupported.returncode == 4
    assert output.read_bytes() == valid
    result['rejectUnrepresentablePathWithoutLoss'] = True
(work.parent / 'result.json').write_text(json.dumps(result, indent=2), encoding='utf-8')
print(json.dumps(result))
