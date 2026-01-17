package com.didimlog.domain.template

import com.didimlog.domain.enums.TemplateOwnershipType
import org.springframework.data.annotation.CreatedDate
import org.springframework.data.annotation.Id
import org.springframework.data.annotation.LastModifiedDate
import org.springframework.data.mongodb.core.mapping.Document
import java.time.LocalDateTime

/**
 * 회고 템플릿 도메인 객체
 * 사용자가 회고 작성을 위해 사용할 수 있는 템플릿을 표현한다.
 *
 * @property studentId 템플릿 소유자 ID (SYSTEM 템플릿의 경우 null)
 * @property title 템플릿 이름
 * @property content 템플릿 내용 (마크다운, 매크로 포함)
 * @property type 템플릿 소유권 타입 (SYSTEM, CUSTOM)
 * @property isDefault 사용자의 기본 템플릿 여부
 */
@Document(collection = "templates")
data class Template(
    @Id
    val id: String? = null,
    val studentId: String? = null,
    val title: String,
    val content: String,
    val type: TemplateOwnershipType,
    val isDefault: Boolean = false,
    @CreatedDate
    val createdAt: LocalDateTime = LocalDateTime.now(),
    @LastModifiedDate
    val updatedAt: LocalDateTime = LocalDateTime.now()
) {
    init {
        require(title.isNotBlank()) { "템플릿 제목은 필수입니다." }
        require(title.length <= 100) { "템플릿 제목은 100자 이하여야 합니다." }
        require(content.isNotBlank()) { "템플릿 내용은 필수입니다." }
        require(content.length <= 10000) { "템플릿 내용은 10000자 이하여야 합니다." }
        if (type == TemplateOwnershipType.SYSTEM) {
            require(studentId == null) { "시스템 템플릿은 소유자를 가질 수 없습니다." }
            require(!isDefault) { "시스템 템플릿은 기본 템플릿으로 설정할 수 없습니다." }
        }
        if (type == TemplateOwnershipType.CUSTOM) {
            require(studentId != null) { "커스텀 템플릿은 소유자가 필요합니다." }
        }
    }

    /**
     * 템플릿 내용을 업데이트한다.
     * 시스템 템플릿은 수정할 수 없다.
     *
     * @param newTitle 새로운 제목
     * @param newContent 새로운 내용
     * @return 업데이트된 템플릿
     * @throws IllegalArgumentException 시스템 템플릿인 경우
     */
    fun update(newTitle: String, newContent: String): Template {
        if (type == TemplateOwnershipType.SYSTEM) {
            throw IllegalArgumentException("시스템 템플릿은 수정할 수 없습니다.")
        }
        require(newTitle.isNotBlank()) { "템플릿 제목은 필수입니다." }
        require(newTitle.length <= 100) { "템플릿 제목은 100자 이하여야 합니다." }
        require(newContent.isNotBlank()) { "템플릿 내용은 필수입니다." }
        require(newContent.length <= 10000) { "템플릿 내용은 10000자 이하여야 합니다." }
        
        return copy(
            title = newTitle,
            content = newContent,
            updatedAt = LocalDateTime.now()
        )
    }

    /**
     * 템플릿을 기본값으로 설정한다.
     *
     * @return 기본값으로 설정된 템플릿
     */
    fun setAsDefault(): Template {
        if (type == TemplateOwnershipType.SYSTEM) {
            throw IllegalArgumentException("시스템 템플릿은 기본 템플릿으로 설정할 수 없습니다.")
        }
        return copy(isDefault = true, updatedAt = LocalDateTime.now())
    }

    /**
     * 템플릿의 기본값을 해제한다.
     *
     * @return 기본값 해제된 템플릿
     */
    fun unsetDefault(): Template {
        return copy(isDefault = false, updatedAt = LocalDateTime.now())
    }

    /**
     * 템플릿의 소유자인지 확인한다.
     *
     * @param studentId 확인할 학생 ID
     * @return 소유자이면 true, 그렇지 않으면 false
     */
    fun isOwner(studentId: String): Boolean {
        if (type == TemplateOwnershipType.SYSTEM) {
            return false
        }
        return this.studentId == studentId
    }

    /**
     * 템플릿의 소유자인지 검증한다.
     * 소유자가 아니면 예외를 발생시킨다.
     *
     * @param studentId 확인할 학생 ID
     * @throws IllegalArgumentException 소유자가 아닌 경우
     */
    fun validateOwner(studentId: String) {
        if (!isOwner(studentId)) {
            throw IllegalArgumentException("템플릿 소유자가 아닙니다. studentId=$studentId")
        }
    }
}