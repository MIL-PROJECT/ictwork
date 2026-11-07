-- 간편 부서 요청의 희망 일정: 1·2지망(날짜·오전/오후) 대신 희망 일자 하나와 희망 시각(선택)
ALTER TABLE work_request
    RENAME COLUMN preferred_date_1 TO preferred_date,
    ADD COLUMN preferred_time TIME NULL,
    DROP COLUMN preferred_period_1,
    DROP COLUMN preferred_date_2,
    DROP COLUMN preferred_period_2;
