package com.didimlog.domain.template

import com.didimlog.domain.enums.SectionCategory

/**
 * 템플릿 섹션 프리셋 Enum
 * 사용자가 커스텀 템플릿 작성 시 활용할 수 있는 추천 섹션 목록을 제공한다.
 *
 * @property title 섹션 제목 (버튼에 표시될 이름)
 * @property markdownContent 삽입될 마크다운 내용 (이모지 포함)
 * @property guide 섹션 작성 가이드 (툴팁용)
 * @property contentGuide 본문에 삽입될 가이드 질문(코칭 질문) (nullable, 선택적으로 사용)
 * @property category 섹션 카테고리 (SUCCESS, FAIL, COMMON)
 */
enum class SectionPreset(
    val title: String,
    val markdownContent: String,
    val guide: String,
    val contentGuide: String?,
    val category: SectionCategory
) {
    // 성공용 섹션
    KEY_LOGIC(
        "💡 핵심 로직",
        "## 💡 핵심 로직\n\n",
        "이 문제의 가장 중요한 점화식이나 접근법은 무엇인가요?",
        "- 문제를 해결하기 위해 어떤 알고리즘이나 자료구조를 선택했나요?\n- 풀이의 핵심 공식을 적어보세요.",
        SectionCategory.SUCCESS
    ),
    COMPLEXITY(
        "⏱️ 복잡도 분석",
        "## ⏱️ 복잡도 분석\n\n",
        "시간 복잡도와 공간 복잡도를 분석해보세요. (예: O(N), O(1))",
        "- 시간 복잡도: O(?)\n- 공간 복잡도: O(?)\n- 각 단계별 연산 횟수를 분석해보세요.",
        SectionCategory.SUCCESS
    ),
    DATA_STRUCTURE(
        "🛠️ 사용한 자료구조",
        "## 🛠️ 사용한 자료구조\n\n",
        "왜 HashMap 대신 TreeMap을 썼는지 등 자료구조 선택 이유를 설명하세요.",
        "- 어떤 자료구조를 선택했고, 왜 그 자료구조가 적합한가요?\n- 다른 자료구조를 사용했다면 어떻게 달라졌을까요?",
        SectionCategory.SUCCESS
    ),
    ALTERNATIVE(
        "🆚 다른 풀이 비교",
        "## 🆚 다른 풀이 비교\n\n",
        "현재 풀이와 다른 접근 방법(DFS vs BFS 등)을 비교해보세요.",
        "- 다른 접근 방법은 무엇이 있나요? (DFS vs BFS, 그리디 vs DP 등)\n- 각 방법의 장단점은 무엇인가요?",
        SectionCategory.SUCCESS
    ),
    REFACTORING(
        "✨ 리팩토링 포인트",
        "## ✨ 리팩토링 포인트\n\n",
        "더 깔끔하게 작성할 수 있었던 변수명이나 함수 분리 포인트를 적어보세요.",
        "- 개선할 수 있는 변수명이나 함수명은 무엇인가요?\n- 코드 중복을 줄이기 위한 리팩토링 포인트는 무엇인가요?",
        SectionCategory.SUCCESS
    ),

    // 실패용 섹션
    ROOT_CAUSE(
        "🧐 실패 원인",
        "## 🧐 실패 원인\n\n",
        "문제를 풀지 못한 주요 원인은 무엇인가요? (논리 오류, 구현 실수, 지식 부족 등)",
        "- 어떤 종류의 에러가 발생했나요? (시간 초과, 메모리 초과 등)\n- 로직의 어느 부분이 잘못되었나요?",
        SectionCategory.FAIL
    ),
    COUNTER_EXAMPLE(
        "🧪 반례",
        "## 🧪 반례\n\n",
        "내 코드가 틀리는 결정적인 입력값을 찾아보세요.",
        "- 내 코드를 깨뜨리는 입력값은 무엇인가요?\n- 왜 그 입력값에서 문제가 발생했나요?",
        SectionCategory.FAIL
    ),
    DEBUGGING_LOG(
        "🐛 디버깅 로그",
        "## 🐛 디버깅 로그\n\n",
        "어떤 입력값에서 문제가 발생했는지, 어떻게 추적했는지 기록하세요.",
        "- 어떤 입력값에서 문제가 발생했나요?\n- 디버깅 과정에서 발견한 패턴은 무엇인가요?",
        SectionCategory.FAIL
    ),
    ACTION_PLAN(
        "🔧 다음 시도 계획",
        "## 🔧 다음 시도 계획\n\n",
        "다시 풀 때 꼭 체크할 리스트를 작성하세요.",
        "- 다음에 다시 풀 때 바꿀 점은 무엇인가요?\n- 체크해야 할 엣지 케이스는 무엇인가요?",
        SectionCategory.FAIL
    ),

    // 공통 섹션
    REFERENCES(
        "🔗 참고 자료",
        "## 🔗 참고 자료\n\n",
        "도움받은 블로그 링크나 공식 문서를 정리하세요.",
        "- 참고한 블로그 링크나 공식 문서를 기록하세요.",
        SectionCategory.COMMON
    ),
    COMMENT(
        "💬 오늘의 한마디",
        "## 💬 오늘의 한마디\n\n",
        "이 문제를 풀며 느낀 점을 자유롭게 적어보세요.",
        null, // 코칭 질문 없이 자유롭게 작성
        SectionCategory.COMMON
    )
}
