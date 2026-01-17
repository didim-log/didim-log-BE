package com.didimlog.domain.repository

import com.didimlog.domain.enums.TemplateOwnershipType
import com.didimlog.domain.template.Template
import org.springframework.data.mongodb.repository.MongoRepository

interface TemplateRepository : MongoRepository<Template, String> {

    /**
     * 특정 학생의 템플릿과 시스템 템플릿을 모두 조회한다.
     *
     * @param studentId 학생 ID
     * @return 템플릿 목록
     */
    fun findByStudentIdOrType(studentId: String, type: TemplateOwnershipType): List<Template>

    /**
     * 특정 학생의 커스텀 템플릿만 조회한다.
     *
     * @param studentId 학생 ID
     * @return 템플릿 목록
     */
    fun findByStudentId(studentId: String): List<Template>

    /**
     * 시스템 템플릿만 조회한다.
     *
     * @param type 템플릿 소유권 타입
     * @return 템플릿 목록
     */
    fun findByType(type: TemplateOwnershipType): List<Template>

    /**
     * 특정 학생의 기본 템플릿을 조회한다.
     *
     * @param studentId 학생 ID
     * @return 기본 템플릿 (없으면 null)
     */
    fun findByStudentIdAndIsDefaultTrue(studentId: String): Template?

    /**
     * 특정 학생의 모든 기본 템플릿을 조회한다.
     * 기본값 설정 시 기존 기본 템플릿을 찾기 위해 사용한다.
     *
     * @param studentId 학생 ID
     * @return 기본 템플릿 목록
     */
    fun findAllByStudentIdAndIsDefaultTrue(studentId: String): List<Template>
}