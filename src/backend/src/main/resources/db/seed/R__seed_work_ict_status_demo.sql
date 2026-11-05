-- 시연용 정보통신 현황 자료 (명세 7장, G-144, slices/15 15장). scripts/demo-ict-status-data.py가 만든 파일이다. 직접 고치지 않는다
-- 박스 10개, 판 105개, 공개 범위 9줄, 담당자 3명, 범위 카드 2개. 값은 모두 가상(NFR-002)
-- 이름이 R__seed_work_ict_status_demo라 초기 데이터·문서함 시연 자료 다음에 돈다. 박스가 있거나 번호 범위(500001~509999)가 쓰였거나 쓸 계정이 없으면 전체를 건너뛴다
-- 2대대 CPCON은 bn2-cmdr에게 2대대 부서 관리자 역할이 있을 때만 넣는다(@ok_bn2, 박스는 그 부서 관리자가 추가, REQ-503)
-- 기록 일(ict_record_job)을 넣어 두면 서버가 켜질 때 기록 일꾼이 문서함 기록 파일을 만든다
-- 시각은 시연 기준 시각(KST, 고정)에서 뺀 값이다

SET @base = TIMESTAMP('2026-11-02 12:00:00');
SET @a_bn1_cmdr = (SELECT id FROM account WHERE login_id = 'bn1-cmdr' AND active = TRUE);
SET @a_bn1_co1_admin = (SELECT id FROM account WHERE login_id = 'bn1-co1-admin' AND active = TRUE);
SET @a_bn1_co1_s1_lead = (SELECT id FROM account WHERE login_id = 'bn1-co1-s1-lead' AND active = TRUE);
SET @a_bn1_co1_s1_m1 = (SELECT id FROM account WHERE login_id = 'bn1-co1-s1-m1' AND active = TRUE);
SET @a_bn1_co2_s1_lead = (SELECT id FROM account WHERE login_id = 'bn1-co2-s1-lead' AND active = TRUE);
SET @a_bn1_co2_s1_m1 = (SELECT id FROM account WHERE login_id = 'bn1-co2-s1-m1' AND active = TRUE);
SET @a_bn1_co2_s2_lead = (SELECT id FROM account WHERE login_id = 'bn1-co2-s2-lead' AND active = TRUE);
SET @a_bn2_cmdr = (SELECT id FROM account WHERE login_id = 'bn2-cmdr' AND active = TRUE);
SET @a_bn2_co1_cmdr = (SELECT id FROM account WHERE login_id = 'bn2-co1-cmdr' AND active = TRUE);
SET @a_bn2_co1_s1_lead = (SELECT id FROM account WHERE login_id = 'bn2-co1-s1-lead' AND active = TRUE);
SET @ok = (SELECT COUNT(*) FROM ict_box) = 0
    AND (SELECT COALESCE(MAX(id), 0) FROM ict_box_schema) < 500001
    AND (SELECT COALESCE(MAX(id), 0) FROM ict_box_table) < 500001
    AND (SELECT COALESCE(MAX(id), 0) FROM ict_box_revision) < 500001
    AND (SELECT COALESCE(MAX(id), 0) FROM ict_box_scope) < 500001
    AND (SELECT COALESCE(MAX(id), 0) FROM scope_card) < 500001
    AND (SELECT COALESCE(MAX(id), 0) FROM scope_card_entry) < 500001
    AND @a_bn1_cmdr IS NOT NULL
    AND @a_bn1_co1_admin IS NOT NULL
    AND @a_bn1_co1_s1_lead IS NOT NULL
    AND @a_bn1_co1_s1_m1 IS NOT NULL
    AND @a_bn1_co2_s1_lead IS NOT NULL
    AND @a_bn1_co2_s1_m1 IS NOT NULL
    AND @a_bn1_co2_s2_lead IS NOT NULL
    AND @a_bn2_cmdr IS NOT NULL
    AND @a_bn2_co1_cmdr IS NOT NULL
    AND @a_bn2_co1_s1_lead IS NOT NULL;
SET @ok_bn2 = EXISTS (SELECT 1 FROM role_assignment r JOIN department d ON d.id = r.department_id WHERE r.account_id = @a_bn2_cmdr AND r.role_code = 'DEPT_ADMIN' AND r.department_id = 11 AND r.active AND d.active);

INSERT INTO ict_box (id, department_id, title, title_key, summary_mode, summary_text, summary_field, color_mode, color, schema_id, values_json, tables_json,
    last_revision_no, version, created_by_account_id, created_at, updated_by_account_id, updated_at)
SELECT * FROM (VALUES
    ROW(500001, 1, 'CPCON', 'cpcon', 'FIELD', NULL, 'stage', 'FIELD', NULL, 500102, '{"stage":{"option":"s4"},"note":"정기 점검 기간 연장"}', '{}', 7, 6, @a_bn1_cmdr, @base - INTERVAL 129600 MINUTE, @a_bn1_cmdr, @base - INTERVAL 2880 MINUTE),
    ROW(500002, 1, '위기경보단계', '위기경보단계', 'FIELD', NULL, 'stage', 'FIELD', NULL, 500104, '{"stage":{"option":"a2"}}', '{}', 5, 4, @a_bn1_cmdr, @base - INTERVAL 126720 MINUTE, @a_bn1_cmdr, @base - INTERVAL 5760 MINUTE),
    ROW(500003, 2, '전장망', '전장망', 'FIELD', NULL, 'status', 'FIELD', NULL, 500106, '{"status":{"option":"lim"},"lines":"9"}', '{"tbl":500206}', 40, 39, @a_bn1_co1_admin, @base - INTERVAL 128190 MINUTE, @a_bn1_co1_admin, @base - INTERVAL 1200 MINUTE),
    ROW(500004, 2, '기반통신', '기반통신', 'FIELD', NULL, 'op', 'FIELD', NULL, 500108, '{"op":{"option":"ok"}}', '{}', 6, 5, @a_bn1_co1_admin, @base - INTERVAL 122400 MINUTE, @a_bn1_co1_admin, @base - INTERVAL 24480 MINUTE),
    ROW(500005, 5, 'GPS 전파교란', 'gps 전파교란', 'FIELD', NULL, 'jam', 'FIELD', NULL, 500110, '{"jam":{"option":"yes"},"at":"06:40","act":"수신기 전원 재시작, 상급 부대 보고, 복구 확인 중"}', '{}', 6, 5, @a_bn1_co1_s1_lead, @base - INTERVAL 115200 MINUTE, @a_bn1_co1_s1_lead, @base - INTERVAL 1740 MINUTE),
    ROW(500006, 7, '교환기', '교환기', 'FIELD', NULL, 'status', 'FIELD', NULL, 500112, '{"status":{"option":"chk"},"used":"44","memo":"분기 접속 시험(11-03까지)"}', '{}', 9, 8, @a_bn1_co2_s1_lead, @base - INTERVAL 90720 MINUTE, @a_bn1_co2_s1_m1, @base - INTERVAL 4320 MINUTE),
    ROW(500007, 8, '무전망', '무전망', 'FIELD', NULL, 'link', 'FIELD', NULL, 500114, '{"link":{"option":"poor"},"at":"08:30","act":"야간 교신 잡음 재발, 충전기 2대 고장으로 예비 충전기 사용"}', '{}', 6, 5, @a_bn1_co2_s2_lead, @base - INTERVAL 83520 MINUTE, @a_bn1_co2_s2_lead, @base - INTERVAL 2880 MINUTE),
    ROW(500008, 12, '전장망', '전장망', 'FIELD', NULL, 'status', 'FIELD', NULL, 500116, '{"status":{"option":"lim"},"lines":"7","note":"단말기 C 정비 중, 2반 구간은 무전망으로 확인"}', '{}', 15, 14, @a_bn2_co1_cmdr, @base - INTERVAL 100800 MINUTE, @a_bn2_co1_s1_lead, @base - INTERVAL 1200 MINUTE),
    ROW(500009, 12, '통신소 운용', '통신소 운용', 'FIELD', NULL, 'op', 'FIELD', NULL, 500118, '{"op":{"option":"ok"},"note":"발전기 정비 완료"}', '{}', 4, 3, @a_bn2_co1_cmdr, @base - INTERVAL 95040 MINUTE, @a_bn2_co1_cmdr, @base - INTERVAL 36000 MINUTE)
) AS v WHERE @ok;

INSERT INTO ict_box (id, department_id, title, title_key, summary_mode, summary_text, summary_field, color_mode, color, schema_id, values_json, tables_json,
    last_revision_no, version, created_by_account_id, created_at, updated_by_account_id, updated_at)
SELECT * FROM (VALUES
    ROW(500010, 11, 'CPCON', 'cpcon', 'FIELD', NULL, 'stage', 'FIELD', NULL, 500120, '{"stage":{"option":"s3"},"note":"11월 첫째 주 지휘소 연습으로 상향"}', '{}', 7, 6, @a_bn2_cmdr, @base - INTERVAL 109440 MINUTE, @a_bn2_cmdr, @base - INTERVAL 1560 MINUTE)
) AS v WHERE @ok AND @ok_bn2;

INSERT INTO ict_box_schema (id, box_id, sha256, definition, created_at)
SELECT * FROM (VALUES
    ROW(500101, 500001, '01f2f9c28aa1d4d36a81ff042620b6677d25bf07c2bf4acc37b58658778a4fca', '{"fields":[]}', @base - INTERVAL 129600 MINUTE),
    ROW(500102, 500001, 'fa76b4ea6c9c123fa5542a1b67738a8b9047a3f54e75b999766711e5f5285dd7', '{"fields":[{"key":"stage","kind":"DROPDOWN","title":"단계","description":"","options":[{"id":"s5","label":"5단계","color":"GREEN"},{"id":"s4","label":"4단계","color":"BLUE"},{"id":"s3","label":"3단계","color":"YELLOW"},{"id":"s2","label":"2단계","color":"ORANGE"},{"id":"s1","label":"1단계","color":"RED"}],"allowOther":false,"rows":[],"columns":[],"scaleMin":null,"scaleMax":null,"minLabel":"","maxLabel":"","validation":null,"table":null},{"key":"note","kind":"LONG_TEXT","title":"비고","description":"","options":[],"allowOther":false,"rows":[],"columns":[],"scaleMin":null,"scaleMax":null,"minLabel":"","maxLabel":"","validation":null,"table":null}]}', @base - INTERVAL 129590 MINUTE),
    ROW(500103, 500002, '01f2f9c28aa1d4d36a81ff042620b6677d25bf07c2bf4acc37b58658778a4fca', '{"fields":[]}', @base - INTERVAL 126720 MINUTE),
    ROW(500104, 500002, '1725c52d113a752c7408708d11c742bc06491e3a56b6af8dbf337a63c1bc311e', '{"fields":[{"key":"stage","kind":"DROPDOWN","title":"단계","description":"","options":[{"id":"a1","label":"관심","color":"BLUE"},{"id":"a2","label":"주의","color":"YELLOW"},{"id":"a3","label":"경계","color":"ORANGE"},{"id":"a4","label":"심각","color":"RED"}],"allowOther":false,"rows":[],"columns":[],"scaleMin":null,"scaleMax":null,"minLabel":"","maxLabel":"","validation":null,"table":null}]}', @base - INTERVAL 126710 MINUTE),
    ROW(500105, 500003, '01f2f9c28aa1d4d36a81ff042620b6677d25bf07c2bf4acc37b58658778a4fca', '{"fields":[]}', @base - INTERVAL 128190 MINUTE),
    ROW(500106, 500003, 'e668c5635113678dca4457c3866803c8a63548ef03eef15c19f79f4b120a7686', '{"fields":[{"key":"status","kind":"DROPDOWN","title":"상태","description":"","options":[{"id":"ok","label":"정상","color":"GREEN"},{"id":"lim","label":"제한","color":"YELLOW"},{"id":"bad","label":"불능","color":"RED"}],"allowOther":false,"rows":[],"columns":[],"scaleMin":null,"scaleMax":null,"minLabel":"","maxLabel":"","validation":null,"table":null},{"key":"lines","kind":"NUMBER","title":"가용 회선 수","description":"","options":[],"allowOther":false,"rows":[],"columns":[],"scaleMin":null,"scaleMax":null,"minLabel":"","maxLabel":"","validation":null,"table":null},{"key":"tbl","kind":"TABLE","title":"구간별 상태","description":"","options":[],"allowOther":false,"rows":[],"columns":[],"scaleMin":null,"scaleMax":null,"minLabel":"","maxLabel":"","validation":null,"table":{"columns":[{"id":"sec","title":"구간","kind":"SHORT_TEXT","options":[]},{"id":"st","title":"상태","kind":"DROPDOWN","options":[{"id":"ok","label":"정상","goTo":null},{"id":"lim","label":"제한","goTo":null},{"id":"bad","label":"불능","goTo":null}]},{"id":"dt","title":"점검일","kind":"DATE","options":[]}]}}]}', @base - INTERVAL 128160 MINUTE),
    ROW(500107, 500004, '01f2f9c28aa1d4d36a81ff042620b6677d25bf07c2bf4acc37b58658778a4fca', '{"fields":[]}', @base - INTERVAL 122400 MINUTE),
    ROW(500108, 500004, '64b24987134eed91fcd23d4b948e13238b0f2b9fb72591a573271d2b40670897', '{"fields":[{"key":"op","kind":"MULTIPLE_CHOICE","title":"운용","description":"","options":[{"id":"ok","label":"정상","color":"GREEN"},{"id":"part","label":"일부 제한","color":"YELLOW"}],"allowOther":false,"rows":[],"columns":[],"scaleMin":null,"scaleMax":null,"minLabel":"","maxLabel":"","validation":null,"table":null},{"key":"note","kind":"SHORT_TEXT","title":"특이사항","description":"","options":[],"allowOther":false,"rows":[],"columns":[],"scaleMin":null,"scaleMax":null,"minLabel":"","maxLabel":"","validation":null,"table":null}]}', @base - INTERVAL 122390 MINUTE),
    ROW(500109, 500005, '01f2f9c28aa1d4d36a81ff042620b6677d25bf07c2bf4acc37b58658778a4fca', '{"fields":[]}', @base - INTERVAL 115200 MINUTE),
    ROW(500110, 500005, '8150dc3709d52a3ac912a3f038f54d6364be0ae08b0afd5db452e161e0f1c008', '{"fields":[{"key":"jam","kind":"MULTIPLE_CHOICE","title":"교란","description":"","options":[{"id":"none","label":"없음","color":"GREEN"},{"id":"yes","label":"있음","color":"RED"}],"allowOther":false,"rows":[],"columns":[],"scaleMin":null,"scaleMax":null,"minLabel":"","maxLabel":"","validation":null,"table":null},{"key":"at","kind":"TIME","title":"발생 시각","description":"","options":[],"allowOther":false,"rows":[],"columns":[],"scaleMin":null,"scaleMax":null,"minLabel":"","maxLabel":"","validation":null,"table":null},{"key":"act","kind":"LONG_TEXT","title":"조치","description":"","options":[],"allowOther":false,"rows":[],"columns":[],"scaleMin":null,"scaleMax":null,"minLabel":"","maxLabel":"","validation":null,"table":null}]}', @base - INTERVAL 115190 MINUTE),
    ROW(500111, 500006, '01f2f9c28aa1d4d36a81ff042620b6677d25bf07c2bf4acc37b58658778a4fca', '{"fields":[]}', @base - INTERVAL 90720 MINUTE),
    ROW(500112, 500006, '7bf9c79c7b5c5fff5d268fc42b30ae0b2886439c973dae354ca778848b7e1eae', '{"fields":[{"key":"status","kind":"DROPDOWN","title":"상태","description":"","options":[{"id":"ok","label":"정상","color":"GREEN"},{"id":"chk","label":"점검 중","color":"BLUE"},{"id":"bad","label":"장애","color":"RED"}],"allowOther":false,"rows":[],"columns":[],"scaleMin":null,"scaleMax":null,"minLabel":"","maxLabel":"","validation":null,"table":null},{"key":"used","kind":"NUMBER","title":"사용 회선","description":"","options":[],"allowOther":false,"rows":[],"columns":[],"scaleMin":null,"scaleMax":null,"minLabel":"","maxLabel":"","validation":null,"table":null},{"key":"memo","kind":"SHORT_TEXT","title":"점검 메모","description":"","options":[],"allowOther":false,"rows":[],"columns":[],"scaleMin":null,"scaleMax":null,"minLabel":"","maxLabel":"","validation":null,"table":null}]}', @base - INTERVAL 89270 MINUTE),
    ROW(500113, 500007, '01f2f9c28aa1d4d36a81ff042620b6677d25bf07c2bf4acc37b58658778a4fca', '{"fields":[]}', @base - INTERVAL 83520 MINUTE),
    ROW(500114, 500007, '90ba153c667e31d43f299ca907d90f2797a509f55445b62d2fbdcf2462f8f8e0', '{"fields":[{"key":"link","kind":"MULTIPLE_CHOICE","title":"교신","description":"","options":[{"id":"good","label":"양호","color":"GREEN"},{"id":"poor","label":"불량","color":"ORANGE"}],"allowOther":false,"rows":[],"columns":[],"scaleMin":null,"scaleMax":null,"minLabel":"","maxLabel":"","validation":null,"table":null},{"key":"at","kind":"TIME","title":"점검 시각","description":"","options":[],"allowOther":false,"rows":[],"columns":[],"scaleMin":null,"scaleMax":null,"minLabel":"","maxLabel":"","validation":null,"table":null},{"key":"act","kind":"LONG_TEXT","title":"조치","description":"","options":[],"allowOther":false,"rows":[],"columns":[],"scaleMin":null,"scaleMax":null,"minLabel":"","maxLabel":"","validation":null,"table":null}]}', @base - INTERVAL 83510 MINUTE),
    ROW(500115, 500008, '01f2f9c28aa1d4d36a81ff042620b6677d25bf07c2bf4acc37b58658778a4fca', '{"fields":[]}', @base - INTERVAL 100800 MINUTE),
    ROW(500116, 500008, '32daa9d150940d247b00fc2b4f55f3843c63eb0af39c9ee83aa28a5ac84f62dc', '{"fields":[{"key":"status","kind":"DROPDOWN","title":"상태","description":"","options":[{"id":"ok","label":"정상","color":"GREEN"},{"id":"lim","label":"제한","color":"YELLOW"},{"id":"bad","label":"불능","color":"RED"}],"allowOther":false,"rows":[],"columns":[],"scaleMin":null,"scaleMax":null,"minLabel":"","maxLabel":"","validation":null,"table":null},{"key":"lines","kind":"NUMBER","title":"가용 회선 수","description":"","options":[],"allowOther":false,"rows":[],"columns":[],"scaleMin":null,"scaleMax":null,"minLabel":"","maxLabel":"","validation":null,"table":null},{"key":"note","kind":"LONG_TEXT","title":"비고","description":"","options":[],"allowOther":false,"rows":[],"columns":[],"scaleMin":null,"scaleMax":null,"minLabel":"","maxLabel":"","validation":null,"table":null}]}', @base - INTERVAL 99270 MINUTE),
    ROW(500117, 500009, '01f2f9c28aa1d4d36a81ff042620b6677d25bf07c2bf4acc37b58658778a4fca', '{"fields":[]}', @base - INTERVAL 95040 MINUTE),
    ROW(500118, 500009, '64b24987134eed91fcd23d4b948e13238b0f2b9fb72591a573271d2b40670897', '{"fields":[{"key":"op","kind":"MULTIPLE_CHOICE","title":"운용","description":"","options":[{"id":"ok","label":"정상","color":"GREEN"},{"id":"part","label":"일부 제한","color":"YELLOW"}],"allowOther":false,"rows":[],"columns":[],"scaleMin":null,"scaleMax":null,"minLabel":"","maxLabel":"","validation":null,"table":null},{"key":"note","kind":"SHORT_TEXT","title":"특이사항","description":"","options":[],"allowOther":false,"rows":[],"columns":[],"scaleMin":null,"scaleMax":null,"minLabel":"","maxLabel":"","validation":null,"table":null}]}', @base - INTERVAL 95030 MINUTE)
) AS v WHERE @ok;

INSERT INTO ict_box_schema (id, box_id, sha256, definition, created_at)
SELECT * FROM (VALUES
    ROW(500119, 500010, '01f2f9c28aa1d4d36a81ff042620b6677d25bf07c2bf4acc37b58658778a4fca', '{"fields":[]}', @base - INTERVAL 109440 MINUTE),
    ROW(500120, 500010, 'fa76b4ea6c9c123fa5542a1b67738a8b9047a3f54e75b999766711e5f5285dd7', '{"fields":[{"key":"stage","kind":"DROPDOWN","title":"단계","description":"","options":[{"id":"s5","label":"5단계","color":"GREEN"},{"id":"s4","label":"4단계","color":"BLUE"},{"id":"s3","label":"3단계","color":"YELLOW"},{"id":"s2","label":"2단계","color":"ORANGE"},{"id":"s1","label":"1단계","color":"RED"}],"allowOther":false,"rows":[],"columns":[],"scaleMin":null,"scaleMax":null,"minLabel":"","maxLabel":"","validation":null,"table":null},{"key":"note","kind":"LONG_TEXT","title":"비고","description":"","options":[],"allowOther":false,"rows":[],"columns":[],"scaleMin":null,"scaleMax":null,"minLabel":"","maxLabel":"","validation":null,"table":null}]}', @base - INTERVAL 109430 MINUTE)
) AS v WHERE @ok AND @ok_bn2;

INSERT INTO ict_box_table (id, box_id, sha256, data)
SELECT t.id, t.box_id, SHA2(t.data, 256), t.data FROM (
    SELECT 500201 AS id, 500003 AS box_id, CONCAT('[', '{"sec":"대대-1중대","st":"ok","dt":"', DATE_FORMAT(@base - INTERVAL 128160 MINUTE, '%Y-%m-%d'), '"}', ',', '{"sec":"1중대-2중대","st":"ok","dt":"', DATE_FORMAT(@base - INTERVAL 129600 MINUTE, '%Y-%m-%d'), '"}', ',', '{"sec":"1중대-3중대","st":"ok","dt":"', DATE_FORMAT(@base - INTERVAL 131040 MINUTE, '%Y-%m-%d'), '"}', ',', '{"sec":"대대-상황실","st":"ok","dt":"', DATE_FORMAT(@base - INTERVAL 132480 MINUTE, '%Y-%m-%d'), '"}', ',', '{"sec":"1중대-통신소","st":"ok","dt":"', DATE_FORMAT(@base - INTERVAL 133920 MINUTE, '%Y-%m-%d'), '"}', ']') AS data
    UNION ALL
    SELECT 500202 AS id, 500003 AS box_id, CONCAT('[', '{"sec":"대대-1중대","st":"ok","dt":"', DATE_FORMAT(@base - INTERVAL 108000 MINUTE, '%Y-%m-%d'), '"}', ',', '{"sec":"1중대-2중대","st":"lim","dt":"', DATE_FORMAT(@base - INTERVAL 109440 MINUTE, '%Y-%m-%d'), '"}', ',', '{"sec":"1중대-3중대","st":"ok","dt":"', DATE_FORMAT(@base - INTERVAL 110880 MINUTE, '%Y-%m-%d'), '"}', ',', '{"sec":"대대-상황실","st":"ok","dt":"', DATE_FORMAT(@base - INTERVAL 112320 MINUTE, '%Y-%m-%d'), '"}', ',', '{"sec":"1중대-통신소","st":"ok","dt":"', DATE_FORMAT(@base - INTERVAL 113760 MINUTE, '%Y-%m-%d'), '"}', ']') AS data
    UNION ALL
    SELECT 500203 AS id, 500003 AS box_id, CONCAT('[', '{"sec":"대대-1중대","st":"ok","dt":"', DATE_FORMAT(@base - INTERVAL 79200 MINUTE, '%Y-%m-%d'), '"}', ',', '{"sec":"1중대-2중대","st":"bad","dt":"', DATE_FORMAT(@base - INTERVAL 80640 MINUTE, '%Y-%m-%d'), '"}', ',', '{"sec":"1중대-3중대","st":"lim","dt":"', DATE_FORMAT(@base - INTERVAL 82080 MINUTE, '%Y-%m-%d'), '"}', ',', '{"sec":"대대-상황실","st":"ok","dt":"', DATE_FORMAT(@base - INTERVAL 83520 MINUTE, '%Y-%m-%d'), '"}', ',', '{"sec":"1중대-통신소","st":"ok","dt":"', DATE_FORMAT(@base - INTERVAL 84960 MINUTE, '%Y-%m-%d'), '"}', ']') AS data
    UNION ALL
    SELECT 500204 AS id, 500003 AS box_id, CONCAT('[', '{"sec":"대대-1중대","st":"ok","dt":"', DATE_FORMAT(@base - INTERVAL 72000 MINUTE, '%Y-%m-%d'), '"}', ',', '{"sec":"1중대-2중대","st":"ok","dt":"', DATE_FORMAT(@base - INTERVAL 73440 MINUTE, '%Y-%m-%d'), '"}', ',', '{"sec":"1중대-3중대","st":"ok","dt":"', DATE_FORMAT(@base - INTERVAL 74880 MINUTE, '%Y-%m-%d'), '"}', ',', '{"sec":"대대-상황실","st":"ok","dt":"', DATE_FORMAT(@base - INTERVAL 76320 MINUTE, '%Y-%m-%d'), '"}', ',', '{"sec":"1중대-통신소","st":"ok","dt":"', DATE_FORMAT(@base - INTERVAL 77760 MINUTE, '%Y-%m-%d'), '"}', ']') AS data
    UNION ALL
    SELECT 500205 AS id, 500003 AS box_id, CONCAT('[', '{"sec":"대대-1중대","st":"ok","dt":"', DATE_FORMAT(@base - INTERVAL 4320 MINUTE, '%Y-%m-%d'), '"}', ',', '{"sec":"1중대-2중대","st":"ok","dt":"', DATE_FORMAT(@base - INTERVAL 5760 MINUTE, '%Y-%m-%d'), '"}', ',', '{"sec":"1중대-3중대","st":"ok","dt":"', DATE_FORMAT(@base - INTERVAL 7200 MINUTE, '%Y-%m-%d'), '"}', ',', '{"sec":"대대-상황실","st":"ok","dt":"', DATE_FORMAT(@base - INTERVAL 8640 MINUTE, '%Y-%m-%d'), '"}', ',', '{"sec":"1중대-통신소","st":"ok","dt":"', DATE_FORMAT(@base - INTERVAL 10080 MINUTE, '%Y-%m-%d'), '"}', ']') AS data
    UNION ALL
    SELECT 500206 AS id, 500003 AS box_id, CONCAT('[', '{"sec":"대대-1중대","st":"ok","dt":"', DATE_FORMAT(@base - INTERVAL 1440 MINUTE, '%Y-%m-%d'), '"}', ',', '{"sec":"1중대-2중대","st":"ok","dt":"', DATE_FORMAT(@base - INTERVAL 2880 MINUTE, '%Y-%m-%d'), '"}', ',', '{"sec":"1중대-3중대","st":"lim","dt":"', DATE_FORMAT(@base - INTERVAL 4320 MINUTE, '%Y-%m-%d'), '"}', ',', '{"sec":"대대-상황실","st":"ok","dt":"', DATE_FORMAT(@base - INTERVAL 5760 MINUTE, '%Y-%m-%d'), '"}', ',', '{"sec":"1중대-통신소","st":"ok","dt":"', DATE_FORMAT(@base - INTERVAL 7200 MINUTE, '%Y-%m-%d'), '"}', ']') AS data
) AS t WHERE @ok;

INSERT INTO ict_box_revision (id, box_id, revision_no, changed_by_account_id, changed_at, title, summary_mode, summary_text, summary_field,
    color_mode, color, shown_summary, shown_color, schema_id, values_json, tables_json)
SELECT * FROM (VALUES
    ROW(500301, 500001, 1, @a_bn1_cmdr, @base - INTERVAL 129600 MINUTE, 'CPCON', 'TEXT', '4단계', NULL, 'FIXED', 'BLUE', '4단계', 'BLUE', 500101, '{}', '{}'),
    ROW(500302, 500001, 2, @a_bn1_cmdr, @base - INTERVAL 129590 MINUTE, 'CPCON', 'FIELD', NULL, 'stage', 'FIELD', NULL, '4단계', 'BLUE', 500102, '{"stage":{"option":"s4"}}', '{}'),
    ROW(500303, 500001, 3, @a_bn1_cmdr, @base - INTERVAL 100800 MINUTE, 'CPCON', 'FIELD', NULL, 'stage', 'FIELD', NULL, '3단계', 'YELLOW', 500102, '{"stage":{"option":"s3"},"note":"합동 훈련 기간 상향"}', '{}'),
    ROW(500304, 500001, 4, @a_bn1_cmdr, @base - INTERVAL 79200 MINUTE, 'CPCON', 'FIELD', NULL, 'stage', 'FIELD', NULL, '4단계', 'BLUE', 500102, '{"stage":{"option":"s4"}}', '{}'),
    ROW(500305, 500001, 5, @a_bn1_cmdr, @base - INTERVAL 43200 MINUTE, 'CPCON', 'FIELD', NULL, 'stage', 'FIELD', NULL, '5단계', 'GREEN', 500102, '{"stage":{"option":"s5"},"note":"평시 전환"}', '{}'),
    ROW(500306, 500001, 6, @a_bn1_cmdr, @base - INTERVAL 17280 MINUTE, 'CPCON', 'FIELD', NULL, 'stage', 'FIELD', NULL, '4단계', 'BLUE', 500102, '{"stage":{"option":"s4"},"note":"정기 점검 기간"}', '{}'),
    ROW(500307, 500001, 7, @a_bn1_cmdr, @base - INTERVAL 2880 MINUTE, 'CPCON', 'FIELD', NULL, 'stage', 'FIELD', NULL, '4단계', 'BLUE', 500102, '{"stage":{"option":"s4"},"note":"정기 점검 기간 연장"}', '{}'),
    ROW(500308, 500002, 1, @a_bn1_cmdr, @base - INTERVAL 126720 MINUTE, '위기경보단계', 'TEXT', '관심', NULL, 'FIXED', 'BLUE', '관심', 'BLUE', 500103, '{}', '{}'),
    ROW(500309, 500002, 2, @a_bn1_cmdr, @base - INTERVAL 126710 MINUTE, '위기경보단계', 'FIELD', NULL, 'stage', 'FIELD', NULL, '관심', 'BLUE', 500104, '{"stage":{"option":"a1"}}', '{}'),
    ROW(500310, 500002, 3, @a_bn1_cmdr, @base - INTERVAL 57600 MINUTE, '위기경보단계', 'FIELD', NULL, 'stage', 'FIELD', NULL, '주의', 'YELLOW', 500104, '{"stage":{"option":"a2"}}', '{}'),
    ROW(500311, 500002, 4, @a_bn1_cmdr, @base - INTERVAL 21600 MINUTE, '위기경보단계', 'FIELD', NULL, 'stage', 'FIELD', NULL, '관심', 'BLUE', 500104, '{"stage":{"option":"a1"}}', '{}'),
    ROW(500312, 500002, 5, @a_bn1_cmdr, @base - INTERVAL 5760 MINUTE, '위기경보단계', 'FIELD', NULL, 'stage', 'FIELD', NULL, '주의', 'YELLOW', 500104, '{"stage":{"option":"a2"}}', '{}'),
    ROW(500313, 500003, 1, @a_bn1_co1_admin, @base - INTERVAL 128190 MINUTE, '전장망', 'TEXT', '정상', NULL, 'FIXED', 'GREEN', '정상', 'GREEN', 500105, '{}', '{}'),
    ROW(500314, 500003, 2, @a_bn1_co1_admin, @base - INTERVAL 128160 MINUTE, '전장망', 'FIELD', NULL, 'status', 'FIELD', NULL, '정상', 'GREEN', 500106, '{"status":{"option":"ok"},"lines":"12"}', '{"tbl":500201}'),
    ROW(500315, 500003, 3, @a_bn1_co1_s1_m1, @base - INTERVAL 125160 MINUTE, '전장망', 'FIELD', NULL, 'status', 'FIELD', NULL, '정상', 'GREEN', 500106, '{"status":{"option":"ok"},"lines":"11"}', '{"tbl":500201}'),
    ROW(500316, 500003, 4, @a_bn1_co1_admin, @base - INTERVAL 122160 MINUTE, '전장망', 'FIELD', NULL, 'status', 'FIELD', NULL, '정상', 'GREEN', 500106, '{"status":{"option":"ok"},"lines":"12"}', '{"tbl":500201}'),
    ROW(500317, 500003, 5, @a_bn1_co1_s1_m1, @base - INTERVAL 119520 MINUTE, '전장망', 'FIELD', NULL, 'status', 'FIELD', NULL, '정상', 'GREEN', 500106, '{"status":{"option":"ok"},"lines":"11"}', '{"tbl":500201}'),
    ROW(500318, 500003, 6, @a_bn1_co1_admin, @base - INTERVAL 115080 MINUTE, '전장망', 'FIELD', NULL, 'status', 'FIELD', NULL, '정상', 'GREEN', 500106, '{"status":{"option":"ok"},"lines":"12"}', '{"tbl":500201}'),
    ROW(500319, 500003, 7, @a_bn1_co1_s1_m1, @base - INTERVAL 112080 MINUTE, '전장망', 'FIELD', NULL, 'status', 'FIELD', NULL, '정상', 'GREEN', 500106, '{"status":{"option":"ok"},"lines":"11"}', '{"tbl":500201}'),
    ROW(500320, 500003, 8, @a_bn1_co1_admin, @base - INTERVAL 109440 MINUTE, '전장망', 'FIELD', NULL, 'status', 'FIELD', NULL, '제한', 'YELLOW', 500106, '{"status":{"option":"lim"},"lines":"9"}', '{"tbl":500201}'),
    ROW(500321, 500003, 9, @a_bn1_co1_s1_m1, @base - INTERVAL 105000 MINUTE, '전장망', 'FIELD', NULL, 'status', 'FIELD', NULL, '제한', 'YELLOW', 500106, '{"status":{"option":"lim"},"lines":"8"}', '{"tbl":500201}'),
    ROW(500322, 500003, 10, @a_bn1_co1_admin, @base - INTERVAL 102000 MINUTE, '전장망', 'FIELD', NULL, 'status', 'FIELD', NULL, '정상', 'GREEN', 500106, '{"status":{"option":"ok"},"lines":"11"}', '{"tbl":500202}'),
    ROW(500323, 500003, 11, @a_bn1_co1_s1_m1, @base - INTERVAL 99360 MINUTE, '전장망', 'FIELD', NULL, 'status', 'FIELD', NULL, '정상', 'GREEN', 500106, '{"status":{"option":"ok"},"lines":"12"}', '{"tbl":500202}'),
    ROW(500324, 500003, 12, @a_bn1_co1_admin, @base - INTERVAL 94920 MINUTE, '전장망', 'FIELD', NULL, 'status', 'FIELD', NULL, '정상', 'GREEN', 500106, '{"status":{"option":"ok"},"lines":"11"}', '{"tbl":500202}'),
    ROW(500325, 500003, 13, @a_bn1_co1_s1_m1, @base - INTERVAL 91920 MINUTE, '전장망', 'FIELD', NULL, 'status', 'FIELD', NULL, '정상', 'GREEN', 500106, '{"status":{"option":"ok"},"lines":"12"}', '{"tbl":500202}'),
    ROW(500326, 500003, 14, @a_bn1_co1_admin, @base - INTERVAL 89280 MINUTE, '전장망', 'FIELD', NULL, 'status', 'FIELD', NULL, '정상', 'GREEN', 500106, '{"status":{"option":"ok"},"lines":"11"}', '{"tbl":500202}'),
    ROW(500327, 500003, 15, @a_bn1_co1_s1_m1, @base - INTERVAL 84840 MINUTE, '전장망', 'FIELD', NULL, 'status', 'FIELD', NULL, '정상', 'GREEN', 500106, '{"status":{"option":"ok"},"lines":"12"}', '{"tbl":500202}'),
    ROW(500328, 500003, 16, @a_bn1_co1_admin, @base - INTERVAL 81840 MINUTE, '전장망', 'FIELD', NULL, 'status', 'FIELD', NULL, '정상', 'GREEN', 500106, '{"status":{"option":"ok"},"lines":"11"}', '{"tbl":500202}'),
    ROW(500329, 500003, 17, @a_bn1_co1_s1_m1, @base - INTERVAL 79200 MINUTE, '전장망', 'FIELD', NULL, 'status', 'FIELD', NULL, '정상', 'GREEN', 500106, '{"status":{"option":"ok"},"lines":"12"}', '{"tbl":500202}'),
    ROW(500330, 500003, 18, @a_bn1_co1_admin, @base - INTERVAL 74760 MINUTE, '전장망', 'FIELD', NULL, 'status', 'FIELD', NULL, '정상', 'GREEN', 500106, '{"status":{"option":"ok"},"lines":"11"}', '{"tbl":500202}'),
    ROW(500331, 500003, 19, @a_bn1_co1_s1_m1, @base - INTERVAL 71760 MINUTE, '전장망', 'FIELD', NULL, 'status', 'FIELD', NULL, '불능', 'RED', 500106, '{"status":{"option":"bad"},"lines":"4"}', '{"tbl":500203}'),
    ROW(500332, 500003, 20, @a_bn1_co1_admin, @base - INTERVAL 69120 MINUTE, '전장망', 'FIELD', NULL, 'status', 'FIELD', NULL, '제한', 'YELLOW', 500106, '{"status":{"option":"lim"},"lines":"8"}', '{"tbl":500203}'),
    ROW(500333, 500003, 21, @a_bn1_co1_s1_m1, @base - INTERVAL 64680 MINUTE, '전장망', 'FIELD', NULL, 'status', 'FIELD', NULL, '제한', 'YELLOW', 500106, '{"status":{"option":"lim"},"lines":"7"}', '{"tbl":500203}'),
    ROW(500334, 500003, 22, @a_bn1_co1_admin, @base - INTERVAL 61680 MINUTE, '전장망', 'FIELD', NULL, 'status', 'FIELD', NULL, '정상', 'GREEN', 500106, '{"status":{"option":"ok"},"lines":"12"}', '{"tbl":500204}'),
    ROW(500335, 500003, 23, @a_bn1_co1_s1_m1, @base - INTERVAL 59040 MINUTE, '전장망', 'FIELD', NULL, 'status', 'FIELD', NULL, '정상', 'GREEN', 500106, '{"status":{"option":"ok"},"lines":"11"}', '{"tbl":500204}'),
    ROW(500336, 500003, 24, @a_bn1_co1_admin, @base - INTERVAL 54600 MINUTE, '전장망', 'FIELD', NULL, 'status', 'FIELD', NULL, '정상', 'GREEN', 500106, '{"status":{"option":"ok"},"lines":"12"}', '{"tbl":500204}'),
    ROW(500337, 500003, 25, @a_bn1_co1_s1_m1, @base - INTERVAL 51600 MINUTE, '전장망', 'FIELD', NULL, 'status', 'FIELD', NULL, '정상', 'GREEN', 500106, '{"status":{"option":"ok"},"lines":"11"}', '{"tbl":500204}'),
    ROW(500338, 500003, 26, @a_bn1_co1_admin, @base - INTERVAL 48960 MINUTE, '전장망', 'FIELD', NULL, 'status', 'FIELD', NULL, '정상', 'GREEN', 500106, '{"status":{"option":"ok"},"lines":"12"}', '{"tbl":500204}'),
    ROW(500339, 500003, 27, @a_bn1_co1_s1_m1, @base - INTERVAL 44520 MINUTE, '전장망', 'FIELD', NULL, 'status', 'FIELD', NULL, '정상', 'GREEN', 500106, '{"status":{"option":"ok"},"lines":"11"}', '{"tbl":500204}'),
    ROW(500340, 500003, 28, @a_bn1_co1_admin, @base - INTERVAL 41520 MINUTE, '전장망', 'FIELD', NULL, 'status', 'FIELD', NULL, '정상', 'GREEN', 500106, '{"status":{"option":"ok"},"lines":"12"}', '{"tbl":500204}'),
    ROW(500341, 500003, 29, @a_bn1_co1_s1_m1, @base - INTERVAL 38880 MINUTE, '전장망', 'FIELD', NULL, 'status', 'FIELD', NULL, '정상', 'GREEN', 500106, '{"status":{"option":"ok"},"lines":"11"}', '{"tbl":500204}'),
    ROW(500342, 500003, 30, @a_bn1_co1_admin, @base - INTERVAL 34440 MINUTE, '전장망', 'FIELD', NULL, 'status', 'FIELD', NULL, '정상', 'GREEN', 500106, '{"status":{"option":"ok"},"lines":"12"}', '{"tbl":500204}'),
    ROW(500343, 500003, 31, @a_bn1_co1_s1_m1, @base - INTERVAL 31440 MINUTE, '전장망', 'FIELD', NULL, 'status', 'FIELD', NULL, '정상', 'GREEN', 500106, '{"status":{"option":"ok"},"lines":"11"}', '{"tbl":500204}'),
    ROW(500344, 500003, 32, @a_bn1_co1_admin, @base - INTERVAL 28800 MINUTE, '전장망', 'FIELD', NULL, 'status', 'FIELD', NULL, '정상', 'GREEN', 500106, '{"status":{"option":"ok"},"lines":"12"}', '{"tbl":500204}'),
    ROW(500345, 500003, 33, @a_bn1_co1_s1_m1, @base - INTERVAL 24360 MINUTE, '전장망', 'FIELD', NULL, 'status', 'FIELD', NULL, '제한', 'YELLOW', 500106, '{"status":{"option":"lim"},"lines":"10"}', '{"tbl":500204}'),
    ROW(500346, 500003, 34, @a_bn1_co1_admin, @base - INTERVAL 21360 MINUTE, '전장망', 'FIELD', NULL, 'status', 'FIELD', NULL, '제한', 'YELLOW', 500106, '{"status":{"option":"lim"},"lines":"9"}', '{"tbl":500204}'),
    ROW(500347, 500003, 35, @a_bn1_co1_s1_m1, @base - INTERVAL 18720 MINUTE, '전장망', 'FIELD', NULL, 'status', 'FIELD', NULL, '정상', 'GREEN', 500106, '{"status":{"option":"ok"},"lines":"12"}', '{"tbl":500204}'),
    ROW(500348, 500003, 36, @a_bn1_co1_admin, @base - INTERVAL 14280 MINUTE, '전장망', 'FIELD', NULL, 'status', 'FIELD', NULL, '정상', 'GREEN', 500106, '{"status":{"option":"ok"},"lines":"11"}', '{"tbl":500204}'),
    ROW(500349, 500003, 37, @a_bn1_co1_s1_m1, @base - INTERVAL 11280 MINUTE, '전장망', 'FIELD', NULL, 'status', 'FIELD', NULL, '정상', 'GREEN', 500106, '{"status":{"option":"ok"},"lines":"12"}', '{"tbl":500204}'),
    ROW(500350, 500003, 38, @a_bn1_co1_admin, @base - INTERVAL 8640 MINUTE, '전장망', 'FIELD', NULL, 'status', 'FIELD', NULL, '정상', 'GREEN', 500106, '{"status":{"option":"ok"},"lines":"11"}', '{"tbl":500204}'),
    ROW(500351, 500003, 39, @a_bn1_co1_s1_m1, @base - INTERVAL 4200 MINUTE, '전장망', 'FIELD', NULL, 'status', 'FIELD', NULL, '정상', 'GREEN', 500106, '{"status":{"option":"ok"},"lines":"12"}', '{"tbl":500205}'),
    ROW(500352, 500003, 40, @a_bn1_co1_admin, @base - INTERVAL 1200 MINUTE, '전장망', 'FIELD', NULL, 'status', 'FIELD', NULL, '제한', 'YELLOW', 500106, '{"status":{"option":"lim"},"lines":"9"}', '{"tbl":500206}'),
    ROW(500353, 500004, 1, @a_bn1_co1_admin, @base - INTERVAL 122400 MINUTE, '기반통신', 'TEXT', '정상', NULL, 'FIXED', 'GREEN', '정상', 'GREEN', 500107, '{}', '{}'),
    ROW(500354, 500004, 2, @a_bn1_co1_admin, @base - INTERVAL 122390 MINUTE, '기반통신', 'FIELD', NULL, 'op', 'FIELD', NULL, '정상', 'GREEN', 500108, '{"op":{"option":"ok"}}', '{}'),
    ROW(500355, 500004, 3, @a_bn1_co1_admin, @base - INTERVAL 72000 MINUTE, '기반통신', 'FIELD', NULL, 'op', 'FIELD', NULL, '일부 제한', 'YELLOW', 500108, '{"op":{"option":"part"},"note":"교환기 1대 점검"}', '{}'),
    ROW(500356, 500004, 4, @a_bn1_co1_admin, @base - INTERVAL 64800 MINUTE, '기반통신', 'FIELD', NULL, 'op', 'FIELD', NULL, '정상', 'GREEN', 500108, '{"op":{"option":"ok"}}', '{}'),
    ROW(500357, 500004, 5, @a_bn1_co1_admin, @base - INTERVAL 28800 MINUTE, '기반통신', 'FIELD', NULL, 'op', 'FIELD', NULL, '일부 제한', 'YELLOW', 500108, '{"op":{"option":"part"},"note":"선로 공사"}', '{}'),
    ROW(500358, 500004, 6, @a_bn1_co1_admin, @base - INTERVAL 24480 MINUTE, '기반통신', 'FIELD', NULL, 'op', 'FIELD', NULL, '정상', 'GREEN', 500108, '{"op":{"option":"ok"}}', '{}'),
    ROW(500359, 500005, 1, @a_bn1_co1_s1_lead, @base - INTERVAL 115200 MINUTE, 'GPS 전파교란', 'TEXT', '없음', NULL, 'FIXED', 'GREEN', '없음', 'GREEN', 500109, '{}', '{}'),
    ROW(500360, 500005, 2, @a_bn1_co1_s1_lead, @base - INTERVAL 115190 MINUTE, 'GPS 전파교란', 'FIELD', NULL, 'jam', 'FIELD', NULL, '없음', 'GREEN', 500110, '{"jam":{"option":"none"}}', '{}'),
    ROW(500361, 500005, 3, @a_bn1_co1_s1_lead, @base - INTERVAL 47520 MINUTE, 'GPS 전파교란', 'FIELD', NULL, 'jam', 'FIELD', NULL, '있음', 'RED', 500110, '{"jam":{"option":"yes"},"at":"14:20","act":"수신기 재부팅, 상급 부대 보고"}', '{}'),
    ROW(500362, 500005, 4, @a_bn1_co1_s1_lead, @base - INTERVAL 47340 MINUTE, 'GPS 전파교란', 'FIELD', NULL, 'jam', 'FIELD', NULL, '없음', 'GREEN', 500110, '{"jam":{"option":"none"},"at":"14:20","act":"복구 확인"}', '{}'),
    ROW(500363, 500005, 5, @a_bn1_co1_s1_lead, @base - INTERVAL 7200 MINUTE, 'GPS 전파교란', 'FIELD', NULL, 'jam', 'FIELD', NULL, '없음', 'GREEN', 500110, '{"jam":{"option":"none"},"act":"정기 점검 이상 없음"}', '{}'),
    ROW(500364, 500005, 6, @a_bn1_co1_s1_lead, @base - INTERVAL 1740 MINUTE, 'GPS 전파교란', 'FIELD', NULL, 'jam', 'FIELD', NULL, '있음', 'RED', 500110, '{"jam":{"option":"yes"},"at":"06:40","act":"수신기 전원 재시작, 상급 부대 보고, 복구 확인 중"}', '{}'),
    ROW(500365, 500006, 1, @a_bn1_co2_s1_lead, @base - INTERVAL 90720 MINUTE, '교환기', 'TEXT', '정상', NULL, 'FIXED', 'GREEN', '정상', 'GREEN', 500111, '{}', '{}'),
    ROW(500366, 500006, 2, @a_bn1_co2_s1_lead, @base - INTERVAL 89270 MINUTE, '교환기', 'FIELD', NULL, 'status', 'FIELD', NULL, '정상', 'GREEN', 500112, '{"status":{"option":"ok"},"used":"42"}', '{}'),
    ROW(500367, 500006, 3, @a_bn1_co2_s1_lead, @base - INTERVAL 69420 MINUTE, '교환기', 'FIELD', NULL, 'status', 'FIELD', NULL, '점검 중', 'BLUE', 500112, '{"status":{"option":"chk"},"used":"42","memo":"분기 접속 시험"}', '{}'),
    ROW(500368, 500006, 4, @a_bn1_co2_s1_m1, @base - INTERVAL 69120 MINUTE, '교환기', 'FIELD', NULL, 'status', 'FIELD', NULL, '정상', 'GREEN', 500112, '{"status":{"option":"ok"},"used":"42","memo":"접속 시험 이상 없음"}', '{}'),
    ROW(500369, 500006, 5, @a_bn1_co2_s1_m1, @base - INTERVAL 47520 MINUTE, '교환기', 'FIELD', NULL, 'status', 'FIELD', NULL, '정상', 'GREEN', 500112, '{"status":{"option":"ok"},"used":"44","memo":"행정반 내선 2회선 증설"}', '{}'),
    ROW(500370, 500006, 6, @a_bn1_co2_s1_lead, @base - INTERVAL 29000 MINUTE, '교환기', 'FIELD', NULL, 'status', 'FIELD', NULL, '장애', 'RED', 500112, '{"status":{"option":"bad"},"used":"44","memo":"전원부 경보, 내선 일부 불통"}', '{}'),
    ROW(500371, 500006, 7, @a_bn1_co2_s1_lead, @base - INTERVAL 28800 MINUTE, '교환기', 'FIELD', NULL, 'status', 'FIELD', NULL, '점검 중', 'BLUE', 500112, '{"status":{"option":"chk"},"used":"44","memo":"예비 전원으로 전환, 정비 요청"}', '{}'),
    ROW(500372, 500006, 8, @a_bn1_co2_s1_m1, @base - INTERVAL 24480 MINUTE, '교환기', 'FIELD', NULL, 'status', 'FIELD', NULL, '정상', 'GREEN', 500112, '{"status":{"option":"ok"},"used":"44","memo":"전원부 교체 완료"}', '{}'),
    ROW(500373, 500006, 9, @a_bn1_co2_s1_m1, @base - INTERVAL 4320 MINUTE, '교환기', 'FIELD', NULL, 'status', 'FIELD', NULL, '점검 중', 'BLUE', 500112, '{"status":{"option":"chk"},"used":"44","memo":"분기 접속 시험(11-03까지)"}', '{}'),
    ROW(500374, 500007, 1, @a_bn1_co2_s2_lead, @base - INTERVAL 83520 MINUTE, '무전망', 'TEXT', '양호', NULL, 'FIXED', 'GREEN', '양호', 'GREEN', 500113, '{}', '{}'),
    ROW(500375, 500007, 2, @a_bn1_co2_s2_lead, @base - INTERVAL 83510 MINUTE, '무전망', 'FIELD', NULL, 'link', 'FIELD', NULL, '양호', 'GREEN', 500114, '{"link":{"option":"good"},"at":"09:00"}', '{}'),
    ROW(500376, 500007, 3, @a_bn1_co2_s2_lead, @base - INTERVAL 53280 MINUTE, '무전망', 'FIELD', NULL, 'link', 'FIELD', NULL, '불량', 'ORANGE', 500114, '{"link":{"option":"poor"},"at":"21:30","act":"야간 교신 잡음, 안테나 연결부 점검"}', '{}'),
    ROW(500377, 500007, 4, @a_bn1_co2_s2_lead, @base - INTERVAL 51840 MINUTE, '무전망', 'FIELD', NULL, 'link', 'FIELD', NULL, '양호', 'GREEN', 500114, '{"link":{"option":"good"},"at":"09:10","act":"안테나 케이블 교체 뒤 양호"}', '{}'),
    ROW(500378, 500007, 5, @a_bn1_co2_s2_lead, @base - INTERVAL 17280 MINUTE, '무전망', 'FIELD', NULL, 'link', 'FIELD', NULL, '양호', 'GREEN', 500114, '{"link":{"option":"good"},"at":"08:40","act":"정기 교신 점검 이상 없음"}', '{}'),
    ROW(500379, 500007, 6, @a_bn1_co2_s2_lead, @base - INTERVAL 2880 MINUTE, '무전망', 'FIELD', NULL, 'link', 'FIELD', NULL, '불량', 'ORANGE', 500114, '{"link":{"option":"poor"},"at":"08:30","act":"야간 교신 잡음 재발, 충전기 2대 고장으로 예비 충전기 사용"}', '{}'),
    ROW(500380, 500008, 1, @a_bn2_co1_cmdr, @base - INTERVAL 100800 MINUTE, '전장망', 'TEXT', '정상', NULL, 'FIXED', 'GREEN', '정상', 'GREEN', 500115, '{}', '{}'),
    ROW(500381, 500008, 2, @a_bn2_co1_cmdr, @base - INTERVAL 99270 MINUTE, '전장망', 'FIELD', NULL, 'status', 'FIELD', NULL, '정상', 'GREEN', 500116, '{"status":{"option":"ok"},"lines":"8"}', '{}'),
    ROW(500382, 500008, 3, @a_bn2_co1_s1_lead, @base - INTERVAL 91920 MINUTE, '전장망', 'FIELD', NULL, 'status', 'FIELD', NULL, '정상', 'GREEN', 500116, '{"status":{"option":"ok"},"lines":"7","note":"1반 구간 회선 1개 점검"}', '{}'),
    ROW(500383, 500008, 4, @a_bn2_co1_cmdr, @base - INTERVAL 84870 MINUTE, '전장망', 'FIELD', NULL, 'status', 'FIELD', NULL, '정상', 'GREEN', 500116, '{"status":{"option":"ok"},"lines":"8"}', '{}'),
    ROW(500384, 500008, 5, @a_bn2_co1_s1_lead, @base - INTERVAL 77520 MINUTE, '전장망', 'FIELD', NULL, 'status', 'FIELD', NULL, '제한', 'YELLOW', 500116, '{"status":{"option":"lim"},"lines":"5","note":"중계소 전원 불안정"}', '{}'),
    ROW(500385, 500008, 6, @a_bn2_co1_cmdr, @base - INTERVAL 76230 MINUTE, '전장망', 'FIELD', NULL, 'status', 'FIELD', NULL, '정상', 'GREEN', 500116, '{"status":{"option":"ok"},"lines":"8","note":"전원 복구"}', '{}'),
    ROW(500386, 500008, 7, @a_bn2_co1_s1_lead, @base - INTERVAL 67440 MINUTE, '전장망', 'FIELD', NULL, 'status', 'FIELD', NULL, '정상', 'GREEN', 500116, '{"status":{"option":"ok"},"lines":"7"}', '{}'),
    ROW(500387, 500008, 8, @a_bn2_co1_cmdr, @base - INTERVAL 58950 MINUTE, '전장망', 'FIELD', NULL, 'status', 'FIELD', NULL, '정상', 'GREEN', 500116, '{"status":{"option":"ok"},"lines":"8"}', '{}'),
    ROW(500388, 500008, 9, @a_bn2_co1_s1_lead, @base - INTERVAL 50160 MINUTE, '전장망', 'FIELD', NULL, 'status', 'FIELD', NULL, '불능', 'RED', 500116, '{"status":{"option":"bad"},"lines":"2","note":"대대-1중대 구간 선로 단선, 복구 중"}', '{}'),
    ROW(500389, 500008, 10, @a_bn2_co1_cmdr, @base - INTERVAL 48870 MINUTE, '전장망', 'FIELD', NULL, 'status', 'FIELD', NULL, '제한', 'YELLOW', 500116, '{"status":{"option":"lim"},"lines":"6","note":"임시 회선으로 우회"}', '{}'),
    ROW(500390, 500008, 11, @a_bn2_co1_s1_lead, @base - INTERVAL 45840 MINUTE, '전장망', 'FIELD', NULL, 'status', 'FIELD', NULL, '정상', 'GREEN', 500116, '{"status":{"option":"ok"},"lines":"8","note":"선로 복구 완료"}', '{}'),
    ROW(500391, 500008, 12, @a_bn2_co1_cmdr, @base - INTERVAL 34470 MINUTE, '전장망', 'FIELD', NULL, 'status', 'FIELD', NULL, '정상', 'GREEN', 500116, '{"status":{"option":"ok"},"lines":"7"}', '{}'),
    ROW(500392, 500008, 13, @a_bn2_co1_s1_lead, @base - INTERVAL 22800 MINUTE, '전장망', 'FIELD', NULL, 'status', 'FIELD', NULL, '정상', 'GREEN', 500116, '{"status":{"option":"ok"},"lines":"8"}', '{}'),
    ROW(500393, 500008, 14, @a_bn2_co1_cmdr, @base - INTERVAL 8550 MINUTE, '전장망', 'FIELD', NULL, 'status', 'FIELD', NULL, '제한', 'YELLOW', 500116, '{"status":{"option":"lim"},"lines":"6","note":"2반 구간 단말기 정비입고"}', '{}'),
    ROW(500394, 500008, 15, @a_bn2_co1_s1_lead, @base - INTERVAL 1200 MINUTE, '전장망', 'FIELD', NULL, 'status', 'FIELD', NULL, '제한', 'YELLOW', 500116, '{"status":{"option":"lim"},"lines":"7","note":"단말기 C 정비 중, 2반 구간은 무전망으로 확인"}', '{}'),
    ROW(500395, 500009, 1, @a_bn2_co1_cmdr, @base - INTERVAL 95040 MINUTE, '통신소 운용', 'TEXT', '정상', NULL, 'FIXED', 'GREEN', '정상', 'GREEN', 500117, '{}', '{}'),
    ROW(500396, 500009, 2, @a_bn2_co1_cmdr, @base - INTERVAL 95030 MINUTE, '통신소 운용', 'FIELD', NULL, 'op', 'FIELD', NULL, '정상', 'GREEN', 500118, '{"op":{"option":"ok"}}', '{}'),
    ROW(500397, 500009, 3, @a_bn2_co1_cmdr, @base - INTERVAL 38880 MINUTE, '통신소 운용', 'FIELD', NULL, 'op', 'FIELD', NULL, '일부 제한', 'YELLOW', 500118, '{"op":{"option":"part"},"note":"발전기 정비로 예비 전원 운용"}', '{}'),
    ROW(500398, 500009, 4, @a_bn2_co1_cmdr, @base - INTERVAL 36000 MINUTE, '통신소 운용', 'FIELD', NULL, 'op', 'FIELD', NULL, '정상', 'GREEN', 500118, '{"op":{"option":"ok"},"note":"발전기 정비 완료"}', '{}')
) AS v WHERE @ok;

INSERT INTO ict_box_revision (id, box_id, revision_no, changed_by_account_id, changed_at, title, summary_mode, summary_text, summary_field,
    color_mode, color, shown_summary, shown_color, schema_id, values_json, tables_json)
SELECT * FROM (VALUES
    ROW(500399, 500010, 1, @a_bn2_cmdr, @base - INTERVAL 109440 MINUTE, 'CPCON', 'TEXT', '5단계', NULL, 'FIXED', 'GREEN', '5단계', 'GREEN', 500119, '{}', '{}'),
    ROW(500400, 500010, 2, @a_bn2_cmdr, @base - INTERVAL 109430 MINUTE, 'CPCON', 'FIELD', NULL, 'stage', 'FIELD', NULL, '5단계', 'GREEN', 500120, '{"stage":{"option":"s5"}}', '{}'),
    ROW(500401, 500010, 3, @a_bn2_cmdr, @base - INTERVAL 74880 MINUTE, 'CPCON', 'FIELD', NULL, 'stage', 'FIELD', NULL, '4단계', 'BLUE', 500120, '{"stage":{"option":"s4"},"note":"대대 전술훈련 기간"}', '{}'),
    ROW(500402, 500010, 4, @a_bn2_cmdr, @base - INTERVAL 54720 MINUTE, 'CPCON', 'FIELD', NULL, 'stage', 'FIELD', NULL, '3단계', 'YELLOW', 500120, '{"stage":{"option":"s3"},"note":"지휘소 연습 대비 상향"}', '{}'),
    ROW(500403, 500010, 5, @a_bn2_cmdr, @base - INTERVAL 44640 MINUTE, 'CPCON', 'FIELD', NULL, 'stage', 'FIELD', NULL, '4단계', 'BLUE', 500120, '{"stage":{"option":"s4"},"note":"연습 종료"}', '{}'),
    ROW(500404, 500010, 6, @a_bn2_cmdr, @base - INTERVAL 12960 MINUTE, 'CPCON', 'FIELD', NULL, 'stage', 'FIELD', NULL, '4단계', 'BLUE', 500120, '{"stage":{"option":"s4"},"note":"동계 전투준비 점검 기간"}', '{}'),
    ROW(500405, 500010, 7, @a_bn2_cmdr, @base - INTERVAL 1560 MINUTE, 'CPCON', 'FIELD', NULL, 'stage', 'FIELD', NULL, '3단계', 'YELLOW', 500120, '{"stage":{"option":"s3"},"note":"11월 첫째 주 지휘소 연습으로 상향"}', '{}')
) AS v WHERE @ok AND @ok_bn2;

INSERT INTO ict_box_scope (id, box_id, personnel_id, department_id, include_sub)
SELECT * FROM (VALUES
    ROW(500501, 500001, NULL, 1, TRUE),
    ROW(500502, 500002, NULL, 1, TRUE),
    ROW(500503, 500003, NULL, 3, FALSE),
    ROW(500504, 500005, NULL, 2, TRUE),
    ROW(500505, 500006, NULL, 3, TRUE),
    ROW(500506, 500007, NULL, 3, TRUE),
    ROW(500507, 500008, NULL, 11, FALSE),
    ROW(500508, 500008, NULL, 12, TRUE)
) AS v WHERE @ok;

INSERT INTO ict_box_scope (id, box_id, personnel_id, department_id, include_sub)
SELECT * FROM (VALUES
    ROW(500509, 500010, NULL, 11, TRUE)
) AS v WHERE @ok AND @ok_bn2;

INSERT INTO ict_box_assignee (box_id, personnel_id, assigned_by_account_id, assigned_at)
SELECT 500003, (SELECT personnel_id FROM account WHERE id = @a_bn1_co1_s1_m1), @a_bn1_co1_admin, @base - INTERVAL 126720 MINUTE FROM DUAL WHERE @ok
UNION ALL
SELECT 500006, (SELECT personnel_id FROM account WHERE id = @a_bn1_co2_s1_m1), @a_bn1_co2_s1_lead, @base - INTERVAL 72000 MINUTE FROM DUAL WHERE @ok
UNION ALL
SELECT 500008, (SELECT personnel_id FROM account WHERE id = @a_bn2_co1_s1_lead), @a_bn2_co1_cmdr, @base - INTERVAL 95040 MINUTE FROM DUAL WHERE @ok;

-- 기록 일: (박스, 판 연도)마다 한 줄
INSERT INTO ict_record_job (box_id, year, requested_at)
SELECT DISTINCT box_id, YEAR(changed_at), @base FROM ict_box_revision WHERE box_id BETWEEN 500001 AND 509999 AND @ok;

-- 범위 카드: bn1-co1-admin의 "1대대 전체", bn2-co1-cmdr의 "2대대 전체"
INSERT INTO scope_card (id, account_id, name, created_at, updated_at)
SELECT * FROM (VALUES
    ROW(500601, @a_bn1_co1_admin, '1대대 전체', @base - INTERVAL 86400 MINUTE, @base - INTERVAL 86400 MINUTE),
    ROW(500602, @a_bn2_co1_cmdr, '2대대 전체', @base - INTERVAL 57600 MINUTE, @base - INTERVAL 57600 MINUTE)
) AS v WHERE @ok;
INSERT INTO scope_card_entry (id, card_id, personnel_id, department_id, include_sub)
SELECT * FROM (VALUES
    ROW(500611, 500601, NULL, 1, TRUE),
    ROW(500612, 500602, NULL, 11, TRUE)
) AS v WHERE @ok;

SELECT @ok AS ict_status_demo_applied, @ok_bn2 AS ict_status_bn2_applied, (SELECT COUNT(*) FROM ict_box WHERE id >= 500001) AS ict_boxes, (SELECT COUNT(*) FROM ict_box_revision WHERE box_id >= 500001) AS ict_revisions;
