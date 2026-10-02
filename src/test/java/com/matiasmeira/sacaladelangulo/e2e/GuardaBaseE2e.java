package com.matiasmeira.sacaladelangulo.e2e;

final class GuardaBaseE2e {

    private GuardaBaseE2e() {
    }

    /** El perfil e2e borra la base al arrancar: sólo se permite contra una base *_e2e. */
    static void verificar(String jdbcUrl) {
        String base = jdbcUrl == null ? "" : jdbcUrl.replaceFirst("\\?.*$", "");
        base = base.substring(base.lastIndexOf('/') + 1);
        if (!base.endsWith("_e2e")) {
            throw new IllegalStateException(
                    "El perfil e2e borra la base al arrancar y sólo corre contra una base terminada en _e2e (se recibió: " + base + ")");
        }
    }
}
