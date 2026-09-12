# ---- build -----------------------------------------------------------------
FROM eclipse-temurin:21-jdk-alpine AS build
WORKDIR /workspace

# Camada de dependências: só é reconstruída quando os arquivos de build mudam.
COPY gradlew ./
COPY gradle gradle
COPY settings.gradle.kts build.gradle.kts ./
RUN ./gradlew --no-daemon dependencies --quiet

COPY src src
# Os testes de integração exigem Docker e rodam no pipeline, não dentro da imagem.
RUN ./gradlew --no-daemon bootJar -x test

# ---- runtime ---------------------------------------------------------------
FROM eclipse-temurin:21-jre-alpine AS runtime
WORKDIR /app

# Processo sem privilégios: um container comprometido não roda como root.
RUN addgroup -S flashbooking && adduser -S flashbooking -G flashbooking
COPY --from=build /workspace/build/libs/*.jar app.jar
RUN chown flashbooking:flashbooking app.jar
USER flashbooking

EXPOSE 8080

# MaxRAMPercentage: a JVM enxerga o limite do container e usa uma fração dele, em vez do
# padrão conservador que desperdiça memória reservada.
# ExitOnOutOfMemoryError: com OOM, morrer é melhor do que seguir degradado — o orquestrador
# substitui a instância, e uma JVM em thrashing de GC responde devagar sem nunca falhar o
# health check.
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError -XX:+HeapDumpOnOutOfMemoryError"

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
