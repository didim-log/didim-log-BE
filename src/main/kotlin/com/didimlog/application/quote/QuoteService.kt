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
     * 기존 데이터가 있어도 누락 명언을 보강하고 Unknown 저자를 교정한다.
     */
    @PostConstruct
    @Transactional
    fun seedQuotes() {
        val curatedQuotes = curatedQuotes()
        val existingQuotes = quoteRepository.findAll()
        val existingByContent = existingQuotes.associateBy { normalizeContent(it.content) }

        val toInsert = mutableListOf<Quote>()
        val toUpdate = mutableListOf<Quote>()

        curatedQuotes.forEach { candidate ->
            val key = normalizeContent(candidate.content)
            val existing = existingByContent[key]
            if (existing == null) {
                toInsert.add(candidate)
                return@forEach
            }

            if (isUnknownAuthor(existing.author) && !isUnknownAuthor(candidate.author)) {
                toUpdate.add(existing.copy(author = candidate.author))
            }
        }

        if (toInsert.isNotEmpty()) {
            quoteRepository.saveAll(toInsert)
        }
        if (toUpdate.isNotEmpty()) {
            quoteRepository.saveAll(toUpdate)
        }

        log.info(
            "명언 데이터 동기화 완료: inserted={}, authorUpdated={}, total={}",
            toInsert.size,
            toUpdate.size,
            quoteRepository.count()
        )
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

    private fun normalizeContent(content: String): String {
        return content.trim().lowercase()
    }

    private fun isUnknownAuthor(author: String): Boolean {
        return author.trim().equals("unknown", ignoreCase = true)
    }

    private fun curatedQuotes(): List<Quote> {
        return listOf(
            Quote(content = "말은 쉽다. 코드를 보여줘라.", author = "Linus Torvalds"),
            Quote(content = "프로그램은 사람이 읽을 수 있게 작성하고, 기계가 실행할 수 있게 해야 한다.", author = "Harold Abelson"),
            Quote(content = "컴퓨터가 이해하는 코드는 누구나 짤 수 있다. 좋은 개발자는 사람이 이해하는 코드를 짠다.", author = "Martin Fowler"),
            Quote(content = "신뢰성의 전제 조건은 단순함이다.", author = "Edsger W. Dijkstra"),
            Quote(content = "먼저 문제를 해결하라. 그다음 코드를 작성하라.", author = "John Johnson"),
            Quote(content = "조기 최적화는 모든 악의 근원이다.", author = "Donald Knuth"),
            Quote(content = "디버깅이 버그를 제거하는 과정이라면, 프로그래밍은 버그를 넣는 과정이다.", author = "Edsger W. Dijkstra"),
            Quote(content = "재사용 가능한 소프트웨어가 되기 전에 먼저 사용 가능한 소프트웨어여야 한다.", author = "Ralph Johnson"),
            Quote(content = "소프트웨어의 위대한 점은 복잡함을 단순하게 보이게 하는 것이다.", author = "Grady Booch"),
            Quote(content = "정확한 측정 하나는 전문가의 의견 천 개보다 가치 있다.", author = "Grace Hopper"),
            Quote(content = "가장 위험한 말은 '우리는 항상 이렇게 해왔어'다.", author = "Grace Hopper"),
            Quote(content = "새 언어를 배우는 최고의 방법은 그 언어로 프로그램을 작성하는 것이다.", author = "Dennis Ritchie"),
            Quote(content = "위대한 개발자는 빠른 개발자가 아니라, 습관이 좋은 개발자다.", author = "Kent Beck"),
            Quote(content = "리팩토링은 코드를 더 빠르게 만드는 일이 아니라 더 이해하기 쉽게 만드는 일이다.", author = "Martin Fowler"),
            Quote(content = "단순함은 효율성의 핵심이다.", author = "Austin Freeman"),
            Quote(content = "완벽함은 더할 것이 없을 때가 아니라 뺄 것이 없을 때 완성된다.", author = "Antoine de Saint-Exupery"),
            Quote(content = "코드를 작성할 때는 다음 유지보수자가 흉기를 든 사이코패스라는 마음으로 작성하라.", author = "John Woods"),
            Quote(content = "오늘의 작은 개선이 내일의 큰 실력을 만든다.", author = "James Clear"),
            Quote(content = "계속 시도하는 사람만이 결국 문제를 푼다.", author = "Albert Einstein"),
            Quote(content = "성공은 열정이 식지 않은 채 실패를 거듭하는 능력이다.", author = "Winston Churchill"),
            Quote(content = "시작이 반이다. 작게라도 매일 코드를 작성하라.", author = "Horace"),
            Quote(content = "훌륭한 엔지니어는 문제를 피하지 않고 구조를 설계한다.", author = "Barbara Liskov"),
            Quote(content = "이해하기 쉬운 설계는 협업을 가속한다.", author = "Robert C. Martin"),
            Quote(content = "테스트는 품질의 결과가 아니라 품질을 만드는 과정이다.", author = "Kent Beck"),
            Quote(content = "중복은 버그의 번식지다.", author = "Andy Hunt"),
            Quote(content = "복잡한 문제를 풀려면 먼저 문제를 잘게 나누어라.", author = "Alan Perlis"),
            Quote(content = "기술은 빠르게 변하지만, 문제 해결 능력은 오래간다.", author = "Bjarne Stroustrup"),
            Quote(content = "더 나은 코드는 더 나은 질문에서 시작된다.", author = "Brian Kernighan"),
            Quote(content = "배움에 투자한 시간은 절대 배신하지 않는다.", author = "Benjamin Franklin"),
            Quote(content = "오늘의 불편함은 내일의 경쟁력이 된다.", author = "Peter Drucker"),
            Quote(content = "프로그래밍은 창의성과 논리의 만남이다.", author = "Donald Knuth"),
            Quote(content = "읽기 좋은 코드가 결국 빠른 개발을 만든다.", author = "Robert C. Martin"),
            Quote(content = "문제를 정확히 정의하면 절반은 해결된 것이다.", author = "Charles Kettering"),
            Quote(content = "실패를 기록하는 사람만이 개선을 반복할 수 있다.", author = "Thomas Edison"),
            Quote(content = "꾸준함은 재능을 이긴다.", author = "Angela Duckworth")
        )
    }
}













