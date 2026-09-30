package com.matiasmeira.sacaladelangulo.seguridad;

import com.matiasmeira.sacaladelangulo.auth.model.Usuario;
import com.matiasmeira.sacaladelangulo.establecimiento.model.Cancha;
import com.matiasmeira.sacaladelangulo.establecimiento.model.Deporte;
import com.matiasmeira.sacaladelangulo.reserva.model.EstadoReserva;
import com.matiasmeira.sacaladelangulo.reserva.model.Reserva;
import com.matiasmeira.sacaladelangulo.reserva.repository.ReservaRepository;
import com.matiasmeira.sacaladelangulo.support.AbstractSecurityWebTest;
import com.matiasmeira.sacaladelangulo.support.Canchas;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.ResultActions;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Padrón de clientes: GET /api/v1/establecimientos/{id}/clientes, /{jugadorId} y /{jugadorId}/reservas.
 * Los tres llevan @PreAuthorize OWNER/ADMIN (ClienteController:35, 47, 56) y su service arranca con
 * validarAcceso (ClienteService:165) -> AutorizacionEmpleadoService.validarPropietarioOAdmin (línea 135),
 * invocado desde listarClientes (ClienteService:49), obtenerDetalle (:124) y listarReservasDeCliente (:193):
 * el dueño ajeno recibe AccessDeniedException("No autorizado en este establecimiento") -> 403.
 * El 401 sin token lo cubre SinTokenBarridoTest.
 *
 * <p>Escenario: {@code cliente} tiene una reserva FINALIZADA en canchaA (complejo A) y otra en canchaB
 * (complejo B); {@code clienteDeB} sólo tiene una en canchaB.
 */
@DisplayName("Padrón de clientes /api/v1/establecimientos/{id}/clientes")
class ClientesAutorizacionTest extends AbstractSecurityWebTest {

    private static final String MENSAJE_DUENO_AJENO = "No autorizado en este establecimiento";

    @Autowired
    private ReservaRepository reservaRepository;

    private Cancha canchaB;
    private Usuario cliente;
    private Usuario clienteDeB;

    @BeforeEach
    void sembrarClientes() {
        canchaB = canchaRepository.save(Canchas.canchaActiva(establecimientoB));
        cliente = jugadorExtra();
        clienteDeB = jugadorExtra();
        reservaFinalizada(cliente, canchaA, 3);
        reservaFinalizada(cliente, canchaB, 2);
        reservaFinalizada(clienteDeB, canchaB, 1);
    }

    private void reservaFinalizada(Usuario jugadorDeLaReserva, Cancha cancha, int diasAtras) {
        LocalDateTime inicio = LocalDateTime.now().minusDays(diasAtras).withHour(18).withMinute(0).withSecond(0).withNano(0);
        reservaRepository.save(Reserva.builder()
                .cancha(cancha)
                .jugador(jugadorDeLaReserva)
                .deporteSeleccionado(Deporte.PADEL)
                .fechaHoraInicio(inicio)
                .fechaHoraFin(inicio.plusMinutes(90))
                .estado(EstadoReserva.FINALIZADA)
                .precioTotal(new BigDecimal("1000"))
                .senaPagada(BigDecimal.ZERO)
                .build());
    }

    private String listado() {
        return "/api/v1/establecimientos/" + establecimientoA.getId() + "/clientes";
    }

    private String detalle(Usuario jugadorConsultado) {
        return listado() + "/" + jugadorConsultado.getId();
    }

    private String reservas(Usuario jugadorConsultado) {
        return detalle(jugadorConsultado) + "/reservas";
    }

    private ResultActions pedir(String ruta, Usuario quien) throws Exception {
        return mockMvc.perform(get(ruta).header("Authorization", bearer(quien)));
    }

    private void assertPreauthorize(ResultActions respuesta) throws Exception {
        respuesta.andExpect(status().isForbidden()).andExpect(jsonPath("$.error").value(ERROR_PREAUTHORIZE));
    }

    private void assertDuenoAjeno(ResultActions respuesta) throws Exception {
        respuesta.andExpect(status().isForbidden()).andExpect(jsonPath("$.error").value(MENSAJE_DUENO_AJENO));
    }

    private void assertListadoDeA(ResultActions respuesta) throws Exception {
        respuesta.andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].jugadorId").value(cliente.getId()))
                .andExpect(jsonPath("$.content[0].reservasTotales").value(1));
    }

    private void assertDetalleDeA(ResultActions respuesta) throws Exception {
        respuesta.andExpect(status().isOk())
                .andExpect(jsonPath("$.cliente.jugadorId").value(cliente.getId()))
                .andExpect(jsonPath("$.cliente.reservasTotales").value(1));
    }

    private void assertReservasDeA(ResultActions respuesta) throws Exception {
        respuesta.andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].canchaId").value(canchaA.getId()));
    }

    // ---- listado ----

    @Test
    @DisplayName("listado_jugador_Devuelve403")
    void listado_jugador_Devuelve403() throws Exception {
        assertPreauthorize(pedir(listado(), jugador));
    }

    @Test
    @DisplayName("listado_empleado_Devuelve403")
    void listado_empleado_Devuelve403() throws Exception {
        assertPreauthorize(pedir(listado(), empleadoConPermiso));
    }

    @Test
    @DisplayName("listado_duenoAjeno_Devuelve403")
    void listado_duenoAjeno_Devuelve403() throws Exception {
        assertDuenoAjeno(pedir(listado(), duenoB));
    }

    @Test
    @DisplayName("listado_admin_Devuelve200")
    void listado_admin_Devuelve200() throws Exception {
        assertListadoDeA(pedir(listado(), admin));
    }

    @Test
    @DisplayName("listado_duenoPropio_Devuelve200")
    void listado_duenoPropio_Devuelve200() throws Exception {
        assertListadoDeA(pedir(listado(), duenoA));
    }

    // ---- detalle ----

    @Test
    @DisplayName("detalle_jugador_Devuelve403")
    void detalle_jugador_Devuelve403() throws Exception {
        assertPreauthorize(pedir(detalle(cliente), jugador));
    }

    @Test
    @DisplayName("detalle_empleado_Devuelve403")
    void detalle_empleado_Devuelve403() throws Exception {
        assertPreauthorize(pedir(detalle(cliente), empleadoConPermiso));
    }

    @Test
    @DisplayName("detalle_duenoAjeno_Devuelve403")
    void detalle_duenoAjeno_Devuelve403() throws Exception {
        assertDuenoAjeno(pedir(detalle(cliente), duenoB));
    }

    @Test
    @DisplayName("detalle_admin_Devuelve200")
    void detalle_admin_Devuelve200() throws Exception {
        assertDetalleDeA(pedir(detalle(cliente), admin));
    }

    @Test
    @DisplayName("detalle_duenoPropio_Devuelve200")
    void detalle_duenoPropio_Devuelve200() throws Exception {
        assertDetalleDeA(pedir(detalle(cliente), duenoA));
    }

    // ---- reservas del cliente ----

    @Test
    @DisplayName("reservas_jugador_Devuelve403")
    void reservas_jugador_Devuelve403() throws Exception {
        assertPreauthorize(pedir(reservas(cliente), jugador));
    }

    @Test
    @DisplayName("reservas_empleado_Devuelve403")
    void reservas_empleado_Devuelve403() throws Exception {
        assertPreauthorize(pedir(reservas(cliente), empleadoConPermiso));
    }

    @Test
    @DisplayName("reservas_duenoAjeno_Devuelve403")
    void reservas_duenoAjeno_Devuelve403() throws Exception {
        assertDuenoAjeno(pedir(reservas(cliente), duenoB));
    }

    @Test
    @DisplayName("reservas_admin_Devuelve200")
    void reservas_admin_Devuelve200() throws Exception {
        assertReservasDeA(pedir(reservas(cliente), admin));
    }

    @Test
    @DisplayName("reservas_duenoPropio_Devuelve200")
    void reservas_duenoPropio_Devuelve200() throws Exception {
        assertReservasDeA(pedir(reservas(cliente), duenoA));
    }

    // ---- aislamiento entre complejos ----

    @Test
    @DisplayName("aislamiento_detalleDeClienteDeOtroComplejo_Devuelve404")
    void aislamiento_detalleDeClienteDeOtroComplejo_Devuelve404() throws Exception {
        // clienteDeB no tiene reservas en A: ClienteService.obtenerDetalle (:125) lanza EntityNotFoundException.
        pedir(detalle(clienteDeB), duenoA)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("El jugador no tiene reservas en este establecimiento"));
    }

    @Test
    @DisplayName("aislamiento_reservasDeClienteDeOtroComplejo_Devuelve200Vacio")
    void aislamiento_reservasDeClienteDeOtroComplejo_Devuelve200Vacio() throws Exception {
        // Comportamiento actual: la query filtra por establecimiento y devuelve página vacía, no 404.
        pedir(reservas(clienteDeB), duenoA)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(0))
                .andExpect(jsonPath("$.content").isEmpty());
    }
}
