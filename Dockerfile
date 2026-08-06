# =============================================================================
# Multi-stage build para sacaladelangulo (Spring Boot 3.5, Java 21).
#   Stage 1 (build):   Maven + JDK 21 -> jar ejecutable, con las capas extraídas.
#   Stage 2 (runtime): JRE 21 slim, usuario NO root, JVM container-aware.
# Versiones de imagen pinneadas (sin :latest) para builds reproducibles.
# =============================================================================

# ---- Stage 1: build ---------------------------------------------------------
FROM maven:3.9.9-eclipse-temurin-21 AS build
WORKDIR /build

# Cachear dependencias: se copia primero solo el pom y se bajan las dependencias.
# Mientras el pom no cambie, Docker reutiliza esta capa y no rebaja Maven Central
# en cada build de código.
COPY pom.xml ./
RUN mvn -B -q dependency:go-offline

# Ahora sí el código fuente. Se saltean los tests en la imagen (se corren en CI /
# local): la suite con Testcontainers necesita un daemon de Docker, que no está
# garantizado dentro del build de la imagen.
COPY src ./src
RUN mvn -B -q clean package -DskipTests

# Extraer las capas del jar (dependencies / spring-boot-loader /
# snapshot-dependencies / application) para copiarlas por separado al runtime y
# maximizar el cacheo: las dependencias (la capa pesada) casi no cambian entre
# deploys, así que su capa de imagen se reutiliza.
RUN cp target/*.jar app.jar \
    && java -Djarmode=layertools -jar app.jar extract --destination extracted

# ---- Stage 2: runtime -------------------------------------------------------
FROM eclipse-temurin:21-jre-jammy AS runtime
WORKDIR /app

# Usuario sin privilegios (no root): si algo compromete el proceso, no corre como
# root dentro del contenedor.
RUN groupadd --system --gid 1001 appgrp \
    && useradd --system --uid 1001 --gid appgrp --home /app --shell /usr/sbin/nologin appuser

# Copiar las capas en orden de "menos cambia" -> "más cambia" para aprovechar el
# cache de capas de Docker.
COPY --from=build --chown=appuser:appgrp /build/extracted/dependencies/ ./
COPY --from=build --chown=appuser:appgrp /build/extracted/spring-boot-loader/ ./
COPY --from=build --chown=appuser:appgrp /build/extracted/snapshot-dependencies/ ./
COPY --from=build --chown=appuser:appgrp /build/extracted/application/ ./

USER appuser

# Perfil de producción por defecto (overrideable con SPRING_PROFILES_ACTIVE).
ENV SPRING_PROFILES_ACTIVE=prod

# JVM container-aware: SIN -Xmx fijo. MaxRAMPercentage respeta el límite de RAM
# del contenedor (poco en los tiers chicos). Ajustar el porcentaje según el plan.
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75.0 -XX:InitialRAMPercentage=50.0"

# Documentativo: la app escucha en el puerto que le pasa la plataforma vía PORT
# (server.port=${PORT:8080} en application-prod.properties); acá se declara el
# default por claridad.
EXPOSE 8080

# Exec form (JSON array) para que java sea PID 1 y reciba el SIGTERM del redeploy
# -> dispara el graceful shutdown configurado en el perfil prod.
# JarLauncher arranca el jar explotado (paquete .launch. desde Spring Boot 3.2+).
ENTRYPOINT ["java", "org.springframework.boot.loader.launch.JarLauncher"]
