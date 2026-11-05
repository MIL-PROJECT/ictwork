-- 시연용 부대 지시사항 (명세 7장, REQ-601, slices/13 7장). 손으로 쓴 파일이다(생성기 없음). 내용은 모두 가상(NFR-002)
-- 이름이 R__seed_work_directive_demo라 초기 데이터·시연 업무 다음, 시연 문서함 앞에 돈다(계정·역할만 기댐)
-- 지시사항 29건: 1대대·2대대와 그 중대·반의 부서장 명의, 2026-10-15 ~ 2026-11-02. 고친 건 4건(UPDATE 기록), 시스템 관리자가 대신 올린 건 3건
-- 지시사항이 하나라도 있거나 번호 범위(300001~)가 쓰였거나, 쓸 계정(사용 중)과 그 명의의 사용 중 부서장 역할(대상 부서 일치, 부서 사용 중)이 하나라도 없으면 전체를 건너뛴다.
-- 알림 줄은 넣지 않는다. 시각은 시연 기준 시각 2026-11-02 12:00:00(KST, 고정) 앞의 정한 시각이고, 지시 일자는 만든 시각의 날짜다.
-- 11-02 건은 아침(08시 대)에 둔다(시연 PC가 그날 아침에 DB를 만들어도 앞날 기록이 되지 않게)

SET @a_sysadmin = (SELECT id FROM account WHERE login_id = 'sysadmin' AND active = TRUE);
SET @a_bn1_cmdr = (SELECT id FROM account WHERE login_id = 'bn1-cmdr' AND active = TRUE);
SET @a_bn1_co1_cmdr = (SELECT id FROM account WHERE login_id = 'bn1-co1-cmdr' AND active = TRUE);
SET @a_bn1_co1_s1_lead = (SELECT id FROM account WHERE login_id = 'bn1-co1-s1-lead' AND active = TRUE);
SET @a_bn1_co1_s2_lead = (SELECT id FROM account WHERE login_id = 'bn1-co1-s2-lead' AND active = TRUE);
SET @a_bn1_co2_cmdr = (SELECT id FROM account WHERE login_id = 'bn1-co2-cmdr' AND active = TRUE);
SET @a_bn1_co2_s1_lead = (SELECT id FROM account WHERE login_id = 'bn1-co2-s1-lead' AND active = TRUE);
SET @a_bn1_co2_s2_lead = (SELECT id FROM account WHERE login_id = 'bn1-co2-s2-lead' AND active = TRUE);
SET @a_bn1_co3_cmdr = (SELECT id FROM account WHERE login_id = 'bn1-co3-cmdr' AND active = TRUE);
SET @a_bn1_co3_s1_lead = (SELECT id FROM account WHERE login_id = 'bn1-co3-s1-lead' AND active = TRUE);
SET @a_bn1_co3_s2_lead = (SELECT id FROM account WHERE login_id = 'bn1-co3-s2-lead' AND active = TRUE);
SET @a_bn2_cmdr = (SELECT id FROM account WHERE login_id = 'bn2-cmdr' AND active = TRUE);
SET @a_bn2_co1_cmdr = (SELECT id FROM account WHERE login_id = 'bn2-co1-cmdr' AND active = TRUE);
SET @a_bn2_co1_s1_lead = (SELECT id FROM account WHERE login_id = 'bn2-co1-s1-lead' AND active = TRUE);
SET @a_bn2_co1_s2_lead = (SELECT id FROM account WHERE login_id = 'bn2-co1-s2-lead' AND active = TRUE);
SET @a_bn2_co2_cmdr = (SELECT id FROM account WHERE login_id = 'bn2-co2-cmdr' AND active = TRUE);
SET @a_bn2_co2_s1_lead = (SELECT id FROM account WHERE login_id = 'bn2-co2-s1-lead' AND active = TRUE);
SET @a_bn2_co2_s2_lead = (SELECT id FROM account WHERE login_id = 'bn2-co2-s2-lead' AND active = TRUE);
SET @a_bn2_co3_cmdr = (SELECT id FROM account WHERE login_id = 'bn2-co3-cmdr' AND active = TRUE);
SET @a_bn2_co3_s1_lead = (SELECT id FROM account WHERE login_id = 'bn2-co3-s1-lead' AND active = TRUE);
SET @a_bn2_co3_s2_lead = (SELECT id FROM account WHERE login_id = 'bn2-co3-s2-lead' AND active = TRUE);

-- 명의 20개: (명의자, 부서장 역할, 명의 부서)마다 사용 중인 그 역할이 있고 부서가 사용 중이어야 한다(역할은 (계정, 역할, 부서)가 유일, V2)
SET @ok = (SELECT COUNT(*) FROM directive) = 0
    AND (SELECT COALESCE(MAX(id), 0) FROM directive_audit) < 300001
    AND (SELECT COALESCE(MAX(directive_id), 0) FROM directive_audit) < 300001
    AND @a_sysadmin IS NOT NULL
    AND (SELECT COUNT(*) FROM (VALUES
            ROW(@a_bn1_cmdr, 'BN_CMDR', 1), ROW(@a_bn1_co1_cmdr, 'CO_CMDR', 2), ROW(@a_bn1_co1_s1_lead, 'SEC_LEAD', 5), ROW(@a_bn1_co1_s2_lead, 'SEC_LEAD', 6),
            ROW(@a_bn1_co2_cmdr, 'CO_CMDR', 3), ROW(@a_bn1_co2_s1_lead, 'SEC_LEAD', 7), ROW(@a_bn1_co2_s2_lead, 'SEC_LEAD', 8),
            ROW(@a_bn1_co3_cmdr, 'CO_CMDR', 4), ROW(@a_bn1_co3_s1_lead, 'SEC_LEAD', 9), ROW(@a_bn1_co3_s2_lead, 'SEC_LEAD', 10),
            ROW(@a_bn2_cmdr, 'BN_CMDR', 11), ROW(@a_bn2_co1_cmdr, 'CO_CMDR', 12), ROW(@a_bn2_co1_s1_lead, 'SEC_LEAD', 15), ROW(@a_bn2_co1_s2_lead, 'SEC_LEAD', 16),
            ROW(@a_bn2_co2_cmdr, 'CO_CMDR', 13), ROW(@a_bn2_co2_s1_lead, 'SEC_LEAD', 17), ROW(@a_bn2_co2_s2_lead, 'SEC_LEAD', 18),
            ROW(@a_bn2_co3_cmdr, 'CO_CMDR', 14), ROW(@a_bn2_co3_s1_lead, 'SEC_LEAD', 19), ROW(@a_bn2_co3_s2_lead, 'SEC_LEAD', 20)
         ) AS n (account_id, role_code, department_id)
         JOIN role_assignment r ON r.account_id = n.account_id AND r.role_code = n.role_code AND r.department_id = n.department_id AND r.active
         JOIN department d ON d.id = n.department_id AND d.active) = 20;

-- (번호, 명의 부서, 명의자, 올린 계정, 내용, 만든 시각, 고친 시각, 판). 지시 일자 = 만든 시각의 날짜.
-- 300001~300006은 처음 시연 자료(같은 뜻, 날짜·기한 문구는 기준일에 맞춤). 만든 날은 평일, 기한은 날짜(요일)로 적는다. 대신 올림: 300003·300013·300016(올린 계정 sysadmin → 명의자에게만 "시스템 관리자가 대신 올림")
-- 고침: 300004·300008·300014·300021 ("고침(시각)"). 부서마다 지시 일자가 가장 늦은 건이 [지시사항] 탭 박스에 보인다
INSERT INTO directive (id, department_id, named_account_id, written_by_account_id, content, directive_date, created_at, edited_at, version)
SELECT v.id, v.department_id, v.named, v.writer, v.content, DATE(v.created_at), v.created_at, v.edited_at, v.version FROM (VALUES
    -- 1대대
    ROW(300008, 1, @a_bn1_cmdr, @a_bn1_cmdr, CONCAT('정보통신 장비 보안 점검(비인가 저장매체 연결 여부)을 부서마다 월 1회 실시할 것', CHAR(10), '점검 결과는 업무 등록으로 남기고, 이상이 있으면 그날 보고할 것'), TIMESTAMP('2026-10-19 08:40:00'), TIMESTAMP('2026-10-20 09:15:00'), 1),
    ROW(300001, 1, @a_bn1_cmdr, @a_bn1_cmdr, '동절기 대비 발전기·무정전 전원장치 점검을 11월 13일(금)까지 마치고 결과를 보고할 것', TIMESTAMP('2026-10-26 09:00:00'), NULL, 0),
    -- 1대대 1중대·반
    ROW(300002, 2, @a_bn1_co1_cmdr, @a_bn1_co1_cmdr, CONCAT('야간 통신 점검은 무전기, 유선 전화, 화상회의 장비 순서로 실시할 것', CHAR(10), '점검 뒤 근무 교대 때 인수인계 대장에 결과를 적고 서로 확인할 것'), TIMESTAMP('2026-10-28 08:30:00'), NULL, 0),
    ROW(300003, 2, @a_bn1_co1_cmdr, @a_sysadmin, '오늘 대대 화상회의 전에 화상회의 장비 사전 점검을 마칠 것', TIMESTAMP('2026-11-02 08:20:00'), NULL, 0),
    ROW(300004, 5, @a_bn1_co1_s1_lead, @a_bn1_co1_s1_lead, '무전기 배터리 교체 주기(3개월)를 지키고 교체한 날짜를 장비 비고에 적을 것', TIMESTAMP('2026-10-29 10:00:00'), TIMESTAMP('2026-10-30 11:00:00'), 1),
    ROW(300020, 6, @a_bn1_co1_s2_lead, @a_bn1_co1_s2_lead, '2반 화상회의 단말기는 쓴 뒤에 카메라 덮개를 닫고 전원을 끌 것', TIMESTAMP('2026-10-29 11:00:00'), NULL, 0),
    -- 1대대 2중대·반
    ROW(300009, 3, @a_bn1_co2_cmdr, @a_bn1_co2_cmdr, CONCAT('중대 행정반 PC 백신 갱신 상태를 매주 금요일에 확인할 것', CHAR(10), '확인 결과는 반장이 모아 중대장에게 보고할 것'), TIMESTAMP('2026-10-20 10:00:00'), NULL, 0),
    ROW(300005, 3, @a_bn1_co2_cmdr, @a_bn1_co2_cmdr, '2중대 전화 회선 정리를 이번 주 안에 마칠 것', TIMESTAMP('2026-10-26 13:00:00'), NULL, 0),
    ROW(300023, 3, @a_bn1_co2_cmdr, @a_bn1_co2_cmdr, '2중대 당직 근무표가 바뀌면 근무자 현황에서 확인하고 근무자 본인에게 알릴 것', TIMESTAMP('2026-10-30 16:00:00'), NULL, 0),
    ROW(300015, 7, @a_bn1_co2_s1_lead, @a_bn1_co2_s1_lead, '1반 교환기 점검표를 문서함에 올리고 2반에도 공유할 것', TIMESTAMP('2026-10-26 14:00:00'), NULL, 0),
    ROW(300024, 8, @a_bn1_co2_s2_lead, @a_bn1_co2_s2_lead, '통신실 교대 근무자는 교대 시각(08:00·20:00)을 지키고 무전망 점검 결과를 정보통신 현황에 적을 것', TIMESTAMP('2026-10-30 15:00:00'), NULL, 0),
    -- 1대대 3중대·반
    ROW(300010, 4, @a_bn1_co3_cmdr, @a_bn1_co3_cmdr, '3중대 무전기 배터리 보유 수량을 조사해 10월 말까지 보고할 것', TIMESTAMP('2026-10-21 13:30:00'), NULL, 0),
    ROW(300029, 4, @a_bn1_co3_cmdr, @a_bn1_co3_cmdr, CONCAT('동계 통신장비 운용 지침을 문서함에 올렸으니 반마다 읽고 교육할 것', CHAR(10), '교육한 날짜와 인원은 반장이 모아 11월 6일(금)까지 보고할 것'), TIMESTAMP('2026-10-30 09:30:00'), NULL, 0),
    ROW(300011, 9, @a_bn1_co3_s1_lead, @a_bn1_co3_s1_lead, '1반 사무실 전원 멀티탭 과부하 여부를 점검하고 낡은 멀티탭은 교체 요청할 것', TIMESTAMP('2026-10-22 09:10:00'), NULL, 0),
    ROW(300018, 10, @a_bn1_co3_s2_lead, @a_bn1_co3_s2_lead, '2반은 야전 발전기 시운전을 매주 월요일 오전에 하고 시운전 기록을 문서함에 남길 것', TIMESTAMP('2026-10-28 16:20:00'), NULL, 0),
    -- 2대대
    ROW(300007, 11, @a_bn2_cmdr, @a_bn2_cmdr, '11월 동계 전투준비 기간 전에 중대별 통신망 운용 계획을 10월 23일(금)까지 제출할 것', TIMESTAMP('2026-10-15 09:00:00'), NULL, 0),
    ROW(300014, 11, @a_bn2_cmdr, @a_bn2_cmdr, CONCAT('11월 첫째 주(11-03 ~ 11-06) 대대 지휘소 연습 때 화상회의 장비를 중대마다 1대씩 준비할 것', CHAR(10), '장비 점검은 연습 전날(11-02)까지 마치고 이상 장비는 업무 요청을 올릴 것'), TIMESTAMP('2026-10-26 09:00:00'), TIMESTAMP('2026-10-27 08:20:00'), 1),
    ROW(300006, 11, @a_bn2_cmdr, @a_bn2_cmdr, '2대대 보안 점검을 다음 주 금요일(11-06)까지 실시할 것', TIMESTAMP('2026-10-30 10:00:00'), NULL, 0),
    ROW(300025, 11, @a_bn2_cmdr, @a_bn2_cmdr, '오늘 오후 대대 정보통신 회의 전까지 중대별 고장·정비입고 장비를 장비 목록에서 확인해 올 것', TIMESTAMP('2026-11-02 08:00:00'), NULL, 0),
    -- 2대대 1중대·반
    ROW(300012, 12, @a_bn2_co1_cmdr, @a_bn2_co1_cmdr, '중대 상황실 근무자는 근무 교대 때 통신 장비 상태를 인수인계 대장에 적을 것', TIMESTAMP('2026-10-23 08:30:00'), NULL, 0),
    ROW(300021, 12, @a_bn2_co1_cmdr, @a_bn2_co1_cmdr, '전장망 구간 점검 결과는 정보통신 현황의 전장망 박스에 그날 바로 반영할 것', TIMESTAMP('2026-10-30 09:30:00'), TIMESTAMP('2026-10-30 17:40:00'), 1),
    ROW(300017, 15, @a_bn2_co1_s1_lead, @a_bn2_co1_s1_lead, '1반 사무실 스위치 포트 점검을 이번 주 금요일(10-30)까지 마치고 불량 포트는 업무 요청을 올릴 것', TIMESTAMP('2026-10-28 09:00:00'), NULL, 0),
    ROW(300022, 16, @a_bn2_co1_s2_lead, @a_bn2_co1_s2_lead, '2반 통신소 교대 근무자는 야간 근무 전에 무전기 송수신 시험을 할 것', TIMESTAMP('2026-10-30 14:10:00'), NULL, 0),
    -- 2대대 2중대·반
    ROW(300013, 13, @a_bn2_co2_cmdr, @a_sysadmin, '2중대 전장망 단말기 점검을 다음 주 수요일(10-28)까지 마치고 이상 장비는 정비입고 처리할 것', TIMESTAMP('2026-10-23 15:00:00'), NULL, 0),
    ROW(300019, 17, @a_bn2_co2_s1_lead, @a_bn2_co2_s1_lead, '1반 노트북 운영체제 업데이트를 이번 주 금요일(10-30)까지 마칠 것', TIMESTAMP('2026-10-29 08:50:00'), NULL, 0),
    ROW(300027, 18, @a_bn2_co2_s2_lead, @a_bn2_co2_s2_lead, '2반 차량용 무전기 거치대 고정 상태를 이번 주 안에 점검하고 풀린 것은 그날 고칠 것', TIMESTAMP('2026-10-29 15:00:00'), NULL, 0),
    -- 2대대 3중대·반
    ROW(300016, 14, @a_bn2_co3_cmdr, @a_sysadmin, '3중대 통신창고를 정리하고 장비 대장과 실제 수량을 맞출 것', TIMESTAMP('2026-10-27 10:30:00'), NULL, 0),
    ROW(300028, 20, @a_bn2_co3_s2_lead, @a_bn2_co3_s2_lead, '2반 행정용 PC 화면 보호기 설정(10분)을 모두 확인할 것', TIMESTAMP('2026-10-27 11:00:00'), NULL, 0),
    ROW(300026, 19, @a_bn2_co3_s1_lead, @a_bn2_co3_s1_lead, '1반 CCTV 녹화기 저장 공간을 확인하고 80%를 넘으면 보고할 것', TIMESTAMP('2026-11-02 08:05:00'), NULL, 0)
) AS v (id, department_id, named, writer, content, created_at, edited_at, version)
WHERE @ok ORDER BY v.id;

-- 감사 기록: 위에서 넣은 건마다 CREATE(올린 계정), 고친 건에 UPDATE(명의자). 번호는 300001부터 기록 시각 순
INSERT INTO directive_audit (id, directive_id, action, account_id, acted_at)
SELECT 300000 + ROW_NUMBER() OVER (ORDER BY e.acted_at, e.directive_id, e.action), e.directive_id, e.action, e.account_id, e.acted_at
FROM (
    SELECT id AS directive_id, 'CREATE' AS action, written_by_account_id AS account_id, created_at AS acted_at FROM directive WHERE id BETWEEN 300001 AND 300099
    UNION ALL
    SELECT id, 'UPDATE', named_account_id, edited_at FROM directive WHERE id BETWEEN 300001 AND 300099 AND edited_at IS NOT NULL
) e
WHERE @ok;

SELECT @ok AS directive_demo_applied, (SELECT COUNT(*) FROM directive WHERE id >= 300001) AS directives,
       (SELECT COUNT(*) FROM directive_audit WHERE id >= 300001) AS directive_audits;
