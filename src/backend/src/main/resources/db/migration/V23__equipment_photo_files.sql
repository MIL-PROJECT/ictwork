-- 장비 사진·관련자료 (slices/10 20.1, 명세 REQ-520·521·505, G-132·G-133, DEC-097)
-- 파일 정보·본문은 업무 첨부와 같은 stored_file·stored_file_body에 두고(용도 EQUIPMENT_PHOTO·EQUIPMENT_FILE), 장비와의 연결만 여기에 둔다
-- MySQL DDL은 트랜잭션이 아니어서 한 문장에 한 가지 변경만 쓴다(slices/11 2.1)

-- 장비 사진 (REQ-520): 장비마다 한 장. 바꾸면 새 파일로 바꾸고 옛 파일은 지운다
CREATE TABLE equipment_photo (
    equipment_id BIGINT NOT NULL,
    file_id      BIGINT NOT NULL,
    PRIMARY KEY (equipment_id),
    UNIQUE KEY uk_equipment_photo_file (file_id),
    CONSTRAINT fk_equipment_photo_equipment FOREIGN KEY (equipment_id) REFERENCES equipment (id),
    CONSTRAINT fk_equipment_photo_file FOREIGN KEY (file_id) REFERENCES stored_file (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

-- 장비 관련자료 (REQ-521): 여러 개, 올린 순서(file_id)대로 보인다. 지우면 기록을 남기지 않는다
CREATE TABLE equipment_file (
    file_id      BIGINT NOT NULL,
    equipment_id BIGINT NOT NULL,
    PRIMARY KEY (file_id),
    KEY ix_equipment_file (equipment_id, file_id),
    CONSTRAINT fk_equipment_file_equipment FOREIGN KEY (equipment_id) REFERENCES equipment (id),
    CONSTRAINT fk_equipment_file_file FOREIGN KEY (file_id) REFERENCES stored_file (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

-- 구성도 공유 항목 "장비 사진"(REQ-505, G-132)은 처음 켜짐. 공유 항목은 켠 항목의 쉼표 목록이므로 기존 구성도에도 값을 넣는다
UPDATE diagram SET share_fields = CONCAT_WS(',', NULLIF(share_fields, ''), 'EQUIPMENT_PHOTO')
WHERE FIND_IN_SET('EQUIPMENT_PHOTO', share_fields) = 0;

ALTER TABLE diagram ALTER COLUMN share_fields SET DEFAULT 'DEPARTMENT,LOCATION,SYSTEM_NAME,NAME,MODEL_NAME,SERIAL_NUMBER,COMMON_NAME,MEMO,HW_VERSION,HW_INSTALLED_ON,OS_TYPE,OS_VERSION,OS_INSTALLED_ON,SOFTWARE,NETWORK_KIND,INTRODUCED_ON,INTRO_PROJECT,MAINTENANCE_PROJECT,STATUS,CARRY_OUT,CURRENT_LOCATION,CARRY_OUT_REASON,EQUIPMENT_PHOTO';
