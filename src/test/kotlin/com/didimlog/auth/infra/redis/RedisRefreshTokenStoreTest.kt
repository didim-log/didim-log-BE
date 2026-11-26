package com.didimlog.auth.infra.redis

import io.mockk.every
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.core.ValueOperations
import org.springframework.data.redis.core.script.RedisScript

@DisplayName("RedisRefreshTokenStore 테스트")
class RedisRefreshTokenStoreTest {

    private val redisTemplate: StringRedisTemplate = mockk()
    private val valueOps: ValueOperations<String, String> = mockk()
    private val store = RedisRefreshTokenStore(redisTemplate)

    private val scriptKeys = mutableListOf<List<String>>()
    private val scriptArgs = mutableListOf<List<Any?>>()
    private val results = ArrayDeque<Long>()

    @BeforeEach
    fun setUp() {
        every { redisTemplate.opsForValue() } returns valueOps
        every {
            redisTemplate.execute(any<RedisScript<Long>>(), any<List<String>>(), *anyVararg())
        } answers {
            scriptKeys += secondArg<List<String>>()
            scriptArgs += args.drop(2).flatMap { arg -> if (arg is Array<*>) arg.toList() else listOf(arg) }
            results.removeFirst()
        }
    }

    @Test
    @DisplayName("저장은 토큰 키와 학생 토큰 집합 키에 TTL과 함께 기록한다")
    fun `토큰 저장`() {
        results += 1L

        store.save("token-1", "student-1", 3600L)

        assertThat(scriptKeys.single()).containsExactly("refresh:token:token-1", "refresh:student:student-1")
        assertThat(scriptArgs.single()).containsExactly("student-1", "token-1", "3600")
    }

    @Test
    @DisplayName("저장된 학생 ID와 일치할 때만 토큰이 유효하다")
    fun `토큰 일치 확인`() {
        every { valueOps.get("refresh:token:token-1") } returns "student-1"
        every { valueOps.get("refresh:token:missing") } returns null

        assertThat(store.matches("token-1", "student-1")).isTrue()
        assertThat(store.matches("token-1", "student-2")).isFalse()
        assertThat(store.matches("missing", "student-1")).isFalse()
    }

    @Test
    @DisplayName("교체는 기존 토큰, 새 토큰, 학생 집합 키로 원자적으로 수행하고 성공 결과만 true로 본다")
    fun `토큰 교체`() {
        results.addAll(listOf(1L, 0L, -1L, -2L, 99L))

        val outcomes = List(5) { store.rotate("old", "new", "student-1", 7200L) }

        assertThat(outcomes).containsExactly(true, false, false, false, false)
        assertThat(scriptKeys.first()).containsExactly(
            "refresh:token:old",
            "refresh:token:new",
            "refresh:student:student-1"
        )
        assertThat(scriptArgs.first()).containsExactly("student-1", "old", "new", "7200")
    }

    @Test
    @DisplayName("학생 토큰 전체 삭제는 학생 집합 키와 토큰 키 접두어를 전달한다")
    fun `학생 토큰 전체 삭제`() {
        results += 2L

        store.deleteByStudentId("student-1")

        assertThat(scriptKeys.single()).containsExactly("refresh:student:student-1")
        assertThat(scriptArgs.single()).containsExactly("refresh:token:")
    }
}
