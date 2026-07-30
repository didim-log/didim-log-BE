package com.didimlog.infra.auth

import com.didimlog.global.exception.BusinessException
import com.didimlog.global.exception.ErrorCode
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import java.time.Duration
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.data.redis.RedisConnectionFailureException
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.core.ValueOperations
import org.springframework.data.redis.core.script.RedisScript

@DisplayName("자격 증명 세션 조정자 테스트")
class RedisCredentialSessionCoordinatorTest {

    private val redisTemplate = mockk<StringRedisTemplate>()
    private val valueOperations = mockk<ValueOperations<String, String>>()
    private val renewalExecutor = mockk<ScheduledExecutorService>()
    private val renewalFuture = mockk<ScheduledFuture<*>>(relaxed = true)
    private val coordinator = RedisCredentialSessionCoordinator(redisTemplate, renewalExecutor)

    @Test
    @DisplayName("일반 실행은 잠금을 획득해 작업한 뒤 추가 소유권 확인 없이 잠금을 해제한다")
    fun `executes action and releases owned lock`() {
        every { redisTemplate.opsForValue() } returns valueOperations
        every {
            valueOperations.setIfAbsent(LOCK_KEY, any(), Duration.ofSeconds(30))
        } returns true
        every {
            renewalExecutor.scheduleAtFixedRate(
                any<Runnable>(),
                10_000L,
                10_000L,
                TimeUnit.MILLISECONDS
            )
        } returns renewalFuture
        every {
            redisTemplate.execute(
                any<RedisScript<Long>>(),
                listOf(LOCK_KEY),
                any()
            )
        } returns 1L

        val result = coordinator.execute(STUDENT_ID) { "completed" }

        assertThat(result).isEqualTo("completed")
        verify(exactly = 1) { renewalFuture.cancel(false) }
        verify(exactly = 0) {
            redisTemplate.execute(
                any<RedisScript<Long>>(),
                listOf(LOCK_KEY),
                any(),
                "30000"
            )
        }
        verify(exactly = 1) {
            redisTemplate.execute(
                any<RedisScript<Long>>(),
                listOf(LOCK_KEY),
                any()
            )
        }
    }

    @Test
    @DisplayName("엄격 실행은 작업 완료 직후 소유권을 확인한 뒤 결과를 반환한다")
    fun `checks ownership before returning strict execution result`() {
        every { redisTemplate.opsForValue() } returns valueOperations
        every {
            valueOperations.setIfAbsent(LOCK_KEY, any(), Duration.ofSeconds(30))
        } returns true
        every {
            renewalExecutor.scheduleAtFixedRate(
                any<Runnable>(),
                10_000L,
                10_000L,
                TimeUnit.MILLISECONDS
            )
        } returns renewalFuture
        every {
            redisTemplate.execute(
                any<RedisScript<Long>>(),
                listOf(LOCK_KEY),
                any(),
                "30000"
            )
        } returns 1L
        every {
            redisTemplate.execute(
                any<RedisScript<Long>>(),
                listOf(LOCK_KEY),
                any()
            )
        } returns 1L

        val result = coordinator.executeWithCompletionCheck(STUDENT_ID) { "completed" }

        assertThat(result).isEqualTo("completed")
        verify(exactly = 1) {
            redisTemplate.execute(
                any<RedisScript<Long>>(),
                listOf(LOCK_KEY),
                any(),
                "30000"
            )
        }
        verify(exactly = 1) {
            redisTemplate.execute(
                any<RedisScript<Long>>(),
                listOf(LOCK_KEY),
                any()
            )
        }
    }

    @Test
    @DisplayName("작업이 길어지면 소유권을 확인하고 잠금 TTL을 갱신한다")
    fun `renews lock ttl while action is running`() {
        val renewalTask = slot<Runnable>()
        every { redisTemplate.opsForValue() } returns valueOperations
        every {
            valueOperations.setIfAbsent(LOCK_KEY, any(), Duration.ofSeconds(30))
        } returns true
        every {
            renewalExecutor.scheduleAtFixedRate(
                capture(renewalTask),
                10_000L,
                10_000L,
                TimeUnit.MILLISECONDS
            )
        } returns renewalFuture
        every {
            redisTemplate.execute(
                any<RedisScript<Long>>(),
                listOf(LOCK_KEY),
                any(),
                "30000"
            )
        } returns 1L
        every {
            redisTemplate.execute(
                any<RedisScript<Long>>(),
                listOf(LOCK_KEY),
                any()
            )
        } returns 1L

        coordinator.execute(STUDENT_ID) {
            renewalTask.captured.run()
        }

        verify(exactly = 1) {
            redisTemplate.execute(
                any<RedisScript<Long>>(),
                listOf(LOCK_KEY),
                any(),
                "30000"
            )
        }
    }

    @Test
    @DisplayName("작업 종료 전에 잠금 소유권을 잃으면 결과를 반환하지 않는다")
    fun `rejects result after lock ownership is lost`() {
        every { redisTemplate.opsForValue() } returns valueOperations
        every {
            valueOperations.setIfAbsent(LOCK_KEY, any(), Duration.ofSeconds(30))
        } returns true
        every {
            renewalExecutor.scheduleAtFixedRate(
                any<Runnable>(),
                10_000L,
                10_000L,
                TimeUnit.MILLISECONDS
            )
        } returns renewalFuture
        every {
            redisTemplate.execute(
                any<RedisScript<Long>>(),
                listOf(LOCK_KEY),
                any(),
                "30000"
            )
        } returns 0L
        every {
            redisTemplate.execute(
                any<RedisScript<Long>>(),
                listOf(LOCK_KEY),
                any()
            )
        } returns 0L

        val exception = assertThrows<BusinessException> {
            coordinator.executeWithCompletionCheck<Unit>(STUDENT_ID) {}
        }

        assertThat(exception.errorCode).isEqualTo(ErrorCode.SESSION_STATE_CONFLICT)
        verify(exactly = 1) { renewalFuture.cancel(false) }
    }

    @Test
    @DisplayName("작업 종료 소유권 확인 중 Redis 오류가 발생하면 503으로 응답한다")
    fun `fails closed when final ownership check is unavailable`() {
        val connectionFailure = RedisConnectionFailureException("Redis unavailable")
        every { redisTemplate.opsForValue() } returns valueOperations
        every {
            valueOperations.setIfAbsent(LOCK_KEY, any(), Duration.ofSeconds(30))
        } returns true
        every {
            renewalExecutor.scheduleAtFixedRate(
                any<Runnable>(),
                10_000L,
                10_000L,
                TimeUnit.MILLISECONDS
            )
        } returns renewalFuture
        every {
            redisTemplate.execute(
                any<RedisScript<Long>>(),
                listOf(LOCK_KEY),
                any(),
                "30000"
            )
        } throws connectionFailure
        every {
            redisTemplate.execute(
                any<RedisScript<Long>>(),
                listOf(LOCK_KEY),
                any()
            )
        } returns 1L

        val exception = assertThrows<BusinessException> {
            coordinator.executeWithCompletionCheck<Unit>(STUDENT_ID) {}
        }

        assertThat(exception.errorCode).isEqualTo(ErrorCode.SESSION_STATE_UNAVAILABLE)
        assertThat(exception.cause).isSameAs(connectionFailure)
        verify(exactly = 1) { renewalFuture.cancel(false) }
    }

    @Test
    @DisplayName("다른 요청이 잠금을 보유하면 작업을 실행하지 않고 충돌로 응답한다")
    fun `rejects concurrent credential operation`() {
        var actionExecuted = false
        every { redisTemplate.opsForValue() } returns valueOperations
        every {
            valueOperations.setIfAbsent(LOCK_KEY, any(), Duration.ofSeconds(30))
        } returns false

        val exception = assertThrows<BusinessException> {
            coordinator.execute(STUDENT_ID) {
                actionExecuted = true
            }
        }

        assertThat(exception.errorCode).isEqualTo(ErrorCode.SESSION_STATE_CONFLICT)
        assertThat(actionExecuted).isFalse()
        verify(exactly = 0) {
            redisTemplate.execute(
                any<RedisScript<Long>>(),
                any<List<String>>(),
                *anyVararg()
            )
        }
    }

    @Test
    @DisplayName("Redis 연결 실패는 세션 검증을 우회하지 않고 503으로 바꾼다")
    fun `fails closed when redis is unavailable`() {
        var actionExecuted = false
        val connectionFailure = RedisConnectionFailureException("Redis unavailable")
        every { redisTemplate.opsForValue() } returns valueOperations
        every {
            valueOperations.setIfAbsent(LOCK_KEY, any(), Duration.ofSeconds(30))
        } throws connectionFailure

        val exception = assertThrows<BusinessException> {
            coordinator.execute(STUDENT_ID) {
                actionExecuted = true
            }
        }

        assertThat(exception.errorCode).isEqualTo(ErrorCode.SESSION_STATE_UNAVAILABLE)
        assertThat(exception.cause).isSameAs(connectionFailure)
        assertThat(actionExecuted).isFalse()
    }

    @Test
    @DisplayName("잠금 갱신 작업을 등록할 수 없으면 잠금을 해제하고 503으로 응답한다")
    fun `releases lock when renewal scheduling fails`() {
        var actionExecuted = false
        val schedulingFailure = RejectedExecutionException("renewal executor unavailable")
        every { redisTemplate.opsForValue() } returns valueOperations
        every {
            valueOperations.setIfAbsent(LOCK_KEY, any(), Duration.ofSeconds(30))
        } returns true
        every {
            renewalExecutor.scheduleAtFixedRate(
                any<Runnable>(),
                10_000L,
                10_000L,
                TimeUnit.MILLISECONDS
            )
        } throws schedulingFailure
        every {
            redisTemplate.execute(
                any<RedisScript<Long>>(),
                listOf(LOCK_KEY),
                any()
            )
        } returns 1L

        val exception = assertThrows<BusinessException> {
            coordinator.execute(STUDENT_ID) {
                actionExecuted = true
            }
        }

        assertThat(exception.errorCode).isEqualTo(ErrorCode.SESSION_STATE_UNAVAILABLE)
        assertThat(exception.cause).isSameAs(schedulingFailure)
        assertThat(actionExecuted).isFalse()
        verify(exactly = 1) {
            redisTemplate.execute(
                any<RedisScript<Long>>(),
                listOf(LOCK_KEY),
                any()
            )
        }
    }

    @Test
    @DisplayName("작업이 실패해도 획득한 잠금을 해제한다")
    fun `releases lock when action fails`() {
        every { redisTemplate.opsForValue() } returns valueOperations
        every {
            valueOperations.setIfAbsent(LOCK_KEY, any(), Duration.ofSeconds(30))
        } returns true
        every {
            renewalExecutor.scheduleAtFixedRate(
                any<Runnable>(),
                10_000L,
                10_000L,
                TimeUnit.MILLISECONDS
            )
        } returns renewalFuture
        every {
            redisTemplate.execute(
                any<RedisScript<Long>>(),
                listOf(LOCK_KEY),
                any()
            )
        } returns 1L

        assertThrows<IllegalStateException> {
            coordinator.execute(STUDENT_ID) {
                error("credential operation failed")
            }
        }

        verify(exactly = 1) {
            redisTemplate.execute(
                any<RedisScript<Long>>(),
                listOf(LOCK_KEY),
                any()
            )
        }
    }

    companion object {
        private const val STUDENT_ID = "student-123"
        private const val LOCK_KEY = "credential:session:lock:$STUDENT_ID"
    }
}
