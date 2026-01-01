package com.didimlog.application.log

import com.didimlog.domain.Log
import com.didimlog.domain.repository.LogRepository
import com.didimlog.domain.valueobject.LogCode
import com.didimlog.domain.valueobject.LogContent
import com.didimlog.domain.valueobject.LogTitle
import com.didimlog.global.exception.AiGenerationTimeoutException
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles

/**
 * AI 리뷰 생성 통합 테스트
 * 실제 Gemini API를 호출하여 테스트합니다.
 * 
 * 실행 조건:
 * - GEMINI_API_KEY 환경 변수가 설정되어 있어야 합니다.
 * - 실제 Gemini API를 호출하므로 네트워크 연결이 필요합니다.
 */
@DisplayName("AI 리뷰 생성 통합 테스트 (실제 Gemini API)")
@SpringBootTest
@ActiveProfiles("test")
class AiReviewIntegrationTest {

    @Autowired
    private lateinit var aiReviewService: AiReviewService

    @Autowired
    private lateinit var logRepository: LogRepository

    @Test
    @DisplayName("실제 Gemini API를 호출하여 한 줄 리뷰를 생성할 수 있다")
    @EnabledIfEnvironmentVariable(named = "GEMINI_API_KEY", matches = ".+")
    fun `실제 Gemini API 호출 테스트`() {
        // given: 실제 코드를 포함한 Log 생성
        val testCode = """
            public class Solution {
                public int solution(int[] nums) {
                    int sum = 0;
                    for (int i = 0; i < nums.length; i++) {
                        sum += nums[i];
                    }
                    return sum;
                }
            }
        """.trimIndent()

        val log = Log(
            title = LogTitle("테스트 문제 풀이"),
            content = LogContent("테스트 회고 내용"),
            code = LogCode(testCode)
        )
        val savedLog = logRepository.save(log)
        val logId = savedLog.id ?: throw IllegalStateException("로그 ID가 없습니다.")

        // when: AI 리뷰 생성 (최대 60초 대기)
        val startTime = System.currentTimeMillis()
        val result = try {
            aiReviewService.requestOneLineReview(logId)
        } catch (e: AiGenerationTimeoutException) {
            throw AssertionError("AI 리뷰 생성이 타임아웃되었습니다. (${System.currentTimeMillis() - startTime}ms 소요)", e)
        }
        val duration = System.currentTimeMillis() - startTime

        // then: 결과 검증
        assertThat(result.review).isNotBlank()
        assertThat(result.review.length).isGreaterThan(10) // 최소한의 의미 있는 리뷰
        assertThat(result.cached).isFalse() // 첫 생성이므로 캐시되지 않음
        assertThat(duration).isLessThan(60_000) // 60초 이내 완료

        // DB에 저장된 리뷰 확인
        val updatedLog = logRepository.findById(logId).orElseThrow()
        assertThat(updatedLog.aiReview?.value).isEqualTo(result.review)
        assertThat(updatedLog.aiReviewDurationMillis).isNotNull()
        assertThat(updatedLog.aiReviewDurationMillis).isGreaterThan(0)

        // 결과 출력
        println("✅ AI 리뷰 생성 성공!")
        println("📝 리뷰: ${result.review}")
        println("⏱️  소요 시간: ${duration}ms (${duration / 1000.0}초)")
        println("💾 DB 저장 확인: ${updatedLog.aiReview?.value != null}")
    }

    @Test
    @DisplayName("같은 로그에 대해 두 번 요청하면 캐시된 결과를 반환한다")
    @EnabledIfEnvironmentVariable(named = "GEMINI_API_KEY", matches = ".+")
    fun `캐시 동작 테스트`() {
        // given: Log 생성 및 첫 번째 AI 리뷰 생성
        val testCode = """
            def solution(nums):
                return sum(nums)
        """.trimIndent()

        val log = Log(
            title = LogTitle("캐시 테스트"),
            content = LogContent("캐시 테스트 회고"),
            code = LogCode(testCode)
        )
        val savedLog = logRepository.save(log)
        val logId = savedLog.id ?: throw IllegalStateException("로그 ID가 없습니다.")

        // 첫 번째 요청
        val firstResult = aiReviewService.requestOneLineReview(logId)
        assertThat(firstResult.cached).isFalse()

        // when: 두 번째 요청
        val secondResult = aiReviewService.requestOneLineReview(logId)

        // then: 캐시된 결과 반환
        assertThat(secondResult.cached).isTrue()
        assertThat(secondResult.review).isEqualTo(firstResult.review)

        println("✅ 캐시 동작 확인!")
        println("📝 첫 요청: ${firstResult.review}")
        println("📝 두 번째 요청 (캐시): ${secondResult.review}")
    }
}

