-- 작업 일자와 종료예정 시각 (DEC-062)

-- 요청의 작업 일자: 업무 요청은 희망 작업일(접수되면 처리 건에 옮김), 업무 등록은 작업 일자(지원 계획의 기간)
-- 시작일은 새 요청에서 필수(이전 요청은 비어 있음), 시작 시각·종료예정일·종료예정 시각은 선택(종료예정일이 없으면 시작일 하루)
ALTER TABLE work_request
    ADD COLUMN work_start_date DATE NULL AFTER content,
    ADD COLUMN work_start_time TIME NULL AFTER work_start_date,
    ADD COLUMN work_end_date   DATE NULL AFTER work_start_time,
    ADD COLUMN work_end_time   TIME NULL AFTER work_end_date;

-- 종료예정 시각: "기간이 끝나면 자동 완료"가 이 시각에 완료한다(없으면 종료일 다음 날 0시)
ALTER TABLE work_item ADD COLUMN end_time TIME NULL AFTER end_date;
ALTER TABLE plan_proposal ADD COLUMN end_time TIME NULL AFTER start_time;
