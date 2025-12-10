package com.didimlog.application.quote

import com.didimlog.domain.Quote
import com.didimlog.domain.repository.QuoteRepository
import jakarta.annotation.PostConstruct
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * 명언 서비스
 * 동기부여 명언을 관리하고 랜덤으로 제공한다.
 */
@Service
class QuoteService(
    private val quoteRepository: QuoteRepository
) {

    private val log = LoggerFactory.getLogger(QuoteService::class.java)

    /**
     * 애플리케이션 시작 시 명언 데이터를 시딩한다.
     * DB에 명언이 하나도 없으면 초기 데이터를 저장한다.
     */
    @PostConstruct
    @Transactional
    fun seedQuotes() {
        if (quoteRepository.count() > 0) {
            log.info("명언 데이터가 이미 존재합니다. 시딩을 건너뜁니다.")
            return
        }

        val initialQuotes = listOf(
            Quote(content = "코딩은 90%의 디버깅과 10%의 버그 생성으로 이루어진다.", author = "Unknown"),
            Quote(content = "프로그래밍은 생각을 코드로 표현하는 예술이다.", author = "Unknown"),
            Quote(content = "좋은 코드는 읽기 쉬운 코드다.", author = "Martin Fowler"),
            Quote(content = "실패는 성공의 어머니다. 실패에서 배우고 계속 나아가라.", author = "Unknown"),
            Quote(content = "코드는 사람이 읽기 위해 작성되고, 컴퓨터가 실행하기 위해 작성된다.", author = "Harold Abelson"),
            Quote(content = "완벽한 코드는 없다. 하지만 더 나은 코드는 있다.", author = "Unknown"),
            Quote(content = "작은 것부터 시작하라. 큰 것을 만들기 전에 작은 것을 완벽하게 만들어라.", author = "Unknown"),
            Quote(content = "코드 리뷰는 배움의 기회다. 비판을 두려워하지 말라.", author = "Unknown"),
            Quote(content = "알고리즘은 문제 해결의 핵심이다. 계속 연습하라.", author = "Unknown"),
            Quote(content = "데이터 구조를 선택하는 것이 알고리즘보다 중요할 수 있다.", author = "Unknown"),
            Quote(content = "복잡한 문제는 작은 문제들로 나누어 해결하라.", author = "Unknown"),
            Quote(content = "테스트 코드를 작성하면 자신감이 생긴다.", author = "Unknown"),
            Quote(content = "리팩토링은 코드를 개선하는 지속적인 과정이다.", author = "Martin Fowler"),
            Quote(content = "버그를 찾는 것보다 버그를 만들지 않는 것이 더 중요하다.", author = "Unknown"),
            Quote(content = "코드의 가독성은 유지보수성의 핵심이다.", author = "Unknown"),
            Quote(content = "프로그래밍은 문제 해결 능력을 기르는 최고의 방법이다.", author = "Unknown"),
            Quote(content = "매일 조금씩이라도 코딩하라. 꾸준함이 실력을 만든다.", author = "Unknown"),
            Quote(content = "어려운 문제를 풀 때마다 한 단계씩 성장한다.", author = "Unknown"),
            Quote(content = "코드는 문서다. 명확하게 작성하라.", author = "Unknown"),
            Quote(content = "프로그래밍은 창의성과 논리적 사고의 조화다.", author = "Unknown")
        )

        quoteRepository.saveAll(initialQuotes)
        log.info("명언 데이터 시딩 완료: ${initialQuotes.size}개 저장")
    }

    /**
     * DB에 저장된 명언 중 하나를 무작위로 반환한다.
     *
     * @return 랜덤 명언 (DB에 명언이 없으면 null)
     */
    @Transactional(readOnly = true)
    fun getRandomQuote(): Quote? {
        val count = quoteRepository.count()
        if (count == 0L) {
            log.warn("DB에 명언 데이터가 없습니다.")
            return null
        }

        val randomIndex = (0 until count).random()
        val allQuotes = quoteRepository.findAll()
        return allQuotes.elementAt(randomIndex.toInt())
    }
}
