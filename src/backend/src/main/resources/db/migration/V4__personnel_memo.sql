-- 인원 비고: 부서관리 ▸ 인원에 보이는 메모. 인원관리에서 적는다 (DEC-058)
ALTER TABLE personnel
    ADD COLUMN memo VARCHAR(200) NULL AFTER position;
