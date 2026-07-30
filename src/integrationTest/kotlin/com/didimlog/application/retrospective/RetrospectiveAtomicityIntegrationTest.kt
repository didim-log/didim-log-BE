package com.didimlog.application.retrospective

import com.didimlog.application.study.StudyService
import com.didimlog.domain.Problem
import com.didimlog.domain.Retrospective
import com.didimlog.domain.Solution
import com.didimlog.domain.Solutions
import com.didimlog.domain.Student
import com.didimlog.domain.enums.ProblemCategory
import com.didimlog.domain.enums.ProblemResult
import com.didimlog.domain.enums.Provider
import com.didimlog.domain.enums.Role
import com.didimlog.domain.enums.Tier
import com.didimlog.domain.repository.ProblemRepository
import com.didimlog.domain.repository.RetrospectiveRepository
import com.didimlog.domain.repository.StudentRepository
import com.didimlog.domain.valueobject.BojId
import com.didimlog.domain.valueobject.Nickname
import com.didimlog.domain.valueobject.ProblemId
import com.didimlog.domain.valueobject.TimeTakenSeconds
import com.didimlog.global.exception.BusinessException
import com.didimlog.global.exception.ErrorCode
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.Optional
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import org.bson.Document
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.dao.OptimisticLockingFailureException
import org.springframework.data.domain.PageRequest
import org.springframework.data.mongodb.core.MongoTemplate

@SpringBootTest
@DisplayName("회고 원자 갱신 통합 테스트")
class RetrospectiveAtomicityIntegrationTest {

    @Autowired
    private lateinit var retrospectiveRepository: RetrospectiveRepository

    @Autowired
    private lateinit var studentRepository: StudentRepository

    @Autowired
    private lateinit var problemRepository: ProblemRepository

    @Autowired
    private lateinit var mongoTemplate: MongoTemplate

    private lateinit var student: Student
    private lateinit var problem: Problem
    private lateinit var retrospective: Retrospective
    private lateinit var testId: String
    private val additionalProblemIds = mutableSetOf<String>()

    @BeforeEach
    fun setUp() {
        additionalProblemIds.clear()
        testId = UUID.randomUUID().toString().substring(0, 8)
        student = studentRepository.save(
            Student(
                nickname = Nickname("rt$testId"),
                provider = Provider.BOJ,
                providerId = "retro$testId",
                bojId = BojId("retro$testId"),
                password = "password",
                currentTier = Tier.BRONZE,
                role = Role.USER
            )
        )
        problem = problemRepository.save(
            Problem(
                id = ProblemId("retro-$testId"),
                title = "Retrospective Problem",
                category = ProblemCategory.IMPLEMENTATION,
                difficulty = Tier.BRONZE,
                level = 3,
                url = "https://www.acmicpc.net/problem/retro-$testId"
            )
        )
        retrospective = retrospectiveRepository.save(
            Retrospective(
                studentId = requireNotNull(student.id),
                problemId = problem.id.value,
                content = "동시 북마크 토글을 검증하는 회고입니다.",
                summary = "동시 토글",
                isBookmarked = false
            )
        )
    }

    @AfterEach
    fun tearDown() {
        retrospectiveRepository.deleteAll()
        studentRepository.deleteById(requireNotNull(student.id))
        problemRepository.deleteAllById(additionalProblemIds)
        problemRepository.deleteById(problem.id.value)
    }

    @Test
    fun `동시 북마크 토글 두 번은 원래 상태로 돌아온다`() {
        val retrospectiveId = requireNotNull(retrospective.id)
        val studentId = requireNotNull(student.id)
        val readBarrier = CyclicBarrier(2)
        val barrierRepository = FirstReadBarrierRetrospectiveRepository(
            delegate = retrospectiveRepository,
            targetRetrospectiveId = retrospectiveId,
            readBarrier = readBarrier
        )
        val service = RetrospectiveService(
            barrierRepository,
            studentRepository,
            problemRepository
        )
        val executor = Executors.newFixedThreadPool(2)

        val results = try {
            (1..2).map {
                executor.submit<Boolean> {
                    service.toggleBookmark(retrospectiveId, studentId)
                }
            }.map { future -> future.get(10, TimeUnit.SECONDS) }
        } finally {
            executor.shutdownNow()
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue()
        }

        assertThat(results).containsExactlyInAnyOrder(true, false)
        val persisted = retrospectiveRepository.findById(retrospectiveId).orElseThrow()
        assertThat(persisted.isBookmarked).isFalse()
    }

    @Test
    fun `동시 첫 작성은 회고 한 건과 문제 카테고리로 수렴한다`() {
        retrospectiveRepository.deleteById(requireNotNull(retrospective.id))
        val studentId = requireNotNull(student.id)
        val pairReadBarrier = CyclicBarrier(2)
        val barrierRepository = FirstPairReadBarrierRetrospectiveRepository(
            delegate = retrospectiveRepository,
            targetStudentId = studentId,
            targetProblemId = problem.id.value,
            readBarrier = pairReadBarrier
        )
        val service = RetrospectiveService(
            barrierRepository,
            studentRepository,
            problemRepository
        )
        val executor = Executors.newFixedThreadPool(2)

        val failures = try {
            listOf("첫 번째 동시 회고 내용은 충분히 깁니다.", "두 번째 동시 회고 내용도 충분히 깁니다.").mapIndexed { index, content ->
                executor.submit<Throwable?> {
                    runCatching {
                        service.writeRetrospective(
                            studentId = studentId,
                            problemId = problem.id.value,
                            content = content,
                            summary = "동시 회고 ${index + 1}",
                            solutionResult = ProblemResult.SUCCESS,
                            solvedCategory = "Implementation",
                            solveTime = "10m"
                        )
                    }.exceptionOrNull()
                }
            }.map { future -> future.get(10, TimeUnit.SECONDS) }
        } finally {
            executor.shutdownNow()
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue()
        }

        assertThat(failures).containsOnlyNulls()
        val persisted = retrospectiveRepository.findAllByStudentId(studentId)
        assertThat(persisted).hasSize(1)
        val created = persisted.single()
        assertThat(created.mainCategory).isEqualTo(ProblemCategory.IMPLEMENTATION)
        assertThat(created.content to created.summary).isIn(
            "첫 번째 동시 회고 내용은 충분히 깁니다." to "동시 회고 1",
            "두 번째 동시 회고 내용도 충분히 깁니다." to "동시 회고 2"
        )
        val raw = mongoTemplate.getCollection("retrospectives")
            .find(
                Document("studentId", studentId)
                    .append("problemId", problem.id.value)
            )
            .first()
        assertThat(raw?.getString("mainCategory"))
            .isEqualTo(ProblemCategory.IMPLEMENTATION.englishName)

        val actualService = RetrospectiveService(
            retrospectiveRepository,
            studentRepository,
            problemRepository
        )
        assertThat(
            actualService.toggleBookmark(requireNotNull(created.id), studentId)
        ).isTrue()
        val rewritten = actualService.writeRetrospective(
            studentId = studentId,
            problemId = problem.id.value,
            content = "기존 회고를 부분 갱신한 세 번째 내용입니다.",
            summary = "세 번째 회고",
            solutionResult = ProblemResult.FAIL,
            solvedCategory = "Greedy",
            solveTime = "30m"
        )
        assertThat(rewritten.id).isEqualTo(created.id)
        assertThat(rewritten.createdAt).isEqualTo(created.createdAt)
        assertThat(rewritten.isBookmarked).isTrue()
        assertThat(rewritten.mainCategory).isEqualTo(ProblemCategory.IMPLEMENTATION)

        val searchResult = retrospectiveRepository.search(
            RetrospectiveSearchCondition(
                studentId = studentId,
                category = ProblemCategory.IMPLEMENTATION
            ),
            PageRequest.of(0, 10)
        )
        assertThat(searchResult.content).containsExactly(rewritten)
    }

    @Test
    fun `회고 수정과 북마크 토글은 서로의 필드를 보존한다`() {
        val retrospectiveId = requireNotNull(retrospective.id)
        val studentId = requireNotNull(student.id)
        val original = retrospectiveRepository.findById(retrospectiveId).orElseThrow()
        val readBarrier = CyclicBarrier(2)
        val barrierRepository = FirstReadBarrierRetrospectiveRepository(
            delegate = retrospectiveRepository,
            targetRetrospectiveId = retrospectiveId,
            readBarrier = readBarrier
        )
        val service = RetrospectiveService(
            barrierRepository,
            studentRepository,
            problemRepository
        )
        val executor = Executors.newFixedThreadPool(2)

        val failures = try {
            listOf(
                executor.submit<Throwable?> {
                    runCatching {
                        service.updateRetrospective(
                            retrospectiveId = retrospectiveId,
                            studentId = studentId,
                            content = "북마크와 동시에 수정한 새 회고 내용입니다.",
                            summary = "동시 수정",
                            solutionResult = ProblemResult.FAIL,
                            solvedCategory = "Greedy",
                            solveTime = "20m"
                        )
                    }.exceptionOrNull()
                },
                executor.submit<Throwable?> {
                    runCatching {
                        service.toggleBookmark(retrospectiveId, studentId)
                    }.exceptionOrNull()
                }
            ).map { future -> future.get(10, TimeUnit.SECONDS) }
        } finally {
            executor.shutdownNow()
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue()
        }

        assertThat(failures).containsOnlyNulls()
        val persisted = retrospectiveRepository.findById(retrospectiveId).orElseThrow()
        assertThat(persisted.content).isEqualTo("북마크와 동시에 수정한 새 회고 내용입니다.")
        assertThat(persisted.summary).isEqualTo("동시 수정")
        assertThat(persisted.id).isEqualTo(original.id)
        assertThat(persisted.studentId).isEqualTo(original.studentId)
        assertThat(persisted.problemId).isEqualTo(original.problemId)
        assertThat(persisted.createdAt).isEqualTo(original.createdAt)
        assertThat(persisted.solutionResult).isEqualTo(ProblemResult.FAIL)
        assertThat(persisted.solvedCategory).isEqualTo("Greedy")
        assertThat(persisted.solveTime).isEqualTo("20m")
        assertThat(persisted.mainCategory).isEqualTo(ProblemCategory.IMPLEMENTATION)
        assertThat(persisted.isBookmarked).isTrue()
    }

    @Test
    fun `선행 삭제 뒤 늦은 수정은 회고를 되살리지 않는다`() {
        val retrospectiveId = requireNotNull(retrospective.id)
        val studentId = requireNotNull(student.id)
        val firstRead = CountDownLatch(1)
        val continueUpdate = CountDownLatch(1)
        val pausingRepository = PausingReadRetrospectiveRepository(
            delegate = retrospectiveRepository,
            targetRetrospectiveId = retrospectiveId,
            firstRead = firstRead,
            continueRead = continueUpdate
        )
        val delayedUpdateService = RetrospectiveService(
            pausingRepository,
            studentRepository,
            problemRepository
        )
        val deleteService = RetrospectiveService(
            retrospectiveRepository,
            studentRepository,
            problemRepository
        )
        val executor = Executors.newSingleThreadExecutor()

        val updateFuture = executor.submit<Throwable?> {
            runCatching {
                delayedUpdateService.updateRetrospective(
                    retrospectiveId = retrospectiveId,
                    studentId = studentId,
                    content = "삭제 뒤에는 저장되면 안 되는 늦은 수정입니다.",
                    summary = "늦은 수정",
                    solutionResult = null,
                    solvedCategory = null,
                    solveTime = null
                )
            }.exceptionOrNull()
        }

        assertThat(firstRead.await(10, TimeUnit.SECONDS)).isTrue()
        deleteService.deleteRetrospective(retrospectiveId, studentId)
        continueUpdate.countDown()
        val updateFailure = try {
            updateFuture.get(10, TimeUnit.SECONDS)
        } finally {
            continueUpdate.countDown()
            executor.shutdownNow()
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue()
        }

        assertThat(updateFailure).isInstanceOf(BusinessException::class.java)
        assertThat((updateFailure as BusinessException).errorCode)
            .isEqualTo(ErrorCode.RESOURCE_STATE_CONFLICT)
        assertThat(retrospectiveRepository.findById(retrospectiveId)).isEmpty
        assertThat(
            retrospectiveRepository.findByStudentIdAndProblemId(studentId, problem.id.value)
        ).isNull()
    }

    @Test
    fun `회고 삭제는 대상 문제 풀이만 제거하고 학생의 다른 필드를 보존한다`() {
        val studentId = requireNotNull(student.id)
        val otherProblemId = ProblemId("other-$testId")
        val latestSolvedAt = LocalDateTime.now()
        val remainingSolvedAt = latestSolvedAt.minusDays(3)
        val solutions = Solutions().apply {
            add(
                Solution(
                    problemId = problem.id,
                    timeTaken = TimeTakenSeconds(100),
                    result = ProblemResult.SUCCESS,
                    solvedAt = latestSolvedAt.minusDays(1)
                )
            )
            add(
                Solution(
                    problemId = problem.id,
                    timeTaken = TimeTakenSeconds(120),
                    result = ProblemResult.FAIL,
                    solvedAt = latestSolvedAt
                )
            )
            add(
                Solution(
                    problemId = otherProblemId,
                    timeTaken = TimeTakenSeconds(80),
                    result = ProblemResult.SUCCESS,
                    solvedAt = remainingSolvedAt
                )
            )
        }
        student = studentRepository.save(
            student.copy(
                solutions = solutions,
                credentialVersion = 4,
                password = "preserved-password",
                rating = 1234,
                consecutiveSolveDays = 2,
                lastSolvedAt = latestSolvedAt.toLocalDate()
            )
        )
        val staleStudent = student

        RetrospectiveService(
            retrospectiveRepository,
            studentRepository,
            problemRepository
        ).deleteRetrospective(requireNotNull(retrospective.id), studentId)

        val persisted = studentRepository.findById(studentId).orElseThrow()
        assertThat(persisted.getSolvedProblemIds()).containsExactly(otherProblemId)
        assertThat(persisted.nickname).isEqualTo(staleStudent.nickname)
        assertThat(persisted.password).isEqualTo("preserved-password")
        assertThat(persisted.credentialVersion).isEqualTo(4)
        assertThat(persisted.rating).isEqualTo(1234)
        assertThat(persisted.lastSolvedAt).isEqualTo(remainingSolvedAt.toLocalDate())
        assertThat(persisted.consecutiveSolveDays).isEqualTo(1)
        assertThat(persisted.documentVersion)
            .isEqualTo(requireNotNull(staleStudent.documentVersion) + 1)
        assertThat(retrospectiveRepository.findById(requireNotNull(retrospective.id))).isEmpty
        assertThatThrownBy {
            studentRepository.save(staleStudent.copy(nickname = Nickname("stale-user")))
        }.isInstanceOf(OptimisticLockingFailureException::class.java)
    }

    @Test
    fun `유일한 풀이를 삭제하면 마지막 풀이일과 연속 일수를 초기화한다`() {
        val studentId = requireNotNull(student.id)
        val solvedAt = LocalDateTime.now()
        val solutions = Solutions().apply {
            add(
                Solution(
                    problemId = problem.id,
                    timeTaken = TimeTakenSeconds(100),
                    result = ProblemResult.SUCCESS,
                    solvedAt = solvedAt
                )
            )
        }
        student = studentRepository.save(
            student.copy(
                solutions = solutions,
                consecutiveSolveDays = 4,
                lastSolvedAt = solvedAt.toLocalDate()
            )
        )

        RetrospectiveService(
            retrospectiveRepository,
            studentRepository,
            problemRepository
        ).deleteRetrospective(requireNotNull(retrospective.id), studentId)

        val persisted = studentRepository.findById(studentId).orElseThrow()
        assertThat(persisted.solutions.getAll()).isEmpty()
        assertThat(persisted.lastSolvedAt).isNull()
        assertThat(persisted.consecutiveSolveDays).isZero()
    }

    @Test
    fun `풀이 저장과 삭제 CAS가 충돌하면 최신 풀이를 보존해 재시도한다`() {
        val studentId = requireNotNull(student.id)
        val targetSolvedAt = LocalDateTime.now().minusDays(1)
        student = studentRepository.save(
            student.copy(
                solutions = Solutions().apply {
                    add(
                        Solution(
                            problemId = problem.id,
                            timeTaken = TimeTakenSeconds(100),
                            result = ProblemResult.SUCCESS,
                            solvedAt = targetSolvedAt
                        )
                    )
                },
                consecutiveSolveDays = 1,
                lastSolvedAt = targetSolvedAt.toLocalDate()
            )
        )
        val initialDocumentVersion = requireNotNull(student.documentVersion)
        val concurrentProblem = problemRepository.save(
            Problem(
                id = ProblemId("concurrent-$testId"),
                title = "Concurrent Study Problem",
                category = ProblemCategory.DP,
                difficulty = Tier.BRONZE,
                level = 4,
                url = "https://www.acmicpc.net/problem/concurrent-$testId"
            )
        )
        additionalProblemIds += concurrentProblem.id.value
        val firstDeleteUpdate = CountDownLatch(1)
        val continueDelete = CountDownLatch(1)
        val pausingStudentRepository = PausingStudyUpdateStudentRepository(
            delegate = studentRepository,
            targetStudentId = studentId,
            firstUpdate = firstDeleteUpdate,
            continueUpdate = continueDelete
        )
        val deleteService = RetrospectiveService(
            retrospectiveRepository,
            pausingStudentRepository,
            problemRepository
        )
        val executor = Executors.newSingleThreadExecutor()
        val deleteFuture = executor.submit<Throwable?> {
            runCatching {
                deleteService.deleteRetrospective(requireNotNull(retrospective.id), studentId)
            }.exceptionOrNull()
        }

        val deleteFailure = try {
            assertThat(firstDeleteUpdate.await(10, TimeUnit.SECONDS)).isTrue()
            StudyService(studentRepository, problemRepository).submitSolution(
                studentId = studentId,
                problemId = concurrentProblem.id.value,
                timeTaken = 90,
                isSuccess = true
            )
            continueDelete.countDown()
            deleteFuture.get(10, TimeUnit.SECONDS)
        } finally {
            continueDelete.countDown()
            executor.shutdownNow()
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue()
        }

        assertThat(deleteFailure).isNull()
        val persisted = studentRepository.findById(studentId).orElseThrow()
        assertThat(persisted.getSolvedProblemIds()).containsExactly(concurrentProblem.id)
        assertThat(persisted.documentVersion).isEqualTo(initialDocumentVersion + 2)
        assertThat(persisted.consecutiveSolveDays).isEqualTo(1)
        assertThat(retrospectiveRepository.findById(requireNotNull(retrospective.id))).isEmpty
    }

    private class FirstReadBarrierRetrospectiveRepository(
        private val delegate: RetrospectiveRepository,
        private val targetRetrospectiveId: String,
        private val readBarrier: CyclicBarrier
    ) : RetrospectiveRepository by delegate {
        private val readCount = AtomicInteger()

        override fun findById(id: String): Optional<Retrospective> {
            val retrospective = delegate.findById(id)
            if (id == targetRetrospectiveId && readCount.incrementAndGet() <= 2) {
                readBarrier.await(10, TimeUnit.SECONDS)
            }
            return retrospective
        }
    }

    private class FirstPairReadBarrierRetrospectiveRepository(
        private val delegate: RetrospectiveRepository,
        private val targetStudentId: String,
        private val targetProblemId: String,
        private val readBarrier: CyclicBarrier
    ) : RetrospectiveRepository by delegate {
        private val readCount = AtomicInteger()

        override fun findByStudentIdAndProblemId(
            studentId: String,
            problemId: String
        ): Retrospective? {
            val retrospective = delegate.findByStudentIdAndProblemId(studentId, problemId)
            if (
                studentId == targetStudentId &&
                problemId == targetProblemId &&
                readCount.incrementAndGet() <= 2
            ) {
                readBarrier.await(10, TimeUnit.SECONDS)
            }
            return retrospective
        }
    }

    private class PausingReadRetrospectiveRepository(
        private val delegate: RetrospectiveRepository,
        private val targetRetrospectiveId: String,
        private val firstRead: CountDownLatch,
        private val continueRead: CountDownLatch
    ) : RetrospectiveRepository by delegate {
        private val paused = AtomicBoolean()

        override fun findById(id: String): Optional<Retrospective> {
            val retrospective = delegate.findById(id)
            if (id == targetRetrospectiveId && paused.compareAndSet(false, true)) {
                firstRead.countDown()
                check(continueRead.await(10, TimeUnit.SECONDS))
            }
            return retrospective
        }
    }

    private class PausingStudyUpdateStudentRepository(
        private val delegate: StudentRepository,
        private val targetStudentId: String,
        private val firstUpdate: CountDownLatch,
        private val continueUpdate: CountDownLatch
    ) : StudentRepository by delegate {
        private val paused = AtomicBoolean()

        override fun updateStudyProgressById(
            studentId: String,
            expectedDocumentVersion: Long,
            solutions: Solutions,
            consecutiveSolveDays: Int,
            lastSolvedAt: LocalDate?
        ): Student? {
            if (studentId == targetStudentId && paused.compareAndSet(false, true)) {
                firstUpdate.countDown()
                check(continueUpdate.await(10, TimeUnit.SECONDS))
            }
            return delegate.updateStudyProgressById(
                studentId,
                expectedDocumentVersion,
                solutions,
                consecutiveSolveDays,
                lastSolvedAt
            )
        }
    }
}
