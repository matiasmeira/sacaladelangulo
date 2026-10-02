package com.matiasmeira.sacaladelangulo.establecimiento.service;

import com.matiasmeira.sacaladelangulo.auth.model.Usuario;
import com.matiasmeira.sacaladelangulo.core.exception.EntityNotFoundException;
import com.matiasmeira.sacaladelangulo.establecimiento.model.Establecimiento;

import java.util.Optional;
import java.util.function.Consumer;

/**
 * Carga del establecimiento del path SIN oráculo de existencia: un id inexistente se autoriza contra un
 * establecimiento "fantasma" sin dueño ni empleados, así que AutorizacionEmpleadoService responde lo mismo
 * que ante un establecimiento ajeno (403, con el mensaje que le corresponde a cada chequeo). Sólo un ADMIN
 * (que pasa cualquier chequeo de dueño) llega a {@link #exigirExistente}, que le responde 404.
 *
 * <p>Uso corto, cuando no hace falta el usuario autenticado:
 * <pre>
 * Establecimiento e = EstablecimientoAutorizado.autorizar(establecimientoRepository.findById(id),
 *         est -&gt; autorizacionEmpleadoService.validarPropietarioOAdmin(est, email));
 * </pre>
 * Uso largo, siempre en este orden:
 * <pre>
 * Establecimiento e = EstablecimientoAutorizado.resolver(establecimientoRepository.findById(id));
 * autorizacionEmpleadoService.validarPropietarioOAdmin(e, email);
 * EstablecimientoAutorizado.exigirExistente(e);
 * </pre>
 */
public final class EstablecimientoAutorizado {

    private static final long ID_FANTASMA = -1L;

    private EstablecimientoAutorizado() {
    }

    public static Establecimiento autorizar(Optional<Establecimiento> encontrado, Consumer<Establecimiento> autorizacion) {
        Establecimiento establecimiento = resolver(encontrado);
        autorizacion.accept(establecimiento);
        exigirExistente(establecimiento);
        return establecimiento;
    }

    public static Establecimiento resolver(Optional<Establecimiento> encontrado) {
        return encontrado.orElseGet(EstablecimientoAutorizado::fantasma);
    }

    public static void exigirExistente(Establecimiento establecimiento) {
        if (esFantasma(establecimiento)) {
            throw new EntityNotFoundException("Establecimiento no encontrado");
        }
    }

    private static boolean esFantasma(Establecimiento establecimiento) {
        return establecimiento.getId() != null && establecimiento.getId() == ID_FANTASMA;
    }

    private static Establecimiento fantasma() {
        return Establecimiento.builder()
                .id(ID_FANTASMA)
                .dueno(Usuario.builder().id(ID_FANTASMA).build())
                .build();
    }
}
