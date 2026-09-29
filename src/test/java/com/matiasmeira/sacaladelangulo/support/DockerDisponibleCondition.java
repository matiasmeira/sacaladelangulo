package com.matiasmeira.sacaladelangulo.support;

import org.junit.jupiter.api.extension.ConditionEvaluationResult;
import org.junit.jupiter.api.extension.ExecutionCondition;
import org.junit.jupiter.api.extension.ExtensionContext;
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
 * La decisión es {@link #decidir(boolean, boolean)}, una función pura. Las fuentes reales
 * (Docker y la variable CI) se inyectan por constructor para poder reemplazarlas en tests.
 */
public class DockerDisponibleCondition implements ExecutionCondition {

    static final String MENSAJE_SIN_DOCKER =
            "Docker no disponible: se saltean los tests de integración con Postgres (corren en el CI)";

    private final BooleanSupplier dockerDisponible;
    private final Supplier<String> valorCi;

    /** Constructor que usa JUnit: fuentes reales. */
    public DockerDisponibleCondition() {
        this(() -> DockerClientFactory.instance().isDockerAvailable(), () -> System.getenv("CI"));
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
