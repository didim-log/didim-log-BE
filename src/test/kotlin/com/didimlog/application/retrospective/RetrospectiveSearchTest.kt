package com.didimlog.application.retrospective

import com.didimlog.domain.Retrospective
import com.didimlog.domain.enums.ProblemCategory
import com.didimlog.domain.repository.RetrospectiveRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.data.mongo.DataMongoTest
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Pageable
import org.springframework.data.domain.Sort
import java.time.LocalDateTime

@DisplayName("Retrospective 검색 통합 테스트")
@DataMongoTest
class RetrospectiveSearchTest {

    @Autowired
    private lateinit var retrospectiveRepository: RetrospectiveRepository

    @BeforeEach
    fun setUp() {
        retrospectiveRepository.deleteAll()
    }

    @Test
    @DisplayName("키워드로 회고를 검색할 수 있다")
    fun `키워드 검색`() {
        // given
        val retrospective1 = Retrospective(
            studentId = "student-1",
            problemId = "1000",
            content = "이 문제는 DFS를 사용해서 풀었습니다.",
            createdAt = LocalDateTime.now()
        )
        val retrospective2 = Retrospective(
            studentId = "student-1",
            problemId = "1001",
            content = "이 문제는 BFS를 사용해서 풀었습니다.",
            createdAt = LocalDateTime.now()
        )
        retrospectiveRepository.saveAll(listOf(retrospective1, retrospective2))

        val condition = RetrospectiveSearchCondition(keyword = "DFS")
        val pageable = PageRequest.of(0, 10)

        // when
        val result = retrospectiveRepository.search(condition, pageable)

        // then
        assertThat(result.content).hasSize(1)
        assertThat(result.content[0].content).contains("DFS")
    }

    @Test
    @DisplayName("카테고리로 회고를 필터링할 수 있다")
    fun `카테고리 필터링`() {
        // given
        val retrospective1 = Retrospective(
            studentId = "student-1",
            problemId = "1000",
            content = "DFS 문제입니다. 깊이 우선 탐색을 사용했습니다.",
            mainCategory = ProblemCategory.DFS,
            createdAt = LocalDateTime.now()
        )
        val retrospective2 = Retrospective(
            studentId = "student-1",
            problemId = "1001",
            content = "DP 문제입니다. 동적 계획법을 사용했습니다.",
            mainCategory = ProblemCategory.DP,
            createdAt = LocalDateTime.now()
        )
        retrospectiveRepository.saveAll(listOf(retrospective1, retrospective2))

        val condition = RetrospectiveSearchCondition(category = ProblemCategory.DFS)
        val pageable = PageRequest.of(0, 10)

        // when
        val result = retrospectiveRepository.search(condition, pageable)

        // then
        assertThat(result.content).hasSize(1)
        assertThat(result.content[0].mainCategory).isEqualTo(ProblemCategory.DFS)
    }

    @Test
    @DisplayName("북마크로 회고를 필터링할 수 있다")
    fun `북마크 필터링`() {
        // given
        val retrospective1 = Retrospective(
            studentId = "student-1",
            problemId = "1000",
            content = "북마크된 회고입니다.",
            isBookmarked = true,
            createdAt = LocalDateTime.now()
        )
        val retrospective2 = Retrospective(
            studentId = "student-1",
            problemId = "1001",
            content = "북마크되지 않은 회고입니다.",
            isBookmarked = false,
            createdAt = LocalDateTime.now()
        )
        retrospectiveRepository.saveAll(listOf(retrospective1, retrospective2))

        val condition = RetrospectiveSearchCondition(isBookmarked = true)
        val pageable = PageRequest.of(0, 10)

        // when
        val result = retrospectiveRepository.search(condition, pageable)

        // then
        assertThat(result.content).hasSize(1)
        assertThat(result.content[0].isBookmarked).isTrue
    }

    @Test
    @DisplayName("학생 ID로 회고를 필터링할 수 있다")
    fun `학생 ID 필터링`() {
        // given
        val retrospective1 = Retrospective(
            studentId = "student-1",
            problemId = "1000",
            content = "학생 1의 회고입니다.",
            createdAt = LocalDateTime.now()
        )
        val retrospective2 = Retrospective(
            studentId = "student-2",
            problemId = "1001",
            content = "학생 2의 회고입니다.",
            createdAt = LocalDateTime.now()
        )
        retrospectiveRepository.saveAll(listOf(retrospective1, retrospective2))

        val condition = RetrospectiveSearchCondition(studentId = "student-1")
        val pageable = PageRequest.of(0, 10)

        // when
        val result = retrospectiveRepository.search(condition, pageable)

        // then
        assertThat(result.content).hasSize(1)
        assertThat(result.content[0].studentId).isEqualTo("student-1")
    }

    @Test
    @DisplayName("페이징이 정상적으로 동작한다")
    fun `페이징 동작`() {
        // given
        val retrospectives = (1..15).map { index ->
            Retrospective(
                studentId = "student-1",
                problemId = "100$index",
                content = "회고 내용 $index 입니다. 이 문제를 풀면서 배운 점이 많았습니다.",
                createdAt = LocalDateTime.now().minusDays(index.toLong())
            )
        }
        retrospectiveRepository.saveAll(retrospectives)

        val condition = RetrospectiveSearchCondition(studentId = "student-1")
        val pageable = PageRequest.of(0, 10)

        // when
        val result = retrospectiveRepository.search(condition, pageable)

        // then
        assertThat(result.content).hasSize(10)
        assertThat(result.totalElements).isEqualTo(15)
        assertThat(result.totalPages).isEqualTo(2)
        assertThat(result.hasNext()).isTrue
    }

    @Test
    @DisplayName("정렬이 정상적으로 동작한다")
    fun `정렬 동작`() {
        // given
        val now = LocalDateTime.now()
        val retrospective1 = Retrospective(
            studentId = "student-1",
            problemId = "1000",
            content = "오래된 회고입니다. 이전에 작성한 내용입니다.",
            createdAt = now.minusDays(2)
        )
        val retrospective2 = Retrospective(
            studentId = "student-1",
            problemId = "1001",
            content = "최신 회고입니다. 방금 작성한 내용입니다.",
            createdAt = now
        )
        retrospectiveRepository.saveAll(listOf(retrospective1, retrospective2))

        val condition = RetrospectiveSearchCondition(studentId = "student-1")
        val pageable = PageRequest.of(0, 10, Sort.by(Sort.Direction.DESC, "createdAt"))

        // when
        val result = retrospectiveRepository.search(condition, pageable)

        // then
        assertThat(result.content).hasSize(2)
        assertThat(result.content[0].content).isEqualTo("최신 회고입니다. 방금 작성한 내용입니다.")
        assertThat(result.content[1].content).isEqualTo("오래된 회고입니다. 이전에 작성한 내용입니다.")
    }
}

