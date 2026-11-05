-- 지시사항 그림 (REQ-601, G-147, slices/13 15.1). 파일 정보·본문은 업무 첨부와 같은 stored_file·stored_file_body(용도 DIRECTIVE_IMAGE)에 두고,
-- 지시사항과의 연결과 순서만 여기에 둔다(V23 장비 사진과 같은 방식). 지시사항을 지우면 서비스가 이 행과 파일을 함께 지운다
CREATE TABLE directive_image (
    file_id      BIGINT NOT NULL,
    directive_id BIGINT NOT NULL,
    sort_order   INT    NOT NULL,   -- 더한 순서(0부터, 지운 자리는 메우지 않음)
    PRIMARY KEY (file_id),
    KEY ix_directive_image (directive_id, sort_order),
    CONSTRAINT fk_directive_image_directive FOREIGN KEY (directive_id) REFERENCES directive (id),
    CONSTRAINT fk_directive_image_file FOREIGN KEY (file_id) REFERENCES stored_file (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;
