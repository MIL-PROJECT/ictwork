-- 요청 칸·신청자 정보·담당자·자동 완료 체크 하나 (slices/11 3.3.1, 명세 REQ-101·110·114, G-1~G-4)
-- 업무 데이터가 없는 DB를 전제로 한다(G-55). 새 NOT NULL 열은 기본값을 주고 넣은 뒤 기본값을 뺀다
-- MySQL DDL은 트랜잭션이 아니어서 한 문장에 한 가지 변경만 쓴다(실패한 곳을 알기 쉽게, slices/11 2.1)

-- 1. 요청 칸 (G-2): 장소(글 1~100자). 사유는 데이터 이름 content 그대로 (DEC-085)
ALTER TABLE work_request ADD COLUMN place VARCHAR(100) NOT NULL DEFAULT '' AFTER title;
ALTER TABLE work_request ALTER COLUMN place DROP DEFAULT;

-- 2. 신청자 정보 (G-4): 작성(다시 올리기 포함)하는 사람의 그때 인원 정보. 소속은 부서 id(requester_department_id)와 그때 경로 글을 함께 둔다. 신청일자는 sent_at
ALTER TABLE work_request ADD COLUMN applicant_department_name VARCHAR(300) NOT NULL DEFAULT '' AFTER requester_department_id;
ALTER TABLE work_request ADD COLUMN applicant_rank_code VARCHAR(20) NOT NULL DEFAULT 'CIVILIAN' AFTER applicant_department_name;
ALTER TABLE work_request ADD COLUMN applicant_name VARCHAR(50) NOT NULL DEFAULT '' AFTER applicant_rank_code;
ALTER TABLE work_request ADD COLUMN applicant_contact VARCHAR(30) NULL AFTER applicant_name;
ALTER TABLE work_request ALTER COLUMN applicant_department_name DROP DEFAULT;
ALTER TABLE work_request ALTER COLUMN applicant_rank_code DROP DEFAULT;
ALTER TABLE work_request ALTER COLUMN applicant_name DROP DEFAULT;

-- 3. 담당자 (G-3): 체계 전체의 사용 중 인원 1~20명. 보낸 뒤부터 그 요청을 본다(처리 권한·알림 없음)
CREATE TABLE request_assignee (
    request_id   BIGINT NOT NULL,
    personnel_id BIGINT NOT NULL,
    PRIMARY KEY (request_id, personnel_id),
    KEY ix_request_assignee_personnel (personnel_id),
    CONSTRAINT fk_request_assignee_request FOREIGN KEY (request_id) REFERENCES work_request (id),
    CONSTRAINT fk_request_assignee_personnel FOREIGN KEY (personnel_id) REFERENCES personnel (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

-- 4. 자동 완료는 체크 하나 (G-1). 완료되는 때(work_item.auto_complete_at)는 작업 기간 끝으로 서버가 계산한다(색인 ix_work_item_auto 유지)
ALTER TABLE work_item DROP COLUMN auto_complete_mode;
ALTER TABLE plan_proposal DROP COLUMN auto_complete_mode;
ALTER TABLE plan_proposal DROP COLUMN auto_complete_at;
