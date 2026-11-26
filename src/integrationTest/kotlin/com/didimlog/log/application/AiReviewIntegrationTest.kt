package com.didimlog.log.application

import com.didimlog.global.exception.AiGenerationTimeoutException
import com.didimlog.log.domain.Log
import com.didimlog.log.domain.LogCode
import com.didimlog.log.domain.LogContent
import com.didimlog.log.domain.LogTitle
import com.didimlog.log.domain.repository.LogRepository
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
        // Rate Limiter 대기 시간 (최소 4초 간격 필요, 여유를 두고 5초 대기)
        Thread.sleep(5000)
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
    @DisplayName("같은 로그에 대해 두 번 요청하면 캐시된 결과를 반환한다 (비용 0원 테스트)")
    @EnabledIfEnvironmentVariable(named = "GEMINI_API_KEY", matches = ".+")
    fun `캐시 동작 테스트_비용절감`() {
        // Rate Limiter 대기 시간 (최소 4초 간격 필요, 여유를 두고 5초 대기)
        Thread.sleep(5000)
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

        // 첫 번째 요청 (AI API 호출 발생 - 비용 발생)
        val firstStartTime = System.currentTimeMillis()
        val firstResult = aiReviewService.requestOneLineReview(logId)
        val firstDuration = System.currentTimeMillis() - firstStartTime
        assertThat(firstResult.cached).isFalse()

        // when: 두 번째 요청 (캐시 사용 - 비용 0원)
        val secondStartTime = System.currentTimeMillis()
        val secondResult = aiReviewService.requestOneLineReview(logId)
        val secondDuration = System.currentTimeMillis() - secondStartTime

        // then: 캐시된 결과 반환 (AI API 호출 없음)
        assertThat(secondResult.cached).isTrue()
        assertThat(secondResult.review).isEqualTo(firstResult.review)
        // 캐시된 요청은 훨씬 빠름 (DB 조회만 수행)
        assertThat(secondDuration).isLessThan(100) // 100ms 이내 (AI 호출은 2초 이상 소요)

        println("✅ 캐시 동작 확인! (비용 절감)")
        println("📝 첫 요청 (AI API 호출): ${firstResult.review} (소요: ${firstDuration}ms)")
        println("📝 두 번째 요청 (캐시): ${secondResult.review} (소요: ${secondDuration}ms)")
        println("💰 비용 절감: 두 번째 요청은 AI API를 호출하지 않아 비용이 0원입니다!")
    }

    @Test
    @DisplayName("성공한 코드에 대한 AI 리뷰 생성 (개선 제안 중심)")
    @EnabledIfEnvironmentVariable(named = "GEMINI_API_KEY", matches = ".+")
    fun `성공한_코드_리뷰_테스트`() {
        // Rate Limiter 대기 시간 (최소 4초 간격 필요, 여유를 두고 5초 대기)
        Thread.sleep(5000)
        // given: 성공한 코드를 포함한 Log 생성
        val successCode = """
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
            title = LogTitle("성공한 코드 테스트"),
            content = LogContent("성공 회고"),
            code = LogCode(successCode),
            isSuccess = true
        )
        val savedLog = logRepository.save(log)
        val logId = savedLog.id ?: throw IllegalStateException("로그 ID가 없습니다.")

        // when: AI 리뷰 생성
        val result = aiReviewService.requestOneLineReview(logId)

        // then: 성공한 코드에 대한 리뷰 확인 (개선 제안 중심)
        assertThat(result.review).isNotBlank()
        assertThat(result.review.length).isGreaterThan(10)
        assertThat(result.cached).isFalse()

        println("✅ 성공한 코드 리뷰 생성!")
        println("📝 리뷰: ${result.review}")
        println("💡 리뷰 특징: 성공한 코드이므로 개선 제안에 초점을 맞춤")
    }

    @Test
    @DisplayName("실패한 코드에 대한 AI 리뷰 생성 (버그 분석 중심)")
    @EnabledIfEnvironmentVariable(named = "GEMINI_API_KEY", matches = ".+")
    fun `실패한_코드_리뷰_테스트`() {
        // Rate Limiter 대기 시간 (최소 4초 간격 필요, 여유를 두고 5초 대기)
        Thread.sleep(5000)
        // given: 실패한 코드를 포함한 Log 생성
        val failCode = """
            public class Solution {
                public int solution(int[] nums) {
                    int sum = 0;
                    for (int i = 0; i <= nums.length; i++) {
                        sum += nums[i];
                    }
                    return sum;
                }
            }
        """.trimIndent()

        val log = Log(
            title = LogTitle("실패한 코드 테스트"),
            content = LogContent("실패 회고"),
            code = LogCode(failCode),
            isSuccess = false
        )
        val savedLog = logRepository.save(log)
        val logId = savedLog.id ?: throw IllegalStateException("로그 ID가 없습니다.")

        // when: AI 리뷰 생성
        val result = aiReviewService.requestOneLineReview(logId)

        // then: 실패한 코드에 대한 리뷰 확인 (버그 분석 중심)
        assertThat(result.review).isNotBlank()
        assertThat(result.review.length).isGreaterThan(10)
        assertThat(result.cached).isFalse()

        println("✅ 실패한 코드 리뷰 생성!")
        println("📝 리뷰: ${result.review}")
        println("💡 리뷰 특징: 실패한 코드이므로 버그 분석에 초점을 맞춤")
        
        // 다음 테스트를 위한 Rate Limit 대기
        Thread.sleep(5000)
    }

    @Test
    @DisplayName("성공/실패 케이스 비교 테스트")
    @EnabledIfEnvironmentVariable(named = "GEMINI_API_KEY", matches = ".+")
    fun `성공_실패_리뷰_비교_테스트`() {
        // Rate Limiter 대기 시간 (최소 4초 간격 필요, 여유를 두고 5초 대기)
        Thread.sleep(5000)
        // given: 같은 코드지만 성공/실패 정보만 다른 Log 생성
        val testCode = """
            public class Solution {
                public int solution(int[] nums) {
                    return nums[0];
                }
            }
        """.trimIndent()

        val successLog = Log(
            title = LogTitle("비교 테스트 - 성공"),
            content = LogContent("비교 회고"),
            code = LogCode(testCode),
            isSuccess = true
        )
        val savedSuccessLog = logRepository.save(successLog)
        val successLogId = savedSuccessLog.id ?: throw IllegalStateException("로그 ID가 없습니다.")

        val failLog = Log(
            title = LogTitle("비교 테스트 - 실패"),
            content = LogContent("비교 회고"),
            code = LogCode(testCode),
            isSuccess = false
        )
        val savedFailLog = logRepository.save(failLog)
        val failLogId = savedFailLog.id ?: throw IllegalStateException("로그 ID가 없습니다.")

        // when: 각각 AI 리뷰 생성 (Rate Limit을 고려하여 순차 실행)
        val successResult = aiReviewService.requestOneLineReview(successLogId)
        Thread.sleep(5000) // Rate Limit 대기 (4초 + 여유)
        val failResult = aiReviewService.requestOneLineReview(failLogId)

        // then: 성공/실패에 따라 다른 리뷰가 생성됨
        assertThat(successResult.review).isNotBlank()
        assertThat(failResult.review).isNotBlank()
        // 같은 코드지만 성공/실패 정보가 다르므로 다른 리뷰가 나올 가능성이 높음 (100%는 아니지만)
        println("✅ 성공/실패 리뷰 비교!")
        println("📝 성공 케이스 리뷰: ${successResult.review}")
        println("📝 실패 케이스 리뷰: ${failResult.review}")
        println("💡 같은 코드지만 성공/실패 정보에 따라 다른 관점의 리뷰가 생성됨")
    }
}

