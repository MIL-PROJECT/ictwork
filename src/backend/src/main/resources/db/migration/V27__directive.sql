-- 부대 지시사항 (REQ-601, slices/13)
CREATE TABLE directive (
    id                    BIGINT        NOT NULL AUTO_INCREMENT,
    department_id         BIGINT        NOT NULL,            -- 명의 부서
    named_account_id      BIGINT        NOT NULL,            -- 명의자
    written_by_account_id BIGINT        NOT NULL,            -- 실제로 올린 사람 (대신 올림 판단만, 응답에 넣지 않음)
    content               VARCHAR(1000) NOT NULL,            -- 1~1,000자 (utf8mb4 VARCHAR는 글자 수, 이모지 1자)
    directive_date        DATE          NOT NULL,            -- 올린 날 (서버 시계, 고치지 않음)
    created_at            DATETIME      NOT NULL,
    edited_at             DATETIME      NULL,                -- 고친 시각 ("고침(시각)")
    version               BIGINT        NOT NULL DEFAULT 0,  -- 나중 저장 거부
    PRIMARY KEY (id),
    KEY ix_directive_date (directive_date, id),
    CONSTRAINT fk_directive_department FOREIGN KEY (department_id) REFERENCES department (id),
    CONSTRAINT fk_directive_named FOREIGN KEY (named_account_id) REFERENCES account (id),
    CONSTRAINT fk_directive_writer FOREIGN KEY (written_by_account_id) REFERENCES account (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

-- 감사 기록: 올림·고침·지움의 계정과 시각. 지운 지시사항의 줄도 남으므로 directive에 외래 키를 걸지 않는다 (G-144)
CREATE TABLE directive_audit (
    id           BIGINT      NOT NULL AUTO_INCREMENT,
    directive_id BIGINT      NOT NULL,
    action       VARCHAR(10) NOT NULL,
    account_id   BIGINT      NOT NULL,
    acted_at     DATETIME    NOT NULL,
    PRIMARY KEY (id),
    KEY ix_directive_audit_directive (directive_id, acted_at),
    CONSTRAINT fk_directive_audit_account FOREIGN KEY (account_id) REFERENCES account (id),
    CONSTRAINT ck_directive_audit_action CHECK (action IN ('CREATE', 'UPDATE', 'DELETE'))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;
