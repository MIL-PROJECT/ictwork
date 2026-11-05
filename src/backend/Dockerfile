# Render 배포용 이미지 (DEC-066). 빌드 컨텍스트는 저장소 루트, Render의 Dockerfile Path는 src/backend/Dockerfile
# DB 접속 정보·초기 비밀번호는 이미지에 넣지 않고 Render 환경변수로 받는다

# 1) 빌드: 개발기준선과 같은 JDK 17·Node 24·pnpm 12로 bootWar (프론트 빌드 포함)
FROM node:24-bookworm-slim AS build
ENV JAVA_HOME=/opt/java/openjdk
ENV PATH="${JAVA_HOME}/bin:${PATH}"
COPY --from=eclipse-temurin:17-jdk /opt/java/openjdk /opt/java/openjdk
RUN npm install -g pnpm@12.10.1
WORKDIR /app
COPY src/frontend src/frontend
COPY src/backend src/backend
WORKDIR /app/src/backend
RUN sh gradlew --no-daemon bootWar

# 2) 실행: JRE 17로 WAR 실행
FROM eclipse-temurin:17-jre
WORKDIR /app
COPY --from=build /app/src/backend/build/libs/ictwork-0.0.1.war app.war
# 무료 인스턴스 메모리 512MB에 맞춘 JVM 설정 (재검토 F7-2): 힙 50%(256MB)에 메타스페이스·코드 캐시·직접 메모리 상한을 두고,
# JIT는 C1만 써서(TieredStopAtLevel=1) 컴파일러·코드 캐시 메모리를 줄인다. 같은 옵션·UTC로 로컬 측정: 시연 흐름·업무 현황 1년 엑셀·장비 엑셀·10MB 첨부(동시 6개)·장비 5,000대 일괄 등록에서 프로세스 최대 440MB(윈도 Working Set, 고치기 전 같은 흐름 624MB)
# MALLOC_ARENA_MAX: 리눅스 glibc가 스레드마다 메모리 영역(arena)을 늘리지 않게 한다
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=50 -XX:MaxMetaspaceSize=128m -XX:ReservedCodeCacheSize=48m -XX:TieredStopAtLevel=1 -XX:MaxDirectMemorySize=32m -XX:+UseSerialGC -Xss512k"
ENV MALLOC_ARENA_MAX=2
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.war"]
