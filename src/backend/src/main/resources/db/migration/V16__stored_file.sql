-- 첨부파일 (slices/11 2.3.1·3.2.1, 명세 REQ-115·116·210, G-25·G-26·G-54·G-67)
-- 파일은 하나씩 먼저 올려 임시(linked=FALSE)로 두었다가 요청을 보낼 때 묶는다. 남은 임시 파일은 정리한다(G-67)
-- MySQL DDL은 트랜잭션이 아니어서 한 문장에 한 가지 변경만 쓴다(실패한 곳을 알기 쉽게, slices/11 2.1)

-- 파일 정보. 본문은 따로 둬 목록·권한 판정이 본문을 읽지 않게 한다 (V13 diagram_version_scene과 같은 방식)
CREATE TABLE stored_file (
    id                     BIGINT       NOT NULL AUTO_INCREMENT,
    purpose                VARCHAR(20)  NOT NULL COMMENT 'REQUEST, WORK_ITEM, FORM_ANSWER, FORM_IMAGE, ATTRIBUTE',
    linked                 BOOLEAN      NOT NULL DEFAULT FALSE COMMENT '임시(FALSE) → 대상에 묶이면 TRUE',
    uploaded_by_account_id BIGINT       NOT NULL,
    original_name          VARCHAR(255) NOT NULL,
    extension              VARCHAR(10)  NOT NULL,
    size                   INT          NOT NULL,
    sha256                 CHAR(64)     NOT NULL,
    created_at             DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    linked_at              DATETIME(3)  NULL,
    PRIMARY KEY (id),
    KEY ix_stored_file_temp (linked, created_at),
    CONSTRAINT fk_stored_file_uploader FOREIGN KEY (uploaded_by_account_id) REFERENCES account (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

-- 파일 본문: 내려받을 때만 읽는다. MEDIUMBLOB은 16MB까지, 한도는 파일 하나 10MB
CREATE TABLE stored_file_body (
    file_id BIGINT     NOT NULL,
    data    MEDIUMBLOB NOT NULL,
    PRIMARY KEY (file_id),
    CONSTRAINT fk_stored_file_body FOREIGN KEY (file_id) REFERENCES stored_file (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

-- 요청 첨부 (REQ-115): 작성할 때와 발송 반려·회수 뒤 다시 올릴 때만 바뀐다
CREATE TABLE request_attachment (
    request_id BIGINT NOT NULL,
    file_id    BIGINT NOT NULL,
    sort_order INT    NOT NULL,
    PRIMARY KEY (file_id),
    KEY ix_request_attachment (request_id, sort_order),
    CONSTRAINT fk_request_attachment_request FOREIGN KEY (request_id) REFERENCES work_request (id),
    CONSTRAINT fk_request_attachment_file FOREIGN KEY (file_id) REFERENCES stored_file (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

-- 처리 건 첨부 (REQ-116): 처리 건 부서원이 올리는 순간 묶는다(임시 단계 없음)
CREATE TABLE work_item_attachment (
    work_item_id BIGINT NOT NULL,
    file_id      BIGINT NOT NULL,
    PRIMARY KEY (file_id),
    KEY ix_work_item_attachment (work_item_id),
    CONSTRAINT fk_wia_item FOREIGN KEY (work_item_id) REFERENCES work_item (id),
    CONSTRAINT fk_wia_file FOREIGN KEY (file_id) REFERENCES stored_file (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;
