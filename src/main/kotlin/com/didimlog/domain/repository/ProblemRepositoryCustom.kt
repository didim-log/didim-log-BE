package com.didimlog.domain.repository

import com.didimlog.domain.Problem

/**
 * Problem Repository 커스텀 인터페이스
 * 확장된 태그 리스트로 검색하는 메서드를 정의한다.
 */
interface ProblemRepositoryCustom {

    /**
     * solved.ac에서 받은 문제 메타데이터만 갱신한다.
     *
     * 기존 상세 정보, URL과 언어는 보존하고 문서가 없을 때만 기본값을 함께 생성한다.
     *
     * @param problem 갱신할 문제 메타데이터
     */
    fun upsertMetadata(problem: Problem)

    /**
     * 크롤링한 현재 상세 필드와 선택적 언어만 갱신한다.
     *
     * 문서가 삭제됐으면 새로 만들지 않고 null을 반환한다.
     * 상세 필드의 null과 빈 목록은 그대로 저장하며, 언어가 null이면 기존 값을 보존한다.
     *
     * @param problemId 갱신할 문제 ID
     * @param details 갱신할 상세 필드
     * @return 갱신 뒤 문제 또는 대상이 없으면 null
     */
    fun updateDetails(problemId: String, details: ProblemDetailsUpdate): Problem?

    /**
     * 문제 언어만 갱신한다.
     *
     * 문서가 삭제됐으면 새로 만들지 않는다.
     *
     * @param problemId 갱신할 문제 ID
     * @param language 새 언어
     * @return 대상 문서가 있었으면 true
     */
    fun updateLanguage(problemId: String, language: String): Boolean

    /**
     * 레벨 범위로 문제를 검색한다.
     *
     * - 최신 스키마: level 필드 사용
     * - 레거시 스키마 대응: difficultyLevel 필드가 존재하는 경우도 함께 검색
     *
     * @param min 최소 레벨
     * @param max 최대 레벨
     * @return 검색된 문제 목록
     */
    fun findByLevelBetweenFlexible(min: Int, max: Int): List<Problem>

    /**
     * 추천 난이도 범위에서 대표 카테고리 또는 확장 태그가 일치하는 후보를 한 번에 조회한다.
     *
     * level이 없는 이전 문서는 difficultyLevel을 조회 결과의 level로 사용한다.
     *
     * @param min 최소 레벨
     * @param max 최대 레벨
     * @param targetCategories 대표 카테고리 목록
     * @param expandedTags 확장 태그 목록
     * @return 추천 후보 문제 목록
     */
    fun findRecommendationCandidates(
        min: Int,
        max: Int,
        targetCategories: List<String>,
        expandedTags: List<String>
    ): List<Problem>
}

data class ProblemDetailsUpdate(
    val descriptionHtml: String?,
    val inputDescriptionHtml: String?,
    val outputDescriptionHtml: String?,
    val sampleInputs: List<String>?,
    val sampleOutputs: List<String>?,
    val language: String? = null
)
