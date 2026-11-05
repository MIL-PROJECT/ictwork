-- 근무자(야간) 현황 (REQ-404, slices/14 2.1)와 체계 공휴일 (REQ-302·404, 자료는 R__public_holidays.sql)

-- 체계 공휴일(관공서 공휴일). 달력 빨간 날과 근무표 공휴일 복사의 출처
CREATE TABLE public_holiday (
    holiday_date DATE        NOT NULL,
    name         VARCHAR(30) NOT NULL,
    PRIMARY KEY (holiday_date)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

-- 근무표. name_key = 앞뒤 빈칸 지운 이름의 소문자(대소문자 무시 겹침을 DB가 막음). 만든 순서 = id
CREATE TABLE duty_roster (
    id                    BIGINT       NOT NULL AUTO_INCREMENT,
    department_id         BIGINT       NOT NULL,
    name                  VARCHAR(30)  NOT NULL,
    name_key              VARCHAR(30)  COLLATE utf8mb4_bin NOT NULL,
    kind                  VARCHAR(10)  NOT NULL,                -- WEEKDAY(요일 방식)·SHIFT(교대 방식), 바꾸지 않음
    apply_days            SMALLINT     NULL,                    -- 교대 적용 날 (월 1·화 2·수 4·목 8·금 16·토 32·일 64·공휴일 128)
    shift_pattern         VARCHAR(500) NULL,                    -- 교대 패턴 JSON [근무 종류 id 또는 null(비번)]
    pattern_rev           INT          NOT NULL DEFAULT 0,      -- 패턴이 바뀔 때 +1 (기억 이어 가기 판정)
    version               BIGINT       NOT NULL DEFAULT 0,      -- 근무표 단위 나중 저장 거부
    created_at            DATETIME(3)  NOT NULL,
    created_by_account_id BIGINT       NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_duty_roster_name (department_id, name_key),
    CONSTRAINT fk_duty_roster_department FOREIGN KEY (department_id) REFERENCES department (id),
    CONSTRAINT fk_duty_roster_creator FOREIGN KEY (created_by_account_id) REFERENCES account (id),
    CONSTRAINT ck_duty_roster_kind CHECK (kind IN ('WEEKDAY', 'SHIFT'))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

-- 요일 방식 로테이션 줄. 지운 줄은 deleted_at만 채운다(오늘까지 남긴 배치의 줄 이름). 순서 = id
CREATE TABLE duty_line (
    id         BIGINT      NOT NULL AUTO_INCREMENT,
    roster_id  BIGINT      NOT NULL,
    name       VARCHAR(30) NOT NULL,
    apply_days SMALLINT    NOT NULL DEFAULT 255,
    created_at DATETIME(3) NOT NULL,
    deleted_at DATETIME(3) NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_duty_line_roster FOREIGN KEY (roster_id) REFERENCES duty_roster (id) ON DELETE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

-- 줄 인원과 순번. 한 줄에 같은 사람 한 번
CREATE TABLE duty_line_member (
    line_id      BIGINT      NOT NULL,
    personnel_id BIGINT      NOT NULL,
    seq          INT         NOT NULL,
    added_at     DATETIME(3) NOT NULL,
    PRIMARY KEY (line_id, personnel_id),
    CONSTRAINT fk_duty_line_member_line FOREIGN KEY (line_id) REFERENCES duty_line (id) ON DELETE CASCADE,
    CONSTRAINT fk_duty_line_member_personnel FOREIGN KEY (personnel_id) REFERENCES personnel (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

-- 교대 근무 종류. 비번은 행이 없고 패턴에서 null. 순서 = id
CREATE TABLE duty_shift_type (
    id          BIGINT      NOT NULL AUTO_INCREMENT,
    roster_id   BIGINT      NOT NULL,
    name        VARCHAR(30) NOT NULL,
    name_key    VARCHAR(30) COLLATE utf8mb4_bin NOT NULL,
    short_label VARCHAR(8)  NOT NULL,
    short_key   VARCHAR(8)  COLLATE utf8mb4_bin NOT NULL,
    headcount   INT         NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_duty_shift_type_name (roster_id, name_key),
    UNIQUE KEY uk_duty_shift_type_short (roster_id, short_key),
    CONSTRAINT fk_duty_shift_type_roster FOREIGN KEY (roster_id) REFERENCES duty_roster (id) ON DELETE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

-- 교대 인원과 정해 둔 시작 칸
CREATE TABLE duty_shift_member (
    roster_id    BIGINT      NOT NULL,
    personnel_id BIGINT      NOT NULL,
    seq          INT         NOT NULL,
    start_cell   INT         NOT NULL,
    added_at     DATETIME(3) NOT NULL,
    PRIMARY KEY (roster_id, personnel_id),
    CONSTRAINT fk_duty_shift_member_roster FOREIGN KEY (roster_id) REFERENCES duty_roster (id) ON DELETE CASCADE,
    CONSTRAINT fk_duty_shift_member_personnel FOREIGN KEY (personnel_id) REFERENCES personnel (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

-- 자동배치 때 날마다 사람마다 기억한 패턴 칸 (slices/14 5.3). 수동 바꾸기는 손대지 않음
CREATE TABLE duty_shift_memory (
    roster_id    BIGINT  NOT NULL,
    personnel_id BIGINT  NOT NULL,
    duty_date    DATE    NOT NULL,
    cell         TINYINT NOT NULL,
    stopped      BOOLEAN NOT NULL,
    pattern_rev  INT     NOT NULL,
    PRIMARY KEY (roster_id, personnel_id, duty_date),
    KEY ix_duty_shift_memory_date (roster_id, duty_date),
    CONSTRAINT fk_duty_shift_memory_roster FOREIGN KEY (roster_id) REFERENCES duty_roster (id) ON DELETE CASCADE,
    CONSTRAINT fk_duty_shift_memory_personnel FOREIGN KEY (personnel_id) REFERENCES personnel (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

-- 배치. 요일 방식은 줄·날마다 1명, 교대는 근무·날마다 여러 명(같은 사람 두 번 금지). 이름은 저장하지 않는다.
-- line_id·shift_type_id 둘 중 하나만 채운다: MySQL은 ON DELETE CASCADE 외래 키 칸에 CHECK를 걸 수 없어 서비스가 지킨다
CREATE TABLE duty_placement (
    id            BIGINT      NOT NULL AUTO_INCREMENT,
    roster_id     BIGINT      NOT NULL,
    duty_date     DATE        NOT NULL,
    line_id       BIGINT      NULL,
    shift_type_id BIGINT      NULL,
    personnel_id  BIGINT      NOT NULL,
    created_at    DATETIME(3) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_duty_placement_line (line_id, duty_date),
    UNIQUE KEY uk_duty_placement_shift (shift_type_id, duty_date, personnel_id),
    KEY ix_duty_placement_roster (roster_id, duty_date),
    KEY ix_duty_placement_personnel (personnel_id, duty_date),
    CONSTRAINT fk_duty_placement_roster FOREIGN KEY (roster_id) REFERENCES duty_roster (id) ON DELETE CASCADE,
    CONSTRAINT fk_duty_placement_line FOREIGN KEY (line_id) REFERENCES duty_line (id) ON DELETE CASCADE,
    CONSTRAINT fk_duty_placement_shift_type FOREIGN KEY (shift_type_id) REFERENCES duty_shift_type (id) ON DELETE CASCADE,
    CONSTRAINT fk_duty_placement_personnel FOREIGN KEY (personnel_id) REFERENCES personnel (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

-- 근무표 공휴일. 뺀 날은 excluded로 목록에 남는다(다시 넣기·가져오기에서 다시 안 넣음)
CREATE TABLE duty_holiday (
    id           BIGINT      NOT NULL AUTO_INCREMENT,
    roster_id    BIGINT      NOT NULL,
    holiday_date DATE        NOT NULL,
    name         VARCHAR(30) NOT NULL,
    view_as      VARCHAR(10) NOT NULL DEFAULT 'HOLIDAY',
    excluded     BOOLEAN     NOT NULL DEFAULT FALSE,
    from_system  BOOLEAN     NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_duty_holiday_date (roster_id, holiday_date),
    CONSTRAINT fk_duty_holiday_roster FOREIGN KEY (roster_id) REFERENCES duty_roster (id) ON DELETE CASCADE,
    CONSTRAINT ck_duty_holiday_view_as CHECK (view_as IN ('HOLIDAY', 'SAT', 'SUN'))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

-- 공유 부서 (부서마다 하위 포함)
CREATE TABLE duty_share (
    roster_id     BIGINT  NOT NULL,
    department_id BIGINT  NOT NULL,
    include_sub   BOOLEAN NOT NULL DEFAULT FALSE,
    PRIMARY KEY (roster_id, department_id),
    CONSTRAINT fk_duty_share_roster FOREIGN KEY (roster_id) REFERENCES duty_roster (id) ON DELETE CASCADE,
    CONSTRAINT fk_duty_share_department FOREIGN KEY (department_id) REFERENCES department (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

-- 근무표 담당자
CREATE TABLE duty_assignee (
    roster_id    BIGINT      NOT NULL,
    personnel_id BIGINT      NOT NULL,
    created_at   DATETIME(3) NOT NULL,
    PRIMARY KEY (roster_id, personnel_id),
    CONSTRAINT fk_duty_assignee_roster FOREIGN KEY (roster_id) REFERENCES duty_roster (id) ON DELETE CASCADE,
    CONSTRAINT fk_duty_assignee_personnel FOREIGN KEY (personnel_id) REFERENCES personnel (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;
