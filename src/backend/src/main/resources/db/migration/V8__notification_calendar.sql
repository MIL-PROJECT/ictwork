-- 알림 (REQ-701~704) · 개인 일정 (DEC-060)

-- 알림: 종류 WORK_ARRIVED(요청 들어옴) / WORK_STATUS(처리 상태 바뀜) / APPROVAL_TURN(내 결재 차례) / APPROVAL_RESULT(결재 완료·반려)
CREATE TABLE notification (
    id         BIGINT       NOT NULL AUTO_INCREMENT,
    account_id BIGINT       NOT NULL,
    type       VARCHAR(30)  NOT NULL,
    message    VARCHAR(300) NOT NULL,
    link       VARCHAR(200) NULL,
    read_at    DATETIME     NULL,
    created_at DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY ix_notification_account (account_id, created_at),
    CONSTRAINT fk_notification_account FOREIGN KEY (account_id) REFERENCES account (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

-- 알림 설정: 끈 종류만 행으로 둔다(행이 없으면 받음)
CREATE TABLE notification_setting (
    account_id BIGINT      NOT NULL,
    type       VARCHAR(30) NOT NULL,
    enabled    BOOLEAN     NOT NULL,
    PRIMARY KEY (account_id, type),
    CONSTRAINT fk_notification_setting_account FOREIGN KEY (account_id) REFERENCES account (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

-- 개인 일정: 달력의 일정 추가를 저장한다. 본인만 본다 (DEC-045·046·060)
CREATE TABLE calendar_event (
    id            BIGINT       NOT NULL AUTO_INCREMENT,
    account_id    BIGINT       NOT NULL,
    title         VARCHAR(100) NOT NULL,
    start_date    DATE         NOT NULL,
    end_date      DATE         NOT NULL,
    color         VARCHAR(20)  NOT NULL,
    important     BOOLEAN      NOT NULL DEFAULT FALSE,
    weekdays_only BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY ix_calendar_event_account (account_id, start_date),
    CONSTRAINT fk_calendar_event_account FOREIGN KEY (account_id) REFERENCES account (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;
