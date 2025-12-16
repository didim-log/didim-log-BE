package com.didimlog.domain

import com.didimlog.domain.enums.ProblemCategory
import com.didimlog.domain.enums.Tier
import com.didimlog.domain.valueobject.ProblemId
import org.springframework.data.annotation.Id
import org.springframework.data.mongodb.core.mapping.Document

/**
 * 알고리즘 문제 정보를 표현하는 도메인 객체
 * 불변 객체로 설계하여 생성 시점 이후 상태가 변경되지 않는다.
 *
 * @property level Solved.ac 기준 난이도 레벨 (1~30)
 * @property category 문제 카테고리 (영문 표준명으로 저장)
 * @property description 문제 본문 HTML (크롤링으로 수집, nullable) - deprecated: descriptionHtml 사용 권장
 * @property inputDescription 입력 설명 HTML (크롤링으로 수집, nullable) - deprecated: inputDescriptionHtml 사용 권장
 * @property outputDescription 출력 설명 HTML (크롤링으로 수집, nullable) - deprecated: outputDescriptionHtml 사용 권장
 * @property examples 입출력 예시 리스트 (크롤링으로 수집, nullable) - deprecated: sampleInputs, sampleOutputs 사용 권장
 * @property descriptionHtml 문제 본문 HTML (크롤링으로 수집, nullable)
 * @property inputDescriptionHtml 입력 설명 HTML (크롤링으로 수집, nullable)
 * @property outputDescriptionHtml 출력 설명 HTML (크롤링으로 수집, nullable)
 * @property sampleInputs 샘플 입력 리스트 (크롤링으로 수집, nullable)
 * @property sampleOutputs 샘플 출력 리스트 (크롤링으로 수집, nullable)
 * @property tags 알고리즘 분류 태그 리스트 (영문 표준명으로 저장)
 */
@Document(collection = "problems")
data class Problem(
    @Id
    val id: ProblemId,
    val title: String,
    val category: ProblemCategory,
    val difficulty: Tier,
    val level: Int,
    val url: String,
    val description: String? = null,
    val inputDescription: String? = null,
    val outputDescription: String? = null,
    val examples: List<Example>? = null,
    val descriptionHtml: String? = null,
    val inputDescriptionHtml: String? = null,
    val outputDescriptionHtml: String? = null,
    val sampleInputs: List<String>? = null,
    val sampleOutputs: List<String>? = null,
    val tags: List<String> = emptyList()
) {
    init {
        require(level in 1..30) { "난이도 레벨은 1~30 사이여야 합니다. level=$level" }
    }

    /**
     * Solved.ac 레벨을 반환한다. (기존 difficultyLevel과의 호환성을 위해 유지)
     */
    val difficultyLevel: Int
        get() = level

    /**
     * 주어진 티어보다 이 문제가 더 어려운지 여부를 판단한다.
     * Solved.ac 레벨을 직접 비교하여 단순하게 판단한다.
     *
     * @param tier 비교 대상 티어
     * @return 문제가 더 어렵다면 true, 그렇지 않다면 false
     */
    fun isHarderThan(tier: Tier): Boolean {
        return level > tier.value
    }
}
