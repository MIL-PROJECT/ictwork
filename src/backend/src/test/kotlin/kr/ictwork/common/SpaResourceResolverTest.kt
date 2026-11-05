package kr.ictwork.common

import kr.ictwork.common.web.SpaResourceResolver
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.springframework.core.io.FileSystemResource
import java.nio.file.Files
import java.nio.file.Path

/** 화면 경로는 index.html, 없는 정적 파일(빌드 파일 assets/·구성도 글꼴·API)은 index.html로 넘기지 않는다 (07 3.1) */
class SpaResourceResolverTest {
    @Test
    fun `없는 assets 파일은 index html이 아니라 없음이고 화면 경로는 index html`(@TempDir dir: Path) {
        Files.writeString(dir.resolve("index.html"), "<html></html>")
        Files.createDirectories(dir.resolve("assets"))
        Files.writeString(dir.resolve("assets/app.js"), "console.log(1)")
        val location = FileSystemResource(dir.toString() + "/")
        val resolver = SpaResourceResolver()

        assertThat(resolver.getResource("assets/app.js", location)?.filename).isEqualTo("app.js")
        assertThat(resolver.getResource("assets/nope.js", location)).isNull()
        assertThat(resolver.getResource("excalidraw-assets/nope.woff2", location)).isNull()
        assertThat(resolver.getResource("api/nope", location)).isNull()
        assertThat(resolver.getResource("department/equipment/12", location)?.filename).isEqualTo("index.html")
    }
}
