package com.didimlog.domain.repository

import com.didimlog.domain.CrawlerCheckpoint
import com.didimlog.domain.enums.CrawlType
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.data.mongo.DataMongoTest
import java.time.LocalDateTime

@DataMongoTest
@DisplayName("CrawlerCheckpointRepository 테스트")
class CrawlerCheckpointRepositoryTest(
    @Autowired private val crawlerCheckpointRepository: CrawlerCheckpointRepository
) {

    @Test
    @DisplayName("크롤링 타입으로 checkpoint를 조회한다")
    fun `크롤링 타입으로 checkpoint 조회`() {
        // given
        val checkpoint = CrawlerCheckpoint(
            crawlType = CrawlType.METADATA_COLLECT,
            lastCrawledId = "1000",
            updatedAt = LocalDateTime.now()
        )
        crawlerCheckpointRepository.save(checkpoint)

        // when
        val found = crawlerCheckpointRepository.findByCrawlType(CrawlType.METADATA_COLLECT)

        // then
        assertThat(found).isNotNull()
        assertThat(found?.crawlType).isEqualTo(CrawlType.METADATA_COLLECT)
        assertThat(found?.lastCrawledId).isEqualTo("1000")
    }

    @Test
    @DisplayName("크롤링 타입으로 checkpoint를 삭제한다")
    fun `크롤링 타입으로 checkpoint 삭제`() {
        // given
        val checkpoint = CrawlerCheckpoint(
            crawlType = CrawlType.DETAILS_COLLECT,
            lastCrawledId = "2000",
            updatedAt = LocalDateTime.now()
        )
        crawlerCheckpointRepository.save(checkpoint)

        // when
        crawlerCheckpointRepository.deleteByCrawlType(CrawlType.DETAILS_COLLECT)

        // then
        val found = crawlerCheckpointRepository.findByCrawlType(CrawlType.DETAILS_COLLECT)
        assertThat(found).isNull()
    }

    @Test
    @DisplayName("checkpoint가 없으면 null을 반환한다")
    fun `checkpoint 없으면 null 반환`() {
        // when
        val found = crawlerCheckpointRepository.findByCrawlType(CrawlType.LANGUAGE_UPDATE)

        // then
        assertThat(found).isNull()
    }
}

