package com.matiasmeira.sacaladelangulo.support;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ConditionEvaluationResult;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DockerDisponibleConditionTest {

    private static ConditionEvaluationResult evaluar(boolean docker, String ci) {
        return new DockerDisponibleCondition(() -> docker, () -> ci).evaluateExecutionCondition(null);
    }

    @Test
    void dockerSiCiNo_habilita() {
        assertFalse(evaluar(true, null).isDisabled());
    }

    @Test
    void dockerNoCiNo_deshabilitaConMensaje() {
        ConditionEvaluationResult r = evaluar(false, null);
        assertTrue(r.isDisabled());
        assertEquals(DockerDisponibleCondition.MENSAJE_SIN_DOCKER, r.getReason().orElseThrow());
    }

    @Test
    void dockerNoCiSi_habilitaParaQueFalleNatural() {
        assertFalse(evaluar(false, "true").isDisabled());
    }

    @Test
    void dockerSiCiSi_habilita() {
        assertFalse(evaluar(true, "true").isDisabled());
    }

    @Test
    void ciConOtroValorCuentaComoNoCi() {
        assertTrue(evaluar(false, "false").isDisabled());
        assertTrue(evaluar(false, null).isDisabled());
        assertTrue(evaluar(false, "").isDisabled());
    }

    @Test
    void enCiNoConsultaDocker() {
        DockerDisponibleCondition condicion = new DockerDisponibleCondition(
                () -> { throw new AssertionError("no se debe consultar Docker en el CI"); },
                () -> "true");
        assertFalse(condicion.evaluateExecutionCondition(null).isDisabled());
    }
}
