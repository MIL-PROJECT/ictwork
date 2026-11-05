-- 시연용 근무표 (명세 7장 시연 도구, REQ-404, slices/14 9장·14.9, G-147). 근무표 6개(요일 규칙·교대 규칙, 한 근무표에 둘을 섞은 것 포함),
-- 적용 날짜 @d0(시연 기준일 2026-11-02, 고정) 기준 @d0-7일 ~ @d0+23일(31일) 배치. 생성기 없이 재귀 CTE로 날짜를 만들고 순번·칸을 MOD로 센다. 이름·값은 모두 가상(NFR-002)
-- 이름이 R__seed_work_duty_demo라 초기 데이터(계정)·R__seed_work_docbox_demo 다음, R__seed_work_ict_status_demo(조각 15) 앞에 돈다.
-- 체계 공휴일(db/migration R__public_holidays)은 반복 마이그레이션 이름순으로 이보다 먼저 들어간다.
-- 번호 400001~409999(duty_roster·duty_rule·duty_shift_type·duty_placement·duty_holiday)에 줄이 하나라도 있거나, 쓸 계정(사용 중)이 하나라도 없거나,
-- 같은 부서에 같은 이름 근무표가 있으면 전체를 건너뛴다(옛 시연 데이터가 든 DB는 V33이 옮긴 줄이 번호를 쓰고 있어 건너뜀)
-- 근무표는 그 부서의 부서 관리자가 만든다(REQ-404): 1대대 1중대는 bn1-co1-admin, 1중대 2반은 bn1-dual, 1대대 2중대 1반·2반은 반장, 2대대 1중대는 중대장(초기데이터 3.3).
-- 부서 관리자가 없는 부서(1대대 2중대·3중대 본부와 그 반, 2대대 본부·2중대·3중대)에는 근무표를 두지 않는다
-- 만든 시각·인원에 든 시각은 적용하는 순간(NOW)이다: 인원의 부서 바꾼 시각(적용 때 들어감)보다 앞이면 "전출" 표시가 붙는다(DutyPlacementService)

SET @d0 = DATE('2026-11-02');
SET @now = NOW(3);
SET @a_admin = (SELECT id FROM account WHERE login_id = 'bn1-co1-admin' AND active = TRUE);
SET @a_dual = (SELECT id FROM account WHERE login_id = 'bn1-dual' AND active = TRUE);
SET @a_co2_s1_lead = (SELECT id FROM account WHERE login_id = 'bn1-co2-s1-lead' AND active = TRUE);
SET @a_co2_s2_lead = (SELECT id FROM account WHERE login_id = 'bn1-co2-s2-lead' AND active = TRUE);
SET @a_bn2_co1_cmdr = (SELECT id FROM account WHERE login_id = 'bn2-co1-cmdr' AND active = TRUE);
-- 1대대 1중대
SET @p_co1_cmdr = (SELECT personnel_id FROM account WHERE login_id = 'bn1-co1-cmdr' AND active = TRUE);
SET @p_s1_lead = (SELECT personnel_id FROM account WHERE login_id = 'bn1-co1-s1-lead' AND active = TRUE);
SET @p_s2_lead = (SELECT personnel_id FROM account WHERE login_id = 'bn1-co1-s2-lead' AND active = TRUE);
SET @p_s1_m1 = (SELECT personnel_id FROM account WHERE login_id = 'bn1-co1-s1-m1' AND active = TRUE);
SET @p_co1_admin = (SELECT personnel_id FROM account WHERE login_id = 'bn1-co1-admin' AND active = TRUE);
SET @p_s2_m1 = (SELECT personnel_id FROM account WHERE login_id = 'bn1-co1-s2-m1' AND active = TRUE);
SET @p_s2_m3 = (SELECT personnel_id FROM account WHERE login_id = 'bn1-co1-s2-m3' AND active = TRUE);
SET @p_dual = (SELECT personnel_id FROM account WHERE login_id = 'bn1-dual' AND active = TRUE);
-- 1대대 2중대
SET @p_c2_cmdr = (SELECT personnel_id FROM account WHERE login_id = 'bn1-co2-cmdr' AND active = TRUE);
SET @p_c2_s1_lead = (SELECT personnel_id FROM account WHERE login_id = 'bn1-co2-s1-lead' AND active = TRUE);
SET @p_c2_s1_m1 = (SELECT personnel_id FROM account WHERE login_id = 'bn1-co2-s1-m1' AND active = TRUE);
SET @p_c2_s1_m2 = (SELECT personnel_id FROM account WHERE login_id = 'bn1-co2-s1-m2' AND active = TRUE);
SET @p_c2_s1_m3 = (SELECT personnel_id FROM account WHERE login_id = 'bn1-co2-s1-m3' AND active = TRUE);
SET @p_c2_s2_lead = (SELECT personnel_id FROM account WHERE login_id = 'bn1-co2-s2-lead' AND active = TRUE);
SET @p_c2_s2_m1 = (SELECT personnel_id FROM account WHERE login_id = 'bn1-co2-s2-m1' AND active = TRUE);
SET @p_c2_s2_m2 = (SELECT personnel_id FROM account WHERE login_id = 'bn1-co2-s2-m2' AND active = TRUE);
SET @p_c2_s2_m3 = (SELECT personnel_id FROM account WHERE login_id = 'bn1-co2-s2-m3' AND active = TRUE);
-- 2대대 1중대
SET @p_b2_cmdr = (SELECT personnel_id FROM account WHERE login_id = 'bn2-co1-cmdr' AND active = TRUE);
SET @p_b2_s1_lead = (SELECT personnel_id FROM account WHERE login_id = 'bn2-co1-s1-lead' AND active = TRUE);
SET @p_b2_s1_m1 = (SELECT personnel_id FROM account WHERE login_id = 'bn2-co1-s1-m1' AND active = TRUE);
SET @p_b2_s1_m2 = (SELECT personnel_id FROM account WHERE login_id = 'bn2-co1-s1-m2' AND active = TRUE);
SET @p_b2_s1_m3 = (SELECT personnel_id FROM account WHERE login_id = 'bn2-co1-s1-m3' AND active = TRUE);
SET @p_b2_s2_lead = (SELECT personnel_id FROM account WHERE login_id = 'bn2-co1-s2-lead' AND active = TRUE);
SET @p_b2_s2_m1 = (SELECT personnel_id FROM account WHERE login_id = 'bn2-co1-s2-m1' AND active = TRUE);
SET @p_b2_s2_m2 = (SELECT personnel_id FROM account WHERE login_id = 'bn2-co1-s2-m2' AND active = TRUE);
SET @p_b2_s2_m3 = (SELECT personnel_id FROM account WHERE login_id = 'bn2-co1-s2-m3' AND active = TRUE);
SET @ok = (SELECT COUNT(*) FROM duty_roster WHERE id BETWEEN 400001 AND 409999) = 0
    AND (SELECT COUNT(*) FROM duty_rule WHERE id BETWEEN 400001 AND 409999) = 0
    AND (SELECT COUNT(*) FROM duty_shift_type WHERE id BETWEEN 400001 AND 409999) = 0
    AND (SELECT COUNT(*) FROM duty_placement WHERE id BETWEEN 400001 AND 409999) = 0
    AND (SELECT COUNT(*) FROM duty_holiday WHERE id BETWEEN 400001 AND 409999) = 0
    AND @a_admin IS NOT NULL AND @a_dual IS NOT NULL AND @a_co2_s1_lead IS NOT NULL AND @a_co2_s2_lead IS NOT NULL AND @a_bn2_co1_cmdr IS NOT NULL
    AND @p_co1_cmdr IS NOT NULL AND @p_s1_lead IS NOT NULL AND @p_s2_lead IS NOT NULL
    AND @p_s1_m1 IS NOT NULL AND @p_co1_admin IS NOT NULL AND @p_s2_m1 IS NOT NULL AND @p_s2_m3 IS NOT NULL AND @p_dual IS NOT NULL
    AND @p_c2_cmdr IS NOT NULL AND @p_c2_s1_lead IS NOT NULL AND @p_c2_s1_m1 IS NOT NULL AND @p_c2_s1_m2 IS NOT NULL AND @p_c2_s1_m3 IS NOT NULL
    AND @p_c2_s2_lead IS NOT NULL AND @p_c2_s2_m1 IS NOT NULL AND @p_c2_s2_m2 IS NOT NULL AND @p_c2_s2_m3 IS NOT NULL
    AND @p_b2_cmdr IS NOT NULL AND @p_b2_s1_lead IS NOT NULL AND @p_b2_s1_m1 IS NOT NULL AND @p_b2_s1_m2 IS NOT NULL AND @p_b2_s1_m3 IS NOT NULL
    AND @p_b2_s2_lead IS NOT NULL AND @p_b2_s2_m1 IS NOT NULL AND @p_b2_s2_m2 IS NOT NULL AND @p_b2_s2_m3 IS NOT NULL
    -- 근무표 부서(1대대 1중대 2, 1중대 2반 6, 2중대 1반 7, 2중대 2반 8, 2대대 1중대 12)와 공유 부서(1대대 1, 1대대 2중대 3, 2대대 11)가 사용 중이고 같은 이름 근무표가 없음
    AND (SELECT COUNT(*) FROM department WHERE id IN (1, 2, 3, 6, 7, 8, 11, 12) AND active) = 8
    AND NOT EXISTS (SELECT 1 FROM duty_roster WHERE (department_id, name_key) IN
        ((2, '야간 당직'), (6, '통신 상황 교대'), (7, '2중대 당직'), (8, '통신실 교대'), (12, '중대 당직'), (12, '상황실 근무')));

-- 근무표 (방식 없음, 규칙이 종류를 가짐). 만든 사람 = 그 부서 부서 관리자
INSERT INTO duty_roster (id, department_id, name, name_key, version, created_at, created_by_account_id)
SELECT * FROM (VALUES
    ROW(400001, 2, '야간 당직', '야간 당직', 0, @now, @a_admin),
    ROW(400002, 6, '통신 상황 교대', '통신 상황 교대', 0, @now, @a_dual),
    ROW(400003, 7, '2중대 당직', '2중대 당직', 0, @now, @a_co2_s1_lead),
    ROW(400004, 8, '통신실 교대', '통신실 교대', 0, @now, @a_co2_s2_lead),
    ROW(400005, 12, '중대 당직', '중대 당직', 0, @now, @a_bn2_co1_cmdr),
    ROW(400006, 12, '상황실 근무', '상황실 근무', 0, @now, @a_bn2_co1_cmdr)
) AS v WHERE @ok;

-- 로테이션 규칙. 요일 규칙 적용 날: 월~금 31, 토·일·공휴일 224. 교대 규칙: 매일 255, 패턴은 근무 종류 id(null = 비번)
--   400001 "야간 당직": 요일 규칙 "평일"·"주말·공휴일" / 400002 "통신 상황 교대": 교대 규칙 "주야 교대"(주야비)
--   400003 "2중대 당직": 요일 규칙 "평일"·"주말·공휴일" / 400004 "통신실 교대": 교대 규칙 "3교대"(주야비)
--   400005 "중대 당직": 요일 규칙 "평일"·"주말·공휴일" / 400006 "상황실 근무": 교대 규칙 "주야 교대"(주야비) + 요일 규칙 "주말 통신 점검"(섞음)
INSERT INTO duty_rule (id, roster_id, kind, name, name_key, apply_days, shift_pattern, pattern_rev, created_at)
SELECT * FROM (VALUES
    ROW(400001, 400001, 'WEEKDAY', '평일', '평일', 31, NULL, 0, @now),
    ROW(400002, 400001, 'WEEKDAY', '주말·공휴일', '주말·공휴일', 224, NULL, 0, @now),
    ROW(400003, 400002, 'SHIFT', '주야 교대', '주야 교대', 255, '[400001,400002,null]', 0, @now),
    ROW(400004, 400003, 'WEEKDAY', '평일', '평일', 31, NULL, 0, @now),
    ROW(400005, 400003, 'WEEKDAY', '주말·공휴일', '주말·공휴일', 224, NULL, 0, @now),
    ROW(400006, 400004, 'SHIFT', '3교대', '3교대', 255, '[400003,400004,null]', 0, @now),
    ROW(400007, 400005, 'WEEKDAY', '평일', '평일', 31, NULL, 0, @now),
    ROW(400008, 400005, 'WEEKDAY', '주말·공휴일', '주말·공휴일', 224, NULL, 0, @now),
    ROW(400009, 400006, 'SHIFT', '주야 교대', '주야 교대', 255, '[400005,400006,null]', 0, @now),
    ROW(400010, 400006, 'WEEKDAY', '주말 통신 점검', '주말 통신 점검', 224, NULL, 0, @now)
) AS v WHERE @ok;

-- 요일 규칙 인원 (seq 순서 = 아래 배치의 MOD)
INSERT INTO duty_rule_member (rule_id, personnel_id, seq, added_at)
SELECT * FROM (VALUES
    ROW(400001, @p_s1_m1, 1, @now), ROW(400001, @p_co1_admin, 2, @now), ROW(400001, @p_s2_m1, 3, @now),
    ROW(400002, @p_co1_cmdr, 1, @now), ROW(400002, @p_s1_lead, 2, @now),
    ROW(400004, @p_c2_s1_m1, 1, @now), ROW(400004, @p_c2_s1_m2, 2, @now), ROW(400004, @p_c2_s1_m3, 3, @now),
    ROW(400005, @p_c2_s1_lead, 1, @now), ROW(400005, @p_c2_s2_lead, 2, @now), ROW(400005, @p_c2_cmdr, 3, @now),
    ROW(400007, @p_b2_s1_m1, 1, @now), ROW(400007, @p_b2_s2_m1, 2, @now), ROW(400007, @p_b2_s2_m2, 3, @now),
    ROW(400008, @p_b2_s1_lead, 1, @now), ROW(400008, @p_b2_s2_lead, 2, @now), ROW(400008, @p_b2_cmdr, 3, @now),
    ROW(400010, @p_b2_s1_m1, 1, @now), ROW(400010, @p_b2_s2_m1, 2, @now)
) AS v WHERE @ok;

-- 교대 근무 종류: 주간(주, 1명)·야간(야, 1명)
INSERT INTO duty_shift_type (id, rule_id, name, name_key, short_label, short_key, headcount)
SELECT * FROM (VALUES
    ROW(400001, 400003, '주간', '주간', '주', '주', 1),
    ROW(400002, 400003, '야간', '야간', '야', '야', 1),
    ROW(400003, 400006, '주간', '주간', '주', '주', 1),
    ROW(400004, 400006, '야간', '야간', '야', '야', 1),
    ROW(400005, 400009, '주간', '주간', '주', '주', 1),
    ROW(400006, 400009, '야간', '야간', '야', '야', 1)
) AS v WHERE @ok;

-- 교대 인원과 시작 칸 (시작 칸 = 아래 기억·배치의 m). 한 사람은 한 근무표 규칙에만 든다(같은 날 두 근무표에 겹쳐 들지 않게)
INSERT INTO duty_shift_member (rule_id, personnel_id, seq, start_cell, added_at)
SELECT * FROM (VALUES
    ROW(400003, @p_s2_lead, 1, 1, @now), ROW(400003, @p_dual, 2, 2, @now), ROW(400003, @p_s2_m3, 3, 3, @now),
    ROW(400006, @p_c2_s2_m1, 1, 1, @now), ROW(400006, @p_c2_s2_m2, 2, 2, @now), ROW(400006, @p_c2_s2_m3, 3, 3, @now),
    ROW(400009, @p_b2_s1_m2, 1, 1, @now), ROW(400009, @p_b2_s1_m3, 2, 2, @now), ROW(400009, @p_b2_s2_m3, 3, 3, @now)
) AS v WHERE @ok;

-- 체계 공휴일 복사 (근무표마다, 보는 요일 공휴일). 처음 두 근무표는 400001~, 나머지는 401001~
INSERT INTO duty_holiday (id, roster_id, holiday_date, name, view_as, excluded, from_system)
SELECT 400000 + ROW_NUMBER() OVER (ORDER BY r.id, h.holiday_date), r.id, h.holiday_date, h.name, 'HOLIDAY', FALSE, TRUE
FROM public_holiday h CROSS JOIN (SELECT 400001 AS id UNION ALL SELECT 400002) r
WHERE @ok;
INSERT INTO duty_holiday (id, roster_id, holiday_date, name, view_as, excluded, from_system)
SELECT 401000 + ROW_NUMBER() OVER (ORDER BY r.id, h.holiday_date), r.id, h.holiday_date, h.name, 'HOLIDAY', FALSE, TRUE
FROM public_holiday h CROSS JOIN (SELECT 400003 AS id UNION ALL SELECT 400004 UNION ALL SELECT 400005 UNION ALL SELECT 400006) r
WHERE @ok;

-- 공유 부서와 담당자 (담당자는 근무표 부서와 그 하위 소속, REQ-404)
--   400001: 1대대 1중대(하위 포함)·1대대(하위 제외, 대대장이 봄), 담당 1중대 1반장 / 400003: 1대대 2중대(하위 포함)·1대대(하위 제외), 담당 2중대 1반 반원 bn1-co2-s1-m1
--   400004: 1대대 2중대(하위 제외, 중대장이 봄) / 400005: 2대대 1중대(하위 포함)·2대대(하위 제외), 담당 1반장 / 400006: 2대대 1중대(하위 포함)·2대대(하위 제외), 담당 2반장
--   공유 부서는 하위 포함을 끄면 그 부서 소속만 본다(DutyAccess.canView ③): 대대 직속 소속(대대장 등)만 보고 다른 중대는 보지 않는다
INSERT INTO duty_share (roster_id, department_id, include_sub)
SELECT * FROM (VALUES
    ROW(400001, 2, TRUE), ROW(400001, 1, FALSE), ROW(400003, 3, TRUE), ROW(400003, 1, FALSE), ROW(400004, 3, FALSE),
    ROW(400005, 12, TRUE), ROW(400005, 11, FALSE), ROW(400006, 12, TRUE), ROW(400006, 11, FALSE)
) AS v WHERE @ok;
INSERT INTO duty_assignee (roster_id, personnel_id, created_at)
SELECT * FROM (VALUES
    ROW(400001, @p_s1_lead, @now), ROW(400003, @p_c2_s1_m1, @now), ROW(400005, @p_b2_s1_lead, @now), ROW(400006, @p_b2_s2_lead, @now)
) AS v WHERE @ok;

-- 요일 규칙 배치: 날마다 그 날에 맞는 규칙에 1명. 날 판단은 그 근무표에 복사한 공휴일(보는 요일 공휴일)로: 공휴일·토·일은 224 규칙, 그 밖은 31 규칙.
-- 규칙마다 그 규칙 날의 k번째(0부터)에 인원 k % 인원 수번째. 번호: 400001 근무표 400001~, 400003 근무표 402001~, 400005 근무표 404001~, 400006 근무표 405001~
INSERT INTO duty_placement (id, roster_id, duty_date, rule_id, weekday_rule_id, shift_type_id, personnel_id, created_at)
WITH RECURSIVE seq (i) AS (SELECT 0 UNION ALL SELECT i + 1 FROM seq WHERE i < 30),
     days (d) AS (SELECT @d0 - INTERVAL 7 DAY + INTERVAL i DAY FROM seq),
     -- (근무표, 평일 규칙, 주말·공휴일 규칙): 둘 중 하나가 NULL이면 그 날은 배치하지 않음
     plan (roster_id, weekday_rule, weekend_rule, base_id) AS (
         SELECT 400001, 400001, 400002, 400000 UNION ALL SELECT 400003, 400004, 400005, 402000
         UNION ALL SELECT 400005, 400007, 400008, 404000 UNION ALL SELECT 400006, NULL, 400010, 405000
     ),
     ruled (roster_id, base_id, d, rule_id) AS (
         SELECT p.roster_id, p.base_id, days.d,
                CASE WHEN EXISTS (SELECT 1 FROM duty_holiday h WHERE h.roster_id = p.roster_id AND h.holiday_date = days.d AND NOT h.excluded)
                       OR DAYOFWEEK(days.d) IN (1, 7) THEN p.weekend_rule ELSE p.weekday_rule END
         FROM plan p CROSS JOIN days
     ),
     numbered (roster_id, base_id, d, rule_id, k) AS (
         SELECT roster_id, base_id, d, rule_id, ROW_NUMBER() OVER (PARTITION BY rule_id ORDER BY d) - 1 FROM ruled WHERE rule_id IS NOT NULL
     )
SELECT base_id + ROW_NUMBER() OVER (PARTITION BY roster_id ORDER BY d), roster_id, d, rule_id, rule_id, NULL,
       CASE rule_id
           WHEN 400001 THEN ELT(MOD(k, 3) + 1, @p_s1_m1, @p_co1_admin, @p_s2_m1)
           WHEN 400002 THEN ELT(MOD(k, 2) + 1, @p_co1_cmdr, @p_s1_lead)
           WHEN 400004 THEN ELT(MOD(k, 3) + 1, @p_c2_s1_m1, @p_c2_s1_m2, @p_c2_s1_m3)
           WHEN 400005 THEN ELT(MOD(k, 3) + 1, @p_c2_s1_lead, @p_c2_s2_lead, @p_c2_cmdr)
           WHEN 400007 THEN ELT(MOD(k, 3) + 1, @p_b2_s1_m1, @p_b2_s2_m1, @p_b2_s2_m2)
           WHEN 400008 THEN ELT(MOD(k, 3) + 1, @p_b2_s1_lead, @p_b2_s2_lead, @p_b2_cmdr)
           WHEN 400010 THEN ELT(MOD(k, 2) + 1, @p_b2_s1_m1, @p_b2_s2_m1)
       END, @now
FROM numbered WHERE @ok;

-- 교대: 사람 m(시작 칸 m)의 날 i 칸 = MOD(m - 1 + i, 패턴 길이) + 1. 칸 1 주간, 칸 2 야간, 나머지 비번. 날마다 사람마다 칸을 기억한다
INSERT INTO duty_shift_memory (rule_id, personnel_id, duty_date, cell, stopped, pattern_rev)
WITH RECURSIVE seq (i) AS (SELECT 0 UNION ALL SELECT i + 1 FROM seq WHERE i < 30),
     member (rule_id, n, m, pid) AS (
         SELECT 400003, 3, 1, @p_s2_lead UNION ALL SELECT 400003, 3, 2, @p_dual UNION ALL SELECT 400003, 3, 3, @p_s2_m3
         UNION ALL SELECT 400006, 3, 1, @p_c2_s2_m1 UNION ALL SELECT 400006, 3, 2, @p_c2_s2_m2 UNION ALL SELECT 400006, 3, 3, @p_c2_s2_m3
         UNION ALL SELECT 400009, 3, 1, @p_b2_s1_m2 UNION ALL SELECT 400009, 3, 2, @p_b2_s1_m3 UNION ALL SELECT 400009, 3, 3, @p_b2_s2_m3
     )
SELECT member.rule_id, member.pid, @d0 - INTERVAL 7 DAY + INTERVAL seq.i DAY, MOD(member.m - 1 + seq.i, member.n) + 1, FALSE, 0
FROM seq CROSS JOIN member WHERE @ok;

-- 교대 배치: 칸 1·2인 사람. 근무 종류 = 규칙의 패턴 칸 순서(주간 → 야간). 번호: 400002 근무표 400202~, 400004 근무표 403001~, 400006 근무표 405201~
INSERT INTO duty_placement (id, roster_id, duty_date, rule_id, weekday_rule_id, shift_type_id, personnel_id, created_at)
SELECT p.base_id + ROW_NUMBER() OVER (PARTITION BY p.rule_id ORDER BY m.duty_date, m.cell), p.roster_id, m.duty_date, m.rule_id, NULL,
       CASE m.cell WHEN 1 THEN p.day_type ELSE p.night_type END, m.personnel_id, @now
FROM duty_shift_memory m
JOIN (SELECT 400003 AS rule_id, 400002 AS roster_id, 400201 AS base_id, 400001 AS day_type, 400002 AS night_type
      UNION ALL SELECT 400006, 400004, 403000, 400003, 400004
      UNION ALL SELECT 400009, 400006, 405200, 400005, 400006) p ON p.rule_id = m.rule_id
WHERE @ok AND m.cell <= 2;

SELECT @ok AS duty_demo_applied, (SELECT COUNT(*) FROM duty_roster WHERE id BETWEEN 400001 AND 409999) AS rosters,
       (SELECT COUNT(*) FROM duty_placement WHERE id BETWEEN 400001 AND 409999) AS placements;
