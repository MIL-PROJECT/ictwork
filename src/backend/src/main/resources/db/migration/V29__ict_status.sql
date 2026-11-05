-- 정보통신 현황 박스·판·개인 배치·공개 범위, 범위 카드, 문서함 기록 파일 (slices/15 3장, 명세 REQ-503·602·004~006·A02·A06·A07, G-141~G-144, DEC-111)
-- 칸 정의는 바뀔 때만 한 줄(ict_box_schema), 표 값은 바뀔 때만 한 줄(ict_box_table), 판에는 값(표는 줄 번호)만 둔다(3.1)
-- MySQL DDL은 트랜잭션이 아니어서 한 문장에 한 가지 변경만 쓴다(slices/11 2.1)

-- 박스: 부서마다 여러 개. 지우면 deleted_at만 채우고, 기록 파일 반영을 끝낸 뒤 기록 일꾼이 줄을 지운다(5.2).
-- live_title_key: 지우지 않은 박스의 제목 키(NFC + 소문자). 같은 부서 안에서 대소문자 무시로 겹치지 않게 한다
CREATE TABLE ict_box (
    id                    BIGINT      NOT NULL AUTO_INCREMENT,
    department_id         BIGINT      NOT NULL,
    title                 VARCHAR(30) NOT NULL,
    title_key             VARCHAR(30) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
    summary_mode          VARCHAR(5)  NOT NULL COMMENT 'TEXT, FIELD',
    summary_text          VARCHAR(30) NULL,
    summary_field         VARCHAR(40) NULL,
    color_mode            VARCHAR(5)  NOT NULL COMMENT 'FIXED, FIELD',
    color                 VARCHAR(6)  NULL,
    schema_id             BIGINT      NOT NULL COMMENT '지금 칸 정의 (ict_box_schema.id)',
    values_json           MEDIUMTEXT  NOT NULL COMMENT '표 밖 칸의 지금 값 {칸 key: 값}',
    tables_json           TEXT        NOT NULL COMMENT '{칸 key: ict_box_table.id}',
    last_revision_no      INT         NOT NULL,
    version               BIGINT      NOT NULL,
    created_by_account_id BIGINT      NOT NULL,
    created_at            DATETIME(3) NOT NULL,
    updated_by_account_id BIGINT      NOT NULL,
    updated_at            DATETIME(3) NOT NULL,
    deleted_at            DATETIME(3) NULL,
    live_title_key        VARCHAR(30) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin GENERATED ALWAYS AS (CASE WHEN deleted_at IS NULL THEN title_key END) STORED,
    PRIMARY KEY (id),
    UNIQUE KEY uk_ict_box_title (department_id, live_title_key),
    CONSTRAINT fk_ict_box_department FOREIGN KEY (department_id) REFERENCES department (id),
    CONSTRAINT fk_ict_box_creator FOREIGN KEY (created_by_account_id) REFERENCES account (id),
    CONSTRAINT fk_ict_box_updater FOREIGN KEY (updated_by_account_id) REFERENCES account (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

-- 칸 정의: 같은 박스 안에서 sha256이 같으면 다시 쓴다
CREATE TABLE ict_box_schema (
    id         BIGINT      NOT NULL AUTO_INCREMENT,
    box_id     BIGINT      NOT NULL,
    sha256     CHAR(64)    NOT NULL,
    definition MEDIUMTEXT  NOT NULL,
    created_at DATETIME(3) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_ict_box_schema (box_id, sha256),
    CONSTRAINT fk_ict_box_schema_box FOREIGN KEY (box_id) REFERENCES ict_box (id) ON DELETE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

-- 표 값: 같은 박스 안에서 sha256이 같으면 다시 쓴다
CREATE TABLE ict_box_table (
    id     BIGINT     NOT NULL AUTO_INCREMENT,
    box_id BIGINT     NOT NULL,
    sha256 CHAR(64)   NOT NULL,
    data   MEDIUMTEXT NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_ict_box_table (box_id, sha256),
    CONSTRAINT fk_ict_box_table_box FOREIGN KEY (box_id) REFERENCES ict_box (id) ON DELETE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

-- 판: 저장마다 한 줄(공개 범위만 바꾸거나 바뀐 것이 없으면 남기지 않음). shown_*: 그때 보인 대표 내용·색
CREATE TABLE ict_box_revision (
    id                    BIGINT      NOT NULL AUTO_INCREMENT,
    box_id                BIGINT      NOT NULL,
    revision_no           INT         NOT NULL,
    changed_by_account_id BIGINT      NOT NULL,
    changed_at            DATETIME(3) NOT NULL,
    title                 VARCHAR(30) NOT NULL,
    summary_mode          VARCHAR(5)  NOT NULL,
    summary_text          VARCHAR(30) NULL,
    summary_field         VARCHAR(40) NULL,
    color_mode            VARCHAR(5)  NOT NULL,
    color                 VARCHAR(6)  NULL,
    shown_summary         VARCHAR(30) NOT NULL,
    shown_color           VARCHAR(6)  NOT NULL,
    schema_id             BIGINT      NOT NULL,
    values_json           MEDIUMTEXT  NOT NULL,
    tables_json           TEXT        NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_ict_box_revision (box_id, revision_no),
    KEY ix_ict_box_revision_changed (box_id, changed_at),
    CONSTRAINT fk_ict_box_revision_box FOREIGN KEY (box_id) REFERENCES ict_box (id) ON DELETE CASCADE,
    CONSTRAINT fk_ict_box_revision_changer FOREIGN KEY (changed_by_account_id) REFERENCES account (id),
    CONSTRAINT fk_ict_box_revision_schema FOREIGN KEY (schema_id) REFERENCES ict_box_schema (id) ON DELETE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

-- 판이 쓰는 파일 칸 파일: 판끼리 함께 쓰고, 그 파일을 쓰는 마지막 판이 지워질 때 파일을 지운다(6장)
CREATE TABLE ict_box_revision_file (
    revision_id BIGINT NOT NULL,
    file_id     BIGINT NOT NULL,
    PRIMARY KEY (revision_id, file_id),
    KEY ix_ict_box_revision_file (file_id),
    CONSTRAINT fk_ict_box_revision_file_revision FOREIGN KEY (revision_id) REFERENCES ict_box_revision (id) ON DELETE CASCADE,
    CONSTRAINT fk_ict_box_revision_file_file FOREIGN KEY (file_id) REFERENCES stored_file (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

-- 박스 담당자: 그 부서와 하위 소속의 사용 중 인원. 풀리면(전출·부서 이동·사용 중지) 줄을 지운다(11장)
CREATE TABLE ict_box_assignee (
    box_id                 BIGINT      NOT NULL,
    personnel_id           BIGINT      NOT NULL,
    assigned_by_account_id BIGINT      NOT NULL,
    assigned_at            DATETIME(3) NOT NULL,
    PRIMARY KEY (box_id, personnel_id),
    KEY ix_ict_box_assignee_personnel (personnel_id),
    CONSTRAINT fk_ict_box_assignee_box FOREIGN KEY (box_id) REFERENCES ict_box (id) ON DELETE CASCADE,
    CONSTRAINT fk_ict_box_assignee_personnel FOREIGN KEY (personnel_id) REFERENCES personnel (id),
    CONSTRAINT fk_ict_box_assignee_assigner FOREIGN KEY (assigned_by_account_id) REFERENCES account (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

-- 공개 범위: 사람(인원) 또는 부서(include_sub = 하위 포함) 하나. 그 부서 소속은 늘 보므로 더하기만 한다(REQ-004·006)
CREATE TABLE ict_box_scope (
    id            BIGINT  NOT NULL AUTO_INCREMENT,
    box_id        BIGINT  NOT NULL,
    personnel_id  BIGINT  NULL,
    department_id BIGINT  NULL,
    include_sub   BOOLEAN NOT NULL DEFAULT FALSE,
    PRIMARY KEY (id),
    UNIQUE KEY uk_ict_box_scope_personnel (box_id, personnel_id),
    UNIQUE KEY uk_ict_box_scope_department (box_id, department_id),
    KEY ix_ict_box_scope_personnel (personnel_id),
    KEY ix_ict_box_scope_department (department_id),
    CONSTRAINT fk_ict_box_scope_box FOREIGN KEY (box_id) REFERENCES ict_box (id) ON DELETE CASCADE,
    CONSTRAINT fk_ict_box_scope_personnel FOREIGN KEY (personnel_id) REFERENCES personnel (id),
    CONSTRAINT fk_ict_box_scope_department FOREIGN KEY (department_id) REFERENCES department (id),
    CONSTRAINT ck_ict_box_scope_one CHECK ((department_id IS NULL) <> (personnel_id IS NULL))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

-- 개인 배치: 계정마다 박스 순서·크기·숨김. 공개 범위에서 빠져도 지우지 않는다(다시 들면 예전 배치, 9장)
CREATE TABLE ict_box_layout (
    account_id BIGINT     NOT NULL,
    box_id     BIGINT     NOT NULL,
    sort_key   INT        NOT NULL,
    size       VARCHAR(5) NOT NULL COMMENT 'SMALL, LARGE',
    hidden     BOOLEAN    NOT NULL DEFAULT FALSE,
    PRIMARY KEY (account_id, box_id),
    CONSTRAINT fk_ict_box_layout_account FOREIGN KEY (account_id) REFERENCES account (id),
    CONSTRAINT fk_ict_box_layout_box FOREIGN KEY (box_id) REFERENCES ict_box (id) ON DELETE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

-- 기록 파일에 반영할 일: (박스, 판 연도)마다 한 줄로 모은다(몇 번 저장해도 한 번 씀, 8.3)
CREATE TABLE ict_record_job (
    box_id       BIGINT      NOT NULL,
    year         SMALLINT    NOT NULL,
    requested_at DATETIME(3) NOT NULL,
    PRIMARY KEY (box_id, year),
    CONSTRAINT fk_ict_record_job_box FOREIGN KEY (box_id) REFERENCES ict_box (id) ON DELETE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

-- 문서함 체계 관리 노드: ICT_FOLDER(정보통신 현황 기록 폴더)·ICT_FILE(기록 파일), NULL = 보통. 용량 합에서 뺀다(REQ-A06)
ALTER TABLE doc_node ADD COLUMN system_kind VARCHAR(12) NULL COMMENT 'ICT_FOLDER, ICT_FILE';

-- 기록 파일 한 개(박스·연도): 박스가 지워져도 남는다(box_id에 FK 없음). columns_json: 판 시트 칸 열 순서, sheets_json: 표 시트(8.2)
CREATE TABLE doc_record_file (
    node_id           BIGINT   NOT NULL,
    box_id            BIGINT   NOT NULL,
    department_id     BIGINT   NOT NULL,
    year              SMALLINT NOT NULL,
    first_revision_no INT      NOT NULL,
    last_revision_no  INT      NOT NULL,
    columns_json      TEXT     NOT NULL,
    sheets_json       TEXT     NOT NULL,
    PRIMARY KEY (node_id),
    KEY ix_doc_record_file_box (box_id, year),
    CONSTRAINT fk_doc_record_file_node FOREIGN KEY (node_id) REFERENCES doc_node (id) ON DELETE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

-- 범위 카드: 사람마다(본인만 보고 고침, REQ-005). 이름은 본인 카드끼리 겹치지 않는다
CREATE TABLE scope_card (
    id         BIGINT      NOT NULL AUTO_INCREMENT,
    account_id BIGINT      NOT NULL,
    name       VARCHAR(30) NOT NULL,
    created_at DATETIME(3) NOT NULL,
    updated_at DATETIME(3) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_scope_card_name (account_id, name),
    CONSTRAINT fk_scope_card_account FOREIGN KEY (account_id) REFERENCES account (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE scope_card_entry (
    id            BIGINT  NOT NULL AUTO_INCREMENT,
    card_id       BIGINT  NOT NULL,
    personnel_id  BIGINT  NULL,
    department_id BIGINT  NULL,
    include_sub   BOOLEAN NOT NULL DEFAULT FALSE,
    PRIMARY KEY (id),
    KEY ix_scope_card_entry_personnel (personnel_id),
    KEY ix_scope_card_entry_department (department_id),
    CONSTRAINT fk_scope_card_entry_card FOREIGN KEY (card_id) REFERENCES scope_card (id) ON DELETE CASCADE,
    CONSTRAINT fk_scope_card_entry_personnel FOREIGN KEY (personnel_id) REFERENCES personnel (id),
    CONSTRAINT fk_scope_card_entry_department FOREIGN KEY (department_id) REFERENCES department (id),
    CONSTRAINT ck_scope_card_entry_one CHECK ((department_id IS NULL) <> (personnel_id IS NULL))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;
