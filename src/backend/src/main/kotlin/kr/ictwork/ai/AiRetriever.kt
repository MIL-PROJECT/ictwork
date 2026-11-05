package kr.ictwork.ai

import org.springframework.stereotype.Component

/** 찾은 조각 하나: 모델에 넘길 글과 출처 */
class AiFound(val ref: AiSourceRef, val header: String?, val text: String, val cosine: Double)

/**
 * 글 찾기 (REQ-B02·B03, slices/12 5.4·11.5). 읽기 트랜잭션 안에서 부른다(권한 판정이 DB를 읽음).
 * 1. 메모리 코사인 순위(모든 조각)와 FULLTEXT 상위 200을 RRF(k = 60)로 합친다
 * 2. 합친 순서로 내려가며 대상마다 권한을 한 번만 판정해(judge가 기억) 6개까지, 대상마다 2개까지 고른다. 대상 300개를 보면 멈춘다
 * 3. 코사인이 문턱보다 낮고 FULLTEXT 상위 FULLTEXT_PASS에도 없는 조각은 버린다 (ngram FULLTEXT는 두 글자만 같아도 걸려 상위 200을 다 넣으면 관계없는 글이 섞인다)
 */
@Component
class AiRetriever(
    private val cache: AiVectorCache,
    private val chunkRepository: AiChunkRepository,
    private val sourceRepository: AiSourceRepository,
) {
    fun find(judge: AiSourceAccess.Judge, question: String, vector: FloatArray): List<AiFound> {
        val cosine = cache.rank(vector)
        val fullText = chunkRepository.fullText(question).map { it.toLong() }
        val fullRank = fullText.withIndex().associate { it.value to it.index }
        val scores = HashMap<Long, Double>()
        cosine.forEachIndexed { i, (id, _, _) -> scores.merge(id, 1.0 / (RRF_K + i + 1), Double::plus) }
        fullText.forEachIndexed { i, id -> scores.merge(id, 1.0 / (RRF_K + i + 1), Double::plus) }
        val cosineOf = cosine.associate { it.first to it.third }
        val sourceOfChunk = cosine.associate { it.first to it.second }.toMutableMap()
        // FULLTEXT에만 나온 조각(캐시가 아직 모름)은 DB에서 대상을 찾는다
        val unknown = fullText.filter { it !in sourceOfChunk }
        if (unknown.isNotEmpty()) chunkRepository.findAllById(unknown).forEach { sourceOfChunk[checkNotNull(it.id)] = it.sourcePk }

        val sources = HashMap<Long, AiSource?>()
        val usable = HashMap<Long, Boolean>()
        val perSource = HashMap<Long, Int>()
        val picked = mutableListOf<Long>()
        for ((chunkId, _) in scores.entries.sortedByDescending { it.value }) {
            if (picked.size >= MAX_CHUNKS || usable.size >= MAX_SOURCES) break
            val cos = cosineOf[chunkId] ?: 0.0
            if (cos < MIN_COSINE && (fullRank[chunkId] ?: FULLTEXT_PASS) >= FULLTEXT_PASS) continue
            val pk = sourceOfChunk[chunkId] ?: continue
            if ((perSource[pk] ?: 0) >= PER_SOURCE) continue
            val source = sources.getOrPut(pk) { sourceRepository.findById(pk).orElse(null) } ?: continue
            if (source.status != AiIndexStatuses.DONE) continue
            if (!usable.getOrPut(pk) { judge.canUse(source.key, source.shareKey) }) continue
            perSource.merge(pk, 1, Int::plus)
            picked += chunkId
        }
        val chunks = chunkRepository.findAllById(picked).associateBy { checkNotNull(it.id) }
        val headers = HashMap<Long, String?>()
        return picked.mapNotNull { id ->
            val chunk = chunks[id] ?: return@mapNotNull null
            val source = checkNotNull(sources[chunk.sourcePk])
            AiFound(
                judge.ref(source.key, source.title, chunk.locator, source.shareKey),
                headers.getOrPut(chunk.sourcePk) { judge.workHeader(source.key) },
                chunk.text,
                cosineOf[id] ?: 0.0,
            )
        }
    }

    companion object {
        const val RRF_K = 60
        const val MAX_CHUNKS = 6
        const val PER_SOURCE = 2
        const val MAX_SOURCES = 300

        /**
         * 코사인 문턱 (slices/12 11.5: 처음 0.45, PoC 값). 시연 자료로 잰 값(tests/poc/ai-qa/실제모델평가.md): 질문과 맞는 글 0.53~0.76,
         * 일반 질문(라우터·TCP)의 가장 가까운 글 0.34~0.37, 체계 질문에 함께 찾히는 다른 업무 글 0.45~0.55 → 0.5
         */
        const val MIN_COSINE = 0.5

        /** 코사인이 문턱보다 낮아도 넣는 FULLTEXT 순위 (그 밖 FULLTEXT 조각은 순위 합치기에만 쓴다) */
        const val FULLTEXT_PASS = 10
    }
}
