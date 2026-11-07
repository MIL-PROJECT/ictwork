package kr.ictwork.ai.assist

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.LocalDate

/**
 * 체계 검사 (REQ-C03·C05·C06, NFR-007, slices/21 5.7·9장·13.5): PoC 시나리오의 위반 예마다 그 값만 버려지는지, [바꾸기]로 가는지, 되묻기 정리.
 * 조직·사람·장비는 모두 가상 (NFR-002)
 */
class AssistValidatorTest {
    private fun dept(id: Long, path: String) = Candidate(Choice(id, path), path.split(" ").last())
    private fun person(id: Long, path: String, rank: String, name: String) = Candidate(Choice(id, "$path $rank $name"), name, person = true, path = path)

    private val org = listOf(
        dept(1, "1대대"), dept(2, "1대대 1중대"), dept(3, "1대대 1중대 1반"), dept(4, "1대대 1중대 2반"), dept(5, "1대대 2중대"), dept(6, "1대대 2중대 1반"), dept(7, "1대대 통신소대"),
    )
    private val people = listOf(
        person(11, "1대대 1중대 1반", "병장", "김가상"), person(12, "1대대 1중대 2반", "병장", "김가상"), person(13, "1대대 통신소대", "중사", "정가상"),
    )

    private val fields = listOf(
        AssistField("place", "요청 장소", FieldKinds.TEXT, required = true),
        AssistField("detail", "세부 내용", FieldKinds.LONGTEXT, required = true, aliases = listOf("내용")),
        AssistField("serialNumber", "시리얼넘버", FieldKinds.TEXT, required = true),
        AssistField("memo", "비고", FieldKinds.LONGTEXT),
        AssistField("status", "상태", FieldKinds.SELECT, required = true, choices = AssistScreens.EQUIPMENT_STATUSES),
        AssistField("role", "역할", FieldKinds.SELECT, perm = true, choices = AssistScreens.ROLES),
        AssistField("viewerDepartments", "열람 범위(부서)", FieldKinds.MULTI, perm = true, source = Sources.ORG),
        AssistField("viewerPeople", "열람 범위(사람)", FieldKinds.MULTI, perm = true, source = Sources.PEOPLE),
        AssistField("values.level", "상세: 단계", FieldKinds.SELECT, choices = (1..5).map { Choice("L$it", "${it}단계") }, aliases = listOf("단계")),
        AssistField("summary.text", "대표 내용", FieldKinds.TEXT, aliases = listOf("대표", "요약")),
        AssistField("color.color", "색", FieldKinds.SELECT, choices = AssistScreens.BOX_COLORS),
    )

    private fun input(
        userWords: List<String>,
        current: Map<String, Any?> = emptyMap(),
        userEdited: Set<String> = emptySet(),
        declined: Set<String> = emptySet(),
        turnWords: List<String> = userWords.takeLast(1),
    ) = AssistCheck(
        fields = fields,
        pool = { f -> f.choices?.map { Candidate(it, it.label) } ?: when (f.source) { Sources.ORG -> org; Sources.PEOPLE -> people; else -> emptyList() } },
        columnPool = { _, _ -> emptyList() },
        current = current, userEdited = userEdited, declined = declined, userWords = userWords, turnWords = turnWords,
    )

    @Test
    fun `지어낸 시리얼과 장소에 넣은 부서 이름은 버린다(근거 검사)`() {
        val r = AssistValidator.check(input(listOf("행정반 전화가 안 돼요")), mapOf("serialNumber" to "SN-9999", "place" to "1중대 1반", "detail" to "행정반 전화 통화 불가"))
        // 세부 내용도 글 칸이라 사용자 말에 이어진 그대로가 아니면 버린다(사용자 결정)
        assertThat(r.applied).isEmpty()
        assertThat(r.violations).contains(AssistViolation("serialNumber", 6), AssistViolation("place", 6), AssistViolation("detail", 6))
        val ok = AssistValidator.check(input(listOf("시리얼은 AI-SN-0007, 장소는 행정반")), mapOf("serialNumber" to "AI-SN-0007", "place" to "행정반"))
        assertThat(ok.applied).isEqualTo(mapOf("serialNumber" to "AI-SN-0007", "place" to "행정반"))
    }

    @Test
    fun `문서 속 지시문으로는 열람 범위를 채우지 않는다`() {
        val r = AssistValidator.check(
            input(listOf("행정반 전화 회선 옮겨 달라고 요청해 줘")),
            mapOf("viewerDepartments" to listOf("1대대")),
        )
        assertThat(r.applied).isEmpty()
        assertThat(r.violations).containsExactly(AssistViolation("viewerDepartments", 8))
        // 사용자가 말한 이름은 받는다: 가장 긴 이름(1대대 1중대), 끝 이름이 하나뿐인 부서(통신소대)
        val ok = AssistValidator.check(input(listOf("열람 범위에 1대대 1중대와 통신소대 넣어 줘")), mapOf("viewerDepartments" to listOf("1대대 1중대", "1대대 통신소대")))
        assertThat(ok.applied["viewerDepartments"]).isEqualTo(listOf(2L, 7L))
        assertThat(AssistValidator.mentioned(org, listOf("1대대 1중대에 보내")).map { it.choice.value }).containsExactly(2L)
        // 끝 이름이 둘인 부서("1반")는 그 이름만으로 받지 않는다
        assertThat(AssistValidator.mentioned(org, listOf("1반도 넣어"))).isEmpty()
    }

    @Test
    fun `같은 성명이 여럿이면 버리고 후보와 함께 되묻는다`() {
        val r = AssistValidator.check(input(listOf("김가상도 열람에 넣어 줘")), mapOf("viewerPeople" to listOf("1대대 1중대 1반 병장 김가상")))
        assertThat(r.applied).isEmpty()
        assertThat(r.forced.single().kind).isEqualTo(AssistAsk.CHOICE)
        assertThat(r.forced.single().choices).containsExactly("1대대 1중대 1반 병장 김가상", "1대대 1중대 2반 병장 김가상")
        // 소속 낱말이 나오면 그 사람으로 좁힌다
        val ok = AssistValidator.check(input(listOf("2반 김가상 넣어 줘")), mapOf("viewerPeople" to listOf("1대대 1중대 2반 병장 김가상")))
        assertThat(ok.applied["viewerPeople"]).isEqualTo(listOf(12L))
        assertThat(ok.forced).isEmpty()
    }

    @Test
    fun `후보 밖 값과 거절 글과 시스템 관리자 역할과 칸 목록 밖은 버린다`() {
        val r = AssistValidator.check(
            input(listOf("상태는 사용 중, 비고는 몰라, 역할은 시스템 관리자로")),
            mapOf("status" to "폐기", "memo" to "몰라", "role" to "시스템 관리자", "password" to "x"),
        )
        assertThat(r.applied).isEmpty()
        assertThat(r.violations).contains(AssistViolation("status", 5), AssistViolation("memo", 4), AssistViolation("role", 5), AssistViolation(AssistValidator.UNKNOWN_FIELD, 1))
        // 칸 목록 밖 칸 키는 로그에 그대로 남기지 않는다 (12.7)
        assertThat(r.violations.map { it.field }).doesNotContain("password")
        // 포함 맞춤이 하나뿐이면 그 후보로 바꾼다
        val ok = AssistValidator.check(input(listOf("상태는 고장")), mapOf("status" to "고장 상태"))
        assertThat(ok.applied["status"]).isEqualTo("BROKEN")
    }

    @Test
    fun `근거 없는 선택 칸 고르기는 버린다`() {
        val r = AssistValidator.check(input(listOf("박스 고쳐 줘")), mapOf("color.color" to "빨강"))
        assertThat(r.violations).containsExactly(AssistViolation("color.color", 7))
    }

    @Test
    fun `사용자 칸과 이름이 나오지 않은 값 있는 칸은 바꾸기 제안이다`() {
        val current = mapOf("detail" to "사용자가 적은 세부 내용", "memo" to "예전 비고", "place" to "행정반")
        val r = AssistValidator.check(
            input(listOf("PC-101 비고에 모니터 교체 적어 줘"), current = current, userEdited = setOf("detail")),
            mapOf("detail" to "모니터 교체", "memo" to "모니터 교체", "place" to "통신창고"),
        )
        // "비고"가 나왔으므로 비고는 바로, 세부 내용(사용자 칸)·장소(값이 있는 칸)는 [바꾸기]
        assertThat(r.applied).isEqualTo(mapOf("memo" to "모니터 교체"))
        assertThat(r.proposed.map { it.field }).containsExactly("detail")
        assertThat(r.violations).contains(AssistViolation("detail", 2), AssistViolation("place", 6))
    }

    @Test
    fun `CPCON 4단계 예 - 칸 이름이나 새 값이 말에 있으면 바로, 아니면 바꾸기 제안(사용자 결정)`() {
        val current = mapOf("values.level" to "L5", "summary.text" to "5단계", "color.color" to "GREEN")
        val raw = mapOf("values.level" to "4단계", "summary.text" to "4단계 발령", "color.color" to "주황")
        val r = AssistValidator.check(input(listOf("CPCON 4단계로 올려줘, 색도 주황"), current = current), raw)
        // 단계: 칸 이름이 나옴, 색: 새 값(주황)이 말에 있음 → 바로. 대표 내용: "4단계 발령"이 말에 이어진 그대로 없음 → 버림(사용자 결정)
        assertThat(r.applied).isEqualTo(mapOf("values.level" to "L4", "color.color" to "ORANGE"))
        assertThat(r.proposed).isEmpty()
        assertThat(r.violations).contains(AssistViolation("summary.text", 6))
        val all = AssistValidator.check(input(listOf("CPCON 4단계, 대표 내용은 4단계 발령"), current = current), raw - "color.color")
        assertThat(all.applied).isEqualTo(mapOf("values.level" to "L4", "summary.text" to "4단계 발령"))
    }

    @Test
    fun `화면 모양과 서버 칸을 바꿔 주고받는다(받는 부서 짝, 박스 대표 내용·색)`() {
        val raw = mapOf("targets" to listOf(3), "chosenTypes" to mapOf("3" to listOf(30)), "place" to "행정반")
        assertThat(AssistShapes.inbound("request", raw)).isEqualTo(mapOf("targets" to listOf(mapOf("departmentId" to 3L, "typeId" to 30L)), "place" to "행정반"))
        // 받는 부서는 목록 칸: 짝 값이 최종 전체 목록이다(유형을 아직 고르지 않은 부서 5는 그대로 둔다)
        val out = AssistShapes.outbound("request", mapOf("targets" to listOf(mapOf("departmentId" to 3L, "typeId" to 30L), mapOf("departmentId" to 4L, "typeId" to 40L))), raw + ("targets" to listOf(3, 5)))
        assertThat(out).isEqualTo(mapOf("targets" to listOf(5L, 3L, 4L), "chosenTypes" to mapOf("5" to emptyList<Long>(), "3" to listOf(30L), "4" to listOf(40L))))
        assertThat(AssistShapes.outbound("request", mapOf("targets" to listOf(mapOf("departmentId" to 4L, "typeId" to 40L))), raw))
            .isEqualTo(mapOf("targets" to listOf(4L), "chosenTypes" to mapOf("4" to listOf(40L))))
        assertThat(AssistShapes.inboundKeys("request", setOf("chosenTypes", "place"))).containsExactlyInAnyOrder("targets", "place")
        val box = mapOf("summary" to mapOf("mode" to "TEXT", "text" to "5단계", "fieldKey" to null), "color" to mapOf("mode" to "FIXED", "color" to "GREEN"))
        assertThat(AssistShapes.inbound("ict.edit", box)).containsEntry("summary.text", "5단계").containsEntry("color.color", "GREEN").doesNotContainKey("summary")
        assertThat(AssistShapes.outbound("ict.edit", mapOf("summary.text" to "4단계"), box)).isEqualTo(mapOf("summary" to mapOf("mode" to "TEXT", "text" to "4단계", "fieldKey" to null)))
        // 박스 추가 창의 summary·color는 그냥 칸이다
        assertThat(AssistShapes.inbound("ict.new", mapOf("summary" to "정상"))).isEqualTo(mapOf("summary" to "정상"))
    }

    private val more = listOf(
        AssistField("shade", "표시 색상", FieldKinds.SELECT, choices = AssistScreens.CALENDAR_COLORS),
        AssistField("due", "마감일", FieldKinds.DATE),
        AssistField("at", "출동 시각", FieldKinds.TIME),
        AssistField("note", "비고", FieldKinds.LONGTEXT),
        AssistField("important", "중요 표시", FieldKinds.SELECT, choices = AssistScreens.yesNo("중요")),
    )

    private fun moreInput(words: String, current: Map<String, Any?>) = AssistCheck(
        fields = more, pool = { f -> f.choices.orEmpty().map { Candidate(it, it.label) } }, columnPool = { _, _ -> emptyList() },
        current = current, userWords = listOf(words), turnWords = listOf(words),
    )

    @Test
    fun `값이 있는 칸도 새 값이 이번 말에 그대로 있으면 바로 바꾼다(사용자 결정)`() {
        val current = mapOf("shade" to "Success", "due" to "2026-11-01", "at" to "09:00", "note" to "예전 메모", "important" to false)
        val r = AssistValidator.check(
            moreInput("빨간색으로, 11월 20일 오후 3시 반에 모니터 교체, 중요 표시도", current),
            mapOf("shade" to "빨강", "due" to "2026-11-20", "at" to "15:30", "note" to "모니터 교체", "important" to "예"),
        )
        assertThat(r.applied).isEqualTo(mapOf("shade" to "Danger", "due" to "2026-11-20", "at" to "15:30", "note" to "모니터 교체", "important" to true))
        assertThat(r.proposed).isEmpty()
        // 말에 없는 새 값은 받지 않는다: 날짜·시각은 말에 근거가 없으면(결함 13), 글은 말에 이어진 그대로가 아니면(사용자 결정)
        val p = AssistValidator.check(moreInput("고쳐 줘", current), mapOf("due" to "2026-11-21", "at" to "16:00", "note" to "모니터 수리"))
        assertThat(p.applied).isEmpty()
        assertThat(p.proposed).isEmpty()
        assertThat(p.violations).contains(AssistViolation("due", 6), AssistViolation("at", 6), AssistViolation("note", 6))
        assertThat(AssistValidator.dateIn("2026-11-20", "11/20까지")).isTrue()
        assertThat(AssistValidator.dateIn("2026-11-02", "11월 20일")).isFalse()
        assertThat(AssistValidator.timeIn("09:05", "9시 5분")).isTrue()
    }

    @Test
    fun `구성도 동작에는 id 옆에 보일 이름을 붙인다`() {
        val pool = listOf(Candidate(Choice(1L, "PC-101 (SN-1)"), "PC-101"), Candidate(Choice(2L, "무전기A (SN-2)"), "무전기A"))
        val f = AssistField("ops", "넣을 요소", FieldKinds.OPS, source = Sources.DIAGRAM_EQUIPMENT)
        val shapes = listOf(Candidate(Choice("shape-a", "분배기"), "분배기"))
        val input = AssistCheck(listOf(f), { pool }, { _, _ -> emptyList() }, emptyMap(), placed = listOf(1L), placedShapes = shapes)
        val raw = listOf(
            mapOf("op" to "place", "equipment" to "무전기A (SN-2)"), mapOf("op" to "connect", "from" to "PC-101 (SN-1)", "to" to "무전기A (SN-2)"),
            mapOf("op" to "zone", "name" to "통신실", "members" to listOf("PC-101 (SN-1)")),
        )
        @Suppress("UNCHECKED_CAST")
        val ops = AssistValidator.check(input, mapOf("ops" to raw)).applied["ops"] as List<Map<String, Any?>>
        assertThat(ops[0]).containsEntry("equipment", 2L).containsEntry("equipmentLabel", "무전기A (SN-2)")
        assertThat(ops[1]).containsEntry("from", 1L).containsEntry("fromLabel", "PC-101 (SN-1)").containsEntry("toLabel", "무전기A (SN-2)")
        assertThat(ops[2]).containsEntry("members", listOf(1L)).containsEntry("memberLabels", listOf("PC-101 (SN-1)")).containsEntry("memberKinds", listOf("equipment"))
        // 놓인 도형도 선의 끝·구역 묶음이 된다
        val withShape = listOf(mapOf("op" to "connect", "from" to "분배기", "to" to "PC-101 (SN-1)"), mapOf("op" to "zone", "name" to "1층", "members" to listOf("분배기", "PC-101 (SN-1)")))
        @Suppress("UNCHECKED_CAST")
        val shapeOps = AssistValidator.check(input, mapOf("ops" to withShape)).applied["ops"] as List<Map<String, Any?>>
        assertThat(shapeOps[0]).containsEntry("from", "shape-a").containsEntry("fromKind", "shape").containsEntry("to", 1L).containsEntry("toKind", "equipment")
        assertThat(shapeOps[1]).containsEntry("members", listOf("shape-a", 1L)).containsEntry("memberKinds", listOf("shape", "equipment"))
        // 놓이지 않은 장비는 선의 끝이 될 수 없다
        assertThat(AssistValidator.check(input, mapOf("ops" to listOf(mapOf("op" to "connect", "from" to "분배기", "to" to "무전기A (SN-2)")))).applied).isEmpty()
    }

    @Test
    fun `박스 새 칸은 그리드와 선형 배율 정의와 값을 받는다`() {
        val f = AssistField("fields", "새 상세 칸", FieldKinds.BOX_FIELDS, maxRows = 10)
        val input = AssistCheck(listOf(f), { emptyList() }, { _, _ -> emptyList() }, emptyMap())
        val raw = listOf(
            mapOf("kind" to "객관식 그리드", "title" to "회선 상태", "gridRows" to listOf("1번", "2번"), "gridColumns" to listOf("정상", "장애"), "value" to mapOf("1번" to "정상", "2번" to "장애")),
            mapOf("kind" to "선형 배율", "title" to "혼잡도", "scaleMin" to 1, "scaleMax" to 5, "minLabel" to "한산", "maxLabel" to "혼잡", "value" to 4),
        )
        @Suppress("UNCHECKED_CAST")
        val out = AssistValidator.check(input, mapOf("fields" to raw)).applied["fields"] as List<Map<String, Any?>>
        assertThat(out[0]).containsEntry("kind", "MC_GRID").containsEntry("gridRows", listOf("1번", "2번")).containsEntry("gridColumns", listOf("정상", "장애"))
            .containsEntry("value", mapOf("1번" to "정상", "2번" to "장애"))
        assertThat(out[1]).containsEntry("kind", "LINEAR_SCALE").containsEntry("scaleMin", 1).containsEntry("scaleMax", 5).containsEntry("minLabel", "한산").containsEntry("value", 4)
        // 그리드에 열이 없거나 배율 밖 값은 버린다
        val bad = AssistValidator.check(input, mapOf("fields" to listOf(mapOf("kind" to "선형 배율", "title" to "혼잡도", "scaleMax" to 5, "value" to 9))))
        assertThat(bad.applied).isEmpty()
        assertThat(AssistValidator.check(input, mapOf("fields" to listOf(mapOf("kind" to "체크박스 그리드", "title" to "x", "gridRows" to listOf("a"))))).applied).isEmpty()
    }

    @Test
    fun `근무 규칙 줄의 사람은 그 규칙 인원만 받는다`() {
        val f = AssistField(
            "first", "요일 규칙마다 첫 사람", FieldKinds.ROWS,
            columns = listOf(AssistColumn("rule", "규칙", FieldKinds.SELECT, source = Sources.WEEKDAY_RULES), AssistColumn("person", "첫 사람", FieldKinds.SELECT, source = Sources.RULE_MEMBERS)),
        )
        val rules = listOf(Candidate(Choice(100L, "평일 당직"), "평일 당직"), Candidate(Choice(200L, "주말 당직"), "주말 당직"))
        val members = mapOf(100L to listOf(people[0]), 200L to listOf(people[2]))
        fun input(words: String) = AssistCheck(
            listOf(f), { emptyList() }, { c, row -> if (c.id == "rule") rules else members[(row["rule"] as? Number)?.toLong()].orEmpty() }, emptyMap(),
            userWords = listOf(words), turnWords = listOf(words),
        )
        val ok = AssistValidator.check(input("평일 당직 첫 사람은 김가상"), mapOf("first" to listOf(mapOf("rule" to "평일 당직", "person" to "1대대 1중대 1반 병장 김가상"))))
        assertThat(ok.applied["first"]).isEqualTo(listOf(mapOf("rule" to 100L, "person" to 11L)))
        val bad = AssistValidator.check(input("주말 당직 첫 사람은 김가상"), mapOf("first" to listOf(mapOf("rule" to "주말 당직", "person" to "1대대 1중대 1반 병장 김가상"))))
        assertThat(bad.applied).isEmpty()
        assertThat(bad.violations).containsExactly(AssistViolation("first", 5))
    }

    @Test
    fun `바꾸기 제안은 화면 칸 하나에 하나다`() {
        val raw = mapOf("targets" to listOf(3), "chosenTypes" to mapOf("3" to listOf(30)))
        val req = AssistShapes.proposals("request", listOf(ProposedChange("targets", listOf(mapOf("departmentId" to 4L, "typeId" to 40L)), "값이 있는 칸")), raw)
        assertThat(req).hasSize(1)
        assertThat(req.single().field).isEqualTo("targets")
        assertThat(req.single().value).isEqualTo(mapOf("targets" to listOf(4L), "chosenTypes" to mapOf("4" to listOf(40L))))
        val box = mapOf("summary" to mapOf("mode" to "TEXT", "text" to "5단계", "fieldKey" to null))
        val ict = AssistShapes.proposals("ict.edit", listOf(ProposedChange("summary.text", "4단계", "값이 있는 칸"), ProposedChange("summary.mode", "TEXT", "값이 있는 칸"), ProposedChange("color.color", "RED", "값이 있는 칸")), box)
        assertThat(ict.map { it.field }).containsExactly("summary", "color")
        assertThat(ict[0].value).isEqualTo(mapOf("mode" to "TEXT", "text" to "4단계", "fieldKey" to null))
        assertThat(ict[1].value).isEqualTo(mapOf("color" to "RED"))
    }

    @Test
    fun `비워 두기로 한 칸은 채우지 않는다`() {
        val r = AssistValidator.check(input(listOf("행정반이요"), declined = setOf("place")), mapOf("place" to "행정반"))
        assertThat(r.applied).isEmpty()
    }

    @Test
    fun `되묻기는 체계 되묻기를 앞에, 필수 칸 먼저, 3개까지이고 찬 칸과 비우기로 한 칸은 묻지 않는다`() {
        val forced = listOf(AssistAsk("viewerPeople", "같은 이름이 여럿입니다", AssistAsk.CHOICE, listOf("가", "나")))
        val model = listOf(AssistAsk("memo", "비고는요?"), AssistAsk("place", "장소는요?"), AssistAsk("nothing", "?"), AssistAsk("viewerDepartments", "열람은요?"))
        val asks = AssistValidator.asks(fields, model, mapOf("detail" to "있음"), declined = setOf("serialNumber"), forced = forced)
        assertThat(asks).hasSize(3)
        assertThat(asks.map { it.field }).containsExactly("viewerPeople", "place", "status")
        assertThat(asks.map { it.field }).doesNotContain("detail", "serialNumber", "viewerDepartments", "nothing")
        // 형식 정보가 "자주"인 선택 칸도 체계가 묻는다(필수 칸 뒤)
        val more = AssistValidator.asks(fields, emptyList(), mapOf("place" to "a", "detail" to "b", "serialNumber" to "c", "status" to "IN_USE"), emptySet(), emptyList(), frequent = setOf("memo"))
        assertThat(more.map { it.field }).containsExactly("memo")
    }

    // ───────── 목록 칸 "더하기는 바로, 빼기는 확인" (사용자 결정) ─────────

    @Test
    fun `목록 칸은 지금 항목을 말에 없어도 통과시키고 새 항목만 검사해 걸린 것만 뺀다`() {
        val current = mapOf("viewerDepartments" to listOf(2))
        val r = AssistValidator.check(
            input(listOf("열람 범위에 통신소대도 넣어 줘"), current = current),
            mapOf("viewerDepartments" to listOf("1대대 1중대", "1대대 통신소대", "1대대 2중대")),
        )
        // 1대대 1중대: 지금 항목이라 통과, 통신소대: 말에 나옴, 2중대: 말에 없어 그 항목만 뺀다
        assertThat(r.applied["viewerDepartments"]).isEqualTo(listOf(2, 7L))
        assertThat(r.violations).containsExactly(AssistViolation("viewerDepartments", 8))
        assertThat(r.proposed).isEmpty()
        // 지금 항목이 빠지면(빼기) 바로 넣지 않고 칸 전체를 [바꾸기]로
        val removed = AssistValidator.check(input(listOf("열람 범위를 통신소대로 바꿔 줘"), current = current), mapOf("viewerDepartments" to listOf("1대대 통신소대")))
        assertThat(removed.applied).isEmpty()
        assertThat(removed.proposed.single().field).isEqualTo("viewerDepartments")
        assertThat(removed.proposed.single().value).isEqualTo(listOf(7L))
        // 빠짐이 없어도 이번 말에 칸 이름도 새 항목도 없으면 값이 있는 칸 규칙대로 [바꾸기]
        val quiet = AssistValidator.check(
            input(listOf("통신소대도 넣어", "응 그렇게 해 줘"), current = current),
            mapOf("viewerDepartments" to listOf("1대대 1중대", "1대대 통신소대")),
        )
        assertThat(quiet.applied).isEmpty()
        assertThat(quiet.proposed.map { it.field }).containsExactly("viewerDepartments")
        // 지금 값과 같은 목록은 바꾸지 않는다
        assertThat(AssistValidator.check(input(listOf("열람 범위 그대로"), current = current), mapOf("viewerDepartments" to listOf("1대대 1중대"))).applied).isEmpty()
    }

    @Test
    fun `결재 경로 줄은 지금 결재자를 통과시키고 새 결재자만 말에서 찾는다`() {
        val steps = AssistField(
            "steps", "결재 경로(단계 줄)", FieldKinds.ROWS, perm = true, aliases = listOf("결재 경로", "결재"),
            columns = listOf(AssistColumn("kind", "구분", FieldKinds.SELECT, choices = AssistScreens.STEP_KINDS), AssistColumn("accountId", "결재자", FieldKinds.SELECT, source = Sources.APPROVERS, perm = true)),
        )
        val now = mapOf("steps" to listOf(mapOf("kind" to "APPROVE", "accountId" to 13)))
        fun check(words: String, rows: List<Map<String, Any?>>) = AssistValidator.check(
            AssistCheck(listOf(steps), { emptyList() }, { c, _ -> c.choices?.map { Candidate(it, it.label) } ?: people }, now, userWords = listOf(words), turnWords = listOf(words)),
            mapOf("steps" to rows),
        )
        val ok = check(
            "결재 앞에 2반 김가상 검토 넣어 줘",
            listOf(
                mapOf("kind" to "검토", "accountId" to "1대대 1중대 2반 병장 김가상"),
                mapOf("kind" to "결재", "accountId" to "1대대 통신소대 중사 정가상"),
                mapOf("kind" to "검토", "accountId" to "1대대 1중대 1반 병장 김가상"),
            ),
        )
        // 정가상(지금 결재자)은 말에 없어도 통과, 1반 김가상은 말에 없어 그 줄만 뺀다
        assertThat(ok.applied["steps"]).isEqualTo(listOf(mapOf("kind" to "REVIEW", "accountId" to 12L), mapOf("kind" to "APPROVE", "accountId" to 13L)))
        assertThat(ok.violations).containsExactly(AssistViolation("steps", 8))
        // 지금 결재 줄을 빼면 [바꾸기]
        val removed = check("결재를 2반 김가상으로 바꿔 줘", listOf(mapOf("kind" to "결재", "accountId" to "1대대 1중대 2반 병장 김가상")))
        assertThat(removed.applied).isEmpty()
        assertThat(removed.proposed.single().value).isEqualTo(listOf(mapOf("kind" to "APPROVE", "accountId" to 12L)))
    }

    @Test
    fun `양식 질문은 형식에 맞지 않는 질문만 빼고 지금 질문을 빠뜨리면 바꾸기 제안이다`() {
        val f = AssistField("questions", "양식 질문", FieldKinds.QUESTIONS, maxRows = 30, aliases = listOf("질문", "양식"))
        val line = mapOf(
            "section" to null, "kind" to "SHORT_TEXT", "title" to "내선 번호", "description" to "", "required" to false, "options" to emptyList<Any>(),
            "shuffle" to false, "allowOther" to false, "scaleMin" to null, "scaleMax" to null, "rows" to emptyList<Any>(), "columns" to emptyList<Any>(), "validation" to null,
        )
        fun check(words: String, raw: List<Map<String, Any?>>) = AssistValidator.check(
            AssistCheck(listOf(f), { emptyList() }, { _, _ -> emptyList() }, mapOf("questions" to listOf(line)), userWords = listOf(words), turnWords = listOf(words)),
            mapOf("questions" to raw),
        )
        val added = mapOf("kind" to "객관식", "title" to "장애 종류", "options" to listOf("전화", "인터넷"))
        val r = check("질문에 장애 종류 객관식 더해 줘", listOf(mapOf("kind" to "단답", "title" to "내선 번호"), added, mapOf("kind" to "객관식", "title" to "잘못된 질문", "options" to emptyList<String>())))
        @Suppress("UNCHECKED_CAST")
        val out = r.applied["questions"] as List<Map<String, Any?>>
        assertThat(out.map { it["title"] }).containsExactly("내선 번호", "장애 종류")
        assertThat(r.violations).containsExactly(AssistViolation("questions", 5))
        val removed = check("질문을 장애 종류 하나로 해 줘", listOf(added))
        assertThat(removed.applied).isEmpty()
        assertThat(removed.proposed.map { it.field }).containsExactly("questions")
    }

    @Test
    fun `같은 이름이 여럿일 때 되묻기 단추의 전체 이름으로 답하면 그 사람으로 정한다`() {
        val twins = listOf(person(21, "1대대 1중대 1반", "병장", "이가상"), person(22, "1대대 2중대 1반", "병장", "이가상"))
        // 소속 끝 낱말(1반)이 같아 그 낱말로는 좁혀지지 않는다
        assertThat(AssistValidator.mentioned(twins, listOf("1반 이가상 넣어 줘"))).hasSize(2)
        val words = listOf("이가상도 열람에 넣어 줘", "1대대 2중대 1반 병장 이가상")
        assertThat(AssistValidator.mentioned(twins, words).map { it.choice.value }).containsExactly(22L)
        val f = AssistField("viewerPeople", "열람 범위(사람)", FieldKinds.MULTI, perm = true, source = Sources.PEOPLE)
        val r = AssistValidator.check(
            AssistCheck(listOf(f), { twins }, { _, _ -> emptyList() }, emptyMap(), userWords = words, turnWords = words.takeLast(1)),
            mapOf("viewerPeople" to listOf("1대대 2중대 1반 병장 이가상")),
        )
        assertThat(r.applied["viewerPeople"]).isEqualTo(listOf(22L))
        assertThat(r.forced).isEmpty()
    }

    @Test
    fun `칸별 답의 날짜·시각·달은 올해 기준 화면 형식으로 바꾸고 알아보지 못하면 null이다`() {
        val today = LocalDate.of(2026, 11, 7)
        assertThat(AssistValidator.parseDate("12월 25일", today)).isEqualTo("2026-12-25")
        assertThat(AssistValidator.parseDate("12/25", today)).isEqualTo("2026-12-25")
        assertThat(AssistValidator.parseDate("내일", today)).isEqualTo("2026-11-08")
        assertThat(AssistValidator.parseDate("2027.1.5", today)).isEqualTo("2027-01-05")
        assertThat(AssistValidator.parseDate("2월 30일", today)).isNull()
        assertThat(AssistValidator.parseDate("언젠가", today)).isNull()
        assertThat(AssistValidator.parseTime("오후 3시")).isEqualTo("15:00")
        assertThat(AssistValidator.parseTime("15시 30분")).isEqualTo("15:30")
        assertThat(AssistValidator.parseTime("9:05")).isEqualTo("09:05")
        assertThat(AssistValidator.parseTime("3시 반")).isEqualTo("03:30")
        assertThat(AssistValidator.parseTime("25시")).isNull()
        assertThat(AssistValidator.parseMonth("12월", today)).isEqualTo("2026-12")
        assertThat(AssistValidator.parseMonth("다음 달", today)).isEqualTo("2026-12")
        assertThat(AssistValidator.parseMonth("2027년 1월", today)).isEqualTo("2027-01")
        assertThat(AssistValidator.parseMonth("13월", today)).isNull()
        // 근무표 월별 채우기의 달 칸은 YYYY-MM만 받는다
        val month = AssistField("month", "달(YYYY-MM)", FieldKinds.MONTH, required = true)
        val monthInput = AssistCheck(listOf(month), { emptyList() }, { _, _ -> emptyList() }, emptyMap())
        assertThat(AssistValidator.check(monthInput, mapOf("month" to "2026-12")).applied).isEqualTo(mapOf("month" to "2026-12"))
        assertThat(AssistValidator.check(monthInput, mapOf("month" to "12월")).violations).containsExactly(AssistViolation("month", 5))
        assertThat(AssistScreens.find("duty.fill")!!.fields.first { it.id == "month" }.kind).isEqualTo(FieldKinds.MONTH)
    }

    // ───────── 재검토 결함 고침 ─────────

    private fun question(kind: String, title: String, options: List<String> = emptyList(), description: String = "") = mapOf(
        "section" to null, "kind" to kind, "title" to title, "description" to description, "required" to false,
        "options" to options.map { mapOf("label" to it, "goTo" to null) }, "shuffle" to false, "allowOther" to false,
        "scaleMin" to null, "scaleMax" to null, "rows" to emptyList<Any>(), "columns" to emptyList<Any>(), "validation" to null,
    )

    @Test
    fun `결함1 지금 양식 질문은 다시 검사하지 않고 통과하고, 새 질문은 저장 규칙과 질문 수 한도로 검사한다`() {
        val f = AssistScreens.find("worktype.form")!!.fields.single()
        assertThat(f.maxRows).isEqualTo(kr.ictwork.form.FormRules.MAX_QUESTIONS)
        // 지금 질문: 제목 없는 안내문(설명만), 선택지 하나인 객관식 (저장 규칙에 맞는 질문)
        val guide = question("GUIDE", "", description = "아래 내용을 적어 주세요")
        val single = question("MULTIPLE_CHOICE", "장애 종류", listOf("전화"))
        val now = mapOf("questions" to listOf(guide, single))
        fun check(raw: List<Map<String, Any?>>, current: Map<String, Any?> = now) = AssistValidator.check(
            AssistCheck(listOf(f), { emptyList() }, { _, _ -> emptyList() }, current, userWords = listOf("질문 더해 줘"), turnWords = listOf("질문 더해 줘")),
            mapOf("questions" to raw),
        )
        // 모델이 지금 질문을 망가뜨려 옮겨도(안내문 설명 빠짐·선택지 빠짐) 지금 질문 그대로 남는다
        val r = check(listOf(mapOf("kind" to "안내문", "title" to ""), mapOf("kind" to "객관식", "title" to "장애 종류"), mapOf("kind" to "드롭다운", "title" to "새 질문", "options" to listOf("하나"))))
        @Suppress("UNCHECKED_CAST")
        val out = r.applied["questions"] as List<Map<String, Any?>>
        assertThat(out.map { it["title"] }).containsExactly("", "장애 종류", "새 질문")
        assertThat(out[0]["description"]).isEqualTo("아래 내용을 적어 주세요")
        assertThat(out[1]["options"]).isEqualTo(listOf(mapOf("label" to "전화", "goTo" to null)))
        // 새 질문: 선택지 하나도 저장 규칙대로 받고, 제목·설명이 없는 안내문과 선택지 없는 객관식은 뺀다
        assertThat(r.violations).isEmpty()
        val bad = check(listOf(guide, single, mapOf("kind" to "안내문", "title" to ""), mapOf("kind" to "객관식", "title" to "빈 질문")))
        assertThat(bad.violations).containsExactly(AssistViolation("questions", 5), AssistViolation("questions", 5))
        // 질문 수 한도: 지금 질문 50개는 모두 남고 새 질문만 넘친 것을 뺀다
        val fifty = (1..50).map { question("SHORT_TEXT", "질문$it") }
        val full = check(fifty.map { mapOf("kind" to "단답", "title" to it["title"]) } + mapOf("kind" to "단답", "title" to "넘친 질문"), mapOf("questions" to fifty))
        assertThat(full.violations).containsExactly(AssistViolation("questions", 5))
        assertThat(full.applied).isEmpty() // 지금 목록과 같아 바꾸지 않는다
        // 그 종류에 맞지 않는 응답 검증만 뺀다
        val v = check(listOf(guide, single, mapOf("kind" to "장문", "title" to "설명", "validation" to mapOf("type" to "전화번호"))))
        @Suppress("UNCHECKED_CAST")
        assertThat((v.applied["questions"] as List<Map<String, Any?>>).last()["validation"]).isNull()
    }

    @Test
    fun `결함1 줄 수 상한 - 장비 SW·네트워크 줄은 지금 줄을 자르지 않는다`() {
        val fields = AssistScreens.find("equipment")!!.fields
        val software = fields.first { it.id == "software" }
        assertThat(software.openRows).isTrue()
        assertThat(fields.first { it.id == "networks" }.openRows).isTrue()
        val now = (1..25).map { mapOf("name" to "SW$it") }
        val r = AssistValidator.check(
            AssistCheck(listOf(software), { emptyList() }, { _, _ -> emptyList() }, mapOf("software" to now), userWords = listOf("SW에 한글 더해 줘"), turnWords = listOf("SW에 한글 더해 줘")),
            mapOf("software" to now + mapOf("name" to "한글")),
        )
        @Suppress("UNCHECKED_CAST")
        assertThat((r.applied["software"] as List<*>)).hasSize(26)
        assertThat(r.proposed).isEmpty()
    }

    @Test
    fun `결함5 명시 날짜를 상대 날짜보다 먼저 보고, 내일모레는 +2, 지난 날짜는 다음 해, 낮·밤 시각`() {
        val today = LocalDate.of(2026, 11, 7)
        assertThat(AssistValidator.parseDate("내일모레", today)).isEqualTo("2026-11-09")
        assertThat(AssistValidator.parseDate("모레", today)).isEqualTo("2026-11-09")
        assertThat(AssistValidator.parseDate("내일 말고 11월 20일", today)).isEqualTo("2026-11-20")
        assertThat(AssistValidator.parseDate("오늘 정한 12/25", today)).isEqualTo("2026-12-25")
        assertThat(AssistValidator.parseDate("1월 5일", LocalDate.of(2026, 12, 20))).isEqualTo("2027-01-05")
        assertThat(AssistValidator.parseDate("1/5", LocalDate.of(2026, 12, 20))).isEqualTo("2027-01-05")
        assertThat(AssistValidator.parseDate("11월 7일", today)).isEqualTo("2026-11-07")
        assertThat(AssistValidator.parseTime("낮 2시")).isEqualTo("14:00")
        assertThat(AssistValidator.parseTime("낮 12시")).isEqualTo("12:00")
        assertThat(AssistValidator.parseTime("밤 12시")).isEqualTo("00:00")
        assertThat(AssistValidator.parseTime("밤 9시")).isEqualTo("21:00")
        assertThat(AssistValidator.parseTime("오전 12시")).isEqualTo("00:00")
    }

    @Test
    fun `결함7 같은 차례에 모델이 채운 칸의 형식 안내 되묻기는 뺀다`() {
        val time = AssistField("endTime", "끝 시각", FieldKinds.TIME, required = true)
        val format = AssistAsk("endTime", "끝 시각을 시각으로 알아보지 못했습니다.", format = true)
        assertThat(AssistValidator.asks(listOf(time), emptyList(), mapOf("endTime" to "17:00"), emptySet(), listOf(format), filled = setOf("endTime"))).isEmpty()
        // 모델이 채우지 않았으면(화면 처음 값만 있음) 다시 묻는다
        assertThat(AssistValidator.asks(listOf(time), emptyList(), mapOf("endTime" to "10:00"), emptySet(), listOf(format)).map { it.field }).containsExactly("endTime")
    }

    @Test
    fun `결함13 날짜·시각은 사용자 말에서 나온 값이나 화면 처음 값만, 양식 선택지는 선택지 글이 말에 있어야 한다`() {
        val date = AssistField("end", "끝 날짜", FieldKinds.DATE)
        val time = AssistField("time", "양식: 시각", FieldKinds.TIME)
        val today = LocalDate.of(2026, 11, 7) // 토요일
        fun check(words: String, raw: Map<String, Any?>, current: Map<String, Any?> = emptyMap()) =
            AssistValidator.check(AssistCheck(listOf(date, time), { emptyList() }, { _, _ -> emptyList() }, current, userWords = listOf(words), turnWords = listOf(words), today = today), raw)
        assertThat(check("22일까지 해 줘", mapOf("end" to "2026-11-22")).applied).containsKey("end")
        assertThat(check("내일모레까지", mapOf("end" to "2026-11-09")).applied).containsKey("end")
        assertThat(check("금요일까지", mapOf("end" to "2026-11-13")).applied).containsKey("end")
        assertThat(check("12월 22일까지", mapOf("end" to "2026-11-22")).violations).containsExactly(AssistViolation("end", 6))
        assertThat(check("전화 점검", mapOf("time" to "15:30")).violations).containsExactly(AssistViolation("time", 6))
        assertThat(check("오후 3시 반에", mapOf("time" to "15:30")).applied).containsKey("time")
        // 화면 처음 값이면 받는다(같은 값이라 바꾸지 않음)
        assertThat(check("전화 점검", mapOf("time" to "15:30"), mapOf("time" to "15:30")).violations).isEmpty()
        // 양식 체크박스·객관식: 칸 이름 낱말만으로는 고르지 않는다
        val item = kr.ictwork.form.FormItem(
            "c", "CHECKBOXES", "점검 항목", options = listOf(kr.ictwork.form.FormOption("o1", "회선 점검"), kr.ictwork.form.FormOption("o2", "단말 교체"), kr.ictwork.form.FormOption("o3", "배터리 교환")),
        )
        val boxes = AssistScreens.itemFields("form.1.2.c", "양식(가상전화): 점검 항목", item, false).single()
        val one = AssistScreens.itemFields("form.1.2.k", "양식(가상전화): 종류", kr.ictwork.form.FormItem("k", "DROPDOWN", "종류", options = listOf(kr.ictwork.form.FormOption("x", "일반"))), true).single()
        val r = AssistValidator.check(
            AssistCheck(listOf(boxes, one), { f -> f.choices!!.map { Candidate(it, it.label) } }, { _, _ -> emptyList() }, emptyMap(), userWords = listOf("회선 점검해 줘"), turnWords = listOf("회선 점검해 줘")),
            mapOf("form.1.2.c" to listOf("회선 점검", "단말 교체", "배터리 교환"), "form.1.2.k" to "일반"),
        )
        assertThat(r.applied["form.1.2.c"]).isEqualTo(listOf("o1"))
        assertThat(r.violations).containsExactly(AssistViolation("form.1.2.c", 7), AssistViolation("form.1.2.c", 7))
        // 선택지가 하나뿐인 필수 칸은 화면이 정한 것이라 받는다
        assertThat(r.applied["form.1.2.k"]).isEqualTo("x")
    }

    @Test
    fun `결함11 구조화 출력 스키마에 글 길이·줄 수 한도가 있다`() {
        val mapper = com.fasterxml.jackson.databind.ObjectMapper()
        val ops = AssistScreens.find("diagram")!!.fields.single()
        val title = AssistField("title", "제목", FieldKinds.TEXT, max = 100)
        val schema = AssistPrompts.fillSchema(mapper, listOf(ops, title), { listOf("단말-7") }, { emptyList() }, listOf("단말-7"), emptyList())
        val values = schema.path("properties").path("values").path("properties")
        val item = values.path("ops").path("items").path("properties")
        assertThat(values.path("ops").path("maxItems").asInt()).isEqualTo(ops.maxRows)
        assertThat(item.path("name").path("maxLength").asInt()).isEqualTo(AssistValidator.OPS_NAME_MAX)
        assertThat(item.path("text").path("maxLength").asInt()).isEqualTo(AssistValidator.OPS_TEXT_MAX)
        assertThat(values.path("title").path("maxLength").asInt()).isEqualTo(100)
        assertThat(schema.path("properties").path("message").path("maxLength").asInt()).isEqualTo(AssistPrompts.MAX_MESSAGE)
    }

    @Test
    fun `결함15 채우기 규칙에 칸 이름 뒤의 값과 최종 단추 부탁의 칸 값 규칙이 있다`() {
        val system = AssistPrompts.fillSystem("2026-11-07 (토)", "가상 사용자", "인원 수정 창", "[저장]", "")
        assertThat(system).contains("칸 이름 바로 뒤에 온 이름·값은 그 칸의 값", "칸은 채우고 단추만 사용자에게 맡긴다", "되묻지 말고 채운다")
        assertThat(AssistPrompts.intentSystem("2026-11-07 (토)", "가상 사용자")).contains("action은 새로 만들기")
    }

    // ───────── 2차 측정 결함 ─────────

    @Test
    fun `2차-2 구성도 동작은 맞지 않는 동작과 그에 기대는 동작만 빼고 나머지는 남긴다`() {
        val ops = AssistScreens.find("diagram")!!.fields.single()
        val pool = listOf(Candidate(Choice(1L, "단말-7 (SN1)"), "단말-7"), Candidate(Choice(2L, "라우터 (SN2)"), "라우터"))
        val raw = listOf(
            mapOf("op" to "place", "equipment" to "단말-7 (SN1)"),
            mapOf("op" to "place", "equipment" to "없는장비"),
            mapOf("op" to "connect", "from" to "단말-7 (SN1)", "to" to "없는장비"),
            mapOf("op" to "zone", "name" to "A구역", "members" to listOf("없는장비")),
            mapOf("op" to "zone", "name" to "B구역", "members" to listOf("단말-7 (SN1)")),
            mapOf("op" to "text", "text" to "회선 메모"),
            mapOf("op" to "jump"),
        )
        val r = AssistValidator.check(AssistCheck(listOf(ops), { pool }, { _, _ -> emptyList() }, emptyMap()), mapOf("ops" to raw))
        @Suppress("UNCHECKED_CAST")
        val out = r.applied["ops"] as List<Map<String, Any?>>
        assertThat(out.map { it["op"] }).containsExactly("place", "zone", "text")
        assertThat(out[1]["members"]).isEqualTo(listOf(1L))
        // 로그에는 동작 번호와 규칙 번호만
        assertThat(r.violations).containsExactly(AssistViolation("ops[2]", 5), AssistViolation("ops[3]", 5), AssistViolation("ops[4]", 5), AssistViolation("ops[7]", 5))
        // 모델 글 대신 남은 동작으로 만든 체계 요약
        assertThat(AssistValidator.opsSummary(out, 4)).isEqualTo("넣을 동작: 장비 놓기 1개, 글상자 1개, 구역 1개. 맞지 않는 동작 4개는 뺐습니다. 미리보기를 확인해 주세요.")
        assertThat(AssistValidator.opsSummary(null, 2)).startsWith("넣을 수 있는 동작이 없습니다.")
    }

    @Test
    fun `2차-3 의도 고르기 프롬프트는 넣어 줘·잡아 줘를 만들기로 밝히고 개인 일정 만들기 예가 있다`() {
        val system = AssistPrompts.intentSystem("2026-11-07 (토)", "가상 사용자")
        assertThat(system).contains("create(넣어 줘·잡아 줘·추가해 줘·등록해 줘", "screen=calendar, action=create")
    }

    // ───────── F12 개정: 근거 없이 채우지 않음 (사용자 결정 4~7) ─────────

    @Test
    fun `F12-4 글 칸은 사용자 말 하나에 이어진 그대로만 받고, 끝 문장 부호와 빈칸 차이는 같다고 본다`() {
        val words = listOf("행정반 전화가  안 돼요", "장소는 행정반.")
        assertThat(AssistValidator.verbatim("행정반 전화가 안 돼요.", words)).isTrue()
        assertThat(AssistValidator.verbatim(" 행정반 ", words)).isTrue()
        // 다듬거나 덧붙인 글, 두 말에 걸친 글은 받지 않는다
        assertThat(AssistValidator.verbatim("행정반 전화 고장", words)).isFalse()
        assertThat(AssistValidator.verbatim("행정반 전화가 안 돼요 장소는", words)).isFalse()
        val r = AssistValidator.check(input(words), mapOf("detail" to "행정반 전화가 안 돼요", "memo" to "행정반 전화 통화 불가", "place" to "행정반"))
        assertThat(r.applied).isEqualTo(mapOf("detail" to "행정반 전화가 안 돼요", "place" to "행정반"))
        assertThat(r.violations).containsExactly(AssistViolation("memo", 6))
        // 화면 처음 값은 그대로 둔다(같은 값이라 바꾸지 않음, 위반 아님)
        assertThat(AssistValidator.check(input(listOf("응"), current = mapOf("memo" to "예전 비고")), mapOf("memo" to "예전 비고")).violations).isEmpty()
        // 줄 칸의 새 글 열도 같다
        val sw = AssistScreens.find("equipment")!!.fields.first { it.id == "software" }
        val rows = AssistValidator.check(
            AssistCheck(listOf(sw), { emptyList() }, { _, _ -> emptyList() }, emptyMap(), userWords = listOf("SW에 한글 넣어 줘")),
            mapOf("software" to listOf(mapOf("name" to "한글 2024"))),
        )
        assertThat(rows.violations).containsExactly(AssistViolation("software", 6))
    }

    @Test
    fun `F12-5 고르는 칸은 선택지 이름이나 별칭이 말에 있어야 하고 칸 이름만으로는 고르지 않는다`() {
        val colors = AssistValidator.check(input(listOf("색 바꿔 줘")), mapOf("color.color" to "빨강"))
        assertThat(colors.violations).containsExactly(AssistViolation("color.color", 7))
        // 별칭(빨간)으로 말해도 그 선택지다
        assertThat(AssistValidator.check(input(listOf("빨간색으로 해 줘")), mapOf("color.color" to "빨강")).applied).isEqualTo(mapOf("color.color" to "RED"))
        // 예·아니오 칸은 그 칸의 뜻 낱말(중요)이 "예"의 별칭이다
        val important = AssistScreens.find("calendar")!!.fields.first { it.id == "important" }
        fun yes(words: String) = AssistValidator.check(
            AssistCheck(listOf(important), { f -> f.choices!!.map { Candidate(it, it.label) } }, { _, _ -> emptyList() }, emptyMap(), userWords = listOf(words), turnWords = listOf(words)),
            mapOf("important" to "예"),
        ).applied
        assertThat(yes("중요한 일정이야")).isEqualTo(mapOf("important" to true))
        assertThat(yes("휴가 잡아 줘")).isEmpty()
        // 서버 후보(부서): 사용자 말에 나온 부서 이름이면 받는다
        val dept = AssistField("dept", "부서", FieldKinds.SELECT, required = true, source = Sources.ORG)
        fun deptOf(words: String) = AssistValidator.check(
            AssistCheck(listOf(dept), { org }, { _, _ -> emptyList() }, emptyMap(), userWords = listOf(words), turnWords = listOf(words)), mapOf("dept" to "1대대 통신소대"),
        )
        assertThat(deptOf("통신소대 박스 추가해 줘").applied).isEqualTo(mapOf("dept" to 7L))
        assertThat(deptOf("박스 추가해 줘").violations).containsExactly(AssistViolation("dept", 7))
        // 여러 값 칸(요일): 말한 요일만
        val days = AssistField("days", "적용 날", FieldKinds.MULTI, choices = AssistScreens.DUTY_DAYS)
        val d = AssistValidator.check(
            AssistCheck(listOf(days), { f -> f.choices!!.map { Candidate(it, it.label) } }, { _, _ -> emptyList() }, emptyMap(), userWords = listOf("토 일 근무")),
            mapOf("days" to listOf("토", "일", "공휴일")),
        )
        assertThat(d.applied["days"]).isEqualTo(listOf("SAT", "SUN"))
        assertThat(d.violations).containsExactly(AssistViolation("days", 7))
        // 줄 칸의 고르는 열: 새 값은 말에 있어야 한다
        val route = AssistScreens.find("route")!!.fields.first { it.id == "steps" }
        val steps = AssistValidator.check(
            AssistCheck(listOf(route), { emptyList() }, { c, _ -> c.choices!!.map { Candidate(it, it.label) } }, emptyMap(), userWords = listOf("대대장 결재 하나로")),
            mapOf("steps" to listOf(mapOf("position" to "대대장", "kind" to "결재"), mapOf("position" to "반장", "kind" to "검토"))),
        )
        assertThat(steps.applied["steps"]).isEqualTo(listOf(mapOf("position" to "BN_CMDR", "kind" to "APPROVE")))
        assertThat(steps.violations).containsExactly(AssistViolation("steps", 7))
    }

    @Test
    fun `F12-6 받는 부서 짝은 부서 이름이나 유형 이름을 말했거나 단추로 고른 짝만 받는다`() {
        val targets = AssistField("targets", "받는 부서·업무 유형", FieldKinds.PAIRS, required = true, perm = true, source = Sources.RECEIVERS)
        val pair = Candidate(Choice(mapOf("departmentId" to 4L, "typeId" to 40L), "1대대 1중대 2반 · 가상전화"), "가상전화", recommended = true, path = "1대대 1중대 2반")
        fun check(words: String) = AssistValidator.check(
            AssistCheck(listOf(targets), { listOf(pair) }, { _, _ -> emptyList() }, emptyMap(), userWords = listOf(words), turnWords = listOf(words)),
            mapOf("targets" to listOf("1대대 1중대 2반 · 가상전화")),
        )
        // 문제만 말함(추천 1순위라도 넣지 않음)
        assertThat(check("전화 회선 관련 업무 요청을 하고 싶어").applied).isEmpty()
        assertThat(check("전화 회선 관련 업무 요청을 하고 싶어").violations).containsExactly(AssistViolation("targets", 8))
        // 부서 이름·유형 이름·단추 답(짝 표시 이름)
        assertThat(check("2반에 전화 고쳐 달라고 요청해 줘").applied["targets"]).isEqualTo(listOf(mapOf("departmentId" to 4L, "typeId" to 40L)))
        assertThat(check("가상전화 업무로 요청해 줘").applied).containsKey("targets")
        assertThat(check("1대대 1중대 2반 · 가상전화").applied).containsKey("targets")
    }

    @Test
    fun `F12-7 조사는 받침에 맞추고 영문·숫자로 끝나면 둘 다 적는다`() {
        assertThat(AssistPrompts.josa("요청 장소", "을", "를")).isEqualTo("요청 장소를")
        assertThat(AssistPrompts.josa("세부 내용", "을", "를")).isEqualTo("세부 내용을")
        assertThat(AssistPrompts.josa("받는 부서", "이", "가")).isEqualTo("받는 부서가")
        assertThat(AssistPrompts.josa("희망 일자(1~3지망)", "을", "를")).isEqualTo("희망 일자(1~3지망)을")
        assertThat(AssistPrompts.josa("HW 버전", "을", "를")).isEqualTo("HW 버전을")
        assertThat(AssistPrompts.josa("달(YYYY-MM)", "을", "를")).isEqualTo("달(YYYY-MM)을(를)")
        assertThat(AssistPrompts.josa("IP", "이", "가")).isEqualTo("IP이(가)")
        // 되묻기 기본 질문
        val asks = AssistValidator.asks(fields, emptyList(), emptyMap(), emptySet(), emptyList())
        assertThat(asks.first { it.field == "place" }.question).isEqualTo("요청 장소를 알려 주세요.")
    }

    @Test
    fun `F12-7 화면마다 최종 단추 이름은 화면의 실제 단추와 같다`() {
        fun final(id: String, target: Long? = null) = AssistScreens.find(id)!!.finalOf(target)
        assertThat(final("calendar")).isEqualTo("[추가]")
        assertThat(final("calendar", 1L)).isEqualTo("[저장]")
        assertThat(final("request")).isEqualTo("[결재 올리기]")
        assertThat(final("register")).isEqualTo("[결재 올리기]")
        assertThat(final("request.edit", 1L)).isEqualTo("[다시 올리기]")
        assertThat(final("equipment")).isEqualTo("[저장]")
        assertThat(final("duty.holiday", 1L)).isEqualTo("[더하기]")
        assertThat(final("duty.roster", 1L)).isEqualTo("[추가]")
        assertThat(final("ict.new")).isEqualTo("[추가]")
        assertThat(final("personnel.role", 1L)).isEqualTo("[역할 주기]")
        assertThat(final("docbox.folder", 1L)).isEqualTo("[만들기]")
        // 채우기 규칙의 안내는 조사가 필요 없는 꼴
        assertThat(AssistPrompts.fillSystem("2026-11-07 (토)", "가상 사용자", "개인 일정 창", "[추가]", "")).contains("화면에서 [추가] 단추를 직접 눌러 주세요").doesNotContain("]를")
    }

    @Test
    fun `F12-1 작업 부탁 낱말과 화면 열기 낱말을 가린다`() {
        assertThat(AssistPrompts.asksTask("전화 회선 관련 업무 요청을 하고 싶어")).isTrue()
        assertThat(AssistPrompts.asksTask("단말-7 비고 고쳐 줘")).isTrue()
        assertThat(AssistPrompts.asksTask("우리 장비 목록 보고 싶어")).isFalse()
        assertThat(AssistPrompts.asksTask("인원현황 탭 보여줘")).isFalse()
        assertThat(AssistPrompts.asksNavigate("인원현황 탭 보여줘")).isTrue()
        assertThat(AssistPrompts.asksNavigate("문서함 열어 줘")).isTrue()
        assertThat(AssistPrompts.asksNavigate("행정반 전화가 안 돼")).isFalse()
        // 의도 스키마: intent가 필수이고 qa·navigate·task
        val schema = AssistPrompts.intentSchema(com.fasterxml.jackson.databind.ObjectMapper())
        assertThat(schema.path("properties").path("intent").path("enum").map { it.asText() }).containsExactly("qa", "navigate", "task")
        assertThat(schema.path("required").map { it.asText() }).contains("intent", "screen")
        assertThat(AssistPrompts.intentSystem("2026-11-07 (토)", "가상 사용자")).contains("기본은 qa", "\"인원현황 탭 보여줘\" → intent=navigate", "장비=equipment")
    }
}
