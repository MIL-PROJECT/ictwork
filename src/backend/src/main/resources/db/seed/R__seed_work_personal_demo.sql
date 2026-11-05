-- 시연용 개인 일정 (명세 7장 시연 데이터, REQ-303 달력 일정 추가). 손으로 쓴 파일이다(생성기 없음). 제목·일정은 모두 가상(NFR-002)
-- 이름이 R__seed_work_personal_demo라 설명 이름순으로 다른 시연 시드 다음(마지막)에 돈다(계정만 기댐)
-- 시연 계정 12개(초기데이터 3.1의 사용 중 계정 11개 + 2대대 1중대장 bn2-co1-cmdr)마다 2026-10-26 ~ 2026-11-20의 훈련·회의·교육·당직·휴가 같은 일정 6~10건, 모두 93건.
-- 중요 표시 일부, 여러 날 일정(평일만 표시 포함). 제목 1~100자, 날짜 2000~2099년, 1년 이하, 평일만 표시는 기간에 평일이 있음(CalendarEventService.validate와 같은 규칙)
-- 다른 시연 시드와 맞춤(2026-11-01):
--   자리를 비우는 일정(휴가·연가·외박·교육·시험·훈련·연습·수료식 참석)은 그 사람의 근무표 배치(R__seed_work_duty_demo)와
--   끝나지 않은 계획 칸(R__seed_work_demo의 계획 중·완료 결재 중 처리 건, 진행 중 제안)이 없는 날에만 둔다.
--   1대대 1중대 1반(생방송 시연용으로 11-03~11-05를 비워 둔 반)은 그 사흘에 자리 비우는 일정을 두지 않는다
--   "당직" 일정은 근무표에 그 사람이 배치된 날만 둔다(같은 날·같은 사람)
-- 색은 화면의 네 가지(Danger 훈련·중요 행사, Warning 당직·점검, Primary 회의·업무, Success 교육·휴가)
-- 시연 기준 시각은 2026-11-02 12:00:00(KST, 고정)이다. 만든 시각은 시작일 7일 전 09:00(늦어도 11-02 08:00)
-- id 700001부터. 개인 일정 번호 700001 이상이 쓰였거나(이미 넣음), 쓸 계정(로그인 아이디가 같고 사용 중) 12개 중 하나라도 없으면 전체를 건너뛴다

SET @ok = (SELECT COALESCE(MAX(id), 0) FROM calendar_event) < 700001
    AND (SELECT COUNT(*) FROM account WHERE active = TRUE AND login_id IN
         ('bn1-cmdr', 'bn2-cmdr', 'bn1-co1-cmdr', 'bn1-co2-cmdr', 'bn1-co1-s1-lead', 'bn1-co1-s1-m1', 'bn1-co1-s2-m1',
          'bn1-co1-admin', 'bn1-co2-s1-m1', 'bn1-dual', 'sysadmin', 'bn2-co1-cmdr')) = 12;

-- (번호, 계정, 제목, 시작일, 끝일, 색, 중요, 평일만)
INSERT INTO calendar_event (id, account_id, title, start_date, end_date, color, important, weekdays_only, created_at, updated_at)
SELECT v.column_0, a.id, v.column_2, v.column_3, v.column_4, v.column_5, v.column_6, v.column_7,
       LEAST(TIMESTAMP(v.column_3 - INTERVAL 7 DAY, '09:00:00'), TIMESTAMP('2026-11-02 08:00:00')),
       LEAST(TIMESTAMP(v.column_3 - INTERVAL 7 DAY, '09:00:00'), TIMESTAMP('2026-11-02 08:00:00'))
FROM (VALUES
    -- 1대대장 (근무표 배치 없음)
    ROW(700001, 'bn1-cmdr', '주간 업무 보고', '2026-10-26', '2026-10-26', 'Primary', FALSE, FALSE),
    ROW(700002, 'bn1-cmdr', '대대 정신전력 교육', '2026-10-28', '2026-10-28', 'Success', FALSE, FALSE),
    ROW(700003, 'bn1-cmdr', '주말 부대 순시', '2026-10-31', '2026-11-01', 'Warning', FALSE, FALSE),
    ROW(700004, 'bn1-cmdr', '대대 지휘관 회의', '2026-11-02', '2026-11-02', 'Primary', TRUE, FALSE),
    ROW(700005, 'bn1-cmdr', '지휘소 연습(CPX)', '2026-11-03', '2026-11-05', 'Danger', TRUE, FALSE),
    ROW(700006, 'bn1-cmdr', '주간 업무 보고', '2026-11-09', '2026-11-09', 'Primary', FALSE, FALSE),
    ROW(700007, 'bn1-cmdr', '동계 전투준비태세 점검 기간', '2026-11-09', '2026-11-20', 'Warning', FALSE, TRUE),
    ROW(700008, 'bn1-cmdr', '사단 정보통신 운용 점검', '2026-11-10', '2026-11-10', 'Danger', TRUE, FALSE),
    ROW(700009, 'bn1-cmdr', '연대 지휘관 회의', '2026-11-13', '2026-11-13', 'Primary', TRUE, FALSE),
    ROW(700010, 'bn1-cmdr', '부대 개방 행사 준비', '2026-11-16', '2026-11-18', 'Success', FALSE, FALSE),
    -- 2대대장 (근무표 배치 없음)
    ROW(700011, 'bn2-cmdr', '주간 업무 보고', '2026-10-26', '2026-10-26', 'Primary', FALSE, FALSE),
    ROW(700012, 'bn2-cmdr', '보안 감사 대비 회의', '2026-10-29', '2026-10-29', 'Warning', FALSE, FALSE),
    ROW(700013, 'bn2-cmdr', '간부 체력 측정', '2026-10-30', '2026-10-30', 'Success', FALSE, FALSE),
    ROW(700014, 'bn2-cmdr', '2대대 지휘관 회의', '2026-11-02', '2026-11-02', 'Primary', TRUE, FALSE),
    ROW(700015, 'bn2-cmdr', '전 간부 정보보호 교육', '2026-11-04', '2026-11-04', 'Success', FALSE, FALSE),
    ROW(700016, 'bn2-cmdr', '연대 작전 회의', '2026-11-06', '2026-11-06', 'Primary', TRUE, FALSE),
    ROW(700017, 'bn2-cmdr', '2대대 야외 전술훈련', '2026-11-10', '2026-11-13', 'Danger', TRUE, FALSE),
    ROW(700018, 'bn2-cmdr', '동계 장비 점검 기간', '2026-11-16', '2026-11-20', 'Warning', FALSE, TRUE),
    -- 1대대 1중대장 (야간 당직 주말: 10-31·11-07·11-14·11-21, 계획 칸 11-07~11-09)
    ROW(700019, 'bn1-co1-cmdr', '주간 업무 보고', '2026-10-26', '2026-10-26', 'Primary', FALSE, FALSE),
    ROW(700020, 'bn1-co1-cmdr', '분대장 리더십 교육', '2026-10-28', '2026-10-28', 'Success', FALSE, FALSE),
    ROW(700021, 'bn1-co1-cmdr', '야간 당직(주말)', '2026-10-31', '2026-10-31', 'Warning', FALSE, FALSE),
    ROW(700022, 'bn1-co1-cmdr', '중대 지휘관 회의(화상)', '2026-11-02', '2026-11-02', 'Primary', TRUE, FALSE),
    ROW(700023, 'bn1-co1-cmdr', '지휘소 연습 지원', '2026-11-03', '2026-11-05', 'Danger', TRUE, FALSE),
    ROW(700024, 'bn1-co1-cmdr', '전입 인원 면담', '2026-11-06', '2026-11-06', 'Success', FALSE, FALSE),
    ROW(700025, 'bn1-co1-cmdr', '중대 사격 훈련', '2026-11-11', '2026-11-12', 'Danger', TRUE, FALSE),
    ROW(700026, 'bn1-co1-cmdr', '정보통신 장비 일제 점검', '2026-11-16', '2026-11-19', 'Warning', FALSE, TRUE),
    -- 1대대 2중대장 (2중대 당직: 11-07·11-15, 계획 칸 11-07·11-08)
    ROW(700027, 'bn1-co2-cmdr', '2중대 안전 교육', '2026-10-27', '2026-10-27', 'Success', FALSE, FALSE),
    ROW(700028, 'bn1-co2-cmdr', '중대 체육 활동', '2026-10-30', '2026-10-30', 'Success', FALSE, FALSE),
    ROW(700029, 'bn1-co2-cmdr', '주간 업무 보고', '2026-11-02', '2026-11-02', 'Primary', FALSE, FALSE),
    ROW(700030, 'bn1-co2-cmdr', '지휘소 연습 지원', '2026-11-03', '2026-11-05', 'Danger', TRUE, FALSE),
    ROW(700031, 'bn1-co2-cmdr', '2중대 당직', '2026-11-07', '2026-11-07', 'Warning', FALSE, FALSE),
    ROW(700032, 'bn1-co2-cmdr', '중대 유선망 정비 기간', '2026-11-09', '2026-11-13', 'Warning', FALSE, TRUE),
    ROW(700033, 'bn1-co2-cmdr', '연가', '2026-11-17', '2026-11-19', 'Success', TRUE, FALSE),
    -- 1대대 1중대 1반장 권승현 (야간 당직 주말: 11-01·11-08·11-15, 계획 칸이 많음: 비는 날 11-16·11-17)
    ROW(700034, 'bn1-co1-s1-lead', '반 회의', '2026-10-27', '2026-10-27', 'Primary', FALSE, FALSE),
    ROW(700035, 'bn1-co1-s1-lead', '반 정기 장비 점검', '2026-10-30', '2026-10-30', 'Warning', FALSE, FALSE),
    ROW(700036, 'bn1-co1-s1-lead', '회의실 화상회의 장비 설치 마무리', '2026-11-02', '2026-11-02', 'Primary', TRUE, FALSE),
    ROW(700037, 'bn1-co1-s1-lead', '전장망 단말기 정기 점검', '2026-11-06', '2026-11-06', 'Primary', FALSE, FALSE),
    ROW(700038, 'bn1-co1-s1-lead', '주말 당직 근무', '2026-11-08', '2026-11-08', 'Warning', FALSE, FALSE),
    ROW(700039, 'bn1-co1-s1-lead', '반 회의', '2026-11-10', '2026-11-10', 'Primary', FALSE, FALSE),
    ROW(700040, 'bn1-co1-s1-lead', '부사관 보수 교육', '2026-11-16', '2026-11-17', 'Success', TRUE, FALSE),
    ROW(700041, 'bn1-co1-s1-lead', '무전기 배터리 교체', '2026-11-18', '2026-11-18', 'Warning', FALSE, FALSE),
    -- 1대대 1중대 1반 반원 이예준 (야간 당직 평일: 10-26·10-29·11-03·11-06·11-11·11-16·11-19, 계획 칸이 많아 비는 날은 11-01뿐(11-04·05는 생방송용))
    ROW(700042, 'bn1-co1-s1-m1', '야간 당직 근무', '2026-10-29', '2026-10-29', 'Warning', FALSE, FALSE),
    ROW(700043, 'bn1-co1-s1-m1', '체력 측정', '2026-10-30', '2026-10-30', 'Primary', FALSE, FALSE),
    ROW(700044, 'bn1-co1-s1-m1', '정보처리 자격 시험', '2026-11-01', '2026-11-01', 'Primary', TRUE, FALSE),
    ROW(700045, 'bn1-co1-s1-m1', '야간 당직 근무', '2026-11-06', '2026-11-06', 'Warning', FALSE, FALSE),
    ROW(700046, 'bn1-co1-s1-m1', '지원 장비 반납 확인', '2026-11-06', '2026-11-06', 'Warning', TRUE, FALSE),
    ROW(700047, 'bn1-co1-s1-m1', '분대 회의', '2026-11-10', '2026-11-10', 'Primary', FALSE, FALSE),
    ROW(700048, 'bn1-co1-s1-m1', '분대 장비 점검', '2026-11-12', '2026-11-12', 'Warning', FALSE, FALSE),
    ROW(700049, 'bn1-co1-s1-m1', '야간 당직 근무', '2026-11-16', '2026-11-16', 'Warning', FALSE, FALSE),
    -- 1대대 1중대 2반 반원 임현준 (야간 당직 평일: 10-28·11-02·11-05·11-10·11-13·11-18, 비는 날 11-14~11-17·11-19~)
    ROW(700050, 'bn1-co1-s2-m1', '신병 수료식 참석', '2026-10-27', '2026-10-27', 'Success', FALSE, FALSE),
    ROW(700051, 'bn1-co1-s2-m1', '체력 측정', '2026-10-30', '2026-10-30', 'Primary', FALSE, FALSE),
    ROW(700052, 'bn1-co1-s2-m1', '강의실 프로젝터 설치', '2026-11-03', '2026-11-03', 'Primary', TRUE, FALSE),
    ROW(700053, 'bn1-co1-s2-m1', '야간 당직 근무', '2026-11-05', '2026-11-05', 'Warning', FALSE, FALSE),
    ROW(700054, 'bn1-co1-s2-m1', '강의실 음향 장비 설치', '2026-11-05', '2026-11-05', 'Primary', FALSE, FALSE),
    ROW(700055, 'bn1-co1-s2-m1', '야간 당직 근무', '2026-11-13', '2026-11-13', 'Warning', FALSE, FALSE),
    ROW(700056, 'bn1-co1-s2-m1', '정기 휴가', '2026-11-14', '2026-11-17', 'Success', TRUE, FALSE),
    ROW(700057, 'bn1-co1-s2-m1', 'PC 정비 교육', '2026-11-19', '2026-11-19', 'Success', FALSE, FALSE),
    -- 1대대 1중대 부서 관리자 장선우 (야간 당직 평일: 10-27·10-30·11-04·11-09·11-12·11-17·11-20, 비는 날 10-26·11-15·11-16)
    ROW(700058, 'bn1-co1-admin', '부서 관리 교육', '2026-10-26', '2026-10-26', 'Success', FALSE, FALSE),
    ROW(700059, 'bn1-co1-admin', '야간 당직 근무', '2026-10-30', '2026-10-30', 'Warning', FALSE, FALSE),
    ROW(700060, 'bn1-co1-admin', '주간 업무 보고', '2026-11-02', '2026-11-02', 'Primary', FALSE, FALSE),
    ROW(700061, 'bn1-co1-admin', '야간 당직 근무', '2026-11-04', '2026-11-04', 'Warning', FALSE, FALSE),
    ROW(700062, 'bn1-co1-admin', '장비 대장 정리 기간', '2026-11-09', '2026-11-13', 'Warning', FALSE, TRUE),
    ROW(700063, 'bn1-co1-admin', '외박', '2026-11-15', '2026-11-16', 'Success', TRUE, FALSE),
    -- 1대대 2중대 1반 반원 정건우 (2중대 당직 평일: 10-26·10-29·11-03·11-06·11-11·11-16·11-19, 비는 날 11-13~11-15·11-17·11-18)
    ROW(700064, 'bn1-co2-s1-m1', '2중대 당직', '2026-10-29', '2026-10-29', 'Warning', FALSE, FALSE),
    ROW(700065, 'bn1-co2-s1-m1', '체력 측정', '2026-10-30', '2026-10-30', 'Primary', FALSE, FALSE),
    ROW(700066, 'bn1-co2-s1-m1', '행정반 무정전 전원장치 교체', '2026-11-02', '2026-11-02', 'Warning', TRUE, FALSE),
    ROW(700067, 'bn1-co2-s1-m1', '무전기 감도 점검 요청 건 확인', '2026-11-04', '2026-11-04', 'Primary', FALSE, FALSE),
    ROW(700068, 'bn1-co2-s1-m1', '중대 유선망 정비 기간', '2026-11-09', '2026-11-12', 'Warning', FALSE, TRUE),
    ROW(700069, 'bn1-co2-s1-m1', '정기 휴가', '2026-11-13', '2026-11-15', 'Success', TRUE, FALSE),
    ROW(700070, 'bn1-co2-s1-m1', '정보보호 교육', '2026-11-17', '2026-11-17', 'Success', FALSE, FALSE),
    -- 1대대 1중대 2반 반원·부서 관리자 안지우 (통신 상황 교대 주간·야간이 이틀 걸러, 비는 날 10-27·10-30·11-02·11-08·11-11·11-17·11-20)
    ROW(700071, 'bn1-dual', '통신 상황 근무 요령 교육', '2026-10-27', '2026-10-27', 'Success', FALSE, FALSE),
    ROW(700072, 'bn1-dual', '11월 근무표 작성 마감', '2026-10-29', '2026-10-29', 'Danger', TRUE, FALSE),
    ROW(700073, 'bn1-dual', '부서 관리 교육', '2026-10-30', '2026-10-30', 'Success', FALSE, FALSE),
    ROW(700074, 'bn1-dual', '통신 상황 교대(주간)', '2026-11-03', '2026-11-03', 'Warning', FALSE, FALSE),
    ROW(700075, 'bn1-dual', '신병 면담', '2026-11-05', '2026-11-05', 'Primary', FALSE, FALSE),
    ROW(700076, 'bn1-dual', '위로 휴가', '2026-11-17', '2026-11-17', 'Success', TRUE, FALSE),
    -- 시스템 관리자 황은우 (근무표 배치 없음)
    ROW(700077, 'sysadmin', '주간 업무 보고', '2026-10-26', '2026-10-26', 'Primary', FALSE, FALSE),
    ROW(700078, 'sysadmin', '체계 정기 백업 점검', '2026-10-30', '2026-10-30', 'Warning', FALSE, FALSE),
    ROW(700079, 'sysadmin', '월간 체계 운영 보고', '2026-11-02', '2026-11-02', 'Primary', FALSE, FALSE),
    ROW(700080, 'sysadmin', '3중대 전입 인원 계정 등록', '2026-11-03', '2026-11-03', 'Primary', TRUE, FALSE),
    ROW(700081, 'sysadmin', '정보보호 교육 지원', '2026-11-04', '2026-11-04', 'Success', FALSE, FALSE),
    ROW(700082, 'sysadmin', '외부 정비업체 방문', '2026-11-06', '2026-11-06', 'Primary', FALSE, FALSE),
    ROW(700083, 'sysadmin', '서버실 전원 점검', '2026-11-07', '2026-11-07', 'Warning', FALSE, FALSE),
    ROW(700084, 'sysadmin', '체계 사용자 교육', '2026-11-10', '2026-11-12', 'Success', FALSE, FALSE),
    ROW(700085, 'sysadmin', '체계 점검 결과 정리', '2026-11-13', '2026-11-13', 'Warning', FALSE, FALSE),
    ROW(700086, 'sysadmin', '보안 패치 적용 기간', '2026-11-16', '2026-11-20', 'Danger', TRUE, TRUE),
    -- 2대대 1중대장 최유준 (중대 당직 주말: 11-07·11-15, 계획 칸 11-05)
    ROW(700087, 'bn2-co1-cmdr', '주간 업무 보고', '2026-11-02', '2026-11-02', 'Primary', FALSE, FALSE),
    ROW(700088, 'bn2-co1-cmdr', '화상회의 장비 대여 수령 확인', '2026-11-03', '2026-11-03', 'Primary', FALSE, FALSE),
    ROW(700089, 'bn2-co1-cmdr', '중대 당직', '2026-11-07', '2026-11-07', 'Warning', FALSE, FALSE),
    ROW(700090, 'bn2-co1-cmdr', '대대 간 화상회의 장비 반납', '2026-11-09', '2026-11-09', 'Primary', TRUE, FALSE),
    ROW(700091, 'bn2-co1-cmdr', '2대대 야외 전술훈련', '2026-11-10', '2026-11-13', 'Danger', TRUE, FALSE),
    ROW(700092, 'bn2-co1-cmdr', '임대 장비 만료 점검', '2026-11-16', '2026-11-16', 'Warning', FALSE, FALSE),
    ROW(700093, 'bn2-co1-cmdr', '연가', '2026-11-19', '2026-11-20', 'Success', TRUE, FALSE)
) v
JOIN account a ON a.login_id = v.column_1 AND a.active = TRUE
WHERE @ok;

-- 넣은 건수 (조건에 걸려 조용히 건너뛰었는지 서버 기동 로그에서 보이게)
SELECT @ok AS personal_demo_applied, (SELECT COUNT(*) FROM calendar_event WHERE id >= 700001) AS personal_events;
