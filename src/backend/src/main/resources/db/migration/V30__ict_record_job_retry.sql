-- 기록 파일 일 실패 처리 (slices/15 8.3, 검토 15-F3): 실패할 때마다 횟수·마지막 오류를 남기고 다시 시도 간격을 늘린다(30초부터 두 배씩, 1시간 상한).
-- 본문 칸 상한(stored_file_body MEDIUMBLOB 16MB, V16)을 넘는 일은 stopped_at을 채워 멈춘다. 멈춘 일은 판 정리·지운 박스 정리를 막지 않고,
-- 새로 저장하거나 기록 파일을 되살리면(일을 다시 넣으면) 네 칸을 비워 다시 한다
-- MySQL DDL은 트랜잭션이 아니어서 한 문장에 한 가지 변경만 쓴다
ALTER TABLE ict_record_job ADD COLUMN fail_count INT NOT NULL DEFAULT 0 COMMENT '이어 실패한 횟수';
ALTER TABLE ict_record_job ADD COLUMN next_try_at DATETIME(3) NULL COMMENT '이 시각부터 다시 한다(NULL = 바로)';
ALTER TABLE ict_record_job ADD COLUMN last_error VARCHAR(500) NULL COMMENT '마지막 실패 까닭';
ALTER TABLE ict_record_job ADD COLUMN stopped_at DATETIME(3) NULL COMMENT '본문 칸 상한을 넘어 멈춘 시각(NULL = 진행)';
