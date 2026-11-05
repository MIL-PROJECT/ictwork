package kr.ictwork.work

import kr.ictwork.worktype.otherTargets
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.time.LocalDateTime

/**
 * 완료 결재(REQ-212, G-149)·자동 완료(REQ-110)·지원 내용 결재와 계획 재결재 없앰(REQ-208 삭제)·달력·배정된 업무 (slices/16 9.2 W-C·W-A·W-D)
 */
class WorkCompleteApprovalTest : PlanSlotTestBase() {
    @Autowired lateinit var jdbcTemplate: JdbcTemplate

    // ───────── 완료 결재 (W-C) ─────────

    @Test
    fun `W-C1 계획 상태에서 완료 결재를 올리면 결재중, 문서에 올릴 때의 계획 칸, 결재가 끝나면 완료하고 완료한 때는 결재가 끝난 때`() {
        val id = plannedItem(slot("2026-10-20", "2026-10-21", e1, people = listOf("ps-s1b")))
        postAs("ps-s1a", "/api/work/items/$id/complete-approval", mapOf("steps" to line("ps-s1a", review("ps-s1lead"), approve("ps-c1cmdr"))))
            .andExpect(status().isNoContent)
        assertThat(item("ps-s1a", id)["status"].asText()).isEqualTo("IN_APPROVAL")
        assertThat(item("ps-s1a", id)["approvalInProgress"].asBoolean()).isTrue()
        val docId = myTurnDoc("ps-s1lead")
        val view = doc("ps-s1lead", docId)
        assertThat(view["docType"].asText()).isEqualTo("COMPLETE")
        assertThat(view["proposal"].isNull).isTrue()
        assertThat(view["autoSubmitted"].asBoolean()).isFalse()
        assertThat(view["completionPlan"]["slots"].single()["equipment"].map { it["name"].asText() }).containsExactly("무전기-가 (PS-SN-0001)")
        assertThat(view["completionPlan"]["slots"].single()["personnel"].map { it["name"].asText() }).containsExactly("사람-ps-s1b")
        // 결재 중에도 계획은 장비에서 계획으로 본다
        assertThat(carryOutOf(e1)).isEqualTo("RESERVED")
        postAs("ps-s1lead", "/api/approvals/docs/$docId/approve").andExpect(status().isNoContent)
        assertThat(item("ps-s1a", id)["status"].asText()).isEqualTo("IN_APPROVAL")
        clock.now = LocalDateTime.of(2026, 10, 22, 10, 0)
        postAs("ps-c1cmdr", "/api/approvals/docs/$docId/approve").andExpect(status().isNoContent)
        val done = workItemRepository.findById(id).get()
        assertThat(done.status).isEqualTo(WorkStatuses.DONE)
        assertThat(done.completedAt).isEqualTo(LocalDateTime.of(2026, 10, 22, 10, 0))
        assertThat(done.completedByAuto).isFalse()
        assertThat(carryOutOf(e1)).isEqualTo("NONE")
        // 요청자에게 완료 결재중·완료 상태 알림 (REQ-701)
        assertThat(notificationsOf("ps-req", "WORK_STATUS").map { it.message }).anyMatch { it.endsWith("완료 결재중") }.anyMatch { it.endsWith(" 완료") }
    }

    @Test
    fun `W-C2 기안자 한 사람의 결재 급 경로로 올리면 바로 완료`() {
        val id = plannedItem(slot("2026-10-20"))
        complete("ps-s1a", id).andExpect(status().isNoContent)
        assertThat(item("ps-s1a", id)["status"].asText()).isEqualTo("DONE")
        val doc = lastDoc("ps-s1a", id)
        assertThat(doc["docType"].asText()).isEqualTo("COMPLETE")
        assertThat(doc["status"].asText()).isEqualTo("DONE")
        assertThat(doc["steps"].map { it["result"].asText() }).containsExactly("APPROVED")
    }

    @Test
    fun `W-C3 반려되면 계획으로 돌아가 자동 완료가 꺼지고, 계획을 고쳐 같은 문서를 다시 올리면 새 회차에 고친 칸`() {
        val id = plannedItem(slot("2026-10-20"), autoComplete = true)
        completeInApproval(id)
        val docId = myTurnDoc("ps-s1lead")
        postAs("ps-s1lead", "/api/approvals/docs/$docId/reject", mapOf("reason" to "결과 보완")).andExpect(status().isNoContent)
        val back = workItemRepository.findById(id).get()
        assertThat(back.status).isEqualTo(WorkStatuses.PLANNED)
        assertThat(back.autoComplete).isFalse()
        assertThat(back.autoCompleteAt).isNull()
        assertThat(back.autoCompleteByAccountId).isNull()
        send("PUT", "ps-s1a", "/api/work/items/$id/plan", mapOf("plan" to plan(slot("2026-10-20"), slot("2026-10-23", null, e2)))).andExpect(status().isNoContent)
        // 보낸 계획은 쓰지 않고 지금 칸으로 다시 올린다
        postAs("ps-s1a", "/api/approvals/docs/$docId/resubmit", mapOf("steps" to line("ps-s1a", approve("ps-s1lead")), "plan" to plan(slot("2026-12-01"))))
            .andExpect(status().isNoContent)
        val again = doc("ps-s1a", docId)
        assertThat(again["round"].asInt()).isEqualTo(2)
        assertThat(again["status"].asText()).isEqualTo("IN_PROGRESS")
        assertThat(again["completionPlan"]["slots"].map { it["startDate"].asText() }).containsExactly("2026-10-20", "2026-10-23")
        // 다시 올릴 때도 처음 올릴 때와 같은 "완료 결재중" 상태 알림
        val notices = notificationsOf("ps-req", "WORK_STATUS").map { it.message }
        assertThat(notices.count { it.endsWith(" 완료 결재중") }).isEqualTo(2)
        assertThat(notices.filter { it.endsWith("결재중") }).allMatch { it.endsWith(" 완료 결재중") }
        assertThat(item("ps-s1a", id)["status"].asText()).isEqualTo("IN_APPROVAL")
    }

    @Test
    fun `W-C4 소속 부서원이 완료 결재를 회수하면 계획으로 돌아가 자동 완료가 꺼지고 회수한 사람이 기안자`() {
        val id = plannedItem(slot("2026-10-20"), autoComplete = true)
        completeInApproval(id)
        val docId = myTurnDoc("ps-s1lead")
        postAs("ps-s2a", "/api/approvals/docs/$docId/withdraw").andExpect(status().isNotFound) // 다른 반은 볼 수 없다
        postAs("ps-s1b", "/api/approvals/docs/$docId/withdraw").andExpect(status().isNoContent)
        val back = workItemRepository.findById(id).get()
        assertThat(back.status).isEqualTo(WorkStatuses.PLANNED)
        assertThat(back.autoComplete).isFalse()
        val view = doc("ps-s1b", docId)
        assertThat(view["status"].asText()).isEqualTo("WITHDRAWN")
        assertThat(view["drafterAccountId"].asLong()).isEqualTo(accountIdOf("ps-s1b"))
        assertThat(view["resubmittable"].asBoolean()).isTrue()
    }

    @Test
    fun `W-C5 처리 건 부서원이 아닌 결재자·상위 지휘관은 완료 결재를 올릴 수 없고, 접수 상태(계획 전)에서는 거부`() {
        val id = plannedItem(slot("2026-10-20"))
        postAs("ps-c1cmdr", "/api/work/items/$id/complete-approval", mapOf("steps" to listOf(approve("ps-c1cmdr")))).andExpect(status().isForbidden)
        assertThat(item("ps-s1a", id)["status"].asText()).isEqualTo("PLANNED")
        // 그 건의 접수 결재선에 든 다른 부서(2반) 결재자도 올릴 수 없다 (볼 수는 있음)
        val approved = sendRequest(s1)
        postAs("ps-s1a", "/api/work/items/$approved/receive-approval", mapOf("steps" to line("ps-s1a", approve("ps-s2a")))).andExpect(status().isNoContent)
        postAs("ps-s2a", "/api/approvals/docs/${myTurnDoc("ps-s2a")}/approve").andExpect(status().isNoContent)
        postAs("ps-s1a", "/api/work/items/$approved/plan", mapOf("plan" to plan(slot("2026-10-21")))).andExpect(status().isNoContent)
        assertThat(item("ps-s2a", approved)["canProcess"].asBoolean()).isFalse()
        postAs("ps-s2a", "/api/work/items/$approved/complete-approval", mapOf("steps" to listOf(approve("ps-s2a")))).andExpect(status().isForbidden)
        assertThat(item("ps-s1a", approved)["status"].asText()).isEqualTo("PLANNED")
        val received = receivedItem()
        complete("ps-s1a", received).andExpect(status().isConflict).andExpect(jsonPath("$.code").value("WORK_STATE"))
        // 진행 중 결재가 있으면 409
        completeInApproval(id)
        complete("ps-s1a", id).andExpect(status().isConflict)
    }

    @Test
    fun `W-C6 반려된 완료 결재는 다시 올리기를 기다리는 동안 처리할 문서에 있고, 새 완료 결재를 올리면 앞 문서는 처리한 문서로 간다`() {
        val id = plannedItem(slot("2026-10-20"))
        completeInApproval(id)
        val first = myTurnDoc("ps-s1lead")
        postAs("ps-s1lead", "/api/approvals/docs/$first/reject", mapOf("reason" to "보완")).andExpect(status().isNoContent)
        assertThat(getAs("ps-s1a", "/api/approvals/my-turn").map { it["id"].asLong() }).containsExactly(first)
        postAs("ps-s1a", "/api/work/items/$id/complete-approval", mapOf("steps" to line("ps-s1a", approve("ps-s1lead")))).andExpect(status().isNoContent)
        assertThat(getAs("ps-s1a", "/api/approvals/my-turn").map { it["id"].asLong() }).doesNotContain(first)
        assertThat(getAs("ps-s1a", "/api/approvals/handled").map { it["id"].asLong() }).contains(first)
        assertThat(doc("ps-s1a", first)["resubmittable"].asBoolean()).isFalse()
    }

    @Test
    fun `W-C7 기본 결재 경로 종류는 발송·접수·완료, 완료 경로를 저장해 불러오고 옛 지원 내용·계획 재결재는 400`() {
        val sys = user("ps-sys", s3, "MEMBER", "SYS_ADMIN" to null)
        val steps = listOf(mapOf("stepOrder" to 1, "kind" to "REVIEW", "position" to "SEC_LEAD"), mapOf("stepOrder" to 2, "kind" to "APPROVE", "position" to "CO_CMDR"))
        send("PUT", sys.loginId, "/api/admin/approval-routes", mapOf("departmentId" to b1.id, "docType" to "COMPLETE", "steps" to steps)).andExpect(status().isOk)
        val id = plannedItem(slot("2026-10-20"))
        assertThat(getAs("ps-s1a", "/api/approvals/route-suggestion?docType=COMPLETE&workItemId=$id").map { it["accountId"].asLong() })
            .containsExactly(accountIdOf("ps-s1lead"), accountIdOf("ps-c1cmdr"))
        for (old in listOf("SUPPORT", "REPLAN")) {
            send("PUT", sys.loginId, "/api/admin/approval-routes", mapOf("departmentId" to b1.id, "docType" to old, "steps" to steps))
                .andExpect(status().isBadRequest).andExpect(jsonPath("$.code").value("DOC_TYPE_INVALID"))
            getStatus("ps-s1a", "/api/approvals/route-suggestion?docType=$old&workItemId=$id")
                .andExpect(status().isBadRequest).andExpect(jsonPath("$.code").value("DOC_TYPE_INVALID"))
        }
    }

    @Test
    fun `W-C8 다른 받는 부서 부서원과 발송 결재선에만 든 사람은 완료 결재 문서를 볼 수 없다`() {
        // 받는 부서 둘(1반·2반), 발송 결재선에 다른 대대 ps-out
        val body = mapOf(
            "title" to "두 반 지원", "place" to "통신실", "content" to "지원", "assigneePersonnelIds" to listOf(personnelIdOf("ps-req")), "workStartDate" to "2026-10-20",
            "targets" to workTypeRepository.otherTargets(s1.id, s2.id), "steps" to line("ps-req", approve("ps-out")),
            "viewers" to mapOf("personnelIds" to listOf(personnelIdOf("ps-req"))),
        )
        val request = objectMapper.readTree(postAs("ps-req", "/api/work/requests", body).andExpect(status().isCreated)
            .andReturn().response.getContentAsString(Charsets.UTF_8))["id"].asLong()
        postAs("ps-out", "/api/approvals/docs/${myTurnDoc("ps-out")}/approve").andExpect(status().isNoContent)
        val id = workItemRepository.findByRequestIdOrderByIdAsc(request).first { it.departmentId == s1.id }.id!!
        postAs("ps-s1a", "/api/work/items/$id/receive").andExpect(status().isNoContent)
        postAs("ps-s1a", "/api/work/items/$id/plan", mapOf("plan" to plan(slot("2026-10-20")))).andExpect(status().isNoContent)
        completeInApproval(id)
        val docId = myTurnDoc("ps-s1lead")
        getStatus("ps-s2a", "/api/approvals/docs/$docId").andExpect(status().isNotFound)
        getStatus("ps-out", "/api/approvals/docs/$docId").andExpect(status().isNotFound)
        // 요청자는 요청자 쪽이라 본다
        getStatus("ps-req", "/api/approvals/docs/$docId").andExpect(status().isOk)
    }

    // ───────── 자동 완료 (W-A) ─────────

    @Test
    fun `W-A1 자동 완료를 켜면 끝 칸이 끝난 뒤 켠 사람의 본인 결재로 체계가 완료 결재를 올려 끝낸다`() {
        val id = plannedItem(slot("2026-10-20"), slot("2026-10-22", endTime = "18:00"))
        postAs("ps-s1b", "/api/work/items/$id/auto-complete", mapOf("autoComplete" to true)).andExpect(status().isNoContent)
        assertThat(workItemRepository.findById(id).get().autoCompleteAt).isEqualTo(LocalDateTime.of(2026, 10, 22, 18, 0))
        clock.now = LocalDateTime.of(2026, 10, 22, 17, 59)
        workItemService.autoComplete(id)
        assertThat(item("ps-s1a", id)["status"].asText()).isEqualTo("PLANNED")
        clock.now = LocalDateTime.of(2026, 10, 22, 18, 1)
        workItemService.autoComplete(id)
        val done = workItemRepository.findById(id).get()
        assertThat(done.status).isEqualTo(WorkStatuses.DONE)
        assertThat(done.completedByAuto).isTrue()
        val doc = lastDoc("ps-s1a", id)
        assertThat(doc["docType"].asText()).isEqualTo("COMPLETE")
        assertThat(doc["autoSubmitted"].asBoolean()).isTrue()
        assertThat(doc["drafterAccountId"].asLong()).isEqualTo(accountIdOf("ps-s1b"))
        assertThat(doc["steps"].map { it["kind"].asText() + ":" + it["result"].asText() }).containsExactly("APPROVE:APPROVED")
        assertThat(doc["completionPlan"]["slots"]).hasSize(2)
        // 체계 자동 완료는 "결재중" 상태 알림 없이 "완료" 알림만
        assertThat(notificationsOf("ps-req", "WORK_STATUS").map { it.message }).noneMatch { it.endsWith("결재중") }.anyMatch { it.endsWith(" 완료") }
    }

    @Test
    fun `W-A2 계획을 고쳐 끝 칸의 종료예정일·시각을 바꾸면 바뀐 시각에 완료되고 그 전에는 그대로`() {
        val id = plannedItem(slot("2026-10-20"), autoComplete = true)
        send("PUT", "ps-s1a", "/api/work/items/$id/plan", mapOf("plan" to plan(slot("2026-10-20"), slot("2026-10-24", endTime = "12:00"), autoComplete = true)))
            .andExpect(status().isNoContent)
        clock.now = LocalDateTime.of(2026, 10, 21, 0, 1)
        workItemService.autoComplete(id)
        assertThat(item("ps-s1a", id)["status"].asText()).isEqualTo("PLANNED")
        clock.now = LocalDateTime.of(2026, 10, 24, 12, 0)
        workItemService.autoComplete(id)
        assertThat(item("ps-s1a", id)["status"].asText()).isEqualTo("DONE")
    }

    @Test
    fun `W-A3 자동 완료가 꺼진 건과 완료 결재 중인 건은 시각이 지나도 그대로`() {
        val off = plannedItem(slot("2026-10-20"))
        val inApproval = plannedItem(slot("2026-10-20"), autoComplete = true)
        completeInApproval(inApproval)
        clock.now = LocalDateTime.of(2026, 10, 25, 0, 0)
        workItemService.autoComplete(off)
        workItemService.autoComplete(inApproval)
        assertThat(item("ps-s1a", off)["status"].asText()).isEqualTo("PLANNED")
        assertThat(item("ps-s1a", inApproval)["status"].asText()).isEqualTo("IN_APPROVAL")
        assertThat(item("ps-s1a", inApproval)["docs"].count { it["docType"].asText() == "COMPLETE" }).isEqualTo(1)
    }

    @Test
    fun `W-A4 켠 사람이 다른 부서로 옮기면 완료하지 않고 처리 건 부서원에게 한 번 알리고, 다른 부서원이 다시 켜면 그 사람 본인 결재로 완료`() {
        val id = plannedItem(slot("2026-10-20"), autoComplete = true, planner = "ps-s1a")
        assertThat(workItemRepository.findById(id).get().autoCompleteByAccountId).isEqualTo(accountIdOf("ps-s1a"))
        personnelRepository.findById(personnelIdOf("ps-s1a")).get().departmentId = s2.id!!
        personnelRepository.flush()
        clock.now = LocalDateTime.of(2026, 10, 21, 0, 1)
        workItemService.autoComplete(id)
        assertThat(workItemRepository.findById(id).get().status).isEqualTo(WorkStatuses.PLANNED)
        assertThat(workItemRepository.findById(id).get().autoComplete).isTrue() // 켜진 채로 둔다
        val notice = notificationsOf("ps-s1b", "WORK_STATUS").filter { it.message.startsWith("자동 완료하지 못함") }
        assertThat(notice).hasSize(1)
        assertThat(notice.single().link).isEqualTo("/work/items/$id")
        assertThat(notificationsOf("ps-s1lead", "WORK_STATUS").filter { it.message.startsWith("자동 완료하지 못함") }).hasSize(1)
        // 다음 분에도 알림은 늘지 않는다
        clock.now = LocalDateTime.of(2026, 10, 21, 0, 2)
        workItemService.autoComplete(id)
        assertThat(notificationsOf("ps-s1b", "WORK_STATUS").filter { it.message.startsWith("자동 완료하지 못함") }).hasSize(1)
        // 부서원 ps-s1b가 다시 켜면 그 사람이 켠 사람
        postAs("ps-s1b", "/api/work/items/$id/auto-complete", mapOf("autoComplete" to true)).andExpect(status().isNoContent)
        workItemService.autoComplete(id)
        assertThat(workItemRepository.findById(id).get().status).isEqualTo(WorkStatuses.DONE)
        assertThat(lastDoc("ps-s1b", id)["drafterAccountId"].asLong()).isEqualTo(accountIdOf("ps-s1b"))
    }

    @Test
    fun `W-A5 켠 사람의 계정이 사용 중지되면 같은 알림을 한 번 보내고 완료하지 않는다`() {
        val id = plannedItem(slot("2026-10-20"), autoComplete = true, planner = "ps-s1a")
        accountRepository.findByLoginId("ps-s1a")!!.active = false
        accountRepository.flush()
        clock.now = LocalDateTime.of(2026, 10, 21, 0, 1)
        workItemService.autoComplete(id)
        workItemService.autoComplete(id)
        assertThat(workItemRepository.findById(id).get().status).isEqualTo(WorkStatuses.PLANNED)
        assertThat(notificationsOf("ps-s1b", "WORK_STATUS").filter { it.message.startsWith("자동 완료하지 못함") }).hasSize(1)
    }

    @Test
    fun `W-A6 이미 기간이 끝난 건에 켜면 다음 검사에서 완료`() {
        val id = plannedItem(slot("2026-10-20"))
        clock.now = LocalDateTime.of(2026, 10, 25, 9, 0)
        postAs("ps-s1a", "/api/work/items/$id/auto-complete", mapOf("autoComplete" to true)).andExpect(status().isNoContent)
        assertThat(workItemRepository.findDueForAutoComplete(clock.now)).contains(id)
        workItemService.autoComplete(id)
        assertThat(workItemRepository.findById(id).get().status).isEqualTo(WorkStatuses.DONE)
    }

    @Test
    fun `W-A7 자동 완료를 켠 채 올린 완료 결재가 반려되면 자동 완료가 꺼져 시각이 지나도 그대로`() {
        val id = plannedItem(slot("2026-10-20"), autoComplete = true)
        completeInApproval(id)
        postAs("ps-s1lead", "/api/approvals/docs/${myTurnDoc("ps-s1lead")}/reject", mapOf("reason" to "보완")).andExpect(status().isNoContent)
        assertThat(workItemRepository.findById(id).get().autoComplete).isFalse()
        clock.now = LocalDateTime.of(2026, 10, 25, 0, 0)
        assertThat(workItemRepository.findDueForAutoComplete(clock.now)).doesNotContain(id)
        workItemService.autoComplete(id)
        assertThat(workItemRepository.findById(id).get().status).isEqualTo(WorkStatuses.PLANNED)
    }

    @Test
    fun `W-A8 자동 완료는 처음 꺼져 있고 켤 때 조건 칸은 무시한다`() {
        val id = plannedItem(slot("2026-10-20"))
        assertThat(item("ps-s1a", id)["plan"]["autoComplete"].asBoolean()).isFalse()
        postAs("ps-s1a", "/api/work/items/$id/auto-complete", mapOf("autoComplete" to true, "autoCompleteMode" to "AT_TIME", "autoCompleteAt" to "2026-10-18T00:00"))
            .andExpect(status().isNoContent)
        val on = workItemRepository.findById(id).get()
        assertThat(on.autoCompleteAt).isEqualTo(LocalDateTime.of(2026, 10, 21, 0, 0))
        assertThat(on.autoCompleteByAccountId).isEqualTo(accountIdOf("ps-s1a"))
        postAs("ps-s1a", "/api/work/items/$id/auto-complete", mapOf("autoComplete" to false)).andExpect(status().isNoContent)
        val off = workItemRepository.findById(id).get()
        assertThat(off.autoCompleteAt to off.autoCompleteByAccountId).isEqualTo(null to null)
    }

    @Test
    fun `W-A9 완료 결재 중에는 자동 완료를 켜고 끌 수 없다`() {
        val id = plannedItem(slot("2026-10-20"), autoComplete = true)
        completeInApproval(id)
        postAs("ps-s1a", "/api/work/items/$id/auto-complete", mapOf("autoComplete" to false))
            .andExpect(status().isConflict).andExpect(jsonPath("$.code").value("WORK_STATE"))
        postAs("ps-s1a", "/api/work/items/$id/auto-complete", mapOf("autoComplete" to true))
            .andExpect(status().isConflict).andExpect(jsonPath("$.code").value("WORK_STATE"))
        val still = workItemRepository.findById(id).get()
        assertThat(still.autoComplete to still.autoCompleteByAccountId).isEqualTo(true to accountIdOf("ps-s1a"))
    }

    @Test
    fun `W-A10 켠 사람 기록이 없는 켜진 건(옛 데이터)은 기간이 지나도 완료하지 않고 처리 건 부서원에게 처리 상태 알림을 한 번`() {
        val id = plannedItem(slot("2026-10-20"), autoComplete = true)
        workItemRepository.findById(id).get().autoCompleteByAccountId = null
        workItemRepository.flush()
        clock.now = LocalDateTime.of(2026, 10, 21, 0, 1)
        workItemService.autoComplete(id)
        workItemService.autoComplete(id)
        assertThat(workItemRepository.findById(id).get().status).isEqualTo(WorkStatuses.PLANNED)
        val notice = notificationsOf("ps-s1a", "WORK_STATUS").filter { it.message.startsWith("자동 완료하지 못함") }
        assertThat(notice).hasSize(1)
        assertThat(notice.single().link).isEqualTo("/work/items/$id")
        assertThat(notice.single().message).contains("회선 지원 요청")
        // 처리 건 부서원이 아닌 요청자에게는 가지 않는다
        assertThat(notificationsOf("ps-req", "WORK_STATUS").filter { it.message.startsWith("자동 완료하지 못함") }).isEmpty()
    }

    // ───────── 없앤 결재·전환, 알림, 달력, 배정된 업무 (W-D) ─────────

    @Test
    fun `W-D1 지원 내용 결재·계획 재결재 주소는 없고, 옛 지원 내용 문서는 다시 올릴 수 없다`() {
        val received = receivedItem()
        val body = mapOf("steps" to line("ps-s1a", approve("ps-s1lead")), "plan" to plan(slot("2026-10-20")))
        postAs("ps-s1a", "/api/work/items/$received/plan-approval", body).andExpect(status().isNotFound)
        val planned = plannedItem(slot("2026-10-20"))
        postAs("ps-s1a", "/api/work/items/$planned/replan", body).andExpect(status().isNotFound)
        // 옛 지원 내용 문서(V34 전에 반려된 것)를 SQL로 둔다
        jdbcTemplate.update("INSERT INTO approval_doc (doc_type, work_item_id, drafter_account_id, status, round, with_plan, completed_at) VALUES ('SUPPORT', ?, ?, 'REJECTED', 1, TRUE, NOW())",
            received, accountIdOf("ps-s1a"))
        val old = jdbcTemplate.queryForObject("SELECT MAX(id) FROM approval_doc WHERE work_item_id = ?", Long::class.java, received)!!
        jdbcTemplate.update("INSERT INTO approval_step (doc_id, round, step_order, kind, assignee_account_id, result) VALUES (?, 1, 1, 'APPROVE', ?, 'REJECTED')",
            old, accountIdOf("ps-s1a"))
        assertThat(doc("ps-s1a", old)["resubmittable"].asBoolean()).isFalse()
        assertThat(getAs("ps-s1a", "/api/approvals/my-turn").map { it["id"].asLong() }).doesNotContain(old)
        postAs("ps-s1a", "/api/approvals/docs/$old/resubmit", body).andExpect(status().isConflict).andExpect(jsonPath("$.code").value("DOC_TYPE_RETIRED"))
        assertThat(item("ps-s1a", received)["status"].asText()).isEqualTo("APPROVED")
    }

    @Test
    fun `W-D2 계획 상태에서 결재 없는 완료 주소는 없고 상태는 그대로`() {
        val id = plannedItem(slot("2026-10-20"))
        postAs("ps-s1a", "/api/work/items/$id/complete").andExpect(status().isNotFound)
        assertThat(item("ps-s1a", id)["status"].asText()).isEqualTo("PLANNED")
    }

    @Test
    fun `W-D3 표에 없는 전환 - 접수 대기·접수·완료에서 완료 결재는 409`() {
        val waiting = sendRequest(s1)
        complete("ps-s1a", waiting).andExpect(status().isConflict).andExpect(jsonPath("$.code").value("WORK_STATE"))
        complete("ps-s1a", receivedItem()).andExpect(status().isConflict).andExpect(jsonPath("$.code").value("WORK_STATE"))
        val done = plannedItem(slot("2026-10-20"))
        complete("ps-s1a", done).andExpect(status().isNoContent)
        complete("ps-s1a", done).andExpect(status().isConflict).andExpect(jsonPath("$.code").value("WORK_STATE"))
    }

    @Test
    fun `W-D4 완료 결재가 반려·회수되면 요청자에게 상태 알림`() {
        val rejected = plannedItem(slot("2026-10-20"))
        completeInApproval(rejected)
        postAs("ps-s1lead", "/api/approvals/docs/${myTurnDoc("ps-s1lead")}/reject", mapOf("reason" to "보완")).andExpect(status().isNoContent)
        val withdrawn = plannedItem(slot("2026-10-21"))
        completeInApproval(withdrawn)
        postAs("ps-s1a", "/api/approvals/docs/${lastDoc("ps-s1a", withdrawn)["id"].asLong()}/withdraw").andExpect(status().isNoContent)
        val messages = notificationsOf("ps-req", "WORK_STATUS").map { it.message }
        assertThat(messages).anyMatch { it.endsWith("완료 결재 반려(계획)") }.anyMatch { it.endsWith("완료 결재 회수(계획)") }
    }

    @Test
    fun `W-D5 달력은 계획 칸마다 일정 하나, 지원 인원으로만 보는 사람에게는 자기 칸만, 결재 중 제안·업무 등록 칸은 점선`() {
        // 1중대 처리 건: 첫 칸 11-03(지원 인원 없음), 둘째 칸 11-07(1반 ps-s1b, 1중대 처리 건은 조회 범위 밖)
        val id = plannedItem(slot("2026-11-03"), slot("2026-11-07", people = listOf("ps-s1b")), target = c1, planner = "ps-c1m")
        fun events(loginId: String) = getAs(loginId, "/api/work/calendar?start=2026-11-01&end=2026-11-30")
        val mine = events("ps-c1m").filter { it["workItemId"].asLong() == id }
        assertThat(mine.map { it["slotNo"].asInt() to it["startDate"].asText() }).containsExactlyInAnyOrder(1 to "2026-11-03", 2 to "2026-11-07")
        assertThat(mine.map { it["pending"].asBoolean() }).containsOnly(false)
        val assigned = events("ps-s1b").filter { it["workItemId"].asLong() == id }
        assertThat(assigned.map { it["slotNo"].asInt() }).containsExactly(2)
        // 결재 중인 접수 결재의 제안 칸은 점선(slotNo 있음)
        val proposed = sendRequest(s1)
        postAs("ps-s1a", "/api/work/items/$proposed/receive-approval", mapOf("steps" to line("ps-s1a", approve("ps-s1lead")),
            "plan" to plan(slot("2026-11-10"), slot("2026-11-12"))))
            .andExpect(status().isNoContent)
        val pending = events("ps-s1a").filter { it["workItemId"].asLong() == proposed }
        assertThat(pending.map { it["pending"].asBoolean() to it["slotNo"].asInt() }).containsExactlyInAnyOrder(true to 1, true to 2)
        // 결재 중인 업무 등록: 등록자 달력에 칸마다 점선(처리 건 없음), 발송(등록) 결재선에만 든 사람(다른 대대)에게는 없음
        val register = mapOf(
            "kind" to "REGISTER", "title" to "자체 정비", "place" to "1반", "content" to "점검", "assigneePersonnelIds" to listOf(personnelIdOf("ps-s1a")),
            "targets" to workTypeRepository.otherTargets(s1.id), "workStartDate" to "2026-11-10",
            "supportPlan" to mapOf("slots" to listOf(slot("2026-11-10"), slot("2026-11-12"))),
            "steps" to line("ps-s1a", approve("ps-out")), "viewers" to mapOf("departmentIds" to listOf(s1.id)),
        )
        val request = objectMapper.readTree(postAs("ps-s1a", "/api/work/requests", register).andExpect(status().isCreated)
            .andReturn().response.getContentAsString(Charsets.UTF_8))["id"].asLong()
        val registration = events("ps-s1a").filter { it["requestId"].asLong() == request }
        assertThat(registration.map { it["workItemId"].isNull to it["slotNo"].asInt() }).containsExactlyInAnyOrder(true to 1, true to 2)
        assertThat(registration.map { it["pending"].asBoolean() }).containsOnly(true)
        assertThat(events("ps-out").filter { it["requestId"].asLong() == request }).isEmpty()
    }

    @Test
    fun `W-D6 배정된 업무는 그 사람이 든 칸의 기간이 배정 기간과 겹치는 계획·완료, 칸 기간이 보이고 완료 결재 중은 계획`() {
        // 9월 칸에만 ps-s1b가 든 처리 건(10월 칸은 다른 사람)과 10월 완료 결재 중인 처리 건
        val split = plannedItem(slot("2026-09-28", "2026-09-30", people = listOf("ps-s1b")), slot("2026-10-05", people = listOf("ps-s1a")))
        complete("ps-s1a", split).andExpect(status().isNoContent)
        val pending = plannedItem(slot("2026-10-20", people = listOf("ps-s1b")), slot("2026-10-27", people = listOf("ps-s1b")))
        completeInApproval(pending)
        // 지난달 칸만 가진 계획 상태 건 (REQ-402 "이번 달만 고르면 지난달에 끝난 계획은 보이지 않는다")
        val lastMonthPlan = plannedItem(slot("2026-09-15", people = listOf("ps-s1b")))
        fun assigned(query: String) =
            getAs("ps-s1a", "/api/department/personnel$query")["items"].first { it["id"].asLong() == personnelIdOf("ps-s1b") }["assignedWork"]
        // 이번 달(2026-10): 9월 칸만 든 완료 업무·계획 업무는 안 보이고, 완료 결재 중 건은 계획(done 아님)으로 칸 기간 둘
        val month = assigned("")
        assertThat(month.map { it["workItemId"].asLong() }).containsExactly(pending)
        assertThat(month.single()["done"].asBoolean()).isFalse()
        assertThat(month.single()["periods"].map { it["startDate"].asText() }).containsExactly("2026-10-20", "2026-10-27")
        assertThat(month.single()["startDate"].asText() + "~" + month.single()["endDate"].asText()).isEqualTo("2026-10-20~2026-10-27")
        // 지난달로 바꾸면 완료 업무가 완료 표시와 그 사람이 든 칸 기간으로
        val september = assigned("?from=2026-09-01&to=2026-09-30")
        assertThat(september.map { it["workItemId"].asLong() }).containsExactlyInAnyOrder(split, lastMonthPlan)
        val done = september.first { it["workItemId"].asLong() == split }
        assertThat(done["done"].asBoolean()).isTrue()
        assertThat(done["periods"].map { it["startDate"].asText() + "~" + it["endDate"].asText() }).containsExactly("2026-09-28~2026-09-30")
        assertThat(september.first { it["workItemId"].asLong() == lastMonthPlan }["done"].asBoolean()).isFalse()
    }
}
