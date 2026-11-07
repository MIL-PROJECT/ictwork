-- 시연용 정보통신 현황 자료 (명세 7장, G-144, slices/15 15장, slices/21 9장). scripts/demo-ict-status-data.py가 만든 파일이다. 직접 고치지 않는다
-- 박스 9개(모두 정보통신상황실), 판 98개, 공개 범위 17줄, 담당자 3명, 범위 카드 2개. 값은 모두 가상(NFR-002)
-- 이름이 R__seed_work_ict_status_demo라 초기 데이터·문서함 시연 자료 다음에 돈다. 박스가 있거나 번호 범위(500001~509999)가 쓰였거나 쓸 계정·부서가 없거나
-- 상황실장(bn1-hq-s1-lead)에게 정보통신상황실 부서 관리자 역할이 없거나(박스는 그 부서 관리자가 추가, REQ-503) 담당자가 상황실 소속이 아니면 전체를 건너뛴다
-- 공개 범위는 정보통신대대·근무지원대대(하위 포함). 공개 범위가 없으면 상황실 소속만 본다(REQ-503)
-- 기록 일(ict_record_job)을 넣어 두면 서버가 켜질 때 기록 일꾼이 문서함 기록 파일을 만든다
-- 시각은 시연 기준 시각(KST, 고정)에서 뺀 값이다. 사람이 한 일은 기준 시각 전 평일 07:30~19:30

SET @base = TIMESTAMP('2026-11-19 09:00:00');
SET @a_bn1_hq_s1_lead = (SELECT id FROM account WHERE login_id = 'bn1-hq-s1-lead' AND active = TRUE);
SET @a_bn1_hq_s1_m1 = (SELECT id FROM account WHERE login_id = 'bn1-hq-s1-m1' AND active = TRUE);
SET @a_bn1_hq_s1_m2 = (SELECT id FROM account WHERE login_id = 'bn1-hq-s1-m2' AND active = TRUE);
SET @a_bn1_hq_s1_m3 = (SELECT id FROM account WHERE login_id = 'bn1-hq-s1-m3' AND active = TRUE);
SET @a_bn2_co1_cmdr = (SELECT id FROM account WHERE login_id = 'bn2-co1-cmdr' AND active = TRUE);
SET @ok = (SELECT COUNT(*) FROM ict_box) = 0
    AND (SELECT COALESCE(MAX(id), 0) FROM ict_box_schema) < 500001
    AND (SELECT COALESCE(MAX(id), 0) FROM ict_box_table) < 500001
    AND (SELECT COALESCE(MAX(id), 0) FROM ict_box_revision) < 500001
    AND (SELECT COALESCE(MAX(id), 0) FROM ict_box_scope) < 500001
    AND (SELECT COALESCE(MAX(id), 0) FROM scope_card) < 500001
    AND (SELECT COALESCE(MAX(id), 0) FROM scope_card_entry) < 500001
    AND (SELECT COUNT(*) FROM department WHERE id IN (1, 11, 22) AND active = TRUE) = 3
    AND @a_bn1_hq_s1_lead IS NOT NULL
    AND @a_bn1_hq_s1_m1 IS NOT NULL
    AND @a_bn1_hq_s1_m2 IS NOT NULL
    AND @a_bn1_hq_s1_m3 IS NOT NULL
    AND @a_bn2_co1_cmdr IS NOT NULL
    AND EXISTS (SELECT 1 FROM role_assignment r WHERE r.account_id = @a_bn1_hq_s1_lead AND r.role_code = 'DEPT_ADMIN' AND r.department_id = 22 AND r.active)
    AND (SELECT COUNT(*) FROM account a JOIN personnel p ON p.id = a.personnel_id WHERE a.id IN (@a_bn1_hq_s1_lead, @a_bn1_hq_s1_m1, @a_bn1_hq_s1_m2, @a_bn1_hq_s1_m3) AND p.active = TRUE AND p.department_id = 22) = 4;

INSERT INTO ict_box (id, department_id, title, title_key, summary_mode, summary_text, summary_field, color_mode, color, schema_id, values_json, tables_json,
    last_revision_no, version, created_by_account_id, created_at, updated_by_account_id, updated_at)
SELECT * FROM (VALUES
    ROW(500001, 22, 'CPCON', 'cpcon', 'FIELD', NULL, 'stage', 'FIELD', NULL, 500102, '{"stage":{"option":"s4"},"note":"정기 점검 기간 연장(11-27까지)"}', '{}', 7, 6, @a_bn1_hq_s1_lead, @base - INTERVAL 131030 MINUTE, @a_bn1_hq_s1_lead, @base - INTERVAL 2900 MINUTE),
    ROW(500002, 22, '위기경보단계', '위기경보단계', 'FIELD', NULL, 'stage', 'FIELD', NULL, 500104, '{"stage":{"option":"a2"}}', '{}', 5, 4, @a_bn1_hq_s1_lead, @base - INTERVAL 129570 MINUTE, @a_bn1_hq_s1_lead, @base - INTERVAL 4350 MINUTE),
    ROW(500003, 22, '전장망', '전장망', 'FIELD', NULL, 'status', 'FIELD', NULL, 500106, '{"status":{"option":"lim"},"lines":"9"}', '{"tbl":500206}', 40, 39, @a_bn1_hq_s1_lead, @base - INTERVAL 130980 MINUTE, @a_bn1_hq_s1_lead, @base - INTERVAL 40 MINUTE),
    ROW(500004, 22, '인터넷망', '인터넷망', 'FIELD', NULL, 'status', 'FIELD', NULL, 500108, '{"status":{"option":"lim"},"lines":"7","note":"방화벽 1호 정비 중, 근무지원대대 교육장 구간 속도 저하"}', '{}', 15, 14, @a_bn1_hq_s1_lead, @base - INTERVAL 115200 MINUTE, @a_bn1_hq_s1_m2, @base - INTERVAL 950 MINUTE),
    ROW(500005, 22, '화상회의', '화상회의', 'FIELD', NULL, 'status', 'FIELD', NULL, 500110, '{"status":{"option":"chk"},"units":"3","memo":"정보통신대대 회의실 단말 교체 검토(11-20까지)"}', '{}', 9, 8, @a_bn1_hq_s1_lead, @base - INTERVAL 94980 MINUTE, @a_bn1_hq_s1_m3, @base - INTERVAL 4290 MINUTE),
    ROW(500006, 22, '구내 전화망', '구내 전화망', 'FIELD', NULL, 'op', 'FIELD', NULL, 500112, '{"op":{"option":"ok"},"note":"선로 공사 완료"}', '{}', 6, 5, @a_bn1_hq_s1_lead, @base - INTERVAL 123840 MINUTE, @a_bn1_hq_s1_lead, @base - INTERVAL 34260 MINUTE),
    ROW(500007, 22, 'GPS 전파교란', 'gps 전파교란', 'FIELD', NULL, 'jam', 'FIELD', NULL, 500114, '{"jam":{"option":"yes"},"at":"06:40","act":"수신기 전원 재시작, 상급 부대 보고, 복구 확인 중"}', '{}', 6, 5, @a_bn1_hq_s1_lead, @base - INTERVAL 119460 MINUTE, @a_bn1_hq_s1_lead, @base - INTERVAL 1510 MINUTE),
    ROW(500008, 22, '무전망', '무전망', 'FIELD', NULL, 'link', 'FIELD', NULL, 500116, '{"link":{"option":"poor"},"at":"08:30","act":"야간 교신 잡음 재발, 중계기 미운용으로 원거리 교신 불량"}', '{}', 6, 5, @a_bn1_hq_s1_lead, @base - INTERVAL 84960 MINUTE, @a_bn1_hq_s1_lead, @base - INTERVAL 2910 MINUTE),
    ROW(500009, 22, '통신실 전원', '통신실 전원', 'FIELD', NULL, 'op', 'FIELD', NULL, 500118, '{"op":{"option":"ok"},"note":"점검 완료"}', '{}', 4, 3, @a_bn1_hq_s1_lead, @base - INTERVAL 112260 MINUTE, @a_bn1_hq_s1_lead, @base - INTERVAL 38520 MINUTE)
) AS v WHERE @ok;

INSERT INTO ict_box_schema (id, box_id, sha256, definition, created_at)
SELECT * FROM (VALUES
    ROW(500101, 500001, '01f2f9c28aa1d4d36a81ff042620b6677d25bf07c2bf4acc37b58658778a4fca', '{"fields":[]}', @base - INTERVAL 131030 MINUTE),
    ROW(500102, 500001, 'fa76b4ea6c9c123fa5542a1b67738a8b9047a3f54e75b999766711e5f5285dd7', '{"fields":[{"key":"stage","kind":"DROPDOWN","title":"단계","description":"","options":[{"id":"s5","label":"5단계","color":"GREEN"},{"id":"s4","label":"4단계","color":"BLUE"},{"id":"s3","label":"3단계","color":"YELLOW"},{"id":"s2","label":"2단계","color":"ORANGE"},{"id":"s1","label":"1단계","color":"RED"}],"allowOther":false,"rows":[],"columns":[],"scaleMin":null,"scaleMax":null,"minLabel":"","maxLabel":"","validation":null,"table":null},{"key":"note","kind":"LONG_TEXT","title":"비고","description":"","options":[],"allowOther":false,"rows":[],"columns":[],"scaleMin":null,"scaleMax":null,"minLabel":"","maxLabel":"","validation":null,"table":null}]}', @base - INTERVAL 131020 MINUTE),
    ROW(500103, 500002, '01f2f9c28aa1d4d36a81ff042620b6677d25bf07c2bf4acc37b58658778a4fca', '{"fields":[]}', @base - INTERVAL 129570 MINUTE),
    ROW(500104, 500002, '1725c52d113a752c7408708d11c742bc06491e3a56b6af8dbf337a63c1bc311e', '{"fields":[{"key":"stage","kind":"DROPDOWN","title":"단계","description":"","options":[{"id":"a1","label":"관심","color":"BLUE"},{"id":"a2","label":"주의","color":"YELLOW"},{"id":"a3","label":"경계","color":"ORANGE"},{"id":"a4","label":"심각","color":"RED"}],"allowOther":false,"rows":[],"columns":[],"scaleMin":null,"scaleMax":null,"minLabel":"","maxLabel":"","validation":null,"table":null}]}', @base - INTERVAL 129560 MINUTE),
    ROW(500105, 500003, '01f2f9c28aa1d4d36a81ff042620b6677d25bf07c2bf4acc37b58658778a4fca', '{"fields":[]}', @base - INTERVAL 130980 MINUTE),
    ROW(500106, 500003, 'e668c5635113678dca4457c3866803c8a63548ef03eef15c19f79f4b120a7686', '{"fields":[{"key":"status","kind":"DROPDOWN","title":"상태","description":"","options":[{"id":"ok","label":"정상","color":"GREEN"},{"id":"lim","label":"제한","color":"YELLOW"},{"id":"bad","label":"불능","color":"RED"}],"allowOther":false,"rows":[],"columns":[],"scaleMin":null,"scaleMax":null,"minLabel":"","maxLabel":"","validation":null,"table":null},{"key":"lines","kind":"NUMBER","title":"가용 회선 수","description":"","options":[],"allowOther":false,"rows":[],"columns":[],"scaleMin":null,"scaleMax":null,"minLabel":"","maxLabel":"","validation":null,"table":null},{"key":"tbl","kind":"TABLE","title":"구간별 상태","description":"","options":[],"allowOther":false,"rows":[],"columns":[],"scaleMin":null,"scaleMax":null,"minLabel":"","maxLabel":"","validation":null,"table":{"columns":[{"id":"sec","title":"구간","kind":"SHORT_TEXT","options":[]},{"id":"st","title":"상태","kind":"DROPDOWN","options":[{"id":"ok","label":"정상","goTo":null},{"id":"lim","label":"제한","goTo":null},{"id":"bad","label":"불능","goTo":null}]},{"id":"dt","title":"점검일","kind":"DATE","options":[]}]}}]}', @base - INTERVAL 130950 MINUTE),
    ROW(500107, 500004, '01f2f9c28aa1d4d36a81ff042620b6677d25bf07c2bf4acc37b58658778a4fca', '{"fields":[]}', @base - INTERVAL 115200 MINUTE),
    ROW(500108, 500004, '32daa9d150940d247b00fc2b4f55f3843c63eb0af39c9ee83aa28a5ac84f62dc', '{"fields":[{"key":"status","kind":"DROPDOWN","title":"상태","description":"","options":[{"id":"ok","label":"정상","color":"GREEN"},{"id":"lim","label":"제한","color":"YELLOW"},{"id":"bad","label":"불능","color":"RED"}],"allowOther":false,"rows":[],"columns":[],"scaleMin":null,"scaleMax":null,"minLabel":"","maxLabel":"","validation":null,"table":null},{"key":"lines","kind":"NUMBER","title":"가용 회선 수","description":"","options":[],"allowOther":false,"rows":[],"columns":[],"scaleMin":null,"scaleMax":null,"minLabel":"","maxLabel":"","validation":null,"table":null},{"key":"note","kind":"LONG_TEXT","title":"비고","description":"","options":[],"allowOther":false,"rows":[],"columns":[],"scaleMin":null,"scaleMax":null,"minLabel":"","maxLabel":"","validation":null,"table":null}]}', @base - INTERVAL 115190 MINUTE),
    ROW(500109, 500005, '01f2f9c28aa1d4d36a81ff042620b6677d25bf07c2bf4acc37b58658778a4fca', '{"fields":[]}', @base - INTERVAL 94980 MINUTE),
    ROW(500110, 500005, '5bddd535f6b13d4965f98da8293e7ad6dc0bfd33e3b418af415710f538e7f688', '{"fields":[{"key":"status","kind":"DROPDOWN","title":"상태","description":"","options":[{"id":"ok","label":"정상","color":"GREEN"},{"id":"chk","label":"점검 중","color":"BLUE"},{"id":"bad","label":"장애","color":"RED"}],"allowOther":false,"rows":[],"columns":[],"scaleMin":null,"scaleMax":null,"minLabel":"","maxLabel":"","validation":null,"table":null},{"key":"units","kind":"NUMBER","title":"연결 단말 수","description":"","options":[],"allowOther":false,"rows":[],"columns":[],"scaleMin":null,"scaleMax":null,"minLabel":"","maxLabel":"","validation":null,"table":null},{"key":"memo","kind":"SHORT_TEXT","title":"점검 메모","description":"","options":[],"allowOther":false,"rows":[],"columns":[],"scaleMin":null,"scaleMax":null,"minLabel":"","maxLabel":"","validation":null,"table":null}]}', @base - INTERVAL 94970 MINUTE),
    ROW(500111, 500006, '01f2f9c28aa1d4d36a81ff042620b6677d25bf07c2bf4acc37b58658778a4fca', '{"fields":[]}', @base - INTERVAL 123840 MINUTE),
    ROW(500112, 500006, '64b24987134eed91fcd23d4b948e13238b0f2b9fb72591a573271d2b40670897', '{"fields":[{"key":"op","kind":"MULTIPLE_CHOICE","title":"운용","description":"","options":[{"id":"ok","label":"정상","color":"GREEN"},{"id":"part","label":"일부 제한","color":"YELLOW"}],"allowOther":false,"rows":[],"columns":[],"scaleMin":null,"scaleMax":null,"minLabel":"","maxLabel":"","validation":null,"table":null},{"key":"note","kind":"SHORT_TEXT","title":"특이사항","description":"","options":[],"allowOther":false,"rows":[],"columns":[],"scaleMin":null,"scaleMax":null,"minLabel":"","maxLabel":"","validation":null,"table":null}]}', @base - INTERVAL 123830 MINUTE),
    ROW(500113, 500007, '01f2f9c28aa1d4d36a81ff042620b6677d25bf07c2bf4acc37b58658778a4fca', '{"fields":[]}', @base - INTERVAL 119460 MINUTE),
    ROW(500114, 500007, '8150dc3709d52a3ac912a3f038f54d6364be0ae08b0afd5db452e161e0f1c008', '{"fields":[{"key":"jam","kind":"MULTIPLE_CHOICE","title":"교란","description":"","options":[{"id":"none","label":"없음","color":"GREEN"},{"id":"yes","label":"있음","color":"RED"}],"allowOther":false,"rows":[],"columns":[],"scaleMin":null,"scaleMax":null,"minLabel":"","maxLabel":"","validation":null,"table":null},{"key":"at","kind":"TIME","title":"발생 시각","description":"","options":[],"allowOther":false,"rows":[],"columns":[],"scaleMin":null,"scaleMax":null,"minLabel":"","maxLabel":"","validation":null,"table":null},{"key":"act","kind":"LONG_TEXT","title":"조치","description":"","options":[],"allowOther":false,"rows":[],"columns":[],"scaleMin":null,"scaleMax":null,"minLabel":"","maxLabel":"","validation":null,"table":null}]}', @base - INTERVAL 119450 MINUTE),
    ROW(500115, 500008, '01f2f9c28aa1d4d36a81ff042620b6677d25bf07c2bf4acc37b58658778a4fca', '{"fields":[]}', @base - INTERVAL 84960 MINUTE),
    ROW(500116, 500008, '90ba153c667e31d43f299ca907d90f2797a509f55445b62d2fbdcf2462f8f8e0', '{"fields":[{"key":"link","kind":"MULTIPLE_CHOICE","title":"교신","description":"","options":[{"id":"good","label":"양호","color":"GREEN"},{"id":"poor","label":"불량","color":"ORANGE"}],"allowOther":false,"rows":[],"columns":[],"scaleMin":null,"scaleMax":null,"minLabel":"","maxLabel":"","validation":null,"table":null},{"key":"at","kind":"TIME","title":"점검 시각","description":"","options":[],"allowOther":false,"rows":[],"columns":[],"scaleMin":null,"scaleMax":null,"minLabel":"","maxLabel":"","validation":null,"table":null},{"key":"act","kind":"LONG_TEXT","title":"조치","description":"","options":[],"allowOther":false,"rows":[],"columns":[],"scaleMin":null,"scaleMax":null,"minLabel":"","maxLabel":"","validation":null,"table":null}]}', @base - INTERVAL 84950 MINUTE),
    ROW(500117, 500009, '01f2f9c28aa1d4d36a81ff042620b6677d25bf07c2bf4acc37b58658778a4fca', '{"fields":[]}', @base - INTERVAL 112260 MINUTE),
    ROW(500118, 500009, '64b24987134eed91fcd23d4b948e13238b0f2b9fb72591a573271d2b40670897', '{"fields":[{"key":"op","kind":"MULTIPLE_CHOICE","title":"운용","description":"","options":[{"id":"ok","label":"정상","color":"GREEN"},{"id":"part","label":"일부 제한","color":"YELLOW"}],"allowOther":false,"rows":[],"columns":[],"scaleMin":null,"scaleMax":null,"minLabel":"","maxLabel":"","validation":null,"table":null},{"key":"note","kind":"SHORT_TEXT","title":"특이사항","description":"","options":[],"allowOther":false,"rows":[],"columns":[],"scaleMin":null,"scaleMax":null,"minLabel":"","maxLabel":"","validation":null,"table":null}]}', @base - INTERVAL 112250 MINUTE)
) AS v WHERE @ok;

INSERT INTO ict_box_table (id, box_id, sha256, data)
SELECT t.id, t.box_id, SHA2(t.data, 256), t.data FROM (
    SELECT 500201 AS id, 500003 AS box_id, CONCAT('[', '{"sec":"통신실-1중대","st":"ok","dt":"', DATE_FORMAT(@base - INTERVAL 131040 MINUTE, '%Y-%m-%d'), '"}', ',', '{"sec":"통신실-2중대","st":"ok","dt":"', DATE_FORMAT(@base - INTERVAL 132480 MINUTE, '%Y-%m-%d'), '"}', ',', '{"sec":"통신실-3중대","st":"ok","dt":"', DATE_FORMAT(@base - INTERVAL 133920 MINUTE, '%Y-%m-%d'), '"}', ',', '{"sec":"통신실-본부중대","st":"ok","dt":"', DATE_FORMAT(@base - INTERVAL 139680 MINUTE, '%Y-%m-%d'), '"}', ',', '{"sec":"통신실-근무지원대대","st":"ok","dt":"', DATE_FORMAT(@base - INTERVAL 141120 MINUTE, '%Y-%m-%d'), '"}', ']') AS data
    UNION ALL
    SELECT 500202 AS id, 500003 AS box_id, CONCAT('[', '{"sec":"통신실-1중대","st":"ok","dt":"', DATE_FORMAT(@base - INTERVAL 105120 MINUTE, '%Y-%m-%d'), '"}', ',', '{"sec":"통신실-2중대","st":"lim","dt":"', DATE_FORMAT(@base - INTERVAL 109440 MINUTE, '%Y-%m-%d'), '"}', ',', '{"sec":"통신실-3중대","st":"ok","dt":"', DATE_FORMAT(@base - INTERVAL 110880 MINUTE, '%Y-%m-%d'), '"}', ',', '{"sec":"통신실-본부중대","st":"ok","dt":"', DATE_FORMAT(@base - INTERVAL 112320 MINUTE, '%Y-%m-%d'), '"}', ',', '{"sec":"통신실-근무지원대대","st":"ok","dt":"', DATE_FORMAT(@base - INTERVAL 113760 MINUTE, '%Y-%m-%d'), '"}', ']') AS data
    UNION ALL
    SELECT 500203 AS id, 500003 AS box_id, CONCAT('[', '{"sec":"통신실-1중대","st":"ok","dt":"', DATE_FORMAT(@base - INTERVAL 72000 MINUTE, '%Y-%m-%d'), '"}', ',', '{"sec":"통신실-2중대","st":"bad","dt":"', DATE_FORMAT(@base - INTERVAL 73440 MINUTE, '%Y-%m-%d'), '"}', ',', '{"sec":"통신실-3중대","st":"lim","dt":"', DATE_FORMAT(@base - INTERVAL 74880 MINUTE, '%Y-%m-%d'), '"}', ',', '{"sec":"통신실-본부중대","st":"ok","dt":"', DATE_FORMAT(@base - INTERVAL 82080 MINUTE, '%Y-%m-%d'), '"}', ',', '{"sec":"통신실-근무지원대대","st":"ok","dt":"', DATE_FORMAT(@base - INTERVAL 83520 MINUTE, '%Y-%m-%d'), '"}', ']') AS data
    UNION ALL
    SELECT 500204 AS id, 500003 AS box_id, CONCAT('[', '{"sec":"통신실-1중대","st":"ok","dt":"', DATE_FORMAT(@base - INTERVAL 60480 MINUTE, '%Y-%m-%d'), '"}', ',', '{"sec":"통신실-2중대","st":"ok","dt":"', DATE_FORMAT(@base - INTERVAL 61920 MINUTE, '%Y-%m-%d'), '"}', ',', '{"sec":"통신실-3중대","st":"ok","dt":"', DATE_FORMAT(@base - INTERVAL 63360 MINUTE, '%Y-%m-%d'), '"}', ',', '{"sec":"통신실-본부중대","st":"ok","dt":"', DATE_FORMAT(@base - INTERVAL 69120 MINUTE, '%Y-%m-%d'), '"}', ',', '{"sec":"통신실-근무지원대대","st":"ok","dt":"', DATE_FORMAT(@base - INTERVAL 70560 MINUTE, '%Y-%m-%d'), '"}', ']') AS data
    UNION ALL
    SELECT 500205 AS id, 500003 AS box_id, CONCAT('[', '{"sec":"통신실-1중대","st":"ok","dt":"', DATE_FORMAT(@base - INTERVAL 1440 MINUTE, '%Y-%m-%d'), '"}', ',', '{"sec":"통신실-2중대","st":"ok","dt":"', DATE_FORMAT(@base - INTERVAL 2880 MINUTE, '%Y-%m-%d'), '"}', ',', '{"sec":"통신실-3중대","st":"ok","dt":"', DATE_FORMAT(@base - INTERVAL 4320 MINUTE, '%Y-%m-%d'), '"}', ',', '{"sec":"통신실-본부중대","st":"ok","dt":"', DATE_FORMAT(@base - INTERVAL 8640 MINUTE, '%Y-%m-%d'), '"}', ',', '{"sec":"통신실-근무지원대대","st":"ok","dt":"', DATE_FORMAT(@base - INTERVAL 10080 MINUTE, '%Y-%m-%d'), '"}', ']') AS data
    UNION ALL
    SELECT 500206 AS id, 500003 AS box_id, CONCAT('[', '{"sec":"통신실-1중대","st":"ok","dt":"', DATE_FORMAT(@base - INTERVAL 0 MINUTE, '%Y-%m-%d'), '"}', ',', '{"sec":"통신실-2중대","st":"ok","dt":"', DATE_FORMAT(@base - INTERVAL 1440 MINUTE, '%Y-%m-%d'), '"}', ',', '{"sec":"통신실-3중대","st":"lim","dt":"', DATE_FORMAT(@base - INTERVAL 2880 MINUTE, '%Y-%m-%d'), '"}', ',', '{"sec":"통신실-본부중대","st":"ok","dt":"', DATE_FORMAT(@base - INTERVAL 4320 MINUTE, '%Y-%m-%d'), '"}', ',', '{"sec":"통신실-근무지원대대","st":"ok","dt":"', DATE_FORMAT(@base - INTERVAL 8640 MINUTE, '%Y-%m-%d'), '"}', ']') AS data
) AS t WHERE @ok;

INSERT INTO ict_box_revision (id, box_id, revision_no, changed_by_account_id, changed_at, title, summary_mode, summary_text, summary_field,
    color_mode, color, shown_summary, shown_color, schema_id, values_json, tables_json)
SELECT * FROM (VALUES
    ROW(500301, 500001, 1, @a_bn1_hq_s1_lead, @base - INTERVAL 131030 MINUTE, 'CPCON', 'TEXT', '4단계', NULL, 'FIXED', 'BLUE', '4단계', 'BLUE', 500101, '{}', '{}'),
    ROW(500302, 500001, 2, @a_bn1_hq_s1_lead, @base - INTERVAL 131020 MINUTE, 'CPCON', 'FIELD', NULL, 'stage', 'FIELD', NULL, '4단계', 'BLUE', 500102, '{"stage":{"option":"s4"}}', '{}'),
    ROW(500303, 500001, 3, @a_bn1_hq_s1_lead, @base - INTERVAL 103620 MINUTE, 'CPCON', 'FIELD', NULL, 'stage', 'FIELD', NULL, '3단계', 'YELLOW', 500102, '{"stage":{"option":"s3"},"note":"합동 훈련 기간 상향"}', '{}'),
    ROW(500304, 500001, 4, @a_bn1_hq_s1_lead, @base - INTERVAL 81720 MINUTE, 'CPCON', 'FIELD', NULL, 'stage', 'FIELD', NULL, '4단계', 'BLUE', 500102, '{"stage":{"option":"s4"}}', '{}'),
    ROW(500305, 500001, 5, @a_bn1_hq_s1_lead, @base - INTERVAL 44640 MINUTE, 'CPCON', 'FIELD', NULL, 'stage', 'FIELD', NULL, '5단계', 'GREEN', 500102, '{"stage":{"option":"s5"},"note":"평시 전환"}', '{}'),
    ROW(500306, 500001, 6, @a_bn1_hq_s1_lead, @base - INTERVAL 18630 MINUTE, 'CPCON', 'FIELD', NULL, 'stage', 'FIELD', NULL, '4단계', 'BLUE', 500102, '{"stage":{"option":"s4"},"note":"정기 점검 기간"}', '{}'),
    ROW(500307, 500001, 7, @a_bn1_hq_s1_lead, @base - INTERVAL 2900 MINUTE, 'CPCON', 'FIELD', NULL, 'stage', 'FIELD', NULL, '4단계', 'BLUE', 500102, '{"stage":{"option":"s4"},"note":"정기 점검 기간 연장(11-27까지)"}', '{}'),
    ROW(500308, 500002, 1, @a_bn1_hq_s1_lead, @base - INTERVAL 129570 MINUTE, '위기경보단계', 'TEXT', '관심', NULL, 'FIXED', 'BLUE', '관심', 'BLUE', 500103, '{}', '{}'),
    ROW(500309, 500002, 2, @a_bn1_hq_s1_lead, @base - INTERVAL 129560 MINUTE, '위기경보단계', 'FIELD', NULL, 'stage', 'FIELD', NULL, '관심', 'BLUE', 500104, '{"stage":{"option":"a1"}}', '{}'),
    ROW(500310, 500002, 3, @a_bn1_hq_s1_lead, @base - INTERVAL 60180 MINUTE, '위기경보단계', 'FIELD', NULL, 'stage', 'FIELD', NULL, '주의', 'YELLOW', 500104, '{"stage":{"option":"a2"}}', '{}'),
    ROW(500311, 500002, 4, @a_bn1_hq_s1_lead, @base - INTERVAL 22980 MINUTE, '위기경보단계', 'FIELD', NULL, 'stage', 'FIELD', NULL, '관심', 'BLUE', 500104, '{"stage":{"option":"a1"}}', '{}'),
    ROW(500312, 500002, 5, @a_bn1_hq_s1_lead, @base - INTERVAL 4350 MINUTE, '위기경보단계', 'FIELD', NULL, 'stage', 'FIELD', NULL, '주의', 'YELLOW', 500104, '{"stage":{"option":"a2"}}', '{}'),
    ROW(500313, 500003, 1, @a_bn1_hq_s1_lead, @base - INTERVAL 130980 MINUTE, '전장망', 'TEXT', '정상', NULL, 'FIXED', 'GREEN', '정상', 'GREEN', 500105, '{}', '{}'),
    ROW(500314, 500003, 2, @a_bn1_hq_s1_lead, @base - INTERVAL 130950 MINUTE, '전장망', 'FIELD', NULL, 'status', 'FIELD', NULL, '정상', 'GREEN', 500106, '{"status":{"option":"ok"},"lines":"12"}', '{"tbl":500201}'),
    ROW(500315, 500003, 3, @a_bn1_hq_s1_m1, @base - INTERVAL 129320 MINUTE, '전장망', 'FIELD', NULL, 'status', 'FIELD', NULL, '정상', 'GREEN', 500106, '{"status":{"option":"ok"},"lines":"11"}', '{"tbl":500201}'),
    ROW(500316, 500003, 4, @a_bn1_hq_s1_lead, @base - INTERVAL 123400 MINUTE, '전장망', 'FIELD', NULL, 'status', 'FIELD', NULL, '정상', 'GREEN', 500106, '{"status":{"option":"ok"},"lines":"12"}', '{"tbl":500201}'),
    ROW(500317, 500003, 5, @a_bn1_hq_s1_m1, @base - INTERVAL 122390 MINUTE, '전장망', 'FIELD', NULL, 'status', 'FIELD', NULL, '정상', 'GREEN', 500106, '{"status":{"option":"ok"},"lines":"11"}', '{"tbl":500201}'),
    ROW(500318, 500003, 6, @a_bn1_hq_s1_lead, @base - INTERVAL 119240 MINUTE, '전장망', 'FIELD', NULL, 'status', 'FIELD', NULL, '정상', 'GREEN', 500106, '{"status":{"option":"ok"},"lines":"12"}', '{"tbl":500201}'),
    ROW(500319, 500003, 7, @a_bn1_hq_s1_m1, @base - INTERVAL 113320 MINUTE, '전장망', 'FIELD', NULL, 'status', 'FIELD', NULL, '정상', 'GREEN', 500106, '{"status":{"option":"ok"},"lines":"11"}', '{"tbl":500201}'),
    ROW(500320, 500003, 8, @a_bn1_hq_s1_lead, @base - INTERVAL 112310 MINUTE, '전장망', 'FIELD', NULL, 'status', 'FIELD', NULL, '제한', 'YELLOW', 500106, '{"status":{"option":"lim"},"lines":"9"}', '{"tbl":500201}'),
    ROW(500321, 500003, 9, @a_bn1_hq_s1_m1, @base - INTERVAL 109160 MINUTE, '전장망', 'FIELD', NULL, 'status', 'FIELD', NULL, '제한', 'YELLOW', 500106, '{"status":{"option":"lim"},"lines":"8"}', '{"tbl":500201}'),
    ROW(500322, 500003, 10, @a_bn1_hq_s1_lead, @base - INTERVAL 104680 MINUTE, '전장망', 'FIELD', NULL, 'status', 'FIELD', NULL, '정상', 'GREEN', 500106, '{"status":{"option":"ok"},"lines":"11"}', '{"tbl":500202}'),
    ROW(500323, 500003, 11, @a_bn1_hq_s1_m1, @base - INTERVAL 102230 MINUTE, '전장망', 'FIELD', NULL, 'status', 'FIELD', NULL, '정상', 'GREEN', 500106, '{"status":{"option":"ok"},"lines":"12"}', '{"tbl":500202}'),
    ROW(500324, 500003, 12, @a_bn1_hq_s1_lead, @base - INTERVAL 99080 MINUTE, '전장망', 'FIELD', NULL, 'status', 'FIELD', NULL, '정상', 'GREEN', 500106, '{"status":{"option":"ok"},"lines":"11"}', '{"tbl":500202}'),
    ROW(500325, 500003, 13, @a_bn1_hq_s1_m1, @base - INTERVAL 94600 MINUTE, '전장망', 'FIELD', NULL, 'status', 'FIELD', NULL, '정상', 'GREEN', 500106, '{"status":{"option":"ok"},"lines":"12"}', '{"tbl":500202}'),
    ROW(500326, 500003, 14, @a_bn1_hq_s1_lead, @base - INTERVAL 92150 MINUTE, '전장망', 'FIELD', NULL, 'status', 'FIELD', NULL, '정상', 'GREEN', 500106, '{"status":{"option":"ok"},"lines":"11"}', '{"tbl":500202}'),
    ROW(500327, 500003, 15, @a_bn1_hq_s1_m1, @base - INTERVAL 89000 MINUTE, '전장망', 'FIELD', NULL, 'status', 'FIELD', NULL, '정상', 'GREEN', 500106, '{"status":{"option":"ok"},"lines":"12"}', '{"tbl":500202}'),
    ROW(500328, 500003, 16, @a_bn1_hq_s1_lead, @base - INTERVAL 84520 MINUTE, '전장망', 'FIELD', NULL, 'status', 'FIELD', NULL, '정상', 'GREEN', 500106, '{"status":{"option":"ok"},"lines":"11"}', '{"tbl":500202}'),
    ROW(500329, 500003, 17, @a_bn1_hq_s1_m1, @base - INTERVAL 82070 MINUTE, '전장망', 'FIELD', NULL, 'status', 'FIELD', NULL, '정상', 'GREEN', 500106, '{"status":{"option":"ok"},"lines":"12"}', '{"tbl":500202}'),
    ROW(500330, 500003, 18, @a_bn1_hq_s1_lead, @base - INTERVAL 73160 MINUTE, '전장망', 'FIELD', NULL, 'status', 'FIELD', NULL, '정상', 'GREEN', 500106, '{"status":{"option":"ok"},"lines":"11"}', '{"tbl":500202}'),
    ROW(500331, 500003, 19, @a_bn1_hq_s1_m1, @base - INTERVAL 71560 MINUTE, '전장망', 'FIELD', NULL, 'status', 'FIELD', NULL, '불능', 'RED', 500106, '{"status":{"option":"bad"},"lines":"4"}', '{"tbl":500203}'),
    ROW(500332, 500003, 20, @a_bn1_hq_s1_lead, @base - INTERVAL 69110 MINUTE, '전장망', 'FIELD', NULL, 'status', 'FIELD', NULL, '제한', 'YELLOW', 500106, '{"status":{"option":"lim"},"lines":"8"}', '{"tbl":500203}'),
    ROW(500333, 500003, 21, @a_bn1_hq_s1_m1, @base - INTERVAL 61640 MINUTE, '전장망', 'FIELD', NULL, 'status', 'FIELD', NULL, '제한', 'YELLOW', 500106, '{"status":{"option":"lim"},"lines":"7"}', '{"tbl":500203}'),
    ROW(500334, 500003, 22, @a_bn1_hq_s1_lead, @base - INTERVAL 60040 MINUTE, '전장망', 'FIELD', NULL, 'status', 'FIELD', NULL, '정상', 'GREEN', 500106, '{"status":{"option":"ok"},"lines":"12"}', '{"tbl":500204}'),
    ROW(500335, 500003, 23, @a_bn1_hq_s1_m1, @base - INTERVAL 53270 MINUTE, '전장망', 'FIELD', NULL, 'status', 'FIELD', NULL, '정상', 'GREEN', 500106, '{"status":{"option":"ok"},"lines":"11"}', '{"tbl":500204}'),
    ROW(500336, 500003, 24, @a_bn1_hq_s1_lead, @base - INTERVAL 51560 MINUTE, '전장망', 'FIELD', NULL, 'status', 'FIELD', NULL, '정상', 'GREEN', 500106, '{"status":{"option":"ok"},"lines":"12"}', '{"tbl":500204}'),
    ROW(500337, 500003, 25, @a_bn1_hq_s1_m1, @base - INTERVAL 48520 MINUTE, '전장망', 'FIELD', NULL, 'status', 'FIELD', NULL, '정상', 'GREEN', 500106, '{"status":{"option":"ok"},"lines":"11"}', '{"tbl":500204}'),
    ROW(500338, 500003, 26, @a_bn1_hq_s1_lead, @base - INTERVAL 43190 MINUTE, '전장망', 'FIELD', NULL, 'status', 'FIELD', NULL, '정상', 'GREEN', 500106, '{"status":{"option":"ok"},"lines":"12"}', '{"tbl":500204}'),
    ROW(500339, 500003, 27, @a_bn1_hq_s1_m1, @base - INTERVAL 41480 MINUTE, '전장망', 'FIELD', NULL, 'status', 'FIELD', NULL, '정상', 'GREEN', 500106, '{"status":{"option":"ok"},"lines":"11"}', '{"tbl":500204}'),
    ROW(500340, 500003, 28, @a_bn1_hq_s1_lead, @base - INTERVAL 38440 MINUTE, '전장망', 'FIELD', NULL, 'status', 'FIELD', NULL, '정상', 'GREEN', 500106, '{"status":{"option":"ok"},"lines":"12"}', '{"tbl":500204}'),
    ROW(500341, 500003, 29, @a_bn1_hq_s1_m1, @base - INTERVAL 33110 MINUTE, '전장망', 'FIELD', NULL, 'status', 'FIELD', NULL, '정상', 'GREEN', 500106, '{"status":{"option":"ok"},"lines":"11"}', '{"tbl":500204}'),
    ROW(500342, 500003, 30, @a_bn1_hq_s1_lead, @base - INTERVAL 31400 MINUTE, '전장망', 'FIELD', NULL, 'status', 'FIELD', NULL, '정상', 'GREEN', 500106, '{"status":{"option":"ok"},"lines":"12"}', '{"tbl":500204}'),
    ROW(500343, 500003, 31, @a_bn1_hq_s1_m1, @base - INTERVAL 28360 MINUTE, '전장망', 'FIELD', NULL, 'status', 'FIELD', NULL, '정상', 'GREEN', 500106, '{"status":{"option":"ok"},"lines":"11"}', '{"tbl":500204}'),
    ROW(500344, 500003, 32, @a_bn1_hq_s1_lead, @base - INTERVAL 23030 MINUTE, '전장망', 'FIELD', NULL, 'status', 'FIELD', NULL, '정상', 'GREEN', 500106, '{"status":{"option":"ok"},"lines":"12"}', '{"tbl":500204}'),
    ROW(500345, 500003, 33, @a_bn1_hq_s1_m1, @base - INTERVAL 21320 MINUTE, '전장망', 'FIELD', NULL, 'status', 'FIELD', NULL, '제한', 'YELLOW', 500106, '{"status":{"option":"lim"},"lines":"10"}', '{"tbl":500204}'),
    ROW(500346, 500003, 34, @a_bn1_hq_s1_lead, @base - INTERVAL 18280 MINUTE, '전장망', 'FIELD', NULL, 'status', 'FIELD', NULL, '제한', 'YELLOW', 500106, '{"status":{"option":"lim"},"lines":"9"}', '{"tbl":500204}'),
    ROW(500347, 500003, 35, @a_bn1_hq_s1_m1, @base - INTERVAL 14390 MINUTE, '전장망', 'FIELD', NULL, 'status', 'FIELD', NULL, '정상', 'GREEN', 500106, '{"status":{"option":"ok"},"lines":"12"}', '{"tbl":500204}'),
    ROW(500348, 500003, 36, @a_bn1_hq_s1_lead, @base - INTERVAL 11240 MINUTE, '전장망', 'FIELD', NULL, 'status', 'FIELD', NULL, '정상', 'GREEN', 500106, '{"status":{"option":"ok"},"lines":"11"}', '{"tbl":500204}'),
    ROW(500349, 500003, 37, @a_bn1_hq_s1_m1, @base - INTERVAL 8200 MINUTE, '전장망', 'FIELD', NULL, 'status', 'FIELD', NULL, '정상', 'GREEN', 500106, '{"status":{"option":"ok"},"lines":"12"}', '{"tbl":500204}'),
    ROW(500350, 500003, 38, @a_bn1_hq_s1_lead, @base - INTERVAL 4310 MINUTE, '전장망', 'FIELD', NULL, 'status', 'FIELD', NULL, '정상', 'GREEN', 500106, '{"status":{"option":"ok"},"lines":"11"}', '{"tbl":500204}'),
    ROW(500351, 500003, 39, @a_bn1_hq_s1_m1, @base - INTERVAL 1160 MINUTE, '전장망', 'FIELD', NULL, 'status', 'FIELD', NULL, '정상', 'GREEN', 500106, '{"status":{"option":"ok"},"lines":"12"}', '{"tbl":500205}'),
    ROW(500352, 500003, 40, @a_bn1_hq_s1_lead, @base - INTERVAL 40 MINUTE, '전장망', 'FIELD', NULL, 'status', 'FIELD', NULL, '제한', 'YELLOW', 500106, '{"status":{"option":"lim"},"lines":"9"}', '{"tbl":500206}'),
    ROW(500353, 500004, 1, @a_bn1_hq_s1_lead, @base - INTERVAL 115200 MINUTE, '인터넷망', 'TEXT', '정상', NULL, 'FIXED', 'GREEN', '정상', 'GREEN', 500107, '{}', '{}'),
    ROW(500354, 500004, 2, @a_bn1_hq_s1_lead, @base - INTERVAL 115190 MINUTE, '인터넷망', 'FIELD', NULL, 'status', 'FIELD', NULL, '정상', 'GREEN', 500108, '{"status":{"option":"ok"},"lines":"8"}', '{}'),
    ROW(500355, 500004, 3, @a_bn1_hq_s1_m2, @base - INTERVAL 105060 MINUTE, '인터넷망', 'FIELD', NULL, 'status', 'FIELD', NULL, '정상', 'GREEN', 500108, '{"status":{"option":"ok"},"lines":"7","note":"근무지원대대 구간 회선 1개 점검"}', '{}'),
    ROW(500356, 500004, 4, @a_bn1_hq_s1_lead, @base - INTERVAL 94740 MINUTE, '인터넷망', 'FIELD', NULL, 'status', 'FIELD', NULL, '정상', 'GREEN', 500108, '{"status":{"option":"ok"},"lines":"8"}', '{}'),
    ROW(500357, 500004, 5, @a_bn1_hq_s1_m2, @base - INTERVAL 89250 MINUTE, '인터넷망', 'FIELD', NULL, 'status', 'FIELD', NULL, '제한', 'YELLOW', 500108, '{"status":{"option":"lim"},"lines":"5","note":"통신실 라우터 전원 불안정"}', '{}'),
    ROW(500358, 500004, 6, @a_bn1_hq_s1_lead, @base - INTERVAL 84840 MINUTE, '인터넷망', 'FIELD', NULL, 'status', 'FIELD', NULL, '정상', 'GREEN', 500108, '{"status":{"option":"ok"},"lines":"8","note":"전원 복구"}', '{}'),
    ROW(500359, 500004, 7, @a_bn1_hq_s1_m2, @base - INTERVAL 71640 MINUTE, '인터넷망', 'FIELD', NULL, 'status', 'FIELD', NULL, '정상', 'GREEN', 500108, '{"status":{"option":"ok"},"lines":"7"}', '{}'),
    ROW(500360, 500004, 8, @a_bn1_hq_s1_lead, @base - INTERVAL 60420 MINUTE, '인터넷망', 'FIELD', NULL, 'status', 'FIELD', NULL, '정상', 'GREEN', 500108, '{"status":{"option":"ok"},"lines":"8"}', '{}'),
    ROW(500361, 500004, 9, @a_bn1_hq_s1_m2, @base - INTERVAL 53260 MINUTE, '인터넷망', 'FIELD', NULL, 'status', 'FIELD', NULL, '불능', 'RED', 500108, '{"status":{"option":"bad"},"lines":"2","note":"외부 공사로 인터넷 회선 단선, 복구 중"}', '{}'),
    ROW(500362, 500004, 10, @a_bn1_hq_s1_lead, @base - INTERVAL 52820 MINUTE, '인터넷망', 'FIELD', NULL, 'status', 'FIELD', NULL, '제한', 'YELLOW', 500108, '{"status":{"option":"lim"},"lines":"6","note":"임시 회선으로 우회"}', '{}'),
    ROW(500363, 500004, 11, @a_bn1_hq_s1_m2, @base - INTERVAL 50250 MINUTE, '인터넷망', 'FIELD', NULL, 'status', 'FIELD', NULL, '정상', 'GREEN', 500108, '{"status":{"option":"ok"},"lines":"8","note":"회선 복구 완료"}', '{}'),
    ROW(500364, 500004, 12, @a_bn1_hq_s1_lead, @base - INTERVAL 34260 MINUTE, '인터넷망', 'FIELD', NULL, 'status', 'FIELD', NULL, '정상', 'GREEN', 500108, '{"status":{"option":"ok"},"lines":"7"}', '{}'),
    ROW(500365, 500004, 13, @a_bn1_hq_s1_m2, @base - INTERVAL 21540 MINUTE, '인터넷망', 'FIELD', NULL, 'status', 'FIELD', NULL, '정상', 'GREEN', 500108, '{"status":{"option":"ok"},"lines":"8"}', '{}'),
    ROW(500366, 500004, 14, @a_bn1_hq_s1_lead, @base - INTERVAL 8250 MINUTE, '인터넷망', 'FIELD', NULL, 'status', 'FIELD', NULL, '제한', 'YELLOW', 500108, '{"status":{"option":"lim"},"lines":"6","note":"방화벽 1호 정비입고, 일부 구간 속도 제한"}', '{}'),
    ROW(500367, 500004, 15, @a_bn1_hq_s1_m2, @base - INTERVAL 950 MINUTE, '인터넷망', 'FIELD', NULL, 'status', 'FIELD', NULL, '제한', 'YELLOW', 500108, '{"status":{"option":"lim"},"lines":"7","note":"방화벽 1호 정비 중, 근무지원대대 교육장 구간 속도 저하"}', '{}'),
    ROW(500368, 500005, 1, @a_bn1_hq_s1_lead, @base - INTERVAL 94980 MINUTE, '화상회의', 'TEXT', '정상', NULL, 'FIXED', 'GREEN', '정상', 'GREEN', 500109, '{}', '{}'),
    ROW(500369, 500005, 2, @a_bn1_hq_s1_lead, @base - INTERVAL 94970 MINUTE, '화상회의', 'FIELD', NULL, 'status', 'FIELD', NULL, '정상', 'GREEN', 500110, '{"status":{"option":"ok"},"units":"3"}', '{}'),
    ROW(500370, 500005, 3, @a_bn1_hq_s1_lead, @base - INTERVAL 73140 MINUTE, '화상회의', 'FIELD', NULL, 'status', 'FIELD', NULL, '점검 중', 'BLUE', 500110, '{"status":{"option":"chk"},"units":"3","memo":"분기 연결 시험"}', '{}'),
    ROW(500371, 500005, 4, @a_bn1_hq_s1_m3, @base - INTERVAL 73020 MINUTE, '화상회의', 'FIELD', NULL, 'status', 'FIELD', NULL, '정상', 'GREEN', 500110, '{"status":{"option":"ok"},"units":"3","memo":"연결 시험 이상 없음"}', '{}'),
    ROW(500372, 500005, 5, @a_bn1_hq_s1_m3, @base - INTERVAL 51780 MINUTE, '화상회의', 'FIELD', NULL, 'status', 'FIELD', NULL, '정상', 'GREEN', 500110, '{"status":{"option":"ok"},"units":"4","memo":"근무지원대대 교육장 단말 연결 추가"}', '{}'),
    ROW(500373, 500005, 6, @a_bn1_hq_s1_lead, @base - INTERVAL 32880 MINUTE, '화상회의', 'FIELD', NULL, 'status', 'FIELD', NULL, '장애', 'RED', 500110, '{"status":{"option":"bad"},"units":"4","memo":"영상회의 서버 경보, 원격 회의 접속 불가"}', '{}'),
    ROW(500374, 500005, 7, @a_bn1_hq_s1_lead, @base - INTERVAL 32670 MINUTE, '화상회의', 'FIELD', NULL, 'status', 'FIELD', NULL, '점검 중', 'BLUE', 500110, '{"status":{"option":"chk"},"units":"4","memo":"예비 경로로 전환, 화상회의반에 정비 요청"}', '{}'),
    ROW(500375, 500005, 8, @a_bn1_hq_s1_m3, @base - INTERVAL 28680 MINUTE, '화상회의', 'FIELD', NULL, 'status', 'FIELD', NULL, '정상', 'GREEN', 500110, '{"status":{"option":"ok"},"units":"4","memo":"서버 점검 완료"}', '{}'),
    ROW(500376, 500005, 9, @a_bn1_hq_s1_m3, @base - INTERVAL 4290 MINUTE, '화상회의', 'FIELD', NULL, 'status', 'FIELD', NULL, '점검 중', 'BLUE', 500110, '{"status":{"option":"chk"},"units":"3","memo":"정보통신대대 회의실 단말 교체 검토(11-20까지)"}', '{}'),
    ROW(500377, 500006, 1, @a_bn1_hq_s1_lead, @base - INTERVAL 123840 MINUTE, '구내 전화망', 'TEXT', '정상', NULL, 'FIXED', 'GREEN', '정상', 'GREEN', 500111, '{}', '{}'),
    ROW(500378, 500006, 2, @a_bn1_hq_s1_lead, @base - INTERVAL 123830 MINUTE, '구내 전화망', 'FIELD', NULL, 'op', 'FIELD', NULL, '정상', 'GREEN', 500112, '{"op":{"option":"ok"}}', '{}'),
    ROW(500379, 500006, 3, @a_bn1_hq_s1_lead, @base - INTERVAL 100740 MINUTE, '구내 전화망', 'FIELD', NULL, 'op', 'FIELD', NULL, '일부 제한', 'YELLOW', 500112, '{"op":{"option":"part"},"note":"구내 교환기 1대 점검"}', '{}'),
    ROW(500380, 500006, 4, @a_bn1_hq_s1_lead, @base - INTERVAL 93240 MINUTE, '구내 전화망', 'FIELD', NULL, 'op', 'FIELD', NULL, '정상', 'GREEN', 500112, '{"op":{"option":"ok"}}', '{}'),
    ROW(500381, 500006, 5, @a_bn1_hq_s1_lead, @base - INTERVAL 41700 MINUTE, '구내 전화망', 'FIELD', NULL, 'op', 'FIELD', NULL, '일부 제한', 'YELLOW', 500112, '{"op":{"option":"part"},"note":"선로 공사로 일부 내선 불통"}', '{}'),
    ROW(500382, 500006, 6, @a_bn1_hq_s1_lead, @base - INTERVAL 34260 MINUTE, '구내 전화망', 'FIELD', NULL, 'op', 'FIELD', NULL, '정상', 'GREEN', 500112, '{"op":{"option":"ok"},"note":"선로 공사 완료"}', '{}'),
    ROW(500383, 500007, 1, @a_bn1_hq_s1_lead, @base - INTERVAL 119460 MINUTE, 'GPS 전파교란', 'TEXT', '없음', NULL, 'FIXED', 'GREEN', '없음', 'GREEN', 500113, '{}', '{}'),
    ROW(500384, 500007, 2, @a_bn1_hq_s1_lead, @base - INTERVAL 119450 MINUTE, 'GPS 전파교란', 'FIELD', NULL, 'jam', 'FIELD', NULL, '없음', 'GREEN', 500114, '{"jam":{"option":"none"}}', '{}'),
    ROW(500385, 500007, 3, @a_bn1_hq_s1_lead, @base - INTERVAL 48620 MINUTE, 'GPS 전파교란', 'FIELD', NULL, 'jam', 'FIELD', NULL, '있음', 'RED', 500114, '{"jam":{"option":"yes"},"at":"14:20","act":"수신기 재부팅, 상급 부대 보고"}', '{}'),
    ROW(500386, 500007, 4, @a_bn1_hq_s1_lead, @base - INTERVAL 48440 MINUTE, 'GPS 전파교란', 'FIELD', NULL, 'jam', 'FIELD', NULL, '없음', 'GREEN', 500114, '{"jam":{"option":"none"},"at":"14:20","act":"복구 확인"}', '{}'),
    ROW(500387, 500007, 5, @a_bn1_hq_s1_lead, @base - INTERVAL 8580 MINUTE, 'GPS 전파교란', 'FIELD', NULL, 'jam', 'FIELD', NULL, '없음', 'GREEN', 500114, '{"jam":{"option":"none"},"act":"정기 점검 이상 없음"}', '{}'),
    ROW(500388, 500007, 6, @a_bn1_hq_s1_lead, @base - INTERVAL 1510 MINUTE, 'GPS 전파교란', 'FIELD', NULL, 'jam', 'FIELD', NULL, '있음', 'RED', 500114, '{"jam":{"option":"yes"},"at":"06:40","act":"수신기 전원 재시작, 상급 부대 보고, 복구 확인 중"}', '{}'),
    ROW(500389, 500008, 1, @a_bn1_hq_s1_lead, @base - INTERVAL 84960 MINUTE, '무전망', 'TEXT', '양호', NULL, 'FIXED', 'GREEN', '양호', 'GREEN', 500115, '{}', '{}'),
    ROW(500390, 500008, 2, @a_bn1_hq_s1_lead, @base - INTERVAL 84950 MINUTE, '무전망', 'FIELD', NULL, 'link', 'FIELD', NULL, '양호', 'GREEN', 500116, '{"link":{"option":"good"},"at":"09:00"}', '{}'),
    ROW(500391, 500008, 3, @a_bn1_hq_s1_lead, @base - INTERVAL 53300 MINUTE, '무전망', 'FIELD', NULL, 'link', 'FIELD', NULL, '불량', 'ORANGE', 500116, '{"link":{"option":"poor"},"at":"21:30","act":"야간 교신 잡음, 기지국 무전기 안테나 연결부 점검"}', '{}'),
    ROW(500392, 500008, 4, @a_bn1_hq_s1_lead, @base - INTERVAL 51780 MINUTE, '무전망', 'FIELD', NULL, 'link', 'FIELD', NULL, '양호', 'GREEN', 500116, '{"link":{"option":"good"},"at":"09:10","act":"안테나 케이블 교체 뒤 양호"}', '{}'),
    ROW(500393, 500008, 5, @a_bn1_hq_s1_lead, @base - INTERVAL 18720 MINUTE, '무전망', 'FIELD', NULL, 'link', 'FIELD', NULL, '양호', 'GREEN', 500116, '{"link":{"option":"good"},"at":"08:40","act":"정기 교신 점검 이상 없음"}', '{}'),
    ROW(500394, 500008, 6, @a_bn1_hq_s1_lead, @base - INTERVAL 2910 MINUTE, '무전망', 'FIELD', NULL, 'link', 'FIELD', NULL, '불량', 'ORANGE', 500116, '{"link":{"option":"poor"},"at":"08:30","act":"야간 교신 잡음 재발, 중계기 미운용으로 원거리 교신 불량"}', '{}'),
    ROW(500395, 500009, 1, @a_bn1_hq_s1_lead, @base - INTERVAL 112260 MINUTE, '통신실 전원', 'TEXT', '정상', NULL, 'FIXED', 'GREEN', '정상', 'GREEN', 500117, '{}', '{}'),
    ROW(500396, 500009, 2, @a_bn1_hq_s1_lead, @base - INTERVAL 112250 MINUTE, '통신실 전원', 'FIELD', NULL, 'op', 'FIELD', NULL, '정상', 'GREEN', 500118, '{"op":{"option":"ok"}}', '{}'),
    ROW(500397, 500009, 3, @a_bn1_hq_s1_lead, @base - INTERVAL 40320 MINUTE, '통신실 전원', 'FIELD', NULL, 'op', 'FIELD', NULL, '일부 제한', 'YELLOW', 500118, '{"op":{"option":"part"},"note":"무정전 전원장치 점검으로 예비 전원 운용"}', '{}'),
    ROW(500398, 500009, 4, @a_bn1_hq_s1_lead, @base - INTERVAL 38520 MINUTE, '통신실 전원', 'FIELD', NULL, 'op', 'FIELD', NULL, '정상', 'GREEN', 500118, '{"op":{"option":"ok"},"note":"점검 완료"}', '{}')
) AS v WHERE @ok;

INSERT INTO ict_box_scope (id, box_id, personnel_id, department_id, include_sub)
SELECT * FROM (VALUES
    ROW(500501, 500001, NULL, 1, TRUE),
    ROW(500502, 500002, NULL, 1, TRUE),
    ROW(500503, 500003, NULL, 1, TRUE),
    ROW(500504, 500004, NULL, 1, TRUE),
    ROW(500505, 500005, NULL, 1, TRUE),
    ROW(500506, 500006, NULL, 1, TRUE),
    ROW(500507, 500007, NULL, 1, TRUE),
    ROW(500508, 500008, NULL, 1, TRUE),
    ROW(500509, 500009, NULL, 1, TRUE),
    ROW(500510, 500001, NULL, 11, TRUE),
    ROW(500511, 500002, NULL, 11, TRUE),
    ROW(500512, 500003, NULL, 11, TRUE),
    ROW(500513, 500004, NULL, 11, TRUE),
    ROW(500514, 500005, NULL, 11, TRUE),
    ROW(500515, 500006, NULL, 11, TRUE),
    ROW(500516, 500007, NULL, 11, TRUE),
    ROW(500517, 500008, NULL, 11, TRUE)
) AS v WHERE @ok;

INSERT INTO ict_box_assignee (box_id, personnel_id, assigned_by_account_id, assigned_at)
SELECT 500003, (SELECT personnel_id FROM account WHERE id = @a_bn1_hq_s1_m1), @a_bn1_hq_s1_lead, @base - INTERVAL 130920 MINUTE FROM DUAL WHERE @ok
UNION ALL
SELECT 500004, (SELECT personnel_id FROM account WHERE id = @a_bn1_hq_s1_m2), @a_bn1_hq_s1_lead, @base - INTERVAL 115170 MINUTE FROM DUAL WHERE @ok
UNION ALL
SELECT 500005, (SELECT personnel_id FROM account WHERE id = @a_bn1_hq_s1_m3), @a_bn1_hq_s1_lead, @base - INTERVAL 74850 MINUTE FROM DUAL WHERE @ok;

-- 기록 일: (박스, 판 연도)마다 한 줄
INSERT INTO ict_record_job (box_id, year, requested_at)
SELECT DISTINCT box_id, YEAR(changed_at), @base FROM ict_box_revision WHERE box_id BETWEEN 500001 AND 509999 AND @ok;

-- 범위 카드: bn1-hq-s1-lead의 "정보통신대대 전체", bn2-co1-cmdr의 "근무지원대대 전체"
INSERT INTO scope_card (id, account_id, name, created_at, updated_at)
SELECT * FROM (VALUES
    ROW(500601, @a_bn1_hq_s1_lead, '정보통신대대 전체', @base - INTERVAL 113700 MINUTE, @base - INTERVAL 113700 MINUTE),
    ROW(500602, @a_bn2_co1_cmdr, '근무지원대대 전체', @base - INTERVAL 63060 MINUTE, @base - INTERVAL 63060 MINUTE)
) AS v WHERE @ok;
INSERT INTO scope_card_entry (id, card_id, personnel_id, department_id, include_sub)
SELECT * FROM (VALUES
    ROW(500611, 500601, NULL, 1, TRUE),
    ROW(500612, 500602, NULL, 11, TRUE)
) AS v WHERE @ok;

SELECT @ok AS ict_status_demo_applied, (SELECT COUNT(*) FROM ict_box WHERE id >= 500001) AS ict_boxes, (SELECT COUNT(*) FROM ict_box_revision WHERE box_id >= 500001) AS ict_revisions;
