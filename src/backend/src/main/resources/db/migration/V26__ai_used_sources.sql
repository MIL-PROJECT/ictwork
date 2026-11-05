-- AI 질의응답: 답이 근거로 쓴 모든 출처 (slices/12 12.3, REQ-B07·H-19, DEC-098)
-- sources는 화면에 보인 출처, used_sources는 모델에 넘긴 모든 글(찾은 조각)·조회 도구가 찾은 것(업무 현황은 부서 STATUS)이다.
-- 이어 묻기 때 used_sources로 지금 권한을 다시 본다(보인 출처만 보면 숨긴 근거가 남은 앞 답이 넘어감). 예전 답은 비어 있어 sources로 본다
ALTER TABLE ai_message ADD COLUMN used_sources TEXT NULL AFTER sources;
