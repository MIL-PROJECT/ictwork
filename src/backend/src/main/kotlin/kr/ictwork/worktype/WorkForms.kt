package kr.ictwork.worktype

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import kr.ictwork.form.FormDefinition
import org.springframework.stereotype.Component

/** 양식 판 정의 읽기·쓰기 (V18 work_form_version.definition, JSON 글) */
@Component
class WorkForms(private val versionRepository: WorkFormVersionRepository, private val objectMapper: ObjectMapper) {
    fun parse(json: String): FormDefinition = objectMapper.readValue(json)

    fun write(definition: FormDefinition): String = objectMapper.writeValueAsString(definition)

    /** 판 id → 정의 (상세 응답의 forms: 같은 판은 한 번만) */
    fun definitions(ids: Collection<Long?>): Map<Long, FormDefinition> {
        val wanted = ids.filterNotNull().toSet()
        if (wanted.isEmpty()) return emptyMap()
        return versionRepository.findAllById(wanted).associate { checkNotNull(it.id) to parse(it.definition) }
    }
}
