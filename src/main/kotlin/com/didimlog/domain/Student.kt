package com.didimlog.domain

import com.didimlog.domain.enums.PrimaryLanguage
import com.didimlog.domain.enums.ProblemResult
import com.didimlog.domain.enums.Provider
import com.didimlog.domain.enums.Role
import com.didimlog.domain.enums.Tier
import com.didimlog.domain.valueobject.BojId
import com.didimlog.domain.valueobject.Nickname
import com.didimlog.domain.valueobject.ProblemId
import com.didimlog.domain.valueobject.TimeTakenSeconds
import org.springframework.data.annotation.Id
import org.springframework.data.annotation.PersistenceCreator
import org.springframework.data.mongodb.core.index.Indexed
import org.springframework.data.mongodb.core.mapping.Document
import org.springframework.security.crypto.password.PasswordEncoder
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * 알고리즘 학습자의 상태와 풀이 기록을 관리하는 Aggregate Root
 * 티어 정보는 Solved.ac API를 통해 외부에서 동기화되며, 자체 승급 로직은 사용하지 않는다.
 *
 * **주의사항:**
 * - 소셜 로그인 사용자의 경우 password와 bojId가 null일 수 있습니다.
 * - MongoDB 커스텀 컨버터가 Value Object(Nickname, BojId)와 Enum(Tier, Provider, Role)을 자동으로 변환합니다.
 */
@Document(collection = "students")
data class Student(
    @Id
    val id: String? = null,
    val nickname: Nickname,
    val provider: Provider, // 소셜 로그인 제공자 (GOOGLE, GITHUB, NAVER, BOJ)
    @Indexed
    val providerId: String, // 소셜 로그인 제공자의 사용자 ID
    val email: String? = null, // 이메일 (소셜 로그인 제공자가 제공하는 경우)
    val bojId: BojId? = null, // BOJ ID (BOJ 인증을 완료한 경우에만 존재)
    val password: String? = null, // BCrypt로 암호화된 비밀번호 (BOJ 로그인 사용자만 사용)
    @Indexed
    val rating: Int = 0, // Solved.ac Rating (점수) - 랭킹 조회 성능 최적화를 위한 인덱스
    val currentTier: Tier,
    val role: Role = Role.GUEST, // 사용자 권한 (GUEST: 소셜 로그인만 완료, USER: BOJ 인증 완료)
    val termsAgreed: Boolean = false, // 약관 동의 여부
    val isVerified: Boolean = false, // BOJ 계정 소유권 인증 여부 (상태 메시지 기반)
    val solutions: Solutions = Solutions(),
    val consecutiveSolveDays: Int = 0, // 연속 풀이 일수
    val lastSolvedAt: LocalDate? = null, // 마지막으로 문제를 푼 날짜
    val primaryLanguage: PrimaryLanguage? = null, // 주로 사용하는 프로그래밍 언어 (nullable: 기존 사용자 호환성)
    @Indexed
    val createdAt: LocalDateTime = LocalDateTime.now() // 회원 가입 일시
) {
    /**
     * Spring Data MongoDB가 DB에서 데이터를 읽어올 때 사용하는 생성자
     * MongoDB 커스텀 컨버터가 자동으로 Value Object와 Enum을 변환하므로,
     * 여기서는 변환된 타입을 그대로 받는다.
     *
     * @param id 학생 ID
     * @param nickname 닉네임 Value Object (컨버터가 자동 변환)
     * @param provider 소셜 로그인 제공자 Enum (컨버터가 자동 변환)
     * @param providerId 소셜 로그인 제공자의 사용자 ID
     * @param email 이메일 (nullable)
     * @param bojId BOJ ID Value Object (컨버터가 자동 변환, nullable)
     * @param password 암호화된 비밀번호 (BOJ 로그인 사용자만 사용, nullable)
     * @param rating Solved.ac Rating (점수)
     * @param currentTier 티어 Enum (컨버터가 자동 변환)
     * @param role 사용자 권한 Enum (컨버터가 자동 변환)
     * @param termsAgreed 약관 동의 여부
     * @param solutions 풀이 기록 목록
     * @param consecutiveSolveDays 연속 풀이 일수
     * @param lastSolvedAt 마지막으로 문제를 푼 날짜
     * @param primaryLanguage 주로 사용하는 프로그래밍 언어 (nullable)
     * @param createdAt 회원 가입 일시 (nullable: 기존 사용자 호환성)
     */
    @PersistenceCreator
    constructor(
        id: String?,
        nickname: Nickname,
        provider: Provider,
        providerId: String,
        email: String?,
        bojId: BojId?,
        password: String?,
        rating: Int?,
        currentTier: Tier,
        role: Role?,
        termsAgreed: Boolean?,
        isVerified: Boolean?,
        solutions: Solutions?,
        consecutiveSolveDays: Int?,
        lastSolvedAt: LocalDate?,
        primaryLanguage: PrimaryLanguage?,
        createdAt: LocalDateTime?
    ) : this(
        id = id,
        nickname = nickname,
        provider = provider,
        providerId = providerId,
        email = email,
        bojId = bojId,
        password = password,
        rating = rating ?: 0,
        currentTier = currentTier,
        role = role ?: Role.GUEST,
        termsAgreed = termsAgreed ?: false,
        isVerified = isVerified ?: false,
        solutions = solutions ?: Solutions(),
        consecutiveSolveDays = consecutiveSolveDays ?: 0,
        lastSolvedAt = lastSolvedAt,
        primaryLanguage = primaryLanguage,
        createdAt = createdAt ?: LocalDateTime.now()
    )

    /**
     * 문제 풀이 결과를 기록한다.
     * Solved.ac를 Source of Truth로 사용하므로, 자동 승급 로직은 포함하지 않는다.
     * 연속 풀이 일수와 마지막 풀이 날짜를 업데이트한다.
     *
     * @param problem 풀이한 문제
     * @param timeTakenSeconds 풀이에 소요된 시간 (초)
     * @param isSuccess 풀이 성공 여부
     * @return 풀이 기록이 업데이트된 새로운 Student 인스턴스
     */
    fun solveProblem(
        problem: Problem,
        timeTakenSeconds: TimeTakenSeconds,
        isSuccess: Boolean
    ): Student {
        val result = toProblemResult(isSuccess)
        val newSolution = Solution(
            problemId = problem.id,
            timeTaken = timeTakenSeconds,
            result = result
        )
        val updatedSolutions = Solutions().apply {
            solutions.getAll().forEach { add(it) }
            add(newSolution)
        }
        
        val today = LocalDate.now()
        val updatedConsecutiveDays = calculateConsecutiveSolveDays(today)
        val updatedLastSolvedAt = today
        
        return copy(
            solutions = updatedSolutions,
            consecutiveSolveDays = updatedConsecutiveDays,
            lastSolvedAt = updatedLastSolvedAt
        )
    }

    /**
     * 오늘 날짜를 기준으로 연속 풀이 일수를 계산한다.
     * 마지막 풀이 날짜가 어제면 연속 일수를 증가시키고, 오늘이면 유지하며, 그 이전이면 1로 초기화한다.
     *
     * @param today 오늘 날짜
     * @return 업데이트된 연속 풀이 일수
     */
    private fun calculateConsecutiveSolveDays(today: LocalDate): Int {
        if (lastSolvedAt == null) {
            return 1
        }
        
        val daysBetween = java.time.temporal.ChronoUnit.DAYS.between(lastSolvedAt, today)
        
        return when {
            daysBetween == 0L -> consecutiveSolveDays // 오늘 이미 풀었으면 유지
            daysBetween == 1L -> consecutiveSolveDays + 1 // 어제 풀었으면 증가
            else -> 1 // 그 이전이면 초기화
        }
    }

    /**
     * 현재 티어를 반환한다.
     */
    fun tier(): Tier = currentTier

    /**
     * 외부(Solved.ac API)에서 가져온 Rating(점수) 정보로 티어를 업데이트한다.
     * Solved.ac를 Source of Truth로 사용하므로, 이 메서드를 통해 티어를 동기화한다.
     *
     * @param newRating 새로운 Rating (Solved.ac에서 가져온 점수)
     * @return Rating과 티어가 업데이트된 새로운 Student 인스턴스
     */
    fun updateInfo(newRating: Int): Student {
        val newTier = Tier.fromRating(newRating)
        return copy(rating = newRating, currentTier = newTier)
    }

    /**
     * 외부(Solved.ac API)에서 가져온 티어 정보로 티어를 업데이트한다.
     * Solved.ac를 Source of Truth로 사용하므로, 이 메서드를 통해 티어를 동기화한다.
     *
     * @param newTier 새로운 티어 (Solved.ac에서 가져온 정보)
     * @return 티어가 업데이트된 새로운 Student 인스턴스
     * @deprecated Rating 기반 업데이트로 변경되어 사용하지 않음. updateInfo를 사용하세요.
     */
    @Deprecated("Rating 기반 업데이트로 변경되어 사용하지 않음. updateInfo를 사용하세요.")
    fun updateTier(newTier: Tier): Student {
        return copy(currentTier = newTier)
    }

    /**
     * @deprecated Solved.ac 동기화 방식으로 변경되어 사용하지 않음. updateTier를 사용하세요.
     */
    @Deprecated("Solved.ac 동기화 방식으로 변경되어 사용하지 않음. updateTier를 사용하세요.")
    fun syncTier(targetTier: Tier): Student {
        return updateTier(targetTier)
    }

    /**
     * 풀이한 문제 ID 목록을 반환한다.
     */
    fun getSolvedProblemIds(): Set<ProblemId> {
        return solutions.getAll().map { it.problemId }.toSet()
    }

    /**
     * 입력된 평문 비밀번호가 저장된 암호화된 비밀번호와 일치하는지 확인한다.
     * 소셜 로그인 사용자의 경우 password가 null이므로 false를 반환한다.
     *
     * @param rawPassword 평문 비밀번호
     * @param encoder PasswordEncoder (BCryptPasswordEncoder)
     * @return 비밀번호가 일치하면 true, 그렇지 않으면 false
     */
    fun matchPassword(rawPassword: String, encoder: PasswordEncoder): Boolean {
        if (password == null) {
            return false
        }
        return encoder.matches(rawPassword, password)
    }

    /**
     * 소셜 로그인 후 가입 마무리를 수행한다.
     * 약관 동의 및 닉네임 설정을 완료하고, GUEST에서 USER로 역할을 변경한다.
     *
     * @param nickname 설정할 닉네임
     * @param termsAgreed 약관 동의 여부
     * @return 가입 마무리가 완료된 새로운 Student 인스턴스
     * @throws IllegalArgumentException 약관 동의가 false인 경우
     */
    fun finalizeSignup(nickname: String, termsAgreed: Boolean): Student {
        if (!termsAgreed) {
            throw IllegalArgumentException("약관 동의는 필수입니다.")
        }
        
        val nicknameVo = Nickname(nickname)
        
        return copy(
            nickname = nicknameVo,
            termsAgreed = true,
            role = Role.USER
        )
    }

    /**
     * 소셜 로그인 후 가입 마무리를 수행한다. (BOJ ID 필수)
     * - 닉네임/약관 동의/권한(USER) 뿐 아니라 BOJ ID 및 이메일까지 확정한다.
     *
     * @param nickname 설정할 닉네임
     * @param bojId BOJ ID (필수)
     * @param email 이메일 (nullable)
     * @param termsAgreed 약관 동의 여부
     */
    fun finalizeSignup(nickname: String, bojId: BojId, email: String?, termsAgreed: Boolean): Student {
        if (!termsAgreed) {
            throw IllegalArgumentException("약관 동의는 필수입니다.")
        }

        val nicknameVo = Nickname(nickname)

        return copy(
            nickname = nicknameVo,
            bojId = bojId,
            email = email,
            termsAgreed = true,
            role = Role.USER
        )
    }

    /**
     * 주로 사용하는 프로그래밍 언어를 업데이트한다.
     *
     * @param language 새로운 언어
     * @return 언어가 업데이트된 새로운 Student 인스턴스
     */
    fun updatePrimaryLanguage(language: PrimaryLanguage): Student {
        return copy(primaryLanguage = language)
    }

    private fun toProblemResult(isSuccess: Boolean): ProblemResult {
        if (isSuccess) {
            return ProblemResult.SUCCESS
        }
        return ProblemResult.FAIL
    }
}
