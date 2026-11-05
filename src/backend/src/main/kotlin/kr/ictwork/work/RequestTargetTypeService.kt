package kr.ictwork.work

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import kr.ictwork.admin.Actor
import kr.ictwork.admin.DepartmentTree
import kr.ictwork.admin.badRequest
import kr.ictwork.common.error.ApiException
import kr.ictwork.file.FilePurposes
import kr.ictwork.file.FileService
import kr.ictwork.form.FormDefinition
import kr.ictwork.form.FormMode
import kr.ictwork.form.FormRules
import kr.ictwork.worktype.TargetTypes
import kr.ictwork.worktype.TypeView
import kr.ictwork.worktype.WorkForms
import kr.ictwork.worktype.WorkType
import kr.ictwork.worktype.WorkTypeRepository
import kr.ictwork.worktype.WorkTypeResolver
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service

/**
 * 받는 부서 하나와 고른 업무 유형 (REQ-118, slices/11 3.4.3). 업무 등록은 받는 부서가 우리 부서로 정해져 departmentId를 무시한다
 */
data class TargetInput(val departmentId: Long? = null, val types: List<TypeChoiceInput> = emptyList())

/**
 * 고른 유형 하나와 그 양식 (slices/11 3.5.2). formVersionId: 화면이 채운 양식 판(양식 없으면 null) — 지금 판과 다르면 409 FORM_CHANGED(G-59).
 * answers: 질문 key → 답 (모양은 FormRules.validateValue)
 */
data class TypeChoiceInput(val workTypeId: Long, val formVersionId: Long? = null, val answers: JsonNode? = null)

/**
 * 받는 부서마다 고른 업무 유형과 양식 답을 검사하고 저장한다 (REQ-118, G-34·G-37·G-59·G-64·G-69). 받는 부서 모음과 유형 행·파일 답은 이 서비스로만 함께 바꾼다.
 * 검사는 처음 올리기·다시 올리기 때만 한다(발송 결재 마지막 승인 때는 하지 않음, G-69). 답은 올릴 때의 판으로 검사해 그 판과 함께 남긴다.
 * 받는 부서 표와 복합 FK로 묶지 않으므로 저장 끝에 "유형 행의 받는 부서 ⊆ 받는 부서"를 확인한다 (slices/11 5.1 ⑲)
 */
@Service
class RequestTargetTypeService(
    private val repository: RequestTargetTypeRepository,
    private val answerFileRepository: RequestAnswerFileRepository,
    private val workTypeRepository: WorkTypeRepository,
    private val resolver: WorkTypeResolver,
    private val forms: WorkForms,
    private val fileService: FileService,
    private val objectMapper: ObjectMapper,
) {
    private class Wanted(val target: Long, val type: WorkType, val choice: TypeChoiceInput)

    /** 요청의 받는 부서(request.targetDepartmentIds를 정한 뒤)마다 고른 유형·답을 검사해 저장한다. 다시 올릴 때 원래 고른 유형은 그대로 쓸 수 있다 */
    fun apply(actor: Actor, request: WorkRequest, targets: List<TargetInput>) {
        val requestId = checkNotNull(request.id)
        val choices: Map<Long, List<TypeChoiceInput>> = if (request.kind == RequestKinds.REGISTER) {
            mapOf(request.targetDepartmentIds.single() to targets.singleOrNull()?.types.orEmpty())
        } else {
            targets.associate { checkNotNull(it.departmentId) to it.types }
        }
        val previous = repository.findByRequestIdOrderBySortOrderAscIdAsc(requestId)
        val types = workTypeRepository.findAll()
        val byId = types.associateBy { checkNotNull(it.id) }
        val wanted = request.targetDepartmentIds.sorted().flatMap { target ->
            val picked = choices[target].orEmpty()
            val ids = picked.map { it.workTypeId }
            if (ids.isEmpty()) throw badRequest("WORK_TYPES_REQUIRED", "받는 부서마다 업무 유형을 하나 이상 고르세요.")
            if (ids.toSet().size != ids.size) throw badRequest("WORK_TYPE_INVALID", "같은 받는 부서에 같은 업무 유형을 두 번 고를 수 없습니다.")
            val listed = resolver.resolve(actor.tree, target, types).map { it.typeId }.toSet()
            val kept = previous.filter { it.targetDepartmentId == target }.map { it.workTypeId }.toSet()
            picked.map { choice ->
                val type = byId[choice.workTypeId]
                val ok = type != null && (choice.workTypeId in listed || (choice.workTypeId in kept && resolver.stillUsable(actor.tree, target, type)))
                if (!ok) throw badRequest("WORK_TYPE_INVALID", "고를 수 없는 업무 유형입니다. 사용 중인 유형을 다시 고르세요.")
                Wanted(target, checkNotNull(type), choice)
            }
        }
        // 작성 중 양식이 바뀌었으면(화면의 판이 지금 판과 다름) 저장을 거부한다. 화면은 새 판을 불러와 같은 질문(key) 답을 남긴다 (G-59)
        val changed = wanted.filter { it.choice.formVersionId != formOf(it.type) }.map { checkNotNull(it.type.id) }.distinct()
        if (changed.isNotEmpty()) {
            throw ApiException(HttpStatus.CONFLICT, "FORM_CHANGED", "작성하는 동안 업무 유형의 양식이 바뀌었습니다. 새 양식을 불러왔으니 확인한 뒤 다시 올리세요.", rows = changed)
        }
        val definitions = forms.definitions(wanted.map { formOf(it.type) })
        // 답 검사 (지나는 구역의 질문만, 건너뛴 구역 답은 버림). 틀린 곳은 field = 질문 key, rows = [받는 부서·유형]
        val cleaned = wanted.associate { w ->
            val versionId = formOf(w.type)
            val clean = versionId?.let { id ->
                try {
                    FormRules.validateAnswers(checkNotNull(definitions[id]), w.choice.answers, FormMode.FORM)
                } catch (e: ApiException) {
                    throw ApiException(e.status, e.code, e.message, e.field, listOf(mapOf("departmentId" to w.target, "workTypeId" to w.type.id)))
                }
            }
            (w.target to checkNotNull(w.type.id)) to clean
        }
        // 파일 답 묶기: 내가 올린 임시 파일이거나 이미 이 요청의 파일 답이어야 한다
        val previousFiles = answerFileRepository.findByRequestTargetTypeIdIn(previous.mapNotNull { it.id })
        val wantedFiles = cleaned.values.filterNotNull().flatMap { it.fileIds.values.flatten() }
        if (wantedFiles.toSet().size != wantedFiles.size) throw badRequest("ANSWER_INVALID", "같은 파일을 두 번 넣을 수 없습니다.")
        fileService.link(actor, wantedFiles, FilePurposes.FORM_ANSWER, previousFiles.map { it.fileId }.toSet())
        // 파일 답 행은 모두 지웠다가 다시 넣는다(유형 행이 빠지거나 바뀌어도 FK 순서가 꼬이지 않게). 빠진 파일은 지운다
        if (previousFiles.isNotEmpty()) {
            answerFileRepository.deleteAll(previousFiles)
            answerFileRepository.flush()
            fileService.delete(previousFiles.map { it.fileId }.filter { it !in wantedFiles })
        }
        // 빠진 받는 부서·유형의 행을 지우고 남는 행은 순서·그때 이름·판·답을 새로 적는다
        val keys = cleaned.keys
        val removed = previous.filter { (it.targetDepartmentId to it.workTypeId) !in keys }
        if (removed.isNotEmpty()) {
            repository.deleteAll(removed)
            repository.flush()
        }
        val existing = previous.filter { it !in removed }.associateBy { it.targetDepartmentId to it.workTypeId }
        wanted.forEachIndexed { index, w ->
            val typeId = checkNotNull(w.type.id)
            val path = resolver.path(actor.tree, w.type.departmentId)
            val row = existing[w.target to typeId] ?: RequestTargetType(requestId, w.target, typeId, index, w.type.name, path)
            row.sortOrder = index
            row.typeName = w.type.name
            row.typeDepartmentPath = path
            val clean = cleaned[w.target to typeId]
            row.formVersionId = formOf(w.type)
            row.answers = clean?.let { objectMapper.writeValueAsString(it.values) }
            row.answerText = clean?.text
            row.hasFiles = clean?.fileIds?.values?.any { it.isNotEmpty() } == true
            val saved = repository.save(row)
            clean?.fileIds?.forEach { (key, ids) -> ids.forEach { answerFileRepository.save(RequestAnswerFile(it, checkNotNull(saved.id), key)) } }
        }
        repository.flush()
        answerFileRepository.flush()
        check(repository.findByRequestIdOrderBySortOrderAscIdAsc(requestId).all { it.targetDepartmentId in request.targetDepartmentIds }) { "받는 부서 밖의 유형 행" }
    }

    /** 유형의 지금 양식 판. "기타"는 양식이 없다(G-57) */
    private fun formOf(type: WorkType): Long? = if (type.isOther) null else type.currentFormVersionId

    /**
     * 받는 부서별 유형과 양식 답 (받는 부서 id 순, 그 안은 고른 순서). departments가 있으면 그 받는 부서만(화면 기준 보기, slices/11 2.8).
     * tree를 주면(고쳐 다시 올릴 요청의 상세) 줄마다 지금 그대로 쓸 수 있는지(usable)를 적는다 (G-64: 사용 중지와 "더 가까운 같은 이름에 가려짐"을 화면이 구분)
     */
    fun targetsOf(request: WorkRequest, departments: Set<Long>? = null, tree: DepartmentTree? = null): List<TargetTypes> {
        val rows = repository.findByRequestIdOrderBySortOrderAscIdAsc(checkNotNull(request.id))
            .filter { departments == null || it.targetDepartmentId in departments }
        val types = if (tree == null) emptyMap() else workTypeRepository.findAllById(rows.map { it.workTypeId }.toSet()).associateBy { checkNotNull(it.id) }
        val views = detailViews(rows).groupBy { it.first.targetDepartmentId }
        return request.targetDepartmentIds.sorted().filter { departments == null || it in departments }
            .map { target ->
                TargetTypes(target, views[target].orEmpty().map { (row, view) ->
                    val type = types[row.workTypeId]
                    val usable = tree?.let { t -> type?.let { resolver.stillUsable(t, target, it) } ?: false }
                    view.copy(usable = usable, currentFormVersionId = type?.let(::formOf))
                })
            }
    }

    /** 처리 건의 유형과 양식 답: 원래 받는 부서의 것 (이송된 건도 원래 유형·답, REQ-118) */
    fun ofItem(item: WorkItem): List<TypeView> =
        detailViews(repository.findByRequestIdOrderBySortOrderAscIdAsc(item.requestId).filter { it.targetDepartmentId == item.originDepartmentId }).map { it.second }

    /** 보이는 유형들이 쓴 양식 판(과 다시 올릴 요청이면 지금 판)의 정의 (판 id → 정의, 같은 판은 한 번만) */
    fun formsOf(types: List<TypeView>): Map<Long, FormDefinition> = forms.definitions(types.flatMap { listOf(it.formVersionId, it.currentFormVersionId) })

    /** 목록용: 요청마다 (받는 부서 → 유형). 답은 담지 않는다 */
    fun ofRequests(requestIds: Collection<Long>): Map<Long, Map<Long, List<TypeView>>> {
        if (requestIds.isEmpty()) return emptyMap()
        return repository.findByRequestIdIn(requestIds.toSet()).sortedWith(compareBy({ it.sortOrder }, { it.id }))
            .groupBy { it.requestId }.mapValues { (_, rows) -> rows.groupBy({ it.targetDepartmentId }, ::view) }
    }

    private fun detailViews(rows: List<RequestTargetType>): List<Pair<RequestTargetType, TypeView>> {
        val files = answerFileRepository.findByRequestTargetTypeIdIn(rows.mapNotNull { it.id })
        val fileViews = fileService.views(files.map { it.fileId }).associateBy { it.id }
        val byRow = files.groupBy { it.requestTargetTypeId }
        return rows.map { row ->
            val answers = row.answers?.let { objectMapper.readTree(it) }
            // 파일 답은 답에 적힌 순서대로
            val rowFiles = byRow[row.id].orEmpty().groupBy { it.questionKey }.mapValues { (key, list) ->
                val order = answers?.get(key)?.map(JsonNode::asLong).orEmpty()
                list.sortedBy { order.indexOf(it.fileId) }.mapNotNull { fileViews[it.fileId] }
            }
            row to view(row).copy(formVersionId = row.formVersionId, answers = answers, files = rowFiles.takeIf { row.formVersionId != null })
        }
    }

    private fun view(row: RequestTargetType) = TypeView(row.workTypeId, row.typeName, row.typeDepartmentPath)
}
