# ============================================================
# unogroup-app/Dockerfile
# Build multi-stage: compila con Maven, corre sobre JRE 21 slim.
# Mismo patrón que orquestador-app/Dockerfile, cambiando el artifactId.
# ============================================================
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build

# Sin pom.xml padre que copiar ni reactor Maven multi-módulo
# (ensambles-parent eliminado, doc Implementación §1.1) — este
# repositorio contiene un único proyecto Maven standalone, así que el
# build es el de cualquier proyecto Spring Boot de un solo módulo: sin
# -pl (build de un módulo dentro de un reactor) ni -am (also-make).
COPY pom.xml .
RUN mvn -q dependency:go-offline

COPY src src
RUN mvn -q package -DskipTests

FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
RUN addgroup -S ensambles && adduser -S ensambles -G ensambles
USER ensambles

COPY --from=build /build/target/unogroup-app-*.jar app.jar

EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
