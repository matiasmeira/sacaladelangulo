package com.matiasmeira.sacaladelangulo.auth.service;

/**
 * Publicado por DegradacionPlanService cuando un usuario pasa de TRIAL a FREE por
 * vencimiento de la prueba gratuita. Lleva solo el ID porque el listener corre @Async en un
 * hilo/persistence-context distinto al de la transacción que lo publica (mismo motivo que
 * AvisoFinPruebaEvent).
 */
public record PruebaVencidaEvent(Long usuarioId) {
}
