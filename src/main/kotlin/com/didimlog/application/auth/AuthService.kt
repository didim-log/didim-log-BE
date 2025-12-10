package com.didimlog.application.auth

import com.didimlog.domain.Student
import com.didimlog.domain.enums.Provider
import com.didimlog.domain.enums.Role
import com.didimlog.domain.enums.Tier
import com.didimlog.domain.repository.StudentRepository
import com.didimlog.domain.valueobject.BojId
import com.didimlog.domain.valueobject.Nickname
import com.didimlog.global.auth.JwtTokenProvider
import com.didimlog.global.exception.BusinessException
import com.didimlog.global.exception.ErrorCode
import com.didimlog.global.util.PasswordValidator
import com.didimlog.infra.solvedac.SolvedAcClient
import org.slf4j.LoggerFactory
import org.springframework.dao.DuplicateKeyException
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import com.mongodb.MongoWriteException

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
    private val passwordEncoder: PasswordEncoder
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
     * @return 인증 결과 (토큰, Rating, Tier)
     * @throws BusinessException BOJ ID가 유효하지 않거나 이미 가입된 경우
     */
    @Transactional
    fun signup(bojId: String, password: String): AuthResult {
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

            log.info("회원가입 진행: bojId=$bojId, nickname=${nickname.value}, rating=$rating, tier=$initialTier")
            
            val student = Student(
                nickname = nickname,
                provider = Provider.BOJ,
                providerId = bojIdVo.value,
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
     * @param adminKey 관리자 생성용 보안 키
     * @return 인증 결과 (토큰, Rating, Tier)
     * @throws BusinessException adminKey가 일치하지 않거나 BOJ ID가 유효하지 않은 경우
     */
    @Transactional
    fun createSuperAdmin(bojId: String, password: String, adminKey: String): AuthResult {
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

            log.info("슈퍼 관리자 계정 생성: bojId=$bojId, nickname=${nickname.value}, rating=$rating, tier=$initialTier")
            
            val student = Student(
                nickname = nickname,
                provider = Provider.BOJ,
                providerId = bojIdVo.value,
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
     * 약관 동의 및 닉네임 설정을 완료하고, GUEST에서 USER로 역할을 변경한다.
     *
     * @param studentId 현재 사용자 ID (JWT 토큰에서 추출)
     * @param nickname 설정할 닉네임
     * @param termsAgreed 약관 동의 여부
     * @return 인증 결과 (토큰, Rating, Tier)
     * @throws BusinessException 사용자를 찾을 수 없거나 약관 동의가 false인 경우
     */
    @Transactional
    fun finalizeSignup(studentId: String, nickname: String, termsAgreed: Boolean): AuthResult {
        val student = studentRepository.findById(studentId)
            .orElseThrow {
                BusinessException(ErrorCode.STUDENT_NOT_FOUND, "사용자를 찾을 수 없습니다. studentId=$studentId")
            }

        // 약관 동의 확인
        if (!termsAgreed) {
            throw BusinessException(ErrorCode.COMMON_INVALID_INPUT, "약관 동의는 필수입니다.")
        }

        // 닉네임 중복 체크
        val nicknameVo = Nickname(nickname)
        if (studentRepository.existsByNickname(nicknameVo) && student.nickname != nicknameVo) {
            throw BusinessException(ErrorCode.COMMON_INVALID_INPUT, "이미 사용 중인 닉네임입니다. nickname=$nickname")
        }

        // 가입 마무리 수행
        val finalizedStudent = student.finalizeSignup(nickname, termsAgreed)
        val savedStudent = studentRepository.save(finalizedStudent)

        // 정식 Access Token 재발급 (USER role 포함)
        val token = jwtTokenProvider.createToken(savedStudent.id!!, Role.USER.value)
        return AuthResult(token, savedStudent.rating, savedStudent.tier())
    }
}

