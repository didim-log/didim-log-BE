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
        private const val METADATA_COLLECT_JOB_KEY_PREFIX = "metadata:collect:job:"
        private const val DETAILS_COLLECT_JOB_KEY_PREFIX = "details:collect:job:"
        private const val LANGUAGE_UPDATE_JOB_KEY_PREFIX = "language:update:job:"
        private const val JOB_TTL_SECONDS = 86400L // 24시간
    }

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

                val existingProblem = problemRepository.findById(response.problemId.toString())
                val problem = if (existingProblem.isPresent) {
                    // 기존 문제가 있으면 메타데이터만 업데이트 (상세 정보는 유지)
                    val existing = existingProblem.get()
                    existing.copy(
                        title = response.titleKo,
                        difficulty = difficultyTier,
                        level = response.level,
                        category = category,
                        tags = tags
                    )
                } else {
                    // 새 문제 생성
                    Problem(
                        id = ProblemId(response.problemId.toString()),
                        title = response.titleKo,
                        category = category,
                        difficulty = difficultyTier,
                        level = response.level,
                        url = solvedAcProblemUrl(response.problemId),
                        tags = tags
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
                    sampleInputs = details.sampleInputs,
                    sampleOutputs = details.sampleOutputs
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
     * 문제 메타데이터 수집을 비동기로 시작한다.
     * 작업 ID를 반환하고, 실제 작업은 백그라운드에서 진행된다.
     *
     * @param start 시작 문제 ID
     * @param end 종료 문제 ID (포함)
     * @return 작업 ID
     */
    fun collectMetadataAsync(start: Int, end: Int): String {
        val jobId = UUID.randomUUID().toString()
        val totalCount = end - start + 1
        val status = MetadataCollectJobStatus(
            jobId = jobId,
            status = JobStatus.PENDING,
            totalCount = totalCount,
            processedCount = 0,
            successCount = 0,
            failCount = 0,
            startProblemId = start,
            endProblemId = end,
            startedAt = System.currentTimeMillis(),
            completedAt = null,
            errorMessage = null
        )
        saveJobStatus("$METADATA_COLLECT_JOB_KEY_PREFIX$jobId", status)
        collectMetadataAsyncInternal(jobId, start, end)
        return jobId
    }

    /**
     * 문제 메타데이터 수집 작업 상태를 조회한다.
     * Redis에서 작업 상태를 가져와서 MetadataCollectJobStatus 객체로 변환하여 반환한다.
     *
     * @param jobId 작업 ID
     * @return 작업 상태 (작업이 없으면 null)
     */
    fun getMetadataCollectJobStatus(jobId: String): MetadataCollectJobStatus? {
        val key = "$METADATA_COLLECT_JOB_KEY_PREFIX$jobId"
        val json = redisTemplate.opsForValue().get(key) ?: return null

        return try {
            objectMapper.readValue(json, MetadataCollectJobStatus::class.java)
        } catch (e: Exception) {
            log.warn("Failed to deserialize MetadataCollectJobStatus from Redis: jobId=$jobId, error=${e.message}", e)
            null
        }
    }

    /**
     * 문제 상세 정보 크롤링을 비동기로 시작한다.
     * 작업 ID를 반환하고, 실제 작업은 백그라운드에서 진행된다.
     *
     * @return 작업 ID
     */
    fun collectDetailsBatchAsync(): String {
        val jobId = UUID.randomUUID().toString()
        val problemsWithoutDetails = problemRepository.findByDescriptionHtmlIsNull()
        val totalCount = problemsWithoutDetails.size

        val status = DetailsCollectJobStatus(
            jobId = jobId,
            status = JobStatus.PENDING,
            totalCount = totalCount,
            processedCount = 0,
            successCount = 0,
            failCount = 0,
            startedAt = System.currentTimeMillis(),
            completedAt = null,
            errorMessage = null
        )
        saveJobStatus("$DETAILS_COLLECT_JOB_KEY_PREFIX$jobId", status)
        collectDetailsBatchAsyncInternal(jobId)
        return jobId
    }

    /**
     * 문제 상세 정보 수집 작업 상태를 조회한다.
     * Redis에서 작업 상태를 가져와서 DetailsCollectJobStatus 객체로 변환하여 반환한다.
     *
     * @param jobId 작업 ID
     * @return 작업 상태 (작업이 없으면 null)
     */
    fun getDetailsCollectJobStatus(jobId: String): DetailsCollectJobStatus? {
        val key = "$DETAILS_COLLECT_JOB_KEY_PREFIX$jobId"
        val json = redisTemplate.opsForValue().get(key) ?: return null

        return try {
            objectMapper.readValue(json, DetailsCollectJobStatus::class.java)
        } catch (e: Exception) {
            log.warn("Failed to deserialize DetailsCollectJobStatus from Redis: jobId=$jobId, error=${e.message}", e)
            null
        }
    }

    /**
     * 문제 언어 정보 최신화를 비동기로 시작한다.
     * 작업 ID를 반환하고, 실제 작업은 백그라운드에서 진행된다.
     *
     * @return 작업 ID
     */
    fun updateLanguageBatchAsync(): String {
        val jobId = UUID.randomUUID().toString()
        val problemsWithoutLanguage = problemRepository.findByLanguageIsNull()
        val totalCount = problemsWithoutLanguage.size

        val status = LanguageUpdateJobStatus(
            jobId = jobId,
            status = JobStatus.PENDING,
            totalCount = totalCount,
            processedCount = 0,
            successCount = 0,
            failCount = 0,
            startedAt = System.currentTimeMillis(),
            completedAt = null,
            errorMessage = null
        )
        saveJobStatus("$LANGUAGE_UPDATE_JOB_KEY_PREFIX$jobId", status)
        updateLanguageBatchAsyncInternal(jobId)
        return jobId
    }

    /**
     * 언어 정보 업데이트 작업 상태를 조회한다.
     * Redis에서 작업 상태를 가져와서 LanguageUpdateJobStatus 객체로 변환하여 반환한다.
     *
     * @param jobId 작업 ID
     * @return 작업 상태 (작업이 없으면 null)
     */
    fun getLanguageUpdateJobStatus(jobId: String): LanguageUpdateJobStatus? {
        val key = "$LANGUAGE_UPDATE_JOB_KEY_PREFIX$jobId"
        val json = redisTemplate.opsForValue().get(key) ?: return null

        return try {
            objectMapper.readValue(json, LanguageUpdateJobStatus::class.java)
        } catch (e: Exception) {
            log.warn("Failed to deserialize LanguageUpdateJobStatus from Redis: jobId=$jobId, error=${e.message}", e)
            null
        }
    }

    @Async
    private fun collectMetadataAsyncInternal(jobId: String, start: Int, end: Int) {
        val key = "$METADATA_COLLECT_JOB_KEY_PREFIX$jobId"
        var processedCount = 0
        var successCount = 0
        var failCount = 0

        try {
            updateJobStatus<MetadataCollectJobStatus>(key) { current ->
                current?.copy(status = JobStatus.RUNNING) ?: return@updateJobStatus null
            }

            for (problemId in start..end) {
                try {
                    val response = solvedAcClient.fetchProblem(problemId)
                    val difficultyTier = SolvedAcTierMapper.fromProblemLevel(response.level)
                    val tags = ProblemCategoryMapper.extractTagsToEnglish(response.tags)
                    val category = ProblemCategoryMapper.determineCategory(tags)

                    val existingProblem = problemRepository.findById(response.problemId.toString())
                    val problem = if (existingProblem.isPresent) {
                        existingProblem.get().copy(
                            title = response.titleKo,
                            difficulty = difficultyTier,
                            level = response.level,
                            category = category,
                            tags = tags
                        )
                    } else {
                        Problem(
                            id = ProblemId(response.problemId.toString()),
                            title = response.titleKo,
                            category = category,
                            difficulty = difficultyTier,
                            level = response.level,
                            url = solvedAcProblemUrl(response.problemId),
                            tags = tags
                        )
                    }

                    problemRepository.save(problem)
                    successCount++
                    processedCount++
                } catch (e: IllegalStateException) {
                    if (e.message?.contains("찾을 수 없습니다") == true) {
                        failCount++
                        processedCount++
                    } else {
                        failCount++
                        processedCount++
                    }
                } catch (e: Exception) {
                    failCount++
                    processedCount++
                }

                updateJobStatus<MetadataCollectJobStatus>(key) { current ->
                    current?.copy(
                        processedCount = processedCount,
                        successCount = successCount,
                        failCount = failCount
                    )
                }

                Thread.sleep(500)
            }

            updateJobStatus<MetadataCollectJobStatus>(key) { current ->
                current?.copy(
                    status = JobStatus.COMPLETED,
                    processedCount = processedCount,
                    successCount = successCount,
                    failCount = failCount,
                    completedAt = System.currentTimeMillis()
                )
            }
        } catch (e: Exception) {
            log.error("Metadata collection job failed: jobId=$jobId, error=${e.message}", e)
            updateJobStatus<MetadataCollectJobStatus>(key) { current ->
                current?.copy(
                    status = JobStatus.FAILED,
                    errorMessage = e.message,
                    completedAt = System.currentTimeMillis()
                )
            }
        }
    }

    @Async
    private fun collectDetailsBatchAsyncInternal(jobId: String) {
        val key = "$DETAILS_COLLECT_JOB_KEY_PREFIX$jobId"
        val problemsWithoutDetails = problemRepository.findByDescriptionHtmlIsNull()

        if (problemsWithoutDetails.isEmpty()) {
            updateJobStatus<DetailsCollectJobStatus>(key) { current ->
                current?.copy(
                    status = JobStatus.COMPLETED,
                    completedAt = System.currentTimeMillis()
                )
            }
            return
        }

        var processedCount = 0
        var successCount = 0
        var failCount = 0

        try {
            updateJobStatus<DetailsCollectJobStatus>(key) { current ->
                current?.copy(status = JobStatus.RUNNING) ?: return@updateJobStatus null
            }

            for (problem in problemsWithoutDetails) {
                try {
                    val details = bojCrawler.crawlProblemDetails(problem.id.value)

                    if (details == null) {
                        failCount++
                        processedCount++
                        val delay = 2000 + Random.nextInt(2000)
                        Thread.sleep(delay.toLong())
                        continue
                    }

                    val updatedProblem = problem.copy(
                        descriptionHtml = details.descriptionHtml,
                        inputDescriptionHtml = details.inputDescriptionHtml,
                        outputDescriptionHtml = details.outputDescriptionHtml,
                        sampleInputs = details.sampleInputs,
                        sampleOutputs = details.sampleOutputs
                    )
                    problemRepository.save(updatedProblem)
                    successCount++
                    processedCount++

                    updateJobStatus<DetailsCollectJobStatus>(key) { current ->
                        current?.copy(
                            processedCount = processedCount,
                            successCount = successCount,
                            failCount = failCount
                        )
                    }

                    val delay = 2000 + Random.nextInt(2000)
                    Thread.sleep(delay.toLong())
                } catch (e: Exception) {
                    log.error("Failed to collect details for problem: problemId=${problem.id.value}, error=${e.message}", e)
                    failCount++
                    processedCount++

                    updateJobStatus<DetailsCollectJobStatus>(key) { current ->
                        current?.copy(
                            processedCount = processedCount,
                            successCount = successCount,
                            failCount = failCount
                        )
                    }
                }
            }

            updateJobStatus<DetailsCollectJobStatus>(key) { current ->
                current?.copy(
                    status = JobStatus.COMPLETED,
                    processedCount = processedCount,
                    successCount = successCount,
                    failCount = failCount,
                    completedAt = System.currentTimeMillis()
                )
            }
        } catch (e: Exception) {
            log.error("Details collection job failed: jobId=$jobId, error=${e.message}", e)
            updateJobStatus<DetailsCollectJobStatus>(key) { current ->
                current?.copy(
                    status = JobStatus.FAILED,
                    errorMessage = e.message,
                    completedAt = System.currentTimeMillis()
                )
            }
        }
    }

    @Async
    private fun updateLanguageBatchAsyncInternal(jobId: String) {
        val key = "$LANGUAGE_UPDATE_JOB_KEY_PREFIX$jobId"
        val problemsWithoutLanguage = problemRepository.findByLanguageIsNull()

        if (problemsWithoutLanguage.isEmpty()) {
            updateJobStatus<LanguageUpdateJobStatus>(key) { current ->
                current?.copy(
                    status = JobStatus.COMPLETED,
                    completedAt = System.currentTimeMillis()
                )
            }
            return
        }

        var processedCount = 0
        var successCount = 0
        var failCount = 0

        try {
            updateJobStatus<LanguageUpdateJobStatus>(key) { current ->
                current?.copy(status = JobStatus.RUNNING) ?: return@updateJobStatus null
            }

            for (problem in problemsWithoutLanguage) {
                try {
                    // 언어 정보 업데이트 로직 (간단히 "ko"로 설정)
                    val updatedProblem = problem.copy(language = "ko")
                    problemRepository.save(updatedProblem)
                    successCount++
                    processedCount++

                    updateJobStatus<LanguageUpdateJobStatus>(key) { current ->
                        current?.copy(
                            processedCount = processedCount,
                            successCount = successCount,
                            failCount = failCount
                        )
                    }
                } catch (e: Exception) {
                    log.error("Failed to update language for problem: problemId=${problem.id.value}, error=${e.message}", e)
                    failCount++
                    processedCount++

                    updateJobStatus<LanguageUpdateJobStatus>(key) { current ->
                        current?.copy(
                            processedCount = processedCount,
                            successCount = successCount,
                            failCount = failCount
                        )
                    }
                }
            }

            updateJobStatus<LanguageUpdateJobStatus>(key) { current ->
                current?.copy(
                    status = JobStatus.COMPLETED,
                    processedCount = processedCount,
                    successCount = successCount,
                    failCount = failCount,
                    completedAt = System.currentTimeMillis()
                )
            }
        } catch (e: Exception) {
            log.error("Language update job failed: jobId=$jobId, error=${e.message}", e)
            updateJobStatus<LanguageUpdateJobStatus>(key) { current ->
                current?.copy(
                    status = JobStatus.FAILED,
                    errorMessage = e.message,
                    completedAt = System.currentTimeMillis()
                )
            }
        }
    }

    private fun <T> saveJobStatus(key: String, status: T) {
        try {
            val json = objectMapper.writeValueAsString(status)
            redisTemplate.opsForValue().set(key, json, java.time.Duration.ofSeconds(JOB_TTL_SECONDS))
        } catch (e: Exception) {
            log.error("Failed to save job status: key=$key, error=${e.message}", e)
        }
    }

    private inline fun <reified T> updateJobStatus(key: String, update: (T?) -> T?) {
        try {
            val json = redisTemplate.opsForValue().get(key)
            val current = if (json != null) {
                objectMapper.readValue(json, T::class.java)
            } else {
                null
            }
            val updated = update(current)
            if (updated != null) {
                val updatedJson = objectMapper.writeValueAsString(updated)
                redisTemplate.opsForValue().set(key, updatedJson, java.time.Duration.ofSeconds(JOB_TTL_SECONDS))
            }
        } catch (e: Exception) {
            log.error("Failed to update job status: key=$key, error=${e.message}", e)
        }
    }

    private fun solvedAcProblemUrl(problemId: Int): String {
        return "https://www.acmicpc.net/problem/$problemId"
    }
}
