package com.didimlog.quote.domain.repository

import com.didimlog.quote.domain.Quote
import org.springframework.data.mongodb.repository.MongoRepository

interface QuoteRepository : MongoRepository<Quote, String> {
}














