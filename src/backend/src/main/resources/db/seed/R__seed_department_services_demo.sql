-- 부서 업무 안내·업무 유형 (가상, local·demo 프로필만). 관리 ▸ 부서 업무 안내에서 부서 관리자가 고친다
-- 담당 부서 추천은 안내의 담당 체계·처리 업무·대표 증상 글자로 점수를 매기고, 담당하지 않는 업무에 걸리면 뺀다(recommendDepartments.ts).
-- 반마다 맡는 분야가 겹치지 않게 정했다:
--   1대대 1중대 1반 유선 / 2반 무선 / 2중대 1반 전장망 / 2반 화상회의 지원 / 3중대 1반 전산 / 2반 사이버방호
--   2대대 1중대 1반 위성통신 / 2반 전송망 / 2중대 1반 네트워크 장비 / 2반 영상감시 / 3중대 1반 방송·음향 / 2반 전원
-- 없을 때만 넣는다: 이미 있는 안내(관리자가 고친 내용)는 덮어쓰지 않는다(ON DUPLICATE KEY UPDATE로 아무것도 바꾸지 않음)

INSERT INTO department_service_profile (department_id, systems, responsibilities, symptoms, exclusions)
SELECT v.department_id, v.systems, v.responsibilities, v.symptoms, v.exclusions
FROM (
    SELECT 5 AS department_id,
        '일반전화, 군 전화망, 구내 교환기, 랜(LAN) 케이블, 광케이블, 통신선로' AS systems,
        '전화기 설치·이전, 전화 회선 개통, 랜선 포설·단자 설치, 광케이블 접속, 통신선로 점검' AS responsibilities,
        '전화 불통, 전화 잡음, 발신 안 됨, 착신 안 됨, 회선 끊김, 랜선 단선, 인터넷 선 연결 안 됨, 단자 파손' AS symptoms,
        '무전기, 화상회의, 전장망' AS exclusions
    UNION ALL SELECT 6,
        '무전기, 휴대용 무전기, 차량용 무전기, 무선 중계기, 무선망 안테나',
        '무전기 정비·배터리 교체, 무선 주파수 설정, 중계기 점검, 안테나 설치·조정',
        '무전기 전원 안 켜짐, 무전기 송신 안 됨, 수신 불량, 무전 잡음, 배터리 방전, 안테나 파손, 무선 교신 불가',
        '일반전화, 화상회의, 전장망'
    UNION ALL SELECT 7,
        '전장망, 전장망 단말, 지휘통제체계 단말, 전장망 스위치',
        '전장망 단말 설치·이전, 전장망 접속 장애 처리, 지휘통제체계 계정 접속 지원, 전장망 장비 점검',
        '전장망 접속 안 됨, 전장망 느림, 지휘통제체계 로그인 불가, 전장망 단말 부팅 안 됨, 전장망 연결 끊김',
        '인터넷, 화상회의, 무전기'
    UNION ALL SELECT 8,
        '화상회의 체계, 화상회의 단말, 회의실 카메라, 회의실 마이크, 회의실 스피커, 영상 표출 장비',
        '화상회의 연결 지원, 회의 전 장비 점검, 회의실 음향·영상 설정, 화상회의 운영 지원',
        '화상회의 연결 안 됨, 화면 안 나옴, 소리 안 들림, 마이크 안 됨, 카메라 안 나옴, 하울링, 회의 중 끊김',
        '무전기, 전장망, 일반전화 설치'
    UNION ALL SELECT 9,
        '업무용 PC, 인트라넷, 프린터, 복합기, 모니터, 업무용 소프트웨어',
        'PC 설치·이전, 인트라넷 접속 지원, 프린터 연결·드라이버 설치, 업무용 소프트웨어 설치',
        'PC 부팅 안 됨, PC 느림, 인트라넷 접속 안 됨, 프린터 인쇄 안 됨, 용지 걸림, 모니터 안 나옴, 프로그램 오류',
        '전장망, 화상회의, 무전기'
    UNION ALL SELECT 10,
        '백신, 보안 USB, 망 분리, 사이버방호체계, 보안 프로그램',
        '백신·보안 프로그램 점검, 보안 USB 등록·해제, 악성코드 의심 PC 조치, 보안 점검 지원',
        '악성코드 의심, 백신 경고, 보안 USB 인식 안 됨, 보안 프로그램 오류, 비인가 접속 의심, 해킹 의심',
        '프린터, 화상회의, 무전기'
    UNION ALL SELECT 15,
        '위성통신, 위성 단말, 위성 안테나',
        '위성 단말 설치·운용, 위성 안테나 정렬, 위성 통신 시험',
        '위성통신 연결 안 됨, 위성 신호 약함, 위성 단말 오류, 안테나 정렬 불량',
        '일반전화, 화상회의'
    UNION ALL SELECT 16,
        '전송망, 광전송 장비, 마이크로웨이브, 전송 장비',
        '전송 장비 점검, 전송 회선 구성, 마이크로웨이브 정렬',
        '전송 회선 장애, 전송 장비 경보, 마이크로웨이브 신호 약함, 구간 통신 두절',
        '프린터, 화상회의'
    UNION ALL SELECT 17,
        '스위치, 라우터, 방화벽, 서버, 네트워크 장비',
        '네트워크 장비 설치·설정, 서버 점검, 포트 설정, 장비 교체',
        '네트워크 장비 고장, 스위치 포트 불량, 서버 접속 안 됨, 서버 다운, 방화벽 차단',
        '무전기, 화상회의'
    UNION ALL SELECT 18,
        'CCTV, 영상감시 체계, 녹화 장비, 감시 카메라',
        'CCTV 설치·이전, 녹화 장비 점검, 영상 저장 확인',
        'CCTV 화면 안 나옴, 녹화 안 됨, 감시 카메라 흐림, 영상 끊김',
        '화상회의, 무전기'
    UNION ALL SELECT 19,
        '구내방송, 방송 장비, 앰프, 강당 음향 장비',
        '구내방송 장비 점검, 행사 음향 지원, 방송 스피커 설치',
        '구내방송 안 나옴, 방송 잡음, 앰프 고장, 행사 마이크 안 됨',
        '화상회의, 무전기'
    UNION ALL SELECT 20,
        '발전기, UPS, 통신실 전원, 정류기, 축전지',
        '발전기 정비·부하 시험, UPS 점검·축전지 교체, 통신실 전원 점검',
        '정전, 발전기 시동 안 됨, UPS 경보, 축전지 방전, 통신실 전원 차단',
        '화상회의, 무전기'
) v
JOIN department d ON d.id = v.department_id
ON DUPLICATE KEY UPDATE department_id = department_service_profile.department_id;

-- 분야에 맞는 업무 유형(1중대 1반은 처음 시드의 "전화"를 그대로 쓴다). (부서, 이름)이 없을 때만 넣는다(관리자가 이름을 바꾸거나 사용 중지한 유형은 그대로 둔다)
-- 화상회의 지원·행사 음향 지원은 시간이 정해진 지원 업무라 정확한 일정을 받는다(EXACT_REQUIRED)
INSERT INTO work_type (department_id, name, sort_order, schedule_mode)
SELECT v.department_id, v.name, v.sort_order, v.schedule_mode
FROM (
    SELECT 5 AS department_id, '랜선·회선 공사' AS name, 11 AS sort_order, 'OPTIONAL' AS schedule_mode
    UNION ALL SELECT 6, '무전기 고장', 11, 'OPTIONAL'
    UNION ALL SELECT 6, '무선망 점검', 12, 'OPTIONAL'
    UNION ALL SELECT 7, '전장망 장애', 11, 'OPTIONAL'
    UNION ALL SELECT 7, '전장망 단말 설치', 12, 'OPTIONAL'
    UNION ALL SELECT 8, '화상회의 지원', 11, 'EXACT_REQUIRED'
    UNION ALL SELECT 8, '회의실 장비 고장', 12, 'OPTIONAL'
    UNION ALL SELECT 9, 'PC·프린터 고장', 11, 'OPTIONAL'
    UNION ALL SELECT 9, '인트라넷 접속 장애', 12, 'OPTIONAL'
    UNION ALL SELECT 10, '보안 점검', 11, 'OPTIONAL'
    UNION ALL SELECT 10, '보안 USB 등록', 12, 'OPTIONAL'
    UNION ALL SELECT 15, '위성통신 장애', 11, 'OPTIONAL'
    UNION ALL SELECT 16, '전송 회선 장애', 11, 'OPTIONAL'
    UNION ALL SELECT 17, '네트워크 장비 고장', 11, 'OPTIONAL'
    UNION ALL SELECT 18, 'CCTV 고장', 11, 'OPTIONAL'
    UNION ALL SELECT 19, '행사 음향 지원', 11, 'EXACT_REQUIRED'
    UNION ALL SELECT 19, '방송 장비 고장', 12, 'OPTIONAL'
    UNION ALL SELECT 20, '발전기·UPS 점검', 11, 'OPTIONAL'
) v
JOIN department d ON d.id = v.department_id
WHERE NOT EXISTS (SELECT 1 FROM work_type t WHERE t.department_id = v.department_id AND t.name = v.name COLLATE utf8mb4_bin);

-- 분야와 맞지 않는 처음 시드 유형은 지우지 않고 사용 중지한다(이미 보낸 요청의 유형·양식 답은 그대로 남는다).
--   1중대 1반(유선) 전장망 / 1중대 2반(무선) 화상회의 / 2중대 1반(전장망) 전화 / 2중대 2반(화상회의) 전화
-- 반복 시드는 이 파일이 바뀔 때만 다시 돈다. 관리자가 다시 사용으로 바꾼 뒤 이 파일을 고치면 다시 사용 중지되니 그때는 이 목록을 함께 고친다
UPDATE work_type t
JOIN (
    SELECT 5 AS department_id, '전장망' AS name
    UNION ALL SELECT 6, '화상회의'
    UNION ALL SELECT 7, '전화'
    UNION ALL SELECT 8, '전화'
) v ON t.department_id = v.department_id AND t.name = v.name COLLATE utf8mb4_bin AND NOT t.is_other
SET t.active = FALSE
WHERE t.active;

-- 새 유형의 양식(가상). 그 유형에 판이 하나도 없을 때만 첫 판을 넣고, 지금 판이 비어 있는 유형에만 그 판을 지금 판으로 둔다(관리자가 고친 양식은 덮어쓰지 않음)
-- 화상회의 지원은 날짜·시각을 요청의 정확한 일정 칸으로 받으므로 양식에서 다시 묻지 않는다
INSERT INTO work_form_version (work_type_id, version_no, definition, created_by_account_id)
SELECT t.id, 1, v.definition, (SELECT MIN(a.id) FROM account a)
FROM (
    SELECT 5 AS department_id, '랜선·회선 공사' AS name, '{"sections":[{"key":"s1","title":"","description":"","next":"NEXT","items":[{"key":"work","kind":"MULTIPLE_CHOICE","title":"공사 종류","options":[{"id":"o1","label":"랜선 신규 포설"},{"id":"o2","label":"랜선 이동"},{"id":"o3","label":"단자(콘센트) 설치"},{"id":"o4","label":"광케이블 접속"}],"required":true,"allowOther":true},{"key":"ports","kind":"NUMBER","title":"필요한 단자 수","validation":{"type":"NUMBER_RANGE","min":1,"max":50},"required":true},{"key":"route","kind":"SHORT_TEXT","title":"포설 구간","required":true,"description":"예: 1중대 행정반 → 통신실"},{"key":"photo","kind":"FILE","title":"배치도·현장 사진","description":"있으면 올리세요(문서·그림, 파일 하나 10MB)."}]}]}' AS definition
    UNION ALL SELECT 6, '무전기 고장', '{"sections":[{"key":"s1","title":"","description":"","next":"NEXT","items":[{"key":"model","kind":"DROPDOWN","title":"무전기 종류","required":true,"options":[{"id":"o1","label":"휴대용 무전기"},{"id":"o2","label":"차량용 무전기"},{"id":"o3","label":"기지국용 무전기"}]},{"key":"serial","kind":"SHORT_TEXT","title":"장비 번호(시리얼)","description":"장비 뒷면 표기를 적으세요."},{"key":"symptom","kind":"CHECKBOXES","title":"증상","options":[{"id":"o1","label":"전원 안 켜짐"},{"id":"o2","label":"송신 안 됨"},{"id":"o3","label":"수신 불량"},{"id":"o4","label":"잡음"},{"id":"o5","label":"배터리 방전"}],"shuffle":false,"required":true,"allowOther":true},{"key":"count","kind":"NUMBER","title":"고장 대수","validation":{"type":"NUMBER_RANGE","min":1,"max":30},"required":true}]}]}'
    UNION ALL SELECT 6, '무선망 점검', '{"sections":[{"key":"s1","title":"","description":"","next":"NEXT","items":[{"key":"notice","kind":"GUIDE","title":"안내","description":"훈련·작전 일정이 있으면 최소 3일 전에 요청하세요."},{"key":"target","kind":"CHECKBOXES","title":"점검 대상","options":[{"id":"o1","label":"중계기"},{"id":"o2","label":"안테나"},{"id":"o3","label":"주파수 설정"},{"id":"o4","label":"교신 시험"}],"shuffle":false,"required":true},{"key":"area","kind":"SHORT_TEXT","title":"점검 지역·구간","required":true}]}]}'
    UNION ALL SELECT 7, '전장망 장애', '{"sections":[{"key":"s1","title":"","description":"","next":"NEXT","items":[{"key":"status","kind":"MC_GRID","title":"장비별 상태","rows":[{"id":"r1","label":"전장망 단말"},{"id":"r2","label":"전장망 스위치"},{"id":"r3","label":"지휘통제체계 접속"}],"columns":[{"id":"c1","label":"정상"},{"id":"c2","label":"불량"},{"id":"c3","label":"확인 필요"}],"required":true},{"key":"scope","kind":"MULTIPLE_CHOICE","title":"영향 범위","options":[{"id":"o1","label":"단말 1대"},{"id":"o2","label":"사무실 전체"},{"id":"o3","label":"건물 전체"}],"required":true},{"key":"terminal","kind":"SHORT_TEXT","title":"단말 번호"}]}]}'
    UNION ALL SELECT 7, '전장망 단말 설치', '{"sections":[{"key":"s1","title":"","description":"","next":"NEXT","items":[{"key":"work","kind":"MULTIPLE_CHOICE","title":"작업 종류","options":[{"id":"o1","label":"신규 설치"},{"id":"o2","label":"이전 설치"},{"id":"o3","label":"회수"}],"required":true},{"key":"count","kind":"NUMBER","title":"단말 수","validation":{"type":"NUMBER_RANGE","min":1,"max":20},"required":true},{"key":"account","kind":"SHORT_TEXT","title":"사용자 계정 신청 여부","description":"새 계정이 필요하면 사용자 이름을 적으세요."}]}]}'
    UNION ALL SELECT 8, '화상회의 지원', '{"sections":[{"key":"s1","title":"","description":"","next":"NEXT","items":[{"key":"notice","kind":"GUIDE","title":"안내","description":"회의 시작 30분 전부터 장비를 연결합니다.\\n회의 전날 연결 시험을 함께 진행합니다."},{"key":"system","kind":"DROPDOWN","title":"회의 체계","required":true,"options":[{"id":"o1","label":"KJCCS 화상회의"},{"id":"o2","label":"AFCCS 화상회의"},{"id":"o3","label":"웹미팅"}]},{"key":"attendees","kind":"NUMBER","title":"참석 인원","validation":{"type":"NUMBER_RANGE","min":1,"max":200},"required":true},{"key":"room","kind":"SHORT_TEXT","title":"회의실","required":true},{"key":"equipment","kind":"CHECKBOXES","title":"필요한 장비","options":[{"id":"o1","label":"카메라"},{"id":"o2","label":"마이크"},{"id":"o3","label":"스피커"},{"id":"o4","label":"대형 화면"},{"id":"o5","label":"자료 표출(노트북 연결)"}],"shuffle":false,"required":true},{"key":"agenda","kind":"LONG_TEXT","title":"회의 목적과 참석 부서"}]}]}'
    UNION ALL SELECT 8, '회의실 장비 고장', '{"sections":[{"key":"s1","title":"","description":"","next":"NEXT","items":[{"key":"room","kind":"SHORT_TEXT","title":"회의실","required":true},{"key":"device","kind":"CHECKBOXES","title":"고장 장비","options":[{"id":"o1","label":"카메라"},{"id":"o2","label":"마이크"},{"id":"o3","label":"스피커"},{"id":"o4","label":"화면·프로젝터"},{"id":"o5","label":"화상회의 단말"}],"shuffle":false,"required":true,"allowOther":true},{"key":"urgent","kind":"MULTIPLE_CHOICE","title":"다음 회의 일정","options":[{"id":"o1","label":"오늘 회의 있음"},{"id":"o2","label":"이번 주 회의 있음"},{"id":"o3","label":"일정 없음"}],"required":true},{"key":"photo","kind":"FILE","title":"현장·고장 사진","description":"있으면 올리세요(문서·그림, 파일 하나 10MB)."}]}]}'
    UNION ALL SELECT 9, 'PC·프린터 고장', '{"sections":[{"key":"s1","title":"","description":"","next":"NEXT","items":[{"key":"device","kind":"MULTIPLE_CHOICE","title":"장비","options":[{"id":"o1","label":"업무용 PC"},{"id":"o2","label":"프린터·복합기"},{"id":"o3","label":"모니터"}],"required":true,"allowOther":true},{"key":"asset","kind":"SHORT_TEXT","title":"장비 번호(자산 번호)"},{"key":"symptom","kind":"CHECKBOXES","title":"증상","options":[{"id":"o1","label":"전원 안 켜짐"},{"id":"o2","label":"부팅 안 됨"},{"id":"o3","label":"느림"},{"id":"o4","label":"인쇄 안 됨"},{"id":"o5","label":"용지 걸림"},{"id":"o6","label":"화면 안 나옴"}],"shuffle":false,"required":true,"allowOther":true},{"key":"photo","kind":"FILE","title":"오류 화면 사진","description":"있으면 올리세요(문서·그림, 파일 하나 10MB)."}]}]}'
    UNION ALL SELECT 9, '인트라넷 접속 장애', '{"sections":[{"key":"s1","title":"","description":"","next":"NEXT","items":[{"key":"pcName","kind":"SHORT_TEXT","title":"사용자 PC 이름","required":true,"validation":{"type":"TEXT_LENGTH","max":30}},{"key":"ip","kind":"SHORT_TEXT","title":"IP 주소","description":"모르면 비워 두세요.","validation":{"type":"IP"}},{"key":"issue","kind":"DROPDOWN","title":"증상","required":true,"options":[{"id":"o1","label":"접속 안 됨"},{"id":"o2","label":"로그인 실패"},{"id":"o3","label":"특정 메뉴 오류"},{"id":"o4","label":"느림"}]}]}]}'
    UNION ALL SELECT 10, '보안 점검', '{"sections":[{"key":"s1","title":"","description":"","next":"NEXT","items":[{"key":"kind","kind":"MULTIPLE_CHOICE","title":"점검 종류","options":[{"id":"o1","label":"악성코드 의심 PC 점검"},{"id":"o2","label":"백신·보안 프로그램 점검"},{"id":"o3","label":"정기 보안 점검"}],"required":true},{"key":"count","kind":"NUMBER","title":"대상 PC 수","validation":{"type":"NUMBER_RANGE","min":1,"max":100},"required":true},{"key":"detail","kind":"LONG_TEXT","title":"의심 증상·경고 내용"}]}]}'
    UNION ALL SELECT 10, '보안 USB 등록', '{"sections":[{"key":"s1","title":"","description":"","next":"NEXT","items":[{"key":"notice","kind":"GUIDE","title":"안내","description":"보안 USB는 부서장 승인 후 등록합니다. 발송 결재 경로에 부서장을 넣으세요."},{"key":"work","kind":"MULTIPLE_CHOICE","title":"요청 종류","options":[{"id":"o1","label":"신규 등록"},{"id":"o2","label":"등록 해제"},{"id":"o3","label":"분실 신고"}],"required":true},{"key":"count","kind":"NUMBER","title":"USB 수량","validation":{"type":"NUMBER_RANGE","min":1,"max":20},"required":true},{"key":"user","kind":"SHORT_TEXT","title":"사용자","required":true}]}]}'
    UNION ALL SELECT 15, '위성통신 장애', '{"sections":[{"key":"s1","title":"","description":"","next":"NEXT","items":[{"key":"status","kind":"MC_GRID","title":"장비별 상태","rows":[{"id":"r1","label":"위성 단말"},{"id":"r2","label":"위성 안테나"},{"id":"r3","label":"모뎀"}],"columns":[{"id":"c1","label":"정상"},{"id":"c2","label":"불량"},{"id":"c3","label":"확인 필요"}],"required":true},{"key":"site","kind":"SHORT_TEXT","title":"운용 위치","required":true}]}]}'
    UNION ALL SELECT 16, '전송 회선 장애', '{"sections":[{"key":"s1","title":"","description":"","next":"NEXT","items":[{"key":"section","kind":"SHORT_TEXT","title":"장애 구간","required":true,"description":"예: A기지 ↔ B기지"},{"key":"alarm","kind":"MULTIPLE_CHOICE","title":"장비 경보","options":[{"id":"o1","label":"경보 있음"},{"id":"o2","label":"경보 없음"},{"id":"o3","label":"모름"}],"required":true},{"key":"circuit","kind":"SHORT_TEXT","title":"회선 번호"}]}]}'
    UNION ALL SELECT 17, '네트워크 장비 고장', '{"sections":[{"key":"s1","title":"","description":"","next":"NEXT","items":[{"key":"device","kind":"MULTIPLE_CHOICE","title":"장비 종류","options":[{"id":"o1","label":"스위치"},{"id":"o2","label":"라우터"},{"id":"o3","label":"방화벽"},{"id":"o4","label":"서버"}],"required":true,"allowOther":true},{"key":"name","kind":"SHORT_TEXT","title":"장비 이름·위치","required":true},{"key":"scope","kind":"MULTIPLE_CHOICE","title":"영향 범위","options":[{"id":"o1","label":"일부 포트"},{"id":"o2","label":"사무실 전체"},{"id":"o3","label":"건물 전체"},{"id":"o4","label":"부대 전체"}],"required":true}]}]}'
    UNION ALL SELECT 18, 'CCTV 고장', '{"sections":[{"key":"s1","title":"","description":"","next":"NEXT","items":[{"key":"camera","kind":"NUMBER","title":"카메라 번호","validation":{"type":"NUMBER_RANGE","min":1,"max":999},"required":true},{"key":"symptom","kind":"CHECKBOXES","title":"증상","options":[{"id":"o1","label":"화면 안 나옴"},{"id":"o2","label":"녹화 안 됨"},{"id":"o3","label":"화면 흐림"},{"id":"o4","label":"영상 끊김"}],"shuffle":false,"required":true,"allowOther":true},{"key":"place","kind":"SHORT_TEXT","title":"설치 위치","required":true},{"key":"photo","kind":"FILE","title":"화면 캡처·현장 사진","description":"있으면 올리세요(문서·그림, 파일 하나 10MB)."}]}]}'
    UNION ALL SELECT 19, '행사 음향 지원', '{"sections":[{"key":"s1","title":"","description":"","next":"NEXT","items":[{"key":"notice","kind":"GUIDE","title":"안내","description":"행사 전날 음향 점검을 함께 진행합니다."},{"key":"event","kind":"SHORT_TEXT","title":"행사 이름","required":true},{"key":"attendees","kind":"NUMBER","title":"참석 인원","validation":{"type":"NUMBER_RANGE","min":1,"max":1000},"required":true},{"key":"equipment","kind":"CHECKBOXES","title":"필요한 장비","options":[{"id":"o1","label":"무선 마이크"},{"id":"o2","label":"유선 마이크"},{"id":"o3","label":"스피커"},{"id":"o4","label":"음악 재생"},{"id":"o5","label":"녹음"}],"shuffle":false,"required":true}]}]}'
    UNION ALL SELECT 19, '방송 장비 고장', '{"sections":[{"key":"s1","title":"","description":"","next":"NEXT","items":[{"key":"device","kind":"MULTIPLE_CHOICE","title":"장비","options":[{"id":"o1","label":"구내방송 앰프"},{"id":"o2","label":"방송 스피커"},{"id":"o3","label":"방송 마이크"}],"required":true,"allowOther":true},{"key":"place","kind":"SHORT_TEXT","title":"위치","required":true},{"key":"symptom","kind":"CHECKBOXES","title":"증상","options":[{"id":"o1","label":"소리 안 나옴"},{"id":"o2","label":"잡음"},{"id":"o3","label":"일부 구역만 안 들림"}],"shuffle":false,"required":true,"allowOther":true}]}]}'
    UNION ALL SELECT 20, '발전기·UPS 점검', '{"sections":[{"key":"s1","title":"","description":"","next":"NEXT","items":[{"key":"device","kind":"MULTIPLE_CHOICE","title":"장비","options":[{"id":"o1","label":"발전기"},{"id":"o2","label":"UPS"},{"id":"o3","label":"정류기·축전지"}],"required":true},{"key":"kind","kind":"MULTIPLE_CHOICE","title":"점검 종류","options":[{"id":"o1","label":"정기 점검"},{"id":"o2","label":"부하 시험"},{"id":"o3","label":"고장 점검"},{"id":"o4","label":"축전지 교체"}],"required":true},{"key":"place","kind":"SHORT_TEXT","title":"설치 위치","required":true},{"key":"alarm","kind":"LONG_TEXT","title":"경보·이상 내용"}]}]}'
) v
JOIN work_type t ON t.department_id = v.department_id AND t.name = v.name COLLATE utf8mb4_bin AND NOT t.is_other
WHERE NOT EXISTS (SELECT 1 FROM work_form_version f WHERE f.work_type_id = t.id);

UPDATE work_type t
JOIN work_form_version f ON f.work_type_id = t.id AND f.version_no = 1
SET t.current_form_version_id = f.id
WHERE t.current_form_version_id IS NULL AND NOT t.is_other AND t.department_id IN (5, 6, 7, 8, 9, 10, 15, 16, 17, 18, 19, 20);
