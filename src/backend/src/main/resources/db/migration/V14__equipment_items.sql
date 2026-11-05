-- 장비 항목 개편 (REQ-501~517, DEC-079, slices/10_장비항목.md 2장)
-- 순서: 새 열(NULL 허용) → 값 채우기·옮기기 → NOT NULL → active 지우기 → 생성 열·유일 키 → 새 표 → 구성도·위치 열
-- MySQL DDL은 트랜잭션이 아니다. 중간에 실패하면 flyway repair가 필요하므로 개발 DB 백업본에서 먼저 돌린다

-- 1. 새 열. 모델명·시리얼넘버는 대소문자만 무시하는 정렬 규칙(악센트는 구분, 명세 "대소문자를 무시해 비교")
ALTER TABLE equipment
    ADD COLUMN system_name             VARCHAR(100) NULL AFTER name,
    ADD COLUMN model_name              VARCHAR(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_as_ci NULL AFTER system_name,
    ADD COLUMN serial_number           VARCHAR(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_as_ci NULL AFTER model_name,
    ADD COLUMN common_name             VARCHAR(100) NULL AFTER serial_number,
    ADD COLUMN hw_version              VARCHAR(100) NULL,
    ADD COLUMN hw_installed_on         DATE         NULL,
    ADD COLUMN os_type                 VARCHAR(100) NULL,
    ADD COLUMN os_version              VARCHAR(100) NULL,
    ADD COLUMN os_installed_on         DATE         NULL,
    ADD COLUMN network_kind            VARCHAR(20)  NULL,
    ADD COLUMN introduced_on           DATE         NULL,
    ADD COLUMN intro_project           VARCHAR(100) NULL,
    ADD COLUMN maintenance_project     VARCHAR(100) NULL,
    ADD COLUMN carry_out               VARCHAR(10)  NOT NULL DEFAULT 'NONE',
    ADD COLUMN carry_out_reason        VARCHAR(200) NULL,
    ADD COLUMN carry_out_by_account_id BIGINT       NULL,
    ADD COLUMN carry_out_at            DATETIME     NULL,
    ADD COLUMN edited_at               DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3);

-- 2. 기존 자료 옮기기 (E-32). 시드가 없는 DB에서도 돌므로 시드 값을 가정하지 않는다
UPDATE equipment SET model_name = '미상', serial_number = CONCAT('MIG-', id), edited_at = updated_at;

-- 사용 중지 → 불용 (반출·현재 위치·업무 표시 없음)
UPDATE equipment
SET status = 'DISPOSED', current_department_id = NULL, current_place = NULL, work_item_id = NULL
WHERE active = FALSE;

-- 사람이 정한 반출: 반출 여부로 옮기고 사유를 남긴다(바꾼 사람 없음 = 체계)
UPDATE equipment
SET carry_out = 'OUT', carry_out_reason = '기존 자료 옮김', carry_out_at = NOW()
WHERE status = 'OUT' AND work_item_id IS NULL;

-- 업무가 정한 반출 예정·반출: 같은 값으로 옮긴다(서버가 켜진 뒤 1분 안에 다시 계산)
UPDATE equipment SET carry_out = status WHERE status IN ('OUT', 'RESERVED') AND work_item_id IS NOT NULL;

-- 상태: 보관·반출 예정·반출 → 미사용. 정비중(MAINTENANCE)·고장·불용대기는 그대로
UPDATE equipment SET status = 'UNUSED' WHERE status IN ('STORED', 'OUT', 'RESERVED');

-- 3. NOT NULL, 기본 상태, 사용 여부 열 지우기
ALTER TABLE equipment
    MODIFY COLUMN model_name    VARCHAR(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_as_ci NOT NULL,
    MODIFY COLUMN serial_number VARCHAR(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_as_ci NOT NULL,
    MODIFY COLUMN status        VARCHAR(20)  NOT NULL DEFAULT 'UNUSED',
    DROP COLUMN active,
    ADD CONSTRAINT fk_equipment_carry_out_account FOREIGN KEY (carry_out_by_account_id) REFERENCES account (id);

-- 4. 불용이 아닌 장비끼리 같은 모델명 안에서 시리얼넘버가 겹치지 않게 (REQ-501). 불용이면 NULL이라 겹침으로 보지 않는다
ALTER TABLE equipment
    ADD COLUMN active_model_name VARCHAR(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_as_ci
        GENERATED ALWAYS AS (CASE WHEN status <> 'DISPOSED' THEN model_name END) STORED,
    ADD UNIQUE KEY uk_equipment_active_serial (active_model_name, serial_number);

-- 5. SW 목록 (줄 순서 sort_order)
CREATE TABLE equipment_software (
    equipment_id BIGINT       NOT NULL,
    sort_order   INT          NOT NULL,
    name         VARCHAR(100) NOT NULL,
    version      VARCHAR(100) NULL,
    installed_on DATE         NULL,
    PRIMARY KEY (equipment_id, sort_order),
    CONSTRAINT fk_equipment_software_equipment FOREIGN KEY (equipment_id) REFERENCES equipment (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

-- 네트워크 목록. mac_key: 16진수 12자리 대문자. active_mac_key: 장비가 불용이 아니면 mac_key, 불용이면 NULL(서버가 맞춤)
CREATE TABLE equipment_network (
    equipment_id   BIGINT       NOT NULL,
    sort_order     INT          NOT NULL,
    name           VARCHAR(100) NULL,
    ip             VARCHAR(100) NULL,
    mac            VARCHAR(100) NULL,
    mac_key        CHAR(12)     NULL,
    active_mac_key CHAR(12)     NULL,
    PRIMARY KEY (equipment_id, sort_order),
    UNIQUE KEY uk_equipment_network_active_mac (active_mac_key),
    CONSTRAINT fk_equipment_network_equipment FOREIGN KEY (equipment_id) REFERENCES equipment (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

-- 6. 구성도: 상자 글 항목, 공유 권한자에게 보일 항목(처음 값: 네트워크 목록을 뺀 모든 항목, E-31)
ALTER TABLE diagram
    ADD COLUMN box_text_fields VARCHAR(300) NOT NULL DEFAULT 'NAME',
    ADD COLUMN share_fields    VARCHAR(500) NOT NULL DEFAULT 'DEPARTMENT,LOCATION,SYSTEM_NAME,NAME,MODEL_NAME,SERIAL_NUMBER,COMMON_NAME,MEMO,HW_VERSION,HW_INSTALLED_ON,OS_TYPE,OS_VERSION,OS_INSTALLED_ON,SOFTWARE,NETWORK_KIND,INTRODUCED_ON,INTRO_PROJECT,MAINTENANCE_PROJECT,STATUS,CARRY_OUT,CURRENT_LOCATION,CARRY_OUT_REASON';

-- 7. 설치장소(예전 보관 위치): 사용 중인 것끼리 이름이 겹치지 않게 (REQ-512).
-- 이미 겹치는 이름은 id가 작은 것을 두고 나머지 뒤에 " (#id)"를 붙인다(50자를 넘으면 앞부분을 자름, E-32)
UPDATE location l
    JOIN (SELECT id, ROW_NUMBER() OVER (PARTITION BY name ORDER BY id) AS rn FROM location WHERE active = TRUE) d ON d.id = l.id
SET l.name = CONCAT(LEFT(l.name, 50 - CHAR_LENGTH(CONCAT(' (#', l.id, ')'))), ' (#', l.id, ')')
WHERE d.rn > 1;

ALTER TABLE location
    ADD COLUMN active_name VARCHAR(50) GENERATED ALWAYS AS (CASE WHEN active THEN name END) STORED,
    ADD UNIQUE KEY uk_location_active_name (active_name);
