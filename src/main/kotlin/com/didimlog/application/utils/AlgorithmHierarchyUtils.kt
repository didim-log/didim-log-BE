package com.didimlog.application.utils

import com.didimlog.domain.enums.ProblemCategory

/**
 * 알고리즘 계층 구조 유틸리티
 * 상위 개념의 카테고리를 선택하면 하위 태그들도 포함하여 검색할 수 있도록 지원한다.
 *
 * 예: "Graph Theory"를 선택하면 "BFS", "DFS", "Dijkstra" 등 하위 태그도 포함하여 검색
 */
object AlgorithmHierarchyUtils {

    /**
     * 알고리즘 계층 구조 정의
     * Key: 상위 카테고리 (ProblemCategory enum 이름 또는 englishName)
     * Value: 하위 태그 리스트 (ProblemCategory enum 이름 또는 englishName)
     */
    private val HIERARCHY_MAP: Map<String, List<String>> = mapOf(
        // Graph Theory 계층
        ProblemCategory.GRAPH_THEORY.name to listOf(
            ProblemCategory.BFS.name,
            ProblemCategory.DFS.name,
            ProblemCategory.DIJKSTRA.name,
            ProblemCategory.BELLMAN_FORD.name,
            ProblemCategory.FLOYD_WARSHALL.name,
            ProblemCategory.TOPOLOGICAL_SORTING.name,
            ProblemCategory.MST.name,
            ProblemCategory.LCA.name,
            ProblemCategory.BIPARTITE_MATCHING.name,
            ProblemCategory.SHORTEST_PATH.name,
            ProblemCategory.GRAPH_TRAVERSAL.name,
            ProblemCategory.BIPARTITE_GRAPH.name,
            ProblemCategory.DAG.name,
            ProblemCategory.SCC.name,
            ProblemCategory.ARTICULATION_POINTS.name,
            ProblemCategory.BICONNECTED_COMPONENT.name,
            ProblemCategory.EULERIAN_PATH.name,
            ProblemCategory.TWO_SAT.name,
            ProblemCategory.MAX_FLOW.name,
            ProblemCategory.MCMF.name,
            ProblemCategory.TREE_DIAMETER.name,
            ProblemCategory.ZERO_ONE_BFS.name,
            ProblemCategory.FLOOD_FILL.name,
            ProblemCategory.GRID_GRAPH.name,
            ProblemCategory.FUNCTIONAL_GRAPH.name,
            ProblemCategory.PLANAR_GRAPH.name,
            ProblemCategory.CHORDAL_GRAPH.name,
            ProblemCategory.DUAL_GRAPH.name,
            ProblemCategory.DIRECTED_MST.name,
        ),
        ProblemCategory.GRAPH_THEORY.englishName to listOf(
            ProblemCategory.BFS.englishName,
            ProblemCategory.DFS.englishName,
            ProblemCategory.DIJKSTRA.englishName,
            ProblemCategory.BELLMAN_FORD.englishName,
            ProblemCategory.FLOYD_WARSHALL.englishName,
            ProblemCategory.TOPOLOGICAL_SORTING.englishName,
            ProblemCategory.MST.englishName,
            ProblemCategory.LCA.englishName,
            ProblemCategory.BIPARTITE_MATCHING.englishName,
            ProblemCategory.SHORTEST_PATH.englishName,
            ProblemCategory.GRAPH_TRAVERSAL.englishName,
            ProblemCategory.BIPARTITE_GRAPH.englishName,
            ProblemCategory.DAG.englishName,
            ProblemCategory.SCC.englishName,
            ProblemCategory.ARTICULATION_POINTS.englishName,
            ProblemCategory.BICONNECTED_COMPONENT.englishName,
            ProblemCategory.EULERIAN_PATH.englishName,
            ProblemCategory.TWO_SAT.englishName,
            ProblemCategory.MAX_FLOW.englishName,
            ProblemCategory.MCMF.englishName,
            ProblemCategory.TREE_DIAMETER.englishName,
            ProblemCategory.ZERO_ONE_BFS.englishName,
            ProblemCategory.FLOOD_FILL.englishName,
            ProblemCategory.GRID_GRAPH.englishName,
            ProblemCategory.FUNCTIONAL_GRAPH.englishName,
            ProblemCategory.PLANAR_GRAPH.englishName,
            ProblemCategory.CHORDAL_GRAPH.englishName,
            ProblemCategory.DUAL_GRAPH.englishName,
            ProblemCategory.DIRECTED_MST.englishName,
        ),

        // Dynamic Programming 계층
        ProblemCategory.DP.name to listOf(
            ProblemCategory.KNAPSACK.name,
            ProblemCategory.LCS.name,
            ProblemCategory.LIS.name,
            ProblemCategory.BITMASK.name,
            ProblemCategory.DP_ON_TREES.name,
            ProblemCategory.DP_BITFIELD.name,
            ProblemCategory.DIGIT_DP.name,
            ProblemCategory.DP_DEQUE.name,
            ProblemCategory.DP_CONNECTION_PROFILE.name,
            ProblemCategory.SOS_DP.name,
        ),
        ProblemCategory.DP.englishName to listOf(
            ProblemCategory.KNAPSACK.englishName,
            ProblemCategory.LCS.englishName,
            ProblemCategory.LIS.englishName,
            ProblemCategory.BITMASK.englishName,
            ProblemCategory.DP_ON_TREES.englishName,
            ProblemCategory.DP_BITFIELD.englishName,
            ProblemCategory.DIGIT_DP.englishName,
            ProblemCategory.DP_DEQUE.englishName,
            ProblemCategory.DP_CONNECTION_PROFILE.englishName,
            ProblemCategory.SOS_DP.englishName,
        ),

        // Data Structures 계층
        ProblemCategory.DATA_STRUCTURES.name to listOf(
            ProblemCategory.STACK.name,
            ProblemCategory.QUEUE.name,
            ProblemCategory.DEQUE.name,
            ProblemCategory.SEGMENT_TREE.name,
            ProblemCategory.SEGMENT_TREE_LAZY.name,
            ProblemCategory.DISJOINT_SET.name,
            ProblemCategory.PRIORITY_QUEUE.name,
            ProblemCategory.TRIE.name,
            ProblemCategory.SPARSE_TABLE.name,
            ProblemCategory.PERSISTENT_SEGMENT_TREE.name,
            ProblemCategory.MERGE_SORT_TREE.name,
            ProblemCategory.MULTIDIMENSIONAL_SEGMENT_TREE.name,
            ProblemCategory.KINETIC_SEGMENT_TREE.name,
            ProblemCategory.SEGMENT_TREE_BEATS.name,
            ProblemCategory.LINKED_LIST.name,
            ProblemCategory.CARTESIAN_TREE.name,
            ProblemCategory.SPLAY_TREE.name,
            ProblemCategory.LINK_CUT_TREE.name,
            ProblemCategory.TOP_TREE.name,
            ProblemCategory.ROPE.name,
            ProblemCategory.RED_BLACK_TREE.name,
        ),
        ProblemCategory.DATA_STRUCTURES.englishName to listOf(
            ProblemCategory.STACK.englishName,
            ProblemCategory.QUEUE.englishName,
            ProblemCategory.DEQUE.englishName,
            ProblemCategory.SEGMENT_TREE.englishName,
            ProblemCategory.SEGMENT_TREE_LAZY.englishName,
            ProblemCategory.DISJOINT_SET.englishName,
            ProblemCategory.PRIORITY_QUEUE.englishName,
            ProblemCategory.TRIE.englishName,
            ProblemCategory.SPARSE_TABLE.englishName,
            ProblemCategory.PERSISTENT_SEGMENT_TREE.englishName,
            ProblemCategory.MERGE_SORT_TREE.englishName,
            ProblemCategory.MULTIDIMENSIONAL_SEGMENT_TREE.englishName,
            ProblemCategory.KINETIC_SEGMENT_TREE.englishName,
            ProblemCategory.SEGMENT_TREE_BEATS.englishName,
            ProblemCategory.LINKED_LIST.englishName,
            ProblemCategory.CARTESIAN_TREE.englishName,
            ProblemCategory.SPLAY_TREE.englishName,
            ProblemCategory.LINK_CUT_TREE.englishName,
            ProblemCategory.TOP_TREE.englishName,
            ProblemCategory.ROPE.englishName,
            ProblemCategory.RED_BLACK_TREE.englishName,
        ),

        // Mathematics 계층
        ProblemCategory.MATHEMATICS.name to listOf(
            ProblemCategory.NUMBER_THEORY.name,
            ProblemCategory.PRIMALITY_TEST.name,
            ProblemCategory.SIEVE.name,
            ProblemCategory.COMBINATORICS.name,
            ProblemCategory.ARITHMETIC.name,
            ProblemCategory.EUCLIDEAN.name,
            ProblemCategory.EXTENDED_EUCLIDEAN.name,
            ProblemCategory.MODULAR_INVERSE.name,
            ProblemCategory.EULER_TOTIENT.name,
            ProblemCategory.FERMAT_LITTLE.name,
            ProblemCategory.CRT.name,
            ProblemCategory.PRIME_FACTORIZATION.name,
            ProblemCategory.DISCRETE_LOG.name,
            ProblemCategory.DISCRETE_SQRT.name,
            ProblemCategory.MILLER_RABIN.name,
            ProblemCategory.POLLARD_RHO.name,
            ProblemCategory.MOBIUS_INVERSION.name,
            ProblemCategory.LUCAS.name,
            ProblemCategory.PISANO.name,
            ProblemCategory.LTE.name,
        ),
        ProblemCategory.MATHEMATICS.englishName to listOf(
            ProblemCategory.NUMBER_THEORY.englishName,
            ProblemCategory.PRIMALITY_TEST.englishName,
            ProblemCategory.SIEVE.englishName,
            ProblemCategory.COMBINATORICS.englishName,
            ProblemCategory.ARITHMETIC.englishName,
            ProblemCategory.EUCLIDEAN.englishName,
            ProblemCategory.EXTENDED_EUCLIDEAN.englishName,
            ProblemCategory.MODULAR_INVERSE.englishName,
            ProblemCategory.EULER_TOTIENT.englishName,
            ProblemCategory.FERMAT_LITTLE.englishName,
            ProblemCategory.CRT.englishName,
            ProblemCategory.PRIME_FACTORIZATION.englishName,
            ProblemCategory.DISCRETE_LOG.englishName,
            ProblemCategory.DISCRETE_SQRT.englishName,
            ProblemCategory.MILLER_RABIN.englishName,
            ProblemCategory.POLLARD_RHO.englishName,
            ProblemCategory.MOBIUS_INVERSION.englishName,
            ProblemCategory.LUCAS.englishName,
            ProblemCategory.PISANO.englishName,
            ProblemCategory.LTE.englishName,
        ),

        // String 계층
        ProblemCategory.STRING.name to listOf(
            ProblemCategory.KMP.name,
            ProblemCategory.TRIE.name,
            ProblemCategory.SUFFIX_ARRAY.name,
            ProblemCategory.AHO_CORASICK.name,
            ProblemCategory.MANACHER.name,
            ProblemCategory.RABIN_KARP.name,
            ProblemCategory.SUFFIX_TREE.name,
            ProblemCategory.PALINDROME_TREE.name,
            ProblemCategory.Z.name,
            ProblemCategory.REGEX.name,
            ProblemCategory.LCS_BITSET.name,
            ProblemCategory.HIRSCHBERG.name,
        ),
        ProblemCategory.STRING.englishName to listOf(
            ProblemCategory.KMP.englishName,
            ProblemCategory.TRIE.englishName,
            ProblemCategory.SUFFIX_ARRAY.englishName,
            ProblemCategory.AHO_CORASICK.englishName,
            ProblemCategory.MANACHER.englishName,
            ProblemCategory.RABIN_KARP.englishName,
            ProblemCategory.SUFFIX_TREE.englishName,
            ProblemCategory.PALINDROME_TREE.englishName,
            ProblemCategory.Z.englishName,
            ProblemCategory.REGEX.englishName,
            ProblemCategory.LCS_BITSET.englishName,
            ProblemCategory.HIRSCHBERG.englishName,
        ),
    )

    /**
     * 입력받은 카테고리를 확장된 태그 리스트로 변환한다.
     * 자기 자신을 포함한 모든 하위 태그 리스트를 반환한다.
     *
     * @param category 입력받은 카테고리 (ProblemCategory enum 이름, englishName, 또는 일반 문자열)
     * @return 확장된 태그 리스트 (자기 자신 포함). 계층 구조에 없으면 자기 자신만 반환
     */
    fun getExpandedTags(category: String): List<String> {
        // ProblemCategory enum에서 매칭 시도
        val matchedCategory = ProblemCategory.entries.find {
            it.name.equals(category, ignoreCase = true) ||
            it.englishName.equals(category, ignoreCase = true) ||
            it.koreanName.equals(category, ignoreCase = true)
        }
        
        val targetCategory = matchedCategory ?: return listOf(category)
        
        // enum 이름과 englishName 둘 다 시도
        val enumNameTags = HIERARCHY_MAP[targetCategory.name]
        val englishNameTags = HIERARCHY_MAP[targetCategory.englishName]
        
        val foundTags = enumNameTags ?: englishNameTags
        
        return if (foundTags != null) {
            // 하위 태그들을 englishName으로 변환
            val expandedChildTags = foundTags.map { tagName ->
                // tagName이 enum 이름인지 englishName인지 확인
                ProblemCategory.entries.find {
                    it.name == tagName || it.englishName == tagName
                }?.englishName ?: tagName // 변환 실패 시 원본 사용
            }
            
            // englishName을 기준으로 반환 (DB에 저장된 형식)
            listOf(targetCategory.englishName) + expandedChildTags
        } else {
            // 계층 구조에 없으면 자기 자신만 반환
            listOf(targetCategory.englishName)
        }
    }

    /**
     * 카테고리 이름을 정규화한다.
     * 대소문자 무시, 공백/언더스코어 정규화
     *
     * @param category 원본 카테고리 이름
     * @return 정규화된 카테고리 이름
     */
    private fun normalizeCategoryName(category: String): String {
        return category.trim()
            .replace(" ", "_")
            .replace("-", "_")
            .uppercase()
    }

    /**
     * ProblemCategory enum에서 카테고리를 찾아서 englishName을 반환한다.
     * enum 이름, englishName, koreanName 모두 매칭을 시도한다.
     *
     * @param category 입력받은 카테고리
     * @return 매칭된 ProblemCategory의 englishName, 없으면 원본 반환
     */
    fun findCategoryEnglishName(category: String): String {
        val normalized = TagUtils.normalizeTagName(category)

        return ProblemCategory.entries
            .find {
                it.name.equals(normalized, ignoreCase = true) ||
                it.englishName.equals(normalized, ignoreCase = true) ||
                it.koreanName.equals(normalized, ignoreCase = true)
            }
            ?.englishName
            ?: normalized
    }
}

