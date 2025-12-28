package com.didimlog.application

import com.didimlog.domain.Problem
import com.didimlog.domain.Student
import com.didimlog.domain.enums.ProblemCategory
import com.didimlog.domain.enums.Provider
import com.didimlog.domain.enums.Role
import com.didimlog.domain.enums.Tier
import com.didimlog.domain.repository.ProblemRepository
import com.didimlog.domain.repository.StudentRepository
import com.didimlog.domain.valueobject.BojId
import com.didimlog.domain.valueobject.Nickname
import com.didimlog.domain.valueobject.ProblemId
import com.didimlog.infra.crawler.BojCrawler
import com.didimlog.infra.crawler.ProblemDetails
import com.didimlog.infra.solvedac.SolvedAcClient
import com.didimlog.infra.solvedac.SolvedAcProblemResponse
import com.didimlog.infra.solvedac.SolvedAcTag
import com.didimlog.infra.solvedac.SolvedAcTagDisplayName
import com.didimlog.infra.solvedac.SolvedAcUserResponse
import io.mockk.CapturingSlot
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import io.mockk.verifyOrder
import io.mockk.clearAllMocks
import java.util.Optional
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("ProblemService 테스트")
class ProblemServiceTest {

    private val solvedAcClient: SolvedAcClient = mockk()
    private val problemRepository: ProblemRepository = mockk(relaxed = true)
    private val studentRepository: StudentRepository = mockk(relaxed = true)
    private val bojCrawler: BojCrawler = mockk(relaxed = true)

    private val problemService = ProblemService(solvedAcClient, problemRepository, studentRepository, bojCrawler)

    @Test
    @DisplayName("syncProblem은 Solved_ac 문제 정보를 조회하여 Problem을 upsert한다")
    fun `syncProblem으로 문제 정보를 동기화`() {
        // given
        val problemId = 1000
        val response = SolvedAcProblemResponse(
            problemId = problemId,
            titleKo = "A+B",
            level = 3,
            tags = emptyList()
        )
        every { solvedAcClient.fetchProblem(problemId) } returns response

        val savedProblemSlot: CapturingSlot<Problem> = slot()
        every { problemRepository.save(capture(savedProblemSlot)) } answers { savedProblemSlot.captured }

        // when
        problemService.syncProblem(problemId)

        // then
        val savedProblem = savedProblemSlot.captured
        assertThat(savedProblem.id.value).isEqualTo(problemId.toString())
        assertThat(savedProblem.title).isEqualTo("A+B")
        assertThat(savedProblem.url).isEqualTo("https://www.acmicpc.net/problem/$problemId")
        assertThat(savedProblem.level).isEqualTo(3)
        assertThat(savedProblem.difficulty).isEqualTo(Tier.BRONZE)
        assertThat(savedProblem.category).isEqualTo(ProblemCategory.IMPLEMENTATION) // 태그가 없으면 기본값
        assertThat(savedProblem.tags).isEmpty()

        verify(exactly = 1) { problemRepository.save(any<Problem>()) }
    }

    @Test
    @DisplayName("syncProblem은 한글 태그를 영문 표준명으로 변환하여 저장한다")
    fun `syncProblem으로 태그 변환 및 저장`() {
        // given
        val problemId = 1000
        val tags = listOf(
            SolvedAcTag(
                key = "math",
                displayNames = listOf(
                    SolvedAcTagDisplayName(language = "ko", name = "수학")
                )
            ),
            SolvedAcTag(
                key = "implementation",
                displayNames = listOf(
                    SolvedAcTagDisplayName(language = "ko", name = "구현")
                )
            )
        )
        val response = SolvedAcProblemResponse(
            problemId = problemId,
            titleKo = "수학 문제",
            level = 5,
            tags = tags
        )
        every { solvedAcClient.fetchProblem(problemId) } returns response

        val savedProblemSlot: CapturingSlot<Problem> = slot()
        every { problemRepository.save(capture(savedProblemSlot)) } answers { savedProblemSlot.captured }

        // when
        problemService.syncProblem(problemId)

        // then
        val savedProblem = savedProblemSlot.captured
        assertThat(savedProblem.category).isEqualTo(ProblemCategory.MATHEMATICS) // 첫 번째 태그가 카테고리
        assertThat(savedProblem.tags).containsExactly("Mathematics", "Implementation")
        assertThat(savedProblem.tags).doesNotContain("수학", "구현") // 한글이 아닌 영문으로 저장됨

        verify(exactly = 1) { problemRepository.save(any<Problem>()) }
    }

    @Test
    @DisplayName("syncUserTier는 Solved_ac 사용자 티어 정보를 조회하여 Student의 티어를 갱신한다")
    fun `syncUserTier로 사용자 티어 동기화`() {
        // given
        val bojId = BojId("tester123")
        val student = Student(
            nickname = Nickname("tester"),
            provider = Provider.BOJ,
            providerId = bojId.value,
            bojId = bojId,
            password = "test-password",
            rating = 100,
            currentTier = Tier.BRONZE,
            role = Role.USER
        )
        every { studentRepository.findByBojId(bojId) } returns Optional.of(student)

        val userResponse = SolvedAcUserResponse(
            handle = bojId.value,
            rating = 1200  // Rating 1200점은 GOLD 티어 (800점 이상)
        )
        every { solvedAcClient.fetchUser(bojId) } returns userResponse
        
        val savedStudentSlot: CapturingSlot<Student> = slot()
        every { studentRepository.save(capture(savedStudentSlot)) } answers { savedStudentSlot.captured }

        // when
        problemService.syncUserTier(bojId.value)

        // then
        val savedStudent = savedStudentSlot.captured
        assertThat(savedStudent.tier()).isEqualTo(Tier.GOLD) // Rating 1200점은 GOLD
        assertThat(savedStudent.rating).isEqualTo(1200)
        verify(exactly = 1) { studentRepository.save(any<Student>()) }
    }

    @Test
    @DisplayName("syncUserTier는 Student가 없으면 아무 일도 하지 않는다")
    fun `syncUserTier는 학생이 없으면 조용히 종료`() {
        // given
        val bojIdString = "unknown123" // 유효한 BOJ ID 형식
        val bojId = BojId(bojIdString)
        every { studentRepository.findByBojId(bojId) } returns Optional.empty()

        // when
        problemService.syncUserTier(bojIdString)

        // then: Student가 없으면 fetchUser와 save가 호출되지 않아야 함
        verify(exactly = 0) { solvedAcClient.fetchUser(bojId) }
        verify(exactly = 0) { studentRepository.save(any<Student>()) }
    }

    @Test
    @DisplayName("syncUserTier는 Solved_ac 티어가 현재 티어와 같으면 저장하지 않는다")
    fun `동일 티어면 save 호출 생략`() {
        // given
        val bojIdString = "sametier123" // 유효한 BOJ ID 형식
        val bojId = BojId(bojIdString)
        val student = Student(
            nickname = Nickname("tester"),
            provider = Provider.BOJ,
            providerId = bojId.value,
            bojId = bojId,
            password = "test-password",
            rating = 500,
            currentTier = Tier.SILVER,
            role = Role.USER
        )
        every { studentRepository.findByBojId(bojId) } returns Optional.of(student)

        val userResponse = SolvedAcUserResponse(
            handle = bojId.value,
            rating = 500  // Rating 500점은 SILVER (200점 이상이지만 800점 미만)
        )
        every { solvedAcClient.fetchUser(bojId) } returns userResponse

        // when
        problemService.syncUserTier(bojIdString)

        // then
        verify(exactly = 0) { studentRepository.save(any<Student>()) }
    }

    @Test
    @DisplayName("getProblemDetail은 DB에 문제가 있으면 그대로 반환한다")
    fun `DB에 문제가 있으면 그대로 반환`() {
        // given
        val problemId = 1000L
        val existingProblem = Problem(
            id = ProblemId(problemId.toString()),
            title = "기존 문제",
            category = ProblemCategory.IMPLEMENTATION,
            difficulty = Tier.BRONZE,
            level = 3,
            url = "https://www.acmicpc.net/problem/$problemId",
            descriptionHtml = "<p>기존 문제 설명</p>"
        )

        every { problemRepository.findById(problemId.toString()) } returns Optional.of(existingProblem)

        // when
        val result = problemService.getProblemDetail(problemId)

        // then
        assertThat(result).isEqualTo(existingProblem)
        verify(exactly = 0) { solvedAcClient.fetchProblem(any()) }
        verify(exactly = 0) { bojCrawler.crawlProblemDetails(any()) }
        verify(exactly = 0) { problemRepository.save(any<Problem>()) }
    }

    @Test
    @DisplayName("getProblemDetail은 DB에 문제가 없으면 Solved.ac에서 조회하여 저장하고 반환한다")
    fun `DB에 문제가 없으면 Solved_ac에서 조회하여 저장`() {
        // given
        val problemId = 1000L
        val solvedAcResponse = SolvedAcProblemResponse(
            problemId = problemId.toInt(),
            titleKo = "새로운 문제",
            level = 5,
            tags = emptyList()
        )

        every { problemRepository.findById(problemId.toString()) } returns Optional.empty()
        every { solvedAcClient.fetchProblem(problemId.toInt()) } returns solvedAcResponse

        val savedProblemSlot: CapturingSlot<Problem> = slot()
        every { problemRepository.save(capture(savedProblemSlot)) } answers { savedProblemSlot.captured }

        every { bojCrawler.crawlProblemDetails(problemId.toString()) } returns null

        // when
        val result = problemService.getProblemDetail(problemId)

        // then
        val savedProblem = savedProblemSlot.captured
        assertThat(savedProblem.id.value).isEqualTo(problemId.toString())
        assertThat(savedProblem.title).isEqualTo("새로운 문제")
        assertThat(result).isEqualTo(savedProblem)
        verify(exactly = 1) { solvedAcClient.fetchProblem(problemId.toInt()) }
        verify(exactly = 1) { problemRepository.save(any<Problem>()) }
    }

    @Test
    @DisplayName("getProblemDetail은 DB에 문제는 있지만 상세 정보가 없으면 크롤링하여 저장한다")
    fun `상세 정보 없으면 크롤링하여 저장`() {
        // given
        val problemId = 1000L
        val existingProblem = Problem(
            id = ProblemId(problemId.toString()),
            title = "기존 문제",
            category = ProblemCategory.IMPLEMENTATION,
            difficulty = Tier.BRONZE,
            level = 3,
            url = "https://www.acmicpc.net/problem/$problemId",
            descriptionHtml = null
        )

        val crawledDetails = ProblemDetails(
            descriptionHtml = "<p>크롤링된 설명</p>",
            inputDescriptionHtml = "<p>입력 설명</p>",
            outputDescriptionHtml = "<p>출력 설명</p>",
            sampleInputs = listOf("1 2"),
            sampleOutputs = listOf("3")
        )

        every { problemRepository.findById(problemId.toString()) } returns Optional.of(existingProblem)
        every { bojCrawler.crawlProblemDetails(problemId.toString()) } returns crawledDetails

        val savedProblemSlot: CapturingSlot<Problem> = slot()
        every { problemRepository.save(capture(savedProblemSlot)) } answers { savedProblemSlot.captured }

        // when
        val result = problemService.getProblemDetail(problemId)

        // then
        val savedProblem = savedProblemSlot.captured
        assertThat(savedProblem.descriptionHtml).isEqualTo(crawledDetails.descriptionHtml)
        assertThat(savedProblem.inputDescriptionHtml).isEqualTo(crawledDetails.inputDescriptionHtml)
        assertThat(savedProblem.outputDescriptionHtml).isEqualTo(crawledDetails.outputDescriptionHtml)
        assertThat(savedProblem.sampleInputs).isEqualTo(crawledDetails.sampleInputs)
        assertThat(savedProblem.sampleOutputs).isEqualTo(crawledDetails.sampleOutputs)
        assertThat(result).isEqualTo(savedProblem)
        verify(exactly = 1) { bojCrawler.crawlProblemDetails(problemId.toString()) }
        verify(exactly = 1) { problemRepository.save(any<Problem>()) }
    }
}


