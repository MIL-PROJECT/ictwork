-- 간편 부서 요청: 희망 일정은 확정 작업 기간과 별도로 보존한다.
ALTER TABLE work_request
    ADD COLUMN department_intake BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN preferred_date_1 DATE NULL,
    ADD COLUMN preferred_period_1 VARCHAR(2) NULL,
    ADD COLUMN preferred_date_2 DATE NULL,
    ADD COLUMN preferred_period_2 VARCHAR(2) NULL;
