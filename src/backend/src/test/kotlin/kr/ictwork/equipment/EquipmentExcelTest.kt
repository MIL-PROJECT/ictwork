package kr.ictwork.equipment

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import kr.ictwork.account.Account
import kr.ictwork.account.AccountRepository
import kr.ictwork.attribute.AttributeDefRepository
import kr.ictwork.attribute.AttributeTargets
import kr.ictwork.org.Department
import kr.ictwork.org.DepartmentRepository
import kr.ictwork.org.Personnel
import kr.ictwork.org.PersonnelRepository
import kr.ictwork.org.RoleAssignment
import kr.ictwork.org.RoleAssignmentRepository
import kr.ictwork.signedIn
import kr.ictwork.work.FixedClockConfig
import kr.ictwork.work.MutableClock
import kr.ictwork.work.WorkItemService
import kr.ictwork.worktype.WorkTypeRepository
import kr.ictwork.worktype.otherTargets
import org.assertj.core.api.Assertions.assertThat
import org.dhatim.fastexcel.Workbook
import org.dhatim.fastexcel.reader.Cell
import org.dhatim.fastexcel.reader.CellType
import org.dhatim.fastexcel.reader.ReadableWorkbook
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.MediaType
import org.springframework.mock.web.MockMultipartFile
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActions
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.transaction.annotation.Transactional
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * 장비 엑셀 내보내기·일괄 등록 (REQ-516·517, slices/10_장비항목.md 7장·11.1). 시험 이름은 명세 검증 기준 문장을 줄인 것.
 * 조직: 장비대대(1중대: 1반·2반, 2중대: 1반). 파일은 시험 안에서 fastexcel로 만들거나 API로 내려받아 고친다.
 * 시리얼넘버·IP·MAC은 가상 값 (NFR-002)
 */
@SpringBootTest(classes = [kr.ictwork.IctworkApplication::class, FixedClockConfig::class])
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class EquipmentExcelTest(
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val objectMapper: ObjectMapper,
    @Autowired private val accountRepository: AccountRepository,
    @Autowired private val departmentRepository: DepartmentRepository,
    @Autowired private val personnelRepository: PersonnelRepository,
    @Autowired private val equipmentRepository: EquipmentRepository,
    @Autowired private val roleAssignmentRepository: RoleAssignmentRepository,
    @Autowired private val workTypeRepository: WorkTypeRepository,
    @Autowired private val workItemService: WorkItemService,
    @Autowired private val attributeDefRepository: AttributeDefRepository,
    @Autowired private val passwordEncoder: PasswordEncoder,
    @Autowired private val clock: MutableClock,
) {
    private lateinit var b1: Department
    private lateinit var c1: Department
    private lateinit var s1: Department
    private lateinit var s2: Department
    private lateinit var c2: Department
    private lateinit var s3: Department
    private lateinit var r1: Equipment
    private lateinit var r2: Equipment

    private val s1Path = "장비대대 > 1중대 > 1반"
    private val s2Path = "장비대대 > 1중대 > 2반"
    private val s3Path = "장비대대 > 2중대 > 1반"

    @BeforeEach
    fun createOrganization() {
        clock.now = LocalDateTime.of(2026, 10, 17, 9, 0)
        b1 = dept("장비대대", "BATTALION", null)
        c1 = dept("1중대", "COMPANY", b1)
        s1 = dept("1반", "SECTION", c1)
        s2 = dept("2반", "SECTION", c1)
        c2 = dept("2중대", "COMPANY", b1)
        s3 = dept("1반", "SECTION", c2)

        user("x-b1adm", b1, "MEMBER", "DEPT_ADMIN" to b1)
        user("x-c1adm", c1, "MEMBER", "DEPT_ADMIN" to c1)
        user("x-c1cmdr", c1, "CO_CMDR", "CO_CMDR" to c1)
        user("x-c1m", c1, "MEMBER")
        user("x-s1a", s1, "MEMBER")
        user("x-s1b", s1, "MEMBER")
        user("x-s2a", s2, "MEMBER")
        user("x-c2m", c2, "MEMBER")

        r1 = equipment("무전기", s1, "XL-0001")
        r2 = equipment("무전기", s2, "XL-0002")
    }

    private fun dept(name: String, type: String, parent: Department?) = departmentRepository.saveAndFlush(Department(name, type, parent?.id))

    private fun user(loginId: String, department: Department, position: String, vararg roles: Pair<String, Department?>): Account {
        val personnel = personnelRepository.save(Personnel("사람-$loginId", department.id!!, position, "PVT"))
        val account = accountRepository.saveAndFlush(Account(loginId, passwordEncoder.encode("test-password-1"), "사람-$loginId", personnelId = personnel.id!!))
        roles.forEach { (code, target) -> roleAssignmentRepository.save(RoleAssignment(account.id!!, code, target?.id)) }
        roleAssignmentRepository.flush()
        return account
    }

    private fun equipment(name: String, department: Department, serial: String, model: String = "PRC-X1", setup: Equipment.() -> Unit = {}) =
        equipmentRepository.saveAndFlush(Equipment(name, department.id!!, model, serial).apply(setup))

    private fun reload(e: Equipment): Equipment = equipmentRepository.findById(e.id!!).get()

    private fun accountIdOf(loginId: String) = accountRepository.findByLoginId(loginId)!!.id!!

    private fun json(result: ResultActions): JsonNode = objectMapper.readTree(result.andReturn().response.getContentAsString(Charsets.UTF_8))

    private fun send(method: String, loginId: String, path: String, body: Any? = null): ResultActions {
        val builder = if (method == "PUT") put(path) else post(path)
        return mockMvc.perform(
            builder.with(signedIn(loginId, accountRepository)).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(body ?: emptyMap<String, Any>())),
        )
    }

    // ── 파일 ──

    private fun export(loginId: String, ids: List<Long?>, kind: String = "AFFILIATED", departmentIds: List<Long?> = emptyList(), carryOut: List<String> = emptyList()): ByteArray =
        send("POST", loginId, "/api/equipment/export", mapOf("ids" to ids, "kind" to kind, "departmentIds" to departmentIds, "carryOut" to carryOut))
            .andExpect(status().isOk).andReturn().response.contentAsByteArray

    private fun upload(loginId: String, bytes: ByteArray): ResultActions = mockMvc.perform(
        multipart("/api/equipment/import")
            .file(MockMultipartFile("file", "equipment.xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", bytes))
            .with(signedIn(loginId, accountRepository)).with(csrf()),
    )

    /** 일괄 등록 결과 {created, updated, unchanged, skipped} */
    private fun imported(loginId: String, bytes: ByteArray): JsonNode = json(upload(loginId, bytes).andExpect(status().isOk))

    private fun rowsError(loginId: String, bytes: ByteArray): JsonNode =
        json(upload(loginId, bytes).andExpect(status().isBadRequest).andExpect(jsonPath("$.code").value("EXCEL_INVALID")))["rows"]

    private fun text(c: Cell): String? = when (c.type) {
        CellType.EMPTY -> null
        CellType.NUMBER -> BigDecimal(c.rawValue).toPlainString()
        CellType.STRING -> c.asString()
        else -> c.rawValue
    }?.ifEmpty { null }

    /** 시트의 줄(머리글 포함 여부 선택). 빈 줄은 뺀다 */
    private fun sheet(bytes: ByteArray, name: String, width: Int, header: Boolean = false): MutableList<MutableList<Any?>> =
        ReadableWorkbook(ByteArrayInputStream(bytes)).use { wb ->
            wb.findSheet(name).orElseThrow().read()
                .filter { header || it.rowNum > 1 }
                .map { r -> (0 until width).map<Int, Any?> { i -> r.getOptionalCell(i).orElse(null)?.let(::text) }.toMutableList() }
                .filter { row -> row.any { it != null } }.toMutableList()
        }

    private fun mainRows(bytes: ByteArray) = sheet(bytes, "장비", EquipmentExcel.MAIN_COLUMNS.size)

    private fun swRows(bytes: ByteArray) = sheet(bytes, "SW", EquipmentExcel.SW_COLUMNS.size)

    private fun netRows(bytes: ByteArray) = sheet(bytes, "네트워크", EquipmentExcel.NET_COLUMNS.size)

    private fun col(name: String) = EquipmentExcel.MAIN_COLUMNS.indexOf(name).also { check(it >= 0) { name } }

    private fun MutableList<MutableList<Any?>>.rowOf(e: Equipment) = first { it[0] == e.id.toString() }

    /** 양식대로 파일을 만든다(빈 칸은 null). 숫자는 숫자 칸, 날짜는 날짜 칸, 나머지는 글 칸 */
    private fun xlsx(
        main: List<List<Any?>>,
        sw: List<List<Any?>> = emptyList(),
        net: List<List<Any?>> = emptyList(),
        mainHeader: List<String> = EquipmentExcel.MAIN_COLUMNS,
    ): ByteArray {
        val out = ByteArrayOutputStream()
        val wb = Workbook(out, "test", "1.0")
        fun write(name: String, header: List<String>, rows: List<List<Any?>>) {
            val ws = wb.newWorksheet(name)
            header.forEachIndexed { i, h -> ws.value(0, i, h) }
            rows.forEachIndexed { r, row ->
                row.forEachIndexed { c, v ->
                    when (v) {
                        null -> Unit
                        is Number -> ws.value(r + 1, c, v)
                        is LocalDate -> ws.value(r + 1, c, v)
                        else -> ws.value(r + 1, c, v.toString())
                    }
                }
            }
        }
        write("장비", mainHeader, main)
        write("SW", EquipmentExcel.SW_COLUMNS, sw)
        write("네트워크", EquipmentExcel.NET_COLUMNS, net)
        wb.finish()
        return out.toByteArray()
    }

    /** 양식의 장비 줄: 칸 이름 → 값 */
    private fun mainRow(vararg values: Pair<String, Any?>): List<Any?> {
        val m = values.toMap()
        return EquipmentExcel.MAIN_COLUMNS.map { m[it] }
    }

    private fun newRow(path: String, name: String, model: String, serial: String, vararg extra: Pair<String, Any?>) =
        mainRow("관리부서" to path, "장비명" to name, "모델명" to model, "시리얼넘버" to serial, "상태" to "미사용", *extra)

    // ── 장비 화면 저장·업무 ──

    private fun edit(loginId: String, id: Long?, changes: Map<String, Any?>): ResultActions {
        val view = json(mockMvc.perform(get("/api/equipment/$id").with(signedIn(loginId, accountRepository))).andExpect(status().isOk))
        val keys = listOf(
            "departmentId", "storageLocationId", "systemName", "name", "modelName", "serialNumber", "commonName", "memo", "hwVersion", "hwInstalledOn",
            "osType", "osVersion", "osInstalledOn", "networkKind", "introducedOn", "introProject", "maintenanceProject", "status", "software", "networks", "editedAt",
        )
        val body = keys.associateWith { k -> view[k]?.takeUnless { it.isNull }?.let { objectMapper.treeToValue(it, Any::class.java) } } + changes
        return send("PUT", loginId, "/api/equipment/$id", body)
    }

    /** 다른 부서(2중대)가 요청을 보내고 받는 부서 인원이 바로 접수·계획한다 */
    private fun plannedItem(receiver: String, target: Department, vararg equipment: Equipment): Long {
        val requester = "x-c2m"
        val body = mapOf(
            "title" to "장비 지원 요청", "place" to "시험 장소", "content" to "시험", "assigneePersonnelIds" to listOf(accountRepository.findByLoginId(requester)!!.personnelId), "workStartDate" to "2026-10-20",
            "targets" to workTypeRepository.otherTargets(target.id), "steps" to listOf(mapOf("kind" to "APPROVE", "accountId" to accountIdOf(requester))),
            "viewers" to mapOf("personnelIds" to listOf(accountRepository.findByLoginId(requester)!!.personnelId)),
        )
        val requestId = json(send("POST", requester, "/api/work/requests", body).andExpect(status().isCreated))["id"].asLong()
        val item = json(mockMvc.perform(get("/api/work/requests/$requestId").with(signedIn(requester, accountRepository))))["items"][0]["id"].asLong()
        send("POST", receiver, "/api/work/items/$item/receive").andExpect(status().isNoContent)
        val plan = mapOf("slots" to listOf(mapOf("startDate" to "2026-10-20", "endDate" to "2026-10-21", "personnelIds" to emptyList<Long>(), "equipmentIds" to equipment.map { it.id })))
        send("POST", receiver, "/api/work/items/$item/plan", mapOf("plan" to plan)).andExpect(status().isNoContent)
        return item
    }

    // ── REQ-516 내보내기 ──

    @Test
    fun `REQ-516 체계명으로 걸러 내려받은 파일에 그 체계명의 장비만 있고 화면에서 끈 열과 장비 번호도 들어 있다`() {
        r1.systemName = "가체계"
        r1.osVersion = "0010" // 앞의 0이 살아 있어야 한다(글 칸)
        r1.networks.add(EquipmentNetwork("관리용", "192.0.2.51", "02-00-00-00-00-51", "020000000051", "020000000051"))
        r1.software.add(EquipmentSoftware("오피스", "1.10", LocalDate.of(2025, 3, 4)))
        val other = equipment("노트북", s1, "XL-0003") { systemName = "나체계" }
        equipmentRepository.flush()
        // 화면이 "가체계"로 걸러 본 줄의 번호만 보낸다
        val file = export("x-s1a", listOf(r1.id))
        assertThat(sheet(file, "장비", EquipmentExcel.MAIN_COLUMNS.size, header = true)[0]).containsExactlyElementsOf(EquipmentExcel.MAIN_COLUMNS)
        val rows = mainRows(file)
        assertThat(rows).hasSize(1)
        val row = rows[0]
        assertThat(row[col("장비 번호")]).isEqualTo(r1.id.toString())
        assertThat(row[col("관리부서")]).isEqualTo(s1Path)
        assertThat(row[col("체계명")]).isEqualTo("가체계")
        assertThat(row[col("OS 설치버전")]).isEqualTo("0010")
        assertThat(row[col("보유량")]).isEqualTo("1")
        assertThat(row[col("상태")]).isEqualTo("미사용")
        assertThat(row[col("반출 여부")]).isEqualTo("반출 아님")
        assertThat(row[col("고친 시각")] as String).matches("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}")
        assertThat(rows.map { it[0] }).doesNotContain(other.id.toString())
        assertThat(swRows(file)).containsExactly(mutableListOf(r1.id.toString(), "PRC-X1", "XL-0001", "오피스", "1.10", "2025-03-04"))
        assertThat(netRows(file)).containsExactly(mutableListOf(r1.id.toString(), "PRC-X1", "XL-0001", "관리용", "192.0.2.51", "02-00-00-00-00-51"))
        assertThat(sheet(file, "안내", 1, header = true)).isNotEmpty()
    }

    @Test
    fun `REQ-516 소속 범위 밖 장비는 들어 있지 않다`() {
        val x3 = equipment("교환기", s3, "XL-0010")
        val file = export("x-s1a", listOf(r1.id, r2.id, x3.id))
        assertThat(mainRows(file).map { it[0] }).containsExactly(r1.id.toString())
        // 장비관리 목록(관리 범위)에서 내려받으면 관리 범위만
        assertThat(mainRows(export("x-c1adm", listOf(r1.id, r2.id, x3.id), kind = "MANAGED")).map { it[0] })
            .containsExactlyInAnyOrder(r1.id.toString(), r2.id.toString())
    }

    @Test
    fun `REQ-516 업무 현황에서 연 목록은 그 조건 안의 장비만 내려받는다`() {
        val out = equipment("발전기", s1, "XL-0011") { carryOut = "OUT"; currentPlace = "훈련장" }
        val ids = listOf(r1.id, r2.id, out.id)
        assertThat(mainRows(export("x-c1cmdr", ids, kind = "STATUS", departmentIds = listOf(c1.id), carryOut = listOf("OUT"))).map { it[0] })
            .containsExactly(out.id.toString())
        assertThat(mainRows(export("x-c1cmdr", ids)).map { it[0] }).hasSize(3) // 같은 장비라도 장비 목록에서는 모두
    }

    @Test
    fun `REQ-516 볼 수 없는 업무의 제목은 현재 위치 칸에서 가린다`() {
        plannedItem("x-c1m", c1, r1) // 1중대 업무에 1반 장비: 1반 인원은 그 업무를 볼 수 없다
        clock.now = LocalDateTime.of(2026, 10, 20, 9, 0)
        workItemService.refreshEquipment(r1.id!!)
        val hidden = mainRows(export("x-s1a", listOf(r1.id))).single()
        assertThat(hidden[col("반출 여부")]).isEqualTo("반출")
        assertThat(hidden[col("현재 위치")] as String?).isEqualTo("장비대대 > 2중대")
        val shown = mainRows(export("x-c1m", listOf(r1.id))).single()
        assertThat(shown[col("현재 위치")]).isEqualTo("장비대대 > 2중대 장비 지원 요청")
    }

    // ── REQ-517 일괄 등록 ──

    @Test
    fun `REQ-517 일괄 등록 양식은 목록 파일과 같은 시트·열이고 줄은 비어 있다`() {
        val template = mockMvc.perform(get("/api/equipment/import-template").with(signedIn("x-s1a", accountRepository)))
            .andExpect(status().isOk).andReturn().response.contentAsByteArray
        assertThat(sheet(template, "장비", EquipmentExcel.MAIN_COLUMNS.size, header = true)).containsExactly(EquipmentExcel.MAIN_COLUMNS.toMutableList())
        assertThat(sheet(template, "SW", EquipmentExcel.SW_COLUMNS.size, header = true)).containsExactly(EquipmentExcel.SW_COLUMNS.toMutableList())
        assertThat(sheet(template, "네트워크", EquipmentExcel.NET_COLUMNS.size, header = true)).containsExactly(EquipmentExcel.NET_COLUMNS.toMutableList())
        // 빈 양식을 그대로 올리면 바뀌는 것 없이 통과
        val result = imported("x-s1a", template)
        assertThat(listOf(result["created"], result["updated"], result["unchanged"], result["skipped"]).map { it.asInt() }).containsOnly(0)
    }

    @Test
    fun `REQ-517 새 장비 3줄을 올리면 3대가 등록된다`() {
        val file = xlsx(
            main = listOf(
                newRow(s1Path, "노트북 1", "NB-X1", "XN-0001", "사용전산망" to "국방망", "장비도입연도" to "2025-01-02", "HW 설치연도" to LocalDate.of(2024, 5, 6)),
                newRow(s1Path, "노트북 2", "NB-X1", "XN-0002", "상태" to "사용중"),
                newRow(s1Path, "노트북 3", "NB-X1", "XN-0003", "OS 설치버전" to 10.10),
            ),
            sw = listOf(listOf(null, "NB-X1", "XN-0001", "오피스", "2021", "2025-01-03")),
            net = listOf(listOf(null, "nb-x1", " xn-0001 ", "관리용", "192.0.2.21", "02-00-00-00-00-21")),
        )
        val result = imported("x-s1a", file)
        assertThat(result["created"].asInt()).isEqualTo(3)
        assertThat(result["updated"].asInt()).isZero()
        val first = equipmentRepository.findActiveBySerial("NB-X1", "XN-0001").single()
        assertThat(first.departmentId).isEqualTo(s1.id)
        assertThat(first.networkKind).isEqualTo("DEFENSE")
        assertThat(first.introducedOn).isEqualTo(LocalDate.of(2025, 1, 2))
        assertThat(first.hwInstalledOn).isEqualTo(LocalDate.of(2024, 5, 6)) // 날짜 칸도 받는다
        assertThat(first.carryOut).isEqualTo("NONE")
        assertThat(first.software.map { it.name }).containsExactly("오피스")
        assertThat(first.networks.single().macKey).isEqualTo("020000000021")
        assertThat(equipmentRepository.findActiveBySerial("NB-X1", "XN-0002").single().status).isEqualTo("IN_USE")
        assertThat(equipmentRepository.findActiveBySerial("NB-X1", "XN-0003").single().osVersion).isEqualTo("10.1") // 숫자 칸은 숫자 그대로의 글
    }

    @Test
    fun `REQ-517 장비 번호가 있는 줄과 번호 없이 이미 있는 모델명·시리얼넘버의 줄은 그 장비를 고친다`() {
        val other = equipment("노트북", s1, "XL-0020", model = "NB-X1")
        val file = export("x-s1a", listOf(r1.id))
        val rows = mainRows(file)
        rows.rowOf(r1)[col("비고")] = "번호로 고침"
        rows.add(newRow(s1Path, "노트북(고침)", "nb-x1", " xl-0020 ", "비고" to "시리얼로 고침").toMutableList())
        val result = imported("x-s1a", xlsx(rows))
        assertThat(result["updated"].asInt()).isEqualTo(2)
        assertThat(result["created"].asInt()).isZero()
        assertThat(reload(r1).memo).isEqualTo("번호로 고침")
        assertThat(reload(other).memo).isEqualTo("시리얼로 고침")
        assertThat(reload(other).name).isEqualTo("노트북(고침)")
        assertThat(reload(other).serialNumber).isEqualTo("xl-0020") // 파일 값으로 바뀜(같은 장비로 본 비교는 대소문자 무시)
    }

    @Test
    fun `REQ-517 내려받은 목록 파일을 고치지 않고 다시 올리면 바뀌는 것 없이 통과한다(반출 예정·불용·삭제된 부서 장비가 있어도)`() {
        plannedItem("x-s1a", s1, r1)
        assertThat(reload(r1).carryOut).isEqualTo("RESERVED")
        val disposed = equipment("고장 노트북", s2, "XL-0030") { status = "DISPOSED" }
        val closed = dept("9반", "SECTION", c1)
        val orphan = equipment("9반 장비", closed, "XL-0031") { status = "DISPOSED" }
        closed.active = false
        departmentRepository.saveAndFlush(closed)
        r2.networks.add(EquipmentNetwork("관리용", "198.51.100.2", "02-00-00-00-00-02", "020000000002", "020000000002"))
        r2.software.add(EquipmentSoftware("오피스", "2021", null))
        equipmentRepository.flush()
        val ids = listOf(r1.id, r2.id, disposed.id, orphan.id)
        val editedBefore = ids.associateWith { equipmentRepository.findById(it!!).get().editedAt }

        val file = export("x-c1adm", ids, kind = "MANAGED")
        assertThat(mainRows(file).rowOf(orphan)[col("관리부서")]).isEqualTo("장비대대 > 1중대 > 9반 (삭제됨)")
        val result = imported("x-c1adm", file)
        assertThat(result["unchanged"].asInt()).isEqualTo(4)
        assertThat(listOf(result["created"], result["updated"], result["skipped"]).map { it.asInt() }).containsOnly(0)
        ids.forEach { assertThat(equipmentRepository.findById(it!!).get().editedAt).isEqualTo(editedBefore[it]) }
        assertThat(reload(r1).carryOut).isEqualTo("RESERVED")
        // 부서원이 내려받은 소속 범위 목록도 같다
        assertThat(imported("x-s1a", export("x-s1a", listOf(r1.id)))["unchanged"].asInt()).isEqualTo(1)
    }

    @Test
    fun `REQ-517 상태 칸을 불용으로 바꾼 줄은 불용 처리되고 불용을 사용중으로 바꾼 줄은 되돌려진다`() {
        val disposed = equipment("노트북", s1, "XL-0040") { status = "DISPOSED" }
        val rows = mainRows(export("x-s1a", listOf(r1.id, disposed.id)))
        rows.rowOf(r1)[col("상태")] = "불용"
        rows.rowOf(disposed)[col("상태")] = "사용중"
        assertThat(imported("x-s1a", xlsx(rows))["updated"].asInt()).isEqualTo(2)
        assertThat(reload(r1).status).isEqualTo("DISPOSED")
        assertThat(reload(disposed).status).isEqualTo("IN_USE")
    }

    @Test
    fun `REQ-517 반출 여부 칸을 바꿔도 반영되지 않는다`() {
        val rows = mainRows(export("x-s1a", listOf(r1.id)))
        rows.rowOf(r1)[col("반출 여부")] = "반출"
        rows.rowOf(r1)[col("현재 위치")] = "훈련장"
        rows.rowOf(r1)[col("반출 수정사유")] = "엑셀로 반출"
        rows.rowOf(r1)[col("보유량")] = "5"
        val result = imported("x-s1a", xlsx(rows))
        assertThat(result["unchanged"].asInt()).isEqualTo(1)
        assertThat(result["updated"].asInt()).isZero()
        val after = reload(r1)
        assertThat(after.carryOut).isEqualTo("NONE")
        assertThat(after.currentPlace).isNull()
        assertThat(after.carryOutReason).isNull()
    }


    @Test
    fun `REQ-517 내려받은 뒤 부서 이름이 바뀌어도 손대지 않은 줄은 바뀌지 않음이고 고친 줄은 관리부서 칸이 틀린 줄이다`() {
        val bytes = export("x-s1a", listOf(r1.id))
        s1.name = "1반(개편)"
        departmentRepository.saveAndFlush(s1)
        // 손대지 않은 파일: 옛 경로 "장비대대 > 1중대 > 1반"을 찾지 못해도 장비가 그대로라 통과
        assertThat(imported("x-s1a", bytes)["unchanged"].asInt()).isEqualTo(1)
        // 비고를 고친 줄은 옛 경로 그대로면 관리부서 칸이 틀린 줄
        val rows = mainRows(bytes)
        rows.rowOf(r1)[col("비고")] = "고친 비고"
        val errors = rowsError("x-s1a", xlsx(rows))
        assertThat(errors.map { "${it["row"].asInt()}/${it["column"].asText()}/${it["code"].asText()}" }).containsExactly("2/관리부서/DEPARTMENT_INVALID")
        assertThat(reload(r1).memo).isNull()
    }

    @Test
    fun `REQ-517 3줄 중 1줄의 모델명이 비어 있으면 아무것도 등록되지 않고 그 줄과 칸이 안내된다`() {
        val before = equipmentRepository.count()
        val rows = rowsError(
            "x-s1a",
            xlsx(listOf(newRow(s1Path, "노트북 1", "NB-X1", "XN-0101"), newRow(s1Path, "노트북 2", "NB-X1", "XN-0102"), newRow(s1Path, "노트북 3", " ", "XN-0103"))),
        )
        assertThat(rows).hasSize(1)
        assertThat(rows[0]["sheet"].asText()).isEqualTo("장비")
        assertThat(rows[0]["row"].asInt()).isEqualTo(4) // 엑셀에 보이는 줄 번호(머리글이 1)
        assertThat(rows[0]["column"].asText()).isEqualTo("모델명")
        assertThat(rows[0]["code"].asText()).isEqualTo("EQUIPMENT_FIELD_INVALID")
        assertThat(rows[0]["message"].asText()).isNotBlank()
        assertThat(equipmentRepository.count()).isEqualTo(before)
    }

    @Test
    fun `REQ-501 전각으로 적은 모델명·시리얼넘버도 같은 값으로 본다 - 기존 장비를 고치고 SW 줄과 짝지으며 파일 안 겹침도 찾는다`() {
        // 전각 "ＰＲＣ－Ｘ１"·"ｘｌ－０００１" = 기존 r1(PRC-X1·XL-0001). SW 줄은 반각으로 적어도 그 줄과 짝이다
        val result = imported(
            "x-s1a",
            xlsx(
                main = listOf(newRow(s1Path, "무전기(전각)", "ＰＲＣ－Ｘ１", "ｘｌ－０００１", "비고" to "전각으로 고침")),
                sw = listOf(listOf(null, "PRC-X1", "XL-0001", "오피스", "1.0", null)),
            ),
        )
        assertThat(result["updated"].asInt()).isEqualTo(1)
        assertThat(result["created"].asInt()).isZero()
        assertThat(reload(r1).memo).isEqualTo("전각으로 고침")
        assertThat(reload(r1).software.map { it.name }).containsExactly("오피스")
        // 새 장비 두 줄이 반각·전각으로 같은 시리얼이면 뒤 줄이 겹침
        val before = equipmentRepository.count()
        val rows = rowsError("x-s1a", xlsx(listOf(newRow(s1Path, "노트북 1", "NB-X1", "XN-0301"), newRow(s1Path, "노트북 2", "ＮＢ－Ｘ１", "ＸＮ－０３０１"))))
        assertThat(rows.map { "${it["row"].asInt()}/${it["column"].asText()}/${it["code"].asText()}" }).containsExactly("3/시리얼넘버/EQUIPMENT_SERIAL_DUPLICATE")
        assertThat(equipmentRepository.count()).isEqualTo(before)
    }

    @Test
    fun `REQ-517 불용이 아닌 같은 시리얼넘버가 두 줄이면 아무것도 등록되지 않고 그 줄과 칸이 안내된다`() {
        val before = equipmentRepository.count()
        val rows = rowsError(
            "x-s1a",
            xlsx(listOf(newRow(s1Path, "노트북 1", "NB-X1", "XN-0111"), newRow(s1Path, "노트북 2", "NB-X1", "XN-0112"), newRow(s1Path, "노트북 3", "nb-x1", " xn-0111 "))),
        )
        assertThat(rows.map { "${it["row"].asInt()}/${it["column"].asText()}/${it["code"].asText()}" }).containsExactly("4/시리얼넘버/EQUIPMENT_SERIAL_DUPLICATE")
        // 이미 있는(불용이 아닌) 다른 장비와 겹치게 고친 줄도 그 줄과 칸. 범위 밖(2반) 장비면 장비명·시리얼넘버는 알리지 않는다
        val mine = mainRows(export("x-s1a", listOf(r1.id)))
        mine.rowOf(r1)[col("시리얼넘버")] = "xl-0002"
        val dup = rowsError("x-s1a", xlsx(mine))
        assertThat(dup.map { "${it["row"].asInt()}/${it["column"].asText()}/${it["code"].asText()}" }).containsExactly("2/시리얼넘버/EQUIPMENT_SERIAL_DUPLICATE")
        assertThat(dup[0]["message"].asText()).doesNotContain("XL-0002")
        assertThat(equipmentRepository.count()).isEqualTo(before)
        assertThat(reload(r1).serialNumber).isEqualTo("XL-0001")
    }

    @Test
    fun `틀린 SW·네트워크 줄과 짝이 없는 줄은 그 시트의 줄과 칸으로 안내되고 양식이 다른 파일은 거부된다`() {
        val rows = rowsError(
            "x-s1a",
            xlsx(
                main = listOf(newRow(s1Path, "노트북 1", "NB-X1", "XN-0121")),
                sw = listOf(listOf(r2.id, "PRC-X1", "XL-0002", "오피스", null, null), listOf(null, "NB-X1", "XN-0121", null, "1.0", null)),
                net = listOf(listOf(null, "NB-X1", "XN-0121", "관리용", "192.0.2.61", "02-00-00-00-61")),
            ),
        )
        // 짝이 없는 SW 줄(장비 시트에 없는 번호), SW 이름이 빈 줄은 SW 시트의 줄로 안내된다. 같은 장비의 틀린 MAC도 함께 안내된다(E-20)
        assertThat(rows.map { "${it["sheet"].asText()}/${it["row"].asInt()}/${it["column"].asText()}/${it["code"].asText()}" })
            .containsExactlyInAnyOrder("SW/3/SW 이름/EQUIPMENT_FIELD_INVALID", "SW/2/장비 번호/EXCEL_UNMATCHED_ROW", "네트워크/2/MAC 주소/EQUIPMENT_FIELD_INVALID")
        // 틀린 MAC은 네트워크 시트의 줄과 칸
        val mac = rowsError(
            "x-s1a",
            xlsx(main = listOf(newRow(s1Path, "노트북 1", "NB-X1", "XN-0121")), net = listOf(listOf(null, "NB-X1", "XN-0121", "관리용", "192.0.2.61", "02-00-00-00-61"))),
        )
        assertThat(mac.map { "${it["sheet"].asText()}/${it["row"].asInt()}/${it["column"].asText()}/${it["code"].asText()}" })
            .containsExactly("네트워크/2/MAC 주소/EQUIPMENT_FIELD_INVALID")
        // 파일 안에서 같은 MAC을 쓰는 두 장비는 뒤 장비의 네트워크 시트 줄로 안내된다(장비 시트에는 MAC 칸이 없음)
        val sameMac = rowsError(
            "x-s1a",
            xlsx(
                main = listOf(newRow(s1Path, "노트북 1", "NB-X1", "XN-0131"), newRow(s1Path, "노트북 2", "NB-X1", "XN-0132")),
                net = listOf(
                    listOf(null, "NB-X1", "XN-0131", "관리용", "192.0.2.71", "02-00-00-00-00-71"),
                    listOf(null, "NB-X1", "XN-0132", "관리용", "192.0.2.72", "02:00:00:00:00:71"),
                ),
            ),
        )
        assertThat(sameMac.map { "${it["sheet"].asText()}/${it["row"].asInt()}/${it["column"].asText()}/${it["code"].asText()}" })
            .containsExactly("네트워크/3/MAC 주소/EQUIPMENT_MAC_DUPLICATE")
        assertThat(sameMac[0]["message"].asText()).contains("2번째 줄")
        // xlsx가 아닌 파일, 머리글이 다른 파일
        json(upload("x-s1a", "not excel".toByteArray()).andExpect(status().isBadRequest).andExpect(jsonPath("$.code").value("EXCEL_INVALID")).andExpect(jsonPath("$.rows").doesNotExist()))
        val wrongHeader = EquipmentExcel.MAIN_COLUMNS.toMutableList().also { it[4] = "이름" }
        upload("x-s1a", xlsx(listOf(newRow(s1Path, "노트북", "NB-X1", "XN-0122")), mainHeader = wrongHeader))
            .andExpect(status().isBadRequest).andExpect(jsonPath("$.code").value("EXCEL_INVALID"))
    }

    @Test
    fun `REQ-517 틀린 줄이 여럿이면 형식 오류·빈 모델명·시리얼 겹침·SW·네트워크 오류를 모두 한 번에 안내한다 (E-20)`() {
        val before = equipmentRepository.count()
        val rows = rowsError(
            "x-s1a",
            xlsx(
                main = listOf(
                    newRow(s1Path, "노트북 1", "NB-X1", "XN-0201", "상태" to "고칠 것", "HW 설치연도" to "2026-13-01"),
                    newRow(s1Path, "노트북 2", " ", "XN-0202"),
                    newRow(s1Path, "노트북 3", "NB-X1", "XN-0203"),
                    newRow(s1Path, "노트북 4", "NB-X1", "XN-0203"),
                    newRow(s1Path, "노트북 5", "NB-X1", "XN-0205"),
                    // 상태가 틀린 2번째 줄과 시리얼넘버가 같은 줄, 관리부서가 틀리고 4번째 줄과 시리얼넘버가 같은 줄
                    newRow(s1Path, "노트북 6", "NB-X1", "XN-0201"),
                    newRow("없는대대 > 1중대 > 1반", "노트북 7", "NB-X1", "XN-0203"),
                ),
                sw = listOf(listOf(null, "NB-X1", "XN-0205", null, "1.0", null)),
                net = listOf(listOf(null, "NB-X1", "XN-0205", "관리용", "192.0.2.81", "02-00-00-00-81")),
            ),
        )
        // 한 줄에 틀린 칸이 둘이면 둘 다, 한 장비의 SW·네트워크 오류도 둘 다. 상태·관리부서가 틀린 줄도 겹침 검사에 든다
        assertThat(rows.map { "${it["sheet"].asText()}/${it["row"].asInt()}/${it["column"].asText()}/${it["code"].asText()}" }).containsExactly(
            "장비/2/상태/EQUIPMENT_FIELD_INVALID",
            "장비/2/HW 설치연도/EXCEL_VALUE_INVALID",
            "장비/3/모델명/EQUIPMENT_FIELD_INVALID",
            "장비/5/시리얼넘버/EQUIPMENT_SERIAL_DUPLICATE",
            "장비/7/시리얼넘버/EQUIPMENT_SERIAL_DUPLICATE",
            "장비/8/관리부서/DEPARTMENT_INVALID",
            "장비/8/시리얼넘버/EQUIPMENT_SERIAL_DUPLICATE",
            "SW/2/SW 이름/EQUIPMENT_FIELD_INVALID",
            "네트워크/2/MAC 주소/EQUIPMENT_FIELD_INVALID",
        )
        assertThat(equipmentRepository.count()).isEqualTo(before)
    }

    @Test
    fun `REQ-517 불용 장비 A와 새 장비 B의 모델명·시리얼넘버가 같아도 통과한다`() {
        val a = equipment("노트북 A", s1, "XL-0050", model = "NB-X1") { status = "DISPOSED" }
        val result = imported("x-s1a", xlsx(listOf(newRow(s1Path, "노트북 B", "NB-X1", "XL-0050"))))
        assertThat(result["created"].asInt()).isEqualTo(1)
        assertThat(reload(a).status).isEqualTo("DISPOSED")
        assertThat(equipmentRepository.findActiveBySerial("NB-X1", "XL-0050").single().name).isEqualTo("노트북 B")
    }

    @Test
    fun `REQ-517 두 장비의 시리얼넘버(와 MAC)를 맞바꾼 파일은 통과한다`() {
        val a = equipment("노트북 A", s1, "XL-0061", model = "NB-X1") {
            networks.add(EquipmentNetwork("관리용", "192.0.2.61", "02-00-00-00-00-61", "020000000061", "020000000061"))
        }
        val b = equipment("노트북 B", s1, "XL-0062", model = "NB-X1") {
            networks.add(EquipmentNetwork("관리용", "192.0.2.62", "02-00-00-00-00-62", "020000000062", "020000000062"))
        }
        val file = export("x-s1a", listOf(a.id, b.id))
        val rows = mainRows(file)
        rows.rowOf(a)[col("시리얼넘버")] = "XL-0062"
        rows.rowOf(b)[col("시리얼넘버")] = "XL-0061"
        val nets = netRows(file)
        val macA = nets.first { it[0] == a.id.toString() }
        val macB = nets.first { it[0] == b.id.toString() }
        macA[5] = "02-00-00-00-00-62".also { macB[5] = "02-00-00-00-00-61" }
        val result = imported("x-s1a", xlsx(rows, swRows(file), nets))
        assertThat(result["updated"].asInt()).isEqualTo(2)
        assertThat(reload(a).serialNumber).isEqualTo("XL-0062")
        assertThat(reload(b).serialNumber).isEqualTo("XL-0061")
        assertThat(reload(a).networks.single().macKey).isEqualTo("020000000062")
        assertThat(reload(b).networks.single().activeMacKey).isEqualTo("020000000061")
    }

    @Test
    fun `REQ-517 1중대장이 1중대·1반·2반 장비 목록에서 1중대 장비 한 줄만 고쳐 올리면 그 줄만 반영되고 1반·2반 줄은 건너뛴다`() {
        val company = equipment("중대 장비", c1, "XL-0070")
        val rows = mainRows(export("x-c1cmdr", listOf(company.id, r1.id, r2.id)))
        assertThat(rows).hasSize(3)
        rows.rowOf(company)[col("비고")] = "중대장이 고침"
        val result = imported("x-c1cmdr", xlsx(rows))
        assertThat(result["updated"].asInt()).isEqualTo(1)
        assertThat(result["skipped"].asInt()).isEqualTo(2)
        assertThat(reload(company).memo).isEqualTo("중대장이 고침")
    }

    @Test
    fun `REQ-517 1반 소속 인원이 관리부서 칸을 2반으로 바꾼 줄은 건너뛴다`() {
        val rows = mainRows(export("x-s1a", listOf(r1.id)))
        rows.rowOf(r1)[col("관리부서")] = s2Path
        rows.rowOf(r1)[col("비고")] = "옮김"
        val result = imported("x-s1a", xlsx(rows))
        assertThat(result["skipped"].asInt()).isEqualTo(1)
        assertThat(result["updated"].asInt()).isZero()
        assertThat(reload(r1).departmentId).isEqualTo(s1.id)
        assertThat(reload(r1).memo).isNull()
        // 다룰 수 있는 범위 밖 장비를 가리키는 새 줄(2반 경로)도 건너뛴다
        assertThat(imported("x-s1a", xlsx(listOf(newRow(s2Path, "노트북", "NB-X1", "XN-0071"))))["skipped"].asInt()).isEqualTo(1)
    }

    @Test
    fun `REQ-517 고칠 때 OS 설치버전 칸을 비우면 그 값이 지워지고 장비명 칸을 비우면 틀린 줄이다`() {
        r1.osVersion = "22H2"
        r1.software.add(EquipmentSoftware("오피스", "2021", null))
        equipmentRepository.flush()
        val file = export("x-s1a", listOf(r1.id))
        val rows = mainRows(file)
        rows.rowOf(r1)[col("OS 설치버전")] = null
        // SW 시트의 줄을 빼면 SW 목록도 비워진다 (E-38)
        assertThat(imported("x-s1a", xlsx(rows))["updated"].asInt()).isEqualTo(1)
        assertThat(reload(r1).osVersion).isNull()
        assertThat(reload(r1).software).isEmpty()

        val again = mainRows(export("x-s1a", listOf(r1.id)))
        again.rowOf(r1)[col("장비명")] = ""
        val errors = rowsError("x-s1a", xlsx(again))
        assertThat(errors.map { "${it["row"].asInt()}/${it["column"].asText()}/${it["code"].asText()}" }).containsExactly("2/장비명/EQUIPMENT_FIELD_INVALID")
        assertThat(reload(r1).name).isEqualTo("무전기")
    }

    @Test
    fun `REQ-517 내려받은 뒤 다른 사람이 고친 장비의 줄이 있으면 아무것도 반영되지 않는다`() {
        val other = equipment("노트북", s1, "XL-0080")
        val file = export("x-s1a", listOf(r1.id, other.id))
        clock.now = clock.now.plusMinutes(3)
        edit("x-s1b", r1.id, mapOf("memo" to "다른 사람이 고침")).andExpect(status().isOk)
        val rows = mainRows(file)
        rows.rowOf(other)[col("비고")] = "내가 고침"
        val errors = rowsError("x-s1a", xlsx(rows))
        assertThat(errors.map { "${it["row"].asInt()}/${it["column"].asText()}/${it["code"].asText()}" })
            .containsExactly("${mainRows(file).indexOfFirst { it[0] == r1.id.toString() } + 2}/고친 시각/EQUIPMENT_EDIT_CONFLICT")
        assertThat(reload(other).memo).isNull()
        assertThat(reload(r1).memo).isEqualTo("다른 사람이 고침")
    }

    @Test
    fun `REQ-517 고친 시각은 밀리초 없이 내보내고 같은 초면 바뀌지 않은 줄로 본다 (G-92)`() {
        clock.now = clock.now.plusMinutes(3).plusNanos(789_000_000)
        edit("x-s1a", r1.id, mapOf("memo" to "밀리초")).andExpect(status().isOk)
        val rows = mainRows(export("x-s1a", listOf(r1.id)))
        assertThat(rows.rowOf(r1)[col("고친 시각")]).isEqualTo("2026-10-17 09:03:00")
        assertThat(imported("x-s1a", xlsx(rows))["unchanged"].asInt()).isEqualTo(1)
        // 앞서 밀리초까지 내려받은 파일도 받는다
        rows.rowOf(r1)[col("고친 시각")] = "2026-10-17 09:03:00.789"
        assertThat(imported("x-s1a", xlsx(rows))["unchanged"].asInt()).isEqualTo(1)
    }

    @Test
    fun `REQ-517 끝나지 않은 업무 계획에 든 장비의 관리부서를 바꾸는 줄이 있으면 거부된다`() {
        plannedItem("x-s1a", s1, r1)
        val rows = mainRows(export("x-c1adm", listOf(r1.id), kind = "MANAGED"))
        rows.rowOf(r1)[col("관리부서")] = s2Path
        val errors = rowsError("x-c1adm", xlsx(rows))
        assertThat(errors.map { it["code"].asText() }).containsExactly("EQUIPMENT_IN_USE")
        assertThat(reload(r1).departmentId).isEqualTo(s1.id)
        // 업무 계획에 든 장비라도 다른 칸(비고)은 고친다
        val memo = mainRows(export("x-c1adm", listOf(r1.id), kind = "MANAGED"))
        memo.rowOf(r1)[col("비고")] = "계획 중"
        assertThat(imported("x-c1adm", xlsx(memo))["updated"].asInt()).isEqualTo(1)
    }

    @Test
    fun `REQ-517 관리부서 경로 1중대 1반과 2중대 1반이 구분되고 맞는 부서가 없으면 틀린 줄이다`() {
        val result = imported(
            "x-b1adm",
            xlsx(listOf(newRow(s1Path, "노트북 1", "NB-X1", "XN-0091"), newRow("장비대대>2중대>  1반", "노트북 2", "NB-X1", "XN-0092"))),
        )
        assertThat(result["created"].asInt()).isEqualTo(2)
        assertThat(equipmentRepository.findActiveBySerial("NB-X1", "XN-0091").single().departmentId).isEqualTo(s1.id)
        assertThat(equipmentRepository.findActiveBySerial("NB-X1", "XN-0092").single().departmentId).isEqualTo(s3.id)
        val errors = rowsError("x-b1adm", xlsx(listOf(newRow("장비대대 > 3중대 > 1반", "노트북 3", "NB-X1", "XN-0093"), newRow("1반", "노트북 4", "NB-X1", "XN-0094"))))
        assertThat(errors.map { "${it["row"].asInt()}/${it["column"].asText()}/${it["code"].asText()}" })
            .containsExactly("2/관리부서/DEPARTMENT_INVALID", "3/관리부서/DEPARTMENT_INVALID")
    }

    @Test
    fun `REQ-517 설치장소는 사용 중인 설치장소 이름으로 적고 없는 이름은 틀린 줄이다`() {
        send("POST", "x-c1adm", "/api/admin/locations", mapOf("name" to "1반 창고", "departmentId" to s1.id)).andExpect(status().isCreated)
        assertThat(imported("x-s1a", xlsx(listOf(newRow(s1Path, "노트북", "NB-X1", "XN-0095", "설치장소" to " 1반 창고 "))))["created"].asInt()).isEqualTo(1)
        assertThat(equipmentRepository.findActiveBySerial("NB-X1", "XN-0095").single().storageLocationId).isNotNull()
        val errors = rowsError("x-s1a", xlsx(listOf(newRow(s1Path, "노트북", "NB-X1", "XN-0096", "설치장소" to "없는 창고"))))
        assertThat(errors.map { "${it["column"].asText()}/${it["code"].asText()}" }).containsExactly("설치장소/LOCATION_INVALID")
    }

    @Test
    fun `REQ-517 파일 안에서 같은 장비를 가리키는 줄이 둘이면 틀린 줄이다`() {
        val rows = mainRows(export("x-s1a", listOf(r1.id)))
        rows.add(rows[0].toMutableList())
        rows.add(newRow(s1Path, "무전기", "prc-x1", "XL-0001").toMutableList()) // 번호 없이 같은 모델명·시리얼넘버
        val errors = rowsError("x-s1a", xlsx(rows))
        assertThat(errors.map { "${it["row"].asInt()}/${it["code"].asText()}" }).containsExactly("3/EXCEL_DUPLICATE_ROW", "4/EXCEL_DUPLICATE_ROW")
    }

    @Test
    fun `REQ-517 일괄 등록 파일이 6MB를 넘으면 읽기 전에 413이고 6MB 안의 파일은 내용을 검사한다`() {
        // multipart 한도는 첨부 10MB라 6MB+1은 서비스가 거부한다 (slices/11 2.3.2)
        upload("x-s1a", ByteArray((EquipmentExcel.MAX_IMPORT_SIZE + 1).toInt())).andExpect(status().isPayloadTooLarge).andExpect(jsonPath("$.code").value("FILE_TOO_LARGE"))
        upload("x-s1a", ByteArray(EquipmentExcel.MAX_IMPORT_SIZE.toInt())).andExpect(status().isBadRequest).andExpect(jsonPath("$.code").value("EXCEL_INVALID"))
    }

    @Test
    fun `일괄 등록은 시트마다 머리글 아래 한도 줄까지 받고 그 뒤 줄이 있으면 읽기를 멈추고 400이다 (재검토 F6-1)`() {
        val max = EquipmentExcel.MAX_IMPORT_ROWS
        /** 머리글과, 엑셀 줄 번호 rowNum에 줄 하나만 있는 파일 */
        fun fileWith(sheet: String, rowNum: Int, values: List<Any?>): ByteArray {
            val out = ByteArrayOutputStream()
            val wb = Workbook(out, "test", "1.0")
            listOf("장비" to EquipmentExcel.MAIN_COLUMNS, "SW" to EquipmentExcel.SW_COLUMNS, "네트워크" to EquipmentExcel.NET_COLUMNS).forEach { (name, header) ->
                val ws = wb.newWorksheet(name)
                header.forEachIndexed { i, h -> ws.value(0, i, h) }
                if (name == sheet) values.forEachIndexed { c, v -> if (v != null) ws.value(rowNum - 1, c, v.toString()) }
            }
            wb.finish()
            return out.toByteArray()
        }
        assertThat(imported("x-s1a", fileWith("장비", max + 1, newRow(s1Path, "무전기", "PRC-X1", "XL-LIMIT")))["created"].asInt()).isEqualTo(1)
        upload("x-s1a", fileWith("장비", max + 2, newRow(s1Path, "무전기", "PRC-X1", "XL-OVER")))
            .andExpect(status().isBadRequest).andExpect(jsonPath("$.code").value("EXCEL_INVALID"))
            .andExpect(jsonPath("$.message").value("'장비' 시트는 머리글 아래 ${max}줄까지 올릴 수 있습니다. 나눠 올리세요."))
        upload("x-s1a", fileWith("네트워크", max + 2, listOf(r1.id, null, null, "LAN", "10.0.0.1", null)))
            .andExpect(status().isBadRequest).andExpect(jsonPath("$.message").value("'네트워크' 시트는 머리글 아래 ${max}줄까지 올릴 수 있습니다. 나눠 올리세요."))
        assertThat(equipmentRepository.findAll().map { it.serialNumber }).doesNotContain("XL-OVER")
    }

    @Test
    fun `일괄 등록은 줄의 칸이 고정 칸과 특성 수를 넘으면 읽기를 멈추고 400이고, 압축을 풀어 20MB를 넘는 파일도 읽기 전에 400이다 (재검토 2회차 F3)`() {
        val mainLimit = EquipmentExcel.MAIN_COLUMNS.size + attributeDefRepository.countByTarget(AttributeTargets.EQUIPMENT).toInt()
        /** 머리글과, 시트 sheet의 둘째 줄에 칸 번호별 값이 있는 파일 */
        fun fileWith(sheet: String, values: Map<Int, String>): ByteArray {
            val out = ByteArrayOutputStream()
            val wb = Workbook(out, "test", "1.0")
            listOf("장비" to EquipmentExcel.MAIN_COLUMNS, "SW" to EquipmentExcel.SW_COLUMNS, "네트워크" to EquipmentExcel.NET_COLUMNS).forEach { (name, header) ->
                val ws = wb.newWorksheet(name)
                header.forEachIndexed { i, h -> ws.value(0, i, h) }
                if (name == sheet) values.forEach { (c, v) -> ws.value(1, c, v) }
            }
            wb.finish()
            return out.toByteArray()
        }
        val row = newRow(s1Path, "무전기", "PRC-X1", "XL-WIDE").withIndex().filter { it.value != null }.associate { it.index to it.value.toString() }
        // 마지막으로 받는 칸: 특성이 없으면 고친 시각 칸(새 줄은 읽지 않음), 있으면 머리글 없는 칸(읽지 않음)
        val last = if (mainLimit - 1 == col("고친 시각")) "2026-10-17 09:00:00" else "메모"
        assertThat(imported("x-s1a", fileWith("장비", row + (mainLimit - 1 to last)))["created"].asInt()).isEqualTo(1)
        upload("x-s1a", fileWith("장비", row + (mainLimit to "먼 칸")))
            .andExpect(status().isBadRequest).andExpect(jsonPath("$.code").value("EXCEL_INVALID"))
            .andExpect(jsonPath("$.message").value("'장비' 시트 2번째 줄에 양식에 없는 칸이 있습니다. 내려받은 양식의 칸에만 적어 올리세요."))
        upload("x-s1a", fileWith("SW", mapOf(0 to r1.id.toString(), 3 to "한글", EquipmentExcel.SW_COLUMNS.size to "먼 칸")))
            .andExpect(status().isBadRequest).andExpect(jsonPath("$.message").value("'SW' 시트 2번째 줄에 양식에 없는 칸이 있습니다. 내려받은 양식의 칸에만 적어 올리세요."))

        // 같은 글자가 이어져 작게 압축되는 큰 내용 (긴 셀·공유 문자열 폭탄)
        val bomb = ByteArrayOutputStream().also { out ->
            ZipOutputStream(out).use { zip ->
                zip.putNextEntry(ZipEntry("xl/sharedStrings.xml"))
                val chunk = ByteArray(1024 * 1024) { 'a'.code.toByte() }
                repeat((EquipmentExcel.MAX_IMPORT_UNZIPPED / chunk.size).toInt() + 1) { zip.write(chunk) }
                zip.closeEntry()
            }
        }.toByteArray()
        assertThat(bomb.size).isLessThan(EquipmentExcel.MAX_IMPORT_SIZE.toInt())
        upload("x-s1a", bomb).andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.message").value("파일 내용이 너무 큽니다(압축을 풀면 20MB 넘음). 내려받은 양식에 적어 나눠 올리세요."))
        assertThat(equipmentRepository.findAll().map { it.serialNumber }.count { it == "XL-WIDE" }).isEqualTo(1)
    }
}
