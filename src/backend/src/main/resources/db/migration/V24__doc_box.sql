-- 공유 문서함 (slices/12 2.1, 명세 REQ-A01~A07, H-2~H-7·H-17·H-25·H-26, DEC-098)
-- 파일 정보·본문은 업무 첨부와 같은 stored_file·stored_file_body에 두고(용도 DOC_BOX), 폴더 구조·휴지통·공유만 여기에 둔다
-- MySQL DDL은 트랜잭션이 아니어서 한 문장에 한 가지 변경만 쓴다(slices/11 2.1)

-- 문서함: 부서마다 한 줄, 처음 쓸 때 만든다. 쓰기 때 이 줄을 잠가 같은 문서함의 이름 겹침 검사·옮기기를 차례로 한다
CREATE TABLE doc_box (
    department_id BIGINT      NOT NULL,
    created_at    DATETIME(3) NOT NULL,
    PRIMARY KEY (department_id),
    CONSTRAINT fk_doc_box_department FOREIGN KEY (department_id) REFERENCES department (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

-- 파일·폴더. parent_id NULL = 맨 위. 지우면 trashed_at만 채우고 자리는 그대로 둔다(조상이 지워졌으면 지워진 것으로 봄).
-- 이름 겹침(같은 폴더, 지우지 않은 것, 대소문자 무시)은 서버가 검사한다. size: 파일 바이트, 폴더 0 (용량은 휴지통 포함 합계, REQ-A06)
CREATE TABLE doc_node (
    id                    BIGINT       NOT NULL AUTO_INCREMENT,
    department_id         BIGINT       NOT NULL,
    parent_id             BIGINT       NULL,
    kind                  VARCHAR(10)  NOT NULL COMMENT 'FOLDER, FILE',
    name                  VARCHAR(100) NOT NULL,
    file_id               BIGINT       NULL,
    size                  BIGINT       NOT NULL DEFAULT 0,
    created_by_account_id BIGINT       NOT NULL,
    created_at            DATETIME(3)  NOT NULL,
    updated_by_account_id BIGINT       NOT NULL,
    updated_at            DATETIME(3)  NOT NULL,
    trashed_at            DATETIME(3)  NULL,
    trashed_by_account_id BIGINT       NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_doc_node_file (file_id),
    KEY ix_doc_node_parent (department_id, parent_id),
    KEY ix_doc_node_trashed (trashed_at),
    CONSTRAINT fk_doc_node_department FOREIGN KEY (department_id) REFERENCES department (id),
    CONSTRAINT fk_doc_node_parent FOREIGN KEY (parent_id) REFERENCES doc_node (id),
    CONSTRAINT fk_doc_node_file FOREIGN KEY (file_id) REFERENCES stored_file (id),
    CONSTRAINT fk_doc_node_creator FOREIGN KEY (created_by_account_id) REFERENCES account (id),
    CONSTRAINT fk_doc_node_updater FOREIGN KEY (updated_by_account_id) REFERENCES account (id),
    CONSTRAINT fk_doc_node_trasher FOREIGN KEY (trashed_by_account_id) REFERENCES account (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

-- 공유(보기): 사람(인원) 또는 부서(그 부서 소속만, 하위 제외) 하나 (REQ-A04). 노드를 영구 삭제하면 함께 지운다
CREATE TABLE doc_share (
    id                    BIGINT      NOT NULL AUTO_INCREMENT,
    node_id               BIGINT      NOT NULL,
    personnel_id          BIGINT      NULL,
    department_id         BIGINT      NULL,
    created_by_account_id BIGINT      NOT NULL,
    created_at            DATETIME(3) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_doc_share_personnel (node_id, personnel_id),
    UNIQUE KEY uk_doc_share_department (node_id, department_id),
    CONSTRAINT fk_doc_share_node FOREIGN KEY (node_id) REFERENCES doc_node (id) ON DELETE CASCADE,
    CONSTRAINT fk_doc_share_personnel FOREIGN KEY (personnel_id) REFERENCES personnel (id),
    CONSTRAINT fk_doc_share_department FOREIGN KEY (department_id) REFERENCES department (id),
    CONSTRAINT fk_doc_share_creator FOREIGN KEY (created_by_account_id) REFERENCES account (id),
    CONSTRAINT ck_doc_share_one CHECK ((department_id IS NULL) <> (personnel_id IS NULL))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;
