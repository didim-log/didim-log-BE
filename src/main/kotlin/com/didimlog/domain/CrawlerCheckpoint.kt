package com.didimlog.domain

import com.didimlog.domain.enums.CrawlType
import org.springframework.data.annotation.Id
import org.springframework.data.mongodb.core.mapping.Document
import java.time.LocalDateTime

/**
 * 크롤링 작업의 중단 지점을 저장하는 체크포인트
 * 크롤링이 중단되면 마지막으로 처리한 ID를 저장하고, 재시작 시 해당 지점부터 이어서 진행한다.
 *
 * @property id 체크포인트 ID (MongoDB ObjectId)
 * @property crawlType 크롤링 작업 타입
 * @property lastCrawledId 마지막으로 처리한 문제 ID (메타데이터 수집의 경우) 또는 문제 ID 문자열 (상세 정보/언어 업데이트의 경우)
 * @property updatedAt 마지막 업데이트 시간
 */
@Document(collection = "crawler_checkpoints")
data class CrawlerCheckpoint(
    @Id
    val id: String? = null,
    val crawlType: CrawlType,
    val lastCrawledId: String,
    val updatedAt: LocalDateTime = LocalDateTime.now()
) {
    init {
        require(lastCrawledId.isNotBlank()) { "lastCrawledId는 비어있을 수 없습니다." }
    }
}

