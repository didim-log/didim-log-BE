package com.didimlog.application.admin

/**
 * 로그 정리 모드
 */
enum class LogCleanupMode {
    /**
     * 기준 일수보다 오래된 로그를 삭제한다.
     */
    OLDER_THAN_DAYS,

    /**
     * 최근 N일 로그를 남기고 이전 로그를 삭제한다.
     */
    KEEP_RECENT_DAYS
}
