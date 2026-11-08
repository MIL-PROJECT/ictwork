package kr.ictwork.worktype

import kr.ictwork.admin.AccessPolicy
import kr.ictwork.admin.Actor
import kr.ictwork.admin.badRequest
import kr.ictwork.admin.conflict
import kr.ictwork.admin.denied
import kr.ictwork.admin.notFound
import kr.ictwork.admin.validName
import kr.ictwork.file.FileKind
import kr.ictwork.file.FilePurposes
import kr.ictwork.file.FileService
import kr.ictwork.file.UploadedFile
import kr.ictwork.form.FormDefinition
import kr.ictwork.form.FormMode
import kr.ictwork.form.FormRules
import kr.ictwork.form.QuestionKinds
import kr.ictwork.common.error.ApiException
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.multipart.MultipartFile

/** 관리 화면의 유형 한 줄 (사용 중지 포함) */
data class WorkTypeAdminRow(
    val id: Long,
    val name: String,
    val isOther: Boolean,
    val active: Boolean,
    val sortOrder: Int,
    val version: Long,
    val formVersionId: Long?,
    val definition: FormDefinition?, // 지금 판 정의 (양식 편집기의 처음 값). 없으면 null
    val description: String, // 추천 근거. 비어 있으면 추천에 나오지 않는다("기타", 설명 전에 만든 유형)
    val scheduleMode: String, // 요청할 때 일정 입력 방식 (ScheduleModes)
    val preferredCount: Int, // PREFERRED일 때 받는 희망 일자 개수 1~3
)

/** 내가 유형을 고칠 수 있는 부서 (역할의 대상 부서) */
data class WorkTypeDepartment(val id: Long, val path: String)

data class WorkTypeCreateInput(val departmentId: Long, val name: String, val description: String? = null)

data class WorkTypeUpdateInput(
    val name: String? = null,
    val active: Boolean? = null,
    val version: Long? = null,
    val description: String? = null,
    val scheduleMode: String? = null,
    val preferredCount: Int? = null,
)

data class WorkTypeOrderInput(val departmentId: Long, val ids: List<Long>)

/** 양식 저장 (REQ-808): 정의와 유형의 version(동시 수정 검사, G-66) */
data class FormSaveInput(val definition: FormDefinition, val version: Long? = null)

/** 안내문 그림 보이기 */
class FormImage(val extension: String, val data: ByteArray)

/**
 * 업무 유형 관리 (REQ-808 유형 부분, slices/11 3.4.4). 그 부서의 사용 중인 부서 관리자 역할이 있는 사람만 고친다(하위 부서 제외, 전출과 무관, G-62).
 * 시스템 관리자도 그 역할이 없으면 403. 역할이 회수되면 만든 유형은 그대로 쓰이고 고치지만 못 한다(G-50).
 * 이름은 1~50자, 부서 안에서 겹치지 않는다(사용 중지 유형 포함). "기타"는 사용 중지·이름 바꾸기를 할 수 없고 맨 끝이다(G-57)
 */
@Service
class WorkTypeAdminService(
    private val accessPolicy: AccessPolicy,
    private val repository: WorkTypeRepository,
    private val resolver: WorkTypeResolver,
    private val versionRepository: WorkFormVersionRepository,
    private val imageRepository: WorkFormImageRepository,
    private val forms: WorkForms,
    private val fileService: FileService,
) {
    /** 고르기 목록: 받는 부서(업무 요청) 기준, 사용 중인 것만 (REQ-118) */
    @Transactional(readOnly = true)
    fun options(loginId: String, departmentId: Long): List<TypeOption> {
        val actor = accessPolicy.actor(loginId)
        if (actor.tree.find(departmentId)?.active != true) throw badRequest("DEPARTMENT_INVALID", "부서를 찾을 수 없습니다.")
        return withForms(resolver.resolve(actor.tree, departmentId))
    }

    /** 업무 등록의 고르기 목록: 우리 부서 기준 (REQ-114, G-33) */
    @Transactional(readOnly = true)
    fun ownOptions(loginId: String): List<TypeOption> {
        val actor = accessPolicy.actor(loginId)
        val own = actor.personnelDepartmentId?.takeIf { actor.tree.find(it)?.active == true }
            ?: throw badRequest("NO_DEPARTMENT", "소속 부서가 없어 업무를 등록할 수 없습니다.")
        return withForms(resolver.resolve(actor.tree, own))
    }

    /** 등록된 업무 걸러 보기의 유형 고르기 (REQ-117 ③): 모든 부서·유형 짝 */
    @Transactional(readOnly = true)
    fun filterOptions(loginId: String): List<FilterTypeOption> = resolver.all(accessPolicy.actor(loginId).tree)

    /** 고르기 목록 줄마다 지금 판 정의를 붙인다 (요청 화면이 바로 양식을 채움, slices/11 3.5.2) */
    private fun withForms(options: List<TypeOption>): List<TypeOption> {
        val definitions = forms.definitions(options.map { it.formVersionId })
        return options.map { it.copy(definition = it.formVersionId?.let(definitions::get)) }
    }

    @Transactional(readOnly = true)
    fun departments(loginId: String): List<WorkTypeDepartment> {
        val actor = accessPolicy.actor(loginId)
        if (!actor.isSysAdmin && !actor.isDeptAdmin) throw denied()
        return (if (actor.isSysAdmin) actor.tree.allIds().toList() else actor.roles.mapNotNull { it.departmentId?.takeIf(actor::deptAdminOf) }).distinct()
            .filter { actor.tree.find(it)?.active == true }
            .map { WorkTypeDepartment(it, resolver.path(actor.tree, it)) }
            .sortedBy { it.path }
    }

    /** 그 부서 유형 전부(사용 중지 포함), 부서 안 순서, "기타"는 맨 끝 */
    @Transactional(readOnly = true)
    fun list(loginId: String, departmentId: Long): List<WorkTypeAdminRow> {
        requireAdminOf(accessPolicy.actor(loginId), departmentId)
        return rows(ordered(departmentId))
    }

    @Transactional
    fun create(loginId: String, input: WorkTypeCreateInput): WorkTypeAdminRow {
        requireAdminOf(accessPolicy.actor(loginId), input.departmentId)
        val name = validName(input.name)
        if (repository.existsByDepartmentIdAndName(input.departmentId, name)) throw duplicate()
        val description = validDescription(input.description)
        val last = repository.findByDepartmentIdOrderBySortOrderAscIdAsc(input.departmentId).filter { !it.isOther }.maxOfOrNull { it.sortOrder } ?: 0
        return row(repository.saveAndFlush(WorkType(input.departmentId, name, false, last + 1).also { it.description = description }))
    }

    @Transactional
    fun update(loginId: String, id: Long, input: WorkTypeUpdateInput): WorkTypeAdminRow {
        val actor = accessPolicy.actor(loginId)
        val type = repository.findById(id).orElse(null) ?: throw notFound()
        requireAdminOf(actor, type.departmentId)
        if (input.version != type.version) throw conflict("WORK_TYPE_CONFLICT", "다른 관리자가 먼저 고쳤습니다. 새로 불러온 뒤 다시 시도하세요.")
        val name = input.name?.let { validName(it) }
        if (type.isOther && ((name != null && name != type.name) || input.active == false)) {
            throw badRequest("WORK_TYPE_OTHER_FIXED", "\"기타\"는 이름을 바꾸거나 사용 중지할 수 없습니다.")
        }
        if (name != null && name != type.name) {
            if (repository.existsByDepartmentIdAndName(type.departmentId, name)) throw duplicate()
            type.name = name
        }
        // "기타"는 설명·일정 방식을 두지 않는다(희망 일자 하나, 추천에 나오지 않음)
        input.description?.let { text ->
            if (type.isOther) throw badRequest("WORK_TYPE_OTHER_FIXED", "\"기타\"는 설명을 넣을 수 없습니다.")
            type.description = validDescription(text)
        }
        if (input.scheduleMode != null || input.preferredCount != null) {
            val mode = input.scheduleMode ?: type.scheduleMode
            val count = input.preferredCount ?: type.preferredCount
            if (mode !in ScheduleModes.ALL || count !in 1..ScheduleModes.MAX_PREFERRED) {
                throw badRequest("WORK_TYPE_SCHEDULE_MODE_INVALID", "일정 방식을 올바르게 고르세요.")
            }
            if (type.isOther && (mode != type.scheduleMode || count != type.preferredCount)) {
                throw badRequest("WORK_TYPE_OTHER_FIXED", "\"기타\"는 일정 방식을 바꿀 수 없습니다.")
            }
            type.scheduleMode = mode
            type.preferredCount = count
        }
        input.active?.let { type.active = it }
        return row(repository.saveAndFlush(type))
    }

    /** 순서 바꾸기: 그 부서의 "기타"가 아닌 유형 전부를 새 순서로 보낸다. 그사이 유형이 더해지거나 빠졌으면 409 (slices/11 2.5) */
    @Transactional
    fun reorder(loginId: String, input: WorkTypeOrderInput): List<WorkTypeAdminRow> {
        requireAdminOf(accessPolicy.actor(loginId), input.departmentId)
        val types = repository.findByDepartmentIdOrderBySortOrderAscIdAsc(input.departmentId).filter { !it.isOther }
        if (input.ids.size != input.ids.toSet().size || input.ids.toSet() != types.mapNotNull { it.id }.toSet()) {
            throw conflict("WORK_TYPE_CONFLICT", "그사이 유형이 바뀌었습니다. 새로 불러온 뒤 다시 시도하세요.")
        }
        val byId = types.associateBy { checkNotNull(it.id) }
        input.ids.forEachIndexed { index, id -> byId.getValue(id).sortOrder = index + 1 }
        repository.flush()
        return rows(ordered(input.departmentId))
    }

    /**
     * 양식 저장 (REQ-808, slices/11 3.5.1): 정의를 검사·정리해 바뀌었으면 새 판을 남기고 유형의 지금 판으로 둔다(지난 판은 지우지 않음, G-37).
     * "기타"는 양식을 넣을 수 없다(G-57). 같은 version으로 먼저 저장한 사람이 있으면 409 WORK_TYPE_CONFLICT(G-66)
     */
    @Transactional
    fun saveForm(loginId: String, id: Long, input: FormSaveInput): WorkTypeAdminRow {
        val actor = accessPolicy.actor(loginId)
        val type = repository.findById(id).orElse(null) ?: throw notFound()
        requireAdminOf(actor, type.departmentId)
        if (type.isOther) throw otherFixed()
        if (input.version != type.version) throw conflict("WORK_TYPE_CONFLICT", "다른 관리자가 먼저 고쳤습니다. 새로 불러온 뒤 다시 시도하세요.")
        val definition = FormRules.validateDefinition(input.definition, FormMode.FORM)
        // 안내문 그림은 이 유형에 올린 그림만
        definition.sections.flatMap { it.items }.filter { it.kind == QuestionKinds.GUIDE && it.imageFileId != null }.forEach { item ->
            if (imageRepository.findById(checkNotNull(item.imageFileId)).orElse(null)?.workTypeId != type.id) {
                throw ApiException(HttpStatus.BAD_REQUEST, "FORM_INVALID", "안내문 그림을 다시 올리세요.", item.key)
            }
        }
        val json = forms.write(definition)
        val current = type.currentFormVersionId?.let { versionRepository.findById(it).orElse(null) }
        if (current != null && forms.write(forms.parse(current.definition)) == json) return row(type)
        val versionNo = (versionRepository.findTopByWorkTypeIdOrderByVersionNoDesc(id)?.versionNo ?: 0) + 1
        val saved = versionRepository.saveAndFlush(WorkFormVersion(id, versionNo, json, checkNotNull(actor.account.id)))
        type.currentFormVersionId = saved.id
        return row(repository.saveAndFlush(type))
    }

    /** 안내문 그림 올리기 (REQ-808): png·jpg 5MB, 올리는 순간 그 유형에 묶는다 */
    @Transactional
    fun uploadImage(loginId: String, id: Long, file: MultipartFile): UploadedFile {
        val actor = accessPolicy.actor(loginId)
        val type = repository.findById(id).orElse(null) ?: throw notFound()
        requireAdminOf(actor, type.departmentId)
        if (type.isOther) throw otherFixed()
        val saved = fileService.store(actor, FilePurposes.FORM_IMAGE, file, linked = true, kind = FileKind.IMAGE)
        imageRepository.saveAndFlush(WorkFormImage(checkNotNull(saved.id), id))
        return UploadedFile(checkNotNull(saved.id), saved.originalName, saved.size, saved.extension)
    }

    /** 안내문 그림 보이기: 로그인한 누구나(요청 화면·결재 문서에서 양식을 보임). 안내문 그림이 아니면 404 */
    @Transactional(readOnly = true)
    fun image(fileId: Long): FormImage {
        imageRepository.findById(fileId).orElse(null) ?: throw notFound()
        val file = fileService.info(fileId) ?: throw notFound()
        return FormImage(file.extension, fileService.body(fileId) ?: throw notFound())
    }

    /** 설명: 앞뒤 빈칸을 빼고 1~500자 */
    private fun validDescription(text: String?): String {
        val value = text?.trim().orEmpty()
        if (value.isEmpty() || value.codePointCount(0, value.length) > 500) throw badRequest("WORK_TYPE_DESCRIPTION_INVALID", "업무 설명을 1~500자로 입력하세요.")
        return value
    }

    private fun otherFixed() = badRequest("WORK_TYPE_OTHER_FIXED", "\"기타\"에는 양식을 넣을 수 없습니다.")

    private fun ordered(departmentId: Long) =
        repository.findByDepartmentIdOrderBySortOrderAscIdAsc(departmentId).sortedBy { it.isOther }

    /** 그 부서의 사용 중인 부서 관리자 역할이 있고 부서가 사용 중이어야 한다(삭제된 부서의 유형은 고치지 않음) */
    private fun requireAdminOf(actor: Actor, departmentId: Long) {
        if (!actor.deptAdminOf(departmentId) || actor.tree.find(departmentId)?.active != true) throw denied()
    }

    private fun duplicate() = conflict("WORK_TYPE_DUPLICATE", "이 부서에 같은 이름의 업무 유형이 있습니다.")

    private fun row(type: WorkType) = rows(listOf(type)).single()

    /** 관리 목록 줄: 지금 판 정의는 한 번에 읽는다 */
    private fun rows(types: List<WorkType>): List<WorkTypeAdminRow> {
        val definitions = forms.definitions(types.map { it.currentFormVersionId })
        return types.map {
            WorkTypeAdminRow(checkNotNull(it.id), it.name, it.isOther, it.active, it.sortOrder, it.version, it.currentFormVersionId,
                it.currentFormVersionId?.let(definitions::get), it.description, it.scheduleMode, it.preferredCount)
        }
    }
}
