package com.didimlog.domain.repository

import com.didimlog.domain.Quote
import org.springframework.data.mongodb.repository.MongoRepository

interface QuoteRepository : MongoRepository<Quote, String> {
}











