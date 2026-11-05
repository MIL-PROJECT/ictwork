-- 업무 등록·업무 요청 구분, 요청받은 부서, 결재 단계 구분 바꾸기 (DEC-061)

-- 요청 종류: REQUEST(업무 요청: 받는 부서를 고름) / REGISTER(업무 등록: 받는 부서는 우리 부서, 결재 뒤 접수를 건너뜀)
ALTER TABLE work_request ADD COLUMN kind VARCHAR(10) NOT NULL DEFAULT 'REQUEST' AFTER request_type_id;

-- 요청받은 부서: 업무 등록에서 우리에게 일을 맡긴 부서(기록·열람·알림)
CREATE TABLE request_origin (
    request_id    BIGINT NOT NULL,
    department_id BIGINT NOT NULL,
    PRIMARY KEY (request_id, department_id),
    CONSTRAINT fk_request_origin_request FOREIGN KEY (request_id) REFERENCES work_request (id),
    CONSTRAINT fk_request_origin_department FOREIGN KEY (department_id) REFERENCES department (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

-- 결재 단계 구분: 승인·열람 → 검토·결재. 승인 단계 중 마지막이 결재, 나머지 승인 단계는 검토.
-- 열람 단계는 없앤다: 문서의 열람 단계는 검토·자동 통과로 남기고(이력 유지), 기본 경로의 열람 단계는 지운다
UPDATE approval_step SET result = 'SKIPPED' WHERE kind = 'VIEW';

UPDATE approval_step s
JOIN (SELECT doc_id, round, MAX(step_order) AS last_order FROM approval_step WHERE kind <> 'VIEW' GROUP BY doc_id, round) x
  ON x.doc_id = s.doc_id AND x.round = s.round
SET s.kind = CASE WHEN s.kind = 'VIEW' THEN 'REVIEW' WHEN s.step_order = x.last_order THEN 'APPROVE' ELSE 'REVIEW' END;

DELETE FROM approval_route_step WHERE kind = 'VIEW';

UPDATE approval_route_step s
JOIN (SELECT route_id, MAX(step_order) AS last_order FROM approval_route_step GROUP BY route_id) x ON x.route_id = s.route_id
SET s.kind = CASE WHEN s.step_order = x.last_order THEN 'APPROVE' ELSE 'REVIEW' END;
