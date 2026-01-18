package com.didimlog.domain.template

import com.didimlog.domain.enums.SectionCategory

/**
 * 템플릿 섹션 프리셋 Enum
 * 사용자가 커스텀 템플릿 작성 시 활용할 수 있는 추천 섹션 목록을 제공한다.
 *
 * @property title 섹션 제목 (버튼에 표시될 이름)
 * @property markdownContent 삽입될 마크다운 내용 (이모지 포함)
 * @property guide 섹션 작성 가이드 (툴팁용)
 * @property category 섹션 카테고리 (SUCCESS, FAIL, COMMON)
 */
enum class SectionPreset(
    val title: String,
    val markdownContent: String,
    val guide: String,
    val category: SectionCategory
) {
    // 성공용 섹션
    KEY_LOGIC(
        "💡 핵심 로직",
        "## 💡 핵심 로직\n\n",
        "이 문제의 가장 중요한 점화식이나 접근법은 무엇인가요?",
        SectionCategory.SUCCESS
    ),
    COMPLEXITY(
        "⏱️ 복잡도 분석",
        "## ⏱️ 복잡도 분석\n\n",
        "시간 복잡도와 공간 복잡도를 분석해보세요. (예: O(N), O(1))",
        SectionCategory.SUCCESS
    ),
    DATA_STRUCTURE(
        "🛠️ 사용한 자료구조",
        "## 🛠️ 사용한 자료구조\n\n",
        "왜 HashMap 대신 TreeMap을 썼는지 등 자료구조 선택 이유를 설명하세요.",
        SectionCategory.SUCCESS
    ),
    ALTERNATIVE(
        "🆚 다른 풀이 비교",
        "## 🆚 다른 풀이 비교\n\n",
        "현재 풀이와 다른 접근 방법(DFS vs BFS 등)을 비교해보세요.",
        SectionCategory.SUCCESS
    ),
    REFACTORING(
        "✨ 리팩토링 포인트",
        "## ✨ 리팩토링 포인트\n\n",
        "더 깔끔하게 작성할 수 있었던 변수명이나 함수 분리 포인트를 적어보세요.",
        SectionCategory.SUCCESS
    ),

    // 실패용 섹션
    ROOT_CAUSE(
        "🧐 실패 원인",
        "## 🧐 실패 원인\n\n",
        "문제를 풀지 못한 주요 원인은 무엇인가요? (논리 오류, 구현 실수, 지식 부족 등)",
        SectionCategory.FAIL
    ),
    COUNTER_EXAMPLE(
        "🧪 반례",
        "## 🧪 반례\n\n",
        "내 코드가 틀리는 결정적인 입력값을 찾아보세요.",
        SectionCategory.FAIL
    ),
    DEBUGGING_LOG(
        "🐛 디버깅 로그",
        "## 🐛 디버깅 로그\n\n",
        "어떤 입력값에서 문제가 발생했는지, 어떻게 추적했는지 기록하세요.",
        SectionCategory.FAIL
    ),
    ACTION_PLAN(
        "🔧 다음 시도 계획",
        "## 🔧 다음 시도 계획\n\n",
        "다시 풀 때 꼭 체크할 리스트를 작성하세요.",
        SectionCategory.FAIL
    ),

    // 공통 섹션
    REFERENCES(
        "🔗 참고 자료",
        "## 🔗 참고 자료\n\n",
        "도움받은 블로그 링크나 공식 문서를 정리하세요.",
        SectionCategory.COMMON
    ),
    COMMENT(
        "💬 오늘의 한마디",
        "## 💬 오늘의 한마디\n\n",
        "이 문제를 풀며 느낀 점을 자유롭게 적어보세요.",
        SectionCategory.COMMON
    )
}
