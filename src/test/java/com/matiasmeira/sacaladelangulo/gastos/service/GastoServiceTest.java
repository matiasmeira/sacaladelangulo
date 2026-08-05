package com.matiasmeira.sacaladelangulo.gastos.service;

import com.matiasmeira.sacaladelangulo.auth.model.Role;
import com.matiasmeira.sacaladelangulo.auth.model.Usuario;
import com.matiasmeira.sacaladelangulo.cierrecaja.model.OrigenMovimientoCaja;
import com.matiasmeira.sacaladelangulo.cierrecaja.model.TipoMovimientoCaja;
import com.matiasmeira.sacaladelangulo.cierrecaja.service.TurnoCajaService;
import com.matiasmeira.sacaladelangulo.core.exception.EntityNotFoundException;
import com.matiasmeira.sacaladelangulo.core.pago.MetodoPago;
import com.matiasmeira.sacaladelangulo.empleado.model.AccionAuditoria;
import com.matiasmeira.sacaladelangulo.empleado.service.AutorizacionEmpleadoService;
import com.matiasmeira.sacaladelangulo.empleado.service.RegistroAuditoriaService;
import com.matiasmeira.sacaladelangulo.establecimiento.model.Establecimiento;
import com.matiasmeira.sacaladelangulo.establecimiento.repository.EstablecimientoRepository;
import com.matiasmeira.sacaladelangulo.gastos.dto.GastoMapper;
import com.matiasmeira.sacaladelangulo.gastos.dto.GastoRequest;
import com.matiasmeira.sacaladelangulo.gastos.dto.GastoResponse;
import com.matiasmeira.sacaladelangulo.gastos.model.CategoriaGasto;
import com.matiasmeira.sacaladelangulo.gastos.model.Gasto;
import com.matiasmeira.sacaladelangulo.gastos.repository.GastoRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.AccessDeniedException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("GastoService - Tests del gestor de gastos")
class GastoServiceTest {

    @Mock
    private GastoRepository gastoRepository;

    @Mock
    private EstablecimientoRepository establecimientoRepository;

    @Mock
    private AutorizacionEmpleadoService autorizacionEmpleadoService;

    @Mock
    private TurnoCajaService turnoCajaService;

    @Mock
    private RegistroAuditoriaService registroAuditoriaService;

    private GastoService gastoService;

    private Usuario dueno;
    private Usuario empleado;
    private Establecimiento establecimiento;

    @BeforeEach
    void setUp() {
        gastoService = new GastoService(gastoRepository, establecimientoRepository, autorizacionEmpleadoService, new GastoMapper(), turnoCajaService, registroAuditoriaService);

        dueno = Usuario.builder()
                .id(2L)
                .email("dueno@test.com")
                .nombre("Dueño Test")
                .rol(Role.OWNER)
                .build();

        empleado = Usuario.builder()
                .id(3L)
                .email("empleado@test.com")
                .nombre("Empleado Test")
                .rol(Role.EMPLOYEE)
                .build();

        establecimiento = Establecimiento.builder()
                .id(10L)
                .nombre("Establecimiento Test")
                .dueno(dueno)
                .build();
    }

    @Test
    @DisplayName("registrarGasto_Exito_Owner")
    void registrarGasto_Exito_Owner() {
        GastoRequest request = new GastoRequest(LocalDate.now(), BigDecimal.valueOf(1000), CategoriaGasto.SERVICIOS, "Luz", MetodoPago.EFECTIVO, null);

        when(establecimientoRepository.findById(establecimiento.getId())).thenReturn(Optional.of(establecimiento));
        when(autorizacionEmpleadoService.validarPropietarioOAdmin(establecimiento, dueno.getEmail())).thenReturn(dueno);
        when(gastoRepository.save(any(Gasto.class))).thenAnswer(invocation -> {
            Gasto gasto = invocation.getArgument(0);
            gasto.setId(100L);
            gasto.setFechaCreacion(LocalDateTime.now());
            return gasto;
        });

        GastoResponse response = assertDoesNotThrow(
                () -> gastoService.registrarGasto(establecimiento.getId(), request, dueno.getEmail()));

        assertEquals(0, BigDecimal.valueOf(1000).compareTo(response.monto()));
        assertEquals("SERVICIOS", response.categoria());
        assertEquals(dueno.getId(), response.usuarioRegistroId());
        verify(turnoCajaService).registrarMovimientoSiCorresponde(
                eq(establecimiento), eq(TipoMovimientoCaja.EGRESO), eq(OrigenMovimientoCaja.GASTO),
                eq(MetodoPago.EFECTIVO), eq(BigDecimal.valueOf(1000)), eq("Gasto: Luz"),
                eq(100L), eq(dueno));
        // Ver §3 "Consistencia entre features" en la auditoría: alta de gasto por el
        // propio dueño ahora deja rastro en RegistroAuditoria, no solo la de empleados.
        verify(registroAuditoriaService).registrarSobreEstablecimiento(
                eq(dueno), eq(establecimiento), eq(AccionAuditoria.REGISTRAR_GASTO), eq(100L), any());
    }

    @Test
    @DisplayName("registrarGasto_Fallo_Empleado_NoAutorizado")
    void registrarGasto_Fallo_Empleado_NoAutorizado() {
        GastoRequest request = new GastoRequest(LocalDate.now(), BigDecimal.valueOf(1000), CategoriaGasto.SERVICIOS, "Luz", MetodoPago.EFECTIVO, null);

        when(establecimientoRepository.findById(establecimiento.getId())).thenReturn(Optional.of(establecimiento));
        when(autorizacionEmpleadoService.validarPropietarioOAdmin(establecimiento, empleado.getEmail()))
                .thenThrow(new AccessDeniedException("No autorizado en este establecimiento"));

        assertThrows(AccessDeniedException.class,
                () -> gastoService.registrarGasto(establecimiento.getId(), request, empleado.getEmail()));
        verify(gastoRepository, never()).save(any());
    }

    @Test
    @DisplayName("registrarGasto_Fallo_MontoInvalido")
    void registrarGasto_Fallo_MontoInvalido() {
        GastoRequest request = new GastoRequest(LocalDate.now(), BigDecimal.ZERO, CategoriaGasto.SERVICIOS, "Luz", MetodoPago.EFECTIVO, null);

        when(establecimientoRepository.findById(establecimiento.getId())).thenReturn(Optional.of(establecimiento));
        when(autorizacionEmpleadoService.validarPropietarioOAdmin(establecimiento, dueno.getEmail())).thenReturn(dueno);

        assertThrows(IllegalArgumentException.class,
                () -> gastoService.registrarGasto(establecimiento.getId(), request, dueno.getEmail()));
        verify(gastoRepository, never()).save(any());
    }

    @Test
    @DisplayName("editarGasto_Fallo_NoPertenceAlEstablecimiento")
    void editarGasto_Fallo_NoPertenceAlEstablecimiento() {
        GastoRequest request = new GastoRequest(LocalDate.now(), BigDecimal.valueOf(500), CategoriaGasto.OTROS, "Otro", MetodoPago.EFECTIVO, null);

        when(gastoRepository.findByIdAndEstablecimientoId(999L, establecimiento.getId())).thenReturn(Optional.empty());

        assertThrows(EntityNotFoundException.class,
                () -> gastoService.editarGasto(establecimiento.getId(), 999L, request, dueno.getEmail()));
    }

    @Test
    @DisplayName("editarGasto_Exito_ActualizaCampos")
    void editarGasto_Exito_ActualizaCampos() {
        Gasto gasto = Gasto.builder()
                .id(50L)
                .establecimiento(establecimiento)
                .fecha(LocalDate.now().minusDays(1))
                .monto(BigDecimal.valueOf(200))
                .categoria(CategoriaGasto.INSUMOS)
                .descripcion("Vieja")
                .metodoPago(MetodoPago.EFECTIVO)
                .usuarioRegistro(dueno)
                .fechaCreacion(LocalDateTime.now())
                .build();

        GastoRequest request = new GastoRequest(LocalDate.now(), BigDecimal.valueOf(999), CategoriaGasto.MARKETING, "Nueva", MetodoPago.TRANSFERENCIA, "http://x");

        when(gastoRepository.findByIdAndEstablecimientoId(50L, establecimiento.getId())).thenReturn(Optional.of(gasto));
        when(autorizacionEmpleadoService.validarPropietarioOAdmin(establecimiento, dueno.getEmail())).thenReturn(dueno);
        when(gastoRepository.save(any(Gasto.class))).thenAnswer(invocation -> invocation.getArgument(0));

        GastoResponse response = gastoService.editarGasto(establecimiento.getId(), 50L, request, dueno.getEmail());

        assertEquals(0, BigDecimal.valueOf(999).compareTo(response.monto()));
        assertEquals("MARKETING", response.categoria());
        assertEquals("Nueva", response.descripcion());
        assertEquals("http://x", response.comprobanteUrl());
        // Ver M-04 en la auditoría: cambió monto (200->999) y método de pago
        // (EFECTIVO->TRANSFERENCIA), así que debe revertir el egreso original en efectivo
        // y no registrar un nuevo egreso (el nuevo método ya no es EFECTIVO).
        verify(turnoCajaService).registrarMovimientoSiCorresponde(
                eq(establecimiento), eq(TipoMovimientoCaja.INGRESO), eq(OrigenMovimientoCaja.GASTO),
                eq(MetodoPago.EFECTIVO), eq(BigDecimal.valueOf(200)),
                eq("Ajuste por edición del gasto #50: reversión del monto anterior"), eq(50L), eq(dueno));
        verify(turnoCajaService).registrarMovimientoSiCorresponde(
                eq(establecimiento), eq(TipoMovimientoCaja.EGRESO), eq(OrigenMovimientoCaja.GASTO),
                eq(MetodoPago.TRANSFERENCIA), eq(BigDecimal.valueOf(999)),
                eq("Gasto editado: Nueva"), eq(50L), eq(dueno));
        verify(registroAuditoriaService).registrarSobreEstablecimiento(
                eq(dueno), eq(establecimiento), eq(AccionAuditoria.EDITAR_GASTO), eq(50L), any());
    }

    @Test
    @DisplayName("editarGasto_Exito_SinCambioDeMontoNiMetodo_NoTocaLaCaja")
    void editarGasto_Exito_SinCambioDeMontoNiMetodo_NoTocaLaCaja() {
        // Arrange: solo cambia la descripción/categoría, ni el monto ni el método de pago
        Gasto gasto = Gasto.builder()
                .id(51L)
                .establecimiento(establecimiento)
                .fecha(LocalDate.now().minusDays(1))
                .monto(BigDecimal.valueOf(200))
                .categoria(CategoriaGasto.INSUMOS)
                .descripcion("Vieja")
                .metodoPago(MetodoPago.EFECTIVO)
                .usuarioRegistro(dueno)
                .fechaCreacion(LocalDateTime.now())
                .build();

        GastoRequest request = new GastoRequest(LocalDate.now(), BigDecimal.valueOf(200), CategoriaGasto.MARKETING, "Nueva descripción", MetodoPago.EFECTIVO, null);

        when(gastoRepository.findByIdAndEstablecimientoId(51L, establecimiento.getId())).thenReturn(Optional.of(gasto));
        when(autorizacionEmpleadoService.validarPropietarioOAdmin(establecimiento, dueno.getEmail())).thenReturn(dueno);
        when(gastoRepository.save(any(Gasto.class))).thenAnswer(invocation -> invocation.getArgument(0));

        gastoService.editarGasto(establecimiento.getId(), 51L, request, dueno.getEmail());

        verify(turnoCajaService, never()).registrarMovimientoSiCorresponde(
                any(), any(), any(), any(), any(), any(), any(), any());
        // La edición sigue auditándose aunque no haya cambiado monto/método (afecta
        // categoría/descripción/comprobante, que también son datos de negocio).
        verify(registroAuditoriaService).registrarSobreEstablecimiento(
                eq(dueno), eq(establecimiento), eq(AccionAuditoria.EDITAR_GASTO), eq(51L), any());
    }

    @Test
    @DisplayName("eliminarGasto_Exito_EsAnulacionLogicaNoDeleteFisico")
    void eliminarGasto_Exito_EsAnulacionLogicaNoDeleteFisico() {
        // Ver M-04 en la auditoría: eliminarGasto ya no borra la fila (se perdía el
        // historial financiero), marca isActive=false para que quede excluida del listado
        // y de los reportes pero persista para auditoría.
        Gasto gasto = Gasto.builder()
                .id(60L)
                .establecimiento(establecimiento)
                .fecha(LocalDate.now())
                .monto(BigDecimal.valueOf(100))
                .categoria(CategoriaGasto.OTROS)
                .descripcion("A borrar")
                .metodoPago(MetodoPago.EFECTIVO)
                .usuarioRegistro(dueno)
                .build();

        when(gastoRepository.findByIdAndEstablecimientoId(60L, establecimiento.getId())).thenReturn(Optional.of(gasto));
        when(autorizacionEmpleadoService.validarPropietarioOAdmin(establecimiento, dueno.getEmail())).thenReturn(dueno);
        when(gastoRepository.save(any(Gasto.class))).thenAnswer(invocation -> invocation.getArgument(0));

        gastoService.eliminarGasto(establecimiento.getId(), 60L, dueno.getEmail());

        verify(gastoRepository, never()).delete(any());
        verify(gastoRepository).save(gasto);
        assertFalse(gasto.getIsActive());
        // Eliminar un gasto en efectivo debe revertir el egreso que había generado, para
        // que el arqueo no reporte un sobrante falso.
        verify(turnoCajaService).registrarMovimientoSiCorresponde(
                eq(establecimiento), eq(TipoMovimientoCaja.INGRESO), eq(OrigenMovimientoCaja.GASTO),
                eq(MetodoPago.EFECTIVO), eq(BigDecimal.valueOf(100)),
                eq("Gasto eliminado: reversión de A borrar"), eq(60L), eq(dueno));
        verify(registroAuditoriaService).registrarSobreEstablecimiento(
                eq(dueno), eq(establecimiento), eq(AccionAuditoria.ELIMINAR_GASTO), eq(60L), any());
    }

    @Test
    @DisplayName("eliminarGasto_Exito_EsIdempotenteSiYaEstabaEliminado")
    void eliminarGasto_Exito_EsIdempotenteSiYaEstabaEliminado() {
        Gasto gastoYaEliminado = Gasto.builder()
                .id(61L)
                .establecimiento(establecimiento)
                .fecha(LocalDate.now())
                .monto(BigDecimal.valueOf(100))
                .categoria(CategoriaGasto.OTROS)
                .descripcion("Ya eliminado")
                .metodoPago(MetodoPago.EFECTIVO)
                .usuarioRegistro(dueno)
                .isActive(false)
                .build();

        when(gastoRepository.findByIdAndEstablecimientoId(61L, establecimiento.getId())).thenReturn(Optional.of(gastoYaEliminado));
        when(autorizacionEmpleadoService.validarPropietarioOAdmin(establecimiento, dueno.getEmail())).thenReturn(dueno);

        gastoService.eliminarGasto(establecimiento.getId(), 61L, dueno.getEmail());

        verify(gastoRepository, never()).save(any());
        verify(gastoRepository, never()).delete(any());
        verify(turnoCajaService, never()).registrarMovimientoSiCorresponde(
                any(), any(), any(), any(), any(), any(), any(), any());
        verify(registroAuditoriaService, never()).registrarSobreEstablecimiento(
                any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("editarGasto_Fallo_GastoEliminado")
    void editarGasto_Fallo_GastoEliminado() {
        Gasto gastoEliminado = Gasto.builder()
                .id(62L)
                .establecimiento(establecimiento)
                .fecha(LocalDate.now())
                .monto(BigDecimal.valueOf(100))
                .categoria(CategoriaGasto.OTROS)
                .descripcion("Eliminado")
                .metodoPago(MetodoPago.EFECTIVO)
                .usuarioRegistro(dueno)
                .isActive(false)
                .build();

        GastoRequest request = new GastoRequest(LocalDate.now(), BigDecimal.valueOf(200), CategoriaGasto.OTROS, "Intento de edición", MetodoPago.EFECTIVO, null);

        when(gastoRepository.findByIdAndEstablecimientoId(62L, establecimiento.getId())).thenReturn(Optional.of(gastoEliminado));
        when(autorizacionEmpleadoService.validarPropietarioOAdmin(establecimiento, dueno.getEmail())).thenReturn(dueno);

        assertThrows(IllegalArgumentException.class,
                () -> gastoService.editarGasto(establecimiento.getId(), 62L, request, dueno.getEmail()));
        verify(gastoRepository, never()).save(any());
        verify(turnoCajaService, never()).registrarMovimientoSiCorresponde(
                any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("listarGastos_Exito_PasaFiltrosAlRepositorioYMapea")
    void listarGastos_Exito_PasaFiltrosAlRepositorioYMapea() {
        Gasto gasto = Gasto.builder()
                .id(70L)
                .establecimiento(establecimiento)
                .fecha(LocalDate.now())
                .monto(BigDecimal.valueOf(300))
                .categoria(CategoriaGasto.ALQUILER)
                .descripcion("Alquiler mensual")
                .metodoPago(MetodoPago.EFECTIVO)
                .usuarioRegistro(dueno)
                .build();

        LocalDate desde = LocalDate.now().minusDays(7);
        LocalDate hasta = LocalDate.now();
        Pageable pageable = PageRequest.of(0, 20);

        when(establecimientoRepository.findById(establecimiento.getId())).thenReturn(Optional.of(establecimiento));
        when(autorizacionEmpleadoService.validarPropietarioOAdmin(establecimiento, dueno.getEmail())).thenReturn(dueno);
        when(gastoRepository.buscar(eq(establecimiento.getId()), eq(desde), eq(hasta), eq(CategoriaGasto.ALQUILER), eq(pageable)))
                .thenReturn(new PageImpl<>(List.of(gasto)));

        var page = gastoService.listarGastos(establecimiento.getId(), dueno.getEmail(), desde, hasta, CategoriaGasto.ALQUILER, pageable);

        assertEquals(1, page.getTotalElements());
        assertEquals("ALQUILER", page.getContent().get(0).categoria());
    }
}
