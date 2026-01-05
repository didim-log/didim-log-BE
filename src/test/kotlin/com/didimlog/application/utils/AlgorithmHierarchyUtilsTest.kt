package com.didimlog.application.utils

import com.didimlog.domain.enums.ProblemCategory
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("AlgorithmHierarchyUtils 테스트")
class AlgorithmHierarchyUtilsTest {

    @Test
    @DisplayName("Graph Theory를 선택하면 하위 태그들도 포함하여 반환한다")
    fun `Graph Theory 확장 검색`() {
        // when
        val expandedTags = AlgorithmHierarchyUtils.getExpandedTags(ProblemCategory.GRAPH_THEORY.englishName)

        // then
        assertThat(expandedTags).contains(ProblemCategory.GRAPH_THEORY.englishName)
        assertThat(expandedTags).contains(ProblemCategory.BFS.englishName)
        assertThat(expandedTags).contains(ProblemCategory.DFS.englishName)
        assertThat(expandedTags).contains(ProblemCategory.DIJKSTRA.englishName)
        assertThat(expandedTags).contains(ProblemCategory.BELLMAN_FORD.englishName)
        assertThat(expandedTags).contains(ProblemCategory.MST.englishName)
        assertThat(expandedTags).contains(ProblemCategory.LCA.englishName)
    }

    @Test
    @DisplayName("Dynamic Programming을 선택하면 하위 태그들도 포함하여 반환한다")
    fun `Dynamic Programming 확장 검색`() {
        // when
        val expandedTags = AlgorithmHierarchyUtils.getExpandedTags(ProblemCategory.DP.englishName)

        // then
        assertThat(expandedTags).contains(ProblemCategory.DP.englishName)
        assertThat(expandedTags).contains(ProblemCategory.KNAPSACK.englishName)
        assertThat(expandedTags).contains(ProblemCategory.LCS.englishName)
        assertThat(expandedTags).contains(ProblemCategory.LIS.englishName)
    }

    @Test
    @DisplayName("Data Structures를 선택하면 하위 태그들도 포함하여 반환한다")
    fun `Data Structures 확장 검색`() {
        // when
        val expandedTags = AlgorithmHierarchyUtils.getExpandedTags(ProblemCategory.DATA_STRUCTURES.englishName)

        // then
        assertThat(expandedTags).contains(ProblemCategory.DATA_STRUCTURES.englishName)
        assertThat(expandedTags).contains(ProblemCategory.STACK.englishName)
        assertThat(expandedTags).contains(ProblemCategory.QUEUE.englishName)
        assertThat(expandedTags).contains(ProblemCategory.SEGMENT_TREE.englishName)
        assertThat(expandedTags).contains(ProblemCategory.TRIE.englishName)
    }

    @Test
    @DisplayName("String을 선택하면 하위 태그들도 포함하여 반환한다")
    fun `String 확장 검색`() {
        // when
        val expandedTags = AlgorithmHierarchyUtils.getExpandedTags(ProblemCategory.STRING.englishName)

        // then
        assertThat(expandedTags).contains(ProblemCategory.STRING.englishName)
        assertThat(expandedTags).contains(ProblemCategory.KMP.englishName)
        assertThat(expandedTags).contains(ProblemCategory.TRIE.englishName)
        assertThat(expandedTags).contains(ProblemCategory.SUFFIX_ARRAY.englishName)
        assertThat(expandedTags).contains(ProblemCategory.AHO_CORASICK.englishName)
    }

    @Test
    @DisplayName("계층 구조에 없는 카테고리는 자기 자신만 반환한다")
    fun `계층 구조 없는 카테고리`() {
        // when
        val expandedTags = AlgorithmHierarchyUtils.getExpandedTags(ProblemCategory.IMPLEMENTATION.englishName)

        // then
        assertThat(expandedTags).hasSize(1)
        assertThat(expandedTags).containsExactly(ProblemCategory.IMPLEMENTATION.englishName)
    }

    @Test
    @DisplayName("enum 이름으로도 확장 검색이 가능하다")
    fun `enum 이름으로 확장 검색`() {
        // when
        val expandedTags = AlgorithmHierarchyUtils.getExpandedTags(ProblemCategory.GRAPH_THEORY.name)

        // then
        assertThat(expandedTags).isNotEmpty
        assertThat(expandedTags).contains(ProblemCategory.GRAPH_THEORY.englishName)
        assertThat(expandedTags).contains(ProblemCategory.BFS.englishName)
        assertThat(expandedTags).contains(ProblemCategory.DFS.englishName)
    }

    @Test
    @DisplayName("한글 이름으로도 확장 검색이 가능하다")
    fun `한글 이름으로 확장 검색`() {
        // when
        val expandedTags = AlgorithmHierarchyUtils.getExpandedTags(ProblemCategory.GRAPH_THEORY.koreanName)

        // then
        assertThat(expandedTags).contains(ProblemCategory.GRAPH_THEORY.englishName)
        assertThat(expandedTags).contains(ProblemCategory.BFS.englishName)
        assertThat(expandedTags).contains(ProblemCategory.DFS.englishName)
    }

    @Test
    @DisplayName("findCategoryEnglishName은 입력받은 카테고리를 englishName으로 변환한다")
    fun `findCategoryEnglishName 테스트`() {
        // when & then
        assertThat(AlgorithmHierarchyUtils.findCategoryEnglishName("Graph Theory"))
            .isEqualTo(ProblemCategory.GRAPH_THEORY.englishName)
        
        assertThat(AlgorithmHierarchyUtils.findCategoryEnglishName("GRAPH_THEORY"))
            .isEqualTo(ProblemCategory.GRAPH_THEORY.englishName)
        
        assertThat(AlgorithmHierarchyUtils.findCategoryEnglishName("그래프 이론"))
            .isEqualTo(ProblemCategory.GRAPH_THEORY.englishName)
        
        assertThat(AlgorithmHierarchyUtils.findCategoryEnglishName("BFS"))
            .isEqualTo(ProblemCategory.BFS.englishName)
    }
}

