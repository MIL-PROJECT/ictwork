package kr.ictwork.common.web

import org.springframework.context.annotation.Configuration
import org.springframework.core.io.Resource
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer
import org.springframework.web.servlet.resource.PathResourceResolver

/**
 * 정적 파일이 있으면 그 파일, 없으면 index.html (화면 경로 새로고침 대응, 07 3.1). /api/ 경로는 넘기지 않는다.
 * 구성도 글꼴(excalidraw-assets/)과 빌드 파일(assets/)도 넘기지 않아 없는 파일은 404가 된다(실패를 숨기지 않게, slices/8a 7장).
 * 화면 경로는 assets/로 시작하지 않는다(App.tsx)
 */
@Configuration
class SpaWebConfig : WebMvcConfigurer {
    override fun addResourceHandlers(registry: ResourceHandlerRegistry) {
        registry.addResourceHandler("/**")
            .addResourceLocations("classpath:/static/")
            .resourceChain(true)
            .addResolver(SpaResourceResolver())
    }
}

class SpaResourceResolver : PathResourceResolver() {
    public override fun getResource(resourcePath: String, location: Resource): Resource? {
        val requested = location.createRelative(resourcePath)
        if (requested.exists() && requested.isReadable) return requested
        if (NOT_SPA.any(resourcePath::startsWith)) return null
        return location.createRelative("index.html").takeIf { it.exists() }
    }

    private companion object {
        val NOT_SPA = listOf("api/", "excalidraw-assets/", "assets/")
    }
}
