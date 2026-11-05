-- 장비 상태·위치·비고 (DEC-059)

-- 위치: 부서 관리자가 자기 부서에 등록한다(예: 1중대 창고). 그 부서와 하위 부서 장비의 보관 위치로 고른다
CREATE TABLE location (
    id            BIGINT      NOT NULL AUTO_INCREMENT,
    department_id BIGINT      NOT NULL,
    name          VARCHAR(50) NOT NULL,
    active        BOOLEAN     NOT NULL DEFAULT TRUE,
    created_at    DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at    DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    CONSTRAINT fk_location_department FOREIGN KEY (department_id) REFERENCES department (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

-- 상태: STORED 보관, OUT 반출, MAINTENANCE 정비중, BROKEN 고장, DISPOSAL_PENDING 불용대기.
-- 보관 위치(평소 자리)와 현재 위치(반출 등으로 나가 있을 때: 다른 부서와 장소)를 따로 둔다
ALTER TABLE equipment
    ADD COLUMN status VARCHAR(20) NOT NULL DEFAULT 'STORED' AFTER department_id,
    ADD COLUMN storage_location_id BIGINT NULL AFTER status,
    ADD COLUMN current_department_id BIGINT NULL AFTER storage_location_id,
    ADD COLUMN current_place VARCHAR(100) NULL AFTER current_department_id,
    ADD COLUMN memo VARCHAR(200) NULL AFTER current_place,
    ADD CONSTRAINT fk_equipment_storage_location FOREIGN KEY (storage_location_id) REFERENCES location (id),
    ADD CONSTRAINT fk_equipment_current_department FOREIGN KEY (current_department_id) REFERENCES department (id);
