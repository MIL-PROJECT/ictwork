package kr.ictwork.worktype

/**
 * 시험 조직 도우미 (slices/11 3.4.10): 저장소로 바로 만든 부서에는 "기타"가 없어(부서 관리 서비스를 거치지 않음) 시험이 함께 만든다.
 * 그 부서의 "기타" id
 */
fun WorkTypeRepository.otherOf(departmentId: Long): Long =
    findByDepartmentIdOrderBySortOrderAscIdAsc(departmentId).firstOrNull { it.isOther }?.id ?: saveAndFlush(WorkType.other(departmentId)).id!!

/** 받는 부서마다 그 부서 "기타" 하나를 고른 요청 본문의 targets (REQ-118). 업무 등록은 부서 하나(우리 부서) */
fun WorkTypeRepository.otherTargets(vararg departmentIds: Long?): List<Map<String, Any>> =
    departmentIds.map { mapOf("departmentId" to it!!, "types" to listOf(mapOf("workTypeId" to otherOf(it)))) }
