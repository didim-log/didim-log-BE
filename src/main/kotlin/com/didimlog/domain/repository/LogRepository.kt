package com.didimlog.domain.repository

import com.didimlog.domain.Log
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.mongodb.repository.MongoRepository
import org.springframework.data.mongodb.repository.Query
import java.time.LocalDateTime

interface LogRepository : MongoRepository<Log, String> {
    /**
     * BOJ ID로 로그를 페이징하여 조회한다.
     * bojId는 value object이므로 MongoDB 필드 경로를 명시해야 함
     *
     * @param bojIdValue BOJ ID 값
     * @param pageable 페이징 정보
     * @return 로그 페이지
     */
    @Query("{ 'bojId.value': ?0 }")
    fun findByBojIdValue(bojIdValue: String, pageable: Pageable): Page<Log>

    /**
     * 특정 날짜 이전에 생성된 로그를 삭제한다.
     *
     * @param dateTime 기준 날짜/시간
     */
    fun deleteByCreatedAtBefore(dateTime: LocalDateTime)

    /**
     * 특정 날짜 이전에 생성된 로그의 개수를 조회한다.
     *
     * @param dateTime 기준 날짜/시간
     * @return 로그 개수
     */
    @Query("{ 'createdAt': { \$lt: ?0 } }")
    fun countByCreatedAtBefore(dateTime: LocalDateTime): Long
}


