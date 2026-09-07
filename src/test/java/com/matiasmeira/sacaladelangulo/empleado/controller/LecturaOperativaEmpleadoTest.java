package com.matiasmeira.sacaladelangulo.empleado.controller;

import com.matiasmeira.sacaladelangulo.auth.model.PermisoEmpleado;
import com.matiasmeira.sacaladelangulo.auth.model.Role;
import com.matiasmeira.sacaladelangulo.auth.model.Usuario;
import com.matiasmeira.sacaladelangulo.auth.repository.UsuarioRepository;
import com.matiasmeira.sacaladelangulo.establecimiento.model.Cancha;
import com.matiasmeira.sacaladelangulo.establecimiento.model.Deporte;
import com.matiasmeira.sacaladelangulo.establecimiento.model.Establecimiento;
import com.matiasmeira.sacaladelangulo.establecimiento.repository.CanchaRepository;
import com.matiasmeira.sacaladelangulo.establecimiento.repository.EstablecimientoRepository;
import com.matiasmeira.sacaladelangulo.reserva.model.EstadoTurnoFijo;
import com.matiasmeira.sacaladelangulo.reserva.model.TurnoFijo;
import com.matiasmeira.sacaladelangulo.reserva.repository.TurnoFijoRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Set;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Un permiso de acción no sirve de nada si el empleado no puede leer la pantalla desde
 * donde se ejerce.
 *
 * Los listados del establecimiento eran todos validarPropietarioOAdmin, así que un
 * empleado con FINALIZAR_RESERVA recibía 403 al pedir la agenda: tenía permitido cobrar
 * un turno pero no podía llegar al id de ninguno. Lo mismo con REGISTRAR_VENTA_BUFFET y
 * el catálogo de productos.
 *
 * Este test fija las dos mitades de la regla nueva: con el permiso ve, sin el permiso
 * no. La lectura sigue siendo tan específica como la acción — no se abrió el listado a
 * "cualquier EMPLOYEE".
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:testdb-lectura-empleado;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "jwt.secret=TestSecretKeyQueSeaSuficientementeLargaParaValidarElTest123",
        "spring.config.import=",
        "spring.flyway.enabled=false"
})
class LecturaOperativaEmpleadoTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UsuarioRepository usuarioRepository;

    @Autowired
    private EstablecimientoRepository establecimientoRepository;

    @Autowired
    private CanchaRepository canchaRepository;

    @Autowired
    private TurnoFijoRepository turnoFijoRepository;

    private Establecimiento establecimiento;

    private Establecimiento sembrarLocal(String sufijo) {
        Usuario dueno = usuarioRepository.save(Usuario.builder()
                .email("dueno-" + sufijo + "@lectura-test.com")
                .password("hash")
                .nombre("Dueno " + sufijo)
                .rol(Role.OWNER)
                .isActive(true)
                .emailVerified(true)
                .telefonoVerificado(true)
                .build());

        return establecimientoRepository.save(Establecimiento.builder()
                .nombre("Complejo " + sufijo)
                .direccion("Calle 123")
                .slug("complejo-lectura-" + sufijo)
                .latitud(-34.5)
                .longitud(-58.7)
                .requiereSena(false)
                .isActive(true)
                .dueno(dueno)
                .build());
    }

    private String sembrarEmpleado(Establecimiento local, String nombre, Set<PermisoEmpleado> permisos) {
        String email = "empleado-" + nombre.toLowerCase() + "@lectura-test.interno";
        usuarioRepository.save(Usuario.builder()
                .email(email)
                .password("hash")
                .nombre(nombre)
                .rol(Role.EMPLOYEE)
                .isActive(true)
                .emailVerified(true)
                .telefonoVerificado(true)
                .establecimiento(local)
                .permisos(permisos)
                .build());
        return email;
    }

    private TurnoFijo sembrarTurnoFijo(Establecimiento local) {
        Cancha cancha = canchaRepository.save(Cancha.builder()
                .nombre("Cancha lectura")
                .deportes(Set.of(Deporte.FUTBOL_5))
                .isActive(true)
                .precioBase(BigDecimal.valueOf(1000))
                .montoSena(BigDecimal.valueOf(200))
                .establecimiento(local)
                .build());

        return turnoFijoRepository.save(TurnoFijo.builder()
                .cancha(cancha)
                .deporteSeleccionado(Deporte.FUTBOL_5)
                .diaSemana(DayOfWeek.TUESDAY)
                .horaInicio(LocalTime.of(20, 0))
                .horaFin(LocalTime.of(21, 0))
                .fechaInicioPeriodo(LocalDate.of(2030, 1, 1))
                .fechaFinPeriodo(LocalDate.of(2030, 12, 31))
                .estado(EstadoTurnoFijo.ACTIVO)
                .nombreClienteManual("Cliente Fijo")
                .build());
    }

    @Test
    @DisplayName("agenda_EmpleadoConPermisoDeReserva_LaVe")
    void agenda_EmpleadoConPermisoDeReserva_LaVe() throws Exception {
        establecimiento = sembrarLocal("agenda-si");
        String email = sembrarEmpleado(establecimiento, "Cobrador", Set.of(PermisoEmpleado.FINALIZAR_RESERVA));

        mockMvc.perform(get("/api/v1/reservas/establecimiento/" + establecimiento.getId())
                        .param("fecha", LocalDate.now().toString())
                        .with(user(email).roles("EMPLOYEE")))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("agenda_EmpleadoSoloDeCaja_NoLaVe")
    void agenda_EmpleadoSoloDeCaja_NoLaVe() throws Exception {
        establecimiento = sembrarLocal("agenda-no");
        // OPERAR_CAJA no habilita la agenda: la lectura sigue atada a las acciones que
        // se ejercen desde ella, no a "ser empleado".
        String email = sembrarEmpleado(establecimiento, "Cajero", Set.of(PermisoEmpleado.OPERAR_CAJA));

        mockMvc.perform(get("/api/v1/reservas/establecimiento/" + establecimiento.getId())
                        .param("fecha", LocalDate.now().toString())
                        .with(user(email).roles("EMPLOYEE")))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("agenda_EmpleadoDeOtroLocal_NoLaVe")
    void agenda_EmpleadoDeOtroLocal_NoLaVe() throws Exception {
        establecimiento = sembrarLocal("agenda-propio");
        Establecimiento ajeno = sembrarLocal("agenda-ajeno");
        // Tiene el permiso, pero en OTRO establecimiento.
        String email = sembrarEmpleado(ajeno, "Intruso", Set.of(PermisoEmpleado.FINALIZAR_RESERVA));

        mockMvc.perform(get("/api/v1/reservas/establecimiento/" + establecimiento.getId())
                        .param("fecha", LocalDate.now().toString())
                        .with(user(email).roles("EMPLOYEE")))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("turnosFijos_EmpleadoConPermisoDeReserva_LoVe")
    void turnosFijos_EmpleadoConPermisoDeReserva_LoVe() throws Exception {
        establecimiento = sembrarLocal("turnos-fijos-listado-si");
        // Misma regla que la agenda: el listado de series es lectura, no escritura, así
        // que cualquiera de los permisos operativos de reserva alcanza (ver
        // AutorizacionEmpleadoService.PERMISOS_OPERATIVOS_DE_RESERVA).
        String email = sembrarEmpleado(establecimiento, "Cobrador2", Set.of(PermisoEmpleado.FINALIZAR_RESERVA));

        mockMvc.perform(get("/api/v1/turnos-fijos")
                        .param("establecimientoId", establecimiento.getId().toString())
                        .with(user(email).roles("EMPLOYEE")))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("turnosFijos_EmpleadoDeOtroLocal_NoLoVe")
    void turnosFijos_EmpleadoDeOtroLocal_NoLoVe() throws Exception {
        establecimiento = sembrarLocal("turnos-fijos-listado-propio");
        Establecimiento ajeno = sembrarLocal("turnos-fijos-listado-ajeno");
        // Tiene el permiso, pero en OTRO establecimiento: no puede ver las series ajenas.
        String email = sembrarEmpleado(ajeno, "Intruso2", Set.of(PermisoEmpleado.FINALIZAR_RESERVA));

        mockMvc.perform(get("/api/v1/turnos-fijos")
                        .param("establecimientoId", establecimiento.getId().toString())
                        .with(user(email).roles("EMPLOYEE")))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("turnoFijoDetalle_EmpleadoConPermisoDeReserva_LoVe")
    void turnoFijoDetalle_EmpleadoConPermisoDeReserva_LoVe() throws Exception {
        establecimiento = sembrarLocal("turnos-fijos-detalle-si");
        TurnoFijo turnoFijo = sembrarTurnoFijo(establecimiento);
        String email = sembrarEmpleado(establecimiento, "Cobrador3", Set.of(PermisoEmpleado.FINALIZAR_RESERVA));

        mockMvc.perform(get("/api/v1/turnos-fijos/" + turnoFijo.getId())
                        .with(user(email).roles("EMPLOYEE")))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("turnoFijoDetalle_EmpleadoDeOtroLocal_NoLoVe")
    void turnoFijoDetalle_EmpleadoDeOtroLocal_NoLoVe() throws Exception {
        establecimiento = sembrarLocal("turnos-fijos-detalle-propio");
        TurnoFijo turnoFijo = sembrarTurnoFijo(establecimiento);
        Establecimiento ajeno = sembrarLocal("turnos-fijos-detalle-ajeno");
        // Tiene el permiso, pero en OTRO establecimiento: no puede ver el detalle de una
        // serie ajena aunque conozca (o adivine) su id — es justo el caso IDOR.
        String email = sembrarEmpleado(ajeno, "Intruso3", Set.of(PermisoEmpleado.FINALIZAR_RESERVA));

        mockMvc.perform(get("/api/v1/turnos-fijos/" + turnoFijo.getId())
                        .with(user(email).roles("EMPLOYEE")))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("turnoFijoCancelar_Empleado_NoPuedeAunqueTengaElPermisoDeReserva")
    void turnoFijoCancelar_Empleado_NoPuedeAunqueTengaElPermisoDeReserva() throws Exception {
        establecimiento = sembrarLocal("turnos-fijos-cancelar");
        TurnoFijo turnoFijo = sembrarTurnoFijo(establecimiento);
        // FINALIZAR_RESERVA alcanza para leer la agenda y el listado de series (ver los
        // tests de arriba), pero dar de baja una serie es escritura reservada a OWNER/ADMIN:
        // ni con permisos operativos de reserva un empleado puede cancelarla. Sin body: el
        // endpoint acepta CancelarTurnoFijoRequest opcional, y esto de paso cubre ese wiring.
        String email = sembrarEmpleado(establecimiento, "Cobrador4", Set.of(PermisoEmpleado.FINALIZAR_RESERVA));

        mockMvc.perform(post("/api/v1/turnos-fijos/" + turnoFijo.getId() + "/cancelar")
                        .with(user(email).roles("EMPLOYEE")))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("productosBuffet_EmpleadoQuePuedeVender_VeElCatalogo")
    void productosBuffet_EmpleadoQuePuedeVender_VeElCatalogo() throws Exception {
        establecimiento = sembrarLocal("buffet-si");
        String email = sembrarEmpleado(establecimiento, "Vendedor", Set.of(PermisoEmpleado.REGISTRAR_VENTA_BUFFET));

        mockMvc.perform(get("/api/v1/establecimientos/" + establecimiento.getId() + "/productos-buffet")
                        .with(user(email).roles("EMPLOYEE")))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("productosBuffet_EmpleadoSinEsePermiso_NoVeElCatalogo")
    void productosBuffet_EmpleadoSinEsePermiso_NoVeElCatalogo() throws Exception {
        establecimiento = sembrarLocal("buffet-no");
        String email = sembrarEmpleado(establecimiento, "Cajero2", Set.of(PermisoEmpleado.OPERAR_CAJA));

        mockMvc.perform(get("/api/v1/establecimientos/" + establecimiento.getId() + "/productos-buffet")
                        .with(user(email).roles("EMPLOYEE")))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("canchas_EmpleadoQuePuedeCargarTurnos_VeElListado")
    void canchas_EmpleadoQuePuedeCargarTurnos_VeElListado() throws Exception {
        establecimiento = sembrarLocal("canchas-si");
        String email = sembrarEmpleado(establecimiento, "Mostrador", Set.of(PermisoEmpleado.CREAR_RESERVA_MANUAL));

        mockMvc.perform(get("/api/v1/establecimientos/" + establecimiento.getId() + "/canchas")
                        .with(user(email).roles("EMPLOYEE")))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("canchas_EmpleadoQueSoloCobra_TambienLoVe")
    void canchas_EmpleadoQueSoloCobra_TambienLoVe() throws Exception {
        establecimiento = sembrarLocal("canchas-cobrador");
        // La agenda se dibuja POR cancha: sin este listado no se renderiza, aunque la
        // persona sólo vaya a cobrar y no a cargar turnos.
        String email = sembrarEmpleado(establecimiento, "SoloCobra", Set.of(PermisoEmpleado.FINALIZAR_RESERVA));

        mockMvc.perform(get("/api/v1/establecimientos/" + establecimiento.getId() + "/canchas")
                        .with(user(email).roles("EMPLOYEE")))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("canchas_EmpleadoSinEsePermiso_NoVeElListado")
    void canchas_EmpleadoSinEsePermiso_NoVeElListado() throws Exception {
        establecimiento = sembrarLocal("canchas-no");
        String email = sembrarEmpleado(establecimiento, "Cajero3", Set.of(PermisoEmpleado.OPERAR_CAJA));

        mockMvc.perform(get("/api/v1/establecimientos/" + establecimiento.getId() + "/canchas")
                        .with(user(email).roles("EMPLOYEE")))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("lecturasQueSiguenSiendoDelDueno_ElEmpleadoNoLasVe")
    void lecturasQueSiguenSiendoDelDueno_ElEmpleadoNoLasVe() throws Exception {
        establecimiento = sembrarLocal("cerradas");
        // Con TODOS los permisos: lo que sigue cerrado no es por falta de permiso, es
        // que esos listados no son del mostrador.
        String email = sembrarEmpleado(establecimiento, "Todopoderoso", Set.of(PermisoEmpleado.values()));
        Long id = establecimiento.getId();

        mockMvc.perform(get("/api/v1/establecimientos/" + id + "/clientes").with(user(email).roles("EMPLOYEE")))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v1/establecimientos/" + id + "/empleados").with(user(email).roles("EMPLOYEE")))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v1/establecimientos/" + id + "/caja/turnos").with(user(email).roles("EMPLOYEE")))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v1/establecimientos/" + id + "/reportes/facturacion")
                        .param("desde", LocalDate.now().toString())
                        .param("hasta", LocalDate.now().toString())
                        .with(user(email).roles("EMPLOYEE")))
                .andExpect(status().isForbidden());
    }
}
