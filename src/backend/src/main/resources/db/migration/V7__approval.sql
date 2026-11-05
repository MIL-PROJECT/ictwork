-- 결재 (REQ-201~210, 806, DEC-060)

-- 결재 문서: 종류 SEND(요청 발송) / RECEIVE(접수, 계획 포함 가능) / SUPPORT(지원 내용) / REPLAN(계획 재결재).
-- 대상은 요청(SEND) 또는 처리 건(나머지) 중 하나. 상태 IN_PROGRESS / DONE / REJECTED / CANCELLED
CREATE TABLE approval_doc (
    id                 BIGINT      NOT NULL AUTO_INCREMENT,
    doc_type           VARCHAR(20) NOT NULL,
    request_id         BIGINT      NULL,
    work_item_id       BIGINT      NULL,
    drafter_account_id BIGINT      NOT NULL,
    status             VARCHAR(20) NOT NULL,
    round              INT         NOT NULL DEFAULT 1,
    with_plan          BOOLEAN     NOT NULL DEFAULT FALSE,
    completed_at       DATETIME    NULL,
    version            BIGINT      NOT NULL DEFAULT 0,
    created_at         DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at         DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY ix_approval_doc_drafter (drafter_account_id, status),
    CONSTRAINT fk_approval_doc_request FOREIGN KEY (request_id) REFERENCES work_request (id),
    CONSTRAINT fk_approval_doc_work_item FOREIGN KEY (work_item_id) REFERENCES work_item (id),
    CONSTRAINT fk_approval_doc_drafter FOREIGN KEY (drafter_account_id) REFERENCES account (id),
    CONSTRAINT ck_approval_doc_target CHECK ((request_id IS NULL) <> (work_item_id IS NULL))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

-- 결재 단계: 회차마다 새 행을 만들어 이전 회차가 이력으로 남는다(REQ-205·210).
-- 구분 APPROVE / VIEW, 결과 PENDING / APPROVED / REJECTED / SKIPPED / VIEWED
CREATE TABLE approval_step (
    id                  BIGINT       NOT NULL AUTO_INCREMENT,
    doc_id              BIGINT       NOT NULL,
    round               INT          NOT NULL,
    step_order          INT          NOT NULL,
    kind                VARCHAR(10)  NOT NULL,
    assignee_account_id BIGINT       NOT NULL,
    result              VARCHAR(10)  NOT NULL,
    acted_at            DATETIME     NULL,
    reason              VARCHAR(500) NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_approval_step (doc_id, round, step_order),
    KEY ix_approval_step_assignee (assignee_account_id, result),
    CONSTRAINT fk_approval_step_doc FOREIGN KEY (doc_id) REFERENCES approval_doc (id),
    CONSTRAINT fk_approval_step_assignee FOREIGN KEY (assignee_account_id) REFERENCES account (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

-- 결재 중인 계획 제안 값: 결재가 끝나면 처리 건에 복사하고, 반려되면 그대로 둔다(처리 건에는 확정 값만)
CREATE TABLE plan_proposal (
    doc_id             BIGINT      NOT NULL,
    start_date         DATE        NOT NULL,
    end_date           DATE        NOT NULL,
    start_time         TIME        NULL,
    auto_complete      BOOLEAN     NOT NULL DEFAULT FALSE,
    auto_complete_mode VARCHAR(20) NULL,
    auto_complete_at   DATETIME    NULL,
    PRIMARY KEY (doc_id),
    CONSTRAINT fk_plan_proposal_doc FOREIGN KEY (doc_id) REFERENCES approval_doc (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE plan_proposal_personnel (
    doc_id       BIGINT NOT NULL,
    personnel_id BIGINT NOT NULL,
    PRIMARY KEY (doc_id, personnel_id),
    CONSTRAINT fk_ppp_doc FOREIGN KEY (doc_id) REFERENCES plan_proposal (doc_id),
    CONSTRAINT fk_ppp_personnel FOREIGN KEY (personnel_id) REFERENCES personnel (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE plan_proposal_equipment (
    doc_id       BIGINT NOT NULL,
    equipment_id BIGINT NOT NULL,
    PRIMARY KEY (doc_id, equipment_id),
    CONSTRAINT fk_ppe_doc FOREIGN KEY (doc_id) REFERENCES plan_proposal (doc_id),
    CONSTRAINT fk_ppe_equipment FOREIGN KEY (equipment_id) REFERENCES equipment (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

-- 기본 결재 경로 (REQ-806): 부서별·문서 종류별. 그 부서에 없으면 상위 부서 경로를 쓴다
CREATE TABLE approval_route (
    id            BIGINT      NOT NULL AUTO_INCREMENT,
    department_id BIGINT      NOT NULL,
    doc_type      VARCHAR(20) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_approval_route (department_id, doc_type),
    CONSTRAINT fk_approval_route_department FOREIGN KEY (department_id) REFERENCES department (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

-- 기본 경로 단계: 직위(SEC_LEAD 반장 / CO_CMDR 중대장 / BN_CMDR 대대장)와 구분
CREATE TABLE approval_route_step (
    route_id   BIGINT      NOT NULL,
    step_order INT         NOT NULL,
    kind       VARCHAR(10) NOT NULL,
    position   VARCHAR(20) NOT NULL,
    PRIMARY KEY (route_id, step_order),
    CONSTRAINT fk_approval_route_step_route FOREIGN KEY (route_id) REFERENCES approval_route (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;
