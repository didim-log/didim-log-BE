package com.didimlog.application.auth

import com.didimlog.application.auth.boj.BojOwnershipVerificationService
import com.didimlog.domain.PasswordResetCode
import com.didimlog.domain.Student
import com.didimlog.domain.enums.Provider
import com.didimlog.domain.enums.Role
import com.didimlog.domain.enums.Tier
import com.didimlog.domain.repository.PasswordResetCodeRepository
import com.didimlog.domain.repository.StudentRepository
import com.didimlog.domain.valueobject.BojId
import com.didimlog.domain.valueobject.Nickname
import com.didimlog.domain.valueobject.SolvedAcTierLevel
import com.didimlog.global.auth.JwtTokenProvider
import com.didimlog.global.config.mongo.MongoIndexInitializer
import com.didimlog.global.exception.BusinessException
import com.didimlog.global.exception.ErrorCode
import com.didimlog.global.util.PasswordValidator
import com.didimlog.global.util.SensitiveDataMasker
import com.didimlog.infra.email.EmailService
import com.didimlog.infra.solvedac.SolvedAcUserResponse
import com.didimlog.infra.solvedac.SolvedAcClient
import com.mongodb.MongoCommandException
import com.mongodb.MongoWriteException
import org.slf4j.LoggerFactory
import org.springframework.dao.DuplicateKeyException
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
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
    private val passwordResetCodeRepository: PasswordResetCodeRepository,
    private val passwordResetCodeGenerator: PasswordResetCodeGenerator,
    private val refreshTokenService: RefreshTokenService,
    private val bojOwnershipVerificationService: BojOwnershipVerificationService,
    private val credentialSessionCoordinator: CredentialSessionCoordinator
) {

    private val log = LoggerFactory.getLogger(AuthService::class.java)

    /**
     * 회원가입 결과 정보
     */
    data class AuthResult(
        val token: String,
        val refreshToken: String,
        val rating: Int,
        val tier: com.didimlog.domain.enums.Tier,
        val tierLevel: Int
    )

    /**
     * BOJ ID와 비밀번호를 기반으로 회원가입을 처리한다.
     * Solved.ac API를 통해 사용자 정보를 검증하고, 비밀번호를 암호화하여 저장한다.
     *
     * @param bojId BOJ ID
     * @param password 평문 비밀번호
     * @param email 이메일 주소 (필수)
     * @param verificationSessionId BOJ 소유권 인증을 마친 세션 ID
     * @return 인증 결과 (토큰, Rating, Tier)
     * @throws BusinessException BOJ ID가 유효하지 않거나 이미 가입된 경우, 이메일이 중복된 경우
     */
    @Transactional
    fun signup(bojId: String, password: String, email: String, verificationSessionId: String): AuthResult {
        return registerBojAccount(bojId, password, email, Role.USER, verificationSessionId)
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
        val studentId = requireNotNull(student.id) { "저장된 학생 ID가 없습니다." }

        // Rating 및 Tier 정보 동기화
        try {
            val userResponse = solvedAcClient.fetchUser(bojIdVo)
            val newRating = userResponse.rating
            val newTierLevel = SolvedAcTierLevel.fromRating(newRating)
            val updatedStudent = student.updateSolvedAcProfile(newRating, newTierLevel)
            if (
                student.rating != updatedStudent.rating ||
                student.solvedAcTierLevel != updatedStudent.solvedAcTierLevel ||
                student.currentTier != updatedStudent.currentTier
            ) {
                studentRepository.updateSolvedAcProfileById(
                    studentId = studentId,
                    expectedBojId = bojIdVo,
                    rating = updatedStudent.rating,
                    solvedAcTierLevel = updatedStudent.solvedAcTierLevel,
                    currentTier = updatedStudent.currentTier
                ) ?: throw BusinessException(
                    ErrorCode.STUDENT_NOT_FOUND,
                    "프로필을 갱신할 학생을 찾을 수 없습니다. bojId=$bojId"
                )
                log.info(
                    "Rating 및 티어 정보 동기화 완료: bojId={}, oldRating={}, newRating={}, oldTierLevel={}, newTierLevel={}",
                    bojId,
                    student.rating,
                    newRating,
                    student.solvedAcTierLevel.value,
                    newTierLevel.value
                )
            }
        } catch (e: BusinessException) {
            throw e
        } catch (e: IllegalStateException) {
            log.warn("Solved.ac API 호출 실패로 Rating 동기화 건너뜀: bojId=$bojId, message=${e.message}")
            // Solved.ac API 호출 실패 시에도 로그인은 진행 (기존 정보 유지)
        } catch (e: Exception) {
            log.error("Rating 동기화 중 예상치 못한 예외 발생: bojId=$bojId, exceptionType=${e.javaClass.simpleName}, message=${e.message}", e)
            // 예외 발생 시에도 로그인은 진행 (기존 정보 유지)
        }

        return credentialSessionCoordinator.executeWithCompletionCheck(studentId) {
            val latestStudent = studentRepository.findById(studentId)
                .orElseThrow {
                    BusinessException(ErrorCode.STUDENT_NOT_FOUND, "사용자를 찾을 수 없습니다. bojId=$bojId")
                }
            val latestBojId = latestStudent.bojId?.value
            if (latestBojId != bojId) {
                throw BusinessException(
                    ErrorCode.STUDENT_NOT_FOUND,
                    "사용자를 찾을 수 없습니다. bojId=$bojId"
                )
            }
            if (!latestStudent.matchPassword(password, passwordEncoder)) {
                throw BusinessException(ErrorCode.COMMON_INVALID_INPUT, "비밀번호가 일치하지 않습니다.")
            }

            val token = jwtTokenProvider.createToken(
                subject = latestBojId,
                studentId = studentId,
                credentialVersion = latestStudent.credentialVersion,
                role = latestStudent.role.value
            )
            val refreshToken = refreshTokenService.generateAndSave(latestStudent)

            AuthResult(
                token = token,
                refreshToken = refreshToken,
                rating = latestStudent.rating,
                tier = latestStudent.tier(),
                tierLevel = latestStudent.solvedAcTierLevel.value
            )
        }
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
        return registerBojAccount(bojId, password, email, Role.ADMIN)
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
        validateSignupFinalize(termsAgreed, bojId)

        val bojIdValue = bojId!!.trim()
        val bojIdVo = BojId(bojIdValue)

        val userResponse = fetchSolvedAcUserOrThrow(bojIdVo, bojIdValue)
        val providerEnum = parseProviderOrThrow(provider)

        val existingStudent = studentRepository.findByProviderAndProviderId(providerEnum, providerId)
        validateBojIdOwnershipOrThrow(existingStudent.orElse(null), bojIdVo)
        validateEmailOwnershipOrThrow(existingStudent.orElse(null), email)

        if (existingStudent.isPresent) {
            val saved = finalizeExistingStudent(existingStudent.get(), nickname, bojIdVo, email)
            return issueUserToken(saved, bojIdVo.value)
        }

        val saved = createNewStudent(
            provider = providerEnum,
            providerId = providerId,
            nickname = nickname,
            bojIdVo = bojIdVo,
            email = email,
            rating = userResponse.rating,
            tierLevel = SolvedAcTierLevel.fromRating(userResponse.rating).value
        )
        return issueUserToken(saved, bojIdVo.value)
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
                BusinessException(ErrorCode.STUDENT_NOT_FOUND, "해당 이메일로 가입된 계정을 찾을 수 없습니다.")
            }

        val bojId = student.bojId?.value
            ?: throw BusinessException(ErrorCode.COMMON_INVALID_INPUT, "BOJ ID가 등록되지 않은 계정입니다.")

        val variables = mapOf(
            "nickname" to student.nickname.value,
            "bojId" to bojId
        )

        emailService.sendTemplateEmail(email, FIND_ID_MAIL_SUBJECT, FIND_ID_MAIL_TEMPLATE, variables)
        log.info(
            "아이디 찾기 이메일 발송 완료: email={}, bojId={}",
            SensitiveDataMasker.maskEmail(email),
            SensitiveDataMasker.maskId(bojId)
        )
    }

    /**
     * 이메일과 BOJ ID를 입력받아 일치하는 사용자가 있으면 비밀번호 재설정 코드를 생성하여 저장하고 이메일로 발송한다.
     *
     * @param email 사용자 이메일
     * @param bojId BOJ ID
     * @throws BusinessException 해당 이메일과 BOJ ID로 가입된 사용자가 없는 경우
     */
    fun findPassword(email: String, bojId: String) {
        val bojIdVo = BojId(bojId)
        val student = studentRepository.findByEmail(email)
            .orElseThrow {
                BusinessException(ErrorCode.STUDENT_NOT_FOUND, "해당 이메일로 가입된 계정을 찾을 수 없습니다.")
            }

        if (student.bojId != bojIdVo) {
            throw BusinessException(ErrorCode.COMMON_INVALID_INPUT, "이메일과 BOJ ID가 일치하지 않습니다.")
        }

        if (student.password == null) {
            throw BusinessException(ErrorCode.COMMON_INVALID_INPUT, "비밀번호가 설정되지 않은 계정입니다. 소셜 로그인 계정은 비밀번호 찾기를 사용할 수 없습니다.")
        }

        val studentId = student.id
            ?: throw BusinessException(ErrorCode.COMMON_INTERNAL_ERROR, "학생 ID를 찾을 수 없습니다.")

        val (latestStudent, passwordResetCode) = credentialSessionCoordinator.execute(studentId) {
            val currentStudent = studentRepository.findById(studentId)
                .orElseThrow {
                    BusinessException(ErrorCode.STUDENT_NOT_FOUND, "비밀번호를 재설정할 계정을 찾을 수 없습니다.")
                }
            if (currentStudent.email != email || currentStudent.bojId != bojIdVo) {
                throw BusinessException(ErrorCode.COMMON_INVALID_INPUT, "이메일과 BOJ ID가 일치하지 않습니다.")
            }
            if (currentStudent.password == null) {
                throw BusinessException(
                    ErrorCode.COMMON_INVALID_INPUT,
                    "비밀번호가 설정되지 않은 계정입니다. 소셜 로그인 계정은 비밀번호 찾기를 사용할 수 없습니다."
                )
            }
            currentStudent to issuePasswordResetCode(
                studentId = studentId,
                credentialVersion = currentStudent.credentialVersion,
                bojId = requireNotNull(currentStudent.bojId).value
            )
        }

        val variables = mapOf(
            "nickname" to latestStudent.nickname.value,
            "email" to email,
            "bojId" to bojId,
            "resetCode" to passwordResetCode.resetCode
        )

        try {
            emailService.sendTemplateEmail(email, RESET_PASSWORD_MAIL_SUBJECT, RESET_PASSWORD_MAIL_TEMPLATE, variables)
        } catch (exception: Exception) {
            compensatePasswordResetCodeAfterMailFailure(passwordResetCode, exception)
            throw exception
        }
        log.info(
            "비밀번호 재설정 코드 이메일 발송 완료: email={}, bojId={}",
            SensitiveDataMasker.maskEmail(email),
            SensitiveDataMasker.maskId(bojId)
        )
    }

    /**
     * 비밀번호 재설정 코드와 새 비밀번호를 입력받아 비밀번호를 변경한다.
     *
     * @param resetCode 재설정 코드
     * @param newPassword 새 비밀번호
     * @throws BusinessException 재설정 코드가 유효하지 않거나 만료된 경우, 비밀번호 정책 위반 시
     */
    fun resetPassword(resetCode: String, newPassword: String) {
        PasswordValidator.validate(newPassword)

        val resetCodeSnapshot = passwordResetCodeRepository.findByResetCode(resetCode)
            .orElseThrow {
                BusinessException(
                    ErrorCode.COMMON_INVALID_INPUT,
                    "유효하지 않은 재설정 코드입니다."
                )
            }

        credentialSessionCoordinator.execute(resetCodeSnapshot.studentId) {
            val student = studentRepository.findById(resetCodeSnapshot.studentId)
                .orElseGet {
                    passwordResetCodeRepository.consumeByResetCode(
                        resetCode,
                        resetCodeSnapshot.studentId
                    )
                    throw BusinessException(
                        ErrorCode.STUDENT_NOT_FOUND,
                        "학생을 찾을 수 없습니다. studentId=${resetCodeSnapshot.studentId}"
                    )
                }
            validatePasswordResetCodeContext(resetCodeSnapshot, student)

            val passwordResetCode = passwordResetCodeRepository.consumeByResetCode(
                resetCode,
                resetCodeSnapshot.studentId
            )
                ?: throw BusinessException(
                    ErrorCode.COMMON_INVALID_INPUT,
                    "유효하지 않은 재설정 코드입니다."
                )
            validatePasswordResetCodeContext(passwordResetCode, student)
            if (passwordResetCode.isExpired()) {
                throw BusinessException(ErrorCode.COMMON_INVALID_INPUT, "만료된 재설정 코드입니다.")
            }

            if (student.bojId == null) {
                throw BusinessException(
                    ErrorCode.COMMON_INVALID_INPUT,
                    "BOJ ID가 등록되지 않은 계정입니다."
                )
            }
            val encodedPassword = passwordEncoder.encode(newPassword)

            if (
                !studentRepository.updatePasswordById(
                    studentId = passwordResetCode.studentId,
                    encodedPassword = encodedPassword,
                    expectedCredentialVersion = student.credentialVersion,
                    expectedBojId = requireNotNull(student.bojId)
                )
            ) {
                throw BusinessException(ErrorCode.PASSWORD_RESET_CONFLICT)
            }
            refreshTokenService.revokeAllForStudent(passwordResetCode.studentId)
        }

        log.info("비밀번호 재설정 완료: studentId=${resetCodeSnapshot.studentId}")
    }

    /**
     * BOJ ID 중복 여부를 확인한다.
     *
     * @param bojId 확인할 BOJ ID
     * @return 중복이면 true, 아니면 false
     */
    @Transactional(readOnly = true)
    fun checkBojIdDuplicate(bojId: String): Boolean {
        val bojIdVo = BojId(bojId)
        return studentRepository.existsByBojId(bojIdVo)
    }

    private fun validatePasswordResetCodeContext(
        passwordResetCode: PasswordResetCode,
        student: Student
    ) {
        if (
            passwordResetCode.credentialVersion != student.credentialVersion ||
            passwordResetCode.bojId != student.bojId?.value
        ) {
            throw BusinessException(ErrorCode.PASSWORD_RESET_CONFLICT)
        }
    }

    private fun issuePasswordResetCode(
        studentId: String,
        credentialVersion: Long,
        bojId: String
    ): PasswordResetCode {
        var lastCollisionField: String? = null
        var resetCode = passwordResetCodeGenerator.generate()
        var issuedAt = LocalDateTime.now()

        repeat(MAX_RESET_CODE_ISSUE_ATTEMPTS) { attempt ->
            try {
                return passwordResetCodeRepository.issueForStudent(
                    studentId = studentId,
                    resetCode = resetCode,
                    credentialVersion = credentialVersion,
                    bojId = bojId,
                    expiresAt = issuedAt.plusMinutes(RESET_CODE_EXPIRES_MINUTES),
                    createdAt = issuedAt
                )
            } catch (exception: RuntimeException) {
                val collisionField = when {
                    exception.isDuplicateFor(
                        MongoIndexInitializer.PASSWORD_RESET_CODE_UNIQUE_INDEX_NAME,
                        "resetCode"
                    ) -> "resetCode"

                    exception.isDuplicateFor(
                        MongoIndexInitializer.PASSWORD_RESET_STUDENT_ID_UNIQUE_INDEX_NAME,
                        "studentId"
                    ) -> "studentId"

                    else -> null
                }
                if (collisionField == null || !exception.isDuplicateKeyException()) {
                    throw exception
                }
                lastCollisionField = collisionField

                log.warn(
                    "비밀번호 재설정 코드 충돌로 발급 재시도: studentId={}, field={}, attempt={}/{}",
                    studentId,
                    collisionField,
                    attempt + 1,
                    MAX_RESET_CODE_ISSUE_ATTEMPTS
                )

                if (collisionField == "resetCode" && attempt + 1 < MAX_RESET_CODE_ISSUE_ATTEMPTS) {
                    resetCode = passwordResetCodeGenerator.generate()
                    issuedAt = LocalDateTime.now()
                }
            }
        }

        log.error(
            "비밀번호 재설정 코드 발급 재시도 소진: studentId={}, attempts={}, field={}",
            studentId,
            MAX_RESET_CODE_ISSUE_ATTEMPTS,
            lastCollisionField
        )
        throw BusinessException(
            ErrorCode.COMMON_INTERNAL_ERROR,
            "비밀번호 재설정 코드를 생성하지 못했습니다."
        )
    }

    private fun Throwable.isDuplicateKeyException(): Boolean {
        var current: Throwable? = this
        while (current != null) {
            if (current is DuplicateKeyException) {
                return true
            }
            if (current is MongoWriteException && current.code == MONGO_DUPLICATE_KEY_ERROR_CODE) {
                return true
            }
            if (current is MongoCommandException &&
                current.errorCode == MONGO_DUPLICATE_KEY_ERROR_CODE
            ) {
                return true
            }
            current = current.cause
        }
        return false
    }

    private fun compensatePasswordResetCodeAfterMailFailure(
        passwordResetCode: PasswordResetCode,
        mailException: Exception
    ) {
        try {
            val deleted = passwordResetCodeRepository.deleteIssuedCode(
                studentId = passwordResetCode.studentId,
                resetCode = passwordResetCode.resetCode
            )
            if (!deleted) {
                log.warn(
                    "메일 발송 실패 코드의 조건부 삭제 대상 없음: studentId={}",
                    passwordResetCode.studentId
                )
            }
        } catch (compensationException: Exception) {
            if (compensationException !== mailException) {
                mailException.addSuppressed(compensationException)
            }
            log.error(
                "메일 발송 실패 코드의 조건부 삭제 중 예외 발생: studentId={}, exceptionType={}",
                passwordResetCode.studentId,
                compensationException.javaClass.simpleName
            )
        }
    }

    private fun registerBojAccount(
        bojId: String,
        password: String,
        email: String,
        role: Role,
        verificationSessionId: String? = null
    ): AuthResult {
        val bojIdVo = BojId(bojId)

        PasswordValidator.validate(password)
        validateBojIdNotRegistered(bojIdVo, bojId)
        validateEmailNotRegistered(email)

        val userResponse = fetchSolvedAcUserOrThrow(bojIdVo, bojId)
        val encodedPassword = passwordEncoder.encode(password)

        val rating = userResponse.rating
        val tierLevel = SolvedAcTierLevel.fromRating(rating).value
        val tier = Tier.fromRating(rating)
        val nickname = createNicknameOrThrow(bojId, userResponse.handle)

        log.info(
            "BOJ 계정 생성: role={}, bojId={}, email={}, rating={}, tier={}",
            role.value,
            SensitiveDataMasker.maskId(bojId),
            SensitiveDataMasker.maskEmail(email),
            rating,
            tier
        )

        val student = Student(
            nickname = nickname,
            provider = Provider.BOJ,
            providerId = bojIdVo.value,
            email = email,
            bojId = bojIdVo,
            password = encodedPassword,
            rating = rating,
            solvedAcTierLevel = SolvedAcTierLevel(tierLevel),
            currentTier = tier,
            role = role,
            termsAgreed = true,
            isVerified = verificationSessionId != null
        )

        // 저장 실패 시에도 인증 세션은 복구하지 않으며, 다음 가입 시 BOJ 인증을 다시 받아야 한다.
        verificationSessionId?.let {
            bojOwnershipVerificationService.consumeVerifiedBojId(it, bojIdVo.value)
        }
        val savedStudent = saveStudentOrThrowDuplicate(bojId, student)
        val savedStudentId = requireNotNull(savedStudent.id) {
            "저장된 학생만 Access Token을 발급할 수 있습니다."
        }
        val token = jwtTokenProvider.createToken(
            subject = bojId,
            studentId = savedStudentId,
            credentialVersion = savedStudent.credentialVersion,
            role = savedStudent.role.value
        )
        
        // Refresh Token 발급 및 저장
        val refreshToken = refreshTokenService.generateAndSave(savedStudent)

        return AuthResult(token, refreshToken, rating, tier, tierLevel)
    }

    private fun validateBojIdNotRegistered(bojIdVo: BojId, bojId: String) {
        val existingStudent = studentRepository.findByBojId(bojIdVo)
        if (existingStudent.isPresent) {
            throw BusinessException(ErrorCode.COMMON_INVALID_INPUT, "이미 가입된 BOJ ID입니다. bojId=$bojId")
        }
    }

    private fun validateEmailNotRegistered(email: String) {
        val existingEmailStudent = studentRepository.findByEmail(email)
        if (existingEmailStudent.isPresent) {
            throw BusinessException(ErrorCode.COMMON_INVALID_INPUT, "이미 사용 중인 이메일입니다.")
        }
    }

    private fun fetchSolvedAcUserOrThrow(bojIdVo: BojId, bojId: String): SolvedAcUserResponse {
        try {
            return solvedAcClient.fetchUser(bojIdVo)
        } catch (e: IllegalStateException) {
            log.warn("Solved.ac 사용자 조회 실패: bojId={}, message={}", SensitiveDataMasker.maskId(bojId), e.message)
            throw BusinessException(ErrorCode.COMMON_RESOURCE_NOT_FOUND, "유효하지 않은 BOJ ID입니다. bojId=$bojId")
        } catch (e: Exception) {
            log.error(
                "Solved.ac API 호출 중 예상치 못한 예외 발생: bojId={}, exceptionType={}, message={}",
                SensitiveDataMasker.maskId(bojId),
                e.javaClass.simpleName,
                e.message,
                e
            )
            throw BusinessException(ErrorCode.COMMON_RESOURCE_NOT_FOUND, "유효하지 않은 BOJ ID입니다. bojId=$bojId")
        }
    }

    private fun createNicknameOrThrow(bojId: String, handle: String): Nickname {
        try {
            return Nickname(handle)
        } catch (e: IllegalArgumentException) {
            log.error("닉네임 생성 실패: bojId={}, handle={}, message={}", SensitiveDataMasker.maskId(bojId), handle, e.message, e)
            throw BusinessException(ErrorCode.COMMON_INVALID_INPUT, "유효하지 않은 닉네임입니다. handle=$handle")
        }
    }

    private fun saveStudentOrThrowDuplicate(bojId: String, student: Student): Student {
        try {
            return studentRepository.save(student)
        } catch (e: MongoWriteException) {
            if (e.code == 11000) {
                log.error("MongoDB 중복 키 에러 발생: bojId={}, errorCode={}, message={}", SensitiveDataMasker.maskId(bojId), e.code, e.message, e)
                throw duplicateStudentException(bojId, e)
            }
            log.error("MongoDB 쓰기 에러 발생: bojId={}, errorCode={}, message={}", SensitiveDataMasker.maskId(bojId), e.code, e.message, e)
            throw e
        } catch (e: DuplicateKeyException) {
            log.error("중복 키 에러 발생: bojId={}, message={}", SensitiveDataMasker.maskId(bojId), e.message, e)
            throw duplicateStudentException(bojId, e)
        } catch (e: Exception) {
            log.error(
                "Student 저장 중 예외 발생: bojId={}, exceptionType={}, message={}",
                SensitiveDataMasker.maskId(bojId),
                e.javaClass.simpleName,
                e.message,
                e
            )
            throw e
        }
    }

    private fun duplicateStudentException(bojId: String, exception: Throwable): BusinessException {
        return when {
            exception.isDuplicateFor(
                MongoIndexInitializer.STUDENT_EMAIL_UNIQUE_INDEX_NAME,
                "email"
            ) -> BusinessException(ErrorCode.COMMON_INVALID_INPUT, "이미 사용 중인 이메일입니다.")

            exception.isDuplicateFor(
                MongoIndexInitializer.STUDENT_NICKNAME_UNIQUE_INDEX_NAME,
                "nickname"
            ) -> BusinessException(ErrorCode.DUPLICATE_NICKNAME)

            else -> BusinessException(
                ErrorCode.COMMON_INVALID_INPUT,
                "이미 가입된 BOJ ID입니다. bojId=$bojId"
            )
        }
    }

    private fun Throwable.isDuplicateFor(indexName: String, field: String): Boolean {
        var current: Throwable? = this
        while (current != null) {
            if (current.message?.contains(indexName) == true) {
                return true
            }
            if (current is MongoWriteException) {
                val details = current.error.details
                if (details.containsKey("keyPattern") &&
                    details.getDocument("keyPattern").containsKey(field)
                ) {
                    return true
                }
            }
            if (current is MongoCommandException) {
                val response = current.response
                if (response.containsKey("keyPattern") &&
                    response.getDocument("keyPattern").containsKey(field)
                ) {
                    return true
                }
            }
            current = current.cause
        }
        return false
    }

    private fun validateSignupFinalize(termsAgreed: Boolean, bojId: String?) {
        if (!termsAgreed) {
            throw BusinessException(ErrorCode.COMMON_INVALID_INPUT, "약관 동의는 필수입니다.")
        }
        if (bojId.isNullOrBlank()) {
            throw BusinessException(ErrorCode.COMMON_INVALID_INPUT, "백준 아이디는 필수입니다.")
        }
    }

    private fun parseProviderOrThrow(provider: String): Provider {
        try {
            return Provider.valueOf(provider.uppercase())
        } catch (e: IllegalArgumentException) {
            throw BusinessException(ErrorCode.COMMON_INVALID_INPUT, "유효하지 않은 프로바이더입니다. provider=$provider")
        }
    }

    private fun validateBojIdOwnershipOrThrow(existingStudent: Student?, bojIdVo: BojId) {
        val existingBojOwner = studentRepository.findByBojId(bojIdVo)
        if (!existingBojOwner.isPresent) {
            return
        }
        if (existingStudent != null && existingBojOwner.get().id == existingStudent.id) {
            return
        }
        throw BusinessException(ErrorCode.DUPLICATE_BOJ_ID)
    }

    private fun validateEmailOwnershipOrThrow(existingStudent: Student?, email: String) {
        val existingEmailStudent = studentRepository.findByEmail(email)
        if (!existingEmailStudent.isPresent) {
            return
        }
        if (existingStudent != null && existingEmailStudent.get().id == existingStudent.id) {
            return
        }
        throw BusinessException(ErrorCode.COMMON_INVALID_INPUT, "이미 사용 중인 이메일입니다.")
    }

    private fun finalizeExistingStudent(student: Student, nickname: String, bojIdVo: BojId, email: String): Student {
        val nicknameVo = Nickname(nickname)
        if (studentRepository.existsByNickname(nicknameVo) && student.nickname != nicknameVo) {
            throw BusinessException(ErrorCode.COMMON_INVALID_INPUT, "이미 사용 중인 닉네임입니다. nickname=$nickname")
        }
        val finalizedStudent = student.finalizeSignup(nickname, bojIdVo, email, true)
        return studentRepository.save(finalizedStudent)
    }

    private fun createNewStudent(
        provider: Provider,
        providerId: String,
        nickname: String,
        bojIdVo: BojId,
        email: String,
        rating: Int,
        tierLevel: Int
    ): Student {
        val nicknameVo = Nickname(nickname)
        if (studentRepository.existsByNickname(nicknameVo)) {
            throw BusinessException(ErrorCode.COMMON_INVALID_INPUT, "이미 사용 중인 닉네임입니다. nickname=$nickname")
        }

        val tier = Tier.fromRating(rating)
        val newStudent = Student(
            nickname = nicknameVo,
            provider = provider,
            providerId = providerId,
            email = email,
            bojId = bojIdVo,
            password = null,
            rating = rating,
            solvedAcTierLevel = SolvedAcTierLevel(tierLevel),
            currentTier = tier,
            role = Role.USER,
            termsAgreed = true
        )

        return studentRepository.save(newStudent)
    }

    private fun issueUserToken(student: Student, bojId: String): AuthResult {
        val studentId = requireNotNull(student.id) {
            "저장된 학생만 Access Token을 발급할 수 있습니다."
        }
        val token = jwtTokenProvider.createToken(
            subject = bojId,
            studentId = studentId,
            credentialVersion = student.credentialVersion,
            role = student.role.value
        )
        
        // Refresh Token 발급 및 저장
        val refreshToken = refreshTokenService.generateAndSave(student)
        
        return AuthResult(
            token = token,
            refreshToken = refreshToken,
            rating = student.rating,
            tier = student.tier(),
            tierLevel = student.solvedAcTierLevel.value
        )
    }

    companion object {
        private const val FIND_ID_MAIL_SUBJECT = "[디딤로그] 아이디 찾기"
        private const val RESET_PASSWORD_MAIL_SUBJECT = "[디딤로그] 비밀번호 재설정"

        private const val FIND_ID_MAIL_TEMPLATE = "mail/find-id"
        private const val RESET_PASSWORD_MAIL_TEMPLATE = "mail/find-password"

        private const val RESET_CODE_EXPIRES_MINUTES = 30L
        private const val MAX_RESET_CODE_ISSUE_ATTEMPTS = 5
        private const val MONGO_DUPLICATE_KEY_ERROR_CODE = 11000
    }
}
