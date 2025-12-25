package com.didimlog.domain

import com.didimlog.domain.enums.ProblemCategory
import java.time.LocalDateTime
import org.springframework.data.annotation.Id
import org.springframework.data.mongodb.core.mapping.Document

/**
 * 문제 풀이 후 작성하는 회고를 표현하는 도메인 객체
 * 대용량 텍스트이므로 별도 컬렉션으로 분리한다.
 */
@Document(collection = "retrospectives")
data class Retrospective(
    @Id
    val id: String? = null,
    val studentId: String, // Student 엔티티의 DB ID (@Id 필드)
    val problemId: String,
    val content: String,
    val summary: String? = null, // 한 줄 요약
    val createdAt: LocalDateTime = LocalDateTime.now(),
    val isBookmarked: Boolean = false,
    val mainCategory: ProblemCategory? = null,
    /**
     * 해당 회고가 성공한 풀이인지 실패한 풀이인지 저장
     * 사용자가 직접 선택한 결과임을 명시한다.
     */
    val solutionResult: com.didimlog.domain.enums.ProblemResult? = null,
    /**
     * 사용자가 직접 선택한 풀이 전략(알고리즘) 태그
     * 예: "BruteForce", "Greedy" 등
     */
    val solvedCategory: String? = null
) {

    init {
        validateContent(content)
    }

    fun updateContent(newContent: String, newSummary: String? = null): Retrospective {
        validateContent(newContent)
        return copy(content = newContent, summary = newSummary)
    }

    fun updateSolutionInfo(
        solutionResult: com.didimlog.domain.enums.ProblemResult?,
        solvedCategory: String?
    ): Retrospective {
        return copy(solutionResult = solutionResult, solvedCategory = solvedCategory)
    }

    /**
     * 즐겨찾기 상태를 토글한다.
     *
     * @return 즐겨찾기 상태가 변경된 새로운 Retrospective 인스턴스
     */
    fun toggleBookmark(): Retrospective {
        return copy(isBookmarked = !isBookmarked)
    }

    /**
     * 회고의 소유자인지 확인한다.
     * 객체 지향적 설계: 데이터를 꺼내지 말고 객체에 메시지를 보낸다.
     *
     * @param student 확인할 학생
     * @return 소유자이면 true, 그렇지 않으면 false
     */
    fun isOwner(student: Student): Boolean {
        val studentId = student.id
        if (studentId == null) {
            return false
        }
        return this.studentId == studentId
    }

    /**
     * 회고의 소유자인지 검증한다.
     * 소유자가 아니면 예외를 발생시킨다.
     *
     * @param student 확인할 학생
     * @throws IllegalArgumentException 소유자가 아닌 경우
     */
    fun validateOwner(student: Student) {
        if (!isOwner(student)) {
            throw IllegalArgumentException("회고 소유자가 아닙니다. studentId=${student.id}")
        }
    }

    private fun validateContent(target: String) {
        require(target.length >= 10) { "회고 내용은 10자 이상이어야 합니다." }
    }
}
