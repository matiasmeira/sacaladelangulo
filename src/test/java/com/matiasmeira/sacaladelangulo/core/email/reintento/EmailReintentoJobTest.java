package com.matiasmeira.sacaladelangulo.core.email.reintento;

import com.matiasmeira.sacaladelangulo.core.email.EmailTransport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("EmailReintentoJob - drenado de la cola")
class EmailReintentoJobTest {

    private static final int INTENTOS_MAXIMOS = 3;
    private static final String ASUNTO = "Verificá tu email";
    private static final String CUERPO = "<p>hola</p>";

    @Mock
    private EmailTransport emailTransport;

    @Mock
    private EmailPendienteRepository emailPendienteRepository;

    @Mock
    private EmailPendienteRegistro emailPendienteRegistro;

    @InjectMocks
    private EmailReintentoJob job;

    @BeforeEach
    void configurarValoresInyectados() {
        ReflectionTestUtils.setField(job, "intentosMaximos", INTENTOS_MAXIMOS);
        ReflectionTestUtils.setField(job, "tamanioLote", 50);
    }

    private EmailPendiente pendiente(Long id, String destinatario) {
        return EmailPendiente.builder()
                .id(id)
                .destinatario(destinatario)
                .asunto(ASUNTO)
                .cuerpoHtml(CUERPO)
                .estado(EstadoEmailPendiente.PENDIENTE)
                .intentos(0)
                .build();
    }

    @Test
    @DisplayName("colaVacia_noHaceNada")
    void colaVacia_noHaceNada() {
        when(emailPendienteRepository.findLoteAReintentar(any(Pageable.class))).thenReturn(List.of());

        job.reintentarPendientes();

        verifyNoInteractions(emailTransport);
        verifyNoInteractions(emailPendienteRegistro);
    }

    @Test
    @DisplayName("reintentoExitoso_borraLaFilaDeLaCola")
    void reintentoExitoso_borraLaFilaDeLaCola() {
        when(emailPendienteRepository.findLoteAReintentar(any(Pageable.class)))
                .thenReturn(List.of(pendiente(1L, "jugador@saque.test")));

        job.reintentarPendientes();

        verify(emailTransport).enviar("jugador@saque.test", ASUNTO, CUERPO);
        verify(emailPendienteRegistro).borrar(1L);
        verify(emailPendienteRegistro, never()).registrarFallo(anyLong(), anyString(), anyInt());
    }

    @Test
    @DisplayName("reintentoFallido_registraElFalloConElTopeDeIntentos")
    void reintentoFallido_registraElFalloConElTopeDeIntentos() {
        when(emailPendienteRepository.findLoteAReintentar(any(Pageable.class)))
                .thenReturn(List.of(pendiente(1L, "jugador@saque.test")));
        doThrow(new RuntimeException("sigue caido")).when(emailTransport).enviar(anyString(), anyString(), anyString());

        job.reintentarPendientes();

        verify(emailPendienteRegistro).registrarFallo(1L, "sigue caido", INTENTOS_MAXIMOS);
        verify(emailPendienteRegistro, never()).borrar(anyLong());
    }

    /**
     * Un destinatario que rebota no debe impedir que se drene el resto de la cola: cada
     * email se confirma en su propia transacción, así que el fallo de uno es aislado.
     */
    @Test
    @DisplayName("unFalloEnElMedio_noCortaElDrenadoDelResto")
    void unFalloEnElMedio_noCortaElDrenadoDelResto() {
        when(emailPendienteRepository.findLoteAReintentar(any(Pageable.class))).thenReturn(List.of(
                pendiente(1L, "ok1@saque.test"),
                pendiente(2L, "rebota@saque.test"),
                pendiente(3L, "ok2@saque.test")));
        // lenient() es obligatorio acá, no cosmético: con strict stubbing, invocar un método
        // stubbeado con argumentos que NO matchean hace que Mockito lance
        // PotentialStubbingProblem... que es una RuntimeException, y el catch del job la
        // trata como un fallo del proveedor. El resultado era que ok1 y ok2 se contaban como
        // rebotados y el test fallaba describiendo un bug que no existe.
        lenient().doThrow(new RuntimeException("rebote"))
                .when(emailTransport).enviar("rebota@saque.test", ASUNTO, CUERPO);

        job.reintentarPendientes();

        verify(emailPendienteRegistro).borrar(1L);
        verify(emailPendienteRegistro).registrarFallo(eq(2L), anyString(), anyInt());
        verify(emailPendienteRegistro).borrar(3L);
    }

    @Test
    @DisplayName("elLoteSePideConElTamanioConfigurado")
    void elLoteSePideConElTamanioConfigurado() {
        ReflectionTestUtils.setField(job, "tamanioLote", 7);
        when(emailPendienteRepository.findLoteAReintentar(any(Pageable.class))).thenReturn(List.of());

        job.reintentarPendientes();

        verify(emailPendienteRepository).findLoteAReintentar(
                org.mockito.ArgumentMatchers.argThat(p -> p.getPageSize() == 7));
    }

    /**
     * La transición a ERROR vive en la entidad, no en el job: se verifica acá porque es la
     * regla que define cuándo algo deja de reintentarse solo.
     */
    @Test
    @DisplayName("entidad_alAgotarLosIntentosPasaAError")
    void entidad_alAgotarLosIntentosPasaAError() {
        EmailPendiente pendiente = pendiente(1L, "jugador@saque.test");

        pendiente.registrarFallo("fallo 1", INTENTOS_MAXIMOS);
        assertEquals(EstadoEmailPendiente.PENDIENTE, pendiente.getEstado());
        pendiente.registrarFallo("fallo 2", INTENTOS_MAXIMOS);
        assertEquals(EstadoEmailPendiente.PENDIENTE, pendiente.getEstado());
        pendiente.registrarFallo("fallo 3", INTENTOS_MAXIMOS);

        assertEquals(EstadoEmailPendiente.ERROR, pendiente.getEstado());
        assertEquals(3, pendiente.getIntentos());
    }

    @Test
    @DisplayName("entidad_recortaUnErrorMasLargoQueLaColumna")
    void entidad_recortaUnErrorMasLargoQueLaColumna() {
        EmailPendiente pendiente = pendiente(1L, "jugador@saque.test");

        pendiente.registrarFallo("x".repeat(2000), INTENTOS_MAXIMOS);

        assertEquals(EmailPendiente.LARGO_MAXIMO_ERROR, pendiente.getUltimoError().length());
    }
}
