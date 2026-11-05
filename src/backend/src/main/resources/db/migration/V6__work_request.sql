-- 업무 요청·처리 건 (DEC-060, slices/B_업무결재.md)

-- 요청 유형 (REQ-805): 시스템 관리자가 관리
CREATE TABLE request_type (
    id         BIGINT      NOT NULL AUTO_INCREMENT,
    name       VARCHAR(50) NOT NULL,
    active     BOOLEAN     NOT NULL DEFAULT TRUE,
    created_at DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_request_type_name (name)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

-- 요청: 누구나 작성. 단계 SEND_APPROVAL(발송 결재중) / SEND_REJECTED / CANCELLED / SENT.
-- 보낸 뒤의 진행 상태는 처리 건으로 계산한다(저장하지 않음)
CREATE TABLE work_request (
    id                      BIGINT        NOT NULL AUTO_INCREMENT,
    request_type_id         BIGINT        NOT NULL,
    title                   VARCHAR(100)  NOT NULL,
    content                 VARCHAR(2000) NOT NULL,
    requester_account_id    BIGINT        NOT NULL,
    requester_department_id BIGINT        NULL,
    phase                   VARCHAR(20)   NOT NULL,
    sent_at                 DATETIME      NULL,
    version                 BIGINT        NOT NULL DEFAULT 0,
    created_at              DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at              DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    CONSTRAINT fk_work_request_type FOREIGN KEY (request_type_id) REFERENCES request_type (id),
    CONSTRAINT fk_work_request_requester FOREIGN KEY (requester_account_id) REFERENCES account (id),
    CONSTRAINT fk_work_request_department FOREIGN KEY (requester_department_id) REFERENCES department (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

-- 받는 부서 (보내기 전까지 고칠 수 있음. 보내면 부서마다 처리 건이 생김)
CREATE TABLE request_target (
    request_id    BIGINT NOT NULL,
    department_id BIGINT NOT NULL,
    PRIMARY KEY (request_id, department_id),
    CONSTRAINT fk_request_target_request FOREIGN KEY (request_id) REFERENCES work_request (id),
    CONSTRAINT fk_request_target_department FOREIGN KEY (department_id) REFERENCES department (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

-- 열람 범위: 요청자가 정하는 부서(그 부서 소속 인원) 또는 사람
CREATE TABLE request_viewer (
    id            BIGINT NOT NULL AUTO_INCREMENT,
    request_id    BIGINT NOT NULL,
    department_id BIGINT NULL,
    personnel_id  BIGINT NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_request_viewer_request FOREIGN KEY (request_id) REFERENCES work_request (id),
    CONSTRAINT fk_request_viewer_department FOREIGN KEY (department_id) REFERENCES department (id),
    CONSTRAINT fk_request_viewer_personnel FOREIGN KEY (personnel_id) REFERENCES personnel (id),
    CONSTRAINT ck_request_viewer_one CHECK ((department_id IS NULL) <> (personnel_id IS NULL))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

-- 처리 건: 받는 부서마다 하나. 상태 RECEIVED / IN_APPROVAL / APPROVED / PLANNED / DONE / REJECTED / TRANSFERRED
CREATE TABLE work_item (
    id                     BIGINT       NOT NULL AUTO_INCREMENT,
    request_id             BIGINT       NOT NULL,
    department_id          BIGINT       NOT NULL,
    status                 VARCHAR(20)  NOT NULL,
    reject_reason          VARCHAR(500) NULL,
    approval_rejected      BOOLEAN      NOT NULL DEFAULT FALSE,
    transferred_from_id    BIGINT       NULL,
    received_by_account_id BIGINT       NULL,
    received_at            DATETIME     NULL,
    planned_by_account_id  BIGINT       NULL,
    start_date             DATE         NULL,
    end_date               DATE         NULL,
    start_time             TIME         NULL,
    auto_complete          BOOLEAN      NOT NULL DEFAULT FALSE,
    auto_complete_mode     VARCHAR(20)  NULL,
    auto_complete_at       DATETIME     NULL,
    completed_at           DATETIME     NULL,
    version                BIGINT       NOT NULL DEFAULT 0,
    created_at             DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at             DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY ix_work_item_department (department_id, status),
    KEY ix_work_item_auto (status, auto_complete, auto_complete_at),
    CONSTRAINT fk_work_item_request FOREIGN KEY (request_id) REFERENCES work_request (id),
    CONSTRAINT fk_work_item_department FOREIGN KEY (department_id) REFERENCES department (id),
    CONSTRAINT fk_work_item_transferred_from FOREIGN KEY (transferred_from_id) REFERENCES work_item (id),
    CONSTRAINT fk_work_item_received_by FOREIGN KEY (received_by_account_id) REFERENCES account (id),
    CONSTRAINT fk_work_item_planned_by FOREIGN KEY (planned_by_account_id) REFERENCES account (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

-- 확정된 지원 인원·장비 (REQ-108)
CREATE TABLE work_item_personnel (
    work_item_id BIGINT NOT NULL,
    personnel_id BIGINT NOT NULL,
    PRIMARY KEY (work_item_id, personnel_id),
    CONSTRAINT fk_wip_work_item FOREIGN KEY (work_item_id) REFERENCES work_item (id),
    CONSTRAINT fk_wip_personnel FOREIGN KEY (personnel_id) REFERENCES personnel (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE work_item_equipment (
    work_item_id BIGINT NOT NULL,
    equipment_id BIGINT NOT NULL,
    PRIMARY KEY (work_item_id, equipment_id),
    CONSTRAINT fk_wie_work_item FOREIGN KEY (work_item_id) REFERENCES work_item (id),
    CONSTRAINT fk_wie_equipment FOREIGN KEY (equipment_id) REFERENCES equipment (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

-- 업무가 잡은 장비 표시: 업무 계획으로 반출 예정(RESERVED)·반출(OUT)이 된 장비는 그 처리 건을 가리킨다.
-- 사람이 바꾼 반출과 구분해, 업무 연동은 이 표시가 있는 장비만 다시 계산한다 (DEC-060)
ALTER TABLE equipment
    ADD COLUMN work_item_id BIGINT NULL AFTER memo,
    ADD CONSTRAINT fk_equipment_work_item FOREIGN KEY (work_item_id) REFERENCES work_item (id);
