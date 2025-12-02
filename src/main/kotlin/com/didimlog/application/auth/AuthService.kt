package com.didimlog.application.auth

import com.didimlog.domain.Student
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
                bojId = bojIdVo,
                password = encodedPassword,
                rating = rating,
                currentTier = initialTier
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

            // JWT 토큰 발급
            val token = jwtTokenProvider.createToken(bojId)
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

        // JWT 토큰 발급
        val token = jwtTokenProvider.createToken(bojId)
        return AuthResult(token, currentStudent.rating, currentStudent.tier())
    }
}

