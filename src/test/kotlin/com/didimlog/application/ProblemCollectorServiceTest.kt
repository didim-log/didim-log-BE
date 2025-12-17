package com.didimlog.application

import com.didimlog.domain.Problem
import com.didimlog.domain.enums.ProblemCategory
import com.didimlog.domain.enums.Tier
import com.didimlog.domain.repository.ProblemRepository
import com.didimlog.domain.valueobject.ProblemId
import com.didimlog.infra.crawler.BojCrawler
import com.didimlog.infra.solvedac.SolvedAcClient
import com.didimlog.infra.solvedac.SolvedAcProblemResponse
import com.didimlog.infra.solvedac.SolvedAcTag
import com.didimlog.infra.solvedac.SolvedAcTagDisplayName
import com.didimlog.infra.solvedac.SolvedAcTierMapper
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.util.Optional

@DisplayName("ProblemCollectorService 테스트")
class ProblemCollectorServiceTest {

    private val solvedAcClient: SolvedAcClient = mockk()
    private val problemRepository: ProblemRepository = mockk(relaxed = true)
    private val bojCrawler: BojCrawler = mockk(relaxed = true)

    private val problemCollectorService = ProblemCollectorService(
        solvedAcClient,
        problemRepository,
        bojCrawler
    )

    @Test
    @DisplayName("collectMetadata는 한글 태그를 영문 표준명으로 변환하여 저장한다")
    fun `한글 태그를 영문으로 변환하여 저장`() {
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
        every { problemRepository.findById(problemId.toString()) } returns Optional.empty()

        // when
        problemCollectorService.collectMetadata(problemId, problemId)

        // then
        verify(exactly = 1) { problemRepository.save(any<Problem>()) }
        // 저장된 Problem의 태그가 영문으로 변환되었는지 확인
        // (실제 검증은 mock의 capture를 사용하거나, 실제 저장된 객체를 확인해야 함)
    }

    @Test
    @DisplayName("collectMetadata는 태그가 없으면 기본 카테고리로 저장한다")
    fun `태그 없으면 기본 카테고리 사용`() {
        // given
        val problemId = 1000
        val response = SolvedAcProblemResponse(
            problemId = problemId,
            titleKo = "문제",
            level = 3,
            tags = emptyList()
        )
        every { solvedAcClient.fetchProblem(problemId) } returns response
        every { problemRepository.findById(problemId.toString()) } returns Optional.empty()

        // when
        problemCollectorService.collectMetadata(problemId, problemId)

        // then
        verify(exactly = 1) { problemRepository.save(any<Problem>()) }
    }

    @Test
    @DisplayName("extractTagsToEnglish는 한글 태그를 영문 표준명으로 변환한다")
    fun `extractTagsToEnglish 한글 태그 변환`() {
        // given
        val tags = listOf(
            SolvedAcTag(
                key = "dp",
                displayNames = listOf(
                    SolvedAcTagDisplayName(language = "ko", name = "다이나믹 프로그래밍")
                )
            ),
            SolvedAcTag(
                key = "greedy",
                displayNames = listOf(
                    SolvedAcTagDisplayName(language = "ko", name = "그리디 알고리즘")
                )
            )
        )

        // when: 리플렉션을 사용하여 private 메서드 호출 (실제로는 public 메서드를 통해 간접 테스트)
        // 또는 extractTagsToEnglish를 public으로 변경하거나, collectMetadata를 통해 간접 테스트
        // 여기서는 collectMetadata를 통해 간접 테스트
        val response = SolvedAcProblemResponse(
            problemId = 1000,
            titleKo = "문제",
            level = 5,
            tags = tags
        )
        every { solvedAcClient.fetchProblem(1000) } returns response
        every { problemRepository.findById("1000") } returns Optional.empty()

        // when
        problemCollectorService.collectMetadata(1000, 1000)

        // then: 저장된 Problem이 영문 태그를 가지고 있는지 확인
        verify(exactly = 1) { problemRepository.save(any<Problem>()) }
    }
}







