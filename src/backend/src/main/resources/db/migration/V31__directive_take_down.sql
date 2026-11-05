-- 지시사항 내리기·다시 올림 (REQ-601, G-146, slices/13 14.1). 다시 올리면 두 열을 NULL로 되돌린다
ALTER TABLE directive
    ADD COLUMN taken_down_at DATETIME NULL,                 -- 내린 시각 (NULL이면 올라 있음)
    ADD COLUMN taken_down_by_account_id BIGINT NULL,        -- 내린 계정 (응답에 넣지 않음)
    ADD CONSTRAINT fk_directive_taken_down_by FOREIGN KEY (taken_down_by_account_id) REFERENCES account (id);
ALTER TABLE directive_audit DROP CHECK ck_directive_audit_action;
ALTER TABLE directive_audit ADD CONSTRAINT ck_directive_audit_action CHECK (action IN ('CREATE', 'UPDATE', 'DELETE', 'TAKE_DOWN', 'RESTORE'));
