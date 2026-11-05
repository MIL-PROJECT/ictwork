package kr.ictwork.auth

import kr.ictwork.account.AccountRepository
import org.springframework.security.core.userdetails.User
import org.springframework.security.core.userdetails.UserDetails
import org.springframework.security.core.userdetails.UserDetailsService
import org.springframework.security.core.userdetails.UsernameNotFoundException
import org.springframework.stereotype.Service

@Service
class AccountUserDetailsService(private val accountRepository: AccountRepository) : UserDetailsService {
    override fun loadUserByUsername(username: String): UserDetails {
        val account = accountRepository.findByLoginId(username) ?: throw UsernameNotFoundException(username)
        return User.withUsername(account.loginId)
            .password(account.passwordHash)
            .disabled(!account.active)
            .authorities(emptyList())
            .build()
    }
}
