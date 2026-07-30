package com.didimlog.domain.repository

import org.springframework.data.annotation.Id

data class StudentFeedbackWriterView(
    @Id
    val id: String,
    val bojId: String?
)
