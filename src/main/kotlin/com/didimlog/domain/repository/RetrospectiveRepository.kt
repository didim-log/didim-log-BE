package com.didimlog.domain.repository

import com.didimlog.domain.Retrospective
import org.springframework.data.mongodb.repository.MongoRepository
import java.time.LocalDateTime

interface RetrospectiveRepository : MongoRepository<Retrospective, String>, RetrospectiveRepositoryCustom {

    fun findAllByStudentId(studentId: String): List<Retrospective>

    fun findByStudentIdAndProblemId(studentId: String, problemId: String): Retrospective?

    fun deleteAllByStudentId(studentId: String)

    /**
     * 학생의 총 회고 수를 반환한다.
     * DB 쿼리에서 직접 COUNT를 수행하여 성능을 최적화한다.
     *
     * @param studentId 학생 ID
     * @return 총 회고 수
     */
    fun countByStudentId(studentId: String): Long

    fun findByStudentIdAndCreatedAtBetween(
        studentId: String,
        startDate: LocalDateTime,
        endDate: LocalDateTime
    ): List<Retrospective>

    /**
     * 특정 날짜 이전에 생성된 회고를 삭제한다.
     *
     * @param dateTime 기준 날짜/시간
     */
    fun deleteByCreatedAtBefore(dateTime: LocalDateTime)

    /**
     * 특정 날짜 이전에 생성된 회고의 개수를 조회한다.
     *
     * @param dateTime 기준 날짜/시간
     * @return 회고 개수
     */
    fun countByCreatedAtBefore(dateTime: LocalDateTime): Long
}
