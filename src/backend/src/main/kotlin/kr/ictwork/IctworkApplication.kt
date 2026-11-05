package kr.ictwork

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.builder.SpringApplicationBuilder
import org.springframework.boot.runApplication
import org.springframework.boot.web.servlet.support.SpringBootServletInitializer
import java.util.TimeZone

@SpringBootApplication
class IctworkApplication : SpringBootServletInitializer() {
    override fun configure(builder: SpringApplicationBuilder): SpringApplicationBuilder =
        builder.sources(IctworkApplication::class.java)
}

fun main(args: Array<String>) {
    // 기준 시간대 Asia/Seoul (07 3.4)
    TimeZone.setDefault(TimeZone.getTimeZone("Asia/Seoul"))
    runApplication<IctworkApplication>(*args)
}
