package com.didimlog.application.utils

/**
 * 태그 별칭을 공식 전체 이름으로 변환하는 유틸리티
 * 사용자가 축약형 태그(예: "BFS", "DFS", "DP")를 입력했을 때
 * DB에 저장된 공식 전체 이름(예: "Breadth-first Search")으로 변환한다.
 */
object TagUtils {

    /**
     * 일반적으로 사용되는 태그 별칭을 공식 전체 이름으로 매핑
     * Solved.ac의 공식 태그 이름과 일치하도록 구성
     */
    private val TAG_ALIASES = mapOf(
        // 그래프 탐색
        "BFS" to "Breadth-first Search",
        "DFS" to "Depth-first Search",
        
        // 다이나믹 프로그래밍
        "DP" to "Dynamic Programming",
        
        // 그래프 알고리즘
        "MST" to "Minimum Spanning Tree",
        "LCA" to "Lowest Common Ancestor",
        "SCC" to "Strongly Connected Component",
        "DAG" to "Directed Acyclic Graph",
        
        // 문자열 알고리즘
        "KMP" to "Knuth–morris–pratt",
        "LIS" to "Longest Increasing Sequence Problem",
        "LCS" to "Longest Common Subsequence",
        
        // 수학/이론
        "FFT" to "Fast Fourier Transform",
        "CRT" to "Chinese Remainder Theorem",
        
        // 기타
        "Bitmask" to "Bitmask",
        "Topological Sort" to "Topological Sorting",
        "Topological Sorting" to "Topological Sorting", // 이미 올바른 형식
    )

    /**
     * 입력된 태그 이름을 공식 전체 이름으로 변환한다.
     * 별칭이 있으면 변환하고, 없으면 원본을 반환한다.
     *
     * @param tagName 입력된 태그 이름 (축약형 또는 전체 이름)
     * @return 공식 전체 이름 (별칭이 없으면 원본 반환)
     */
    fun normalizeTagName(tagName: String): String {
        // 대소문자 무시하고 공백 제거 후 비교
        val normalized = tagName.trim()
        
        // 정확한 매칭 시도 (대소문자 무시)
        TAG_ALIASES.forEach { (alias, fullName) ->
            if (alias.equals(normalized, ignoreCase = true)) {
                return fullName
            }
        }
        
        // 별칭이 없으면 원본 반환
        return normalized
    }
}








