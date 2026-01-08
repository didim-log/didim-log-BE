package com.didimlog.domain.repository

import com.didimlog.domain.CrawlerCheckpoint
import com.didimlog.domain.enums.CrawlType
import org.springframework.data.mongodb.repository.MongoRepository

/**
 * 크롤링 체크포인트 저장소
 */
interface CrawlerCheckpointRepository : MongoRepository<CrawlerCheckpoint, String> {

    /**
     * 크롤링 타입으로 체크포인트를 조회한다.
     *
     * @param crawlType 크롤링 타입
     * @return 체크포인트 (없으면 null)
     */
    fun findByCrawlType(crawlType: CrawlType): CrawlerCheckpoint?

    /**
     * 크롤링 타입으로 체크포인트를 삭제한다.
     *
     * @param crawlType 크롤링 타입
     */
    fun deleteByCrawlType(crawlType: CrawlType)
}

