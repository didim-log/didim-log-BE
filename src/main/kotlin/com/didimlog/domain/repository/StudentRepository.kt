package com.didimlog.domain.repository

import com.didimlog.domain.Student
import com.didimlog.domain.valueobject.BojId
import com.didimlog.domain.valueobject.Nickname
import java.util.Optional
import org.springframework.data.mongodb.repository.MongoRepository

interface StudentRepository : MongoRepository<Student, String> {

    fun findByBojId(bojId: BojId): Optional<Student>

    fun existsByBojId(bojId: BojId): Boolean

    fun existsByNickname(nickname: Nickname): Boolean

    /**
     * Rating(점수) 기준 내림차순으로 상위 100명을 조회한다.
     * 랭킹 조회 성능 최적화를 위해 rating 필드에 인덱스가 설정되어 있다.
     *
     * @return Rating이 높은 순서대로 정렬된 Student 리스트 (최대 100개)
     */
    fun findTop100ByOrderByRatingDesc(): List<Student>
}

