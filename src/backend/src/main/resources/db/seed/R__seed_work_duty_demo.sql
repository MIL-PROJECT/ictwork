-- 시연용 근무표 (명세 7장 시연 도구, REQ-404, slices/14 9장·14.9, G-147, slices/21 9장). 근무표 6개(요일 규칙·교대 규칙, 한 근무표에 둘을 섞은 것 포함),
-- 적용 날짜 @d0(시연 기준일 2026-11-19(목), 고정) 기준 @d0-7일 ~ @d0+23일(2026-11-12 ~ 2026-12-12, 31일) 배치. 이 기간에 체계 공휴일은 없다.
-- 생성기 없이 재귀 CTE로 날짜를 만들고 순번·칸을 MOD로 센다. 이름·값은 모두 가상(NFR-002)
-- 이름이 R__seed_work_duty_demo라 초기 데이터(계정)·R__seed_work_docbox_demo 다음, R__seed_work_ict_status_demo(조각 15) 앞에 돈다.
-- 체계 공휴일(db/migration R__public_holidays)은 반복 마이그레이션 이름순으로 이보다 먼저 들어간다.
-- 번호 400001~409999(duty_roster·duty_rule·duty_shift_type·duty_placement·duty_holiday)에 줄이 하나라도 있거나, 쓸 계정(사용 중)이 하나라도 없거나,
-- 근무표·공유 부서가 사용 중이 아니거나, 같은 부서에 같은 이름 근무표가 있으면 전체를 건너뛴다(옛 시연 데이터가 든 DB는 V33이 옮긴 줄이 번호를 쓰고 있어 건너뜀)
-- 근무표는 그 부서의 부서 관리자가 만든다(REQ-404, 초기 데이터 role_assignment의 DEPT_ADMIN):
--   정보통신상황실(22) bn1-hq-s1-lead, 정보통신대대 본부중대(21) bn1-hq-cmdr, 서버반(10) bn1-co3-s2-lead, 1중대(2) bn1-co1-admin,
--   근무지원대대 본부중대(12) bn2-co1-cmdr, 의무중대(23) bn2-co4-cmdr. 근무 인원은 그 부서와 하위 소속이고, 한 사람은 한 근무표에만 든다
-- 만든 시각·인원에 든 시각은 적용하는 순간(NOW)이다: 인원의 부서 바꾼 시각(적용 때 들어감)보다 앞이면 "전출" 표시가 붙는다(DutyPlacementService)

SET @d0 = DATE('2026-11-19');
SET @now = NOW(3);
SET @a_hq_s1_lead = (SELECT id FROM account WHERE login_id = 'bn1-hq-s1-lead' AND active = TRUE);
SET @a_hq_cmdr = (SELECT id FROM account WHERE login_id = 'bn1-hq-cmdr' AND active = TRUE);
SET @a_co3_s2_lead = (SELECT id FROM account WHERE login_id = 'bn1-co3-s2-lead' AND active = TRUE);
SET @a_co1_admin = (SELECT id FROM account WHERE login_id = 'bn1-co1-admin' AND active = TRUE);
SET @a_bn2_co1_cmdr = (SELECT id FROM account WHERE login_id = 'bn2-co1-cmdr' AND active = TRUE);
SET @a_bn2_co4_cmdr = (SELECT id FROM account WHERE login_id = 'bn2-co4-cmdr' AND active = TRUE);
-- 정보통신대대 본부중대(21)·정보통신상황실(22)
SET @p_hq_cmdr = (SELECT personnel_id FROM account WHERE login_id = 'bn1-hq-cmdr' AND active = TRUE);
SET @p_hq_lead = (SELECT personnel_id FROM account WHERE login_id = 'bn1-hq-s1-lead' AND active = TRUE);
SET @p_hq_m1 = (SELECT personnel_id FROM account WHERE login_id = 'bn1-hq-s1-m1' AND active = TRUE);
SET @p_hq_m2 = (SELECT personnel_id FROM account WHERE login_id = 'bn1-hq-s1-m2' AND active = TRUE);
SET @p_hq_m3 = (SELECT personnel_id FROM account WHERE login_id = 'bn1-hq-s1-m3' AND active = TRUE);
-- 3중대 서버반(10)
SET @p_sv_lead = (SELECT personnel_id FROM account WHERE login_id = 'bn1-co3-s2-lead' AND active = TRUE);
SET @p_sv_m1 = (SELECT personnel_id FROM account WHERE login_id = 'bn1-co3-s2-m1' AND active = TRUE);
SET @p_sv_m2 = (SELECT personnel_id FROM account WHERE login_id = 'bn1-co3-s2-m2' AND active = TRUE);
SET @p_sv_m3 = (SELECT personnel_id FROM account WHERE login_id = 'bn1-co3-s2-m3' AND active = TRUE);
-- 1중대(2): 유선반(5)·무선반(6)
SET @p_co1_cmdr = (SELECT personnel_id FROM account WHERE login_id = 'bn1-co1-cmdr' AND active = TRUE);
SET @p_w_lead = (SELECT personnel_id FROM account WHERE login_id = 'bn1-co1-s1-lead' AND active = TRUE);
SET @p_w_m1 = (SELECT personnel_id FROM account WHERE login_id = 'bn1-co1-s1-m1' AND active = TRUE);
SET @p_w_admin = (SELECT personnel_id FROM account WHERE login_id = 'bn1-co1-admin' AND active = TRUE);
SET @p_r_lead = (SELECT personnel_id FROM account WHERE login_id = 'bn1-co1-s2-lead' AND active = TRUE);
SET @p_r_m1 = (SELECT personnel_id FROM account WHERE login_id = 'bn1-co1-s2-m1' AND active = TRUE);
SET @p_r_dual = (SELECT personnel_id FROM account WHERE login_id = 'bn1-dual' AND active = TRUE);
SET @p_r_m3 = (SELECT personnel_id FROM account WHERE login_id = 'bn1-co1-s2-m3' AND active = TRUE);
-- 근무지원대대 본부중대(12): 행정반(15)·인사교육반(16)
SET @p_b2_cmdr = (SELECT personnel_id FROM account WHERE login_id = 'bn2-co1-cmdr' AND active = TRUE);
SET @p_b2_s1_lead = (SELECT personnel_id FROM account WHERE login_id = 'bn2-co1-s1-lead' AND active = TRUE);
SET @p_b2_s1_m1 = (SELECT personnel_id FROM account WHERE login_id = 'bn2-co1-s1-m1' AND active = TRUE);
SET @p_b2_s1_m2 = (SELECT personnel_id FROM account WHERE login_id = 'bn2-co1-s1-m2' AND active = TRUE);
SET @p_b2_s2_lead = (SELECT personnel_id FROM account WHERE login_id = 'bn2-co1-s2-lead' AND active = TRUE);
SET @p_b2_s2_m1 = (SELECT personnel_id FROM account WHERE login_id = 'bn2-co1-s2-m1' AND active = TRUE);
SET @p_b2_s2_m2 = (SELECT personnel_id FROM account WHERE login_id = 'bn2-co1-s2-m2' AND active = TRUE);
-- 의무중대(23): 의무행정반(24)·진료반(25)
SET @p_md_cmdr = (SELECT personnel_id FROM account WHERE login_id = 'bn2-co4-cmdr' AND active = TRUE);
SET @p_md_s1_lead = (SELECT personnel_id FROM account WHERE login_id = 'bn2-co4-s1-lead' AND active = TRUE);
SET @p_md_s1_m1 = (SELECT personnel_id FROM account WHERE login_id = 'bn2-co4-s1-m1' AND active = TRUE);
SET @p_md_s1_m2 = (SELECT personnel_id FROM account WHERE login_id = 'bn2-co4-s1-m2' AND active = TRUE);
SET @p_md_s2_lead = (SELECT personnel_id FROM account WHERE login_id = 'bn2-co4-s2-lead' AND active = TRUE);
SET @p_md_s2_m1 = (SELECT personnel_id FROM account WHERE login_id = 'bn2-co4-s2-m1' AND active = TRUE);
SET @p_md_s2_m2 = (SELECT personnel_id FROM account WHERE login_id = 'bn2-co4-s2-m2' AND active = TRUE);
SET @ok = (SELECT COUNT(*) FROM duty_roster WHERE id BETWEEN 400001 AND 409999) = 0
    AND (SELECT COUNT(*) FROM duty_rule WHERE id BETWEEN 400001 AND 409999) = 0
    AND (SELECT COUNT(*) FROM duty_shift_type WHERE id BETWEEN 400001 AND 409999) = 0
    AND (SELECT COUNT(*) FROM duty_placement WHERE id BETWEEN 400001 AND 409999) = 0
    AND (SELECT COUNT(*) FROM duty_holiday WHERE id BETWEEN 400001 AND 409999) = 0
    AND @a_hq_s1_lead IS NOT NULL AND @a_hq_cmdr IS NOT NULL AND @a_co3_s2_lead IS NOT NULL AND @a_co1_admin IS NOT NULL
    AND @a_bn2_co1_cmdr IS NOT NULL AND @a_bn2_co4_cmdr IS NOT NULL
    AND @p_hq_cmdr IS NOT NULL AND @p_hq_lead IS NOT NULL AND @p_hq_m1 IS NOT NULL AND @p_hq_m2 IS NOT NULL AND @p_hq_m3 IS NOT NULL
    AND @p_sv_lead IS NOT NULL AND @p_sv_m1 IS NOT NULL AND @p_sv_m2 IS NOT NULL AND @p_sv_m3 IS NOT NULL
    AND @p_co1_cmdr IS NOT NULL AND @p_w_lead IS NOT NULL AND @p_w_m1 IS NOT NULL AND @p_w_admin IS NOT NULL
    AND @p_r_lead IS NOT NULL AND @p_r_m1 IS NOT NULL AND @p_r_dual IS NOT NULL AND @p_r_m3 IS NOT NULL
    AND @p_b2_cmdr IS NOT NULL AND @p_b2_s1_lead IS NOT NULL AND @p_b2_s1_m1 IS NOT NULL AND @p_b2_s1_m2 IS NOT NULL
    AND @p_b2_s2_lead IS NOT NULL AND @p_b2_s2_m1 IS NOT NULL AND @p_b2_s2_m2 IS NOT NULL
    AND @p_md_cmdr IS NOT NULL AND @p_md_s1_lead IS NOT NULL AND @p_md_s1_m1 IS NOT NULL AND @p_md_s1_m2 IS NOT NULL
    AND @p_md_s2_lead IS NOT NULL AND @p_md_s2_m1 IS NOT NULL AND @p_md_s2_m2 IS NOT NULL
    -- 근무표 부서(22·21·10·2·12·23)와 공유 부서(정보통신대대 1, 3중대 4, 근무지원대대 11)가 사용 중이고 같은 이름 근무표가 없음
    AND (SELECT COUNT(*) FROM department WHERE id IN (1, 2, 4, 10, 11, 12, 21, 22, 23) AND active) = 9
    AND NOT EXISTS (SELECT 1 FROM duty_roster WHERE (department_id, name_key) IN
        ((22, '상황실 근무'), (21, '야간 당직'), (10, '통신실 교대'), (2, '중대 당직'), (12, '대대 당직'), (23, '진료 당직')));

-- 근무표 (방식 없음, 규칙이 종류를 가짐). 만든 사람 = 그 부서 부서 관리자
INSERT INTO duty_roster (id, department_id, name, name_key, version, created_at, created_by_account_id)
SELECT * FROM (VALUES
    ROW(400001, 22, '상황실 근무', '상황실 근무', 0, @now, @a_hq_s1_lead),
    ROW(400002, 21, '야간 당직', '야간 당직', 0, @now, @a_hq_cmdr),
    ROW(400003, 10, '통신실 교대', '통신실 교대', 0, @now, @a_co3_s2_lead),
    ROW(400004, 2, '중대 당직', '중대 당직', 0, @now, @a_co1_admin),
    ROW(400005, 12, '대대 당직', '대대 당직', 0, @now, @a_bn2_co1_cmdr),
    ROW(400006, 23, '진료 당직', '진료 당직', 0, @now, @a_bn2_co4_cmdr)
) AS v WHERE @ok;

-- 로테이션 규칙. 요일 규칙 적용 날: 월~금 31, 토·일·공휴일 224. 교대 규칙: 매일 255, 패턴은 근무 종류 id(비번도 쉬는 근무 종류, G-151)
--   400001 "상황실 근무": 교대 규칙 "주야 교대"(주야비)
--   400002 "야간 당직": 요일 규칙 "평일"·"주말·공휴일"
--   400003 "통신실 교대": 교대 규칙 "3교대"(주야비) + 요일 규칙 "주말 서버 점검"(섞음)
--   400004 "중대 당직"·400005 "대대 당직"·400006 "진료 당직": 요일 규칙 "평일"·"주말·공휴일"
INSERT INTO duty_rule (id, roster_id, kind, name, name_key, apply_days, shift_pattern, pattern_rev, created_at)
SELECT * FROM (VALUES
    ROW(400001, 400001, 'SHIFT', '주야 교대', '주야 교대', 255, '[400001,400002,400003]', 0, @now),
    ROW(400002, 400002, 'WEEKDAY', '평일', '평일', 31, NULL, 0, @now),
    ROW(400003, 400002, 'WEEKDAY', '주말·공휴일', '주말·공휴일', 224, NULL, 0, @now),
    ROW(400004, 400003, 'SHIFT', '3교대', '3교대', 255, '[400004,400005,400006]', 0, @now),
    ROW(400005, 400003, 'WEEKDAY', '주말 서버 점검', '주말 서버 점검', 224, NULL, 0, @now),
    ROW(400006, 400004, 'WEEKDAY', '평일', '평일', 31, NULL, 0, @now),
    ROW(400007, 400004, 'WEEKDAY', '주말·공휴일', '주말·공휴일', 224, NULL, 0, @now),
    ROW(400008, 400005, 'WEEKDAY', '평일', '평일', 31, NULL, 0, @now),
    ROW(400009, 400005, 'WEEKDAY', '주말·공휴일', '주말·공휴일', 224, NULL, 0, @now),
    ROW(400010, 400006, 'WEEKDAY', '평일', '평일', 31, NULL, 0, @now),
    ROW(400011, 400006, 'WEEKDAY', '주말·공휴일', '주말·공휴일', 224, NULL, 0, @now)
) AS v WHERE @ok;

-- 요일 규칙 인원 (seq 순서 = 아래 배치의 MOD)
--   야간 당직: 평일 상황실장·본부중대장, 주말·공휴일 본부중대장·상황실장 / 주말 서버 점검: 서버반장
--   중대 당직: 평일 유선반·무선반 반원 4명, 주말·공휴일 중대장·두 반장·무선반 하사 / 대대 당직: 평일 반원 4명, 주말·공휴일 두 반장·중대장
--   진료 당직: 평일 진료반·의무행정반 반원 4명, 주말·공휴일 진료반장·의무행정반장·중대장
INSERT INTO duty_rule_member (rule_id, personnel_id, seq, added_at)
SELECT * FROM (VALUES
    ROW(400002, @p_hq_lead, 1, @now), ROW(400002, @p_hq_cmdr, 2, @now),
    ROW(400003, @p_hq_cmdr, 1, @now), ROW(400003, @p_hq_lead, 2, @now),
    ROW(400005, @p_sv_lead, 1, @now),
    ROW(400006, @p_w_m1, 1, @now), ROW(400006, @p_r_m1, 2, @now), ROW(400006, @p_w_admin, 3, @now), ROW(400006, @p_r_dual, 4, @now),
    ROW(400007, @p_co1_cmdr, 1, @now), ROW(400007, @p_w_lead, 2, @now), ROW(400007, @p_r_lead, 3, @now), ROW(400007, @p_r_m3, 4, @now),
    ROW(400008, @p_b2_s1_m1, 1, @now), ROW(400008, @p_b2_s2_m1, 2, @now), ROW(400008, @p_b2_s1_m2, 3, @now), ROW(400008, @p_b2_s2_m2, 4, @now),
    ROW(400009, @p_b2_s1_lead, 1, @now), ROW(400009, @p_b2_s2_lead, 2, @now), ROW(400009, @p_b2_cmdr, 3, @now),
    ROW(400010, @p_md_s2_m1, 1, @now), ROW(400010, @p_md_s1_m1, 2, @now), ROW(400010, @p_md_s2_m2, 3, @now), ROW(400010, @p_md_s1_m2, 4, @now),
    ROW(400011, @p_md_s2_lead, 1, @now), ROW(400011, @p_md_s1_lead, 2, @now), ROW(400011, @p_md_cmdr, 3, @now)
) AS v WHERE @ok;

-- 교대 근무 종류: 주간(주, 1명)·야간(야, 1명)·비번(비, 쉬는 근무 — 배치하지 않음, 인원 0)
INSERT INTO duty_shift_type (id, rule_id, name, name_key, short_label, short_key, headcount, off_duty)
SELECT * FROM (VALUES
    ROW(400001, 400001, '주간', '주간', '주', '주', 1, FALSE),
    ROW(400002, 400001, '야간', '야간', '야', '야', 1, FALSE),
    ROW(400003, 400001, '비번', '비번', '비', '비', 0, TRUE),
    ROW(400004, 400004, '주간', '주간', '주', '주', 1, FALSE),
    ROW(400005, 400004, '야간', '야간', '야', '야', 1, FALSE),
    ROW(400006, 400004, '비번', '비번', '비', '비', 0, TRUE)
) AS v WHERE @ok;

-- 교대 인원과 시작 칸 (시작 칸 = 아래 기억·배치의 m). 상황실 근무자 3명, 서버반 반원 3명
INSERT INTO duty_shift_member (rule_id, personnel_id, seq, start_cell, added_at)
SELECT * FROM (VALUES
    ROW(400001, @p_hq_m1, 1, 1, @now), ROW(400001, @p_hq_m2, 2, 2, @now), ROW(400001, @p_hq_m3, 3, 3, @now),
    ROW(400004, @p_sv_m1, 1, 1, @now), ROW(400004, @p_sv_m2, 2, 2, @now), ROW(400004, @p_sv_m3, 3, 3, @now)
) AS v WHERE @ok;

-- 체계 공휴일 복사 (근무표마다, 보는 요일 공휴일). 번호 400001~ (근무표 순서 → 날짜 순서)
INSERT INTO duty_holiday (id, roster_id, holiday_date, name, view_as, excluded, from_system)
SELECT 400000 + ROW_NUMBER() OVER (ORDER BY r.id, h.holiday_date), r.id, h.holiday_date, h.name, 'HOLIDAY', FALSE, TRUE
FROM public_holiday h CROSS JOIN (SELECT 400001 AS id UNION ALL SELECT 400002 UNION ALL SELECT 400003
                                  UNION ALL SELECT 400004 UNION ALL SELECT 400005 UNION ALL SELECT 400006) r
WHERE @ok;

-- 공유 부서와 담당자 (담당자는 근무표 부서와 그 하위 소속, REQ-404)
--   400001 상황실 근무: 정보통신대대 본부중대(하위 포함)·정보통신대대(하위 제외, 대대장·체계 관리자가 봄), 담당 상황 근무자 bn1-hq-s1-m1
--   400002 야간 당직: 정보통신대대 본부중대(하위 포함, 상황실이 봄)·정보통신대대(하위 제외), 담당 상황실장
--   400003 통신실 교대: 3중대(하위 제외, 중대장이 봄) / 400004 중대 당직: 1중대(하위 포함)·정보통신대대(하위 제외), 담당 유선반장
--   400005 대대 당직: 근무지원대대 본부중대(하위 포함)·근무지원대대(하위 제외), 담당 행정반장
--   400006 진료 당직: 의무중대(하위 포함)·근무지원대대(하위 포함, 대대 모두가 봄), 담당 진료반장
--   공유 부서는 하위 포함을 끄면 그 부서 소속만 본다(DutyAccess.canView ③): 대대 직속 소속(대대장 등)만 보고 다른 중대는 보지 않는다
INSERT INTO duty_share (roster_id, department_id, include_sub)
SELECT * FROM (VALUES
    ROW(400001, 21, TRUE), ROW(400001, 1, FALSE), ROW(400002, 21, TRUE), ROW(400002, 1, FALSE), ROW(400003, 4, FALSE),
    ROW(400004, 2, TRUE), ROW(400004, 1, FALSE), ROW(400005, 12, TRUE), ROW(400005, 11, FALSE), ROW(400006, 23, TRUE), ROW(400006, 11, TRUE)
) AS v WHERE @ok;
INSERT INTO duty_assignee (roster_id, personnel_id, created_at)
SELECT * FROM (VALUES
    ROW(400001, @p_hq_m1, @now), ROW(400002, @p_hq_lead, @now), ROW(400004, @p_w_lead, @now), ROW(400005, @p_b2_s1_lead, @now), ROW(400006, @p_md_s2_lead, @now)
) AS v WHERE @ok;

-- 요일 규칙 배치: 날마다 그 날에 맞는 규칙에 1명. 날 판단은 그 근무표에 복사한 공휴일(보는 요일 공휴일)로: 공휴일·토·일은 224 규칙, 그 밖은 31 규칙.
-- 규칙마다 그 규칙 날의 k번째(0부터)에 인원 k % 인원 수번째.
-- 번호: 400002 근무표 401001~, 400003 근무표(주말 서버 점검) 402201~, 400004 근무표 403001~, 400005 근무표 404001~, 400006 근무표 405001~
INSERT INTO duty_placement (id, roster_id, duty_date, rule_id, weekday_rule_id, shift_type_id, personnel_id, created_at)
WITH RECURSIVE seq (i) AS (SELECT 0 UNION ALL SELECT i + 1 FROM seq WHERE i < 30),
     days (d) AS (SELECT @d0 - INTERVAL 7 DAY + INTERVAL i DAY FROM seq),
     -- (근무표, 평일 규칙, 주말·공휴일 규칙): 둘 중 하나가 NULL이면 그 날은 배치하지 않음
     plan (roster_id, weekday_rule, weekend_rule, base_id) AS (
         SELECT 400002, 400002, 400003, 401000 UNION ALL SELECT 400003, NULL, 400005, 402200
         UNION ALL SELECT 400004, 400006, 400007, 403000 UNION ALL SELECT 400005, 400008, 400009, 404000
         UNION ALL SELECT 400006, 400010, 400011, 405000
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
           WHEN 400002 THEN ELT(MOD(k, 2) + 1, @p_hq_lead, @p_hq_cmdr)
           WHEN 400003 THEN ELT(MOD(k, 2) + 1, @p_hq_cmdr, @p_hq_lead)
           WHEN 400005 THEN @p_sv_lead
           WHEN 400006 THEN ELT(MOD(k, 4) + 1, @p_w_m1, @p_r_m1, @p_w_admin, @p_r_dual)
           WHEN 400007 THEN ELT(MOD(k, 4) + 1, @p_co1_cmdr, @p_w_lead, @p_r_lead, @p_r_m3)
           WHEN 400008 THEN ELT(MOD(k, 4) + 1, @p_b2_s1_m1, @p_b2_s2_m1, @p_b2_s1_m2, @p_b2_s2_m2)
           WHEN 400009 THEN ELT(MOD(k, 3) + 1, @p_b2_s1_lead, @p_b2_s2_lead, @p_b2_cmdr)
           WHEN 400010 THEN ELT(MOD(k, 4) + 1, @p_md_s2_m1, @p_md_s1_m1, @p_md_s2_m2, @p_md_s1_m2)
           WHEN 400011 THEN ELT(MOD(k, 3) + 1, @p_md_s2_lead, @p_md_s1_lead, @p_md_cmdr)
       END, @now
FROM numbered WHERE @ok;

-- 교대: 사람 m(시작 칸 m)의 날 i 칸 = MOD(m - 1 + i, 패턴 길이) + 1. 칸 1 주간, 칸 2 야간, 나머지 비번. 날마다 사람마다 칸을 기억한다
INSERT INTO duty_shift_memory (rule_id, personnel_id, duty_date, cell, stopped, pattern_rev)
WITH RECURSIVE seq (i) AS (SELECT 0 UNION ALL SELECT i + 1 FROM seq WHERE i < 30),
     member (rule_id, n, m, pid) AS (
         SELECT 400001, 3, 1, @p_hq_m1 UNION ALL SELECT 400001, 3, 2, @p_hq_m2 UNION ALL SELECT 400001, 3, 3, @p_hq_m3
         UNION ALL SELECT 400004, 3, 1, @p_sv_m1 UNION ALL SELECT 400004, 3, 2, @p_sv_m2 UNION ALL SELECT 400004, 3, 3, @p_sv_m3
     )
SELECT member.rule_id, member.pid, @d0 - INTERVAL 7 DAY + INTERVAL seq.i DAY, MOD(member.m - 1 + seq.i, member.n) + 1, FALSE, 0
FROM seq CROSS JOIN member WHERE @ok;

-- 교대 배치: 칸 1·2인 사람. 근무 종류 = 규칙의 패턴 칸 순서(주간 → 야간). 번호: 400001 근무표 400001~, 400003 근무표 402001~
INSERT INTO duty_placement (id, roster_id, duty_date, rule_id, weekday_rule_id, shift_type_id, personnel_id, created_at)
SELECT p.base_id + ROW_NUMBER() OVER (PARTITION BY p.rule_id ORDER BY m.duty_date, m.cell), p.roster_id, m.duty_date, m.rule_id, NULL,
       CASE m.cell WHEN 1 THEN p.day_type ELSE p.night_type END, m.personnel_id, @now
FROM duty_shift_memory m
JOIN (SELECT 400001 AS rule_id, 400001 AS roster_id, 400000 AS base_id, 400001 AS day_type, 400002 AS night_type
      UNION ALL SELECT 400004, 400003, 402000, 400004, 400005) p ON p.rule_id = m.rule_id
WHERE @ok AND m.cell <= 2;

SELECT @ok AS duty_demo_applied, (SELECT COUNT(*) FROM duty_roster WHERE id BETWEEN 400001 AND 409999) AS rosters,
       (SELECT COUNT(*) FROM duty_placement WHERE id BETWEEN 400001 AND 409999) AS placements;
