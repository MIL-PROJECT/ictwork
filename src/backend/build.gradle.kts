import org.apache.tools.ant.taskdefs.condition.Os

plugins {
    id("org.springframework.boot") version "2.7.18"
    id("io.spring.dependency-management") version "1.0.15.RELEASE"
    kotlin("jvm") version "2.0.21"
    kotlin("plugin.spring") version "2.0.21"
    kotlin("plugin.jpa") version "2.0.21"
    war
}

group = "kr.ictwork"
version = "0.0.1"

java {
    sourceCompatibility = JavaVersion.VERSION_17
}

repositories {
    mavenCentral()
}

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.flywaydb:flyway-core")
    implementation("org.flywaydb:flyway-mysql")
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin")
    implementation("org.jetbrains.kotlin:kotlin-reflect")
    // 업무 현황 엑셀 출력 (xlsx 쓰기 전용, Apache 2.0, DEC-075·T-29)
    implementation("org.dhatim:fastexcel:0.20.2")
    // 장비 엑셀 일괄 등록 읽기 (같은 프로젝트의 읽기 모듈, Apache 2.0, DEC-079·slices/10 7.3)
    implementation("org.dhatim:fastexcel-reader:0.20.2")
    // AI 질의응답 색인의 PDF 본문 읽기 (Apache 2.0, DEC-098·slices/12 8장)
    // commons-logging은 스프링의 spring-jcl이 같은 API를 주므로 빼 겹치지 않게 한다
    implementation("org.apache.pdfbox:pdfbox:3.0.8") { exclude(group = "commons-logging", module = "commons-logging") }
    // AI 질의응답 색인의 hwp 본문 읽기 (Apache 2.0, 의존성 없음, DEC-098·slices/12 8장)
    implementation("kr.dogfoot:hwplib:1.1.11")
    runtimeOnly("com.mysql:mysql-connector-j")
    providedRuntime("org.springframework.boot:spring-boot-starter-tomcat")
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.security:spring-security-test")
}

kotlin {
    compilerOptions {
        freeCompilerArgs.add("-Xjsr305=strict")
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

allOpen {
    annotation("javax.persistence.Entity")
    annotation("javax.persistence.MappedSuperclass")
    annotation("javax.persistence.Embeddable")
}

tasks.withType<Test> {
    useJUnitPlatform()
}

// 프론트(src/frontend)를 빌드해 WAR의 static/에 넣는다 (A0 4.5)
val frontendDir = layout.projectDirectory.dir("../frontend")
val pnpm = if (Os.isFamily(Os.FAMILY_WINDOWS)) listOf("cmd", "/c", "pnpm") else listOf("pnpm")

val installFrontend by tasks.registering(Exec::class) {
    workingDir = frontendDir.asFile
    commandLine(pnpm + listOf("install", "--frozen-lockfile"))
}

val buildFrontend by tasks.registering(Exec::class) {
    dependsOn(installFrontend)
    workingDir = frontendDir.asFile
    commandLine(pnpm + listOf("build"))
}

tasks.bootWar {
    dependsOn(buildFrontend)
    from(frontendDir.dir("dist")) {
        into("WEB-INF/classes/static")
    }
}

tasks.war {
    enabled = false
}
