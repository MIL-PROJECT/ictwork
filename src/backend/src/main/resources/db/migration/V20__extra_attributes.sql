-- 추가 특성 (slices/11 3.7.1, 명세 REQ-401·402·405·501·505·516~519·801, G-42~G-47·G-50·G-53·G-54·G-58·G-59·G-61·G-62·G-66·G-69~G-71)
-- 부서 관리자가 역할 대상 부서의 장비·인원 표에 더하는 열. 값은 장비·인원 행의 JSON 열 {특성 id: 값}
-- MySQL DDL은 트랜잭션이 아니어서 한 문장에 한 가지 변경만 쓴다(slices/11 2.1)

-- 1. 부서·대상별 특성 묶음: 정의 동시 수정 검사(G-66)와 판 번호. 그 부서·대상의 첫 정의를 쓸 때 만든다
CREATE TABLE attribute_set (
    target           VARCHAR(10) NOT NULL COMMENT 'EQUIPMENT, PERSONNEL',
    department_id    BIGINT      NOT NULL,
    version          BIGINT      NOT NULL DEFAULT 0,
    last_revision_no INT         NOT NULL DEFAULT 0,
    PRIMARY KEY (target, department_id),
    CONSTRAINT fk_attribute_set_department FOREIGN KEY (department_id) REFERENCES department (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

-- 2. 특성 정의. 삭제는 deleted_at(같은 ID로 되살리기, G-61). 이름은 살아 있는 것끼리 겹치지 않음(utf8mb4_bin: 글자가 완전히 같으면 같음)
CREATE TABLE attribute_def (
    id            BIGINT      NOT NULL AUTO_INCREMENT,
    target        VARCHAR(10) NOT NULL,
    department_id BIGINT      NOT NULL,
    name          VARCHAR(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
    kind          VARCHAR(20) NOT NULL,
    config        MEDIUMTEXT  NOT NULL COMMENT '선택지(id)·배율·그리드·검증·설명 (slices/11 2.4.3)',
    sort_order    INT         NOT NULL,
    deleted_at    DATETIME(3) NULL,
    live_name     VARCHAR(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin GENERATED ALWAYS AS (CASE WHEN deleted_at IS NULL THEN name END) STORED,
    PRIMARY KEY (id),
    UNIQUE KEY uk_attribute_def_name (target, department_id, live_name),
    CONSTRAINT fk_attribute_def_set FOREIGN KEY (target, department_id) REFERENCES attribute_set (target, department_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

-- 3. 특성 판: 추가·수정·삭제·되살리기·부서 삭제마다, 부서·대상마다 최근 20개 (G-58·G-61). 순서 바꾸기는 판을 남기지 않음
CREATE TABLE attribute_revision (
    id                    BIGINT      NOT NULL AUTO_INCREMENT,
    target                VARCHAR(10) NOT NULL,
    department_id         BIGINT      NOT NULL,
    revision_no           INT         NOT NULL,
    action                VARCHAR(12) NOT NULL COMMENT 'ADD, UPDATE, DELETE, RESTORE, DEPT_DELETE',
    attribute_id          BIGINT      NULL,
    changed_by_account_id BIGINT      NOT NULL,
    changed_at            DATETIME(3) NOT NULL,
    definitions           MEDIUMTEXT  NOT NULL COMMENT '그때 특성 목록',
    deleted_values        MEDIUMTEXT  NULL COMMENT '삭제 판: {장비·인원 id: {값, 그때 부서가 바뀐 시각}}',
    PRIMARY KEY (id),
    UNIQUE KEY uk_attribute_revision (target, department_id, revision_no),
    CONSTRAINT fk_attribute_revision_set FOREIGN KEY (target, department_id) REFERENCES attribute_set (target, department_id),
    CONSTRAINT fk_attribute_revision_account FOREIGN KEY (changed_by_account_id) REFERENCES account (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

-- 4. 삭제 판이 가진 파일 (판이 빠지면 함께 지움)
CREATE TABLE attribute_revision_file (
    file_id     BIGINT NOT NULL,
    revision_id BIGINT NOT NULL,
    PRIMARY KEY (file_id),
    KEY ix_arf_revision (revision_id),
    CONSTRAINT fk_arf_file FOREIGN KEY (file_id) REFERENCES stored_file (id),
    CONSTRAINT fk_arf_revision FOREIGN KEY (revision_id) REFERENCES attribute_revision (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

-- 5. 값: 장비·인원 행의 JSON 열 {특성 id: 값} (DEC-087 메모). 부서가 바뀐 시각은 되살리기 판정(G-61), 인원 고친 시각은 동시 수정 검사(slices/11 2.5)
ALTER TABLE equipment ADD COLUMN attr_values MEDIUMTEXT NULL;
ALTER TABLE equipment ADD COLUMN department_changed_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3);
ALTER TABLE personnel ADD COLUMN attr_values MEDIUMTEXT NULL;
ALTER TABLE personnel ADD COLUMN attr_edited_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3);
ALTER TABLE personnel ADD COLUMN department_changed_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3);

-- 6. 파일 값 (stored_file purpose ATTRIBUTE): 어느 특성·장비(인원)의 값인가
CREATE TABLE attribute_value_file (
    file_id      BIGINT      NOT NULL,
    attribute_id BIGINT      NOT NULL,
    target       VARCHAR(10) NOT NULL,
    owner_id     BIGINT      NOT NULL COMMENT '장비·인원 id',
    PRIMARY KEY (file_id),
    KEY ix_avf_owner (target, owner_id),
    CONSTRAINT fk_avf_file FOREIGN KEY (file_id) REFERENCES stored_file (id),
    CONSTRAINT fk_avf_attr FOREIGN KEY (attribute_id) REFERENCES attribute_def (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

-- 7. 구성도 공유: 공유하는 특성만 행(처음엔 없음 = 공유 안 함, G-46·G-53)
CREATE TABLE diagram_attribute_share (
    diagram_id   BIGINT NOT NULL,
    attribute_id BIGINT NOT NULL,
    PRIMARY KEY (diagram_id, attribute_id),
    CONSTRAINT fk_das_diagram FOREIGN KEY (diagram_id) REFERENCES diagram (id),
    CONSTRAINT fk_das_attr FOREIGN KEY (attribute_id) REFERENCES attribute_def (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;
