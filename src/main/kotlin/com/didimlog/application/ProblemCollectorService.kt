package com.didimlog.application

import com.didimlog.domain.Problem
import com.didimlog.domain.repository.ProblemRepository
import com.didimlog.domain.valueobject.ProblemId
import com.didimlog.infra.crawler.BojCrawler
import com.didimlog.infra.solvedac.ProblemCategoryMapper
import com.didimlog.infra.solvedac.SolvedAcClient
import com.didimlog.infra.solvedac.SolvedAcTierMapper
import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.scheduling.annotation.Async
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Duration
import java.util.UUID
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
    private val bojCrawler: BojCrawler,
    private val redisTemplate: StringRedisTemplate,
    private val objectMapper: ObjectMapper
) {

    private val log = LoggerFactory.getLogger(ProblemCollectorService::class.java)

    companion object {
        private const val LANGUAGE_UPDATE_JOB_KEY_PREFIX = "language:update:job:"
        private const val DETAILS_COLLECT_JOB_KEY_PREFIX = "details:collect:job:"
        private const val METADATA_COLLECT_JOB_KEY_PREFIX = "metadata:collect:job:"
        private const val JOB_STATUS_TTL_HOURS = 24L // 24시간 후 자동 삭제
    }

    /**
     * Solved.ac API를 통해 문제 메타데이터를 수집하여 DB에 저장한다 (비동기 처리).
     * 작업을 백그라운드에서 실행하고 즉시 작업 ID를 반환한다.
     * 작업 진행 상황은 getMetadataCollectJobStatus()로 조회할 수 있다.
     *
     * @param start 시작 문제 ID
     * @param end 종료 문제 ID (포함)
     * @return 작업 ID (작업 상태 조회에 사용)
     */
    fun collectMetadataAsync(start: Int, end: Int): String {
        val jobId = UUID.randomUUID().toString()
        val totalCount = end - start + 1

        // 초기 상태 저장
        val initialStatus = MetadataCollectJobStatus(
            jobId = jobId,
            status = JobStatus.PENDING,
            totalCount = totalCount,
            processedCount = 0,
            successCount = 0,
            failCount = 0,
            startProblemId = start,
            endProblemId = end,
            startedAt = System.currentTimeMillis()
        )
        saveMetadataCollectJobStatus(initialStatus)

        // 비동기로 작업 실행
        executeMetadataCollectAsync(jobId, start, end)

        return jobId
    }

    /**
     * 문제 메타데이터 수집 작업 상태를 조회한다.
     *
     * @param jobId 작업 ID
     * @return 작업 상태 (없으면 null)
     */
    fun getMetadataCollectJobStatus(jobId: String): MetadataCollectJobStatus? {
        val key = METADATA_COLLECT_JOB_KEY_PREFIX + jobId
        val json = redisTemplate.opsForValue().get(key) ?: return null

        return try {
            objectMapper.readValue(json, MetadataCollectJobStatus::class.java)
        } catch (e: Exception) {
            log.error("작업 상태 파싱 실패: jobId=$jobId", e)
            null
        }
    }

    /**
     * 비동기로 문제 메타데이터 수집 작업을 실행한다.
     */
    @Async
    fun executeMetadataCollectAsync(jobId: String, start: Int, end: Int) {
        log.info("문제 메타데이터 수집 시작: jobId=$jobId, start=$start, end=$end")
        val totalCount = end - start + 1

        var processedCount = 0
        var successCount = 0
        var failCount = 0
        val startedAt = System.currentTimeMillis()

        // 상태를 RUNNING으로 변경
        updateMetadataCollectJobStatus(jobId, JobStatus.RUNNING, processedCount, successCount, failCount)

        try {
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
                    log.debug("Problem ${problem.id.value} saved. (Category: ${problem.category})")
                } catch (e: IllegalStateException) {
                    // Solved.ac API에서 문제를 찾을 수 없는 경우 (404 등)
                    if (e.message?.contains("찾을 수 없습니다") == true) {
                        log.debug("Problem $problemId not found in Solved.ac (skipped)")
                        failCount++
                    } else {
                        log.warn("Failed to collect problem $problemId: ${e.message}")
                        failCount++
                    }
                } catch (e: Exception) {
                    // 기타 예외 (네트워크 에러 등)
                    log.warn("Failed to collect problem $problemId: ${e.message}")
                    failCount++
                }

                processedCount++

                // 진행 상황 업데이트 (10개마다 또는 마지막 문제)
                if (processedCount % 10 == 0 || processedCount == totalCount) {
                    updateMetadataCollectJobStatus(jobId, JobStatus.RUNNING, processedCount, successCount, failCount)
                }

                // Rate Limiting: 0.5초 간격으로 요청
                Thread.sleep(500)
            }

            // 완료 상태로 변경
            val completedAt = System.currentTimeMillis()
            val finalStatus = MetadataCollectJobStatus(
                jobId = jobId,
                status = JobStatus.COMPLETED,
                totalCount = totalCount,
                processedCount = processedCount,
                successCount = successCount,
                failCount = failCount,
                startProblemId = start,
                endProblemId = end,
                startedAt = startedAt,
                completedAt = completedAt
            )
            saveMetadataCollectJobStatus(finalStatus)

            log.info("문제 메타데이터 수집 완료: jobId=$jobId, 성공=$successCount, 실패=$failCount")
        } catch (e: Exception) {
            log.error("문제 메타데이터 수집 작업 실패: jobId=$jobId", e)
            val failedStatus = MetadataCollectJobStatus(
                jobId = jobId,
                status = JobStatus.FAILED,
                totalCount = totalCount,
                processedCount = processedCount,
                successCount = successCount,
                failCount = failCount,
                startProblemId = start,
                endProblemId = end,
                startedAt = startedAt,
                completedAt = System.currentTimeMillis(),
                errorMessage = e.message
            )
            saveMetadataCollectJobStatus(failedStatus)
        }
    }

    /**
     * 문제 메타데이터 수집 작업 상태를 Redis에 저장한다.
     */
    private fun saveMetadataCollectJobStatus(status: MetadataCollectJobStatus) {
        val key = METADATA_COLLECT_JOB_KEY_PREFIX + status.jobId
        try {
            val json = objectMapper.writeValueAsString(status)
            redisTemplate.opsForValue().set(
                key,
                json,
                Duration.ofHours(JOB_STATUS_TTL_HOURS)
            )
        } catch (e: Exception) {
            log.error("작업 상태 저장 실패: jobId=${status.jobId}", e)
        }
    }

    /**
     * 문제 메타데이터 수집 작업 상태를 업데이트한다.
     */
    private fun updateMetadataCollectJobStatus(
        jobId: String,
        status: JobStatus,
        processedCount: Int,
        successCount: Int,
        failCount: Int
    ) {
        val currentStatus = getMetadataCollectJobStatus(jobId) ?: return
        val updatedStatus = currentStatus.copy(
            status = status,
            processedCount = processedCount,
            successCount = successCount,
            failCount = failCount
        )
        saveMetadataCollectJobStatus(updatedStatus)
    }

    /**
     * DB에서 descriptionHtml이 null인 문제들의 상세 정보를 크롤링하여 업데이트한다 (비동기 처리).
     * 작업을 백그라운드에서 실행하고 즉시 작업 ID를 반환한다.
     * 작업 진행 상황은 getDetailsCollectJobStatus()로 조회할 수 있다.
     *
     * @return 작업 ID (작업 상태 조회에 사용)
     */
    fun collectDetailsBatchAsync(): String {
        val jobId = UUID.randomUUID().toString()
        val problemsWithoutDetails = problemRepository.findByDescriptionHtmlIsNull()

        if (problemsWithoutDetails.isEmpty()) {
            log.info("상세 정보가 없는 문제가 없습니다.")
            val status = DetailsCollectJobStatus(
                jobId = jobId,
                status = JobStatus.COMPLETED,
                totalCount = 0,
                processedCount = 0,
                successCount = 0,
                failCount = 0,
                startedAt = System.currentTimeMillis(),
                completedAt = System.currentTimeMillis()
            )
            saveDetailsCollectJobStatus(status)
            return jobId
        }

        // 초기 상태 저장
        val initialStatus = DetailsCollectJobStatus(
            jobId = jobId,
            status = JobStatus.PENDING,
            totalCount = problemsWithoutDetails.size,
            processedCount = 0,
            successCount = 0,
            failCount = 0,
            startedAt = System.currentTimeMillis()
        )
        saveDetailsCollectJobStatus(initialStatus)

        // 비동기로 작업 실행
        executeDetailsCollectAsync(jobId, problemsWithoutDetails)

        return jobId
    }

    /**
     * 문제 상세 정보 수집 작업 상태를 조회한다.
     *
     * @param jobId 작업 ID
     * @return 작업 상태 (없으면 null)
     */
    fun getDetailsCollectJobStatus(jobId: String): DetailsCollectJobStatus? {
        val key = DETAILS_COLLECT_JOB_KEY_PREFIX + jobId
        val json = redisTemplate.opsForValue().get(key) ?: return null

        return try {
            objectMapper.readValue(json, DetailsCollectJobStatus::class.java)
        } catch (e: Exception) {
            log.error("작업 상태 파싱 실패: jobId=$jobId", e)
            null
        }
    }

    /**
     * 비동기로 문제 상세 정보 수집 작업을 실행한다.
     */
    @Async
    fun executeDetailsCollectAsync(jobId: String, problems: List<Problem>) {
        log.info("문제 상세 정보 크롤링 시작: jobId=$jobId, totalCount=${problems.size}")

        var processedCount = 0
        var successCount = 0
        var failCount = 0
        val startedAt = System.currentTimeMillis()

        // 상태를 RUNNING으로 변경
        updateDetailsCollectJobStatus(jobId, JobStatus.RUNNING, processedCount, successCount, failCount)

        try {
            for (problem in problems) {
                try {
                    val details = bojCrawler.crawlProblemDetails(problem.id.value)

                    if (details == null) {
                        failCount++
                        log.warn("문제 상세 정보 크롤링 실패: problemId=${problem.id.value}")
                    } else {
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
                    }

                    processedCount++

                    // 진행 상황 업데이트 (10개마다 또는 마지막 문제)
                    if (processedCount % 10 == 0 || processedCount == problems.size) {
                        updateDetailsCollectJobStatus(jobId, JobStatus.RUNNING, processedCount, successCount, failCount)
                    }

                    // Anti-Ban Logic: 2~4초 간격으로 요청
                    val delay = 2000 + Random.nextInt(2000)
                    Thread.sleep(delay.toLong())
                } catch (e: Exception) {
                    log.error("문제 상세 정보 수집 중 예외 발생: problemId=${problem.id.value}, error=${e.message}", e)
                    failCount++
                    processedCount++
                    // 다음 문제로 넘어가기 위해 예외를 잡고 계속 진행
                }
            }

            // 완료 상태로 변경
            val completedAt = System.currentTimeMillis()
            val finalStatus = DetailsCollectJobStatus(
                jobId = jobId,
                status = JobStatus.COMPLETED,
                totalCount = problems.size,
                processedCount = processedCount,
                successCount = successCount,
                failCount = failCount,
                startedAt = startedAt,
                completedAt = completedAt
            )
            saveDetailsCollectJobStatus(finalStatus)

            log.info("문제 상세 정보 크롤링 완료: jobId=$jobId, 성공=$successCount, 실패=$failCount")
        } catch (e: Exception) {
            log.error("문제 상세 정보 수집 작업 실패: jobId=$jobId", e)
            val failedStatus = DetailsCollectJobStatus(
                jobId = jobId,
                status = JobStatus.FAILED,
                totalCount = problems.size,
                processedCount = processedCount,
                successCount = successCount,
                failCount = failCount,
                startedAt = startedAt,
                completedAt = System.currentTimeMillis(),
                errorMessage = e.message
            )
            saveDetailsCollectJobStatus(failedStatus)
        }
    }

    /**
     * 언어 업데이트 작업 상태를 Redis에 저장한다.
     */
    private fun saveJobStatus(status: LanguageUpdateJobStatus) {
        val key = LANGUAGE_UPDATE_JOB_KEY_PREFIX + status.jobId
        try {
            val json = objectMapper.writeValueAsString(status)
            redisTemplate.opsForValue().set(
                key,
                json,
                Duration.ofHours(JOB_STATUS_TTL_HOURS)
            )
        } catch (e: Exception) {
            log.error("작업 상태 저장 실패: jobId=${status.jobId}", e)
        }
    }

    /**
     * 문제 상세 정보 수집 작업 상태를 Redis에 저장한다.
     */
    private fun saveDetailsCollectJobStatus(status: DetailsCollectJobStatus) {
        val key = DETAILS_COLLECT_JOB_KEY_PREFIX + status.jobId
        try {
            val json = objectMapper.writeValueAsString(status)
            redisTemplate.opsForValue().set(
                key,
                json,
                Duration.ofHours(JOB_STATUS_TTL_HOURS)
            )
        } catch (e: Exception) {
            log.error("작업 상태 저장 실패: jobId=${status.jobId}", e)
        }
    }

    /**
     * 언어 업데이트 작업 상태를 업데이트한다.
     */
    private fun updateJobStatus(
        jobId: String,
        status: JobStatus,
        processedCount: Int,
        successCount: Int,
        failCount: Int
    ) {
        val currentStatus = getLanguageUpdateJobStatus(jobId) ?: return
        val updatedStatus = currentStatus.copy(
            status = status,
            processedCount = processedCount,
            successCount = successCount,
            failCount = failCount
        )
        saveJobStatus(updatedStatus)
    }

    /**
     * 문제 상세 정보 수집 작업 상태를 업데이트한다.
     */
    private fun updateDetailsCollectJobStatus(
        jobId: String,
        status: JobStatus,
        processedCount: Int,
        successCount: Int,
        failCount: Int
    ) {
        val currentStatus = getDetailsCollectJobStatus(jobId) ?: return
        val updatedStatus = currentStatus.copy(
            status = status,
            processedCount = processedCount,
            successCount = successCount,
            failCount = failCount
        )
        saveDetailsCollectJobStatus(updatedStatus)
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
     * DB에 저장된 문제 중 언어 정보가 null이거나 "other"인 문제들의 언어 정보를 재판별하여 업데이트한다 (비동기 처리).
     * 작업을 백그라운드에서 실행하고 즉시 작업 ID를 반환한다.
     * 작업 진행 상황은 getLanguageUpdateJobStatus()로 조회할 수 있다.
     *
     * @return 작업 ID (작업 상태 조회에 사용)
     */
    fun updateLanguageBatchAsync(): String {
        val jobId = UUID.randomUUID().toString()
        // 언어 정보가 null이거나 "other"인 문제만 처리 (업데이트 안된 것만)
        val problemsWithNullLanguage = problemRepository.findByLanguageIsNull()
        val problemsWithOtherLanguage = problemRepository.findByLanguage("other")
        val problemsToUpdate = (problemsWithNullLanguage + problemsWithOtherLanguage).distinctBy { it.id.value }

        if (problemsToUpdate.isEmpty()) {
            log.info("업데이트할 문제가 없습니다. (모든 문제의 언어 정보가 이미 설정되어 있습니다.)")
            val status = LanguageUpdateJobStatus(
                jobId = jobId,
                status = JobStatus.COMPLETED,
                totalCount = 0,
                processedCount = 0,
                successCount = 0,
                failCount = 0,
                startedAt = System.currentTimeMillis(),
                completedAt = System.currentTimeMillis()
            )
            saveJobStatus(status)
            return jobId
        }

        log.info("언어 정보 업데이트 대상: ${problemsToUpdate.size}개 (language가 null이거나 'other'인 문제)")

        // 초기 상태 저장
        val initialStatus = LanguageUpdateJobStatus(
            jobId = jobId,
            status = JobStatus.PENDING,
            totalCount = problemsToUpdate.size,
            processedCount = 0,
            successCount = 0,
            failCount = 0,
            startedAt = System.currentTimeMillis()
        )
        saveJobStatus(initialStatus)

        // 비동기로 작업 실행
        executeLanguageUpdateAsync(jobId, problemsToUpdate)

        return jobId
    }

    /**
     * 언어 정보 업데이트 작업 상태를 조회한다.
     *
     * @param jobId 작업 ID
     * @return 작업 상태 (없으면 null)
     */
    fun getLanguageUpdateJobStatus(jobId: String): LanguageUpdateJobStatus? {
        val key = LANGUAGE_UPDATE_JOB_KEY_PREFIX + jobId
        val json = redisTemplate.opsForValue().get(key) ?: return null

        return try {
            objectMapper.readValue(json, LanguageUpdateJobStatus::class.java)
        } catch (e: Exception) {
            log.error("작업 상태 파싱 실패: jobId=$jobId", e)
            null
        }
    }

    /**
     * 비동기로 언어 정보 업데이트 작업을 실행한다.
     */
    @Async
    fun executeLanguageUpdateAsync(jobId: String, problems: List<Problem>) {
        log.info("문제 언어 정보 최신화 시작: jobId=$jobId, totalCount=${problems.size}")
        
        var processedCount = 0
        var successCount = 0
        var failCount = 0
        val startedAt = System.currentTimeMillis()

        // 상태를 RUNNING으로 변경
        updateJobStatus(jobId, JobStatus.RUNNING, processedCount, successCount, failCount)

        try {
            for (problem in problems) {
                try {
                    // BOJ 크롤링으로 언어 재판별
                    val details = bojCrawler.crawlProblemDetails(problem.id.value)

                    if (details == null) {
                        failCount++
                        log.warn("문제 언어 정보 업데이트 실패: problemId=${problem.id.value} (크롤링 실패)")
                    } else {
                        // language 필드만 업데이트 (기존 데이터 유지)
                        val updatedProblem = problem.copy(
                            language = details.language
                        )
                        problemRepository.save(updatedProblem)
                        successCount++
                        log.debug("문제 언어 정보 업데이트 성공: problemId=${problem.id.value}, language=${details.language}")
                    }

                    processedCount++

                    // 진행 상황 업데이트 (10개마다 또는 마지막 문제)
                    if (processedCount % 10 == 0 || processedCount == problems.size) {
                        updateJobStatus(jobId, JobStatus.RUNNING, processedCount, successCount, failCount)
                    }

                    // Anti-Ban Logic: 2~4초 간격으로 요청
                    val delay = 2000 + Random.nextInt(2000)
                    Thread.sleep(delay.toLong())
                } catch (e: Exception) {
                    log.error("문제 언어 정보 업데이트 중 예외 발생: problemId=${problem.id.value}, error=${e.message}", e)
                    failCount++
                    processedCount++
                    // 다음 문제로 넘어가기 위해 예외를 잡고 계속 진행
                }
            }

            // 완료 상태로 변경
            val completedAt = System.currentTimeMillis()
            val finalStatus = LanguageUpdateJobStatus(
                jobId = jobId,
                status = JobStatus.COMPLETED,
                totalCount = problems.size,
                processedCount = processedCount,
                successCount = successCount,
                failCount = failCount,
                startedAt = startedAt,
                completedAt = completedAt
            )
            saveJobStatus(finalStatus)

            log.info("문제 언어 정보 최신화 완료: jobId=$jobId, 성공=$successCount, 실패=$failCount")
        } catch (e: Exception) {
            log.error("언어 정보 업데이트 작업 실패: jobId=$jobId", e)
            val failedStatus = LanguageUpdateJobStatus(
                jobId = jobId,
                status = JobStatus.FAILED,
                totalCount = problems.size,
                processedCount = processedCount,
                successCount = successCount,
                failCount = failCount,
                startedAt = startedAt,
                completedAt = System.currentTimeMillis(),
                errorMessage = e.message
            )
            saveJobStatus(failedStatus)
        }
    }

    private fun solvedAcProblemUrl(problemId: Int): String {
        return "https://www.acmicpc.net/problem/$problemId"
    }
}
