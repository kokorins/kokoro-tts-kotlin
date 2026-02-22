# Data stage — downloads model and lexicon files (cached unless URLs change)
FROM alpine:3 AS data
RUN apk add --no-cache curl bash
COPY scripts/download-data.sh /tmp/download-data.sh
RUN bash /tmp/download-data.sh /data

# Build stage
FROM amazoncorretto:25-jdk AS build
WORKDIR /build

# Copy Gradle wrapper and build files for layer caching
COPY gradlew settings.gradle.kts build.gradle.kts gradle.properties ./
COPY gradle/ gradle/
COPY app/build.gradle.kts app/build.gradle.kts
COPY core/build.gradle.kts core/build.gradle.kts
COPY domain/build.gradle.kts domain/build.gradle.kts
COPY infra/build.gradle.kts infra/build.gradle.kts
COPY lambda/build.gradle.kts lambda/build.gradle.kts
# findutils needed by Gradle's dependency resolution on AL2023-minimal
RUN chmod +x gradlew && dnf install -y findutils && dnf clean all

# Download dependencies (cached unless build files change)
RUN ./gradlew dependencies --no-daemon

# Copy source (dependency order: domain → core → infra → app)
COPY domain/ domain/
COPY core/ core/
COPY infra/ infra/
COPY app/ app/

# Build fat JAR
RUN ./gradlew :app:buildFatJar --no-daemon

# Run stage — full JRE (java.desktop module required by jump3r MP3 encoder)
FROM amazoncorretto:25
WORKDIR /app

# Create non-root user and app directory in a single layer
RUN dnf install -y shadow-utils && dnf clean all \
    && groupadd --system tts && useradd --system --gid tts tts \
    && chown -R tts:tts /app

# Copy fat JAR from build stage and model/lexicon data from download stage
COPY --from=build --chown=tts:tts /build/app/build/libs/app-all.jar app.jar
COPY --from=data --chown=tts:tts /data/ data/
COPY --chown=tts:tts data/lexicon_fixes.json data/

# Drop to non-root user
USER tts

EXPOSE 8080

# TCP check — no curl needed; Ktor only binds port after full startup
HEALTHCHECK --interval=10s --timeout=3s --start-period=30s --retries=3 \
    CMD bash -c '< /dev/tcp/localhost/8080' || exit 1

ENTRYPOINT ["java", \
    "-Xmx3g", \
    "-XX:+ExitOnOutOfMemoryError", \
    "-jar", "app.jar"]
