-- 근무표 수동 배치 표시 (REQ-404, G-150, slices/19 2장)
-- 수동 바꾸기(바꾸기·넣기·맞바꾸기·옮기기)로 넣거나 바꾼 배치는 TRUE, 월별 채우기로 넣은 배치는 FALSE. 이미 있던 배치는 FALSE
ALTER TABLE duty_placement ADD COLUMN manual BOOLEAN NOT NULL DEFAULT FALSE;
