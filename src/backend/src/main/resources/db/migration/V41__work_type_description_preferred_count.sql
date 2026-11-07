-- 업무 유형 설명(추천 근거)과 유형별 희망 일자 개수. 부서 업무 안내(department_service_profile)는 유형 설명으로 옮겨 없앤다
ALTER TABLE work_type
    ADD COLUMN description VARCHAR(500) NOT NULL DEFAULT '',
    ADD COLUMN preferred_count INT NOT NULL DEFAULT 1,
    ALTER COLUMN schedule_mode SET DEFAULT 'PREFERRED';
-- 일정 방식: NONE(받지 않음)·PREFERRED(희망 일자 preferred_count개까지)·EXACT_REQUIRED(정확한 일정 필수). 예전 OPTIONAL은 희망 일자 하나
UPDATE work_type SET schedule_mode = 'PREFERRED' WHERE schedule_mode = 'OPTIONAL';

-- 희망 일자는 지망 순서대로 3개까지
ALTER TABLE work_request
    RENAME COLUMN preferred_date TO preferred_date_1,
    RENAME COLUMN preferred_time TO preferred_time_1,
    ADD COLUMN preferred_date_2 DATE NULL,
    ADD COLUMN preferred_time_2 TIME NULL,
    ADD COLUMN preferred_date_3 DATE NULL,
    ADD COLUMN preferred_time_3 TIME NULL;

DROP TABLE department_service_profile;
