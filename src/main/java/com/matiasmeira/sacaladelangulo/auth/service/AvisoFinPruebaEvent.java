package com.matiasmeira.sacaladelangulo.auth.service;

/**
 * Publicado por AvisoFinPruebaService cuando un usuario entra en la ventana de aviso de
 * fin de prueba gratuita (7, 3 o 1 día restante). Lleva solo el ID porque el listener corre
 * @Async en un hilo/persistence-context distinto al de la transacción que lo publica (ver
 * AvisoFinPruebaEmailListener, mismo motivo que ReservaConfirmadaEvent).
 */
public record AvisoFinPruebaEvent(Long usuarioId, int diasRestantes) {
}
