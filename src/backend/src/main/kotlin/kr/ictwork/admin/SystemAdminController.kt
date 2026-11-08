package kr.ictwork.admin

import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.*
import java.security.Principal
import java.util.concurrent.ConcurrentHashMap
import javax.servlet.http.HttpServletRequest

@RestController
class RegistrationController(private val registration: RegistrationService) {
    private val attempts = ConcurrentHashMap<String, Pair<Long, Int>>()
    @PostMapping("/api/registrations")
    @ResponseStatus(HttpStatus.CREATED)
    fun submit(@RequestBody input: RegistrationInput, request: HttpServletRequest): Map<String, Any> {
        val now = System.currentTimeMillis()
        if (attempts.size > 10000) attempts.entries.removeIf { now - it.value.first > 600000 }
        val attempt = attempts.compute(request.remoteAddr) { _, old -> if (old == null || now - old.first > 600000) now to 1 else old.first to old.second + 1 }!!
        if (attempt.second > 20) throw kr.ictwork.common.error.ApiException(HttpStatus.TOO_MANY_REQUESTS, "REGISTRATION_RATE_LIMIT", "등록 신청이 많습니다. 잠시 후 다시 시도하세요.")
        return registration.submit(input)
    }
}

@RestController
@RequestMapping("/api/admin/system")
class SystemAdminController(private val service: SystemAdminService, private val registration: RegistrationService) {
    @GetMapping("/summary") fun summary(principal: Principal) = service.summary(principal.name)
    @GetMapping("/accounts") fun users(principal: Principal, @RequestParam(defaultValue = "") search: String, @RequestParam(defaultValue = "ALL") state: String,
        @RequestParam(defaultValue = "0") page: Int, @RequestParam(defaultValue = "25") pageSize: Int) = service.users(principal.name, search, state, page, pageSize)
    @PostMapping("/accounts/{id}") @ResponseStatus(HttpStatus.NO_CONTENT)
    fun mutate(principal: Principal, @PathVariable id: Long, @RequestBody input: AccountMutation) = service.mutate(principal.name, id, input)
    @GetMapping("/registrations") fun registrations(principal: Principal) = registration.list(principal.name)
    @PostMapping("/registrations/{id}/review") @ResponseStatus(HttpStatus.NO_CONTENT)
    fun review(principal: Principal, @PathVariable id: Long, @RequestBody input: RegistrationReview) = registration.review(principal.name, id, input)
    @GetMapping("/security") fun policy(principal: Principal) = service.policy(principal.name)
    @PutMapping("/security") fun savePolicy(principal: Principal, @RequestBody input: PolicyMutation) = service.savePolicy(principal.name, input)
    @GetMapping("/audit") fun audit(principal: Principal, @RequestParam(defaultValue = "") search: String,
        @RequestParam(defaultValue = "0") page: Int, @RequestParam(defaultValue = "25") pageSize: Int) = service.audit(principal.name, search, page, pageSize)
}
