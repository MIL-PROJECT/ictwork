package kr.ictwork.ai

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** 답 글 규칙 (AiAnswerRules): 글로 쓴 도구 호출 읽기, 흘려보낼 끝, 일반 답 표시·보일 출처 (실제 모델 평가에서 나온 꼴) */
class AiAnswerRulesTest {
    private val mapper = jacksonObjectMapper()

    private fun found(label: String, text: String, cosine: Double, header: String? = null) =
        AiFound(AiSourceRef(AiSourceTypes.FILE, 1, label = label), header, text, cosine)

    @Test
    fun `따옴표 없는 이름·값으로 쓴 도구 호출을 읽는다`() {
        val calls = AiAnswerRules.textCalls("""query_people {mode: list, department: "1대대 1중대 1반", position: "반장"}""")
        assertThat(calls).hasSize(1)
        assertThat(calls[0].name).isEqualTo("query_people")
        assertThat(mapper.writeValueAsString(calls[0].arguments)).isEqualTo("""{"mode":"list","department":"1대대 1중대 1반","position":"반장"}""")
    }

    @Test
    fun `tool_call 꼴·name·arguments JSON·괄호·이름=값 꼴을 읽는다`() {
        val tagged = AiAnswerRules.textCalls("""<tool_call>{"name": "query_equipment", "arguments": {"mode": "count", "status": "고장", "include_sub": true}}</tool_call>""")
        assertThat(tagged.single().name).isEqualTo("query_equipment")
        assertThat(tagged.single().arguments.path("include_sub").asBoolean()).isTrue()
        val json = AiAnswerRules.textCalls("확인합니다.\n```json\n{\"name\": \"query_work\", \"arguments\": \"{\\\"mode\\\": \\\"count\\\"}\"}\n```")
        assertThat(json.single().name).isEqualTo("query_work")
        assertThat(json.single().arguments.path("mode").asText()).isEqualTo("count")
        val call = AiAnswerRules.textCalls("query_equipment({'mode': 'count', 'department': '1대대 3대대',})")
        assertThat(call.single().arguments.path("department").asText()).isEqualTo("1대대 3대대")
        val pairs = AiAnswerRules.textCalls("""query_people, mode=list, department="1대대 1중대 2반", position="반장"""")
        assertThat(mapper.writeValueAsString(pairs.single().arguments)).isEqualTo("""{"mode":"list","department":"1대대 1중대 2반","position":"반장"}""")
    }

    @Test
    fun `도구 이름이 없거나 읽을 수 없는 글은 도구 호출이 아니다`() {
        assertThat(AiAnswerRules.textCalls("1대대 1중대에 고장 장비는 1대입니다.")).isEmpty()
        assertThat(AiAnswerRules.textCalls("""{"name": "rm", "arguments": {}}""")).isEmpty()
        assertThat(AiAnswerRules.textCalls("query_people {mode: list")).isEmpty()
        assertThat(AiAnswerRules.textCalls("query_peoplex {mode: list}")).isEmpty()
    }

    @Test
    fun `도구 호출 표시와 그 앞부분은 보내지 않고 앞 글만 보낸다`() {
        assertThat(AiAnswerRules.streamEnd("1대대 1중대에 고장 장비는", 0)).isEqualTo("1대대 1중대에 고장 장비는".length)
        assertThat(AiAnswerRules.streamEnd("query_peo", 0)).isZero()
        assertThat(AiAnswerRules.streamEnd("확인합니다. query_people {mode", 0)).isEqualTo("확인합니다.".length)
        assertThat(AiAnswerRules.streamEnd("확인합니다. <tool", 3)).isEqualTo("확인합니다.".length)
        assertThat(AiAnswerRules.streamEnd("확인합니다. q", 0)).isEqualTo("확인합니다.".length)
    }

    @Test
    fun `일반 답 문장으로 시작할 수 있는 글은 차례가 끝날 때까지 잡아 둔다`() {
        assertThat(AiAnswerRules.streamEnd("체계 자료", 0)).isZero()
        assertThat(AiAnswerRules.streamEnd("**체계 자료에 근거하지 않은 일반 답입니다.** 라우터는", 0)).isZero()
        // 찾지 못했다는 답은 갈라지는 곳부터 보낸다
        assertThat(AiAnswerRules.streamEnd("체계 자료에서 찾지 못했습니다.", 0)).isEqualTo("체계 자료에서 찾지 못했습니다.".length)
    }

    @Test
    fun `체계 자료로 한 답이면 일반 답 문장을 지우고 아무것도 쓰지 않은 일반 답이면 남긴다`() {
        val guide = found("발전기_정비지침.hwp", "엔진 오일: 운전 250시간마다 또는 6개월마다 교환한다. 둘 중 먼저 오는 때를 따른다.", 0.67)
        val answer = "체계 자료에 근거하지 않은 일반 답입니다. 발전기 엔진 오일은 운전 250시간마다 또는 6개월마다 교환해야 합니다."
        assertThat(AiAnswerRules.keepGeneral(answer, "발전기 엔진 오일은 언제 갈아야 해?", listOf(guide), false)).isFalse()
        assertThat(AiAnswerRules.stripGeneral(answer)).isEqualTo("발전기 엔진 오일은 운전 250시간마다 또는 6개월마다 교환해야 합니다.")
        assertThat(AiAnswerRules.stripGeneral("**체계 자료에 근거하지 않은 일반 답입니다.**\n무전기 점검은 매월 1회입니다.")).isEqualTo("무전기 점검은 매월 1회입니다.")
        // 가까운 글이 없고(코사인 낮음) 답이 그 글을 쓰지 않았으면 일반 답
        val far = found("비상_연락_절차.hwpx", "통신 두절이 생기면 먼저 대대 지휘통제실에 유선으로 알린다.", 0.34)
        val general = "체계 자료에 근거하지 않은 일반 답입니다. 라우터는 네트워크를 잇는 장비로 패킷을 목적지로 전달합니다."
        assertThat(AiAnswerRules.keepGeneral(general, "라우터가 뭐야?", listOf(far), false)).isTrue()
        assertThat(AiAnswerRules.keepGeneral(general, "라우터가 뭐야?", emptyList(), false)).isTrue()
        // 도구를 썼으면 체계 자료로 한 답
        assertThat(AiAnswerRules.keepGeneral(general, "라우터가 뭐야?", emptyList(), true)).isFalse()
        // 질문과 맞는 글(코사인 0.6 이상)이 있으면 체계 자료로 한 답
        assertThat(AiAnswerRules.keepGeneral("체계 자료에 근거하지 않은 일반 답입니다. 무전기는 매월 점검합니다.", "무전기 점검 주기", listOf(found("통신장비_점검표.xlsx", "외관 매월 1회", 0.66)), false)).isFalse()
    }

    @Test
    fun `답이 쓴 글만 출처로 고른다(이름이 나오거나 업무 상태 머리까지 글이 겹침)`() {
        val pdf = found("전화회선_이동_작업안내.pdf", "주의할 점 1. 작업 전에 교환기 설정을 백업한다. 2. 회선 번호표를 양쪽 끝에 붙인 뒤 선을 뽑는다.", 0.76)
        val minutes = found("회의록_2026-10.docx", "10월 남은 기간의 야간 점검은 1중대 1반 반장이 맡는다.", 0.63)
        val work = found("행정반 전화 회선 이동·증설", "요청 제목: 행정반 전화 회선 이동", 0.67, "[지금 업무 상태] 행정반 전화 회선 이동·증설 — 받는 부서 1대대 1중대 1반: 계획, 작업 일자 2026-11-01 ~ 2026-11-04")
        val list = listOf(pdf, minutes, work)
        assertThat(AiAnswerRules.usedFound("작업 전에 교환기 설정을 백업하고 회선 번호표를 붙입니다.", "전화 회선 이동 작업할 때 주의할 점은?", list)).containsExactly(0)
        assertThat(AiAnswerRules.usedFound("야간 점검은 1반 반장이 맡습니다. (출처: 회의록_2026-10.docx)", "야간 점검은 누가?", list)).containsExactly(1)
        assertThat(AiAnswerRules.usedFound("계획 상태이고 작업 일자는 2026-11-01 ~ 2026-11-04입니다.", "행정반 전화 회선 이동 건 어디까지 됐어?", list)).containsExactly(2)
        assertThat(AiAnswerRules.usedFound("라우터는 네트워크 장비입니다.", "라우터가 뭐야?", list)).isEmpty()
    }

    @Test
    fun `0건 도구 결과는 답이 찾은 글을 출처로 적었으면 쓰지 않은 것이고 오류는 늘 쓴 것이다`() {
        // 실제 모델 평가 4번: 회의록 질문에 업무 도구를 불러 0건을 받고 회의록으로 답함
        val minutes = found("회의록_2026-10.docx", "야간 점검은 1중대 1반 반장이 맡는다.", 0.6)
        val zero = mapOf("기준" to "등록된 업무", "개수" to 0)
        assertThat(AiAnswerRules.usedToolResult("야간 점검은 1반 반장이 맡기로 했습니다. (출처: 회의록_2026-10.docx)", zero, listOf(minutes))).isFalse()
        assertThat(AiAnswerRules.usedToolResult("조건에 맞는 업무는 0건입니다.", zero, listOf(minutes))).isTrue()
        assertThat(AiAnswerRules.usedToolResult("없습니다. (출처: 장비 목록)", zero, listOf(minutes))).isTrue()
        assertThat(AiAnswerRules.usedToolResult("볼 수 있는 범위 밖이라 답할 수 없습니다. (출처: 회의록_2026-10.docx)", mapOf("오류" to "범위 밖"), listOf(minutes))).isTrue()
    }

    @Test
    fun `도구 답 끝의 출처 표기는 지우고 찾은 글의 출처 이름이면 남긴다`() {
        // "(출처"부터는 차례가 끝날 때까지 잡아 둔다
        assertThat(AiAnswerRules.streamEnd("고장 장비는 2대입니다. (출처", 0)).isEqualTo("고장 장비는 2대입니다.".length)
        assertThat(AiAnswerRules.streamEnd("고장 장비는 2대입니다. (", 0)).isEqualTo("고장 장비는 2대입니다.".length)
        val memo = found("ZQX 정비 메모.txt", "발전기 엔진 오일은 ZQX-7 규격으로 250시간마다 교환한다.", 0.7)
        assertThat(AiAnswerRules.stripToolCitation("1대대 1중대 고장 장비는 2대입니다. (출처: 장비 목록)", listOf(memo))).isEqualTo("1대대 1중대 고장 장비는 2대입니다.")
        assertThat(AiAnswerRules.stripToolCitation("김가상 연락처는 010-0000-0001입니다.\n(출처: 조직도 조회 결과).", emptyList())).isEqualTo("김가상 연락처는 010-0000-0001입니다.")
        assertThat(AiAnswerRules.stripToolCitation("250시간마다 교환합니다. (출처: ZQX 정비 메모.txt)", listOf(memo))).isEqualTo("250시간마다 교환합니다. (출처: ZQX 정비 메모.txt)")
        assertThat(AiAnswerRules.stripToolCitation("출처 표기 없는 답입니다.", emptyList())).isEqualTo("출처 표기 없는 답입니다.")
        // 이어 묻기 기록의 "(출처: 장비명 (시리얼넘버))"를 따라 쓴 꼴 (실제 모델 평가 14번)
        assertThat(AiAnswerRules.stripToolCitation("1대대 1중대 2반 통신창고에 있습니다.\n(출처: 차량용 무전기 2호 (V5-21-00002))", listOf(memo)))
            .isEqualTo("1대대 1중대 2반 통신창고에 있습니다.")
    }

    @Test
    fun `글로 쓴 도구 호출을 Ollama tool_calls 모양으로 바꾼다`() {
        val node = AiAnswerRules.toolCallNode(AiAnswerRules.textCalls("query_work {mode: count, basis: work_list}").single(), mapper)
        assertThat(mapper.writeValueAsString(node)).isEqualTo("""{"function":{"name":"query_work","arguments":{"mode":"count","basis":"work_list"}}}""")
    }

    @Test
    fun `개수 말은 하나라도 도구 결과에 없으면 근거 없고, 개수를 묻는 말에는 업무 글의 숫자를 근거로 보지 않는다 (실제 모델 검토)`() {
        val work = found("2중대 전장망PC반 무전기 대여", "정보통신대대 2중대 전장망PC반 대대 전술 토의 기간 무전기 4대 대여 요청, 화상회의반 무전기 5대", 0.5)
        val answer = "정보통신대대 2중대 장비는 총 18대입니다. 전장망PC반 무전기 4대, 화상회의반 무전기 5대 대여 요청이 있습니다."
        assertThat(AiAnswerRules.unsupportedCount(answer, "2중대 장비 몇 대야?", emptyList(), listOf(work))).isTrue()
        // 합계·부서별 수가 도구 결과에 있으면 근거가 있다
        val tool = AiToolResult(mapOf("개수" to 18, "부서별" to mapOf("2중대 전장망PC반" to 4, "2중대 화상회의반" to 5)))
        assertThat(AiAnswerRules.unsupportedCount(answer, "2중대 장비 몇 대야?", listOf(tool), listOf(work))).isFalse()
        // 합계만 맞고 나머지 숫자가 도구에 없으면 근거 없다
        assertThat(AiAnswerRules.unsupportedCount(answer, "2중대 장비 몇 대야?", listOf(AiToolResult(mapOf("개수" to 18))), listOf(work))).isTrue()
        // 개수를 묻지 않은 글 질문은 쓴 글의 숫자로 답해도 된다
        assertThat(AiAnswerRules.unsupportedCount("전장망PC반이 대대 전술 토의 기간 무전기 4대 대여를 요청했습니다.", "전장망PC반 대여 요청 내용 알려줘", emptyList(), listOf(work))).isFalse()
    }

    @Test
    fun `개수 근거에는 도구 개수의 합·부서별 수의 합·질문의 숫자, 도구 없이 질문과 맞는 글의 숫자도 든다 (정적 검토)`() {
        val first = AiToolResult(mapOf("개수" to 3, "부서별" to mapOf("유선반" to 3)))
        val second = AiToolResult(mapOf("개수" to 4, "부서별" to mapOf("무선반" to 4)))
        assertThat(AiAnswerRules.unsupportedCount("유선반 3대, 무선반 4대로 모두 7대입니다.", "유선반과 무선반 장비 몇 대야?", listOf(first, second), emptyList())).isFalse()
        val split = AiToolResult(mapOf("개수" to 5, "부서별" to mapOf("1반" to 2, "2반" to 3)))
        assertThat(AiAnswerRules.unsupportedCount("1반 2대, 2반 3대, 합계 5대입니다.", "1중대 장비 몇 대야?", listOf(split), emptyList())).isFalse()
        assertThat(AiAnswerRules.unsupportedCount("3명 이상이면 3명 모두 적습니다.", "3명 이상이면 몇 명을 적어?", emptyList(), emptyList())).isFalse()
        // 개수를 묻는 말인데 도구 없이 질문과 맞는 글(회의록)로 답함
        val minutes = found("회의록_2026-10.hwp", "10월 정기 회의 참석 인원 12명, 안건: 행정반 회선 이동은 유선반장이 맡기로 함", 0.72)
        assertThat(AiAnswerRules.unsupportedCount("10월 정기 회의 참석 인원은 12명입니다.", "10월 회의에 몇 명 참석했어?", emptyList(), listOf(minutes))).isFalse()
        // 질문의 부서 번호는 근거가 아니고, 질문에 적힌 개수 말("5대 넘는")만 근거다 (2차 정적 검토)
        assertThat(AiAnswerRules.unsupportedCount("2대입니다.", "2반 무전기 몇 대야?", emptyList(), emptyList())).isTrue()
        assertThat(AiAnswerRules.unsupportedCount("5대 넘는 반은 없습니다.", "장비 5대 넘는 반 있어?", emptyList(), emptyList())).isFalse()
        // 개수 질문에 글 숫자를 쓰는 것은 글 내용을 묻는 말이거나 도구를 줄 수 없는 차례일 때만
        val ledger = found("2중대 장비 대장.xlsx", "정보통신대대 2중대 장비 대장: 무전기 18대, 정리 지원 요청", 0.75)
        assertThat(AiAnswerRules.unsupportedCount("정보통신대대 2중대 장비 대장에는 무전기 18대가 있습니다.", "2중대 무전기 몇 대야?", emptyList(), listOf(ledger))).isTrue()
        assertThat(AiAnswerRules.unsupportedCount("정보통신대대 2중대 장비 대장에는 무전기 18대가 있습니다.", "2중대 무전기 몇 대야?", emptyList(), listOf(ledger), toolsOffered = false)).isFalse()
        // 부서·장소 이름 속 "회의"(화상회의반·회의실)는 글 내용을 묻는 말이 아니다 (3차 정적 검토)
        assertThat(AiAnswerRules.docContent("화상회의반 장비 몇 대야?", listOf("화상회의반"))).isFalse()
        assertThat(AiAnswerRules.docContent("대대 회의실 장비 몇 대야?")).isFalse()
        assertThat(AiAnswerRules.docContent("10월 회의에 몇 명 참석했어?", listOf("화상회의반"))).isTrue()
        val video = found("화상회의반 장비 대장.xlsx", "정보통신대대 2중대 화상회의반 장비 대장: 카메라 7대, 마이크 4대", 0.8)
        assertThat(AiAnswerRules.unsupportedCount("화상회의반 장비 대장에는 카메라 7대가 있습니다.", "화상회의반 장비 몇 대야?", emptyList(), listOf(video), departments = listOf("화상회의반"))).isTrue()
        // 질문과 약하게 맞는 글의 숫자로 개수를 답하면 근거가 없다
        val weak = found("회의록_2026-10.hwp", minutes.text, 0.4)
        assertThat(AiAnswerRules.unsupportedCount("10월 정기 회의 참석 인원은 12명입니다.", "10월 회의에 몇 명 참석했어?", emptyList(), listOf(weak))).isTrue()
    }

    @Test
    fun `도구로 답했어도 그 글에만 있는 단위 붙은 수치를 썼으면 그 글을 쓴 것이다 (실제 모델 검토)`() {
        val tool = listOf(AiToolResult(mapOf("개수" to 3, "목록" to listOf(mapOf("장비명" to "야전 발전기 2호")))))
        val text = "발전기 엔진 오일은 운전 250시간 또는 6개월마다 교환한다."
        assertThat(AiAnswerRules.figureFrom("야전 발전기 2호는 엔진 오일을 250시간마다 또는 6개월마다 교환합니다.", "발전기 엔진 오일은 언제 갈아야 해?", text, tool)).isTrue()
        // 수치가 질문·도구 결과에 있거나 글에 없으면 아니다
        assertThat(AiAnswerRules.figureFrom("야전 발전기 2호가 있습니다.", "발전기 엔진 오일은 언제 갈아야 해?", text, tool)).isFalse()
        assertThat(AiAnswerRules.figureFrom("250시간마다 교환합니다.", "250시간마다 갈아?", text, tool)).isFalse()
        // 답이 도구 결과의 개수·목록 이름을 적었으면 그 도구 결과를 쓴 것이다(조건·출처를 숨기지 않음, 2차 정적 검토)
        assertThat(AiAnswerRules.mentionsResult("발전기는 3대이고 엔진 오일은 250시간마다 교환합니다.", tool.single().forModel)).isTrue()
        assertThat(AiAnswerRules.mentionsResult("야전 발전기 2호는 250시간마다 교환합니다.", tool.single().forModel)).isTrue()
        assertThat(AiAnswerRules.mentionsResult("엔진 오일은 250시간마다 교환합니다.", tool.single().forModel)).isFalse()
    }
}
