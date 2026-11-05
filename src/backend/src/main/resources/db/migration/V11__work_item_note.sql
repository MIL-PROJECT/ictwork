-- 지시사항 메모 (REQ-113, DEC-063): 접수한 부서의 접수 결재 경로(업무 등록은 등록 결재 경로)에 있던 사람이 처리 건에 남기는 메모.
-- 고치거나 지우지 않는다. 처리 건을 볼 수 있는 사람은 모두 본다
CREATE TABLE work_item_note (
    id                BIGINT       NOT NULL AUTO_INCREMENT,
    work_item_id      BIGINT       NOT NULL,
    author_account_id BIGINT       NOT NULL,
    content           VARCHAR(500) NOT NULL,
    created_at        DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY ix_work_item_note_item (work_item_id, created_at),
    CONSTRAINT fk_work_item_note_item FOREIGN KEY (work_item_id) REFERENCES work_item (id),
    CONSTRAINT fk_work_item_note_author FOREIGN KEY (author_account_id) REFERENCES account (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;
