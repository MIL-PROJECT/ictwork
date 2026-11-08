-- Legacy OMNIS operations, adapted to the existing department/account policy.
-- No demo rows or existing user data are imported automatically.
CREATE TABLE operation_system (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
 department_id BIGINT NOT NULL,
 code VARCHAR(64) NOT NULL,
 name VARCHAR(100) NOT NULL,
 category VARCHAR(60) NOT NULL,
 status VARCHAR(20) NOT NULL,
 uptime_pct DECIMAL(5,2) NOT NULL,
 remarks VARCHAR(2000) NOT NULL DEFAULT '',
 version BIGINT NOT NULL DEFAULT 0,
 updated_at DATETIME(6) NOT NULL,
 updated_by BIGINT NOT NULL,
 deleted_at DATETIME(6) NULL,
 CONSTRAINT uk_operation_code UNIQUE (code),
 CONSTRAINT fk_operation_department FOREIGN KEY (department_id) REFERENCES department(id),
 CONSTRAINT fk_operation_editor FOREIGN KEY (updated_by) REFERENCES account(id),
 CONSTRAINT chk_operation_uptime CHECK (uptime_pct >= 0 AND uptime_pct <= 100),
 INDEX ix_operation_department (department_id, deleted_at)
);
CREATE TABLE operation_history (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
 system_id BIGINT NOT NULL,
 action VARCHAR(20) NOT NULL,
 name VARCHAR(100) NOT NULL,
 category VARCHAR(60) NOT NULL,
 status VARCHAR(20) NOT NULL,
 uptime_pct DECIMAL(5,2) NOT NULL,
 remarks VARCHAR(2000) NOT NULL,
 version BIGINT NOT NULL,
 recorded_at DATETIME(6) NOT NULL,
 recorded_by BIGINT NOT NULL,
 CONSTRAINT fk_operation_history_system FOREIGN KEY (system_id) REFERENCES operation_system(id),
 CONSTRAINT fk_operation_history_account FOREIGN KEY (recorded_by) REFERENCES account(id),
 CONSTRAINT uk_operation_history_version UNIQUE (system_id, version),
 INDEX ix_operation_history_time (system_id, recorded_at)
);
