package com.didimlog.domain.enums

/**
 * 크롤링 작업 타입
 */
enum class CrawlType {
    /**
     * 메타데이터 수집 (Solved.ac API)
     */
    METADATA_COLLECT,

    /**
     * 상세 정보 수집 (BOJ 크롤링)
     */
    DETAILS_COLLECT,

    /**
     * 언어 정보 업데이트 (BOJ 크롤링)
     */
    LANGUAGE_UPDATE
}

