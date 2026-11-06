CREATE TABLE department_service_profile (
    department_id BIGINT NOT NULL PRIMARY KEY,
    systems TEXT NOT NULL,
    responsibilities TEXT NOT NULL,
    symptoms TEXT NOT NULL,
    exclusions TEXT NOT NULL,
    revision BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT fk_service_profile_department FOREIGN KEY (department_id) REFERENCES department(id)
);
ALTER TABLE work_type ADD COLUMN schedule_mode VARCHAR(20) NOT NULL DEFAULT 'OPTIONAL';
ALTER TABLE work_request
    ADD COLUMN approval_requested BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN requested_start_date DATE NULL,
    ADD COLUMN requested_start_time TIME NULL,
    ADD COLUMN requested_end_date DATE NULL,
    ADD COLUMN requested_end_time TIME NULL;
