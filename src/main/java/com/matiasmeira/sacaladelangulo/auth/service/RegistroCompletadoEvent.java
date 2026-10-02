package com.matiasmeira.sacaladelangulo.auth.service;

import com.matiasmeira.sacaladelangulo.auth.model.Role;

/**
 * Se publica para disparar el email de bienvenida desacoplado de la transacción que lo origina
 * (ver RegistroVerificacionEmailListener). Hay dos orígenes: cuando un jugador o dueño termina el
 * registro (RegistroVerificacionService.completarRegistro) y cuando un jugador convierte su cuenta
 * en cuenta de dueño (UsuarioService.convertirEnDueno, con rol OWNER).
 */
public record RegistroCompletadoEvent(String email, String nombre, Role rol) {
}
