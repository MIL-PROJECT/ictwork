package kr.ictwork.directive

import kr.ictwork.admin.AccessPolicy
import kr.ictwork.admin.Actor
import kr.ictwork.admin.DepartmentTree
import kr.ictwork.admin.badRequest
import kr.ictwork.admin.conflict
import kr.ictwork.admin.denied
import kr.ictwork.admin.notFound
import kr.ictwork.common.error.ApiException
import kr.ictwork.equipment.PhotoBody
import kr.ictwork.file.FilePurposes
import kr.ictwork.file.FileRules
import kr.ictwork.file.FileService
import kr.ictwork.file.StoredFileRepository
import kr.ictwork.form.FormRules
import kr.ictwork.notification.NotificationService
import kr.ictwork.notification.NotificationTypes
import kr.ictwork.status.StatusKinds
import kr.ictwork.status.StatusScopes
import org.springframework.data.repository.findByIdOrNull
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.LocalDate
import java.time.LocalDateTime

/** 지시사항 한 건. 올린 사람은 넣지 않는다. proxy는 명의자 본인에게만 참 (slices/13 2.1·4장) */
data class DirectiveItem(
    val id: Long,
    val directiveDate: LocalDate,
    val createdAt: LocalDateTime,
    val editedAt: LocalDateTime?,
    val departmentId: Long,
    val departmentName: String,
    val positionName: String?, // 직책: 명의 부서 종류 코드(BATTALION·COMPANY·SECTION → 대대장·중대장·반장, 화면이 번역, G-146)
    val namedName: String,
    val namedRankCode: String?, // 명의자 인원 또는 계정이 사용 중지면 null (성명만)
    val content: String,
    val proxy: Boolean,
    val takenDown: Boolean, // 내림 (G-146). 내린 계정·시각은 넣지 않는다
    val canEdit: Boolean,
    val canTakeDown: Boolean, // 내리기·다시 올림
    val version: Long,
    val images: List<DirectiveImageView>, // 그림 (더한 순서, G-147). 본문은 GET /api/directives/{id}/images/{fileId}
)

/** 지시사항 그림 한 장: id는 파일 id (slices/13 15.3) */
data class DirectiveImageView(val id: Long, val name: String, val size: Int)

data class DirectiveNameOption(val accountId: Long, val name: String, val rankCode: String?, val departmentId: Long, val departmentName: String)

data class DirectiveDepartment(val id: Long, val name: String)

/** boxes: [지시사항] 탭 박스로 보일 항목 id, 박스 순서 (G-146) */
data class DirectiveListResponse(
    val items: List<DirectiveItem>,
    val nameOptions: List<DirectiveNameOption>,
    val departments: List<DirectiveDepartment>,
    val boxes: List<Long>,
)

/** imageIds: 임시로 올린 그림(POST /api/files?purpose=DIRECTIVE_IMAGE)의 id, 더한 순서 (G-147) */
data class DirectiveCreateRequest(val accountId: Long, val departmentId: Long, val content: String, val imageIds: List<Long>? = null)

/** imageIds: 고친 뒤의 그림 전체 목록(남길 지금 그림 + 새 임시 그림). 없으면(null) 그림은 그대로 (slices/13 15.3) */
data class DirectiveUpdateRequest(val content: String, val version: Long, val imageIds: List<Long>? = null)

data class DirectiveVersionRequest(val version: Long)

/** 지시사항 (REQ-601·703, slices/13 4장) */
@Service
class DirectiveService(
    private val directiveRepository: DirectiveRepository,
    private val auditRepository: DirectiveAuditRepository,
    private val imageRepository: DirectiveImageRepository,
    private val fileRepository: StoredFileRepository,
    private val fileService: FileService,
    private val access: DirectiveAccess,
    private val accessPolicy: AccessPolicy,
    private val notificationService: NotificationService,
    private val statusScopes: StatusScopes,
    private val clock: Clock,
) {
    @Transactional(readOnly = true)
    fun list(loginId: String): DirectiveListResponse {
        val actor = accessPolicy.actor(loginId)
        val directives = access.visibleDirectives(actor)
        val options = access.nameOptions(actor)
        val order = access.order(actor.tree)
        val shownDepartments = directives.map { it.departmentId }.distinct().sortedBy { order[it] ?: Int.MAX_VALUE }
        val names = names(actor, shownDepartments, options)
        val people = access.namedPeople(directives.map { it.namedAccountId })
        val images = images(directives.map { checkNotNull(it.id) })
        return DirectiveListResponse(
            directives.map { item(actor, it, names, people, images) },
            options.map { DirectiveNameOption(it.accountId, it.name, it.rankCode, it.departmentId, names.getValue(it.departmentId)) },
            shownDepartments.map { DirectiveDepartment(it, names.getValue(it)) },
            access.boxes(actor.tree, directives),
        )
    }

    /** 한 건 (알림·출처로 열 때 권한을 다시 본다, REQ-B05): 없거나 볼 수 없으면 404 */
    @Transactional(readOnly = true)
    fun get(loginId: String, id: Long): DirectiveItem {
        val actor = accessPolicy.actor(loginId)
        return single(actor, viewable(actor, id))
    }

    @Transactional
    fun create(loginId: String, request: DirectiveCreateRequest): DirectiveItem {
        val actor = accessPolicy.actor(loginId)
        if (access.nameOptions(actor).none { it.accountId == request.accountId && it.departmentId == request.departmentId }) {
            throw ApiException(HttpStatus.FORBIDDEN, "DIRECTIVE_NAME_FORBIDDEN", "이 명의로 지시사항을 올릴 수 없습니다.")
        }
        val content = normalize(request.content)
        val writer = checkNotNull(actor.account.id)
        val directive = directiveRepository.saveAndFlush(
            Directive(request.departmentId, request.accountId, writer, content, LocalDate.now(clock), LocalDateTime.now(clock)),
        )
        attach(actor, directive, request.imageIds.orEmpty(), alreadyLinked = emptySet(), from = 0)
        audit(directive, DirectiveAuditActions.CREATE, writer)
        notifyCreated(actor.tree, directive)
        return single(actor, directive)
    }

    /**
     * 볼 수 없으면 404 → 고칠 수 없으면 403 → 판이 다르면 409 → 내용 400 → 그림 400(FILE_NOT_TEMP).
     * 그림 셈 (slices/13 15.3): 지금 E, 요청 R. R이 없으면 그대로, E − R은 연결 행과 파일을 지우고, R − E는 묶어 지금 가장 큰 순서 다음부터 R 순서로 붙인다(남는 그림 순서는 그대로).
     * 내용이 같고 빼거나 더한 그림이 없으면 바꾸지 않는다(고침 표시·감사 없음). 그림만 바꿔도 고침이다 (G-147)
     */
    @Transactional
    fun update(loginId: String, id: Long, request: DirectiveUpdateRequest): DirectiveItem {
        val actor = accessPolicy.actor(loginId)
        val directive = editable(actor, id, request.version)
        val content = normalize(request.content)
        val existing = imageRepository.findByDirectiveIdOrderBySortOrderAsc(id)
        val existingIds = existing.map { it.fileId }.toSet()
        val requested = request.imageIds
        val added = requested?.filter { it !in existingIds }.orEmpty()
        val removed = if (requested == null) emptyList() else existing.filter { it.fileId !in requested }
        if (content == directive.content && added.isEmpty() && removed.isEmpty()) return single(actor, directive)
        attach(actor, directive, added, alreadyLinked = existingIds, from = (existing.maxOfOrNull { it.sortOrder } ?: -1) + 1)
        if (removed.isNotEmpty()) {
            imageRepository.deleteAll(removed)
            imageRepository.flush()
            fileService.delete(removed.map { it.fileId })
        }
        directive.content = content
        directive.editedAt = LocalDateTime.now(clock)
        directiveRepository.saveAndFlush(directive)
        audit(directive, DirectiveAuditActions.UPDATE, checkNotNull(actor.account.id))
        return single(actor, directive)
    }

    @Transactional
    fun delete(loginId: String, id: Long, version: Long) {
        val actor = accessPolicy.actor(loginId)
        val directive = editable(actor, id, version)
        // 연결 행 → 지시사항 → 그림 파일 순으로 지운다 (slices/13 15.3)
        val images = imageRepository.findByDirectiveIdOrderBySortOrderAsc(id)
        imageRepository.deleteAll(images)
        imageRepository.flush()
        directiveRepository.delete(directive)
        directiveRepository.flush()
        fileService.delete(images.map { it.fileId })
        audit(directive, DirectiveAuditActions.DELETE, checkNotNull(actor.account.id))
    }

    /** 내리기 (G-146): 볼 수 없으면 404 → 내릴 수 없으면 403 → 판이 다르거나 이미 내려져 있으면 409. 지시 일자·내용·고침 표시는 그대로, 알림 없음 */
    @Transactional
    fun takeDown(loginId: String, id: Long, version: Long): DirectiveItem = setTakenDown(loginId, id, version, down = true)

    /** 다시 올림 (G-146): 내리기와 같은 사람·같은 검사 순서. 이미 올라 있으면 409 */
    @Transactional
    fun restore(loginId: String, id: Long, version: Long): DirectiveItem = setTakenDown(loginId, id, version, down = false)

    private fun setTakenDown(loginId: String, id: Long, version: Long, down: Boolean): DirectiveItem {
        val actor = accessPolicy.actor(loginId)
        val directive = viewable(actor, id)
        if (!access.canTakeDown(actor, directive)) throw denied()
        if (directive.version != version || directive.takenDown == down) throw stale()
        val accountId = checkNotNull(actor.account.id)
        directive.takenDownAt = if (down) LocalDateTime.now(clock) else null
        directive.takenDownByAccountId = if (down) accountId else null
        directiveRepository.saveAndFlush(directive)
        audit(directive, if (down) DirectiveAuditActions.TAKE_DOWN else DirectiveAuditActions.RESTORE, accountId)
        return single(actor, directive)
    }

    /** 그림 본문 (G-147): 그 지시사항을 볼 수 없거나 그 지시사항의 그림이 아니면 404. 종류는 머리 바이트로 정한다 */
    @Transactional(readOnly = true)
    fun image(loginId: String, id: Long, fileId: Long): PhotoBody {
        val actor = accessPolicy.actor(loginId)
        viewable(actor, id)
        imageRepository.findByFileId(fileId)?.takeIf { it.directiveId == id } ?: throw notFound()
        val data = fileService.body(fileId) ?: throw notFound()
        return PhotoBody(if (FileRules.isPng(data)) "image/png" else "image/jpeg", data)
    }

    /** 내가 올린 임시 그림을 묶고 from부터 순서대로 연결 행을 만든다. 남이 올렸거나 이미 묶인 파일이면 400 FILE_NOT_TEMP */
    private fun attach(actor: Actor, directive: Directive, fileIds: List<Long>, alreadyLinked: Set<Long>, from: Int) {
        if (fileIds.isEmpty()) return
        fileService.link(actor, fileIds, FilePurposes.DIRECTIVE_IMAGE, alreadyLinked)
        val directiveId = checkNotNull(directive.id)
        imageRepository.saveAll(fileIds.mapIndexed { i, fileId -> DirectiveImage(fileId, directiveId, from + i) })
        imageRepository.flush()
    }

    /** 지시사항마다 그림 (더한 순서). 본문은 읽지 않는다 */
    private fun images(directiveIds: List<Long>): Map<Long, List<DirectiveImageView>> {
        if (directiveIds.isEmpty()) return emptyMap()
        val rows = imageRepository.findByDirectiveIdInOrderByDirectiveIdAscSortOrderAsc(directiveIds)
        if (rows.isEmpty()) return emptyMap()
        val files = fileRepository.findAllById(rows.map { it.fileId }).associateBy { checkNotNull(it.id) }
        return rows.groupBy({ it.directiveId }) { row -> files.getValue(row.fileId).let { DirectiveImageView(row.fileId, it.originalName, it.size) } }
    }

    private fun viewable(actor: Actor, id: Long): Directive =
        directiveRepository.findByIdOrNull(id)?.takeIf { access.canView(actor, it) } ?: throw notFound()

    private fun editable(actor: Actor, id: Long, version: Long): Directive {
        val directive = viewable(actor, id)
        if (!access.canEdit(actor, directive)) throw denied()
        if (directive.version != version) throw stale()
        return directive
    }

    private fun stale() = conflict("DIRECTIVE_CONFLICT", "다른 사람이 먼저 고쳤거나 지웠습니다. 목록을 새로 불러온 뒤 다시 하세요.")

    /** 한 건의 부서 이름도 목록과 같은 모음(볼 수 있는 지시사항의 명의 부서 + 명의로 고를 수 있는 부서)으로 구별한다 */
    private fun single(actor: Actor, directive: Directive): DirectiveItem {
        val names = names(actor, access.visibleDirectiveDepartments(actor) + directive.departmentId, access.nameOptions(actor))
        return item(actor, directive, names, access.namedPeople(listOf(directive.namedAccountId)), images(listOf(checkNotNull(directive.id))))
    }

    private fun names(actor: Actor, shown: Collection<Long>, options: List<NameChoice>): Map<Long, String> =
        statusScopes.displayNames(actor.tree, (shown + options.map { it.departmentId }).toSet())

    private fun item(actor: Actor, d: Directive, names: Map<Long, String>, people: Map<Long, NamedPerson>, images: Map<Long, List<DirectiveImageView>>): DirectiveItem {
        val named = people[d.namedAccountId]
        return DirectiveItem(
            checkNotNull(d.id), d.directiveDate, d.createdAt, d.editedAt, d.departmentId, names[d.departmentId] ?: "",
            actor.tree.find(d.departmentId)?.type, named?.name ?: "", named?.rankCode, d.content,
            proxy = d.writtenByAccountId != d.namedAccountId && d.namedAccountId == actor.account.id,
            takenDown = d.takenDown, canEdit = access.canEdit(actor, d), canTakeDown = access.canTakeDown(actor, d), version = d.version,
            images = images[d.id].orEmpty(),
        )
    }

    private fun audit(directive: Directive, action: String, accountId: Long) {
        auditRepository.save(DirectiveAudit(checkNotNull(directive.id), action, accountId, LocalDateTime.now(clock)))
    }

    /**
     * 새로 올릴 때만 '부대 지시사항' 알림 (4.1): ① 명의 부서·하위 소속 ② 명의 부서나 그 하위를 대상으로 한 부서장(겸직, DEC-111) ③ 명의자.
     * 올린 사람은 빼고, 대신 올렸으면 명의자에게만 따로 "시스템 관리자가 대신 올린" 문구. 끈 사람·사용 중지 계정은 notify가 뺀다
     */
    private fun notifyCreated(tree: DepartmentTree, d: Directive) {
        val within = tree.selfAndDescendants(d.departmentId)
        val memberAccounts = directiveRepository.findActiveMemberAccountIds(within)
        val heads = directiveRepository.findActiveRoleAccountIds(StatusKinds.HEAD_ROLES, within)
        val everyone = (memberAccounts + heads + d.namedAccountId).toSet() - d.writtenByAccountId
        val path = statusScopes.fullName(tree, d.departmentId)
        val preview = preview(d.content)
        val link = "/directives?id=${d.id}"
        val proxy = d.writtenByAccountId != d.namedAccountId
        notificationService.notify(if (proxy) everyone - d.namedAccountId else everyone, NotificationTypes.DIRECTIVE, "[$path] 새 지시사항: $preview", link)
        if (proxy) notificationService.notify(listOf(d.namedAccountId), NotificationTypes.DIRECTIVE, "[$path] 시스템 관리자가 대신 올린 지시사항: $preview", link)
    }

    companion object {
        const val MAX_LENGTH = 1000

        /** 줄바꿈을 \n으로 맞추고 앞뒤 빈칸(줄바꿈 포함)을 지운 뒤 1~1,000자(코드 포인트, 줄바꿈 1자) (G-138·G-144) */
        fun normalize(raw: String): String {
            val content = raw.replace("\r\n", "\n").replace('\r', '\n').trim()
            if (content.isEmpty() || FormRules.length(content) > MAX_LENGTH) {
                throw badRequest("DIRECTIVE_CONTENT_INVALID", "내용은 앞뒤 빈칸을 뺀 1~1,000자로 입력하세요.")
            }
            return content
        }

        /** 알림 미리보기: 줄바꿈은 빈칸 하나, 앞 40자, 넘으면 "…" */
        fun preview(content: String): String {
            val flat = content.replace('\n', ' ')
            return if (FormRules.length(flat) > 40) FormRules.clip(flat, 40) + "…" else flat
        }
    }
}
