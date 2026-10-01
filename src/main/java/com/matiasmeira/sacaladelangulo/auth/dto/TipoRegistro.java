package com.matiasmeira.sacaladelangulo.auth.dto;

import com.matiasmeira.sacaladelangulo.auth.model.Role;

/**
 * Tipo de cuenta que se está creando en el registro en 2 pasos (campo opcional "tipo" de
 * POST /auth/registro/iniciar). Un valor desconocido falla al deserializar y devuelve 400.
 * Solo puede elegir entre jugador y dueño: nunca ADMIN ni EMPLOYEE.
 */
public enum TipoRegistro {
    JUGADOR(Role.PLAYER),
    DUENO(Role.OWNER);

    private final Role rol;

    TipoRegistro(Role rol) {
        this.rol = rol;
    }

    public Role rol() {
        return rol;
    }

    /** null (campo ausente) equivale a JUGADOR. */
    public static Role rolDe(TipoRegistro tipo) {
        return tipo == null ? Role.PLAYER : tipo.rol;
    }
}
