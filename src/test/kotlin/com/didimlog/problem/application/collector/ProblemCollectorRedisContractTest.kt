package com.didimlog.problem.application.collector

import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.data.redis.core.script.DefaultRedisScript

/**
 * 수집기 Redis 계약(Lua 스크립트 본문, 키 형식, 스크립트 결과 코드)을 고정한다.
 *
 * 운영 Redis는 EVALSHA로 스크립트를 찾으므로 스크립트 문자열이 한 글자라도 바뀌면
 * SHA1이 달라진다. 수집기 코드를 옮기거나 나눌 때 이 값들이 그대로인지 확인한다.
 */
@DisplayName("ProblemCollector Redis 계약 고정 테스트")
class ProblemCollectorRedisContractTest {

    @Test
    @DisplayName("Lua 스크립트 6개의 SHA1과 결과 타입이 그대로다")
    fun `lua scripts keep their sha1`() {
        val expectedSha1ByName = mapOf(
            "CREATE_JOB_SCRIPT" to "e9bf76da6e9a5704ebd69f48a558c217f1341052",
            "COMPARE_AND_SET_JOB_SCRIPT" to "49629571b54c40d9b939ee8e9817ce4c3277bc22",
            "CLAIM_JOB_SCRIPT" to "3b78acad7f02a6e570ff7a968c529b412e15e598",
            "OWNED_COMPARE_AND_SET_JOB_SCRIPT" to "9eca56f0bbf602a5b305dffa9648c51e907bb6a2",
            "RENEW_JOB_LEASE_SCRIPT" to "c1c7fd707f4523f315ec340fbf9f752dddd96e0e",
            "RELEASE_JOB_LEASE_SCRIPT" to "6d2d50eb2825a5c924dedcd304a9f4bc30c5cf4a"
        )

        val actualScripts = scriptsByName()

        assertThat(actualScripts.keys).isEqualTo(expectedSha1ByName.keys)
        expectedSha1ByName.forEach { (name, expectedSha1) ->
            val script = actualScripts.getValue(name)
            assertThat(script.sha1).describedAs(name).isEqualTo(expectedSha1)
            assertThat(script.resultType).describedAs(name).isEqualTo(Long::class.java)
        }
    }

    @Test
    @DisplayName("Redis 키 접두사, 인덱스 키, TTL이 그대로다")
    fun `redis key prefixes stay the same`() {
        assertThat(staticValue("JOB_KEY_PREFIX")).isEqualTo("problem:job:status:")
        assertThat(staticValue("JOB_FAILURE_KEY_PREFIX")).isEqualTo("problem:job:failures:")
        assertThat(staticValue("JOB_TARGET_KEY_PREFIX")).isEqualTo("problem:job:targets:")
        assertThat(staticValue("JOB_LEASE_KEY_PREFIX")).isEqualTo("problem:job:lease:")
        assertThat(staticValue("JOB_INDEX_KEY")).isEqualTo("problem:job:index")
        assertThat(staticValue("JOB_TTL_SECONDS")).isEqualTo(86400L)
    }

    @Test
    @DisplayName("스크립트에 넘기는 작업 상태 키 순서가 그대로다")
    fun `job state keys keep their order`() {
        assertThat(jobStateKeys("job-1")).containsExactly(
            "problem:job:status:job-1",
            "problem:job:failures:job-1",
            "problem:job:targets:job-1",
            "problem:job:lease:job-1"
        )
    }

    @Test
    @DisplayName("Lua 결과 코드와 CAS 재시도 한도가 그대로다")
    fun `script result codes stay the same`() {
        assertThat(staticValue("JOB_FAILURE_LEDGER_INVALID")).isEqualTo(-2L)
        assertThat(staticValue("JOB_STATE_MISSING")).isEqualTo(-1L)
        assertThat(staticValue("JOB_STATE_CONFLICT")).isEqualTo(0L)
        assertThat(staticValue("JOB_STATE_UPDATED")).isEqualTo(1L)
        assertThat(staticValue("JOB_LEASE_CONFLICT")).isEqualTo(-3L)
        assertThat(staticValue("JOB_LEASE_INVALID")).isEqualTo(-4L)
        assertThat(staticValue("JOB_LEASE_DURATION_INVALID")).isEqualTo(-6L)
        assertThat(staticValue("MAX_JOB_STATE_RETRIES")).isEqualTo(3)
    }

    private fun scriptsByName(): Map<String, DefaultRedisScript<*>> {
        return listOf(
            "CREATE_JOB_SCRIPT",
            "COMPARE_AND_SET_JOB_SCRIPT",
            "CLAIM_JOB_SCRIPT",
            "OWNED_COMPARE_AND_SET_JOB_SCRIPT",
            "RENEW_JOB_LEASE_SCRIPT",
            "RELEASE_JOB_LEASE_SCRIPT"
        ).associateWith { name -> staticValue(name) as DefaultRedisScript<*> }
    }

    private fun staticValue(name: String): Any? {
        val field = ProblemCollectorService::class.java.getDeclaredField(name)
        field.isAccessible = true
        return field.get(null)
    }

    @Suppress("UNCHECKED_CAST")
    private fun jobStateKeys(jobId: String): List<String> {
        val service = ProblemCollectorService(
            solvedAcClient = mockk(),
            problemRepository = mockk(),
            bojCrawler = mockk(),
            redisTemplate = mockk(),
            objectMapper = mockk(),
            adminAuditService = mockk(),
            pacer = mockk(),
            recoveryState = mockk()
        )
        val method = ProblemCollectorService::class.java.getDeclaredMethod("jobStateKeys", String::class.java)
        method.isAccessible = true
        return method.invoke(service, jobId) as List<String>
    }
}
