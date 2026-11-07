-- 없을 때만 넣는다: 같은 키가 이미 있으면 그 줄은 건너뛴다(ON DUPLICATE KEY UPDATE로 아무것도 바꾸지 않음). 이미 있는 데이터(바꾼 비밀번호, 사용 중지, 회수한 역할)는 덮어쓰지 않는다 (DEC-048).
-- INSERT IGNORE는 쓰지 않는다: 외래키·NOT NULL 오류까지 경고로 바꿔 잘못된 줄이 조용히 빠진다.
-- 처음 값으로 되돌리려면 scripts/db-recreate.ps1로 DB를 다시 만든다(slices/11 2.2).
-- 초기 데이터 (가상, local·demo 프로필만). 생성 규칙·계정 목록: docs/02_design/초기데이터.md (DEC-044)
-- 조직: 정보통신대대(본부중대 정보통신상황실, 1중대 유선반·무선반, 2중대 전장망PC반·화상회의반, 3중대 네트워크반·서버반)와
--       근무지원대대(본부중대 행정반·인사교육반, 보급수송중대 보급반·수송반, 복지중대 복지반·체육문화반, 의무중대 의무행정반·진료반). 부서 25개 (slices/21 4.1, DEC-144)
-- 정보통신 일은 정보통신대대만 하고 근무지원대대는 요청만 한다(근무지원대대 부서는 업무 유형 "기타"만). 부서 이름에 '·'·'>'는 쓰지 않는다(DepartmentAdminService 검사)
-- 인원·장비 이름, 인원 계급·연락처는 모두 가상(연락처는 실제 번호가 될 수 없는 000- 형식, NFR-002)
-- 임시 계정 비밀번호는 BCrypt 해시만 둔다(공통, 비밀번호 규칙에 맞는 값, 2026-10-10 변경, DEC-048). 평문은 Git 제외 파일 secrets/로컬계정.md

INSERT INTO department (id, name, type, parent_id) VALUES
    (1, '정보통신대대', 'BATTALION', NULL),
    (21, '본부중대', 'COMPANY', 1),
    (22, '정보통신상황실', 'SECTION', 21),
    (2, '1중대', 'COMPANY', 1),
    (5, '유선반', 'SECTION', 2),
    (6, '무선반', 'SECTION', 2),
    (3, '2중대', 'COMPANY', 1),
    (7, '전장망PC반', 'SECTION', 3),
    (8, '화상회의반', 'SECTION', 3),
    (4, '3중대', 'COMPANY', 1),
    (9, '네트워크반', 'SECTION', 4),
    (10, '서버반', 'SECTION', 4),
    (11, '근무지원대대', 'BATTALION', NULL),
    (12, '본부중대', 'COMPANY', 11),
    (15, '행정반', 'SECTION', 12),
    (16, '인사교육반', 'SECTION', 12),
    (13, '보급수송중대', 'COMPANY', 11),
    (17, '보급반', 'SECTION', 13),
    (18, '수송반', 'SECTION', 13),
    (14, '복지중대', 'COMPANY', 11),
    (19, '복지반', 'SECTION', 14),
    (20, '체육문화반', 'SECTION', 14),
    (23, '의무중대', 'COMPANY', 11),
    (24, '의무행정반', 'SECTION', 23),
    (25, '진료반', 'SECTION', 23)
ON DUPLICATE KEY UPDATE id = id;

-- 인원 62명: 정보통신대대 33(대대 직속 2, 본부중대장, 정보통신상황실 4, 중대장 3, 반 6개: 유선반 3·나머지 4), 근무지원대대 29(대대장, 중대장 4, 반 8개 × 3).
-- 직책은 대대장·중대장·반장·구성원 4가지뿐이라 상황실장은 반장(Personnel.Positions). 연락처를 비운 7명은 빈 연락처 시연용 (slices/21 4.2)
INSERT INTO personnel (id, name, department_id, position, rank_code, contact, active) VALUES
    (1, '김민준', 1, 'BN_CMDR', 'LTC', '000-0000-0001', TRUE),
    (6, '황은우', 1, 'MEMBER', 'MIL_CIVILIAN', '000-0000-0006', TRUE),
    (57, '배성민', 21, 'CO_CMDR', 'CPT', '000-0000-0057', TRUE),
    (58, '문지훈', 22, 'SEC_LEAD', 'LT1', '000-0000-0058', TRUE),
    (59, '홍태윤', 22, 'MEMBER', 'SSG', '000-0000-0059', TRUE),
    (60, '노현서', 22, 'MEMBER', 'SGT', '000-0000-0060', TRUE),
    (61, '구민성', 22, 'MEMBER', 'CPL', '000-0000-0061', TRUE),
    (2, '윤도현', 2, 'CO_CMDR', 'CPT', '000-0000-0002', TRUE),
    (3, '권승현', 5, 'SEC_LEAD', 'SFC', '000-0000-0003', TRUE),
    (4, '이예준', 5, 'MEMBER', 'SGT', '000-0000-0004', TRUE),
    (5, '장선우', 5, 'MEMBER', 'CPL', '000-0000-0005', TRUE),
    (7, '박주원', 6, 'SEC_LEAD', 'MSG', '000-0000-0007', TRUE),
    (8, '임현준', 6, 'MEMBER', 'PFC', '000-0000-0008', TRUE),
    (9, '안지우', 6, 'MEMBER', 'PVT', '000-0000-0009', TRUE),
    (10, '최준우', 6, 'MEMBER', 'SSG', NULL, TRUE),
    (11, '한정우', 3, 'CO_CMDR', 'CPT', '000-0000-0011', TRUE),
    (12, '송서준', 7, 'SEC_LEAD', 'SFC', '000-0000-0012', TRUE),
    (13, '정건우', 7, 'MEMBER', 'SGT', '000-0000-0013', TRUE),
    (14, '오시윤', 7, 'MEMBER', 'CPL', '000-0000-0014', TRUE),
    (15, '류시우', 7, 'MEMBER', 'PFC', NULL, TRUE),
    (16, '강서진', 8, 'SEC_LEAD', 'MSG', '000-0000-0016', TRUE),
    (17, '서지환', 8, 'MEMBER', 'PVT', '000-0000-0017', TRUE),
    (18, '전지호', 8, 'MEMBER', 'SSG', '000-0000-0018', TRUE),
    (19, '조연우', 8, 'MEMBER', 'SGT', '000-0000-0019', TRUE),
    (20, '신유찬', 4, 'CO_CMDR', 'CPT', '000-0000-0020', TRUE),
    (21, '김현우', 9, 'SEC_LEAD', 'SFC', '000-0000-0021', TRUE),
    (22, '윤승우', 9, 'MEMBER', 'CPL', '000-0000-0022', TRUE),
    (23, '권도윤', 9, 'MEMBER', 'PFC', '000-0000-0023', TRUE),
    (24, '이우진', 9, 'MEMBER', 'PVT', NULL, TRUE),
    (25, '장준혁', 10, 'SEC_LEAD', 'MSG', '000-0000-0025', TRUE),
    (26, '황하준', 10, 'MEMBER', 'SSG', '000-0000-0026', TRUE),
    (27, '박민재', 10, 'MEMBER', 'SGT', '000-0000-0027', TRUE),
    (28, '임승민', 10, 'MEMBER', 'CPL', '000-0000-0028', TRUE),
    (29, '안지후', 11, 'BN_CMDR', 'LTC', '000-0000-0029', TRUE),
    (30, '최유준', 12, 'CO_CMDR', 'CPT', '000-0000-0030', TRUE),
    (31, '한민준', 15, 'SEC_LEAD', 'SFC', '000-0000-0031', TRUE),
    (32, '송도현', 15, 'MEMBER', 'PFC', '000-0000-0032', TRUE),
    (33, '정승현', 15, 'MEMBER', 'PVT', '000-0000-0033', TRUE),
    (35, '류선우', 16, 'SEC_LEAD', 'MSG', '000-0000-0035', TRUE),
    (36, '강은우', 16, 'MEMBER', 'SGT', '000-0000-0036', TRUE),
    (37, '서주원', 16, 'MEMBER', 'CPL', NULL, TRUE),
    (39, '조지우', 13, 'CO_CMDR', 'CPT', '000-0000-0039', TRUE),
    (40, '신준우', 17, 'SEC_LEAD', 'SFC', '000-0000-0040', TRUE),
    (41, '김정우', 17, 'MEMBER', 'PVT', '000-0000-0041', TRUE),
    (42, '윤서준', 17, 'MEMBER', 'SSG', '000-0000-0042', TRUE),
    (44, '이시윤', 18, 'SEC_LEAD', 'MSG', '000-0000-0044', TRUE),
    (45, '장시우', 18, 'MEMBER', 'CPL', '000-0000-0045', TRUE),
    (46, '황서진', 18, 'MEMBER', 'PFC', '000-0000-0046', TRUE),
    (48, '임지호', 14, 'CO_CMDR', 'CPT', '000-0000-0048', TRUE),
    (49, '안연우', 19, 'SEC_LEAD', 'SFC', '000-0000-0049', TRUE),
    (50, '최유찬', 19, 'MEMBER', 'SSG', '000-0000-0050', TRUE),
    (51, '한현우', 19, 'MEMBER', 'SGT', '000-0000-0051', TRUE),
    (53, '정도윤', 20, 'SEC_LEAD', 'MSG', '000-0000-0053', TRUE),
    (54, '오우진', 20, 'MEMBER', 'PFC', '000-0000-0054', TRUE),
    (55, '류준혁', 20, 'MEMBER', 'PVT', '000-0000-0055', TRUE),
    (62, '서동하', 23, 'CO_CMDR', 'CPT', '000-0000-0062', TRUE),
    (34, '오예준', 24, 'SEC_LEAD', 'SSG', '000-0000-0034', TRUE),
    (38, '전현준', 24, 'MEMBER', 'PFC', NULL, TRUE),
    (43, '권건우', 24, 'MEMBER', 'SGT', '000-0000-0043', TRUE),
    (56, '강하준', 25, 'SEC_LEAD', 'SSG', '000-0000-0056', TRUE),
    (47, '박지환', 25, 'MEMBER', 'PVT', NULL, TRUE),
    (52, '송승우', 25, 'MEMBER', 'CPL', NULL, TRUE)
ON DUPLICATE KEY UPDATE id = id;

-- 장비: 모델명·시리얼넘버는 가상 값 (NFR-002, slices/10 10장). 관리부서는 slices/21 2.2 원칙: 쓰려고 받은 장비(휴대용 무전기·노트북)는 쓰는 부서,
-- 야전 발전기는 무선반(G15)과 보급반(보급창고 보관). 업무용 노트북 1~3호는 생방송 부서 전장망PC반(7)의 장비(slices/21 7장)
INSERT INTO equipment (id, name, department_id, model_name, serial_number, status) VALUES
    (1, '휴대용 무전기 1호', 6, 'RT-100', 'SN-RT-0001', 'UNUSED'),
    (2, '야전 발전기 1호', 6, 'GEN-5K', 'SN-GN-0001', 'UNUSED'),
    (3, '업무용 노트북 1호', 7, 'NB-14', 'SN-NB-0001', 'UNUSED'),
    (4, '휴대용 무전기 2호', 6, 'RT-100', 'SN-RT-0002', 'UNUSED'),
    (5, '야전 발전기 2호', 6, 'GEN-5K', 'SN-GN-0002', 'UNUSED'),
    (6, '업무용 노트북 2호', 7, 'NB-14', 'SN-NB-0002', 'UNUSED'),
    (7, '휴대용 무전기 3호', 6, 'RT-100', 'SN-RT-0003', 'UNUSED'),
    (8, '야전 발전기 3호', 6, 'GEN-5K', 'SN-GN-0003', 'UNUSED'),
    (9, '업무용 노트북 3호', 7, 'NB-14', 'SN-NB-0003', 'UNUSED'),
    (10, '휴대용 무전기 4호', 6, 'RT-100', 'SN-RT-0004', 'UNUSED'),
    (11, '야전 발전기 4호', 6, 'GEN-5K', 'SN-GN-0004', 'UNUSED'),
    (12, '업무용 노트북 4호', 22, 'NB-14', 'SN-NB-0004', 'UNUSED'),
    (13, '휴대용 무전기 5호', 22, 'RT-100', 'SN-RT-0005', 'UNUSED'),
    (14, '야전 발전기 5호', 6, 'GEN-5K', 'SN-GN-0005', 'UNUSED'),
    (15, '업무용 노트북 5호', 5, 'NB-14', 'SN-NB-0005', 'UNUSED'),
    (16, '휴대용 무전기 6호', 22, 'RT-100', 'SN-RT-0006', 'UNUSED'),
    (17, '야전 발전기 6호', 6, 'GEN-5K', 'SN-GN-0006', 'UNUSED'),
    (18, '업무용 노트북 6호', 8, 'NB-14', 'SN-NB-0006', 'UNUSED'),
    (19, '휴대용 무전기 7호', 17, 'RT-100', 'SN-RT-0007', 'UNUSED'),
    (20, '야전 발전기 7호', 17, 'GEN-5K', 'SN-GN-0007', 'UNUSED'),
    (21, '업무용 노트북 7호', 9, 'NB-14', 'SN-NB-0007', 'UNUSED'),
    (22, '휴대용 무전기 8호', 17, 'RT-100', 'SN-RT-0008', 'UNUSED'),
    (23, '야전 발전기 8호', 17, 'GEN-5K', 'SN-GN-0008', 'UNUSED'),
    (24, '업무용 노트북 8호', 10, 'NB-14', 'SN-NB-0008', 'UNUSED'),
    (25, '휴대용 무전기 9호', 18, 'RT-100', 'SN-RT-0009', 'UNUSED'),
    (26, '야전 발전기 9호', 17, 'GEN-5K', 'SN-GN-0009', 'UNUSED'),
    (27, '업무용 노트북 9호', 16, 'NB-14', 'SN-NB-0009', 'UNUSED'),
    (28, '휴대용 무전기 10호', 18, 'RT-100', 'SN-RT-0010', 'UNUSED'),
    (29, '야전 발전기 10호', 17, 'GEN-5K', 'SN-GN-0010', 'UNUSED'),
    (30, '업무용 노트북 10호', 25, 'NB-14', 'SN-NB-0010', 'UNUSED'),
    (31, '휴대용 무전기 11호', 15, 'RT-100', 'SN-RT-0011', 'UNUSED'),
    (32, '야전 발전기 11호', 17, 'GEN-5K', 'SN-GN-0011', 'UNUSED'),
    (33, '업무용 노트북 11호', 19, 'NB-14', 'SN-NB-0011', 'UNUSED'),
    (34, '휴대용 무전기 12호', 24, 'RT-100', 'SN-RT-0012', 'UNUSED'),
    (35, '야전 발전기 12호', 17, 'GEN-5K', 'SN-GN-0012', 'UNUSED'),
    (36, '업무용 노트북 12호', 18, 'NB-14', 'SN-NB-0012', 'UNUSED')
ON DUPLICATE KEY UPDATE id = id;

-- 상황별 임시 계정 (아이디는 명세 7장 시험 계정 아이디, G-29)
INSERT INTO account (id, login_id, password_hash, display_name, personnel_id, active) VALUES
    (101, 'bn1-cmdr', '$2a$10$OZda4fvZaA5H42SMfEp0.enXwl2cqzAflMS4D9PQgDEteVwMA6NQm', '김민준', 1, TRUE),
    (102, 'bn2-cmdr', '$2a$10$OZda4fvZaA5H42SMfEp0.enXwl2cqzAflMS4D9PQgDEteVwMA6NQm', '안지후', 29, TRUE),
    (103, 'bn1-co1-cmdr', '$2a$10$OZda4fvZaA5H42SMfEp0.enXwl2cqzAflMS4D9PQgDEteVwMA6NQm', '윤도현', 2, TRUE),
    (104, 'bn1-co2-cmdr', '$2a$10$OZda4fvZaA5H42SMfEp0.enXwl2cqzAflMS4D9PQgDEteVwMA6NQm', '한정우', 11, TRUE),
    (105, 'bn1-co1-s1-lead', '$2a$10$OZda4fvZaA5H42SMfEp0.enXwl2cqzAflMS4D9PQgDEteVwMA6NQm', '권승현', 3, TRUE),
    (106, 'bn1-co1-s1-m1', '$2a$10$OZda4fvZaA5H42SMfEp0.enXwl2cqzAflMS4D9PQgDEteVwMA6NQm', '이예준', 4, TRUE),
    (107, 'bn1-co1-s2-m1', '$2a$10$OZda4fvZaA5H42SMfEp0.enXwl2cqzAflMS4D9PQgDEteVwMA6NQm', '임현준', 8, TRUE),
    (108, 'bn1-co1-admin', '$2a$10$OZda4fvZaA5H42SMfEp0.enXwl2cqzAflMS4D9PQgDEteVwMA6NQm', '장선우', 5, TRUE),
    (109, 'bn1-co2-s1-m1', '$2a$10$OZda4fvZaA5H42SMfEp0.enXwl2cqzAflMS4D9PQgDEteVwMA6NQm', '정건우', 13, TRUE),
    (110, 'bn1-dual', '$2a$10$OZda4fvZaA5H42SMfEp0.enXwl2cqzAflMS4D9PQgDEteVwMA6NQm', '안지우', 9, TRUE),
    (111, 'sysadmin', '$2a$10$OZda4fvZaA5H42SMfEp0.enXwl2cqzAflMS4D9PQgDEteVwMA6NQm', '황은우', 6, TRUE),
    (113, 'inactive', '$2a$10$OZda4fvZaA5H42SMfEp0.enXwl2cqzAflMS4D9PQgDEteVwMA6NQm', '윤승우', 22, FALSE)
ON DUPLICATE KEY UPDATE id = id;

-- 부서 관리자 배치 (명세 7장, G-40·G-70): 정보통신대대 bn1-cmdr(대대장 겸직), 1중대 bn1-co1-admin(유선반 반원), 유선반 bn1-co1-s1-lead(반장 겸직), 무선반 bn1-dual(반원).
-- 나머지 부서 관리자는 아래 시연용 계정 역할에 둔다. 반원 계정(bn1-co1-s1-m1 등)·inactive는 역할이 없다
INSERT INTO role_assignment (id, account_id, role_code, department_id, active) VALUES
    (1, 101, 'BN_CMDR', 1, TRUE),
    (2, 102, 'BN_CMDR', 11, TRUE),
    (3, 103, 'CO_CMDR', 2, TRUE),
    (4, 104, 'CO_CMDR', 3, TRUE),
    (5, 105, 'SEC_LEAD', 5, TRUE),
    (8, 108, 'DEPT_ADMIN', 2, TRUE),
    (12, 111, 'SYS_ADMIN', NULL, TRUE),
    (15, 101, 'DEPT_ADMIN', 1, TRUE),
    (16, 105, 'DEPT_ADMIN', 5, TRUE),
    (17, 110, 'DEPT_ADMIN', 6, TRUE)
ON DUPLICATE KEY UPDATE id = id;

-- 시연용 계정 (DEC-078, slices/21 4.2): 계정이 없던 인원 모두에게 직책에 맞는 계정과 역할. 비밀번호는 위 임시 계정과 같다
-- 아이디: 대대·중대·반 번호 + 중대장·대대장 cmdr / 반장 lead / 반원 m(반 안 순서). 근무지원대대 중대 번호는 id 순서(co1 본부·co2 보급수송·co3 복지·co4 의무),
-- 정보통신대대 본부중대(21·22)만 bn1-hq-*. id는 정하지 않는다(관리 화면으로 만든 계정과 겹치지 않게)
-- 같은 아이디나 같은 인원의 계정이 이미 있으면 그 줄은 넣지 않는다(역할도 그 계정이 이 인원의 계정일 때만)
INSERT INTO account (login_id, password_hash, display_name, personnel_id, active)
SELECT v.column_0, v.column_1, v.column_2, v.column_3, TRUE
FROM (VALUES
    ROW('bn1-hq-cmdr', '$2a$10$OZda4fvZaA5H42SMfEp0.enXwl2cqzAflMS4D9PQgDEteVwMA6NQm', '배성민', 57),
    ROW('bn1-hq-s1-lead', '$2a$10$OZda4fvZaA5H42SMfEp0.enXwl2cqzAflMS4D9PQgDEteVwMA6NQm', '문지훈', 58),
    ROW('bn1-hq-s1-m1', '$2a$10$OZda4fvZaA5H42SMfEp0.enXwl2cqzAflMS4D9PQgDEteVwMA6NQm', '홍태윤', 59),
    ROW('bn1-hq-s1-m2', '$2a$10$OZda4fvZaA5H42SMfEp0.enXwl2cqzAflMS4D9PQgDEteVwMA6NQm', '노현서', 60),
    ROW('bn1-hq-s1-m3', '$2a$10$OZda4fvZaA5H42SMfEp0.enXwl2cqzAflMS4D9PQgDEteVwMA6NQm', '구민성', 61),
    ROW('bn1-co1-s2-lead', '$2a$10$OZda4fvZaA5H42SMfEp0.enXwl2cqzAflMS4D9PQgDEteVwMA6NQm', '박주원', 7),
    ROW('bn1-co1-s2-m3', '$2a$10$OZda4fvZaA5H42SMfEp0.enXwl2cqzAflMS4D9PQgDEteVwMA6NQm', '최준우', 10),
    ROW('bn1-co2-s1-lead', '$2a$10$OZda4fvZaA5H42SMfEp0.enXwl2cqzAflMS4D9PQgDEteVwMA6NQm', '송서준', 12),
    ROW('bn1-co2-s1-m2', '$2a$10$OZda4fvZaA5H42SMfEp0.enXwl2cqzAflMS4D9PQgDEteVwMA6NQm', '오시윤', 14),
    ROW('bn1-co2-s1-m3', '$2a$10$OZda4fvZaA5H42SMfEp0.enXwl2cqzAflMS4D9PQgDEteVwMA6NQm', '류시우', 15),
    ROW('bn1-co2-s2-lead', '$2a$10$OZda4fvZaA5H42SMfEp0.enXwl2cqzAflMS4D9PQgDEteVwMA6NQm', '강서진', 16),
    ROW('bn1-co2-s2-m1', '$2a$10$OZda4fvZaA5H42SMfEp0.enXwl2cqzAflMS4D9PQgDEteVwMA6NQm', '서지환', 17),
    ROW('bn1-co2-s2-m2', '$2a$10$OZda4fvZaA5H42SMfEp0.enXwl2cqzAflMS4D9PQgDEteVwMA6NQm', '전지호', 18),
    ROW('bn1-co2-s2-m3', '$2a$10$OZda4fvZaA5H42SMfEp0.enXwl2cqzAflMS4D9PQgDEteVwMA6NQm', '조연우', 19),
    ROW('bn1-co3-cmdr', '$2a$10$OZda4fvZaA5H42SMfEp0.enXwl2cqzAflMS4D9PQgDEteVwMA6NQm', '신유찬', 20),
    ROW('bn1-co3-s1-lead', '$2a$10$OZda4fvZaA5H42SMfEp0.enXwl2cqzAflMS4D9PQgDEteVwMA6NQm', '김현우', 21),
    ROW('bn1-co3-s1-m2', '$2a$10$OZda4fvZaA5H42SMfEp0.enXwl2cqzAflMS4D9PQgDEteVwMA6NQm', '권도윤', 23),
    ROW('bn1-co3-s1-m3', '$2a$10$OZda4fvZaA5H42SMfEp0.enXwl2cqzAflMS4D9PQgDEteVwMA6NQm', '이우진', 24),
    ROW('bn1-co3-s2-lead', '$2a$10$OZda4fvZaA5H42SMfEp0.enXwl2cqzAflMS4D9PQgDEteVwMA6NQm', '장준혁', 25),
    ROW('bn1-co3-s2-m1', '$2a$10$OZda4fvZaA5H42SMfEp0.enXwl2cqzAflMS4D9PQgDEteVwMA6NQm', '황하준', 26),
    ROW('bn1-co3-s2-m2', '$2a$10$OZda4fvZaA5H42SMfEp0.enXwl2cqzAflMS4D9PQgDEteVwMA6NQm', '박민재', 27),
    ROW('bn1-co3-s2-m3', '$2a$10$OZda4fvZaA5H42SMfEp0.enXwl2cqzAflMS4D9PQgDEteVwMA6NQm', '임승민', 28),
    ROW('bn2-co1-cmdr', '$2a$10$OZda4fvZaA5H42SMfEp0.enXwl2cqzAflMS4D9PQgDEteVwMA6NQm', '최유준', 30),
    ROW('bn2-co1-s1-lead', '$2a$10$OZda4fvZaA5H42SMfEp0.enXwl2cqzAflMS4D9PQgDEteVwMA6NQm', '한민준', 31),
    ROW('bn2-co1-s1-m1', '$2a$10$OZda4fvZaA5H42SMfEp0.enXwl2cqzAflMS4D9PQgDEteVwMA6NQm', '송도현', 32),
    ROW('bn2-co1-s1-m2', '$2a$10$OZda4fvZaA5H42SMfEp0.enXwl2cqzAflMS4D9PQgDEteVwMA6NQm', '정승현', 33),
    ROW('bn2-co1-s2-lead', '$2a$10$OZda4fvZaA5H42SMfEp0.enXwl2cqzAflMS4D9PQgDEteVwMA6NQm', '류선우', 35),
    ROW('bn2-co1-s2-m1', '$2a$10$OZda4fvZaA5H42SMfEp0.enXwl2cqzAflMS4D9PQgDEteVwMA6NQm', '강은우', 36),
    ROW('bn2-co1-s2-m2', '$2a$10$OZda4fvZaA5H42SMfEp0.enXwl2cqzAflMS4D9PQgDEteVwMA6NQm', '서주원', 37),
    ROW('bn2-co2-cmdr', '$2a$10$OZda4fvZaA5H42SMfEp0.enXwl2cqzAflMS4D9PQgDEteVwMA6NQm', '조지우', 39),
    ROW('bn2-co2-s1-lead', '$2a$10$OZda4fvZaA5H42SMfEp0.enXwl2cqzAflMS4D9PQgDEteVwMA6NQm', '신준우', 40),
    ROW('bn2-co2-s1-m1', '$2a$10$OZda4fvZaA5H42SMfEp0.enXwl2cqzAflMS4D9PQgDEteVwMA6NQm', '김정우', 41),
    ROW('bn2-co2-s1-m2', '$2a$10$OZda4fvZaA5H42SMfEp0.enXwl2cqzAflMS4D9PQgDEteVwMA6NQm', '윤서준', 42),
    ROW('bn2-co2-s2-lead', '$2a$10$OZda4fvZaA5H42SMfEp0.enXwl2cqzAflMS4D9PQgDEteVwMA6NQm', '이시윤', 44),
    ROW('bn2-co2-s2-m1', '$2a$10$OZda4fvZaA5H42SMfEp0.enXwl2cqzAflMS4D9PQgDEteVwMA6NQm', '장시우', 45),
    ROW('bn2-co2-s2-m2', '$2a$10$OZda4fvZaA5H42SMfEp0.enXwl2cqzAflMS4D9PQgDEteVwMA6NQm', '황서진', 46),
    ROW('bn2-co3-cmdr', '$2a$10$OZda4fvZaA5H42SMfEp0.enXwl2cqzAflMS4D9PQgDEteVwMA6NQm', '임지호', 48),
    ROW('bn2-co3-s1-lead', '$2a$10$OZda4fvZaA5H42SMfEp0.enXwl2cqzAflMS4D9PQgDEteVwMA6NQm', '안연우', 49),
    ROW('bn2-co3-s1-m1', '$2a$10$OZda4fvZaA5H42SMfEp0.enXwl2cqzAflMS4D9PQgDEteVwMA6NQm', '최유찬', 50),
    ROW('bn2-co3-s1-m2', '$2a$10$OZda4fvZaA5H42SMfEp0.enXwl2cqzAflMS4D9PQgDEteVwMA6NQm', '한현우', 51),
    ROW('bn2-co3-s2-lead', '$2a$10$OZda4fvZaA5H42SMfEp0.enXwl2cqzAflMS4D9PQgDEteVwMA6NQm', '정도윤', 53),
    ROW('bn2-co3-s2-m1', '$2a$10$OZda4fvZaA5H42SMfEp0.enXwl2cqzAflMS4D9PQgDEteVwMA6NQm', '오우진', 54),
    ROW('bn2-co3-s2-m2', '$2a$10$OZda4fvZaA5H42SMfEp0.enXwl2cqzAflMS4D9PQgDEteVwMA6NQm', '류준혁', 55),
    ROW('bn2-co4-cmdr', '$2a$10$OZda4fvZaA5H42SMfEp0.enXwl2cqzAflMS4D9PQgDEteVwMA6NQm', '서동하', 62),
    ROW('bn2-co4-s1-lead', '$2a$10$OZda4fvZaA5H42SMfEp0.enXwl2cqzAflMS4D9PQgDEteVwMA6NQm', '오예준', 34),
    ROW('bn2-co4-s1-m1', '$2a$10$OZda4fvZaA5H42SMfEp0.enXwl2cqzAflMS4D9PQgDEteVwMA6NQm', '전현준', 38),
    ROW('bn2-co4-s1-m2', '$2a$10$OZda4fvZaA5H42SMfEp0.enXwl2cqzAflMS4D9PQgDEteVwMA6NQm', '권건우', 43),
    ROW('bn2-co4-s2-lead', '$2a$10$OZda4fvZaA5H42SMfEp0.enXwl2cqzAflMS4D9PQgDEteVwMA6NQm', '강하준', 56),
    ROW('bn2-co4-s2-m1', '$2a$10$OZda4fvZaA5H42SMfEp0.enXwl2cqzAflMS4D9PQgDEteVwMA6NQm', '박지환', 47),
    ROW('bn2-co4-s2-m2', '$2a$10$OZda4fvZaA5H42SMfEp0.enXwl2cqzAflMS4D9PQgDEteVwMA6NQm', '송승우', 52)
) AS v
WHERE NOT EXISTS (SELECT 1 FROM account a WHERE a.login_id = v.column_0 OR a.personnel_id = v.column_3);

-- 부서 관리자(slices/21 4.3): 2중대 1반·2반은 반장, 근무지원대대 본부중대는 중대장 겸직(그대로). 업무 유형·근무표·정보통신 현황이 있는 부서에 더한다:
-- 2중대는 반원 bn1-co2-s2-m2(임시 계정 bn1-co2-cmdr의 시험 상황을 바꾸지 않게), 3중대·본부중대·의무중대는 중대장, 네트워크반·서버반·정보통신상황실은 반장 겸직
INSERT INTO role_assignment (account_id, role_code, department_id, active)
SELECT a.id, v.column_2, v.column_3, TRUE
FROM (VALUES
    ROW('bn1-hq-cmdr', 57, 'CO_CMDR', 21),
    ROW('bn1-hq-s1-lead', 58, 'SEC_LEAD', 22),
    ROW('bn1-co1-s2-lead', 7, 'SEC_LEAD', 6),
    ROW('bn1-co2-s1-lead', 12, 'SEC_LEAD', 7),
    ROW('bn1-co2-s2-lead', 16, 'SEC_LEAD', 8),
    ROW('bn1-co3-cmdr', 20, 'CO_CMDR', 4),
    ROW('bn1-co3-s1-lead', 21, 'SEC_LEAD', 9),
    ROW('bn1-co3-s2-lead', 25, 'SEC_LEAD', 10),
    ROW('bn2-co1-cmdr', 30, 'CO_CMDR', 12),
    ROW('bn2-co1-s1-lead', 31, 'SEC_LEAD', 15),
    ROW('bn2-co1-s2-lead', 35, 'SEC_LEAD', 16),
    ROW('bn2-co2-cmdr', 39, 'CO_CMDR', 13),
    ROW('bn2-co2-s1-lead', 40, 'SEC_LEAD', 17),
    ROW('bn2-co2-s2-lead', 44, 'SEC_LEAD', 18),
    ROW('bn2-co3-cmdr', 48, 'CO_CMDR', 14),
    ROW('bn2-co3-s1-lead', 49, 'SEC_LEAD', 19),
    ROW('bn2-co3-s2-lead', 53, 'SEC_LEAD', 20),
    ROW('bn2-co4-cmdr', 62, 'CO_CMDR', 23),
    ROW('bn2-co4-s1-lead', 34, 'SEC_LEAD', 24),
    ROW('bn2-co4-s2-lead', 56, 'SEC_LEAD', 25),
    ROW('bn1-co2-s1-lead', 12, 'DEPT_ADMIN', 7),
    ROW('bn1-co2-s2-lead', 16, 'DEPT_ADMIN', 8),
    ROW('bn2-co1-cmdr', 30, 'DEPT_ADMIN', 12),
    ROW('bn1-co2-s2-m2', 18, 'DEPT_ADMIN', 3),
    ROW('bn1-co3-cmdr', 20, 'DEPT_ADMIN', 4),
    ROW('bn1-co3-s1-lead', 21, 'DEPT_ADMIN', 9),
    ROW('bn1-co3-s2-lead', 25, 'DEPT_ADMIN', 10),
    ROW('bn1-hq-cmdr', 57, 'DEPT_ADMIN', 21),
    ROW('bn1-hq-s1-lead', 58, 'DEPT_ADMIN', 22),
    ROW('bn2-co4-cmdr', 62, 'DEPT_ADMIN', 23)
) AS v
JOIN account a ON a.login_id = v.column_0 AND a.personnel_id = v.column_1
ON DUPLICATE KEY UPDATE account_id = role_assignment.account_id;

-- 업무 유형 (REQ-808, 명세 7장, G-40·G-70). 부서 관리자가 관리 ▸ 업무 유형에서 고친다. 가상 양식은 아래에서 붙인다
-- 모든 부서에 양식 없는 "기타"(맨 끝). 그 부서에 "기타"가 없을 때만 넣는다
INSERT INTO work_type (department_id, name, is_other, sort_order)
SELECT d.id, '기타', TRUE, 1000000
FROM department d
WHERE NOT EXISTS (SELECT 1 FROM work_type t WHERE t.department_id = d.id AND t.is_other);

-- 유형 배치 (slices/21 5장): 정보통신대대의 반 6개에 2~3개씩, 1~3중대에 위아래로 함께 보이는 유형 1개씩(화상회의반만 2개, 모두 20개). 정보통신상황실과 근무지원대대는 "기타"만
-- (정보통신 일은 정보통신대대만, 추천은 늘 정보통신대대 쪽). 위아래 부서 유형은 받는 부서를 고르면 보인다(REQ-118).
-- (부서, 이름)이 없을 때만 넣는다(관리자가 이름을 바꾸거나 사용 중지한 유형은 그대로 둔다)
INSERT INTO work_type (department_id, name, sort_order)
SELECT v.department_id, v.name, v.sort_order
FROM (
    SELECT 2 AS department_id, '훈련·행사 통신' AS name, 1 AS sort_order
    UNION ALL SELECT 5, '전화', 1
    UNION ALL SELECT 5, '전화 회선 신설·이동', 2
    UNION ALL SELECT 5, '케이블 포설', 3
    UNION ALL SELECT 6, '무전기', 1
    UNION ALL SELECT 6, '녹음기', 2
    UNION ALL SELECT 6, '구내방송', 3
    UNION ALL SELECT 3, '회의·행사 정보체계', 1
    UNION ALL SELECT 7, '전장망 단말', 1
    UNION ALL SELECT 7, '컴퓨터·모니터', 2
    UNION ALL SELECT 7, '프린터 연결', 3
    UNION ALL SELECT 8, '화상회의', 1
    UNION ALL SELECT 8, '회의실 음향·영상', 2
    UNION ALL SELECT 4, '망·서버 작업 협조', 1
    UNION ALL SELECT 9, '인터넷·네트워크', 1
    UNION ALL SELECT 9, '네트워크 회선 개통', 2
    UNION ALL SELECT 9, '방화벽 정책', 3
    UNION ALL SELECT 10, '인트라넷 계정', 1
    UNION ALL SELECT 10, '서버·백업', 2
    UNION ALL SELECT 10, '무정전 전원장치', 3
) v
JOIN department d ON d.id = v.department_id
WHERE NOT EXISTS (SELECT 1 FROM work_type t WHERE t.department_id = v.department_id AND t.name = v.name COLLATE utf8mb4_bin);

-- 유형 설명(업무 요청의 유형 추천 근거, V41)과 일정 방식. 설명이 빈 유형에만 채운다(관리자가 고친 설명·일정 방식은 그대로)
-- 설명에는 그 반이 맡는 장비·증상만 적는다(추천 제외어는 적지 않음: 낱말이 입력에 들어 있으면 점수를 주므로). 여러 반에 걸치는 낱말("사무실" 등)은 넣지 않고
-- "전화 회선"·"랜 구간 회선"처럼 구로 적는다. 괄호로 붙인 낱말은 정규화 뒤 한 덩어리가 되므로 '·'로 가른다 (slices/21 2.1·5장, recommendTypes.ts)
UPDATE work_type t
JOIN (
    SELECT 2 AS department_id, '훈련·행사 통신' AS name, '훈련·행사 기간 임시 전화와 무전 통신망 구성, 임시 회선 철수' AS description, 'PREFERRED' AS schedule_mode, 3 AS preferred_count
    UNION ALL SELECT 5, '전화', '전화가 안 걸림·통화 불가·통화 잡음, 일반전화기 교체, 군 전화망 번호 문의', 'PREFERRED', 1
    UNION ALL SELECT 5, '전화 회선 신설·이동', '전화 회선 신설·이동·증설, 구내 교환기 번호 변경', 'PREFERRED', 2
    UNION ALL SELECT 5, '케이블 포설', '랜선·광케이블 새로 깔기, 끊어진 케이블 잇기, 벽면 단자 달기', 'PREFERRED', 2
    UNION ALL SELECT 6, '무전기', '휴대용·차량용 무전기와 기지국 송수신 불량, 무전 잡음, 배터리·충전기', 'PREFERRED', 1
    UNION ALL SELECT 6, '녹음기', '통화 녹음기·무전 녹음기 녹음 안 됨, 녹음 파일 보관', 'NONE', 1
    UNION ALL SELECT 6, '구내방송', '구내방송 앰프·스피커 소리 안 남, 방송 예약', 'PREFERRED', 1
    UNION ALL SELECT 3, '회의·행사 정보체계', '회의·행사용 컴퓨터와 전장망 단말 준비, 화상회의 연결을 한 번에', 'EXACT_REQUIRED', 1
    UNION ALL SELECT 7, '전장망 단말', '전장망 단말기 접속 불가·화면 이상, 단말기 운용 교육', 'NONE', 1
    UNION ALL SELECT 7, '컴퓨터·모니터', '업무용 컴퓨터 부팅 불가·느림, 모니터 화면 안 나옴, 키보드·마우스', 'PREFERRED', 1
    UNION ALL SELECT 7, '프린터 연결', '프린터·복합기 연결, 인쇄 드라이버 설치, 인쇄 안 됨', 'PREFERRED', 1
    UNION ALL SELECT 8, '화상회의', '화상회의 단말 연결, 원격 회의 접속, 회의 준비', 'PREFERRED', 3
    UNION ALL SELECT 8, '회의실 음향·영상', '회의실 스피커·마이크·카메라, 프로젝터·대형 화면 영상 표출', 'EXACT_REQUIRED', 1
    UNION ALL SELECT 4, '망·서버 작업 협조', '정전·공사 전후 네트워크 장비와 서버 전원 내림·다시 켜기 협조', 'EXACT_REQUIRED', 1
    UNION ALL SELECT 9, '인터넷·네트워크', '인터넷 끊김·인터넷 속도 저하, 랜 포트 연결 안 됨, 무선 접속', 'PREFERRED', 2
    UNION ALL SELECT 9, '네트워크 회선 개통', '랜 구간 회선 개통·이전, 스위치 포트·VLAN 설정 변경', 'PREFERRED', 2
    UNION ALL SELECT 9, '방화벽 정책', '방화벽 허용 정책 신청, 접속 차단 풀기', 'NONE', 1
    UNION ALL SELECT 10, '인트라넷 계정', '인트라넷 로그인 안 됨, 계정 잠김·비밀번호 초기화, 사용 권한 신청', 'NONE', 1
    UNION ALL SELECT 10, '서버·백업', '업무 서버·인트라넷 서버 접속 불가, 스토리지 용량, 자료 백업·복구', 'PREFERRED', 1
    UNION ALL SELECT 10, '무정전 전원장치', '무정전 전원장치·UPS 경고음, UPS 배터리 교체, 통신실 전원', 'PREFERRED', 1
) v ON t.department_id = v.department_id AND t.name = v.name COLLATE utf8mb4_bin AND NOT t.is_other
SET t.description = v.description,
    -- 일정 방식은 아직 처음 값(희망 일자 하나)일 때만 예시로 바꾼다. 여러 표 UPDATE는 SET 순서가 정해지지 않으므로
    -- 예시 값은 PREFERRED(개수 1~3)이거나 개수 1(NONE·EXACT_REQUIRED)로만 둔다(어느 순서로 바뀌어도 같은 결과)
    t.schedule_mode = IF(t.schedule_mode = 'PREFERRED' AND t.preferred_count = 1, v.schedule_mode, t.schedule_mode),
    t.preferred_count = IF(t.schedule_mode = 'PREFERRED' AND t.preferred_count = 1, v.preferred_count, t.preferred_count)
WHERE t.description = '';

-- 가상 양식 (REQ-808, slices/11 3.5.4·slices/21 5장): 지금 양식 7개(인트라넷·화상회의 2·인터넷 구역 이동·전화 2·전장망 그리드)를 맞는 유형으로 옮기고 13개를 새로 둔다.
-- 질문 종류를 고루 쓴다(단답·IP·전화번호·이메일 검증, 드롭다운, 객관식·기타, 체크박스, 그리드, 날짜·시각, 숫자, 선형 배율, 안내문, 파일, 구역 이동).
-- 질문 표시(key)는 고정. 그 유형에 판이 하나도 없을 때만 첫 판을 넣고, 지금 판이 비어 있는 유형에만 그 판을 지금 판으로 둔다(관리자가 고친 양식은 그대로)
INSERT INTO work_form_version (work_type_id, version_no, definition, created_by_account_id)
SELECT t.id, 1, v.definition, (SELECT MIN(a.id) FROM account a)
FROM (
    SELECT 2 AS department_id, '훈련·행사 통신' AS name, '{"sections":[{"key":"s1","title":"","description":"","next":"NEXT","items":[{"key":"event","kind":"SHORT_TEXT","title":"훈련·행사 이름","required":true},{"key":"place","kind":"SHORT_TEXT","title":"장소","required":true},{"key":"need","kind":"CHECKBOXES","title":"필요한 통신","required":true,"options":[{"id":"o1","label":"임시 전화"},{"id":"o2","label":"무전"},{"id":"o3","label":"구내방송"}]},{"key":"days","kind":"NUMBER","title":"기간(일)","validation":{"type":"NUMBER_RANGE","min":1,"max":30}}]}]}' AS definition
    UNION ALL SELECT 5, '전화', '{"sections":[{"key":"s1","title":"","description":"","next":"NEXT","items":[{"key":"line","kind":"SHORT_TEXT","title":"회선 번호","required":true,"description":"숫자와 하이픈(-)으로 입력하세요.","validation":{"type":"PHONE"}},{"key":"symptom","kind":"MULTIPLE_CHOICE","title":"증상","required":true,"options":[{"id":"o1","label":"통화가 안 됨"},{"id":"o2","label":"잡음"},{"id":"o3","label":"전화기 고장"}],"allowOther":true}]}]}'
    UNION ALL SELECT 5, '전화 회선 신설·이동', '{"sections":[{"key":"s1","title":"","description":"","next":"NEXT","items":[{"key":"work","kind":"CHECKBOXES","title":"필요한 작업","required":true,"options":[{"id":"o1","label":"회선 신설"},{"id":"o2","label":"회선 이동"},{"id":"o3","label":"번호 변경"}],"shuffle":false},{"key":"line","kind":"SHORT_TEXT","title":"회선 번호","validation":{"type":"PHONE"}},{"key":"photo","kind":"FILE","title":"고장 사진·배치도","description":"있으면 올리세요(문서·그림, 파일 하나 10MB)."}]}]}'
    UNION ALL SELECT 5, '케이블 포설', '{"sections":[{"key":"s1","title":"","description":"","next":"NEXT","items":[{"key":"cable","kind":"MULTIPLE_CHOICE","title":"케이블 종류","required":true,"options":[{"id":"o1","label":"랜선"},{"id":"o2","label":"광케이블"},{"id":"o3","label":"전화선"}]},{"key":"route","kind":"SHORT_TEXT","title":"구간(출발지 → 도착지)","required":true},{"key":"length","kind":"NUMBER","title":"대략 길이(m)","validation":{"type":"NUMBER_RANGE","min":1,"max":500}}]}]}'
    UNION ALL SELECT 6, '무전기', '{"sections":[{"key":"s1","title":"","description":"","next":"NEXT","items":[{"key":"radio","kind":"DROPDOWN","title":"무전기 종류","required":true,"options":[{"id":"o1","label":"휴대용"},{"id":"o2","label":"차량용"},{"id":"o3","label":"기지국"}]},{"key":"symptom","kind":"CHECKBOXES","title":"증상","required":true,"options":[{"id":"o1","label":"송신 불량"},{"id":"o2","label":"수신 불량"},{"id":"o3","label":"잡음"},{"id":"o4","label":"배터리"}],"allowOther":true},{"key":"count","kind":"NUMBER","title":"대수","validation":{"type":"NUMBER_RANGE","min":1,"max":50}}]}]}'
    UNION ALL SELECT 6, '녹음기', '{"sections":[{"key":"s1","title":"","description":"","next":"NEXT","items":[{"key":"recorder","kind":"MULTIPLE_CHOICE","title":"녹음기","required":true,"options":[{"id":"o1","label":"통화 녹음기"},{"id":"o2","label":"무전 녹음기"}]},{"key":"symptom","kind":"SHORT_TEXT","title":"증상","required":true}]}]}'
    UNION ALL SELECT 6, '구내방송', '{"sections":[{"key":"s1","title":"","description":"","next":"NEXT","items":[{"key":"place","kind":"SHORT_TEXT","title":"장소","required":true},{"key":"symptom","kind":"MULTIPLE_CHOICE","title":"요청 내용","required":true,"options":[{"id":"o1","label":"소리 안 남"},{"id":"o2","label":"잡음"},{"id":"o3","label":"방송 예약"}],"allowOther":true}]}]}'
    UNION ALL SELECT 3, '회의·행사 정보체계', '{"sections":[{"key":"s1","title":"","description":"","next":"NEXT","items":[{"key":"meetingDate","kind":"DATE","title":"회의·행사 날짜","required":true},{"key":"startTime","kind":"TIME","title":"시작 시각","required":true},{"key":"pcCount","kind":"NUMBER","title":"필요한 컴퓨터 대수","validation":{"type":"NUMBER_RANGE","min":0,"max":30}},{"key":"vc","kind":"MULTIPLE_CHOICE","title":"화상회의 연결","required":true,"options":[{"id":"o1","label":"필요"},{"id":"o2","label":"필요 없음"}]}]}]}'
    UNION ALL SELECT 7, '전장망 단말', '{"sections":[{"key":"s1","title":"","description":"","next":"NEXT","items":[{"key":"status","kind":"MC_GRID","title":"장비별 상태","required":true,"rows":[{"id":"r1","label":"단말기"},{"id":"r2","label":"중계기"},{"id":"r3","label":"안테나"}],"columns":[{"id":"c1","label":"정상"},{"id":"c2","label":"불량"},{"id":"c3","label":"확인 필요"}]},{"key":"need","kind":"CB_GRID","title":"필요한 지원","rows":[{"id":"r1","label":"1주차"},{"id":"r2","label":"2주차"}],"columns":[{"id":"c1","label":"점검"},{"id":"c2","label":"교체"},{"id":"c3","label":"교육"}]}]}]}'
    UNION ALL SELECT 7, '컴퓨터·모니터', '{"sections":[{"key":"s1","title":"","description":"","next":"NEXT","items":[{"key":"asset","kind":"SHORT_TEXT","title":"장비 이름 또는 자산 번호"},{"key":"symptom","kind":"CHECKBOXES","title":"증상","required":true,"options":[{"id":"o1","label":"부팅 안 됨"},{"id":"o2","label":"느림"},{"id":"o3","label":"화면 안 나옴"},{"id":"o4","label":"키보드·마우스"}],"allowOther":true}]}]}'
    UNION ALL SELECT 7, '프린터 연결', '{"sections":[{"key":"s1","title":"","description":"","next":"NEXT","items":[{"key":"printer","kind":"SHORT_TEXT","title":"프린터 이름","required":true},{"key":"kind","kind":"MULTIPLE_CHOICE","title":"요청 내용","required":true,"options":[{"id":"o1","label":"새로 연결"},{"id":"o2","label":"드라이버 설치"},{"id":"o3","label":"인쇄 안 됨"}]}]}]}'
    UNION ALL SELECT 8, '화상회의', '{"sections":[{"key":"s1","title":"","description":"","next":"NEXT","items":[{"key":"notice","kind":"GUIDE","title":"안내","description":"회의 시작 30분 전까지 요청하세요.\\n장비 점검은 회의 전날 합니다."},{"key":"meetingDate","kind":"DATE","title":"회의 날짜","required":true},{"key":"startTime","kind":"TIME","title":"시작 시각","required":true},{"key":"attendees","kind":"NUMBER","title":"참석 인원","required":true,"validation":{"type":"NUMBER_RANGE","min":1,"max":100}},{"key":"importance","kind":"LINEAR_SCALE","title":"중요도","scaleMin":1,"scaleMax":5,"minLabel":"낮음","maxLabel":"높음"}]}]}'
    UNION ALL SELECT 8, '회의실 음향·영상', '{"sections":[{"key":"s1","title":"","description":"","next":"NEXT","items":[{"key":"agenda","kind":"LONG_TEXT","title":"회의 목적과 참석 부서","required":true,"validation":{"type":"TEXT_LENGTH","min":10}},{"key":"email","kind":"SHORT_TEXT","title":"회의 자료를 받을 이메일","validation":{"type":"EMAIL"}}]}]}'
    UNION ALL SELECT 4, '망·서버 작업 협조', '{"sections":[{"key":"s1","title":"","description":"","next":"NEXT","items":[{"key":"workDate","kind":"DATE","title":"작업 날짜","required":true},{"key":"start","kind":"TIME","title":"시작 시각","required":true},{"key":"end","kind":"TIME","title":"끝 시각","required":true},{"key":"scope","kind":"CHECKBOXES","title":"영향 받는 장비","required":true,"options":[{"id":"o1","label":"네트워크 장비"},{"id":"o2","label":"서버"},{"id":"o3","label":"무정전 전원장치"}]}]}]}'
    UNION ALL SELECT 9, '인터넷·네트워크', '{"sections":[{"key":"s1","title":"요청 종류","description":"","next":"NEXT","items":[{"key":"kind","kind":"MULTIPLE_CHOICE","title":"요청 종류","required":true,"options":[{"id":"o1","label":"새 회선 설치","goTo":"s2"},{"id":"o2","label":"속도 저하·끊김","goTo":"s3"},{"id":"o3","label":"그 밖의 문의","goTo":"END"}]}]},{"key":"s2","title":"설치 정보","description":"","next":"END","items":[{"key":"outlet","kind":"SHORT_TEXT","title":"설치할 곳(벽면 단자 번호)","required":true},{"key":"count","kind":"NUMBER","title":"필요한 단자 수","required":true,"validation":{"type":"NUMBER_RANGE","min":1,"max":20}}]},{"key":"s3","title":"장애 정보","description":"","next":"END","items":[{"key":"symptom","kind":"CHECKBOXES","title":"증상","required":true,"options":[{"id":"o1","label":"느림"},{"id":"o2","label":"자주 끊김"},{"id":"o3","label":"특정 사이트만 안 됨"}],"allowOther":true},{"key":"since","kind":"TIME","title":"증상이 생긴 시각"}]}]}'
    UNION ALL SELECT 9, '네트워크 회선 개통', '{"sections":[{"key":"s1","title":"","description":"","next":"NEXT","items":[{"key":"place","kind":"SHORT_TEXT","title":"개통할 곳","required":true},{"key":"ports","kind":"NUMBER","title":"필요한 포트 수","required":true,"validation":{"type":"NUMBER_RANGE","min":1,"max":48}},{"key":"vlan","kind":"SHORT_TEXT","title":"VLAN 번호","description":"모르면 비워 두세요."}]}]}'
    UNION ALL SELECT 9, '방화벽 정책', '{"sections":[{"key":"s1","title":"","description":"","next":"NEXT","items":[{"key":"dest","kind":"SHORT_TEXT","title":"접속할 곳(주소)","required":true},{"key":"reason","kind":"LONG_TEXT","title":"필요한 까닭","required":true,"validation":{"type":"TEXT_LENGTH","min":10}},{"key":"period","kind":"DROPDOWN","title":"허용 기간","required":true,"options":[{"id":"o1","label":"1개월"},{"id":"o2","label":"3개월"},{"id":"o3","label":"6개월"}]}]}]}'
    UNION ALL SELECT 10, '인트라넷 계정', '{"sections":[{"key":"s1","title":"","description":"","next":"NEXT","items":[{"key":"pcName","kind":"SHORT_TEXT","title":"사용자 PC 이름","required":true,"validation":{"type":"TEXT_LENGTH","max":30}},{"key":"ip","kind":"SHORT_TEXT","title":"IP 주소","description":"모르면 비워 두세요.","validation":{"type":"IP"}},{"key":"issue","kind":"DROPDOWN","title":"요청 내용","required":true,"options":[{"id":"o1","label":"접속 불가"},{"id":"o2","label":"계정 잠김"},{"id":"o3","label":"권한 신청"}]}]}]}'
    UNION ALL SELECT 10, '서버·백업', '{"sections":[{"key":"s1","title":"","description":"","next":"NEXT","items":[{"key":"server","kind":"DROPDOWN","title":"대상","required":true,"options":[{"id":"o1","label":"업무 서버"},{"id":"o2","label":"인트라넷 서버"},{"id":"o3","label":"스토리지"}]},{"key":"kind","kind":"MULTIPLE_CHOICE","title":"요청 내용","required":true,"options":[{"id":"o1","label":"접속 불가"},{"id":"o2","label":"용량 부족"},{"id":"o3","label":"백업·복구"}]},{"key":"detail","kind":"LONG_TEXT","title":"자세한 내용"}]}]}'
    UNION ALL SELECT 10, '무정전 전원장치', '{"sections":[{"key":"s1","title":"","description":"","next":"NEXT","items":[{"key":"place","kind":"SHORT_TEXT","title":"장소","required":true},{"key":"symptom","kind":"MULTIPLE_CHOICE","title":"증상","required":true,"options":[{"id":"o1","label":"경고음"},{"id":"o2","label":"배터리 교체"},{"id":"o3","label":"전원 꺼짐"}],"allowOther":true}]}]}'
) v
JOIN work_type t ON t.department_id = v.department_id AND t.name = v.name COLLATE utf8mb4_bin AND NOT t.is_other
WHERE NOT EXISTS (SELECT 1 FROM work_form_version f WHERE f.work_type_id = t.id);

UPDATE work_type t
JOIN work_form_version f ON f.work_type_id = t.id AND f.version_no = 1
SET t.current_form_version_id = f.id
WHERE t.current_form_version_id IS NULL AND NOT t.is_other;

-- 기본 결재 경로 (REQ-806, DEC-060·061 검토·결재): 종류는 발송·접수·완료(G-149). 두 대대에 둔다(하위 부서는 상위 경로를 따른다). 기준 부서보다 아래 직위 단계는 빠진다.
-- 경로는 (부서, 종류)로 찾는다(관리자가 지웠다 다시 만들면 id가 바뀜). 단계는 단계가 하나도 없는 경로에만 넣는다(관리자가 고친 단계 유지)
INSERT INTO approval_route (department_id, doc_type)
SELECT d.id, t.doc_type
FROM department d
JOIN (SELECT 'SEND' AS doc_type UNION ALL SELECT 'RECEIVE' UNION ALL SELECT 'COMPLETE') t
WHERE d.id IN (1, 11)
ON DUPLICATE KEY UPDATE department_id = approval_route.department_id;

INSERT INTO approval_route_step (route_id, step_order, kind, position)
SELECT r.id, s.step_order, s.kind, s.position
FROM approval_route r
JOIN (
    SELECT 'SEND' AS doc_type, 1 AS step_order, 'REVIEW' AS kind, 'SEC_LEAD' AS position
    UNION ALL SELECT 'SEND', 2, 'APPROVE', 'CO_CMDR'
    UNION ALL SELECT 'RECEIVE', 1, 'REVIEW', 'SEC_LEAD'
    UNION ALL SELECT 'RECEIVE', 2, 'APPROVE', 'CO_CMDR'
    UNION ALL SELECT 'COMPLETE', 1, 'REVIEW', 'SEC_LEAD'
    UNION ALL SELECT 'COMPLETE', 2, 'APPROVE', 'CO_CMDR'
) s ON s.doc_type = r.doc_type
WHERE r.department_id IN (1, 11)
  AND NOT EXISTS (SELECT 1 FROM approval_route_step x WHERE x.route_id = r.id)
ON DUPLICATE KEY UPDATE route_id = route_id;

-- 시연용 추가 특성 (REQ-405·519, slices/11 3.8): 부서 관리자가 있는 부서에 장비·인원 특성 몇 개. 값은 시연 업무 데이터(R__seed_work_demo.sql)가 넣는다.
-- 장비: 정보통신대대 자산 관리번호·점검 주기(드롭다운) / 1중대 최근 점검일·배터리 상태 / 유선반 최근 점검일(1중대와 같은 이름·종류 → 한 열)·부속품·사용 빈도·점검표(그리드)·취급 설명서(파일) /
--       무선반 점검 주기(단답 → 정보통신대대와 종류가 달라 "점검 주기(무선반)" 열)·부속품(유선반과 한 열) / 근무지원대대 본부중대 임대 만료일·보유 수량
-- 인원: 정보통신대대 특기 / 유선반 전입일·자격증 / 무선반 전입일·비상 연락 순번 / 전장망PC반 운전 가능 차량 (부서 id는 그대로, slices/21 9장)
-- config는 서버가 저장하는 질문 하나(FormItem)의 JSON. 그 부서·대상에 특성이 하나도 없었을 때만 넣는다(관리자가 고치거나 지운 특성은 되살리지 않음). 판(revision)은 남기지 않는다

INSERT INTO attribute_set (target, department_id)
SELECT v.target, v.department_id
FROM (
    SELECT 'EQUIPMENT' AS target, 1 AS department_id
    UNION ALL SELECT 'EQUIPMENT', 2
    UNION ALL SELECT 'EQUIPMENT', 5
    UNION ALL SELECT 'EQUIPMENT', 6
    UNION ALL SELECT 'EQUIPMENT', 12
    UNION ALL SELECT 'PERSONNEL', 1
    UNION ALL SELECT 'PERSONNEL', 5
    UNION ALL SELECT 'PERSONNEL', 6
    UNION ALL SELECT 'PERSONNEL', 7
) v
JOIN department d ON d.id = v.department_id
ON DUPLICATE KEY UPDATE version = attribute_set.version;

INSERT INTO attribute_def (target, department_id, name, kind, config, sort_order)
SELECT v.target, v.department_id, v.name, v.kind, v.config, v.sort_order
FROM (
    SELECT 'EQUIPMENT' AS target, 1 AS department_id, '자산 관리번호' AS name, 'SHORT_TEXT' AS kind, '{"key":"","kind":"SHORT_TEXT","title":"","description":"대대 자산 대장의 번호(가상)","required":false,"shuffle":false,"options":[],"allowOther":false,"rows":[],"columns":[],"scaleMin":null,"scaleMax":null,"minLabel":"","maxLabel":"","validation":{"type":"TEXT_LENGTH","min":null,"max":20},"imageFileId":null}' AS config, 1 AS sort_order
    UNION ALL SELECT 'EQUIPMENT', 1, '점검 주기', 'DROPDOWN', '{"key":"","kind":"DROPDOWN","title":"","description":"","required":false,"shuffle":false,"options":[{"id":"o1","label":"매주","goTo":null},{"id":"o2","label":"매월","goTo":null},{"id":"o3","label":"분기","goTo":null},{"id":"o4","label":"반기","goTo":null}],"allowOther":false,"rows":[],"columns":[],"scaleMin":null,"scaleMax":null,"minLabel":"","maxLabel":"","validation":null,"imageFileId":null}', 2
    UNION ALL SELECT 'EQUIPMENT', 2, '최근 점검일', 'DATE', '{"key":"","kind":"DATE","title":"","description":"","required":false,"shuffle":false,"options":[],"allowOther":false,"rows":[],"columns":[],"scaleMin":null,"scaleMax":null,"minLabel":"","maxLabel":"","validation":null,"imageFileId":null}', 1
    UNION ALL SELECT 'EQUIPMENT', 2, '배터리 상태', 'MULTIPLE_CHOICE', '{"key":"","kind":"MULTIPLE_CHOICE","title":"","description":"","required":false,"shuffle":false,"options":[{"id":"o1","label":"양호","goTo":null},{"id":"o2","label":"교체 필요","goTo":null},{"id":"o3","label":"해당 없음","goTo":null}],"allowOther":true,"rows":[],"columns":[],"scaleMin":null,"scaleMax":null,"minLabel":"","maxLabel":"","validation":null,"imageFileId":null}', 2
    UNION ALL SELECT 'EQUIPMENT', 5, '최근 점검일', 'DATE', '{"key":"","kind":"DATE","title":"","description":"","required":false,"shuffle":false,"options":[],"allowOther":false,"rows":[],"columns":[],"scaleMin":null,"scaleMax":null,"minLabel":"","maxLabel":"","validation":null,"imageFileId":null}', 1
    UNION ALL SELECT 'EQUIPMENT', 5, '부속품', 'CHECKBOXES', '{"key":"","kind":"CHECKBOXES","title":"","description":"","required":false,"shuffle":false,"options":[{"id":"o1","label":"충전기","goTo":null},{"id":"o2","label":"수화기 줄","goTo":null},{"id":"o3","label":"휴대 가방","goTo":null}],"allowOther":false,"rows":[],"columns":[],"scaleMin":null,"scaleMax":null,"minLabel":"","maxLabel":"","validation":null,"imageFileId":null}', 2
    UNION ALL SELECT 'EQUIPMENT', 5, '사용 빈도', 'LINEAR_SCALE', '{"key":"","kind":"LINEAR_SCALE","title":"","description":"","required":false,"shuffle":false,"options":[],"allowOther":false,"rows":[],"columns":[],"scaleMin":1,"scaleMax":5,"minLabel":"거의 안 씀","maxLabel":"매일","validation":null,"imageFileId":null}', 3
    UNION ALL SELECT 'EQUIPMENT', 5, '점검표', 'MC_GRID', '{"key":"","kind":"MC_GRID","title":"","description":"","required":false,"shuffle":false,"options":[],"allowOther":false,"rows":[{"id":"r1","label":"외관","goTo":null},{"id":"r2","label":"전원","goTo":null},{"id":"r3","label":"통신","goTo":null}],"columns":[{"id":"c1","label":"정상","goTo":null},{"id":"c2","label":"불량","goTo":null}],"scaleMin":null,"scaleMax":null,"minLabel":"","maxLabel":"","validation":null,"imageFileId":null}', 4
    UNION ALL SELECT 'EQUIPMENT', 5, '취급 설명서', 'FILE', '{"key":"","kind":"FILE","title":"","description":"글·문서 파일","required":false,"shuffle":false,"options":[],"allowOther":false,"rows":[],"columns":[],"scaleMin":null,"scaleMax":null,"minLabel":"","maxLabel":"","validation":null,"imageFileId":null}', 5
    UNION ALL SELECT 'EQUIPMENT', 6, '점검 주기', 'SHORT_TEXT', '{"key":"","kind":"SHORT_TEXT","title":"","description":"","required":false,"shuffle":false,"options":[],"allowOther":false,"rows":[],"columns":[],"scaleMin":null,"scaleMax":null,"minLabel":"","maxLabel":"","validation":null,"imageFileId":null}', 1
    UNION ALL SELECT 'EQUIPMENT', 6, '부속품', 'CHECKBOXES', '{"key":"","kind":"CHECKBOXES","title":"","description":"","required":false,"shuffle":false,"options":[{"id":"o1","label":"충전기","goTo":null},{"id":"o2","label":"예비 배터리","goTo":null}],"allowOther":false,"rows":[],"columns":[],"scaleMin":null,"scaleMax":null,"minLabel":"","maxLabel":"","validation":null,"imageFileId":null}', 2
    UNION ALL SELECT 'EQUIPMENT', 12, '임대 만료일', 'DATE', '{"key":"","kind":"DATE","title":"","description":"","required":false,"shuffle":false,"options":[],"allowOther":false,"rows":[],"columns":[],"scaleMin":null,"scaleMax":null,"minLabel":"","maxLabel":"","validation":null,"imageFileId":null}', 1
    UNION ALL SELECT 'EQUIPMENT', 12, '보유 수량', 'NUMBER', '{"key":"","kind":"NUMBER","title":"","description":"","required":false,"shuffle":false,"options":[],"allowOther":false,"rows":[],"columns":[],"scaleMin":null,"scaleMax":null,"minLabel":"","maxLabel":"","validation":{"type":"NUMBER_RANGE","min":0,"max":99},"imageFileId":null}', 2
    UNION ALL SELECT 'PERSONNEL', 1, '특기', 'SHORT_TEXT', '{"key":"","kind":"SHORT_TEXT","title":"","description":"","required":false,"shuffle":false,"options":[],"allowOther":false,"rows":[],"columns":[],"scaleMin":null,"scaleMax":null,"minLabel":"","maxLabel":"","validation":null,"imageFileId":null}', 1
    UNION ALL SELECT 'PERSONNEL', 5, '전입일', 'DATE', '{"key":"","kind":"DATE","title":"","description":"","required":false,"shuffle":false,"options":[],"allowOther":false,"rows":[],"columns":[],"scaleMin":null,"scaleMax":null,"minLabel":"","maxLabel":"","validation":null,"imageFileId":null}', 1
    UNION ALL SELECT 'PERSONNEL', 5, '자격증', 'CHECKBOXES', '{"key":"","kind":"CHECKBOXES","title":"","description":"","required":false,"shuffle":false,"options":[{"id":"o1","label":"정보처리기능사","goTo":null},{"id":"o2","label":"네트워크관리사","goTo":null},{"id":"o3","label":"무선설비기능사","goTo":null}],"allowOther":true,"rows":[],"columns":[],"scaleMin":null,"scaleMax":null,"minLabel":"","maxLabel":"","validation":null,"imageFileId":null}', 2
    UNION ALL SELECT 'PERSONNEL', 6, '전입일', 'DATE', '{"key":"","kind":"DATE","title":"","description":"","required":false,"shuffle":false,"options":[],"allowOther":false,"rows":[],"columns":[],"scaleMin":null,"scaleMax":null,"minLabel":"","maxLabel":"","validation":null,"imageFileId":null}', 1
    UNION ALL SELECT 'PERSONNEL', 6, '비상 연락 순번', 'NUMBER', '{"key":"","kind":"NUMBER","title":"","description":"","required":false,"shuffle":false,"options":[],"allowOther":false,"rows":[],"columns":[],"scaleMin":null,"scaleMax":null,"minLabel":"","maxLabel":"","validation":{"type":"NUMBER_RANGE","min":1,"max":20},"imageFileId":null}', 2
    UNION ALL SELECT 'PERSONNEL', 7, '운전 가능 차량', 'DROPDOWN', '{"key":"","kind":"DROPDOWN","title":"","description":"","required":false,"shuffle":false,"options":[{"id":"o1","label":"없음","goTo":null},{"id":"o2","label":"소형","goTo":null},{"id":"o3","label":"중형","goTo":null}],"allowOther":false,"rows":[],"columns":[],"scaleMin":null,"scaleMax":null,"minLabel":"","maxLabel":"","validation":null,"imageFileId":null}', 1
) v
JOIN attribute_set s ON s.target = v.target AND s.department_id = v.department_id
WHERE NOT EXISTS (SELECT 1 FROM attribute_def x WHERE x.target = v.target AND x.department_id = v.department_id);
