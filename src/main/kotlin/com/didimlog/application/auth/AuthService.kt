package com.didimlog.application.auth

import com.didimlog.domain.PasswordResetCode
import com.didimlog.domain.Student
import com.didimlog.domain.enums.Provider
import com.didimlog.domain.enums.Role
import com.didimlog.domain.enums.Tier
import com.didimlog.domain.repository.PasswordResetCodeRepository
import com.didimlog.domain.repository.StudentRepository
import com.didimlog.domain.valueobject.BojId
import com.didimlog.domain.valueobject.Nickname
import com.didimlog.global.auth.JwtTokenProvider
import com.didimlog.global.exception.BusinessException
import com.didimlog.global.exception.ErrorCode
import com.didimlog.global.util.PasswordValidator
import com.didimlog.infra.email.EmailService
import com.didimlog.infra.solvedac.SolvedAcClient
import org.slf4j.LoggerFactory
import org.springframework.dao.DuplicateKeyException
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import com.mongodb.MongoWriteException
import kotlin.random.Random
import java.time.LocalDateTime

/**
 * 인증 서비스
 * Solved.ac 연동 기반의 회원가입 및 로그인을 처리한다.
 * 비밀번호는 BCrypt로 암호화하여 저장한다.
 */
@Service
class AuthService(
    private val solvedAcClient: SolvedAcClient,
    private val studentRepository: StudentRepository,
    private val jwtTokenProvider: JwtTokenProvider,
    private val passwordEncoder: PasswordEncoder,
    private val emailService: EmailService,
    private val passwordResetCodeRepository: PasswordResetCodeRepository
) {

    private val log = LoggerFactory.getLogger(AuthService::class.java)

    /**
     * 회원가입 결과 정보
     */
    data class AuthResult(
        val token: String,
        val rating: Int,
        val tier: com.didimlog.domain.enums.Tier
    )

    /**
     * BOJ ID와 비밀번호를 기반으로 회원가입을 처리한다.
     * Solved.ac API를 통해 사용자 정보를 검증하고, 비밀번호를 암호화하여 저장한다.
     *
     * @param bojId BOJ ID
     * @param password 평문 비밀번호
     * @param email 이메일 주소 (필수)
     * @return 인증 결과 (토큰, Rating, Tier)
     * @throws BusinessException BOJ ID가 유효하지 않거나 이미 가입된 경우, 이메일이 중복된 경우
     */
    @Transactional
    fun signup(bojId: String, password: String, email: String): AuthResult {
        val bojIdVo = BojId(bojId)

        try {
            // 비밀번호 복잡도 검증 (암호화 전에 수행)
            PasswordValidator.validate(password)

            // Solved.ac API를 통해 사용자 정보 검증
            val userResponse = try {
                solvedAcClient.fetchUser(bojIdVo)
            } catch (e: IllegalStateException) {
                log.warn("Solved.ac 사용자 조회 실패: bojId=$bojId, message=${e.message}", e)
                throw BusinessException(ErrorCode.COMMON_RESOURCE_NOT_FOUND, "유효하지 않은 BOJ ID입니다. bojId=$bojId")
            } catch (e: Exception) {
                log.error(
                    "Solved.ac API 호출 중 예상치 못한 예외 발생: bojId=$bojId, exceptionType=${e.javaClass.simpleName}, message=${e.message}",
                    e
                )
                throw BusinessException(ErrorCode.COMMON_RESOURCE_NOT_FOUND, "유효하지 않은 BOJ ID입니다. bojId=$bojId")
            }

            // 이미 가입된 사용자인지 확인
            val existingStudent = studentRepository.findByBojId(bojIdVo)
            if (existingStudent.isPresent) {
                throw BusinessException(ErrorCode.COMMON_INVALID_INPUT, "이미 가입된 BOJ ID입니다. bojId=$bojId")
            }

            // 이메일 중복 체크
            val existingEmailStudent = studentRepository.findByEmail(email)
            if (existingEmailStudent.isPresent) {
                throw BusinessException(ErrorCode.COMMON_INVALID_INPUT, "이미 사용 중인 이메일입니다. email=$email")
            }

            // 비밀번호 암호화
            val encodedPassword = passwordEncoder.encode(password)

            // Rating 기반 티어 계산
            val rating = userResponse.rating
            val initialTier = Tier.fromRating(rating)
            
            val nickname = try {
                Nickname(userResponse.handle) // Solved.ac의 handle을 닉네임으로 사용
            } catch (e: IllegalArgumentException) {
                log.error("닉네임 생성 실패: bojId=$bojId, handle=${userResponse.handle}, message=${e.message}", e)
                throw BusinessException(ErrorCode.COMMON_INVALID_INPUT, "유효하지 않은 닉네임입니다. handle=${userResponse.handle}")
            }

            log.info("회원가입 진행: bojId=$bojId, email=$email, nickname=${nickname.value}, rating=$rating, tier=$initialTier")
            
            val student = Student(
                nickname = nickname,
                provider = Provider.BOJ,
                providerId = bojIdVo.value,
                email = email,
                bojId = bojIdVo,
                password = encodedPassword,
                rating = rating,
                currentTier = initialTier,
                role = Role.USER,
                termsAgreed = true // BOJ 회원가입 시 약관 동의 완료
            )

            try {
                studentRepository.save(student)
            } catch (e: MongoWriteException) {
                // MongoDB 중복 키 에러 (에러 코드 11000)
                if (e.code == 11000) {
                    log.error("MongoDB 중복 키 에러 발생: bojId=$bojId, errorCode=${e.code}, message=${e.message}", e)
                    throw BusinessException(ErrorCode.COMMON_INVALID_INPUT, "이미 가입된 BOJ ID입니다. bojId=$bojId")
                }
                log.error("MongoDB 쓰기 에러 발생: bojId=$bojId, errorCode=${e.code}, message=${e.message}", e)
                throw e
            } catch (e: DuplicateKeyException) {
                log.error("중복 키 에러 발생: bojId=$bojId, message=${e.message}", e)
                throw BusinessException(ErrorCode.COMMON_INVALID_INPUT, "이미 가입된 BOJ ID입니다. bojId=$bojId")
            } catch (e: Exception) {
                log.error("Student 저장 중 예외 발생: bojId=$bojId, exceptionType=${e.javaClass.simpleName}, message=${e.message}", e)
                throw e
            }

            // JWT 토큰 발급 (role 정보 포함)
            val token = jwtTokenProvider.createToken(bojId, student.role.value)
            return AuthResult(token, rating, initialTier)
        } catch (e: BusinessException) {
            // BusinessException은 그대로 재발생
            throw e
        } catch (e: Exception) {
            log.error(
                "회원가입 처리 중 예상치 못한 예외 발생: bojId=$bojId, exceptionType=${e.javaClass.simpleName}, message=${e.message}",
                e
            )
            throw e
        }
    }

    /**
     * BOJ ID와 비밀번호를 기반으로 로그인을 처리한다.
     *
     * @param bojId BOJ ID
     * @param password 평문 비밀번호
     * @return 인증 결과 (토큰, Rating, Tier)
     * @throws BusinessException 사용자를 찾을 수 없거나 비밀번호가 일치하지 않는 경우
     */
    @Transactional
    fun login(bojId: String, password: String): AuthResult {
        val bojIdVo = BojId(bojId)

        val student = studentRepository.findByBojId(bojIdVo)
            .orElseThrow {
                BusinessException(ErrorCode.STUDENT_NOT_FOUND, "가입되지 않은 BOJ ID입니다. 회원가입을 진행해주세요. bojId=$bojId")
            }

        // 비밀번호 검증
        if (!student.matchPassword(password, passwordEncoder)) {
            throw BusinessException(ErrorCode.COMMON_INVALID_INPUT, "비밀번호가 일치하지 않습니다.")
        }

        // Rating 및 Tier 정보 동기화
        var currentStudent = student
        try {
            val userResponse = solvedAcClient.fetchUser(bojIdVo)
            val newRating = userResponse.rating
            if (student.rating != newRating) {
                currentStudent = student.updateInfo(newRating)
                studentRepository.save(currentStudent)
                log.info("Rating 및 티어 정보 동기화 완료: bojId=$bojId, 기존 rating=${student.rating}, 새 rating=$newRating, 기존 티어=${student.tier()}, 새 티어=${currentStudent.tier()}")
            }
        } catch (e: IllegalStateException) {
            log.warn("Solved.ac API 호출 실패로 Rating 동기화 건너뜀: bojId=$bojId, message=${e.message}")
            // Solved.ac API 호출 실패 시에도 로그인은 진행 (기존 정보 유지)
        } catch (e: Exception) {
            log.error("Rating 동기화 중 예상치 못한 예외 발생: bojId=$bojId, exceptionType=${e.javaClass.simpleName}, message=${e.message}", e)
            // 예외 발생 시에도 로그인은 진행 (기존 정보 유지)
        }

        // JWT 토큰 발급 (role 정보 포함)
        val token = jwtTokenProvider.createToken(bojId, currentStudent.role.value)
        return AuthResult(token, currentStudent.rating, currentStudent.tier())
    }

    /**
     * 슈퍼 관리자 계정을 생성한다.
     * adminKey가 일치하는 경우에만 ADMIN 권한으로 계정을 생성한다.
     *
     * @param bojId BOJ ID
     * @param password 평문 비밀번호
     * @param email 이메일 주소 (필수)
     * @param adminKey 관리자 생성용 보안 키
     * @return 인증 결과 (토큰, Rating, Tier)
     * @throws BusinessException adminKey가 일치하지 않거나 BOJ ID가 유효하지 않은 경우, 이메일이 중복된 경우
     */
    @Transactional
    fun createSuperAdmin(bojId: String, password: String, email: String, adminKey: String): AuthResult {
        // adminKey 검증은 Controller에서 수행 (여기서는 서비스 로직만 처리)
        val bojIdVo = BojId(bojId)

        try {
            // 비밀번호 복잡도 검증
            PasswordValidator.validate(password)

            // Solved.ac API를 통해 사용자 정보 검증
            val userResponse = try {
                solvedAcClient.fetchUser(bojIdVo)
            } catch (e: IllegalStateException) {
                log.warn("Solved.ac 사용자 조회 실패: bojId=$bojId, message=${e.message}", e)
                throw BusinessException(ErrorCode.COMMON_RESOURCE_NOT_FOUND, "유효하지 않은 BOJ ID입니다. bojId=$bojId")
            } catch (e: Exception) {
                log.error(
                    "Solved.ac API 호출 중 예상치 못한 예외 발생: bojId=$bojId, exceptionType=${e.javaClass.simpleName}, message=${e.message}",
                    e
                )
                throw BusinessException(ErrorCode.COMMON_RESOURCE_NOT_FOUND, "유효하지 않은 BOJ ID입니다. bojId=$bojId")
            }

            // 이미 가입된 사용자인지 확인
            val existingStudent = studentRepository.findByBojId(bojIdVo)
            if (existingStudent.isPresent) {
                throw BusinessException(ErrorCode.COMMON_INVALID_INPUT, "이미 가입된 BOJ ID입니다. bojId=$bojId")
            }

            // 이메일 중복 체크
            val existingEmailStudent = studentRepository.findByEmail(email)
            if (existingEmailStudent.isPresent) {
                throw BusinessException(ErrorCode.COMMON_INVALID_INPUT, "이미 사용 중인 이메일입니다. email=$email")
            }

            // 비밀번호 암호화
            val encodedPassword = passwordEncoder.encode(password)

            // Rating 기반 티어 계산
            val rating = userResponse.rating
            val initialTier = Tier.fromRating(rating)
            
            val nickname = try {
                Nickname(userResponse.handle)
            } catch (e: IllegalArgumentException) {
                log.error("닉네임 생성 실패: bojId=$bojId, handle=${userResponse.handle}, message=${e.message}", e)
                throw BusinessException(ErrorCode.COMMON_INVALID_INPUT, "유효하지 않은 닉네임입니다. handle=${userResponse.handle}")
            }

            log.info("슈퍼 관리자 계정 생성: bojId=$bojId, email=$email, nickname=${nickname.value}, rating=$rating, tier=$initialTier")
            
            val student = Student(
                nickname = nickname,
                provider = Provider.BOJ,
                providerId = bojIdVo.value,
                email = email,
                bojId = bojIdVo,
                password = encodedPassword,
                rating = rating,
                currentTier = initialTier,
                role = Role.ADMIN,
                termsAgreed = true // 관리자는 약관 자동 동의
            )

            try {
                studentRepository.save(student)
            } catch (e: MongoWriteException) {
                if (e.code == 11000) {
                    log.error("MongoDB 중복 키 에러 발생: bojId=$bojId, errorCode=${e.code}, message=${e.message}", e)
                    throw BusinessException(ErrorCode.COMMON_INVALID_INPUT, "이미 가입된 BOJ ID입니다. bojId=$bojId")
                }
                log.error("MongoDB 쓰기 에러 발생: bojId=$bojId, errorCode=${e.code}, message=${e.message}", e)
                throw e
            } catch (e: DuplicateKeyException) {
                log.error("중복 키 에러 발생: bojId=$bojId, message=${e.message}", e)
                throw BusinessException(ErrorCode.COMMON_INVALID_INPUT, "이미 가입된 BOJ ID입니다. bojId=$bojId")
            } catch (e: Exception) {
                log.error("Student 저장 중 예외 발생: bojId=$bojId, exceptionType=${e.javaClass.simpleName}, message=${e.message}", e)
                throw e
            }

            // JWT 토큰 발급 (ADMIN role 포함)
            val token = jwtTokenProvider.createToken(bojId, Role.ADMIN.value)
            return AuthResult(token, rating, initialTier)
        } catch (e: BusinessException) {
            throw e
        } catch (e: Exception) {
            log.error(
                "슈퍼 관리자 계정 생성 중 예상치 못한 예외 발생: bojId=$bojId, exceptionType=${e.javaClass.simpleName}, message=${e.message}",
                e
            )
            throw e
        }
    }

    /**
     * 소셜 로그인 후 가입 마무리를 처리한다.
     * 신규 유저의 경우 Student 엔티티를 생성하고, 약관 동의 및 닉네임 설정을 완료하여 GUEST에서 USER로 역할을 변경한다.
     *
     * @param email 사용자 이메일 (필수)
     * @param provider 소셜 로그인 제공자 (GOOGLE, GITHUB, NAVER)
     * @param providerId 제공자별 사용자 ID
     * @param nickname 설정할 닉네임
     * @param bojId BOJ ID (선택사항, 나중에 연동 가능)
     * @param termsAgreed 약관 동의 여부
     * @return 인증 결과 (토큰, Rating, Tier)
     * @throws BusinessException 약관 동의가 false이거나 닉네임이 중복되는 경우, 이메일이 중복된 경우
     */
    @Transactional
    fun finalizeSignup(
        email: String,
        provider: String,
        providerId: String,
        nickname: String,
        bojId: String?,
        termsAgreed: Boolean
    ): AuthResult {
        // 약관 동의 확인
        if (!termsAgreed) {
            throw BusinessException(ErrorCode.COMMON_INVALID_INPUT, "약관 동의는 필수입니다.")
        }

        if (bojId.isNullOrBlank()) {
            throw BusinessException(ErrorCode.COMMON_INVALID_INPUT, "백준 아이디는 필수입니다.")
        }

        val bojIdVo = BojId(bojId)

        val userResponse = try {
            solvedAcClient.fetchUser(bojIdVo)
        } catch (e: IllegalStateException) {
            log.warn("Solved.ac 사용자 조회 실패: bojId=$bojId, message=${e.message}", e)
            throw BusinessException(ErrorCode.COMMON_RESOURCE_NOT_FOUND, "유효하지 않은 BOJ ID입니다. bojId=$bojId")
        } catch (e: Exception) {
            log.error(
                "Solved.ac API 호출 중 예상치 못한 예외 발생: bojId=$bojId, exceptionType=${e.javaClass.simpleName}, message=${e.message}",
                e
            )
            throw BusinessException(ErrorCode.COMMON_RESOURCE_NOT_FOUND, "유효하지 않은 BOJ ID입니다. bojId=$bojId")
        }

        // Provider Enum 변환
        val providerEnum = try {
            Provider.valueOf(provider.uppercase())
        } catch (e: IllegalArgumentException) {
            throw BusinessException(ErrorCode.COMMON_INVALID_INPUT, "유효하지 않은 프로바이더입니다. provider=$provider")
        }

        // 이미 가입된 사용자인지 확인 (provider + providerId로 조회)
        val existingStudent = studentRepository.findByProviderAndProviderId(providerEnum, providerId)

        // BOJ ID 중복 체크 (본인 계정 제외)
        val existingBojOwner = studentRepository.findByBojId(bojIdVo)
        if (existingBojOwner.isPresent) {
            val isSameAccount = existingStudent.isPresent && existingBojOwner.get().id == existingStudent.get().id
            if (!isSameAccount) {
                throw BusinessException(ErrorCode.DUPLICATE_BOJ_ID)
            }
        }

        // 이메일 중복 체크 (본인 계정 제외)
        val existingEmailStudent = studentRepository.findByEmail(email)
        if (existingEmailStudent.isPresent) {
            val isSameAccount = existingStudent.isPresent && existingEmailStudent.get().id == existingStudent.get().id
            if (!isSameAccount) {
                throw BusinessException(ErrorCode.COMMON_INVALID_INPUT, "이미 사용 중인 이메일입니다. email=$email")
            }
        }
        
        val savedStudent = if (existingStudent.isPresent) {
            // 기존 유저: 닉네임 및 약관 동의 업데이트
            val student = existingStudent.get()
            val nicknameVo = Nickname(nickname)
            
            // 닉네임 중복 체크 (다른 사용자가 사용 중인 경우)
            if (studentRepository.existsByNickname(nicknameVo) && student.nickname != nicknameVo) {
                throw BusinessException(ErrorCode.COMMON_INVALID_INPUT, "이미 사용 중인 닉네임입니다. nickname=$nickname")
            }
            
            val finalizedStudent = student.finalizeSignup(nickname, bojIdVo, email, termsAgreed)
            studentRepository.save(finalizedStudent)
        } else {
            // 신규 유저: Student 엔티티 생성
            val nicknameVo = Nickname(nickname)
            
            // 닉네임 중복 체크
            if (studentRepository.existsByNickname(nicknameVo)) {
                throw BusinessException(ErrorCode.COMMON_INVALID_INPUT, "이미 사용 중인 닉네임입니다. nickname=$nickname")
            }
            
            val rating = userResponse.rating
            val tier = Tier.fromRating(rating)
            
            val newStudent = Student(
                nickname = nicknameVo,
                provider = providerEnum,
                providerId = providerId,
                email = email,
                bojId = bojIdVo,
                password = null,
                rating = rating,
                currentTier = tier,
                role = Role.USER, // 약관 동의 완료 시 USER로 설정
                termsAgreed = true
            )
            
            try {
                studentRepository.save(newStudent)
            } catch (e: MongoWriteException) {
                if (e.code == 11000) {
                    log.error("MongoDB 중복 키 에러 발생: provider=$provider, providerId=$providerId, errorCode=${e.code}, message=${e.message}", e)
                    throw BusinessException(ErrorCode.COMMON_INVALID_INPUT, "이미 가입된 계정입니다. provider=$provider, providerId=$providerId")
                }
                log.error("MongoDB 쓰기 에러 발생: provider=$provider, providerId=$providerId, errorCode=${e.code}, message=${e.message}", e)
                throw e
            } catch (e: DuplicateKeyException) {
                log.error("중복 키 에러 발생: provider=$provider, providerId=$providerId, message=${e.message}", e)
                throw BusinessException(ErrorCode.COMMON_INVALID_INPUT, "이미 가입된 계정입니다. provider=$provider, providerId=$providerId")
            }
        }

        // 정식 Access Token 발급 (USER role 포함)
        val token = jwtTokenProvider.createToken(bojIdVo.value, Role.USER.value)
        return AuthResult(token, savedStudent.rating, savedStudent.tier())
    }

    /**
     * 이메일을 입력받아 해당 이메일을 가진 사용자의 BOJ ID를 이메일로 전송한다.
     *
     * @param email 사용자 이메일
     * @throws BusinessException 해당 이메일을 가진 사용자가 없는 경우
     */
    @Transactional(readOnly = true)
    fun findId(email: String) {
        val student = studentRepository.findByEmail(email)
            .orElseThrow {
                BusinessException(ErrorCode.STUDENT_NOT_FOUND, "해당 이메일로 가입된 계정을 찾을 수 없습니다. email=$email")
            }

        val bojId = student.bojId?.value
            ?: throw BusinessException(ErrorCode.COMMON_INVALID_INPUT, "BOJ ID가 등록되지 않은 계정입니다. email=$email")

        val subject = "[디딤로그] 아이디 찾기"
        val variables = mapOf(
            "nickname" to student.nickname.value,
            "bojId" to bojId
        )

        emailService.sendTemplateEmail(email, subject, "mail/find-id", variables)
        log.info("아이디 찾기 이메일 발송 완료: email=$email, bojId=$bojId")
    }

    /**
     * 이메일과 BOJ ID를 입력받아 일치하는 사용자가 있으면 비밀번호 재설정 코드를 생성하여 저장하고 이메일로 발송한다.
     *
     * @param email 사용자 이메일
     * @param bojId BOJ ID
     * @throws BusinessException 해당 이메일과 BOJ ID로 가입된 사용자가 없는 경우
     */
    @Transactional(readOnly = true)
    fun findPassword(email: String, bojId: String) {
        val bojIdVo = BojId(bojId)
        val student = studentRepository.findByEmail(email)
            .orElseThrow {
                BusinessException(ErrorCode.STUDENT_NOT_FOUND, "해당 이메일로 가입된 계정을 찾을 수 없습니다. email=$email")
            }

        if (student.bojId != bojIdVo) {
            throw BusinessException(ErrorCode.COMMON_INVALID_INPUT, "이메일과 BOJ ID가 일치하지 않습니다. email=$email, bojId=$bojId")
        }

        if (student.password == null) {
            throw BusinessException(ErrorCode.COMMON_INVALID_INPUT, "비밀번호가 설정되지 않은 계정입니다. 소셜 로그인 계정은 비밀번호 찾기를 사용할 수 없습니다. email=$email")
        }

        val studentId = student.id
            ?: throw BusinessException(ErrorCode.COMMON_INTERNAL_ERROR, "학생 ID를 찾을 수 없습니다. email=$email")

        val resetCode = generateResetCode()
        val expiresAt = LocalDateTime.now().plusMinutes(30)

        val passwordResetCode = PasswordResetCode(
            resetCode = resetCode,
            studentId = studentId,
            expiresAt = expiresAt
        )
        passwordResetCodeRepository.save(passwordResetCode)

        val subject = "[디딤로그] 비밀번호 재설정"
        val variables = mapOf(
            "nickname" to student.nickname.value,
            "email" to email,
            "bojId" to bojId,
            "resetCode" to resetCode
        )

        emailService.sendTemplateEmail(email, subject, "mail/find-password", variables)
        log.info("비밀번호 재설정 코드 이메일 발송 완료: email=$email, bojId=$bojId")
    }

    /**
     * 비밀번호 재설정 코드와 새 비밀번호를 입력받아 비밀번호를 변경한다.
     *
     * @param resetCode 재설정 코드
     * @param newPassword 새 비밀번호
     * @throws BusinessException 재설정 코드가 유효하지 않거나 만료된 경우, 비밀번호 정책 위반 시
     */
    @Transactional
    fun resetPassword(resetCode: String, newPassword: String) {
        val passwordResetCode = passwordResetCodeRepository.findByResetCode(resetCode)
            .orElseThrow {
                BusinessException(ErrorCode.COMMON_INVALID_INPUT, "유효하지 않은 재설정 코드입니다.")
            }

        if (passwordResetCode.isExpired()) {
            throw BusinessException(ErrorCode.COMMON_INVALID_INPUT, "만료된 재설정 코드입니다.")
        }

        val student = studentRepository.findById(passwordResetCode.studentId)
            .orElseThrow {
                BusinessException(ErrorCode.STUDENT_NOT_FOUND, "학생을 찾을 수 없습니다. studentId=${passwordResetCode.studentId}")
            }

        PasswordValidator.validate(newPassword)
        val encodedPassword = passwordEncoder.encode(newPassword)

        val updatedStudent = student.copy(password = encodedPassword)
        studentRepository.save(updatedStudent)

        passwordResetCodeRepository.deleteByResetCode(resetCode)

        log.info("비밀번호 재설정 완료: studentId=${passwordResetCode.studentId}")
    }

    /**
     * 영문+숫자 조합의 8자리 비밀번호 재설정 코드를 생성한다.
     *
     * @return 재설정 코드 문자열
     */
    private fun generateResetCode(): String {
        val chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"
        return (1..8)
            .map { chars[Random.nextInt(chars.length)] }
            .joinToString("")
    }
}
