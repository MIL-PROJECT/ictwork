-- 우선순위 (REQ-107, DEC-065): 일반 NORMAL / 긴급 URGENT, 기본 일반.
-- 처리 건마다 둔다. 업무 등록은 처리 건이 결재가 끝날 때 생기므로 요청에 처음 값을 두었다가 옮긴다
ALTER TABLE work_item ADD COLUMN priority VARCHAR(10) NOT NULL DEFAULT 'NORMAL';
ALTER TABLE work_request ADD COLUMN priority VARCHAR(10) NOT NULL DEFAULT 'NORMAL';
