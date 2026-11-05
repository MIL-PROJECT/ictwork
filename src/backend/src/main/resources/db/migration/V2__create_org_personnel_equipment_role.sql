-- 조직·인원·장비·역할 부여 (03 ERD 2.1, 명세 1.2·1.3)

-- 부서: 대대 / 중대 / 반 / 외부. 상위 부서로 트리를 만든다
CREATE TABLE department (
    id         BIGINT      NOT NULL AUTO_INCREMENT,
    name       VARCHAR(50) NOT NULL,
    type       VARCHAR(20) NOT NULL COMMENT 'BATTALION, COMPANY, SECTION, EXTERNAL',
    parent_id  BIGINT      NULL,
    created_at DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    CONSTRAINT fk_department_parent FOREIGN KEY (parent_id) REFERENCES department (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

-- 인원: 조직 구성원 명단. 계정이 없는 인원도 있다 (REQ-401)
CREATE TABLE personnel (
    id            BIGINT      NOT NULL AUTO_INCREMENT,
    name          VARCHAR(50) NOT NULL,
    department_id BIGINT      NOT NULL,
    position      VARCHAR(20) NOT NULL COMMENT '직책: BN_CMDR(대대장), CO_CMDR(중대장), SEC_LEAD(반장), MEMBER(반원·구성원)',
    active        BOOLEAN     NOT NULL DEFAULT TRUE,
    created_at    DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at    DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    CONSTRAINT fk_personnel_department FOREIGN KEY (department_id) REFERENCES department (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

-- 장비: 부서 보유 장비 (REQ-501)
CREATE TABLE equipment (
    id            BIGINT       NOT NULL AUTO_INCREMENT,
    name          VARCHAR(100) NOT NULL,
    department_id BIGINT       NOT NULL,
    active        BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    CONSTRAINT fk_equipment_department FOREIGN KEY (department_id) REFERENCES department (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

-- 계정과 인원 연결: 인원 1명에 계정 0~1개 (03 ERD 2.1)
ALTER TABLE account
    ADD COLUMN personnel_id BIGINT NULL AFTER display_name,
    ADD UNIQUE KEY uk_account_personnel (personnel_id),
    ADD CONSTRAINT fk_account_personnel FOREIGN KEY (personnel_id) REFERENCES personnel (id);

-- 역할 부여: (역할, 대상 부서) 한 쌍 (REQ-803, R-6). 시스템 관리자는 대상 부서 없음(체계 전체)
CREATE TABLE role_assignment (
    id            BIGINT      NOT NULL AUTO_INCREMENT,
    account_id    BIGINT      NOT NULL,
    role_code     VARCHAR(20) NOT NULL COMMENT 'BN_CMDR, CO_CMDR, SEC_LEAD, SEC_STAFF, DEPT_ADMIN, BN_RECEPTION, SYS_ADMIN, EXT_REQUESTER',
    department_id BIGINT      NULL,
    active        BOOLEAN     NOT NULL DEFAULT TRUE,
    created_at    DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at    DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_role_assignment (account_id, role_code, department_id),
    CONSTRAINT fk_role_assignment_account FOREIGN KEY (account_id) REFERENCES account (id),
    CONSTRAINT fk_role_assignment_department FOREIGN KEY (department_id) REFERENCES department (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;
