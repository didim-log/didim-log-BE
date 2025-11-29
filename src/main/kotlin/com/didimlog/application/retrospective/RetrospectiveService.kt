package com.didimlog.application.retrospective

import com.didimlog.domain.Problem
import com.didimlog.domain.Retrospective
import com.didimlog.domain.Student
import com.didimlog.domain.repository.ProblemRepository
import com.didimlog.domain.repository.RetrospectiveRepository
import com.didimlog.domain.repository.StudentRepository
import com.didimlog.domain.valueobject.ProblemId
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * 회고 관리 서비스
 * 학생이 문제 풀이 후 작성하는 회고를 관리하고, 템플릿을 생성한다.
 */
@Service
class RetrospectiveService(
    private val retrospectiveRepository: RetrospectiveRepository,
    private val studentRepository: StudentRepository,
    private val problemRepository: ProblemRepository
) {

    /**
     * 회고를 작성하거나 수정한다.
     * 이미 해당 문제에 대한 회고가 있으면 수정하고, 없으면 새로 작성한다.
     *
     * @param studentId 학생 ID
     * @param problemId 문제 ID
     * @param content 회고 내용
     * @return 저장된 회고
     * @throws IllegalArgumentException 학생이나 문제를 찾을 수 없는 경우
     */
    @Transactional
    fun writeRetrospective(studentId: String, problemId: String, content: String): Retrospective {
        validateStudentExists(studentId)
        validateProblemExists(problemId)

        val existingRetrospective = retrospectiveRepository.findByStudentIdAndProblemId(studentId, problemId)

        if (existingRetrospective != null) {
            val updatedRetrospective = existingRetrospective.updateContent(content)
            return retrospectiveRepository.save(updatedRetrospective)
        }

        val newRetrospective = Retrospective(
            studentId = studentId,
            problemId = problemId,
            content = content
        )
        return retrospectiveRepository.save(newRetrospective)
    }

    /**
     * 회고를 조회한다.
     *
     * @param retrospectiveId 회고 ID
     * @return 회고
     * @throws IllegalArgumentException 회고를 찾을 수 없는 경우
     */
    @Transactional(readOnly = true)
    fun getRetrospective(retrospectiveId: String): Retrospective {
        return retrospectiveRepository.findById(retrospectiveId)
            .orElseThrow { IllegalArgumentException("회고를 찾을 수 없습니다. id=$retrospectiveId") }
    }

    /**
     * 문제 정보를 바탕으로 회고 템플릿을 생성한다.
     * 마크다운 형식으로 제목, 문제 링크, 접근 방법, 코드 블록 등의 기본 구조를 제공한다.
     *
     * @param problemId 문제 ID
     * @return 마크다운 형식의 템플릿 문자열
     * @throws IllegalArgumentException 문제를 찾을 수 없는 경우
     */
    @Transactional(readOnly = true)
    fun generateTemplate(problemId: String): String {
        val problem = findProblemOrThrow(problemId)
        return buildTemplate(problem)
    }

    private fun validateStudentExists(studentId: String) {
        if (!studentRepository.existsById(studentId)) {
            throw IllegalArgumentException("학생을 찾을 수 없습니다. id=$studentId")
        }
    }

    private fun validateProblemExists(problemId: String) {
        findProblemOrThrow(problemId)
    }

    private fun findProblemOrThrow(problemId: String): Problem {
        return problemRepository.findById(problemId)
            .orElseThrow { IllegalArgumentException("문제를 찾을 수 없습니다. id=$problemId") }
    }

    private fun buildTemplate(problem: Problem): String {
        val template = StringBuilder()
        template.appendLine("# ${problem.title}")
        template.appendLine()
        template.appendLine("## 문제 정보")
        template.appendLine()
        template.appendLine("- **문제 번호:** ${problem.id.value}")
        template.appendLine("- **난이도:** ${problem.difficulty.name} (Level ${problem.level})")
        template.appendLine("- **카테고리:** ${problem.category}")
        template.appendLine("- **문제 링크:** [${problem.title}](${problem.url})")
        template.appendLine()
        template.appendLine("---")
        template.appendLine()
        template.appendLine("## 접근 방법")
        template.appendLine()
        template.appendLine("<!-- 여기에 문제 해결 접근 방법을 작성하세요 -->")
        template.appendLine()
        template.appendLine("---")
        template.appendLine()
        template.appendLine("## 코드")
        template.appendLine()
        template.appendLine("```kotlin")
        template.appendLine("// 여기에 코드를 작성하세요")
        template.appendLine("```")
        template.appendLine()
        template.appendLine("---")
        template.appendLine()
        template.appendLine("## 회고")
        template.appendLine()
        template.appendLine("<!-- 여기에 문제를 풀면서 느낀 점, 배운 점, 개선할 점 등을 작성하세요 -->")
        template.appendLine()

        return template.toString()
    }
}

