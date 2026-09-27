package com.matiasmeira.sacaladelangulo.auth.service;

import java.util.List;

/**
 * Publicado por DegradacionPlanService cuando un usuario pasa de TRIAL a FREE por
 * vencimiento de la prueba gratuita. Lleva el ID (el listener corre @Async en un
 * hilo/persistence-context distinto al de la transacción que lo publica, mismo motivo que
 * AvisoFinPruebaEvent) y, además, los nombres de las canchas cuya seña se ajustó al mínimo de
 * FREE en esa misma transacción (ver DegradacionPlanService.ajustarSenaAMinimoFree): a
 * diferencia del usuario, esas canchas no se pueden "re-derivar" desde la base al momento de
 * enviar el mail -- ya quedaron en su valor ajustado, así que la única forma de saber cuáles
 * cambiaron es que el evento las lleve.
 */
public record PruebaVencidaEvent(Long usuarioId, List<String> canchasAjustadas) {
}
