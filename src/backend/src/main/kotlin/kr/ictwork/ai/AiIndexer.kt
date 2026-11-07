package kr.ictwork.ai

import kr.ictwork.admin.conflict
import org.slf4j.LoggerFactory
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.time.Clock
import java.time.LocalDateTime
import java.util.concurrent.locks.ReentrantReadWriteLock
import javax.annotation.PreDestroy
import kotlin.concurrent.read
import kotlin.concurrent.write
import kotlin.math.sqrt

/**
 * 모든 조각의 벡터를 메모리에 둔다 (slices/12 5.2). 벡터는 길이 1로 맞춰 두어 코사인이 내적이다.
 * 색인이 끝날 때마다 바뀐 대상만 고친다
 */
@Component
class AiVectorCache {
    private class Entry(val sourcePk: Long, val vector: FloatArray)

    private val lock = ReentrantReadWriteLock()
    private val chunks = HashMap<Long, Entry>()

    fun replace(sourcePk: Long, vectors: Map<Long, FloatArray>) = lock.write {
        chunks.values.removeIf { it.sourcePk == sourcePk }
        vectors.forEach { (id, v) -> chunks[id] = Entry(sourcePk, normalize(v)) }
    }

    fun remove(sourcePks: Collection<Long>) = lock.write {
        val set = sourcePks.toSet()
        chunks.values.removeIf { it.sourcePk in set }
    }

    fun clear() = lock.write { chunks.clear() }

    /** 모든 조각의 (조각 id, 대상 pk, 코사인), 높은 순 */
    fun rank(query: FloatArray): List<Triple<Long, Long, Double>> {
        val q = normalize(query)
        return lock.read {
            chunks.map { (id, e) -> Triple(id, e.sourcePk, dot(q, e.vector)) }
        }.sortedByDescending { it.third }
    }

    companion object {
        fun normalize(v: FloatArray): FloatArray {
            val norm = sqrt(v.fold(0.0) { s, x -> s + x * x })
            return if (norm == 0.0) v else FloatArray(v.size) { (v[it] / norm).toFloat() }
        }

        private fun dot(a: FloatArray, b: FloatArray): Double {
            var s = 0.0
            for (i in 0 until minOf(a.size, b.size)) s += a[i] * b[i]
            return s
        }

        fun toBytes(v: FloatArray): ByteArray = ByteBuffer.allocate(v.size * 4).order(ByteOrder.LITTLE_ENDIAN).apply { v.forEach(::putFloat) }.array()

        fun fromBytes(bytes: ByteArray): FloatArray {
            val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            return FloatArray(bytes.size / 4) { buffer.getFloat() }
        }
    }
}

/**
 * 색인 (REQ-B03, slices/12 5.2·11.5). AI 전용 daemon 스레드 하나가 서버를 켤 때와 30초마다 지금 자료의 (대상, 비교 키) 목록을
 * ai_source와 맞춘다: 새것·키가 바뀐 것은 대기(PENDING)로 두고 차례로 색인, 사라진 것은 지운다.
 * 질문을 처리하는 동안에는 쉰다(대상마다, 임베딩 16개 묶음 사이마다 확인). 실행기에 닿지 않으면 다음 회차에 다시 한다.
 * 쓰기는 짧은 트랜잭션으로 나누고 본문 추출·임베딩 동안에는 DB 연결을 쥐지 않는다
 */
@Component
class AiIndexer(
    private val settings: AiSettings,
    private val catalog: AiSourceCatalog,
    private val extractor: AiTextExtractor,
    private val ollama: OllamaClient,
    private val sourceRepository: AiSourceRepository,
    private val chunkRepository: AiChunkRepository,
    private val cache: AiVectorCache,
    private val queue: AiQueue,
    private val tx: TransactionTemplate,
    private val clock: Clock,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val signal = Object()
    private val runLock = Any()

    @Volatile
    private var thread: Thread? = null

    @Volatile
    private var stopped = false

    @EventListener(ApplicationReadyEvent::class)
    fun start() {
        if (!settings.active || thread != null) return
        thread = Thread(::loop, "ai-indexer").apply {
            isDaemon = true
            start()
        }
    }

    @PreDestroy
    fun stop() {
        stopped = true
        thread?.interrupt()
    }

    /** 다음 회차를 바로 시작하게 깨운다 (다시 색인) */
    fun wake() = synchronized(signal) { signal.notifyAll() }

    private fun loop() {
        try {
            loadCache()
        } catch (e: Exception) {
            log.warn("AI 색인 캐시를 읽지 못했습니다", e)
        }
        while (!stopped) {
            try {
                runOnce()
            } catch (e: InterruptedException) {
                return
            } catch (e: Exception) {
                log.warn("AI 색인 회차가 실패했습니다", e)
            }
            try {
                synchronized(signal) { signal.wait(settings.indexIntervalSeconds * 1000) }
            } catch (e: InterruptedException) {
                return
            }
        }
    }

    /** DB의 조각 벡터를 캐시에 올린다 */
    fun loadCache() {
        val vectors = tx.execute { chunkRepository.findAllVectors().map { Triple(it.id, it.sourcePk, AiVectorCache.fromBytes(it.embedding)) } }.orEmpty()
        cache.clear()
        vectors.groupBy { it.second }.forEach { (pk, list) -> cache.replace(pk, list.associate { it.first to it.third }) }
    }

    /** 한 회차: (질문 처리가 끝나기를 기다려) 맞추기 → 대기 대상 색인. 시험은 이 메서드를 바로 부른다 */
    fun runOnce() = synchronized(runLock) {
        waitIdle()
        reconcile()
        indexPending()
    }

    /**
     * 대기 대상을 차례로 색인한다(맞추기 없이). 시험은 맞추기와 색인 사이에 자료를 바꿔 보려고 바로 부른다.
     * 실행기에 닿지 않을 때만 이 회차를 멈춘다. 한 대상만 실패하면 그 대상을 건너뛰고 뒤의 대상을 한다(뒤의 자료가 묶이지 않게, REQ-B03)
     */
    fun indexPending() = synchronized(runLock) {
        for (source in tx.execute { sourceRepository.findByStatusOrderByIdAsc(AiIndexStatuses.PENDING) }.orEmpty()) {
            if (stopped) return
            waitIdle()
            if (!index(source)) return // 실행기에 닿지 않음: 다음 회차에
        }
    }

    /**
     * 지금 자료와 ai_source를 맞춘다. 맞춘 뒤에는 지금 자료의 대상마다 ai_source가 있으므로 "읽는 중 N건"은 대기(PENDING) 줄만 센다
     * (질문마다 대상 목록을 다시 만들지 않는다, slices/12 12.5)
     */
    fun reconcile() = synchronized(runLock) {
        val removed = tx.execute {
            val wanted = catalog.wanted().associateBy { it.key }
            val existing = sourceRepository.findAll()
            val gone = existing.filter { it.key !in wanted }.mapNotNull { it.id }
            if (gone.isNotEmpty()) sourceRepository.deleteByIds(gone)
            val byKey = existing.associateBy { it.key }
            val changed = mutableListOf<Long>()
            wanted.values.forEach { w ->
                val row = byKey[w.key]
                if (row == null) {
                    sourceRepository.save(AiSource(w.key.type, w.key.id, w.key.variant, w.hash, AiIndexStatuses.PENDING, w.title.take(255)).also { it.shareKey = w.shareKey })
                } else if (row.contentHash != w.hash) {
                    row.contentHash = w.hash
                    row.status = AiIndexStatuses.PENDING
                    row.title = w.title.take(255)
                    row.shareKey = w.shareKey
                    row.error = null
                    changed += checkNotNull(row.id)
                } else if (row.title != w.title.take(255)) {
                    row.title = w.title.take(255)
                }
            }
            if (changed.isNotEmpty()) chunkRepository.deleteBySourcePks(changed)
            gone + changed
        }.orEmpty()
        if (removed.isNotEmpty()) cache.remove(removed)
    }

    /** 대상 하나를 색인한다. 실행기에 닿지 않으면 거짓(대기로 남김) */
    private fun index(source: AiSource): Boolean {
        val pk = checkNotNull(source.id)
        val content = try {
            tx.execute { catalog.content(source.key) } ?: return true // 사라짐: 다음 맞추기가 지운다
        } catch (e: Exception) {
            // 자료를 읽지 못함(일시 오류일 수 있음): 대기로 두고(다음 회차에 다시) 뒤의 대상을 한다
            log.warn("AI 색인 대상을 읽지 못했습니다: {}", source.key, e)
            return true
        }
        val units = try {
            content.units ?: extractor.extract(checkNotNull(content.extension), checkNotNull(content.bytes))
        } catch (e: Exception) {
            finish(pk, source.contentHash, content, AiIndexStatuses.FAILED, emptyList(), (e as? ExtractFailedException)?.message ?: "본문을 읽지 못했습니다.")
            return true
        }
        val chunks = TextChunker.chunks(units)
        if (chunks.isEmpty()) {
            finish(pk, source.contentHash, content, AiIndexStatuses.NO_TEXT, emptyList(), null)
            return true
        }
        val vectors = mutableListOf<FloatArray>()
        try {
            chunks.chunked(EMBED_BATCH).forEach { batch ->
                waitIdle()
                vectors += ollama.embed(batch.map { it.text })
            }
        } catch (e: AiRejectedException) {
            // 실행기가 이 글만 오류로 돌려줌: 대기로 두고(다음 회차에 다시) 뒤의 대상을 한다
            log.warn("AI 실행기가 색인 대상을 처리하지 못했습니다: {} ({})", source.key, e.message)
            return true
        } catch (e: AiUnavailableException) {
            log.info("AI 실행기에 닿지 않아 색인을 다음 회차로 미룹니다")
            return false
        }
        finish(pk, source.contentHash, content, AiIndexStatuses.DONE, chunks.zip(vectors), null)
        return true
    }

    /**
     * 결과 저장. 그사이 맞추기가 대상을 바꿨거나(비교 키가 고른 때와 다름) 사라졌으면 버린다.
     * 비교 키·공유 항목은 맞추기 때 값이 아니라 이 내용을 만든 자료의 값(content)을 저장한다: 맞추기 뒤 색인 전에 공유 항목 등이 바뀌면
     * 다음 맞추기가 지금 값과 달라 다시 색인하고, 질문 때는 저장된 공유 항목이 지금과 달라 그 조각을 쓰지 않는다 (slices/12 12.5)
     */
    private fun finish(pk: Long, pickedHash: String, content: AiContent, status: String, chunks: List<Pair<TextUnit, FloatArray>>, error: String?) {
        val saved = tx.execute {
            val row = sourceRepository.findById(pk).orElse(null)
            if (row == null || row.contentHash != pickedHash || row.status != AiIndexStatuses.PENDING) return@execute null
            row.contentHash = content.hash
            row.shareKey = content.shareKey
            chunkRepository.deleteBySourcePks(listOf(pk))
            val rows = chunks.mapIndexed { i, (unit, vector) -> AiChunk(pk, i, unit.locator?.take(50), unit.text, AiVectorCache.toBytes(vector)) }
            val ids = chunkRepository.saveAll(rows).map { checkNotNull(it.id) }
            row.status = status
            row.error = error?.take(200)
            row.indexedAt = LocalDateTime.now(clock)
            ids.zip(chunks.map { it.second }).toMap()
        } ?: return
        cache.replace(pk, saved)
    }

    /** 질문을 처리하는 동안 쉰다 (REQ-B03) */
    private fun waitIdle() {
        while (queue.processing && !stopped) Thread.sleep(200)
    }

    /**
     * 다시 색인 (REQ-B03): 색인에 실패한(FAILED) 파일만 대기로 두고 색인 스레드를 깨운다. 대기·완료·글 없음이면(아직 대상이 없으면 대기)
     * 409 AI_REINDEX_NOT_FAILED (화면은 실패한 파일에만 단추를 보인다, slices/12 12.5)
     */
    fun reindexFile(fileId: Long) {
        val pk = tx.execute {
            val row = sourceRepository.findBySourceTypeAndSourceIdAndVariant(AiSourceTypes.FILE, fileId, AiVariants.NONE)
            if (row == null || row.status != AiIndexStatuses.FAILED) throw conflict("AI_REINDEX_NOT_FAILED", "색인에 실패한 파일만 다시 색인할 수 있습니다.")
            row.status = AiIndexStatuses.PENDING
            row.error = null
            chunkRepository.deleteBySourcePks(listOf(checkNotNull(row.id)))
            row.id
        }
        if (pk != null) cache.remove(listOf(pk))
        wake()
    }

    companion object {
        const val EMBED_BATCH = 16
    }
}

/**
 * 문서함(docbox)이 쓰는 색인 상태·다시 색인 (REQ-B03, slices/12 4.1·5.2). AI가 꺼져 있으면 statusOf는 빈 값, reindex는 404
 */
@Service
class AiIndexStatusService(
    private val settings: AiSettings,
    private val sourceRepository: AiSourceRepository,
    private val indexer: AiIndexer,
    private val tx: TransactionTemplate,
) {
    /** 파일마다 색인 상태(PENDING·DONE·NO_TEXT·FAILED). ai_source가 아직 없으면 PENDING */
    fun statusOf(fileIds: Collection<Long>): Map<Long, String> {
        if (!settings.active || fileIds.isEmpty()) return emptyMap()
        val rows = tx.execute { sourceRepository.findBySourceTypeAndSourceIdIn(AiSourceTypes.FILE, fileIds.toSet()) }.orEmpty().associateBy { it.sourceId }
        return fileIds.associateWith { rows[it]?.status ?: AiIndexStatuses.PENDING }
    }

    /** AI가 꺼져 있으면 404 (문서함의 다시 색인이 권한을 보기 전에 부름) */
    fun requireActive() = settings.requireActive()

    /** 다시 색인. 권한(문서함 부서원)은 부르는 쪽이 본다 */
    fun reindex(fileId: Long) {
        settings.requireActive()
        indexer.reindexFile(fileId)
    }
}
