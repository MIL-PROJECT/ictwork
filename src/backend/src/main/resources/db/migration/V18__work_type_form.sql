-- 업무 유형·양식 (slices/11 3.4.1, 명세 REQ-101·114·118·808·903, REQ-805 삭제, G-32~G-39·G-51·G-52·G-64)
-- ④의 표 변경을 모두 여기에 둔다(양식 판·안내문 그림·양식 파일 답 표도). ④-b는 표를 바꾸지 않는다
-- 업무 데이터가 없는 DB를 전제로 한다(G-55). "기타"는 여기서 넣지 않는다: 시드 부서는 시드가, 새 부서는 부서를 만드는 서비스가 넣는다
-- MySQL DDL은 트랜잭션이 아니어서 한 문장에 한 가지 변경만 쓴다(실패한 곳을 알기 쉽게, slices/11 2.1)

-- 1. 업무 유형 (REQ-808): 부서마다. 이름은 저장할 때 앞뒤 빈칸을 빼고, 비교는 utf8mb4_bin(글자가 완전히 같으면 같음, G-64)
--    "기타"는 부서마다 하나(other_key 유일), 사용 중지·이름 바꾸기·양식 불가(G-57)
CREATE TABLE work_type (
    id                      BIGINT      NOT NULL AUTO_INCREMENT,
    department_id           BIGINT      NOT NULL,
    name                    VARCHAR(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
    is_other                BOOLEAN     NOT NULL DEFAULT FALSE COMMENT '"기타": 부서마다 하나, 사용 중지·이름 바꾸기·양식 불가',
    other_key               BIGINT      GENERATED ALWAYS AS (CASE WHEN is_other THEN department_id END) STORED,
    active                  BOOLEAN     NOT NULL DEFAULT TRUE,
    sort_order              INT         NOT NULL,
    current_form_version_id BIGINT      NULL,
    version                 BIGINT      NOT NULL DEFAULT 0,
    created_at              DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at              DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_work_type_name (department_id, name),
    UNIQUE KEY uk_work_type_other (other_key),
    CONSTRAINT fk_work_type_department FOREIGN KEY (department_id) REFERENCES department (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

-- 2. 양식 판 (REQ-118): 고칠 때마다 새 판, 지우지 않음. 쓰는 것은 ④-b
CREATE TABLE work_form_version (
    id                    BIGINT     NOT NULL AUTO_INCREMENT,
    work_type_id          BIGINT     NOT NULL,
    version_no            INT        NOT NULL,
    definition            MEDIUMTEXT NOT NULL,
    created_by_account_id BIGINT     NOT NULL,
    created_at            DATETIME   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_work_form_version (work_type_id, version_no),
    CONSTRAINT fk_wfv_type FOREIGN KEY (work_type_id) REFERENCES work_type (id),
    CONSTRAINT fk_wfv_creator FOREIGN KEY (created_by_account_id) REFERENCES account (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

ALTER TABLE work_type ADD CONSTRAINT fk_work_type_form FOREIGN KEY (current_form_version_id) REFERENCES work_form_version (id);

-- 3. 안내문 그림 (stored_file purpose FORM_IMAGE, ④-b)
CREATE TABLE work_form_image (
    file_id      BIGINT NOT NULL,
    work_type_id BIGINT NOT NULL,
    PRIMARY KEY (file_id),
    CONSTRAINT fk_wfi_file FOREIGN KEY (file_id) REFERENCES stored_file (id),
    CONSTRAINT fk_wfi_type FOREIGN KEY (work_type_id) REFERENCES work_type (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

-- 4. 받는 부서마다 고른 유형과 그 양식 답 (REQ-118, G-34). 그때 이름을 함께 남긴다(지난 요청·이송된 건은 그때 이름으로 보임).
--    받는 부서 표(request_target)와의 맞물림은 복합 FK로 묶지 않고 서비스가 검사한다 (slices/11 3.4.3, 5.1 ⑲)
CREATE TABLE request_target_type (
    id                   BIGINT       NOT NULL AUTO_INCREMENT,
    request_id           BIGINT       NOT NULL,
    target_department_id BIGINT       NOT NULL,
    work_type_id         BIGINT       NOT NULL,
    sort_order           INT          NOT NULL,
    type_name            VARCHAR(50)  NOT NULL,
    type_department_path VARCHAR(300) NOT NULL,
    form_version_id      BIGINT       NULL COMMENT '"기타"·양식 없는 유형은 NULL',
    answers              MEDIUMTEXT   NULL,
    answer_text          MEDIUMTEXT   NULL COMMENT '단답·장문 답 이어 붙임(REQ-117 글자 검색)',
    has_files            BOOLEAN      NOT NULL DEFAULT FALSE COMMENT '파일 답이 있음(REQ-117 첨부 있는 것만)',
    PRIMARY KEY (id),
    UNIQUE KEY uk_request_target_type (request_id, target_department_id, work_type_id),
    KEY ix_rtt_type (work_type_id),
    CONSTRAINT fk_rtt_request FOREIGN KEY (request_id) REFERENCES work_request (id),
    CONSTRAINT fk_rtt_department FOREIGN KEY (target_department_id) REFERENCES department (id),
    CONSTRAINT fk_rtt_type FOREIGN KEY (work_type_id) REFERENCES work_type (id),
    CONSTRAINT fk_rtt_form FOREIGN KEY (form_version_id) REFERENCES work_form_version (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

-- 5. 양식 파일 답 (stored_file purpose FORM_ANSWER, ④-b)
CREATE TABLE request_answer_file (
    file_id                BIGINT      NOT NULL,
    request_target_type_id BIGINT      NOT NULL,
    question_key           VARCHAR(40) NOT NULL,
    PRIMARY KEY (file_id),
    KEY ix_raf_rtt (request_target_type_id),
    CONSTRAINT fk_raf_file FOREIGN KEY (file_id) REFERENCES stored_file (id),
    CONSTRAINT fk_raf_rtt FOREIGN KEY (request_target_type_id) REFERENCES request_target_type (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

-- 6. 처리 건의 원래 받는 부서: 이송된 건이 원래 유형·답을 가져간다 (REQ-118, G-37)
ALTER TABLE work_item ADD COLUMN origin_department_id BIGINT NULL AFTER department_id;
UPDATE work_item SET origin_department_id = department_id;
ALTER TABLE work_item MODIFY origin_department_id BIGINT NOT NULL;
ALTER TABLE work_item ADD CONSTRAINT fk_work_item_origin FOREIGN KEY (origin_department_id) REFERENCES department (id);

-- 7. 요청 유형(REQ-805) 없앰 (G-32): 업무 유형은 부서마다(REQ-808)
ALTER TABLE work_request DROP FOREIGN KEY fk_work_request_type;
ALTER TABLE work_request DROP COLUMN request_type_id;
DROP TABLE request_type;
