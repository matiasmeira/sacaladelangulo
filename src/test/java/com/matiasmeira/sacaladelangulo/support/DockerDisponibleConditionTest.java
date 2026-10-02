package com.matiasmeira.sacaladelangulo.support;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ConditionEvaluationResult;

import org.slf4j.LoggerFactory;

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

    @Test
    void sondeo_silenciaElErrorDeTestcontainersYRestauraElNivel() {
        Logger sondeo = (Logger) LoggerFactory.getLogger(DockerDisponibleCondition.LOGGER_SONDEO);
        Level nivelOriginal = sondeo.getLevel();
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        sondeo.addAppender(appender);
        try {
            boolean resultado = DockerDisponibleCondition.sinRuidoDeSondeo(() -> {
                sondeo.error("Could not find a valid Docker environment.");
                return false;
            });

            assertFalse(resultado);
            assertTrue(appender.list.isEmpty(), "el ERROR del sondeo no debe llegar al log");
            assertEquals(nivelOriginal, sondeo.getLevel(), "el nivel original se restaura");
        } finally {
            sondeo.detachAppender(appender);
        }
    }

    @Test
    void sondeo_restauraElNivelAunqueElSondeoTire() {
        Logger sondeo = (Logger) LoggerFactory.getLogger(DockerDisponibleCondition.LOGGER_SONDEO);
        Level nivelOriginal = sondeo.getLevel();

        try {
            DockerDisponibleCondition.sinRuidoDeSondeo(() -> { throw new IllegalStateException("boom"); });
        } catch (IllegalStateException esperado) {
            // se propaga: sólo importa que el nivel quede restaurado
        }

        assertEquals(nivelOriginal, sondeo.getLevel());
    }
}
