package com.matiasmeira.sacaladelangulo.establecimiento.service;

import com.matiasmeira.sacaladelangulo.auth.model.Role;
import com.matiasmeira.sacaladelangulo.auth.model.Usuario;
import com.matiasmeira.sacaladelangulo.auth.repository.UsuarioRepository;
import com.matiasmeira.sacaladelangulo.core.exception.EntityNotFoundException;
import com.matiasmeira.sacaladelangulo.empleado.service.AutorizacionEmpleadoService;
import com.matiasmeira.sacaladelangulo.establecimiento.model.Establecimiento;
import com.matiasmeira.sacaladelangulo.support.Establecimientos;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * El establecimiento inexistente se autoriza contra uno fantasma, con el AutorizacionEmpleadoService REAL
 * (sólo el repositorio de usuarios es un mock): el no-admin recibe el mismo 403 que ante uno ajeno.
 */
@DisplayName("EstablecimientoAutorizado - autorización sin oráculo de existencia")
class EstablecimientoAutorizadoTest {

    private static final String EMAIL_DUENO = "dueno@test.com";
    private static final String EMAIL_ADMIN = "admin@test.com";

    private final UsuarioRepository usuarioRepository = mock(UsuarioRepository.class);
    private final AutorizacionEmpleadoService autorizacion = new AutorizacionEmpleadoService(usuarioRepository);

    private Usuario usuario(long id, Role rol, String email) {
        Usuario u = Usuario.builder().id(id).email(email).rol(rol).build();
        when(usuarioRepository.findByEmail(email)).thenReturn(Optional.of(u));
        return u;
    }

    @Test
    @DisplayName("existente_devuelveElMismoEstablecimientoAutorizado")
    void existente_devuelveElMismo() {
        Usuario dueno = usuario(5L, Role.OWNER, EMAIL_DUENO);
        Establecimiento propio = Establecimientos.establecimientoOperativo(b -> b.id(10L).dueno(dueno));

        Establecimiento resultado = EstablecimientoAutorizado.autorizar(Optional.of(propio),
                e -> autorizacion.validarPropietarioOAdmin(e, EMAIL_DUENO));

        assertSame(propio, resultado);
    }

    @Test
    @DisplayName("existenteAjeno_y_inexistente_dan_elMismoMensaje403")
    void ajenoEInexistente_mismoMensaje() {
        usuario(5L, Role.OWNER, EMAIL_DUENO);
        Usuario otro = Usuario.builder().id(6L).build();
        Establecimiento ajeno = Establecimientos.establecimientoOperativo(b -> b.id(11L).dueno(otro));

        AccessDeniedException deAjeno = assertThrows(AccessDeniedException.class,
                () -> EstablecimientoAutorizado.autorizar(Optional.of(ajeno),
                        e -> autorizacion.validarPropietarioOAdmin(e, EMAIL_DUENO)));
        AccessDeniedException deInexistente = assertThrows(AccessDeniedException.class,
                () -> EstablecimientoAutorizado.autorizar(Optional.empty(),
                        e -> autorizacion.validarPropietarioOAdmin(e, EMAIL_DUENO)));

        assertEquals(deAjeno.getMessage(), deInexistente.getMessage());
    }

    @Test
    @DisplayName("inexistente_comoAdmin_lanza404")
    void inexistenteComoAdmin_lanza404() {
        usuario(1L, Role.ADMIN, EMAIL_ADMIN);

        EntityNotFoundException ex = assertThrows(EntityNotFoundException.class,
                () -> EstablecimientoAutorizado.autorizar(Optional.empty(),
                        e -> autorizacion.validarPropietarioOAdmin(e, EMAIL_ADMIN)));

        assertEquals("Establecimiento no encontrado", ex.getMessage());
    }

    @Test
    @DisplayName("inexistente_conValidarPropietario_tambienEs403ParaElAdmin")
    void inexistenteConValidarPropietario_adminRecibe403() {
        usuario(1L, Role.ADMIN, EMAIL_ADMIN);

        assertThrows(AccessDeniedException.class,
                () -> EstablecimientoAutorizado.autorizar(Optional.empty(),
                        e -> autorizacion.validarPropietario(e, EMAIL_ADMIN)));
    }
}
