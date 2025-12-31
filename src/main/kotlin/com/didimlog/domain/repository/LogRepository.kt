package com.didimlog.domain.repository

import com.didimlog.domain.Log
import org.springframework.data.mongodb.repository.MongoRepository

interface LogRepository : MongoRepository<Log, String>


