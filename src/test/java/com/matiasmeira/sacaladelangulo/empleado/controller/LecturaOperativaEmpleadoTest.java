package com.matiasmeira.sacaladelangulo.empleado.controller;

import com.matiasmeira.sacaladelangulo.auth.model.PermisoEmpleado;
import com.matiasmeira.sacaladelangulo.auth.model.Role;
import com.matiasmeira.sacaladelangulo.auth.model.Usuario;
import com.matiasmeira.sacaladelangulo.auth.repository.UsuarioRepository;
import com.matiasmeira.sacaladelangulo.establecimiento.model.Establecimiento;
import com.matiasmeira.sacaladelangulo.establecimiento.repository.EstablecimientoRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDate;
import java.util.Set;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
