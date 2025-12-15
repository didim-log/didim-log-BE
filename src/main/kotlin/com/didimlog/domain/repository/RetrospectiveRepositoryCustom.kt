package com.didimlog.domain.repository

import com.didimlog.application.retrospective.RetrospectiveSearchCondition
import com.didimlog.domain.Retrospective
import com.didimlog.domain.enums.RankingPeriod
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable

/**
 * 회고 Repository 커스텀 인터페이스
 * 동적 쿼리 메서드를 정의한다.
 */
interface RetrospectiveRepositoryCustom {

    /**
     * 기간별 회고 작성 수를 학생 단위로 집계하여 내림차순 정렬한 랭킹을 조회한다.
     *
     * - period에 따라 createdAt 기준으로 기간 필터링을 적용한다.
     * - 정렬: retrospectiveCount DESC, student.rating DESC, studentId ASC
     * - 페이징(Pageable)을 지원한다.
     */
    fun findTopStudentsByRetrospectiveCount(
        period: RankingPeriod,
        pageable: Pageable
    ): Page<StudentRetrospectiveCount>

    /**
     * 검색 조건에 따라 회고를 검색한다.
     *
     * @param condition 검색 조건
     * @param pageable 페이징 정보
     * @return 검색 결과 페이지
     */
    fun search(condition: RetrospectiveSearchCondition, pageable: Pageable): Page<Retrospective>
}

/**
 * 학생별 회고 작성 수 집계 결과
 */
data class StudentRetrospectiveCount(
    val studentId: String,
    val retrospectiveCount: Long
)

