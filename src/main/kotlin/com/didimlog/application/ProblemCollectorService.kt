package com.didimlog.application

import com.didimlog.domain.Problem
import com.didimlog.domain.repository.ProblemRepository
import com.didimlog.domain.valueobject.ProblemId
import com.didimlog.infra.crawler.BojCrawler
import com.didimlog.infra.solvedac.ProblemCategoryMapper
import com.didimlog.infra.solvedac.SolvedAcClient
import com.didimlog.infra.solvedac.SolvedAcTierMapper
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import kotlin.random.Random

/**
 * 문제 데이터 수집 서비스
 * Solved.ac API를 통해 메타데이터를 수집하고, BOJ 크롤링을 통해 상세 정보를 수집한다.
 * Rate Limit을 준수하기 위해 크롤링 시 지연 시간을 둔다.
 */
@Service
class ProblemCollectorService(
    private val solvedAcClient: SolvedAcClient,
    private val problemRepository: ProblemRepository,
    private val bojCrawler: BojCrawler
) {

    private val log = LoggerFactory.getLogger(ProblemCollectorService::class.java)

    /**
     * Solved.ac API를 통해 문제 메타데이터를 수집하여 DB에 저장한다 (Upsert).
     * 문제 ID 범위를 지정하여 일괄 수집할 수 있다.
     *
     * @param start 시작 문제 ID
     * @param end 종료 문제 ID (포함)
     */
    @Transactional
    fun collectMetadata(start: Int, end: Int) {
        log.info("문제 메타데이터 수집 시작: start=$start, end=$end")
        var successCount = 0
        var failCount = 0

        for (problemId in start..end) {
            try {
                val response = solvedAcClient.fetchProblem(problemId)
                val difficultyTier = SolvedAcTierMapper.fromProblemLevel(response.level)
                
                // 태그 추출: 한글 태그를 영문 표준명으로 변환
                val tags = ProblemCategoryMapper.extractTagsToEnglish(response.tags)
                val category = ProblemCategoryMapper.determineCategory(tags)

                // titleKo를 분석하여 언어 판별 (정확도는 낮지만 초기값으로 사용)
                val detectedLanguage = detectLanguageFromTitle(response.titleKo)

                val existingProblem = problemRepository.findById(response.problemId.toString())
                val problem = existingProblem
                    .map { existing ->
                        existing.copy(
                            title = response.titleKo,
                            difficulty = difficultyTier,
                            level = response.level,
                            category = category,
                            tags = tags,
                            language = detectedLanguage // 언어 필드 추가 (나중에 크롤링 시 재판별됨)
                        )
                    }
                    .orElseGet {
                        Problem(
                            id = ProblemId(response.problemId.toString()),
                            title = response.titleKo,
                            category = category,
                            difficulty = difficultyTier,
                            level = response.level,
                            url = solvedAcProblemUrl(response.problemId),
                            tags = tags,
                            language = detectedLanguage // 언어 필드 추가 (나중에 크롤링 시 재판별됨)
                        )
                    }

                problemRepository.save(problem)
                successCount++
                log.info("Problem ${problem.id.value} saved. (Category: ${problem.category})")
            } catch (e: IllegalStateException) {
                // Solved.ac API에서 문제를 찾을 수 없는 경우 (404 등)
                if (e.message?.contains("찾을 수 없습니다") == true) {
                    log.debug("Problem $problemId not found in Solved.ac (skipped)")
                    failCount++
                    continue
                }
                log.warn("Failed to collect problem $problemId: ${e.message}")
                failCount++
                // 다음 문제로 넘어가기 (for 루프이므로 자동으로 continue)
            } catch (e: Exception) {
                // 기타 예외 (네트워크 에러 등)
                log.warn("Failed to collect problem $problemId: ${e.message}")
                failCount++
                // 다음 문제로 넘어가기 (for 루프이므로 자동으로 continue)
            }
            
            // Rate Limiting: 0.5초 간격으로 요청 (에러 발생 여부와 관계없이 항상 실행)
            Thread.sleep(500)
        }

        log.info("문제 메타데이터 수집 완료: 성공=$successCount, 실패=$failCount")
    }

    /**
     * DB에서 descriptionHtml이 null인 문제들의 상세 정보를 크롤링하여 업데이트한다.
     * Rate Limit을 준수하기 위해 각 요청 사이에 2~4초 간격을 둔다.
     * 추후 @Scheduled로 주기적으로 실행할 수 있도록 설계되었다.
     */
    @Transactional
    fun collectDetailsBatch() {
        log.info("문제 상세 정보 크롤링 시작")
        val problemsWithoutDetails = problemRepository.findByDescriptionHtmlIsNull()
        
        if (problemsWithoutDetails.isEmpty()) {
            log.info("상세 정보가 없는 문제가 없습니다.")
            return
        }

        log.info("상세 정보 수집 대상: ${problemsWithoutDetails.size}개")
        var successCount = 0
        var failCount = 0

        for (problem in problemsWithoutDetails) {
            try {
                val details = bojCrawler.crawlProblemDetails(problem.id.value)
                
                if (details == null) {
                    failCount++
                    log.warn("문제 상세 정보 크롤링 실패: problemId=${problem.id.value}")
                    val delay = 2000 + Random.nextInt(2000)
                    Thread.sleep(delay.toLong())
                    continue
                }

                val updatedProblem = problem.copy(
                    descriptionHtml = details.descriptionHtml,
                    inputDescriptionHtml = details.inputDescriptionHtml,
                    outputDescriptionHtml = details.outputDescriptionHtml,
                    sampleInputs = details.sampleInputs.takeIf { it.isNotEmpty() },
                    sampleOutputs = details.sampleOutputs.takeIf { it.isNotEmpty() },
                    language = details.language
                )
                problemRepository.save(updatedProblem)
                successCount++
                log.debug("문제 상세 정보 수집 성공: problemId=${problem.id.value}")

                // Anti-Ban Logic: 2~4초 간격으로 요청
                val delay = 2000 + Random.nextInt(2000)
                Thread.sleep(delay.toLong())
            } catch (e: Exception) {
                log.error("문제 상세 정보 수집 중 예외 발생: problemId=${problem.id.value}, error=${e.message}", e)
                failCount++
                // 다음 문제로 넘어가기 위해 예외를 잡고 계속 진행
            }
        }

        log.info("문제 상세 정보 크롤링 완료: 성공=$successCount, 실패=$failCount")
    }

    /**
     * 문제 통계 정보를 조회한다.
     * 총 문제 수, 최소 문제 ID, 최대 문제 ID를 반환한다.
     *
     * @return 문제 통계 정보
     */
    fun getProblemStats(): com.didimlog.ui.dto.ProblemStatsResponse {
        val totalCount = problemRepository.count()
        
        val allProblems = problemRepository.findAll()
        
        val minProblemId = allProblems
            .mapNotNull { problem ->
                try {
                    problem.id.value.toInt()
                } catch (e: NumberFormatException) {
                    null
                }
            }
            .minOrNull()
        
        val maxProblemId = allProblems
            .mapNotNull { problem ->
                try {
                    problem.id.value.toInt()
                } catch (e: NumberFormatException) {
                    null
                }
            }
            .maxOrNull()
        
        return com.didimlog.ui.dto.ProblemStatsResponse(
            totalCount = totalCount,
            minProblemId = minProblemId,
            maxProblemId = maxProblemId
        )
    }

    /**
     * Solved.ac API 응답의 titleKo를 분석하여 언어를 판별한다.
     * 정확도는 낮지만 초기값으로 사용하며, 나중에 크롤링 시 재판별된다.
     *
     * @param titleKo 제목 (한국어일 수도 있고 다른 언어일 수도 있음)
     * @return "ko", "en", "ja", "zh", "other" 중 하나
     */
    private fun detectLanguageFromTitle(titleKo: String): String {
        if (titleKo.isBlank()) {
            return "other" // 제목이 없으면 기타로 분류
        }

        // 간단한 판별: 한글이 5개 이상이면 "ko", 그렇지 않으면 크롤링 필요
        val koreanCharCount = titleKo.count { char ->
            val codePoint = char.code
            codePoint in 0xAC00..0xD7A3
        }

        return if (koreanCharCount >= 5) {
            "ko"
        } else {
            // 정확한 언어 판별은 크롤링 시 수행되므로, 여기서는 "other"로 설정
            "other" // 크롤링 시 재판별됨
        }
    }

    /**
     * DB에 저장된 모든 문제의 언어 정보를 재판별하여 업데이트한다.
     * 기존 크롤링 데이터는 유지하고 language 필드만 업데이트한다.
     * Rate Limit을 준수하기 위해 각 요청 사이에 2~4초 간격을 둔다.
     *
     * @return 업데이트된 문제 수
     */
    @Transactional
    fun updateLanguageBatch(): Int {
        log.info("문제 언어 정보 최신화 시작")
        val allProblems = problemRepository.findAll()

        if (allProblems.isEmpty()) {
            log.info("업데이트할 문제가 없습니다.")
            return 0
        }

        log.info("언어 정보 최신화 대상: ${allProblems.size}개")
        var successCount = 0
        var failCount = 0

        for (problem in allProblems) {
            try {
                // BOJ 크롤링으로 언어 재판별
                val details = bojCrawler.crawlProblemDetails(problem.id.value)

                if (details == null) {
                    failCount++
                    log.warn("문제 언어 정보 업데이트 실패: problemId=${problem.id.value} (크롤링 실패)")
                    val delay = 2000 + Random.nextInt(2000)
                    Thread.sleep(delay.toLong())
                    continue
                }

                // language 필드만 업데이트 (기존 데이터 유지)
                val updatedProblem = problem.copy(
                    language = details.language
                )
                problemRepository.save(updatedProblem)
                successCount++
                log.debug("문제 언어 정보 업데이트 성공: problemId=${problem.id.value}, language=${details.language}")

                // Anti-Ban Logic: 2~4초 간격으로 요청
                val delay = 2000 + Random.nextInt(2000)
                Thread.sleep(delay.toLong())
            } catch (e: Exception) {
                log.error("문제 언어 정보 업데이트 중 예외 발생: problemId=${problem.id.value}, error=${e.message}", e)
                failCount++
                // 다음 문제로 넘어가기 위해 예외를 잡고 계속 진행
            }
        }

        log.info("문제 언어 정보 최신화 완료: 성공=$successCount, 실패=$failCount")
        return successCount
    }

    private fun solvedAcProblemUrl(problemId: Int): String {
        return "https://www.acmicpc.net/problem/$problemId"
    }
}
