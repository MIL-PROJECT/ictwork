-- 조직·계정 정리 (slices/11 3.1.1, 명세 1.2·1.3·REQ-401·802, G-7·G-8·G-10·G-21)
-- 업무 데이터가 없는 DB를 전제로 한다(G-55, 모든 DB를 다시 만든다). 예전 데이터가 남아 제약이 실패하면 고치지 않고 DB를 다시 만든다(scripts/db-recreate.ps1)
-- MySQL DDL은 트랜잭션이 아니어서 한 문장에 한 가지 변경만 쓴다(실패한 곳을 알기 쉽게, slices/11 2.1)

-- 1. 없앤 역할: 반 담당자·대대 접수 담당자·외부 요청자 (G-10)
DELETE FROM role_assignment WHERE role_code IN ('SEC_STAFF', 'BN_RECEPTION', 'EXT_REQUESTER');

-- 2. 인원 계급(필수, 코드 REQ-401 목록)·연락처(선택 30자) (G-7). RANK는 MySQL 8.0 예약어라 열 이름은 rank_code
ALTER TABLE personnel ADD COLUMN rank_code VARCHAR(20) NOT NULL DEFAULT 'CIVILIAN' COMMENT '계급: PVT…GEN, MIL_CIVILIAN(군무원), CIVILIAN(민간인)' AFTER position;
ALTER TABLE personnel ADD COLUMN contact VARCHAR(30) NULL COMMENT '연락처(가상 값, NFR-002)' AFTER rank_code;
ALTER TABLE personnel ALTER COLUMN rank_code DROP DEFAULT;

-- 3. 인원 없는 계정은 없다: 계정과 인원은 1:1 (G-8, DEC-085)
ALTER TABLE account MODIFY personnel_id BIGINT NOT NULL;

-- 4. 부서 종류는 대대·중대·반만 (G-21), 역할은 5개 (G-10)
ALTER TABLE department ADD CONSTRAINT ck_department_type CHECK (type IN ('BATTALION', 'COMPANY', 'SECTION'));
ALTER TABLE role_assignment ADD CONSTRAINT ck_role_code CHECK (role_code IN ('BN_CMDR', 'CO_CMDR', 'SEC_LEAD', 'DEPT_ADMIN', 'SYS_ADMIN'));
