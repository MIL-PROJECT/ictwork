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

-- 분야에 맞는 업무 유형. (부서, 이름)이 없을 때만 넣는다(관리자가 이름을 바꾸거나 사용 중지한 유형은 그대로 둔다)
-- 화상회의 지원·행사 음향 지원은 시간이 정해진 지원 업무라 정확한 일정을 받는다(EXACT_REQUIRED)
INSERT INTO work_type (department_id, name, sort_order, schedule_mode)
SELECT v.department_id, v.name, v.sort_order, v.schedule_mode
FROM (
    SELECT 5 AS department_id, '전화 고장' AS name, 11 AS sort_order, 'OPTIONAL' AS schedule_mode
    UNION ALL SELECT 5, '랜선·회선 공사', 12, 'OPTIONAL'
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
