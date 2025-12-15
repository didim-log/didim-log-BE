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

        // DB에서 사용자 조회
        val existingStudent = studentRepository.findByProviderAndProviderId(provider, providerId)
        
        val attributes = oauth2User.attributes.toMutableMap()
        
        if (existingStudent.isPresent) {
            // 기존 유저: DB에서 조회한 정보 사용
            val student = existingStudent.get()
            val updatedStudent = if (student.email != email) {
                studentRepository.save(student.copy(email = email))
            } else {
                student
            }
            
            val authorities = listOf(SimpleGrantedAuthority("ROLE_${updatedStudent.role.value}"))
            attributes["studentId"] = updatedStudent.id
            attributes["provider"] = provider.value
            attributes["providerId"] = providerId
            attributes["isNewUser"] = false
            attributes["role"] = updatedStudent.role.value
            
            return DefaultOAuth2User(
                authorities,
                attributes,
                getProviderIdAttributeName(provider)
            )
        } else {
            // 신규 유저: DB에 저장하지 않고 정보만 attributes에 저장
            // SuccessHandler에서 쿼리 파라미터로 전달할 정보
            attributes["provider"] = provider.value
            attributes["providerId"] = providerId
            attributes["email"] = email ?: "" // GitHub 비공개 이메일 등 null인 경우 빈 문자열로 처리
            attributes["nickname"] = nickname
            attributes["isNewUser"] = true
            attributes["role"] = "GUEST"
            
            // 신규 유저는 GUEST 권한으로 처리
            val authorities = listOf(SimpleGrantedAuthority("ROLE_GUEST"))
            
            return DefaultOAuth2User(
                authorities,
                attributes,
                getProviderIdAttributeName(provider)
            )
        }
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
