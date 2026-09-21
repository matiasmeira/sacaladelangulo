package com.matiasmeira.sacaladelangulo.establecimiento.service;

import com.matiasmeira.sacaladelangulo.auth.model.Role;
import com.matiasmeira.sacaladelangulo.auth.model.Usuario;
import com.matiasmeira.sacaladelangulo.empleado.model.AccionAuditoria;
import com.matiasmeira.sacaladelangulo.empleado.service.AutorizacionEmpleadoService;
import com.matiasmeira.sacaladelangulo.empleado.service.RegistroAuditoriaService;
import com.matiasmeira.sacaladelangulo.establecimiento.dto.SolicitarVerificacionRequest;
import com.matiasmeira.sacaladelangulo.establecimiento.dto.SolicitarVerificacionResponse;
import com.matiasmeira.sacaladelangulo.establecimiento.model.EstadoVerificacion;
import com.matiasmeira.sacaladelangulo.establecimiento.model.Establecimiento;
import com.matiasmeira.sacaladelangulo.establecimiento.repository.EstablecimientoRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class EstablecimientoVerificacionServiceTest {

    private static final String CUIT_VALIDO = "20-12345678-6";
    private static final String CUIT_VALIDO_NORMALIZADO = "20123456786";
    private static final String URL_VALIDA = "https://www.instagram.com/miclub";

    @Mock
    private EstablecimientoRepository establecimientoRepository;

    @Mock
    private AutorizacionEmpleadoService autorizacionEmpleadoService;

    @Mock
    private RegistroAuditoriaService registroAuditoriaService;

    @InjectMocks
    private EstablecimientoVerificacionService establecimientoVerificacionService;

    private Usuario dueno() {
        return Usuario.builder().id(1L).email("dueno@test.com").rol(Role.OWNER).build();
    }

    private Establecimiento establecimiento(EstadoVerificacion estado) {
        return Establecimiento.builder()
                .id(10L)
                .nombre("Complejo Test")
                .estadoVerificacion(estado)
                .motivoRechazo(estado == EstadoVerificacion.RECHAZADO ? "Faltan fotos" : null)
                .dueno(dueno())
                .build();
    }

    private SolicitarVerificacionRequest requestValido() {
        return new SolicitarVerificacionRequest(CUIT_VALIDO, "Mi Club SRL", "1122334455", URL_VALIDA);
    }

    @Test
    @DisplayName("solicitarVerificacion_Pendiente_QuedaEnRevisionConCuitNormalizadoYAudita")
    void solicitarVerificacion_Pendiente_QuedaEnRevisionConCuitNormalizadoYAudita() {
        Establecimiento establecimiento = establecimiento(EstadoVerificacion.PENDIENTE);
        Usuario dueno = dueno();
        when(establecimientoRepository.findById(10L)).thenReturn(Optional.of(establecimiento));
        when(autorizacionEmpleadoService.validarPropietario(establecimiento, "dueno@test.com")).thenReturn(dueno);
        when(establecimientoRepository.save(any(Establecimiento.class))).thenAnswer(inv -> inv.getArgument(0));

        SolicitarVerificacionResponse response =
                establecimientoVerificacionService.solicitarVerificacion(10L, requestValido(), "dueno@test.com");

        assertEquals(EstadoVerificacion.EN_REVISION, response.estadoVerificacion());
        assertEquals(CUIT_VALIDO_NORMALIZADO, establecimiento.getCuit());
        assertEquals("Mi Club SRL", establecimiento.getRazonSocial());
        assertEquals("1122334455", establecimiento.getTelefonoContacto());
        assertEquals(URL_VALIDA, establecimiento.getUrlRedSocial());

        verify(registroAuditoriaService).registrarSobreEstablecimiento(
                eq(dueno), eq(establecimiento), eq(AccionAuditoria.SOLICITAR_VERIFICACION_ESTABLECIMIENTO), eq(10L), any());
    }

    @Test
    @DisplayName("solicitarVerificacion_Rechazado_ResolicitaYLimpiaElMotivoDeRechazo")
    void solicitarVerificacion_Rechazado_ResolicitaYLimpiaElMotivoDeRechazo() {
        Establecimiento establecimiento = establecimiento(EstadoVerificacion.RECHAZADO);
        when(establecimientoRepository.findById(10L)).thenReturn(Optional.of(establecimiento));
        when(autorizacionEmpleadoService.validarPropietario(establecimiento, "dueno@test.com")).thenReturn(dueno());
        when(establecimientoRepository.save(any(Establecimiento.class))).thenAnswer(inv -> inv.getArgument(0));

        establecimientoVerificacionService.solicitarVerificacion(10L, requestValido(), "dueno@test.com");

        assertEquals(EstadoVerificacion.EN_REVISION, establecimiento.getEstadoVerificacion());
        assertNull(establecimiento.getMotivoRechazo());
    }

    @Test
    @DisplayName("solicitarVerificacion_DesdeEnRevision_LanzaIllegalArgumentExceptionYNoGuarda")
    void solicitarVerificacion_DesdeEnRevision_LanzaIllegalArgumentExceptionYNoGuarda() {
        Establecimiento establecimiento = establecimiento(EstadoVerificacion.EN_REVISION);
        when(establecimientoRepository.findById(10L)).thenReturn(Optional.of(establecimiento));
        when(autorizacionEmpleadoService.validarPropietario(establecimiento, "dueno@test.com")).thenReturn(dueno());

        assertThrows(IllegalArgumentException.class,
                () -> establecimientoVerificacionService.solicitarVerificacion(10L, requestValido(), "dueno@test.com"));
        verify(establecimientoRepository, never()).save(any());
    }

    @Test
    @DisplayName("solicitarVerificacion_DesdeVerificado_LanzaIllegalArgumentExceptionYNoGuarda")
    void solicitarVerificacion_DesdeVerificado_LanzaIllegalArgumentExceptionYNoGuarda() {
        Establecimiento establecimiento = establecimiento(EstadoVerificacion.VERIFICADO);
        when(establecimientoRepository.findById(10L)).thenReturn(Optional.of(establecimiento));
        when(autorizacionEmpleadoService.validarPropietario(establecimiento, "dueno@test.com")).thenReturn(dueno());

        assertThrows(IllegalArgumentException.class,
                () -> establecimientoVerificacionService.solicitarVerificacion(10L, requestValido(), "dueno@test.com"));
        verify(establecimientoRepository, never()).save(any());
    }

    @Test
    @DisplayName("solicitarVerificacion_CuitInvalido_LanzaIllegalArgumentExceptionYNoGuarda")
    void solicitarVerificacion_CuitInvalido_LanzaIllegalArgumentExceptionYNoGuarda() {
        Establecimiento establecimiento = establecimiento(EstadoVerificacion.PENDIENTE);
        when(establecimientoRepository.findById(10L)).thenReturn(Optional.of(establecimiento));
        when(autorizacionEmpleadoService.validarPropietario(establecimiento, "dueno@test.com")).thenReturn(dueno());

        SolicitarVerificacionRequest request = new SolicitarVerificacionRequest("11111111111", "Mi Club SRL", "1122334455", URL_VALIDA);

        assertThrows(IllegalArgumentException.class,
                () -> establecimientoVerificacionService.solicitarVerificacion(10L, request, "dueno@test.com"));
        verify(establecimientoRepository, never()).save(any());
    }

    @Test
    @DisplayName("solicitarVerificacion_UrlQueNoEsIgNiFb_LanzaIllegalArgumentExceptionYNoGuarda")
    void solicitarVerificacion_UrlQueNoEsIgNiFb_LanzaIllegalArgumentExceptionYNoGuarda() {
        Establecimiento establecimiento = establecimiento(EstadoVerificacion.PENDIENTE);
        when(establecimientoRepository.findById(10L)).thenReturn(Optional.of(establecimiento));
        when(autorizacionEmpleadoService.validarPropietario(establecimiento, "dueno@test.com")).thenReturn(dueno());

        SolicitarVerificacionRequest request =
                new SolicitarVerificacionRequest(CUIT_VALIDO, "Mi Club SRL", "1122334455", "https://www.tiktok.com/miclub");

        assertThrows(IllegalArgumentException.class,
                () -> establecimientoVerificacionService.solicitarVerificacion(10L, request, "dueno@test.com"));
        verify(establecimientoRepository, never()).save(any());
    }

    @Test
    @DisplayName("solicitarVerificacion_NoEsElDueno_LanzaAccessDeniedExceptionYNoGuarda")
    void solicitarVerificacion_NoEsElDueno_LanzaAccessDeniedExceptionYNoGuarda() {
        Establecimiento establecimiento = establecimiento(EstadoVerificacion.PENDIENTE);
        when(establecimientoRepository.findById(10L)).thenReturn(Optional.of(establecimiento));
        when(autorizacionEmpleadoService.validarPropietario(establecimiento, "otro@test.com"))
                .thenThrow(new AccessDeniedException("No autorizado en este establecimiento"));

        assertThrows(AccessDeniedException.class,
                () -> establecimientoVerificacionService.solicitarVerificacion(10L, requestValido(), "otro@test.com"));
        verify(establecimientoRepository, never()).save(any());
    }

    @Test
    @DisplayName("solicitarVerificacion_Pendiente_NoIntentaLimpiarUnMotivoDeRechazoQueNoExiste")
    void solicitarVerificacion_Pendiente_NoIntentaLimpiarUnMotivoDeRechazoQueNoExiste() {
        Establecimiento establecimiento = establecimiento(EstadoVerificacion.PENDIENTE);
        when(establecimientoRepository.findById(10L)).thenReturn(Optional.of(establecimiento));
        when(autorizacionEmpleadoService.validarPropietario(establecimiento, "dueno@test.com")).thenReturn(dueno());
        when(establecimientoRepository.save(any(Establecimiento.class))).thenAnswer(inv -> inv.getArgument(0));

        ArgumentCaptor<String> detalleCaptor = ArgumentCaptor.forClass(String.class);
        establecimientoVerificacionService.solicitarVerificacion(10L, requestValido(), "dueno@test.com");

        verify(registroAuditoriaService).registrarSobreEstablecimiento(
                any(), any(), eq(AccionAuditoria.SOLICITAR_VERIFICACION_ESTABLECIMIENTO), any(), detalleCaptor.capture());
        assertEquals("Solicitud de verificación: Complejo Test", detalleCaptor.getValue());
    }
}
