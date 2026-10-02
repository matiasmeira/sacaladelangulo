package com.matiasmeira.sacaladelangulo.support;

import org.junit.jupiter.api.extension.ConditionEvaluationResult;
import org.junit.jupiter.api.extension.ExecutionCondition;
import org.junit.jupiter.api.extension.ExtensionContext;
import ch.qos.logback.classic.Level;
import org.slf4j.LoggerFactory;
import org.testcontainers.DockerClientFactory;

import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * Saltea las clases que necesitan Postgres real (Testcontainers) cuando no hay Docker,
 * salvo en el CI.
 *
 * <ul>
 *   <li>Fuera del CI y sin Docker: la clase se saltea (disabled) con un mensaje claro.</li>
 *   <li>En el CI (variable de entorno CI=true): nunca se saltea y ni siquiera se consulta
 *       Docker. Si Docker falta, los tests fallan con el error natural, para que el job de
 *       integración no pase en verde sin correr nada.</li>
 * </ul>
 *
 * El sondeo de Docker sin Docker deja una línea ERROR de Testcontainers ("Could not find a
 * valid Docker environment") en el log aunque la clase se saltee a propósito: se acalla sólo
 * durante el sondeo ({@link #sinRuidoDeSondeo(BooleanSupplier)}). En el CI no se sondea, así
 * que el error natural de Testcontainers (si Docker faltara) se sigue viendo completo.
 *
 * La decisión es {@link #decidir(boolean, boolean)}, una función pura. Las fuentes reales
 * (Docker y la variable CI) se inyectan por constructor para poder reemplazarlas en tests.
 */
public class DockerDisponibleCondition implements ExecutionCondition {

    static final String MENSAJE_SIN_DOCKER =
            "Docker no disponible: se saltean los tests de integración con Postgres (corren en el CI)";

    /** Logger de Testcontainers que emite el ERROR "Could not find a valid Docker environment". */
    static final String LOGGER_SONDEO = "org.testcontainers.dockerclient.DockerClientProviderStrategy";

    private final BooleanSupplier dockerDisponible;
    private final Supplier<String> valorCi;

    /** Constructor que usa JUnit: fuentes reales. */
    public DockerDisponibleCondition() {
        this(() -> sinRuidoDeSondeo(() -> DockerClientFactory.instance().isDockerAvailable()),
                () -> System.getenv("CI"));
    }

    DockerDisponibleCondition(BooleanSupplier dockerDisponible, Supplier<String> valorCi) {
        this.dockerDisponible = dockerDisponible;
        this.valorCi = valorCi;
    }

    @Override
    public ConditionEvaluationResult evaluateExecutionCondition(ExtensionContext context) {
        if (esCi(valorCi.get())) {
            return decidir(false, true);
        }
        return decidir(dockerDisponible.getAsBoolean(), false);
    }

    /**
     * Ejecuta el sondeo con el logger de Testcontainers en OFF y restaura su nivel después
     * (aunque el sondeo tire). Si el logging no es Logback, no hace nada.
     */
    static boolean sinRuidoDeSondeo(BooleanSupplier sondeo) {
        if (!(LoggerFactory.getLogger(LOGGER_SONDEO) instanceof ch.qos.logback.classic.Logger logger)) {
            return sondeo.getAsBoolean();
        }
        Level nivelOriginal = logger.getLevel();
        logger.setLevel(Level.OFF);
        try {
            return sondeo.getAsBoolean();
        } finally {
            logger.setLevel(nivelOriginal);
        }
    }

    static boolean esCi(String valor) {
        return "true".equals(valor);
    }

    static ConditionEvaluationResult decidir(boolean dockerDisponible, boolean esCi) {
        if (esCi || dockerDisponible) {
            return ConditionEvaluationResult.enabled("Tests de integración con Postgres habilitados");
        }
        return ConditionEvaluationResult.disabled(MENSAJE_SIN_DOCKER);
    }
}
