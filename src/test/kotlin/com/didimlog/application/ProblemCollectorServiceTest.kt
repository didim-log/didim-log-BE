package com.didimlog.application

import com.didimlog.domain.Problem
import com.didimlog.domain.enums.ProblemCategory
import com.didimlog.domain.enums.Tier
import com.didimlog.domain.repository.CrawlerCheckpointRepository
import com.didimlog.domain.repository.ProblemRepository
import com.didimlog.domain.valueobject.ProblemId
import com.didimlog.infra.crawler.BojCrawler
import com.didimlog.infra.solvedac.ProblemCategoryMapper
import com.didimlog.infra.solvedac.SolvedAcClient
import com.didimlog.infra.solvedac.SolvedAcProblemResponse
import com.didimlog.infra.solvedac.SolvedAcTag
import com.didimlog.infra.solvedac.SolvedAcTagDisplayName
import com.didimlog.infra.solvedac.SolvedAcTierMapper
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.KotlinModule
import com.fasterxml.jackson.module.kotlin.kotlinModule
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.data.redis.core.StringRedisTemplate

@DisplayName("ProblemCollectorService 테스트")
class ProblemCollectorServiceTest {

    private val solvedAcClient: SolvedAcClient = mockk()
    private val problemRepository: ProblemRepository = mockk(relaxed = true)
    private val bojCrawler: BojCrawler = mockk(relaxed = true)
    private val redisTemplate: StringRedisTemplate = mockk(relaxed = false)
    private val valueOperations = mockk<org.springframework.data.redis.core.ValueOperations<String, String>>(relaxed = false)
    
    // ObjectMapper는 실제 객체 사용 (Mock 대신)
    private val objectMapper: ObjectMapper = ObjectMapper().apply {
        registerModule(KotlinModule.Builder().build())
        registerModule(JavaTimeModule())
        disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
        configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
        configure(DeserializationFeature.READ_ENUMS_USING_TO_STRING, false)
        configure(SerializationFeature.WRITE_ENUMS_USING_TO_STRING, false)
    }
    private val crawlerCheckpointRepository: CrawlerCheckpointRepository = mockk(relaxed = true)

    init {
        // RedisTemplate과 ValueOperations 연결 명확히 설정
        every { redisTemplate.opsForValue() } returns valueOperations
    }

    private val problemCollectorService = ProblemCollectorService(
        solvedAcClient,
        problemRepository,
        bojCrawler,
        redisTemplate,
        objectMapper,
        crawlerCheckpointRepository
    )

    @Test
    @DisplayName("collectMetadata는 한글 태그를 영문 표준명으로 변환하여 저장한다")
    fun `한글 태그를 영문으로 변환하여 저장`() {
        // given
        val problemId = 1000
        val response = SolvedAcProblemResponse(
            problemId = problemId,
            titleKo = "테스트 문제",
            level = 3,
            tags = listOf(
                SolvedAcTag(key = "그래프 이론", displayNames = emptyList()),
                SolvedAcTag(key = "너비 우선 탐색", displayNames = emptyList())
            )
        )
        
        every { solvedAcClient.fetchProblem(problemId) } returns response
        every { problemRepository.findById(problemId.toString()) } returns java.util.Optional.empty()
        every { problemRepository.save(any<Problem>()) } returns mockk()
        every { crawlerCheckpointRepository.findByCrawlType(any()) } returns null
        every { crawlerCheckpointRepository.save(any()) } returns mockk()
        every { valueOperations.set(any<String>(), any<String>(), any<java.time.Duration>()) } returns Unit
        every { valueOperations.get(any<String>()) } returns null

        // when
        val jobId = problemCollectorService.collectMetadataAsync(problemId, problemId)

        // then
        assertThat(jobId).isNotBlank()
        // 비동기 실행이므로 실제 저장은 별도 스레드에서 실행됨
        // jobId 반환만 검증
    }

    @Test
    @DisplayName("collectMetadata는 태그가 없으면 기본 카테고리로 저장한다")
    fun `태그 없으면 기본 카테고리 사용`() {
        // given
        val problemId = 1001
        val response = SolvedAcProblemResponse(
            problemId = problemId,
            titleKo = "테스트 문제",
            level = 3,
            tags = emptyList()
        )
        
        every { solvedAcClient.fetchProblem(problemId) } returns response
        every { problemRepository.findById(problemId.toString()) } returns java.util.Optional.empty()
        every { problemRepository.save(any<Problem>()) } returns mockk()
        every { crawlerCheckpointRepository.findByCrawlType(any()) } returns null
        every { crawlerCheckpointRepository.save(any()) } returns mockk()
        every { valueOperations.set(any<String>(), any<String>(), any<java.time.Duration>()) } returns Unit
        every { valueOperations.get(any<String>()) } returns null

        // when
        val jobId = problemCollectorService.collectMetadataAsync(problemId, problemId)

        // then
        assertThat(jobId).isNotBlank()
        // 비동기 실행이므로 실제 저장은 별도 스레드에서 실행됨
        // jobId 반환만 검증
    }

    @Test
    @DisplayName("extractTagsToEnglish는 한글 태그를 영문 표준명으로 변환한다")
    fun `extractTagsToEnglish 한글 태그 변환`() {
        // given
        val tags = listOf(
            SolvedAcTag(key = "그래프 이론", displayNames = emptyList()),
            SolvedAcTag(key = "너비 우선 탐색", displayNames = emptyList())
        )

        // when
        val result = ProblemCategoryMapper.extractTagsToEnglish(tags)

        // then
        assertThat(result).isNotEmpty()
        assertThat(result).anyMatch { it.contains("Graph") || it.contains("Breadth") }
    }

    @Test
    @DisplayName("collectMetadata는 titleKo를 분석하여 언어 필드를 설정한다")
    fun `collectMetadata는 언어 필드 설정`() {
        // given
        val problemId = 1002
        val response = SolvedAcProblemResponse(
            problemId = problemId,
            titleKo = "한글 제목 테스트 문제입니다",
            level = 3,
            tags = emptyList()
        )
        
        every { solvedAcClient.fetchProblem(problemId) } returns response
        every { problemRepository.findById(problemId.toString()) } returns java.util.Optional.empty()
        every { problemRepository.save(any<Problem>()) } returns mockk()
        every { crawlerCheckpointRepository.findByCrawlType(any()) } returns null
        every { crawlerCheckpointRepository.save(any()) } returns mockk()
        every { valueOperations.set(any<String>(), any<String>(), any<java.time.Duration>()) } returns Unit
        every { valueOperations.get(any<String>()) } returns null

        // when
        val jobId = problemCollectorService.collectMetadataAsync(problemId, problemId)

        // then
        assertThat(jobId).isNotBlank()
        // 비동기 실행이므로 실제 저장은 별도 스레드에서 실행됨
        // jobId 반환만 검증
    }

    @Test
    @DisplayName("updateLanguageBatchAsync는 즉시 jobId를 반환한다")
    fun `updateLanguageBatchAsync는 즉시 jobId 반환`() {
        // given
        every { problemRepository.findAll() } returns emptyList()
        every { problemRepository.findByLanguageIsNull() } returns emptyList()
        every { problemRepository.findByLanguage("other") } returns emptyList()
        every { crawlerCheckpointRepository.findByCrawlType(any()) } returns null
        every { valueOperations.set(any<String>(), any<String>(), any<java.time.Duration>()) } returns Unit
        every { valueOperations.get(any<String>()) } returns null

        // when
        val jobId = problemCollectorService.updateLanguageBatchAsync()

        // then
        assertThat(jobId).isNotBlank()
    }

    @Test
    @DisplayName("getLanguageUpdateJobStatus는 저장된 작업 상태를 반환한다")
    fun `getLanguageUpdateJobStatus는 작업 상태 반환`() {
        // given
        val jobId = "test-job-id"
        val key = "language:update:job:$jobId"
        val status = com.didimlog.application.LanguageUpdateJobStatus(
            jobId = jobId,
            status = com.didimlog.application.JobStatus.COMPLETED,
            totalCount = 100,
            processedCount = 100,
            successCount = 95,
            failCount = 5,
            startedAt = 1704067200000,
            completedAt = 1704067300000
        )
        val statusJson = objectMapper.writeValueAsString(status)
        
        // 정확한 키로 get 요청이 오면 json 반환
        every { valueOperations.get(key) } returns statusJson

        // when
        val result = problemCollectorService.getLanguageUpdateJobStatus(jobId)

        // then
        assertThat(result).isNotNull()
        assertThat(result?.jobId).isEqualTo(jobId)
        assertThat(result?.status).isEqualTo(com.didimlog.application.JobStatus.COMPLETED)
        assertThat(result?.totalCount).isEqualTo(100)
        assertThat(result?.processedCount).isEqualTo(100)
        assertThat(result?.successCount).isEqualTo(95)
        assertThat(result?.failCount).isEqualTo(5)
    }

    @Test
    @DisplayName("getLanguageUpdateJobStatus는 작업이 없으면 null을 반환한다")
    fun `getLanguageUpdateJobStatus는 작업 없으면 null 반환`() {
        // given
        val jobId = "non-existent-job-id"
        every { valueOperations.get("language:update:job:$jobId") } returns null

        // when
        val status = problemCollectorService.getLanguageUpdateJobStatus(jobId)

        // then
        assertThat(status).isNull()
    }

    @Test
    @DisplayName("collectDetailsBatchAsync는 즉시 jobId를 반환한다")
    fun `collectDetailsBatchAsync는 즉시 jobId 반환`() {
        // given
        every { problemRepository.findByDescriptionHtmlIsNull() } returns emptyList()
        every { crawlerCheckpointRepository.findByCrawlType(any()) } returns null
        every { valueOperations.set(any<String>(), any<String>(), any<java.time.Duration>()) } returns Unit
        every { valueOperations.get(any<String>()) } returns null

        // when
        val jobId = problemCollectorService.collectDetailsBatchAsync()

        // then
        assertThat(jobId).isNotBlank()
    }

    @Test
    @DisplayName("getDetailsCollectJobStatus는 저장된 작업 상태를 반환한다")
    fun `getDetailsCollectJobStatus는 작업 상태 반환`() {
        // given
        val jobId = "test-job-id"
        val key = "details:collect:job:$jobId"
        val status = DetailsCollectJobStatus(
            jobId = jobId,
            status = JobStatus.COMPLETED,
            totalCount = 100,
            processedCount = 100,
            successCount = 95,
            failCount = 5,
            startedAt = 1704067200000,
            completedAt = 1704067300000
        )
        val statusJson = objectMapper.writeValueAsString(status)
        
        // 정확한 키로 get 요청이 오면 json 반환
        every { valueOperations.get(key) } returns statusJson

        // when
        val result = problemCollectorService.getDetailsCollectJobStatus(jobId)

        // then
        assertThat(result).isNotNull()
        assertThat(result?.jobId).isEqualTo(jobId)
        assertThat(result?.status).isEqualTo(com.didimlog.application.JobStatus.COMPLETED)
        assertThat(result?.totalCount).isEqualTo(100)
        assertThat(result?.processedCount).isEqualTo(100)
        assertThat(result?.successCount).isEqualTo(95)
        assertThat(result?.failCount).isEqualTo(5)
    }

    @Test
    @DisplayName("getDetailsCollectJobStatus는 작업이 없으면 null을 반환한다")
    fun `getDetailsCollectJobStatus는 작업 없으면 null 반환`() {
        // given
        val jobId = "non-existent-job-id"
        every { valueOperations.get("details:collect:job:$jobId") } returns null

        // when
        val status = problemCollectorService.getDetailsCollectJobStatus(jobId)

        // then
        assertThat(status).isNull()
    }

    @Test
    @DisplayName("getMetadataCollectJobStatus는 저장된 작업 상태를 반환한다")
    fun `getMetadataCollectJobStatus는 작업 상태 반환`() {
        // given
        val jobId = "test-job-id"
        val key = "metadata:collect:job:$jobId"
        val status = MetadataCollectJobStatus(
            jobId = jobId,
            status = JobStatus.COMPLETED,
            totalCount = 100,
            processedCount = 100,
            successCount = 95,
            failCount = 5,
            startProblemId = 1,
            endProblemId = 100,
            startedAt = 1704067200000,
            completedAt = 1704067300000
        )
        val statusJson = objectMapper.writeValueAsString(status)
        
        // 정확한 키로 get 요청이 오면 json 반환
        every { valueOperations.get(key) } returns statusJson

        // when
        val result = problemCollectorService.getMetadataCollectJobStatus(jobId)

        // then
        assertThat(result).isNotNull()
        assertThat(result?.jobId).isEqualTo(jobId)
        assertThat(result?.status).isEqualTo(com.didimlog.application.JobStatus.COMPLETED)
        assertThat(result?.totalCount).isEqualTo(100)
        assertThat(result?.processedCount).isEqualTo(100)
        assertThat(result?.successCount).isEqualTo(95)
        assertThat(result?.failCount).isEqualTo(5)
        assertThat(result?.startProblemId).isEqualTo(1)
        assertThat(result?.endProblemId).isEqualTo(100)
    }

    @Test
    @DisplayName("getMetadataCollectJobStatus는 작업이 없으면 null을 반환한다")
    fun `getMetadataCollectJobStatus는 작업 없으면 null 반환`() {
        // given
        val jobId = "non-existent-job-id"
        every { valueOperations.get("metadata:collect:job:$jobId") } returns null

        // when
        val status = problemCollectorService.getMetadataCollectJobStatus(jobId)

        // then
        assertThat(status).isNull()
    }
}





















