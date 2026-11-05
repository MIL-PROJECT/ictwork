package kr.ictwork.common.web

import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.core.JsonToken
import com.fasterxml.jackson.databind.DeserializationContext
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.Module
import com.fasterxml.jackson.databind.cfg.CoercionAction
import com.fasterxml.jackson.databind.cfg.CoercionInputShape
import com.fasterxml.jackson.databind.deser.std.StdScalarDeserializer
import com.fasterxml.jackson.databind.module.SimpleModule
import com.fasterxml.jackson.databind.type.LogicalType
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * JSON 요청의 문자열 칸에는 문자열만 받는다. `{"currentPassword":123}`처럼 숫자·참거짓을 문자열로 바꿔 주지 않고 400 REQUEST_INVALID (DEC-054, 05 3장).
 * Jackson 2.13의 기본 문자열 변환기는 바꿔 주기 설정을 보지 않아 변환기를 직접 둔다. 스프링 부트가 이 모듈을 ObjectMapper에 붙인다.
 * 숫자 칸도 숫자만 받는다: `"5"`(문자열)나 `1.9`(소수 → 정수)는 400 (DEC-063)
 */
@Configuration
class StrictJsonConfig {
    @Bean
    fun strictStringModule(): Module = SimpleModule("StrictString").addDeserializer(String::class.java, StrictStringDeserializer())

    @Bean
    fun strictNumberCustomizer() = Jackson2ObjectMapperBuilderCustomizer { builder ->
        builder.featuresToDisable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
        builder.postConfigurer { mapper ->
            listOf(LogicalType.Integer, LogicalType.Float).forEach {
                mapper.coercionConfigFor(it).setCoercion(CoercionInputShape.String, CoercionAction.Fail)
            }
        }
    }
}

class StrictStringDeserializer : StdScalarDeserializer<String>(String::class.java) {
    override fun deserialize(p: JsonParser, ctxt: DeserializationContext): String =
        if (p.hasToken(JsonToken.VALUE_STRING)) p.text else ctxt.handleUnexpectedToken(String::class.java, p) as String
}
