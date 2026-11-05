-- 구성도 편집 (REQ-504~511, DEC-068·069, slices/8a_구성도편집.md 4장)

-- 구성도: 주인 부서(만든 사람의 소속 부서, 바뀌지 않음). 지우기는 숨김(active=false).
-- active_name: 사용 중이면 이름, 지웠으면 NULL. 유일 키로 사용 중인 구성도끼리만 이름이 겹치지 않게 한다
-- 편집 잠금은 이 행에 둔다(서버가 다시 켜져도 유지). lock_expired: 30분 무동작·관리자 해제로 풀렸지만 토큰은 남아 원래 사람이 이어서 편집할 수 있음
CREATE TABLE diagram (
    id                    BIGINT       NOT NULL AUTO_INCREMENT,
    name                  VARCHAR(100) NOT NULL,
    owner_department_id   BIGINT       NOT NULL,
    created_by_account_id BIGINT       NOT NULL,
    active                BOOLEAN      NOT NULL DEFAULT TRUE,
    active_name           VARCHAR(100) GENERATED ALWAYS AS (CASE WHEN active THEN name END) STORED,
    deleted_at            DATETIME     NULL,
    lock_account_id       BIGINT       NULL,
    lock_token            CHAR(36)     NULL,
    lock_started_at       DATETIME     NULL,
    lock_last_edit_at     DATETIME     NULL,
    lock_expired          BOOLEAN      NOT NULL DEFAULT FALSE,
    last_version_no       INT          NOT NULL DEFAULT 0,
    created_at            DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at            DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_diagram_active_name (owner_department_id, active_name),
    CONSTRAINT fk_diagram_owner FOREIGN KEY (owner_department_id) REFERENCES department (id),
    CONSTRAINT fk_diagram_creator FOREIGN KEY (created_by_account_id) REFERENCES account (id),
    CONSTRAINT fk_diagram_lock_account FOREIGN KEY (lock_account_id) REFERENCES account (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

-- 권한: 부서(그 부서 소속만) 또는 사람(인원). VIEW 보기 / EDIT 보기+고치기 (REQ-509)
CREATE TABLE diagram_grant (
    id            BIGINT      NOT NULL AUTO_INCREMENT,
    diagram_id    BIGINT      NOT NULL,
    permission    VARCHAR(10) NOT NULL,
    department_id BIGINT      NULL,
    personnel_id  BIGINT      NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_diagram_grant_department (diagram_id, department_id),
    UNIQUE KEY uk_diagram_grant_personnel (diagram_id, personnel_id),
    CONSTRAINT fk_diagram_grant_diagram FOREIGN KEY (diagram_id) REFERENCES diagram (id),
    CONSTRAINT fk_diagram_grant_department FOREIGN KEY (department_id) REFERENCES department (id),
    CONSTRAINT fk_diagram_grant_personnel FOREIGN KEY (personnel_id) REFERENCES personnel (id),
    CONSTRAINT ck_diagram_grant_one CHECK ((department_id IS NULL) <> (personnel_id IS NULL))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

-- 판: SAVE 저장 / AUTO 강제 해제 때 자동 저장 / REVERT 되돌리기. 최근 20개만 남긴다 (REQ-508)
CREATE TABLE diagram_version (
    id                  BIGINT      NOT NULL AUTO_INCREMENT,
    diagram_id          BIGINT      NOT NULL,
    version_no          INT         NOT NULL,
    kind                VARCHAR(10) NOT NULL,
    saved_by_account_id BIGINT      NOT NULL,
    saved_at            DATETIME    NOT NULL,
    scene_hash          CHAR(64)    NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_diagram_version_no (diagram_id, version_no),
    CONSTRAINT fk_diagram_version_diagram FOREIGN KEY (diagram_id) REFERENCES diagram (id),
    CONSTRAINT fk_diagram_version_account FOREIGN KEY (saved_by_account_id) REFERENCES account (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

-- 판 본문: 목록 조회가 본문을 읽지 않게 따로 둔다
CREATE TABLE diagram_version_scene (
    version_id BIGINT     NOT NULL,
    scene      MEDIUMTEXT NOT NULL,
    PRIMARY KEY (version_id),
    CONSTRAINT fk_diagram_version_scene FOREIGN KEY (version_id) REFERENCES diagram_version (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

-- 임시본: 편집 중 1분마다 덮어쓴다(판 아님). 구성도마다 하나 (REQ-507)
CREATE TABLE diagram_draft (
    diagram_id BIGINT     NOT NULL,
    account_id BIGINT     NOT NULL,
    scene      MEDIUMTEXT NOT NULL,
    scene_hash CHAR(64)   NOT NULL,
    updated_at DATETIME   NOT NULL,
    PRIMARY KEY (diagram_id),
    CONSTRAINT fk_diagram_draft_diagram FOREIGN KEY (diagram_id) REFERENCES diagram (id),
    CONSTRAINT fk_diagram_draft_account FOREIGN KEY (account_id) REFERENCES account (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

-- 그림: 구성도마다 한 번만 저장하고 판·임시본은 file_key로 가리킨다. PNG·JPEG, 1장 5MB, 구성도 합계 20MB (REQ-506)
CREATE TABLE diagram_file (
    id         BIGINT      NOT NULL AUTO_INCREMENT,
    diagram_id BIGINT      NOT NULL,
    file_key   VARCHAR(64) NOT NULL,
    mime       VARCHAR(20) NOT NULL,
    size       INT         NOT NULL,
    data       MEDIUMBLOB  NOT NULL,
    created_at DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_diagram_file_key (diagram_id, file_key),
    CONSTRAINT fk_diagram_file_diagram FOREIGN KEY (diagram_id) REFERENCES diagram (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;
