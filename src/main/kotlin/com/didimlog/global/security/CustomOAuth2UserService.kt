package com.didimlog.global.security

import com.didimlog.domain.Student
import com.didimlog.domain.enums.Provider
import com.didimlog.domain.enums.Role
import com.didimlog.domain.enums.Tier
import com.didimlog.domain.repository.StudentRepository
import com.didimlog.domain.valueobject.Nickname
import com.didimlog.global.auth.oauth.info.OAuth2UserInfoFactory
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.oauth2.client.userinfo.DefaultOAuth2UserService
import org.springframework.security.oauth2.client.userinfo.OAuth2UserRequest
import org.springframework.security.oauth2.core.OAuth2AuthenticationException
import org.springframework.security.oauth2.core.user.DefaultOAuth2User
import org.springframework.security.oauth2.core.user.OAuth2User
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * OAuth2 소셜 로그인 사용자 정보를 처리하는 서비스
 * 소셜 로그인 성공 시 DB에서 사용자를 조회하거나 생성한다.
 */
@Service
class CustomOAuth2UserService(
    private val studentRepository: StudentRepository
) : DefaultOAuth2UserService() {

    @Transactional
    override fun loadUser(userRequest: OAuth2UserRequest): OAuth2User {
        val oauth2User = super.loadUser(userRequest)
        val registrationId = userRequest.clientRegistration.registrationId.lowercase()
        
        // OAuth2UserInfo 팩토리를 사용하여 제공자별 사용자 정보 추출
        val oauth2UserInfo = OAuth2UserInfoFactory.create(registrationId, oauth2User.attributes)
        
        val provider = oauth2UserInfo.getProvider()
        val providerId = oauth2UserInfo.getProviderId()
        val email = oauth2UserInfo.getEmail()
        val nickname = oauth2UserInfo.getName()

        // DB에서 사용자 조회 또는 생성
        val (student, isNewUser) = findOrCreateStudent(provider, providerId, email, nickname)

        val authorities = listOf(SimpleGrantedAuthority("ROLE_${student.role.value}"))
        val attributes = oauth2User.attributes.toMutableMap()
        
        // Student 정보를 attributes에 추가하여 SuccessHandler에서 사용할 수 있도록 함
        attributes["studentId"] = student.id
        attributes["provider"] = provider.value
        attributes["providerId"] = providerId
        attributes["isNewUser"] = isNewUser
        attributes["role"] = student.role.value

        return DefaultOAuth2User(
            authorities,
            attributes,
            getProviderIdAttributeName(provider)
        )
    }

    /**
     * DB에서 사용자를 조회하거나 생성한다.
     *
     * @param provider 소셜 로그인 제공자
     * @param providerId 제공자별 사용자 ID
     * @param email 사용자 이메일 (nullable)
     * @param nickname 사용자 닉네임
     * @return Pair<Student, Boolean> (Student 엔티티, 신규 사용자 여부)
     */
    private fun findOrCreateStudent(
        provider: Provider,
        providerId: String,
        email: String?,
        nickname: String
    ): Pair<Student, Boolean> {
        val existingStudent = studentRepository.findByProviderAndProviderId(provider, providerId)

        if (existingStudent.isPresent) {
            val student = existingStudent.get()
            // 이메일이 변경되었을 수 있으므로 업데이트
            val updatedStudent = if (student.email != email) {
                studentRepository.save(student.copy(email = email))
            } else {
                student
            }
            return Pair(updatedStudent, false)
        }

        // 신규 유저 생성 (GUEST 역할, BOJ 미연동 상태, 약관 미동의)
        val nicknameVo = Nickname(nickname)
        val newStudent = Student(
            nickname = nicknameVo,
            provider = provider,
            providerId = providerId,
            email = email,
            bojId = null,
            currentTier = Tier.BRONZE,
            role = Role.GUEST,
            termsAgreed = false // 소셜 로그인 직후에는 약관 미동의 상태
        )

        val savedStudent = studentRepository.save(newStudent)
        return Pair(savedStudent, true)
    }

    /**
     * 제공자별로 OAuth2User의 name attribute 키를 반환한다.
     * Spring Security가 사용자 식별에 사용한다.
     *
     * @param provider 소셜 로그인 제공자
     * @return name attribute 키
     */
    private fun getProviderIdAttributeName(provider: Provider): String {
        return when (provider) {
            Provider.GOOGLE -> "sub"
            Provider.GITHUB -> "id"
            Provider.NAVER -> "response"
            Provider.BOJ -> throw OAuth2AuthenticationException("BOJ는 OAuth2를 지원하지 않습니다.")
        }
    }
}
