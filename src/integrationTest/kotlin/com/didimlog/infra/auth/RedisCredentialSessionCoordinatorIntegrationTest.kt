package com.didimlog.infra.auth

import com.didimlog.application.auth.CredentialSessionCoordinator
import com.didimlog.application.student.StudentLifecycleCoordinator
import com.didimlog.global.exception.BusinessException
import com.didimlog.global.exception.ErrorCode
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.data.redis.DataRedisTest
import org.springframework.context.annotation.Import
import org.springframework.data.redis.core.StringRedisTemplate

@DataRedisTest(
    properties = [
        "spring.data.redis.host=127.0.0.1",
        "spring.data.redis.port=\${TEST_REDIS_PORT:6379}"
    ]
)
@Import(RedisCredentialSessionCoordinator::class, CredentialSessionLockConfig::class)
@Execution(ExecutionMode.SAME_THREAD)
@DisplayName("Redis 자격 증명 세션 조정자 통합 테스트")
class RedisCredentialSessionCoordinatorIntegrationTest {

    @Autowired
    private lateinit var coordinator: CredentialSessionCoordinator

    @Autowired
    private lateinit var studentLifecycleCoordinator: StudentLifecycleCoordinator

    @Autowired
    private lateinit var redisTemplate: StringRedisTemplate

    private val keysToClean = mutableSetOf<String>()

    @AfterEach
    fun cleanUp() {
        if (keysToClean.isNotEmpty()) {
            redisTemplate.delete(keysToClean)
        }
        keysToClean.clear()
    }

    @Test
    @DisplayName("일반 실행은 작업 완료 뒤 잠금 키가 없어져도 완료 결과를 반환한다")
    fun `ordinary execution does not fail after action completed`() {
        val studentId = "ordinary-${UUID.randomUUID()}"
        val lockKey = lockKey(studentId)
        keysToClean += lockKey

        val result = coordinator.execute(studentId) {
            assertThat(redisTemplate.delete(lockKey)).isTrue()
            "completed"
        }

        assertThat(result).isEqualTo("completed")
        assertThat(redisTemplate.hasKey(lockKey)).isFalse()
    }

    @Test
    @DisplayName("작업 중 잠금 키가 삭제되면 완료 결과를 거절한다")
    fun `rejects result when lock key is deleted`() {
        val studentId = "deleted-${UUID.randomUUID()}"
        val lockKey = lockKey(studentId)
        keysToClean += lockKey

        val exception = assertThrows<BusinessException> {
            coordinator.executeWithCompletionCheck<Unit>(studentId) {
                assertThat(redisTemplate.delete(lockKey)).isTrue()
            }
        }

        assertThat(exception.errorCode).isEqualTo(ErrorCode.SESSION_STATE_CONFLICT)
        assertThat(redisTemplate.hasKey(lockKey)).isFalse()
    }

    @Test
    @DisplayName("작업 중 잠금 소유자가 바뀌면 완료 결과를 거절하고 새 소유자의 키를 보존한다")
    fun `rejects result when lock owner is replaced`() {
        val studentId = "replaced-${UUID.randomUUID()}"
        val lockKey = lockKey(studentId)
        keysToClean += lockKey

        val exception = assertThrows<BusinessException> {
            coordinator.executeWithCompletionCheck<Unit>(studentId) {
                redisTemplate.opsForValue().set(lockKey, FOREIGN_OWNER)
            }
        }

        assertThat(exception.errorCode).isEqualTo(ErrorCode.SESSION_STATE_CONFLICT)
        assertThat(redisTemplate.opsForValue().get(lockKey)).isEqualTo(FOREIGN_OWNER)
    }

    @Test
    @DisplayName("학생 생명주기 작업과 자격 증명 작업은 같은 학생 잠금을 사용한다")
    fun `lifecycle and credential operations share one lock`() {
        val studentId = "shared-${UUID.randomUUID()}"
        val lockKey = lockKey(studentId)
        keysToClean += lockKey
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val executor = Executors.newSingleThreadExecutor()

        try {
            val lifecycleTask = executor.submit {
                studentLifecycleCoordinator.execute(studentId) {
                    entered.countDown()
                    check(release.await(10, TimeUnit.SECONDS)) {
                        "학생 생명주기 잠금 해제 신호를 기다리지 못했습니다."
                    }
                }
            }
            assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue()

            val exception = assertThrows<BusinessException> {
                coordinator.execute(studentId) { error("잠금 충돌 중 작업이 실행되면 안 됩니다.") }
            }

            assertThat(exception.errorCode).isEqualTo(ErrorCode.SESSION_STATE_CONFLICT)
            release.countDown()
            lifecycleTask.get(10, TimeUnit.SECONDS)
        } finally {
            release.countDown()
            executor.shutdownNow()
        }
    }

    private fun lockKey(studentId: String): String = "credential:session:lock:$studentId"

    companion object {
        private const val FOREIGN_OWNER = "replacement-owner"
    }
}
