package kr.ictwork.ai.assist

import kr.ictwork.ai.AiLabels
import kr.ictwork.approval.StepKinds
import kr.ictwork.form.FormItem
import kr.ictwork.form.QuestionKinds
import kr.ictwork.ictstatus.BoxColors
import kr.ictwork.org.Ranks

/** 칸 종류 (slices/21 4장·12.1) */
object FieldKinds {
    const val TEXT = "text"
    const val LONGTEXT = "longtext"
    const val DATE = "date"
    const val TIME = "time"
    const val MONTH = "month" // 달 YYYY-MM (근무표 월별 채우기)
    const val SELECT = "select"
    const val MULTI = "multi"
    const val ROWS = "rows" // 줄 목록: 열마다 값
    const val PAIRS = "pairs" // 업무 요청의 받는 부서·업무 유형 짝
    const val OPS = "ops" // 구성도 동작(place·connect·text·zone)
    const val QUESTIONS = "questions" // 양식 질문
    const val BOX_FIELDS = "boxFields" // 정보통신 현황 박스 상세 칸 정의

    val CHOOSING = setOf(SELECT, MULTI, PAIRS)
    val TEXTS = setOf(TEXT, LONGTEXT)

    /** 목록 칸: AI가 지금 값을 포함한 최종 전체 목록을 내고 화면은 그 목록으로 바꾼다 ("더하기는 바로, 빼기는 확인", 사용자 결정) */
    val LISTS = setOf(MULTI, PAIRS, ROWS, QUESTIONS)
}

/** 후보 하나: 화면이 칸에 넣는 값(코드·id)과 모델이 보고 고르는 이름. aliases: 사용자 말에 이 낱말이 나와도 그 후보를 말한 것으로 본다(고르는 칸 근거) */
data class Choice(val value: Any, val label: String, val aliases: List<String> = emptyList())

/** 줄 칸의 열 하나. source가 있으면 서버 후보(AssistCandidates) */
data class AssistColumn(
    val id: String,
    val label: String,
    val kind: String,
    val choices: List<Choice>? = null,
    val source: String? = null,
    val perm: Boolean = false,
    /** 글 열의 글자 수 한도 (화면·서버 검사와 같게) */
    val max: Int = 200,
)

/**
 * 칸 하나 (slices/21 4장·12.1·13.5). perm: 공개·권한 칸(REQ-C06 목록과 같음),
 * choices: 고정 후보, source: 서버 후보 이름(AssistCandidates), aliases: [바꾸기] 없이 바로 바꾸는 칸 이름 낱말(13.5), dependsOn: 이 칸 값으로 정해지는 상위 칸
 */
data class AssistField(
    val id: String,
    val label: String,
    val kind: String,
    val required: Boolean = false,
    val perm: Boolean = false,
    val help: String? = null,
    val max: Int = DEFAULT_MAX,
    val choices: List<Choice>? = null,
    val source: String? = null,
    val columns: List<AssistColumn> = emptyList(),
    val aliases: List<String> = emptyList(),
    val dependsOn: List<String> = emptyList(),
    val maxRows: Int = 20,
    /** 화면·저장에 줄 수 상한이 없는 줄 칸: 지금 줄 수에 maxRows를 더한 줄까지 받는다(지금 줄을 자르지 않게) */
    val openRows: Boolean = false,
) {
    /** 칸 이름 낱말: 이름(괄호 앞)과 별칭 */
    val names: List<String> get() = (listOf(label.substringAfterLast(": ").substringBefore('(').trim()) + aliases).filter { it.isNotEmpty() }

    companion object {
        const val DEFAULT_MAX = 2000
    }
}

/** 고칠 대상: 없음 / 있으면 고치기, 없으면 새로 / 꼭 있음 */
enum class TargetMode { NONE, OPTIONAL, REQUIRED }

/**
 * 지원 화면 하나 (slices/21 8장·12.6·12.9). task: 단추·제안 글의 작업 이름, final: 최종 단추(사용자가 누름),
 * route: 화면 경로(대상 id), guideOnly: 바로 저장되는 동작의 창이라 열기만 하고 칸을 채우지 않음(12.6)
 */
data class AssistScreen(
    val id: String,
    val title: String,
    val task: String,
    val describe: String,
    val final: String,
    val target: TargetMode,
    val fields: List<AssistField>,
    val formInfo: Boolean = false,
    val finalEdit: String? = null,
    val taskEdit: String? = null,
    val guideOnly: Boolean = false,
    private val path: String,
    private val editPath: String? = null,
) {
    fun route(target: Long?): String = (if (target != null && editPath != null) editPath else path).replace("{id}", target?.toString() ?: "")

    fun finalOf(target: Long?): String = if (target != null && finalEdit != null) finalEdit else final

    /** 고칠 대상이 있으면 고치기 작업 이름 */
    fun taskOf(target: Long?): String = if (target != null && taskEdit != null) taskEdit else task
}

/** 이동 제안의 메뉴 하나 (AssistScreens.MENUS) */
data class AssistMenu(val id: String, val name: String, val screens: List<String>, val target: String? = null)

/** 서버 후보 이름 (AssistCandidates) */
object Sources {
    const val ORG = "ORG" // 사용 중인 부서
    const val PEOPLE = "PEOPLE" // 사용 중인 부서의 사용 중인 인원
    const val APPROVERS = "APPROVERS" // 결재자로 고를 수 있는 계정
    const val RECEIVERS = "RECEIVERS" // 받는 부서·업무 유형 짝 (업무 추천 후보 ∪ 사용자가 말한 부서의 유형)
    const val OWN_TYPES = "OWN_TYPES" // 업무 등록: 우리 부서 기준 유형
    const val PLAN_PEOPLE = "PLAN_PEOPLE" // 업무 등록 지원 인원: 우리 부서와 하위 부서 인원
    const val PLAN_EQUIPMENT = "PLAN_EQUIPMENT" // 업무 등록 지원 장비: 우리 부서와 하위 부서의 불용 아닌 장비
    const val DIRECTIVE_NAMES = "DIRECTIVE_NAMES" // 지시사항 명의
    const val ICT_DEPTS = "ICT_DEPTS" // 박스를 추가할 수 있는 부서
    const val BOX_FIELD_KEYS = "BOX_FIELD_KEYS" // 박스 상세 칸 (대표 내용·색을 정하는 칸)
    const val EQUIPMENT_DEPTS = "EQUIPMENT_DEPTS" // 장비 관리부서로 고를 수 있는 부서
    const val LOCATIONS = "LOCATIONS" // 설치장소
    const val DIAGRAM_EQUIPMENT = "DIAGRAM_EQUIPMENT" // 구성도 장비 고르기 후보
    const val FOLDERS = "FOLDERS" // 문서함 옮길 곳
    const val ADMIN_DEPTS = "ADMIN_DEPTS" // 관리하는 부서 (인원관리)
    const val LOCATION_DEPTS = "LOCATION_DEPTS" // 설치장소를 등록할 수 있는 부서
    const val ROUTE_DEPTS = "ROUTE_DEPTS" // 결재 경로 부서
    const val WEEKDAY_RULES = "WEEKDAY_RULES" // 근무표의 요일 규칙
    const val SHIFT_RULES = "SHIFT_RULES" // 근무표의 교대 규칙
    const val RULE_MEMBERS = "RULE_MEMBERS" // 그 줄에서 고른 근무 규칙의 인원 (줄 칸 열)
    const val CHOSEN_MEMBERS = "CHOSEN_MEMBERS" // 로테이션 규칙 창에서 고른 인원 (줄 칸 열)
}

/**
 * 지원 화면과 칸 목록 (REQ-C03 ⑦·C07, slices/21 4장·8장·12.1·12.6·12.9·13.5). 서버가 이 목록으로 스키마를 만들고 목록 밖 칸은 버린다.
 * 동적 칸(양식 질문·박스 칸·추가 특성)은 AssistFields가 DB에서 읽어 더한다
 */
object AssistScreens {
    /** 예·아니오 칸: yes는 "예"를 말한 것으로 보는 낱말(그 칸의 뜻, 예: 중요 표시 칸의 "중요") */
    fun yesNo(vararg yes: String) = listOf(Choice(true, "예", yes.toList()), Choice(false, "아니오"))
    private fun codes(map: Map<String, String>, keys: Collection<String> = map.keys) = keys.map { Choice(it, map.getValue(it)) }

    val STEP_KINDS = codes(mapOf("REVIEW" to "검토", "APPROVE" to "결재", "FULL" to "전결", "PROXY_FULL" to "전대결", "PROXY" to "대결", "VACANT" to "공석"), StepKinds.ALL.toList())
    val PRIORITIES = listOf(Choice("NORMAL", "일반"), Choice("URGENT", "긴급"))
    val EQUIPMENT_STATUSES = codes(AiLabels.EQUIPMENT_STATUSES - "DISPOSED") // 불용 제외 (REQ-C07)
    val NETWORK_KINDS = codes(AiLabels.NETWORK_KINDS)
    val RANKS = Ranks.CODES.map { Choice(it, AiLabels.RANKS.getValue(it)) }
    val POSITIONS = codes(AiLabels.POSITIONS, listOf("MEMBER", "SEC_LEAD", "CO_CMDR", "BN_CMDR"))
    val ROLES = listOf(Choice("BN_CMDR", "대대장"), Choice("CO_CMDR", "중대장"), Choice("SEC_LEAD", "반장"), Choice("DEPT_ADMIN", "부서 관리자")) // 시스템 관리자 없음 (REQ-C06)
    val CALENDAR_COLORS = listOf(Choice("Danger", "빨강", listOf("빨간")), Choice("Success", "초록", listOf("녹색")), Choice("Primary", "파랑", listOf("파란")), Choice("Warning", "주황"))
    private val BOX_COLOR_NAMES = mapOf(
        "GREEN" to Choice("GREEN", "초록", listOf("녹색")), "YELLOW" to Choice("YELLOW", "노랑", listOf("노란")), "ORANGE" to Choice("ORANGE", "주황"),
        "RED" to Choice("RED", "빨강", listOf("빨간")), "BLUE" to Choice("BLUE", "파랑", listOf("파란")), "GRAY" to Choice("GRAY", "회색"),
    )
    val BOX_COLORS = BoxColors.ALL.map { BOX_COLOR_NAMES.getValue(it) }
    val DUTY_KINDS = listOf(Choice("WEEKDAY", "요일 규칙"), Choice("SHIFT", "교대 규칙"))
    val DUTY_DAYS = codes(mapOf("MON" to "월", "TUE" to "화", "WED" to "수", "THU" to "목", "FRI" to "금", "SAT" to "토", "SUN" to "일", "HOLIDAY" to "공휴일"))
    val ROUTE_DOCS = listOf(Choice("SEND", "발송"), Choice("RECEIVE", "접수"), Choice("COMPLETE", "완료"))
    val ROUTE_POSITIONS = listOf(Choice("SEC_LEAD", "반장"), Choice("CO_CMDR", "중대장"), Choice("BN_CMDR", "대대장"))
    val QUESTION_KINDS = codes(
        mapOf(
            "SHORT_TEXT" to "단답", "LONG_TEXT" to "장문", "MULTIPLE_CHOICE" to "객관식", "CHECKBOXES" to "체크박스", "DROPDOWN" to "드롭다운",
            "LINEAR_SCALE" to "선형 배율", "MC_GRID" to "객관식 그리드", "CB_GRID" to "체크박스 그리드", "DATE" to "날짜", "TIME" to "시간",
            "NUMBER" to "숫자", "FILE" to "파일", "GUIDE" to "안내문",
        ),
    )
    val VALIDATIONS = codes(
        mapOf(
            "NUMBER_RANGE" to "숫자 범위", "TEXT_LENGTH" to "글자 수", "PHONE" to "전화번호", "EMAIL" to "이메일", "IP" to "IP", "DIGITS" to "숫자만",
        ),
    )
    val SUMMARY_MODES = listOf(Choice("TEXT", "직접 쓰기"), Choice("FIELD", "상세 칸 값"))
    val COLOR_MODES = listOf(Choice("FIXED", "고정 색"), Choice("FIELD", "상세 칸 선택지 색"))
    val DIAGRAM_OPS = setOf("place", "connect", "text", "zone")

    // ── 칸 묶음 ──

    private fun steps(perm: Boolean = true) = AssistField(
        "steps", "결재 경로(단계 줄)", FieldKinds.ROWS, perm = perm, aliases = listOf("결재 경로", "결재"), maxRows = 10,
        columns = listOf(AssistColumn("kind", "구분", FieldKinds.SELECT, choices = STEP_KINDS), AssistColumn("accountId", "결재자", FieldKinds.SELECT, source = Sources.APPROVERS, perm = true)),
        help = "사용자가 결재자 이름을 말했을 때만. 마지막 단계는 결재 급(결재·전결·전대결·대결)",
    )

    private val viewers = listOf(
        AssistField("viewerDepartments", "열람 범위(부서)", FieldKinds.MULTI, perm = true, source = Sources.ORG, aliases = listOf("열람 범위", "열람")),
        AssistField("viewerPeople", "열람 범위(사람)", FieldKinds.MULTI, perm = true, source = Sources.PEOPLE, aliases = listOf("열람 범위", "열람")),
    )

    private val contact = AssistField("contact", "연락처", FieldKinds.TEXT, max = 30, help = "사용자가 말한 연락처만. 인원 정보에 연락처가 있으면 비워 둔다")

    private fun place(required: Boolean = true) = AssistField(
        "place", "요청 장소", FieldKinds.TEXT, required = required, max = 100, aliases = listOf("장소"),
        help = "작업할 곳(방·건물 이름, 예: 행정반). 사용자가 말한 장소만. 부서 이름이나 자료 속 위치가 아니다",
    )

    private val equipmentFields = listOf(
        AssistField("dept", "관리부서", FieldKinds.SELECT, required = true, perm = true, source = Sources.EQUIPMENT_DEPTS, dependsOn = emptyList()),
        AssistField("location", "설치장소", FieldKinds.SELECT, source = Sources.LOCATIONS),
        AssistField("system", "체계명", FieldKinds.TEXT, max = 100),
        AssistField("name", "장비명", FieldKinds.TEXT, required = true, max = 100, help = "사용자가 말한 장비 이름. 말하지 않았으면 되묻는다"),
        AssistField("model", "모델명", FieldKinds.TEXT, required = true, max = 100),
        AssistField("serial", "시리얼넘버", FieldKinds.TEXT, required = true, max = 100, aliases = listOf("시리얼"), help = "사용자가 말한 값만. 말하지 않았으면 되묻는다"),
        AssistField("commonName", "통칭", FieldKinds.TEXT, max = 100),
        AssistField("status", "상태", FieldKinds.SELECT, required = true, choices = EQUIPMENT_STATUSES),
        AssistField("hwVersion", "HW 버전", FieldKinds.TEXT, max = 100),
        AssistField("hwInstalledOn", "HW 설치일", FieldKinds.DATE),
        AssistField("osType", "OS 종류", FieldKinds.TEXT, max = 100),
        AssistField("osVersion", "OS 버전", FieldKinds.TEXT, max = 100),
        AssistField("osInstalledOn", "OS 설치일", FieldKinds.DATE),
        AssistField("networkKind", "사용전산망", FieldKinds.SELECT, choices = NETWORK_KINDS),
        AssistField("introduced", "도입일", FieldKinds.DATE),
        AssistField("introProject", "도입 사업", FieldKinds.TEXT, max = 100),
        AssistField("maintProject", "유지보수 사업", FieldKinds.TEXT, max = 100),
        AssistField("memo", "비고", FieldKinds.LONGTEXT, max = 200, help = "사용자가 말한 메모"),
        AssistField(
            "software", "SW 줄", FieldKinds.ROWS, aliases = listOf("SW", "소프트웨어"), openRows = true,
            columns = listOf(AssistColumn("name", "SW 이름", FieldKinds.TEXT, max = 100), AssistColumn("version", "버전", FieldKinds.TEXT, max = 100), AssistColumn("installedOn", "설치일", FieldKinds.DATE)),
        ),
        AssistField(
            "networks", "네트워크 줄", FieldKinds.ROWS, aliases = listOf("네트워크", "IP", "MAC"), openRows = true,
            columns = listOf(AssistColumn("name", "이름", FieldKinds.TEXT, max = 100), AssistColumn("ip", "IP 주소", FieldKinds.TEXT, max = 100), AssistColumn("mac", "MAC 주소", FieldKinds.TEXT, max = 100)),
        ),
    )

    /** 업무 요청의 받는 부서 다음 칸 (간편 부서 요청을 다시 올릴 때도 씀) */
    val REQUEST_BODY: List<AssistField> = listOf(
        place(),
        AssistField("detail", "세부 내용", FieldKinds.LONGTEXT, required = true, aliases = listOf("내용"), help = "사용자 말에서 문제를 말한 부분을 그대로"),
        AssistField(
            "preferences", "희망 일자(1~3지망)", FieldKinds.ROWS, aliases = listOf("희망 일자", "희망"), maxRows = 3,
            columns = listOf(AssistColumn("date", "날짜", FieldKinds.DATE), AssistColumn("time", "시각", FieldKinds.TIME)),
        ),
        AssistField("schedule.startDate", "정확한 일정: 시작 날짜", FieldKinds.DATE, aliases = listOf("일정")),
        AssistField("schedule.startTime", "정확한 일정: 시작 시각", FieldKinds.TIME),
        AssistField("schedule.endDate", "정확한 일정: 끝 날짜", FieldKinds.DATE),
        AssistField("schedule.endTime", "정확한 일정: 끝 시각", FieldKinds.TIME),
        contact,
    ) + viewers + steps()

    /** 간편 부서 요청 전에 만든 요청을 다시 올릴 때 (REQ-C07 업무 요청 행: 제목·사유·작업 희망일자·담당자 등) */
    val REQUEST_LEGACY: List<AssistField> = listOf(
        AssistField("title", "제목", FieldKinds.TEXT, required = true, max = 100),
        place(),
        AssistField("reason", "사유", FieldKinds.LONGTEXT, required = true),
        AssistField("start", "작업 희망일자: 시작", FieldKinds.DATE, aliases = listOf("작업 희망일자", "희망일자")),
        AssistField("end", "작업 희망일자: 끝", FieldKinds.DATE),
        AssistField("assignees", "담당자", FieldKinds.MULTI, perm = true, source = Sources.PEOPLE),
        contact,
    ) + viewers + steps()

    /** 업무 등록 칸 (다시 올리기 업무 등록과 함께 씀) */
    val REGISTER_FIELDS: List<AssistField> = listOf(
                AssistField("types", "업무 유형", FieldKinds.MULTI, required = true, source = Sources.OWN_TYPES, aliases = listOf("유형")),
                AssistField("title", "제목", FieldKinds.TEXT, required = true, max = 100, help = "작업 이름(예: 행정반 공유기 교체). 사용자 말에서 그대로"),
                AssistField("place", "장소", FieldKinds.TEXT, required = true, max = 100, help = "작업할 곳(방·건물 이름). 사용자가 말한 장소"),
                AssistField("reason", "사유", FieldKinds.LONGTEXT, required = true, help = "왜 하는지. 사용자가 말하지 않았으면 되묻는다"),
                contact,
                AssistField("assignees", "담당자", FieldKinds.MULTI, perm = true, source = Sources.PEOPLE),
                AssistField("start", "작업 시작일", FieldKinds.DATE, required = true, aliases = listOf("작업 일자")),
                AssistField("end", "작업 종료일", FieldKinds.DATE),
            ) + viewers + listOf(
                AssistField(
                    "plan", "지원 계획 칸", FieldKinds.ROWS, aliases = listOf("지원 계획", "지원 인원"),
                    columns = listOf(
                        AssistColumn("startDate", "시작일", FieldKinds.DATE), AssistColumn("endDate", "종료일", FieldKinds.DATE),
                        AssistColumn("people", "지원 인원", FieldKinds.MULTI, source = Sources.PLAN_PEOPLE, perm = true),
                        AssistColumn("equipment", "장비", FieldKinds.MULTI, source = Sources.PLAN_EQUIPMENT),
                    ),
                ),
                AssistField("priority", "우선순위", FieldKinds.SELECT, required = true, choices = PRIORITIES),
                AssistField("origins", "요청받은 부서", FieldKinds.MULTI, perm = true, source = Sources.ORG),
                steps(),
    )

    val ALL: List<AssistScreen> = listOf(
        AssistScreen(
            "request", "업무 요청 (요청 작성 단계)", "업무 요청 작성", "업무 요청(전화·인터넷·장비 고장이나 작업을 다른 부서에 고쳐 달라고 부탁)",
            "[결재 올리기]", TargetMode.NONE, formInfo = true, path = "/approvals/request",
            fields = listOf(
                AssistField("recommend.query", "업무 추천 찾기 글", FieldKinds.TEXT, max = AssistRecommend.MAX_QUERY, help = "사용자 말에서 문제를 말한 부분을 그대로(업무 추천 찾기)"),
                AssistField(
                    "targets", "받는 부서·업무 유형", FieldKinds.PAIRS, required = true, perm = true, source = Sources.RECEIVERS, dependsOn = listOf("recommend.query"),
                    aliases = listOf("받는 부서", "업무 유형"), help = "사용자가 부서 이름이나 업무 유형 이름을 말했을 때만 후보에서 그 짝. 말하지 않았으면 넣지 않는다(체계가 추천 후보로 되묻는다)",
                ),
            ) + REQUEST_BODY,
        ),
        AssistScreen(
            "request.edit", "업무 요청 고쳐 다시 올리기", "요청 고쳐 다시 올리기", "발송이 반려·회수된 예전 요청을 고쳐 다시 올리기",
            "[다시 올리기]", TargetMode.REQUIRED, path = "/work/requests/{id}/edit",
            // 칸은 요청 종류에 따라 AssistFields가 정한다(간편 부서 요청·예전 요청·업무 등록, A7)
            fields = emptyList(),
        ),
        AssistScreen(
            "register", "업무 등록", "업무 등록", "업무 등록(우리 부서가 할 작업·업무를 등록)", "[결재 올리기]", TargetMode.NONE, formInfo = true, path = "/approvals/new",
            fields = REGISTER_FIELDS,
        ),
        AssistScreen(
            "directive.new", "지시사항 올리기 창", "지시사항 올리기", "지시사항 올리기", "[올리기]", TargetMode.NONE, formInfo = true, path = "/directives",
            fields = listOf(
                AssistField("named", "명의(명의 부서·명의자)", FieldKinds.SELECT, required = true, source = Sources.DIRECTIVE_NAMES, aliases = listOf("명의")),
                AssistField("content", "내용", FieldKinds.LONGTEXT, required = true, max = 1000, help = "사용자가 말한 지시 내용을 그대로(되묻지 말고 채움)"),
            ),
        ),
        AssistScreen(
            "directive.edit", "지시사항 고치기 창", "지시사항 고치기", "올린 지시사항 내용 고치기", "[저장]", TargetMode.REQUIRED, formInfo = true, path = "/directives",
            fields = listOf(AssistField("content", "내용", FieldKinds.LONGTEXT, required = true, max = 1000, help = "사용자가 말한 새 내용을 그대로. 말하지 않았으면 바꾸지 않는다")),
        ),
        AssistScreen(
            "duty.rule", "로테이션 규칙 창", "로테이션 규칙 작성", "근무표 로테이션 규칙(요일 규칙·교대 규칙) 만들기", "[저장]", TargetMode.REQUIRED, path = "/duty/edit",
            fields = listOf(
                AssistField("name", "규칙 이름", FieldKinds.TEXT, required = true, max = 30),
                AssistField("kind", "종류", FieldKinds.SELECT, required = true, choices = DUTY_KINDS),
                AssistField("days", "적용 날", FieldKinds.MULTI, choices = DUTY_DAYS, aliases = listOf("요일")),
                AssistField("members", "인원(순번대로)", FieldKinds.MULTI, required = true, perm = true, source = Sources.PEOPLE, aliases = listOf("인원", "순번")),
                AssistField(
                    "shifts", "교대 근무 종류", FieldKinds.ROWS, aliases = listOf("근무 종류"), maxRows = 10,
                    columns = listOf(
                        AssistColumn("name", "이름", FieldKinds.TEXT, max = 30), AssistColumn("short", "짧은 이름", FieldKinds.TEXT, max = 2),
                        AssistColumn("count", "인원 수", FieldKinds.TEXT, max = 3), AssistColumn("off", "쉬는 근무", FieldKinds.SELECT, choices = yesNo("쉬는")),
                    ),
                ),
                AssistField("pattern", "교대 패턴", FieldKinds.TEXT, max = 200, help = "교대 근무 종류의 짧은 이름을 차례로 쉼표로(예: 주,야,비)"),
                AssistField(
                    "startCell", "교대 시작 칸", FieldKinds.ROWS, aliases = listOf("시작 칸"),
                    columns = listOf(AssistColumn("person", "사람", FieldKinds.SELECT, source = Sources.CHOSEN_MEMBERS), AssistColumn("cell", "시작 칸 번호", FieldKinds.TEXT)),
                ),
            ),
        ),
        AssistScreen(
            "duty.fill", "근무표 월별 채우기 창", "근무표 월별 채우기", "근무표 월별 채우기", "[채우기]", TargetMode.REQUIRED, path = "/duty/edit",
            fields = listOf(
                AssistField("month", "달(YYYY-MM)", FieldKinds.MONTH, required = true, max = 7, aliases = listOf("달", "월")),
                AssistField("fromDate", "채울 첫날", FieldKinds.DATE, required = true, aliases = listOf("첫날")),
                AssistField(
                    "first", "요일 규칙마다 첫 사람", FieldKinds.ROWS, aliases = listOf("첫 사람"), dependsOn = listOf("month", "fromDate"),
                    columns = listOf(AssistColumn("rule", "규칙", FieldKinds.SELECT, source = Sources.WEEKDAY_RULES), AssistColumn("person", "첫 사람", FieldKinds.SELECT, source = Sources.RULE_MEMBERS)),
                ),
                AssistField(
                    "cells", "교대 규칙마다 시작 칸", FieldKinds.ROWS, aliases = listOf("시작 칸"), dependsOn = listOf("month", "fromDate"),
                    columns = listOf(
                        AssistColumn("rule", "규칙", FieldKinds.SELECT, source = Sources.SHIFT_RULES), AssistColumn("person", "사람", FieldKinds.SELECT, source = Sources.RULE_MEMBERS),
                        AssistColumn("cell", "시작 칸 번호", FieldKinds.TEXT),
                    ),
                ),
            ),
        ),
        AssistScreen(
            "duty.holiday", "공휴일 추가 창", "공휴일 추가", "근무표 공휴일 추가", "[더하기]", TargetMode.REQUIRED, path = "/duty/edit",
            fields = listOf(AssistField("date", "날짜", FieldKinds.DATE, required = true), AssistField("name", "공휴일 이름", FieldKinds.TEXT, required = true, max = 30)),
        ),
        AssistScreen(
            "duty.roster", "근무표 추가 창", "근무표 추가", "새 근무표 만들기", "[추가]", TargetMode.REQUIRED, path = "/duty/edit",
            fields = listOf(AssistField("name", "근무표 이름", FieldKinds.TEXT, required = true, max = 30)),
        ),
        AssistScreen(
            "duty.share", "근무표 [공유 부서] 탭", "근무표 공유 부서 열기", "근무표 공유 부서 더하기·빼기(바로 저장, 안내만)", "[더하기]·[빼기]", TargetMode.REQUIRED, guideOnly = true,
            path = "/duty/edit", fields = emptyList(),
        ),
        AssistScreen(
            "ict.new", "정보통신 현황 박스 추가 창", "정보통신 현황 박스 추가", "정보통신 현황 박스 추가", "[추가]", TargetMode.NONE, path = "/ict-status",
            fields = listOf(
                AssistField("dept", "부서", FieldKinds.SELECT, required = true, source = Sources.ICT_DEPTS),
                AssistField("title", "제목", FieldKinds.TEXT, required = true, max = 30),
                AssistField("summary", "대표 내용", FieldKinds.TEXT, required = true, max = 30, aliases = listOf("대표", "요약"), help = "박스에 크게 보일 짧은 값"),
                AssistField("color", "색", FieldKinds.SELECT, required = true, choices = BOX_COLORS),
            ),
        ),
        AssistScreen(
            "ict.edit", "정보통신 현황 박스 고치기", "정보통신 현황 박스 고치기", "정보통신 현황 박스 값·칸 고치기", "[저장]", TargetMode.REQUIRED, path = "/ict-status",
            fields = listOf(
                AssistField("title", "제목", FieldKinds.TEXT, required = true, max = 30),
                AssistField("summary.mode", "대표 내용 방식", FieldKinds.SELECT, choices = SUMMARY_MODES, aliases = listOf("대표", "요약")),
                AssistField("summary.text", "대표 내용", FieldKinds.TEXT, max = 30, aliases = listOf("대표", "요약"), help = "방식이 직접 쓰기일 때 박스에 크게 보일 짧은 값"),
                AssistField("summary.fieldKey", "대표 내용으로 쓸 상세 칸", FieldKinds.SELECT, source = Sources.BOX_FIELD_KEYS, aliases = listOf("대표", "요약")),
                AssistField("color.mode", "색 방식", FieldKinds.SELECT, choices = COLOR_MODES, aliases = listOf("색")),
                AssistField("color.color", "색", FieldKinds.SELECT, choices = BOX_COLORS),
                AssistField("fields", "새 상세 칸", FieldKinds.BOX_FIELDS, aliases = listOf("칸 만들기", "새 칸"), maxRows = 10, help = "새로 만들 상세 칸 정의와 그 값. 선택형은 options, 그리드는 gridRows·gridColumns(값은 {행: 열}), 선형 배율은 scaleMin·scaleMax·minLabel·maxLabel(값은 정수), 표는 columns"),
                AssistField("scope", "공개 범위", FieldKinds.MULTI, perm = true, source = Sources.ORG),
            ),
        ),
        AssistScreen(
            "calendar", "개인 일정 창", "개인 일정 추가", "달력 개인 일정 추가·고치기", "[추가]", TargetMode.OPTIONAL, finalEdit = "[저장]", taskEdit = "개인 일정 고치기", path = "/calendar",
            fields = listOf(
                AssistField("title", "제목", FieldKinds.TEXT, max = 100, help = "일정 이름(예: 휴가). 사용자 말에서 그대로"),
                AssistField("color", "색", FieldKinds.SELECT, choices = CALENDAR_COLORS),
                AssistField("start", "시작 날짜", FieldKinds.DATE, required = true),
                AssistField("end", "끝 날짜", FieldKinds.DATE),
                AssistField("weekdays", "평일만 표시", FieldKinds.SELECT, choices = yesNo("평일만")),
                AssistField("important", "중요 표시", FieldKinds.SELECT, choices = yesNo("중요"), aliases = listOf("중요")),
            ),
        ),
        AssistScreen(
            "equipment", "장비 등록·고치기", "장비 등록", "장비 등록(새 장비)·장비 상세 고치기", "[저장]", TargetMode.OPTIONAL, formInfo = true, finalEdit = "[저장]", taskEdit = "장비 고치기",
            path = "/department/equipment/new", editPath = "/department/equipment/{id}", fields = equipmentFields,
        ),
        AssistScreen(
            "diagram", "구성도 편집", "구성도 편집", "구성도에 장비·선·글상자·구역 넣기(편집 중에만)", "[캔버스에 넣기]", TargetMode.REQUIRED, path = "/department/diagrams/{id}",
            fields = listOf(
                AssistField(
                    "ops", "넣을 요소", FieldKinds.OPS, required = true, source = Sources.DIAGRAM_EQUIPMENT, maxRows = 30,
                    help = "동작 목록. op는 place(장비 놓기, equipment), connect(선, from·to는 놓인 장비나 이번에 놓을 장비), text(글상자, text), zone(구역, name과 members)",
                ),
            ),
        ),
        AssistScreen(
            "docbox.folder", "문서함 새 폴더 창", "문서함 새 폴더 만들기", "문서함 새 폴더 만들기", "[만들기]", TargetMode.REQUIRED, path = "/department/docs",
            fields = listOf(AssistField("name", "폴더 이름", FieldKinds.TEXT, required = true, max = 100)),
        ),
        AssistScreen(
            "docbox.rename", "문서함 이름 바꾸기 창", "문서함 이름 바꾸기", "문서함 폴더·파일 이름 바꾸기", "[바꾸기]", TargetMode.REQUIRED, path = "/department/docs",
            fields = listOf(AssistField("name", "바꿀 이름", FieldKinds.TEXT, required = true, max = 100, aliases = listOf("이름"), help = "파일이면 확장자를 뺀 이름만 쓴다(확장자는 그대로)")),
        ),
        AssistScreen(
            "docbox.move", "문서함 옮기기 창", "문서함 옮기기", "문서함 폴더·파일 옮기기", "[옮기기]", TargetMode.REQUIRED, path = "/department/docs",
            fields = listOf(AssistField("dest", "옮길 곳(폴더)", FieldKinds.SELECT, required = true, perm = true, source = Sources.FOLDERS, aliases = listOf("옮길 곳"))),
        ),
        AssistScreen(
            "docbox.share", "문서함 공유 창", "문서함 공유 창 열기", "문서함 공유 더하기·빼기(바로 저장, 안내만)", "[더하기]·[빼기]", TargetMode.REQUIRED, guideOnly = true,
            path = "/department/docs", fields = emptyList(),
        ),
        AssistScreen(
            "people.attr", "인원 추가 특성 편집 창", "인원 추가 특성 고치기", "인원 추가 특성 값 고치기(혈액형·자격증 같은 특성만)", "[저장]", TargetMode.REQUIRED, path = "/department/people",
            fields = emptyList(),
        ),
        AssistScreen(
            "worktype.type", "업무 유형 추가·고치기 창", "업무 유형 추가", "업무 유형 이름·설명 만들기·고치기", "[저장]", TargetMode.OPTIONAL, taskEdit = "업무 유형 고치기", path = "/admin/work-types",
            fields = listOf(
                AssistField("name", "유형 이름", FieldKinds.TEXT, required = true, max = 50),
                AssistField("description", "설명", FieldKinds.LONGTEXT, required = true, max = 500, help = "업무 추천에 쓰일 설명(어떤 문제일 때 고르는 유형인지). 사용자 말에서 그대로"),
            ),
        ),
        AssistScreen(
            "worktype.form", "양식 편집", "업무 유형 양식 만들기", "업무 유형의 양식(질문) 만들기", "[양식 저장]", TargetMode.REQUIRED, path = "/admin/work-types",
            fields = listOf(
                AssistField(
                    "questions", "양식 질문", FieldKinds.QUESTIONS, maxRows = kr.ictwork.form.FormRules.MAX_QUESTIONS, aliases = listOf("질문", "양식"),
                    help = "질문 목록. 항목: section(구역 제목), kind, title, description, required, options(선택지 이름, goTo는 이동할 구역 제목·END), shuffle, allowOther, scaleMin·scaleMax, rows·columns(그리드), validation",
                ),
            ),
        ),
        AssistScreen(
            "personnel.new", "인원 등록 창", "인원 등록", "인원관리: 새 인원 등록", "[저장]", TargetMode.NONE, path = "/admin/personnel",
            fields = listOf(
                AssistField("name", "성명", FieldKinds.TEXT, required = true, max = 50),
                AssistField("dept", "소속", FieldKinds.SELECT, required = true, perm = true, source = Sources.ADMIN_DEPTS),
                AssistField("position", "직책", FieldKinds.SELECT, required = true, choices = POSITIONS),
                AssistField("rank", "계급", FieldKinds.SELECT, required = true, choices = RANKS),
                AssistField("contact", "연락처", FieldKinds.TEXT, max = 30),
                AssistField("memo", "비고", FieldKinds.LONGTEXT, max = 200),
                AssistField("loginId", "로그인 아이디", FieldKinds.TEXT, required = true, max = 50, aliases = listOf("아이디")),
            ),
        ),
        AssistScreen(
            "personnel.edit", "인원 수정 창", "인원 정보 수정", "인원관리: 인원의 성명·직책·계급·연락처 수정", "[저장]", TargetMode.REQUIRED, path = "/admin/personnel",
            fields = listOf(
                AssistField("name", "성명", FieldKinds.TEXT, required = true, max = 50),
                AssistField("position", "직책", FieldKinds.SELECT, required = true, choices = POSITIONS),
                AssistField("rank", "계급", FieldKinds.SELECT, required = true, choices = RANKS),
                AssistField("contact", "연락처", FieldKinds.TEXT, max = 30),
                AssistField("memo", "비고", FieldKinds.LONGTEXT, max = 200),
            ),
        ),
        AssistScreen(
            "personnel.role", "계정 역할 줄", "역할 주기", "인원관리: 계정에 역할 주기", "[역할 주기]", TargetMode.REQUIRED, path = "/admin/personnel",
            fields = listOf(
                AssistField("role", "역할", FieldKinds.SELECT, required = true, perm = true, choices = ROLES),
                AssistField("roleDept", "대상 부서", FieldKinds.SELECT, required = true, perm = true, source = Sources.ADMIN_DEPTS),
            ),
        ),
        AssistScreen(
            "equipmentAdmin.location", "설치장소 등록·이름 바꾸기 창", "설치장소 등록", "장비관리: 설치장소 등록·이름 바꾸기", "[저장]", TargetMode.OPTIONAL, taskEdit = "설치장소 이름 바꾸기", path = "/admin/equipment",
            fields = listOf(
                AssistField("dept", "부서", FieldKinds.SELECT, required = true, source = Sources.LOCATION_DEPTS),
                AssistField("name", "설치장소 이름", FieldKinds.TEXT, required = true, max = 50),
            ),
        ),
        AssistScreen(
            "route", "결재 경로 관리", "결재 경로 바꾸기", "결재 경로 관리(부서의 기본 결재 경로)", "[저장]", TargetMode.OPTIONAL, path = "/admin/approval-routes",
            fields = listOf(
                AssistField("dept", "부서", FieldKinds.SELECT, required = true, source = Sources.ROUTE_DEPTS),
                AssistField("doc", "결재 문서 종류", FieldKinds.SELECT, required = true, choices = ROUTE_DOCS),
                AssistField(
                    "steps", "단계 줄(직위·구분)", FieldKinds.ROWS, required = true, aliases = listOf("단계"), maxRows = 10, dependsOn = listOf("dept", "doc"),
                    columns = listOf(AssistColumn("position", "직위", FieldKinds.SELECT, choices = ROUTE_POSITIONS), AssistColumn("kind", "구분", FieldKinds.SELECT, choices = STEP_KINDS)),
                ),
            ),
        ),
    )

    val BY_ID: Map<String, AssistScreen> = ALL.associateBy { it.id }

    fun find(id: String?): AssistScreen? = id?.let(BY_ID::get)

    /**
     * 이동 제안의 메뉴 (사용자 결정 navigate): id는 그 메뉴의 대표 화면 id(화면이 이 id로 메뉴 경로를 정함), screens는 그 메뉴의 지원 화면,
     * target은 대상(이름을 말한 장비·구성도·요청)을 열 수 있는 화면(상세 경로가 있는 화면만)
     */
    val MENUS: List<AssistMenu> = listOf(
        AssistMenu("request", "업무 요청", listOf("request", "request.edit"), "request.edit"),
        AssistMenu("register", "업무 등록", listOf("register")),
        AssistMenu("directive.new", "지시사항", listOf("directive.new", "directive.edit")),
        AssistMenu("duty.rule", "근무표 편집", listOf("duty.rule", "duty.fill", "duty.holiday", "duty.roster", "duty.share")),
        AssistMenu("ict.edit", "정보통신 현황", listOf("ict.new", "ict.edit")),
        AssistMenu("calendar", "달력", listOf("calendar")),
        AssistMenu("equipment", "장비", listOf("equipment"), "equipment"),
        AssistMenu("diagram", "구성도", listOf("diagram"), "diagram"),
        AssistMenu("docbox.folder", "문서함", listOf("docbox.folder", "docbox.rename", "docbox.move", "docbox.share")),
        AssistMenu("people.attr", "인원", listOf("people.attr")),
        AssistMenu("worktype.type", "업무 유형 관리", listOf("worktype.type", "worktype.form")),
        AssistMenu("personnel.new", "인원관리", listOf("personnel.new", "personnel.edit", "personnel.role")),
        AssistMenu("equipmentAdmin.location", "장비관리", listOf("equipmentAdmin.location")),
        AssistMenu("route", "결재 경로 관리", listOf("route")),
    )

    fun menuOf(screenId: String): AssistMenu? = MENUS.firstOrNull { screenId in it.screens }

    /**
     * 양식 질문·박스 칸·추가 특성 하나를 칸으로 (12.1): 파일 질문·안내문은 칸으로 만들지 않는다(파일은 사용자가 고름, J-3).
     * 그리드는 행마다 칸 하나(id.행 id). required는 양식 질문만
     */
    fun itemFields(id: String, label: String, item: FormItem, required: Boolean, dependsOn: List<String> = emptyList()): List<AssistField> {
        val options = item.options.map { Choice(it.id, it.label) }
        val base = AssistField(id, label, FieldKinds.TEXT, required = required, dependsOn = dependsOn, help = item.description.takeIf { it.isNotBlank() }?.take(200))
        return when (item.kind) {
            QuestionKinds.SHORT_TEXT -> listOf(base.copy(max = kr.ictwork.form.FormRules.SHORT_MAX))
            QuestionKinds.LONG_TEXT -> listOf(base.copy(kind = FieldKinds.LONGTEXT))
            QuestionKinds.NUMBER -> listOf(base.copy(max = 40))
            QuestionKinds.DATE -> listOf(base.copy(kind = FieldKinds.DATE))
            QuestionKinds.TIME -> listOf(base.copy(kind = FieldKinds.TIME))
            QuestionKinds.MULTIPLE_CHOICE, QuestionKinds.DROPDOWN -> listOf(base.copy(kind = FieldKinds.SELECT, choices = options))
            QuestionKinds.CHECKBOXES -> listOf(base.copy(kind = FieldKinds.MULTI, choices = options))
            QuestionKinds.LINEAR_SCALE -> {
                val min = item.scaleMin ?: 1
                val max = item.scaleMax ?: 5
                listOf(base.copy(kind = FieldKinds.SELECT, choices = (min..max).map { Choice(it, it.toString()) }))
            }
            QuestionKinds.MC_GRID, QuestionKinds.CB_GRID -> item.rows.map { row ->
                base.copy(
                    id = "$id.${row.id}", label = "$label: ${row.label}",
                    kind = if (item.kind == QuestionKinds.MC_GRID) FieldKinds.SELECT else FieldKinds.MULTI,
                    choices = item.columns.map { Choice(it.id, it.label) },
                )
            }
            else -> emptyList() // FILE·GUIDE
        }
    }

}

/**
 * 칸 목록 공급자 (slices/21 12.1): 정적 칸에 동적 칸을 더한다. 동적 칸은 서버가 DB에서 읽고, 상위 칸 값(value)이 정해져야 생긴다(dependsOn).
 * - 업무 요청: 받는 부서·유형 짝마다 양식 질문 form.{받는 부서}.{질문 key} / 업무 등록: 고른 유형의 양식 form.{우리 부서}.{key}
 * - 장비: 관리부서의 추가 특성 attrs.{특성 id} / 인원: 그 인원 부서의 추가 특성
 * - 박스 고치기: 상세 칸 값 values.{key}, 표 값 table.{key}
 * 파일 질문·파일 특성·파일 칸은 넣지 않는다(J-3). 읽기 트랜잭션 안에서 부른다
 */
@org.springframework.stereotype.Component
class AssistFields(
    private val workTypeRepository: kr.ictwork.worktype.WorkTypeRepository,
    private val forms: kr.ictwork.worktype.WorkForms,
    private val attributeValues: kr.ictwork.attribute.AttributeValues,
    private val personnelRepository: kr.ictwork.org.PersonnelRepository,
    private val ictBoxRepository: kr.ictwork.ictstatus.IctBoxRepository,
    private val schemaRepository: kr.ictwork.ictstatus.IctBoxSchemaRepository,
    private val objectMapper: com.fasterxml.jackson.databind.ObjectMapper,
    private val requestRepository: kr.ictwork.work.WorkRequestRepository,
    private val targetTypeRepository: kr.ictwork.work.RequestTargetTypeRepository,
    private val resolver: kr.ictwork.worktype.WorkTypeResolver,
    private val workAccess: kr.ictwork.work.WorkAccess,
) {
    fun of(actor: kr.ictwork.admin.Actor, screen: AssistScreen, target: Long?, value: (String) -> Any?): List<AssistField> = staticFields(screen, target) + when (screen.id) {
        // 양식 칸은 그 화면에서 고를 수 있는 유형만 정의를 읽는다(화면이 보낸 id 그대로 다른 부서 유형의 질문 이름을 읽지 않음, REQ-C06).
        // 업무 요청: 받는 부서가 사용 중이고 접수할 사람이 있으며 유형이 그 부서 기준 고르기 목록(/api/work/types)에 있는 짝
        "request" -> pairsOf(value("targets")).filter { (dept, typeId) -> selectable(actor, dept, typeId, receiver = true) }.flatMap { (dept, typeId) -> formFields(dept, typeId, "targets", value) }
        // 업무 등록: 고른 유형마다 양식 (우리 부서 기준, 화면이 유형 여럿을 고름)
        // 업무 등록: 우리 부서 기준 고르기 목록(/api/work/types/own, 우리 부서 "기타" 포함)에 있는 유형만
        "register" -> actor.personnelDepartmentId?.let { own -> idsOf(value("types")).filter { selectable(actor, own, it, receiver = false) }.flatMap { formFields(own, it, "types", value) } }.orEmpty()
        "request.edit" -> target?.let { editFields(it, value) }.orEmpty()
        // 관리부서는 그 사용자가 고를 수 있는 부서일 때만 특성 칸을 읽는다(화면이 보낸 값 그대로 다른 부서 특성 이름을 읽지 않음)
        "equipment" -> (value("dept") as? Number)?.toLong()?.takeIf { it in actor.equipmentScope() }?.let { attributeFields(kr.ictwork.attribute.AttributeTargets.EQUIPMENT, it, "dept") }.orEmpty()
        "people.attr" -> target?.let { personnelRepository.findById(it).orElse(null) }?.let { attributeFields(kr.ictwork.attribute.AttributeTargets.PERSONNEL, it.departmentId, null) }.orEmpty()
        "ict.edit" -> target?.let { ictBoxRepository.findById(it).orElse(null) }?.let { box -> boxFields(definitionOf(box.schemaId)) }.orEmpty()
        else -> emptyList()
    }

    private fun definitionOf(schemaId: Long): kr.ictwork.ictstatus.BoxDefinition = schemaRepository.findById(schemaId).orElse(null)
        ?.let { objectMapper.readValue(it.definition, kr.ictwork.ictstatus.BoxDefinition::class.java) } ?: kr.ictwork.ictstatus.BoxDefinition()

    /**
     * 다시 올리기 칸 (A7): 요청 종류에 따라 화면이 보이는 칸이 다르다. 업무 등록은 업무 등록 칸, 간편 부서 요청은 업무 요청 칸(받는 부서 다음),
     * 그 전에 만든 요청은 제목·사유·작업 희망일자·담당자와 업무 요청 칸. 양식 질문은 그 요청의 받는 부서·유형 짝마다(지금 판)
     */
    /** 정적 칸: 설치장소 이름 바꾸기는 부서가 정해져 있어 부서 칸을 두지 않는다 (5) */
    private fun staticFields(screen: AssistScreen, target: Long?): List<AssistField> =
        if (screen.id == "equipmentAdmin.location" && target != null) screen.fields.filter { it.id != "dept" } else screen.fields

    private fun editFields(requestId: Long, value: (String) -> Any?): List<AssistField> {
        val request = requestRepository.findById(requestId).orElse(null) ?: return emptyList()
        val base = when {
            request.kind == kr.ictwork.work.RequestKinds.REGISTER -> AssistScreens.REGISTER_FIELDS
            request.departmentIntake -> AssistScreens.REQUEST_BODY
            else -> AssistScreens.REQUEST_LEGACY
        }
        val forms = targetTypeRepository.findByRequestIdOrderBySortOrderAscIdAsc(requestId).flatMap { formFields(it.targetDepartmentId, it.workTypeId, "", value) }
        return base + forms
    }

    /** 그 부서 기준 고르기 목록(WorkTypeResolver.resolve, 화면 목록과 같음)에 있는 유형인가. receiver면 받는 부서로 고를 수 있는 부서여야 한다(WorkAccess.hasReceiver) */
    private fun selectable(actor: kr.ictwork.admin.Actor, departmentId: Long, typeId: Long, receiver: Boolean): Boolean {
        if (actor.tree.find(departmentId)?.active != true || (receiver && !workAccess.hasReceiver(departmentId))) return false
        return resolver.resolve(actor.tree, departmentId).any { it.typeId == typeId }
    }

    /**
     * 양식 질문 칸. 지금 답(이동 질문의 고른 선택지)으로 건너뛰는 구역의 질문은 필수로 두지 않는다(되묻지 않음, 화면의 구역 이동 규칙 FormRules.path와 같게, 결함 12)
     */
    private fun formFields(departmentId: Long, typeId: Long, parent: String, value: (String) -> Any?): List<AssistField> {
        val type = workTypeRepository.findById(typeId).orElse(null) ?: return emptyList()
        val definition = type.currentFormVersionId?.let { forms.definitions(listOf(it))[it] } ?: return emptyList()
        fun id(item: FormItem) = "form.$departmentId.$typeId.${item.key}"
        val answers = objectMapper.createObjectNode()
        definition.sections.flatMap { it.items }.filter(kr.ictwork.form.FormRules::isBranching).forEach { item ->
            (value(id(item)) as? String)?.let { answers.putObject(item.key).put("option", it) }
        }
        val passed = kr.ictwork.form.FormRules.path(definition, answers).map { it.key }.toSet()
        return definition.sections.flatMap { section ->
            section.items.flatMap { item ->
                AssistScreens.itemFields(id(item), "양식(${type.name}): ${item.title}", item, item.required && section.key in passed, listOfNotNull(parent.ifEmpty { null }))
            }
        }
    }

    private fun attributeFields(target: String, departmentId: Long, parent: String?): List<AssistField> =
        attributeValues.liveDefs(target, listOf(departmentId))[departmentId].orEmpty().flatMap { def ->
            AssistScreens.itemFields("attrs.${def.id}", "추가 특성: ${def.name}", attributeValues.item(def), false, listOfNotNull(parent))
        }

    private fun boxFields(definition: kr.ictwork.ictstatus.BoxDefinition): List<AssistField> = definition.fields.flatMap { f ->
        if (f.kind == kr.ictwork.ictstatus.BoxKinds.TABLE) {
            listOf(
                AssistField(
                    "table.${f.key}", "표: ${f.title}", FieldKinds.ROWS, aliases = listOf(f.title), maxRows = kr.ictwork.ictstatus.BoxRules.MAX_ROWS,
                    columns = f.table?.columns.orEmpty().map { c ->
                        when (c.kind) {
                            kr.ictwork.ictstatus.TableColumnKinds.DATE -> AssistColumn(c.id, c.title, FieldKinds.DATE)
                            kr.ictwork.ictstatus.TableColumnKinds.DROPDOWN -> AssistColumn(c.id, c.title, FieldKinds.SELECT, choices = c.options.map { Choice(it.id, it.label) })
                            kr.ictwork.ictstatus.TableColumnKinds.CHECKBOX -> AssistColumn(c.id, c.title, FieldKinds.SELECT, choices = listOf(Choice(true, "예"), Choice(false, "아니오")))
                            else -> AssistColumn(c.id, c.title, FieldKinds.TEXT)
                        }
                    },
                ),
            )
        } else {
            AssistScreens.itemFields("values.${f.key}", "상세: ${f.title}", f.toItem(), false).map { it.copy(aliases = listOf(f.title)) }
        }
    }

    companion object {
        /** id 목록 값 (숫자·숫자 글) */
        fun idsOf(raw: Any?): List<Long> = (raw as? List<*>).orEmpty().mapNotNull { (it as? Number)?.toLong() ?: (it as? String)?.toLongOrNull() }.distinct()

        /** 받는 부서·유형 짝 값([{departmentId, typeId}])을 읽는다 */
        fun pairsOf(raw: Any?): List<Pair<Long, Long>> = (raw as? List<*>).orEmpty().mapNotNull { p ->
            val m = p as? Map<*, *> ?: return@mapNotNull null
            val d = (m["departmentId"] as? Number)?.toLong() ?: return@mapNotNull null
            val t = (m["typeId"] as? Number)?.toLong() ?: return@mapNotNull null
            d to t
        }.distinct()
    }
}
