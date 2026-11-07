-- 시연용 부대 지시사항 (명세 7장, REQ-601, slices/13 7장, slices/21 9장, slices/22 4.3). 손으로 쓴 파일이다(생성기 없음). 내용은 모두 가상(NFR-002)
-- 이름이 R__seed_work_directive_demo라 초기 데이터·시연 업무 다음, 시연 문서함 앞에 돈다(계정·역할만 기댐)
-- 지시사항 33건: 정보통신대대장·정보통신대대 중대장 4명(본부중대 포함)·유선반장·무선반장, 근무지원대대장·근무지원대대 중대장 4명 명의(명의 12개),
-- 2026-10-30 ~ 2026-11-19. 고친 건 4건(UPDATE 기록), 시스템 관리자가 대신 올린 건 3건, 내린 건 1건(300008, TAKE_DOWN 기록), 그림 1장(300032).
-- 반장 명의 2건(300032 유선반·300033 무선반)으로 반원에게 [지시사항] 탭 박스가 대대 → 중대 → 반 순서로 보인다(DirectiveAccess.boxes).
-- 정보통신상황실 명의는 두지 않는다: 지시사항은 명의 부서와 그 하위 소속만 보므로 상황실 명의로 올리면 대대장도 보지 못한다(DirectiveAccess.canView, slices/21 2.1-3)
-- 지시사항이 하나라도 있거나 번호 범위(300001~)가 쓰였거나, 쓸 계정(사용 중)과 그 명의의 사용 중 부서장 역할(대상 부서 일치, 부서 사용 중)·
-- 내린 사람의 부서 관리자 역할이 하나라도 없으면 전체를 건너뛴다. 그림 파일(stored_file 300001~300099)도 범위가 비어 있어야 한다.
-- 알림 줄은 넣지 않는다. 시각은 시연 기준 시각 2026-11-19(목) 09:00:00(KST, 고정) 앞의 평일 근무 시간이고, 지시 일자는 만든 시각의 날짜다.
-- 11-19 건은 08:00~08:20에 둔다(시연 PC가 그날 아침에 DB를 만들어도 앞날 기록이 되지 않게)

SET @a_sysadmin = (SELECT id FROM account WHERE login_id = 'sysadmin' AND active = TRUE);
SET @a_bn1_cmdr = (SELECT id FROM account WHERE login_id = 'bn1-cmdr' AND active = TRUE);
SET @a_bn1_hq_cmdr = (SELECT id FROM account WHERE login_id = 'bn1-hq-cmdr' AND active = TRUE);
SET @a_bn1_co1_cmdr = (SELECT id FROM account WHERE login_id = 'bn1-co1-cmdr' AND active = TRUE);
SET @a_bn1_co2_cmdr = (SELECT id FROM account WHERE login_id = 'bn1-co2-cmdr' AND active = TRUE);
SET @a_bn1_co3_cmdr = (SELECT id FROM account WHERE login_id = 'bn1-co3-cmdr' AND active = TRUE);
SET @a_bn1_co1_s1_lead = (SELECT id FROM account WHERE login_id = 'bn1-co1-s1-lead' AND active = TRUE);
SET @a_bn1_co1_s2_lead = (SELECT id FROM account WHERE login_id = 'bn1-co1-s2-lead' AND active = TRUE);
SET @a_bn1_co1_admin = (SELECT id FROM account WHERE login_id = 'bn1-co1-admin' AND active = TRUE);
SET @a_bn2_cmdr = (SELECT id FROM account WHERE login_id = 'bn2-cmdr' AND active = TRUE);
SET @a_bn2_co1_cmdr = (SELECT id FROM account WHERE login_id = 'bn2-co1-cmdr' AND active = TRUE);
SET @a_bn2_co2_cmdr = (SELECT id FROM account WHERE login_id = 'bn2-co2-cmdr' AND active = TRUE);
SET @a_bn2_co3_cmdr = (SELECT id FROM account WHERE login_id = 'bn2-co3-cmdr' AND active = TRUE);
SET @a_bn2_co4_cmdr = (SELECT id FROM account WHERE login_id = 'bn2-co4-cmdr' AND active = TRUE);

-- 명의 12개: (명의자, 부서장 역할, 명의 부서)마다 사용 중인 그 역할이 있고 부서가 사용 중이어야 한다(역할은 (계정, 역할, 부서)가 유일, V2).
-- 내린 사람 bn1-co1-admin은 1중대(명의 부서)를 바로 대상으로 한 부서 관리자 역할이 있어야 한다(DirectiveAccess.canTakeDown: 볼 수 있고 deptAdminOf(명의 부서))
SET @ok = (SELECT COUNT(*) FROM directive) = 0
    AND (SELECT COALESCE(MAX(id), 0) FROM directive_audit) < 300001
    AND (SELECT COALESCE(MAX(directive_id), 0) FROM directive_audit) < 300001
    AND (SELECT COUNT(*) FROM stored_file WHERE id BETWEEN 300001 AND 300099) = 0
    AND @a_sysadmin IS NOT NULL
    AND (SELECT COUNT(*) FROM (VALUES
            ROW(@a_bn1_cmdr, 'BN_CMDR', 1), ROW(@a_bn1_hq_cmdr, 'CO_CMDR', 21), ROW(@a_bn1_co1_cmdr, 'CO_CMDR', 2),
            ROW(@a_bn1_co2_cmdr, 'CO_CMDR', 3), ROW(@a_bn1_co3_cmdr, 'CO_CMDR', 4),
            ROW(@a_bn1_co1_s1_lead, 'SEC_LEAD', 5), ROW(@a_bn1_co1_s2_lead, 'SEC_LEAD', 6),
            ROW(@a_bn2_cmdr, 'BN_CMDR', 11), ROW(@a_bn2_co1_cmdr, 'CO_CMDR', 12), ROW(@a_bn2_co2_cmdr, 'CO_CMDR', 13),
            ROW(@a_bn2_co3_cmdr, 'CO_CMDR', 14), ROW(@a_bn2_co4_cmdr, 'CO_CMDR', 23),
            ROW(@a_bn1_co1_admin, 'DEPT_ADMIN', 2)
         ) AS n (account_id, role_code, department_id)
         JOIN role_assignment r ON r.account_id = n.account_id AND r.role_code = n.role_code AND r.department_id = n.department_id AND r.active
         JOIN department d ON d.id = n.department_id AND d.active) = 13;

-- (번호, 명의 부서, 명의자, 올린 계정, 내용, 만든 시각, 고친 시각, 판). 지시 일자 = 만든 시각의 날짜.
-- 만든 날·고친 날은 평일 근무 시간, 기한은 날짜(요일)로 적는다. 대신 올림: 300006·300011·300016(올린 계정 sysadmin → 명의자에게만 "시스템 관리자가 대신 올림")
-- 고침: 300002·300009·300017·300019 ("고침(시각)"). 부서마다 지시 일자가 가장 늦은 건이 [지시사항] 탭 박스에 보인다(명의 부서 12개 모두 한 건 이상).
-- 내림: 300008(기한 11-06 지남)은 아래 UPDATE로 내린다. 1중대의 가장 늦은 건은 300011이라 1중대 박스는 그대로 보인다
-- 업무 시나리오(시연 업무 G·A~F)와 겹치는 내용은 두지 않는다
INSERT INTO directive (id, department_id, named_account_id, written_by_account_id, content, directive_date, created_at, edited_at, version)
SELECT v.id, v.department_id, v.named, v.writer, v.content, DATE(v.created_at), v.created_at, v.edited_at, v.version FROM (VALUES
    -- 정보통신대대
    ROW(300001, 1, @a_bn1_cmdr, @a_bn1_cmdr, '동절기 대비 발전기·무정전 전원장치 점검을 11월 13일(금)까지 마치고 결과를 보고할 것', TIMESTAMP('2026-10-30 09:00:00'), NULL, 0),
    ROW(300002, 1, @a_bn1_cmdr, @a_bn1_cmdr, CONCAT('정보통신 장비 보안 점검(비인가 저장매체 연결 여부)을 부서마다 월 1회 실시할 것', CHAR(10), '점검 결과는 업무 등록으로 남기고, 이상이 있으면 그날 보고할 것'), TIMESTAMP('2026-11-02 08:40:00'), TIMESTAMP('2026-11-03 09:15:00'), 1),
    ROW(300003, 1, @a_bn1_cmdr, @a_bn1_cmdr, CONCAT('12월 1일(화) ~ 12월 3일(목) 사단 정보통신 종합 점검 대상 장비 목록을 중대별로 11월 20일(금)까지 제출할 것', CHAR(10), '점검 때 반출할 장비는 장비 목록에서 반출 예정으로 표시할 것'), TIMESTAMP('2026-11-11 10:00:00'), NULL, 0),
    ROW(300004, 1, @a_bn1_cmdr, @a_bn1_cmdr, '오늘 오전 대대 정보통신 회의 전까지 중대별 고장·정비입고 장비를 장비 목록에서 확인해 올 것', TIMESTAMP('2026-11-19 08:10:00'), NULL, 0),
    -- 정보통신대대 본부중대
    ROW(300005, 21, @a_bn1_hq_cmdr, @a_bn1_hq_cmdr, '야간 당직 근무자는 근무 투입 전에 정보통신상황실에 들러 그날 통신망 특이사항을 확인할 것', TIMESTAMP('2026-11-03 13:30:00'), NULL, 0),
    ROW(300006, 21, @a_bn1_hq_cmdr, @a_sysadmin, '정보통신상황실 출입자 명부를 매일 일과 마감 때 확인하고 비인가 출입이 없었는지 중대장에게 보고할 것', TIMESTAMP('2026-11-09 09:00:00'), NULL, 0),
    ROW(300007, 21, @a_bn1_hq_cmdr, @a_bn1_hq_cmdr, CONCAT('12월 상황실 근무표와 야간 당직 근무표를 11월 27일(금)까지 근무자 현황에 올릴 것', CHAR(10), '근무가 바뀐 사람에게는 상황실장이 직접 알릴 것'), TIMESTAMP('2026-11-17 15:00:00'), NULL, 0),
    -- 1중대
    ROW(300008, 2, @a_bn1_co1_cmdr, @a_bn1_co1_cmdr, '중대 통신창고의 케이블·단자함 자재 수량을 장비 대장과 맞추고 11월 6일(금)까지 보고할 것', TIMESTAMP('2026-10-30 14:00:00'), NULL, 0),
    ROW(300009, 2, @a_bn1_co1_cmdr, @a_bn1_co1_cmdr, CONCAT('야간 통신 점검은 무전기, 유선 전화, 구내 교환기 순서로 실시할 것', CHAR(10), '점검 뒤 근무 교대 때 인수인계 대장에 결과를 적고 서로 확인할 것'), TIMESTAMP('2026-11-05 08:30:00'), TIMESTAMP('2026-11-05 16:20:00'), 1),
    ROW(300010, 2, @a_bn1_co1_cmdr, @a_bn1_co1_cmdr, '무전기 배터리는 3개월마다 교체하고 교체한 날짜를 장비 비고에 적을 것', TIMESTAMP('2026-11-12 10:30:00'), NULL, 0),
    ROW(300011, 2, @a_bn1_co1_cmdr, @a_sysadmin, '오늘 일과 중에 중대 통신창고의 무전기 충전기 상태를 확인하고 고장 난 충전기는 대장에 표시할 것', TIMESTAMP('2026-11-19 08:20:00'), NULL, 0),
    -- 2중대
    ROW(300012, 3, @a_bn1_co2_cmdr, @a_bn1_co2_cmdr, CONCAT('중대본부 PC 백신 갱신 상태를 매주 금요일에 확인할 것', CHAR(10), '확인 결과는 반장이 모아 중대장에게 보고할 것'), TIMESTAMP('2026-11-02 10:00:00'), NULL, 0),
    ROW(300013, 3, @a_bn1_co2_cmdr, @a_bn1_co2_cmdr, '회의실 화상회의 단말은 쓴 뒤에 카메라 덮개를 닫고 전원을 끌 것', TIMESTAMP('2026-11-10 14:00:00'), NULL, 0),
    ROW(300014, 3, @a_bn1_co2_cmdr, @a_bn1_co2_cmdr, '반마다 동계 장비 보관 상태(습기·결로)를 11월 27일(금)까지 점검하고 이상 장비는 정비입고 처리할 것', TIMESTAMP('2026-11-16 16:00:00'), NULL, 0),
    -- 3중대
    ROW(300015, 4, @a_bn1_co3_cmdr, @a_bn1_co3_cmdr, CONCAT('전산실 출입은 출입자 명부에 적고 서버반장 허가를 받은 인원만 할 것', CHAR(10), '외부 정비업체는 반원이 함께 들어갈 것'), TIMESTAMP('2026-11-04 09:30:00'), NULL, 0),
    ROW(300016, 4, @a_bn1_co3_cmdr, @a_sysadmin, '서버 정기 백업 결과를 매주 월요일 오전에 확인하고 실패한 백업은 그날 다시 실행할 것', TIMESTAMP('2026-11-09 13:30:00'), NULL, 0),
    ROW(300017, 4, @a_bn1_co3_cmdr, @a_bn1_co3_cmdr, '방화벽 정책 변경은 방화벽 정책 신청서로만 받고, 적용 전에 네트워크반장이 내용을 확인할 것', TIMESTAMP('2026-11-13 10:00:00'), TIMESTAMP('2026-11-16 08:50:00'), 1),
    -- 근무지원대대
    ROW(300018, 11, @a_bn2_cmdr, @a_bn2_cmdr, '근무지원대대 보안 점검(행정용 PC 비밀번호·화면 보호기·저장매체)을 11월 6일(금)까지 실시할 것', TIMESTAMP('2026-10-30 10:00:00'), NULL, 0),
    ROW(300019, 11, @a_bn2_cmdr, @a_bn2_cmdr, CONCAT('동계 전투준비 기간에 정보통신 장비 고장은 업무 요청으로만 올릴 것', CHAR(10), '전화로 받은 고장 신고도 받은 사람이 업무 요청으로 다시 올릴 것'), TIMESTAMP('2026-11-05 09:00:00'), TIMESTAMP('2026-11-06 08:20:00'), 1),
    ROW(300020, 11, @a_bn2_cmdr, @a_bn2_cmdr, '오늘 오후 대대 지휘관 회의 전까지 중대별 정보통신 업무 요청 진행 상황을 확인해 올 것', TIMESTAMP('2026-11-19 08:00:00'), NULL, 0),
    -- 근무지원대대 본부중대
    ROW(300021, 12, @a_bn2_co1_cmdr, @a_bn2_co1_cmdr, '대대 당직 근무자는 근무 교대 때 당직실 전화기·무전기 상태를 인수인계 대장에 적을 것', TIMESTAMP('2026-11-03 08:30:00'), NULL, 0),
    ROW(300022, 12, @a_bn2_co1_cmdr, @a_bn2_co1_cmdr, '교육장 사용은 교육장 사용 안내 문서에 따라 사용일 사흘 전까지 행정반에 신청할 것', TIMESTAMP('2026-11-11 15:30:00'), NULL, 0),
    ROW(300023, 12, @a_bn2_co1_cmdr, @a_bn2_co1_cmdr, '행정반 복합기 토너·용지 재고를 매주 월요일에 확인하고 부족하면 보급반에 청구할 것', TIMESTAMP('2026-11-17 10:00:00'), NULL, 0),
    -- 보급수송중대
    ROW(300024, 13, @a_bn2_co2_cmdr, @a_bn2_co2_cmdr, '차량용 무전기 거치대가 풀린 차량은 운행 전에 정보통신대대 무선반에 업무 요청을 올릴 것', TIMESTAMP('2026-11-02 14:00:00'), NULL, 0),
    ROW(300025, 13, @a_bn2_co2_cmdr, @a_bn2_co2_cmdr, '수송반은 차량 운행 일지에 차량용 무전기 교신 시험 결과를 함께 적을 것', TIMESTAMP('2026-11-06 10:30:00'), NULL, 0),
    ROW(300026, 13, @a_bn2_co2_cmdr, @a_bn2_co2_cmdr, CONCAT('보급창고 야전 발전기는 매주 금요일에 시동 점검을 하고 결과를 보급 대장에 적을 것', CHAR(10), '시동이 걸리지 않으면 그날 정보통신대대 무선반에 업무 요청을 올릴 것'), TIMESTAMP('2026-11-13 09:30:00'), NULL, 0),
    -- 복지중대
    ROW(300027, 14, @a_bn2_co3_cmdr, @a_bn2_co3_cmdr, '사이버지식정보방 PC는 사용수칙에 따라 이용 시간이 끝나면 전원을 끄고 이용 대장을 마감할 것', TIMESTAMP('2026-11-04 11:00:00'), NULL, 0),
    ROW(300028, 14, @a_bn2_co3_cmdr, @a_bn2_co3_cmdr, '복지시설 전화기와 인터넷이 고장 나면 반장이 정보통신대대에 업무 요청을 올리고 처리될 때까지 안내문을 붙일 것', TIMESTAMP('2026-11-12 14:30:00'), NULL, 0),
    -- 의무중대
    ROW(300029, 23, @a_bn2_co4_cmdr, @a_bn2_co4_cmdr, '진료실 PC는 진료실 PC 사용 안내에 따라 진료 기록 프로그램만 쓰고 개인 저장매체를 연결하지 말 것', TIMESTAMP('2026-11-05 13:00:00'), NULL, 0),
    ROW(300030, 23, @a_bn2_co4_cmdr, @a_bn2_co4_cmdr, CONCAT('진료 당직 근무자는 야간 응급 연락용 전화기와 무전기를 근무 시작 때 시험할 것', CHAR(10), '이상이 있으면 대대 당직에 알리고 업무 요청을 올릴 것'), TIMESTAMP('2026-11-10 09:00:00'), NULL, 0),
    ROW(300031, 23, @a_bn2_co4_cmdr, @a_bn2_co4_cmdr, '12월 진료 당직 근무표를 11월 27일(금)까지 확정하고 근무자 현황에서 근무자 본인이 확인하게 할 것', TIMESTAMP('2026-11-18 16:30:00'), NULL, 0),
    -- 1중대 유선반·무선반 (반장 명의, slices/22 4.3). 300032에는 그림 1장(아래 directive_image)
    ROW(300032, 5, @a_bn1_co1_s1_lead, @a_bn1_co1_s1_lead, CONCAT('단자함 회선 번호표는 첨부 그림처럼 단자 오른쪽에 붙일 것', CHAR(10), '떨어지거나 번호가 지워진 번호표는 11월 27일(금)까지 다시 붙이고 구내교환기 번호대장과 맞출 것'), TIMESTAMP('2026-11-16 10:00:00'), NULL, 0),
    ROW(300033, 6, @a_bn1_co1_s2_lead, @a_bn1_co1_s2_lead, '예비 무전기는 하루 단위로 빌려주고, 빌려 간 부서가 다음 날 09:00까지 무선반 사무실에 돌려주게 할 것', TIMESTAMP('2026-11-17 09:30:00'), NULL, 0)
) AS v (id, department_id, named, writer, content, created_at, edited_at, version)
WHERE @ok ORDER BY v.id;

-- 내림 (REQ-601, G-146): 기한(11-06) 지난 300008을 1중대 부서 관리자 bn1-co1-admin이 11-09(월)에 내렸다. 서버처럼 판(version)이 하나 오른다(@Version)
UPDATE directive SET taken_down_at = TIMESTAMP('2026-11-09 09:10:00'), taken_down_by_account_id = @a_bn1_co1_admin, version = version + 1
WHERE id = 300008 AND @ok;

-- 그림 (REQ-601, G-147): 유선반장이 300032에 붙인 PNG 1장. 파일은 업무 첨부와 같은 stored_file·stored_file_body(용도 DIRECTIVE_IMAGE, 묶임)
-- 바이트는 scripts/demo-docbox-data.py의 terminal_label_png()가 만든 것이고, 그 생성기가 돌 때 이 줄과 같은지 확인한다(check_directive_image)
INSERT INTO stored_file (id, purpose, linked, uploaded_by_account_id, original_name, extension, size, sha256, created_at, linked_at)
SELECT * FROM (VALUES
    ROW(300001, 'DIRECTIVE_IMAGE', TRUE, @a_bn1_co1_s1_lead, '단자함_번호표_위치.png', 'png', 1357, 'eff69860f43e776c8574061c5c0f9ab770adb95440e6a20a76bd7c17b08ce97a', TIMESTAMP('2026-11-16 09:55:00'), TIMESTAMP('2026-11-16 10:00:00'))
) AS v WHERE @ok;

INSERT INTO stored_file_body (file_id, data) SELECT 300001, X'89504E470D0A1A0A0000000D4948445200000140000000F00802000000FE4F2A3C000005144944415478DAEDDD315202411005500EB187E04C061601C7343022F23AC64A11A855ABB8E082D3BFDFAF1F1358BE5A74A7A737AF2252361B3F021180450460110158046011015844001611804500161180450460118045046011015844001601584400161180450460118045046011015804601101F8C67978DCABDEA80003AC00030CB0029C0DF8E9F9A0BA4A010658010618600518605580015680010658010618600518605580015680010658010658156080156080015680010658010658156080B5625FB65B8001D6AA7A010678B86EDB67B95E80013EEC76FBA13EF3F81BFCD6384B007FE805B82FE0A3B17947F84C8097EB05B829E06FA5FDD1F05A9F09F072BD0077047C46DAD58657FCCC8B004FA734013CD70B703BC0BF4ABBC2F0BA9F09F072BD00035C15F0F425F1807FD20B30C0008F0EF88CDE2A05F8AE7A2FF2B6FA67FA0A1DA617604FE0A68033F4020CB02730C00003EC6F6080BD071EE43DF0348BFF42FB2FB49358654E62B502EC3D30C0CE423B8905701060D348CE42039C00D83C70AD79608001D684BB38000658CB1B061860752716C000AB5B29010658DD0B0DB02AC0002BC00003AC0003AC0A30C00A30C0002BC00003EC2CF495BB910006D83452D5DD4800036C1E186080CB02B61B096080AB02CEBB130B6080BB00B699016080011EE55AD9BCCB650106186080014E046C3712C0007B02DBCC0030C000AFF14F2CAF91000618608001F61ED86E24800176120B60800176161A6080A3019B460218E004C0E681CD03030CB0BA91036080156080550106580106186005186055800156800106580106186005186055800156800106D8596867A101368D641A096080CD03030C702460BB910006B82A60BB910006B82A60BB9100061860AB5500061860800106D86E245FA101F604B61B09608001F6151A60800106186080BD0706186027B186DF8D0430C0BD003B0B0D30C0B5019B460218E004C0E681CD03030CB0BA91036080156080550106580106186005186055800156800106580106186005186055800156800106D8596867A101368D641A096080CD03030C702460BB910006B82AE0A43BB15C6A07702FC061BB91000618608001061860800106F8A6809376234DB3000CB02770C9FDC000030CB0D52A00030C30C00003EC3D30C00003DCE52416C000F702EC2C34C000D7066C1A09608013009B07360F0C30C0EA460E80015680015605186005186080156080550106580106186005186080156080550106580106186067A19D8506D8349269248001360F0C30C09180ED460218E0AA80937623B95616E05E80DD0B0D30C0008FB55AC55768800106186080C7066C3712C0007B02FB1B1860800176B13BC000030C30C0DE037B0F0C30C04E62DDF12416C000F705EC2C34C000D7066C1A09608013009B07360F0C30C0EA460E80015680015605186005186080156080550106580106186005186080156080550106580106186067A19D8506D8349269248001360F0C30C09180ED460218E0AA80DD890530C05501BB171A608001B65A0560800106186080ED46F2151A604F607F03030C30C0FFF1151A60800106186080BD07F61E1860809DC4021860809D850618E050C0A69100063801B07960F3C00003AC6EE4001860051860558001568001065801065815608015608001568001065801065815608015608001568001FE04ACBA7A01065801061860053812B088002C02B088002C22008B002C22008B08C02202B008C02202B088002C02B01F8108C02202B088002C02B088002C22008B08C022008B08C02202B008C0225230EFCEA8CF79C372D9F10000000049454E44AE426082' FROM DUAL WHERE @ok;

INSERT INTO directive_image (file_id, directive_id, sort_order) SELECT 300001, 300032, 0 FROM DUAL WHERE @ok;

-- 감사 기록: 위에서 넣은 건마다 CREATE(올린 계정), 고친 건에 UPDATE(명의자), 내린 건에 TAKE_DOWN(내린 계정). 번호는 300001부터 기록 시각 순
INSERT INTO directive_audit (id, directive_id, action, account_id, acted_at)
SELECT 300000 + ROW_NUMBER() OVER (ORDER BY e.acted_at, e.directive_id, e.action), e.directive_id, e.action, e.account_id, e.acted_at
FROM (
    SELECT id AS directive_id, 'CREATE' AS action, written_by_account_id AS account_id, created_at AS acted_at FROM directive WHERE id BETWEEN 300001 AND 300099
    UNION ALL
    SELECT id, 'UPDATE', named_account_id, edited_at FROM directive WHERE id BETWEEN 300001 AND 300099 AND edited_at IS NOT NULL
    UNION ALL
    SELECT id, 'TAKE_DOWN', taken_down_by_account_id, taken_down_at FROM directive WHERE id BETWEEN 300001 AND 300099 AND taken_down_at IS NOT NULL
) e
WHERE @ok;

SELECT @ok AS directive_demo_applied, (SELECT COUNT(*) FROM directive WHERE id >= 300001) AS directives,
       (SELECT COUNT(*) FROM directive WHERE id >= 300001 AND taken_down_at IS NOT NULL) AS taken_down,
       (SELECT COUNT(*) FROM directive_image WHERE directive_id >= 300001) AS images,
       (SELECT COUNT(*) FROM directive_audit WHERE id >= 300001) AS directive_audits;
