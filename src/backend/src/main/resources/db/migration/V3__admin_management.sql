-- 관리 화면 (A1-b, DEC-056): 부서 삭제(사용 중지), 초기 비밀번호 강제 변경, 아이디 글자 그대로 비교

-- 부서 삭제는 기록이 남도록 사용 중지로 한다
ALTER TABLE department
    ADD COLUMN active BOOLEAN NOT NULL DEFAULT TRUE AFTER parent_id;

-- 새로 만든·초기화한 계정은 초기 비밀번호로 로그인한 뒤 비밀번호를 바꿔야 한다
ALTER TABLE account
    ADD COLUMN password_change_required BOOLEAN NOT NULL DEFAULT FALSE AFTER active;

-- 아이디는 대소문자·악센트를 구분해 글자 그대로 비교한다 (DEC-054 2번)
ALTER TABLE account
    MODIFY login_id VARCHAR(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL;

-- 초기 데이터 보정(있을 때만): 시스템 관리자(sysadmin)의 인원을 1중대 1반에서 1대대 직속으로 옮긴다.
-- 1중대 부서 관리자 범위 밖에 두어 비밀번호 초기화로 시스템 관리자를 가져갈 수 없게 한다 (DEC-056)
UPDATE personnel p
    JOIN account a ON a.personnel_id = p.id
SET p.department_id = 1
WHERE a.login_id = 'sysadmin' AND p.id = 6 AND p.department_id = 5;
