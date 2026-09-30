package com.matiasmeira.sacaladelangulo.seguridad;

import com.matiasmeira.sacaladelangulo.auth.model.PermisoEmpleado;
import com.matiasmeira.sacaladelangulo.auth.model.Usuario;
import com.matiasmeira.sacaladelangulo.establecimiento.model.BloqueoCancha;
import com.matiasmeira.sacaladelangulo.establecimiento.repository.BloqueoCanchaRepository;
import com.matiasmeira.sacaladelangulo.support.AbstractSecurityWebTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.ResultActions;

import java.time.LocalDate;
import java.util.EnumSet;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * GET /api/v1/establecimientos/{id}/bloqueos?fecha=. Cualquier rol autenticado ve los horarios
 * bloqueados (BloqueoCanchaController @PreAuthorize), pero el motivo (notas internas) sólo lo ve
 * quien tiene acceso de panel al establecimiento: BloqueoCanchaService.listarPorEstablecimientoYFecha
 * delega en AutorizacionEmpleadoService.tieneAccesoDePanel (pendiente 51).
 */
@DisplayName("GET /api/v1/establecimientos/{id}/bloqueos - motivo del bloqueo")
class BloqueoMotivoAutorizacionTest extends AbstractSecurityWebTest {

    private static final String MOTIVO = "Reclamo del proveedor de mantenimiento";

    @Autowired
    private BloqueoCanchaRepository bloqueoCanchaRepository;

    private BloqueoCancha bloqueo;

    @BeforeEach
    void sembrarBloqueo() {
        LocalDate manana = LocalDate.now().plusDays(1);
        bloqueo = bloqueoCanchaRepository.save(BloqueoCancha.builder()
                .cancha(canchaA)
                .fechaInicio(manana.atTime(10, 0))
                .fechaFin(manana.atTime(12, 0))
                .motivo(MOTIVO)
                .build());
    }

    private ResultActions listar(Usuario usuario) throws Exception {
        return mockMvc.perform(get("/api/v1/establecimientos/" + establecimientoA.getId() + "/bloqueos")
                        .param("fecha", LocalDate.now().plusDays(1).toString())
                        .header("Authorization", bearer(usuario)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(bloqueo.getId()));
    }

    private void verMotivo(Usuario usuario) throws Exception {
        listar(usuario).andExpect(jsonPath("$[0].motivo").value(MOTIVO));
    }

    private void noVerMotivo(Usuario usuario) throws Exception {
        listar(usuario).andExpect(jsonPath("$[0].motivo").value((Object) null));
    }

    @Test
    @DisplayName("duenoDelEstablecimiento_VeElMotivo")
    void duenoDelEstablecimiento_VeElMotivo() throws Exception {
        verMotivo(duenoA);
    }

    @Test
    @DisplayName("admin_VeElMotivo")
    void admin_VeElMotivo() throws Exception {
        verMotivo(admin);
    }

    @Test
    @DisplayName("empleadoConPermisoDelEstablecimiento_VeElMotivo")
    void empleadoConPermisoDelEstablecimiento_VeElMotivo() throws Exception {
        verMotivo(empleadoConPermiso);
    }

    @Test
    @DisplayName("empleadoSinPermisos_NoVeElMotivo")
    void empleadoSinPermisos_NoVeElMotivo() throws Exception {
        noVerMotivo(empleadoSinPermiso);
    }

    @Test
    @DisplayName("duenoDeOtroEstablecimiento_NoVeElMotivo")
    void duenoDeOtroEstablecimiento_NoVeElMotivo() throws Exception {
        noVerMotivo(duenoB);
    }

    @Test
    @DisplayName("empleadoConPermisoDeOtroEstablecimiento_NoVeElMotivo")
    void empleadoConPermisoDeOtroEstablecimiento_NoVeElMotivo() throws Exception {
        noVerMotivo(empleado(establecimientoB, EnumSet.allOf(PermisoEmpleado.class)));
    }

    @Test
    @DisplayName("jugador_NoVeElMotivo")
    void jugador_NoVeElMotivo() throws Exception {
        noVerMotivo(jugador);
    }
}
