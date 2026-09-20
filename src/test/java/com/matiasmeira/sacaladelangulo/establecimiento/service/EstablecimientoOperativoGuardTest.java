package com.matiasmeira.sacaladelangulo.establecimiento.service;

import com.matiasmeira.sacaladelangulo.core.exception.EntityNotFoundException;
import com.matiasmeira.sacaladelangulo.establecimiento.model.Establecimiento;
import com.matiasmeira.sacaladelangulo.establecimiento.model.EstadoVerificacion;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("EstablecimientoOperativoGuard")
class EstablecimientoOperativoGuardTest {

    private final EstablecimientoOperativoGuard guard = new EstablecimientoOperativoGuard();

    private Establecimiento establecimiento(boolean activo) {
        return establecimiento(activo, EstadoVerificacion.VERIFICADO);
    }

    private Establecimiento establecimiento(boolean activo, EstadoVerificacion estadoVerificacion) {
        return Establecimiento.builder().id(1L).nombre("Test").isActive(activo).estadoVerificacion(estadoVerificacion).build();
    }

    @Test
    @DisplayName("validarEstablecimientoOperativoParaJugador_ActivoYVerificado_NoLanza")
    void validarEstablecimientoOperativoParaJugador_ActivoYVerificado_NoLanza() {
        assertDoesNotThrow(() -> guard.validarEstablecimientoOperativoParaJugador(establecimiento(true)));
    }

    @Test
    @DisplayName("validarEstablecimientoOperativoParaJugador_Inactivo_LanzaEntityNotFoundOpaco")
    void validarEstablecimientoOperativoParaJugador_Inactivo_LanzaEntityNotFoundOpaco() {
        EntityNotFoundException ex = assertThrows(EntityNotFoundException.class,
                () -> guard.validarEstablecimientoOperativoParaJugador(establecimiento(false)));

        assertEquals("Establecimiento no encontrado", ex.getMessage());
    }

    @ParameterizedTest(name = "validarEstablecimientoOperativoParaJugador_ActivoPeroNoVerificado_{0}_LanzaEntityNotFoundOpaco")
    @EnumSource(value = EstadoVerificacion.class, names = "VERIFICADO", mode = EnumSource.Mode.EXCLUDE)
    @DisplayName("validarEstablecimientoOperativoParaJugador_ActivoPeroNoVerificado_LanzaEntityNotFoundOpaco")
    void validarEstablecimientoOperativoParaJugador_ActivoPeroNoVerificado_LanzaEntityNotFoundOpaco(EstadoVerificacion estadoVerificacion) {
        EntityNotFoundException ex = assertThrows(EntityNotFoundException.class,
                () -> guard.validarEstablecimientoOperativoParaJugador(establecimiento(true, estadoVerificacion)));

        // Mismo mensaje opaco que "no existe" o "inactivo": un tercero no debe poder distinguir
        // por qué el establecimiento no está operativo.
        assertEquals("Establecimiento no encontrado", ex.getMessage());
    }

    @Test
    @DisplayName("validarEstablecimientoOperativoParaJugador_InactivoYNoVerificado_LanzaMismoMensajeOpaco")
    void validarEstablecimientoOperativoParaJugador_InactivoYNoVerificado_LanzaMismoMensajeOpaco() {
        EntityNotFoundException ex = assertThrows(EntityNotFoundException.class,
                () -> guard.validarEstablecimientoOperativoParaJugador(establecimiento(false, EstadoVerificacion.PENDIENTE)));

        assertEquals("Establecimiento no encontrado", ex.getMessage());
    }

    @Test
    @DisplayName("validarEstablecimientoOperativoParaPanel_ActivoYVerificado_NoLanza")
    void validarEstablecimientoOperativoParaPanel_ActivoYVerificado_NoLanza() {
        assertDoesNotThrow(() -> guard.validarEstablecimientoOperativoParaPanel(establecimiento(true)));
    }

    @Test
    @DisplayName("validarEstablecimientoOperativoParaPanel_Inactivo_LanzaIllegalArgumentMencionandoElEstablecimiento")
    void validarEstablecimientoOperativoParaPanel_Inactivo_LanzaIllegalArgumentMencionandoElEstablecimiento() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> guard.validarEstablecimientoOperativoParaPanel(establecimiento(false)));

        // No debe reutilizar el texto de validarCanchaActivaParaPanel (que habla de "la cancha"):
        // tiene que hablar del establecimiento, para no mandar al dueño a buscar una cancha rota.
        assertTrue(ex.getMessage().contains("establecimiento"));
        assertTrue(ex.getMessage().toLowerCase().contains("deshabilitado"));
        assertFalse(ex.getMessage().toLowerCase().contains("cancha"));
    }

    @ParameterizedTest(name = "validarEstablecimientoOperativoParaPanel_ActivoPeroNoVerificado_{0}_LanzaIllegalArgumentMencionandoVerificacion")
    @EnumSource(value = EstadoVerificacion.class, names = "VERIFICADO", mode = EnumSource.Mode.EXCLUDE)
    @DisplayName("validarEstablecimientoOperativoParaPanel_ActivoPeroNoVerificado_LanzaIllegalArgumentMencionandoVerificacion")
    void validarEstablecimientoOperativoParaPanel_ActivoPeroNoVerificado_LanzaIllegalArgumentMencionandoVerificacion(EstadoVerificacion estadoVerificacion) {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> guard.validarEstablecimientoOperativoParaPanel(establecimiento(true, estadoVerificacion)));

        // Mensaje distinto al de "deshabilitado": el dueño tiene que entender que acá la
        // acción que falta es la verificación, no reactivar el establecimiento.
        assertTrue(ex.getMessage().toLowerCase().contains("verificad"));
        assertFalse(ex.getMessage().toLowerCase().contains("deshabilitado"));
    }

    @Test
    @DisplayName("validarEstablecimientoOperativoParaPanel_InactivoYNoVerificado_PriorizaMensajeDeDeshabilitado")
    void validarEstablecimientoOperativoParaPanel_InactivoYNoVerificado_PriorizaMensajeDeDeshabilitado() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> guard.validarEstablecimientoOperativoParaPanel(establecimiento(false, EstadoVerificacion.PENDIENTE)));

        assertTrue(ex.getMessage().toLowerCase().contains("deshabilitado"));
    }
}
