package com.matiasmeira.sacaladelangulo.establecimiento.repository;

import com.matiasmeira.sacaladelangulo.auth.model.PlanSuscripcion;
import com.matiasmeira.sacaladelangulo.auth.model.Role;
import com.matiasmeira.sacaladelangulo.auth.model.Usuario;
import com.matiasmeira.sacaladelangulo.establecimiento.model.Cancha;
import com.matiasmeira.sacaladelangulo.establecimiento.model.Deporte;
import com.matiasmeira.sacaladelangulo.establecimiento.model.Establecimiento;
import com.matiasmeira.sacaladelangulo.establecimiento.model.EstadoVerificacion;
import com.matiasmeira.sacaladelangulo.support.Establecimientos;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.data.domain.Pageable;
import org.springframework.test.context.TestPropertySource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Valida contra una base real (H2) que findCercanosYPorDeporte siga siendo JPQL válido
 * después de agregarle el pre-filtro de bounding box (ver M29 en la auditoría): un test
 * puramente mockeado no detecta un error de sintaxis en funciones nativas (RADIANS/COS/
 * ACOS) que solo se resuelven contra un motor de base real.
 */
@DataJpaTest
// ddl-auto=validate es el default de la config base (ver A10): la base embebida que usa
// @DataJpaTest arranca vacía, así que acá hace falta create-drop para generar el esquema.
// spring.flyway.enabled=false evita que FlywayAutoConfiguration corra las migraciones de
// Postgres (ver db/migration/V1__baseline.sql) contra este H2 en memoria.
@TestPropertySource(properties = {
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.flyway.enabled=false"
})
@DisplayName("EstablecimientoRepository - Búsqueda geográfica (ver M29)")
class EstablecimientoRepositoryTest {

    @Autowired
    private TestEntityManager entityManager;

    @Autowired
    private EstablecimientoRepository establecimientoRepository;

    @Test
    @DisplayName("findCercanosYPorDeporte_DevuelveSoloElEstablecimientoDentroDelRadio")
    void findCercanosYPorDeporte_DevuelveSoloElEstablecimientoDentroDelRadio() {
        Usuario dueno = entityManager.persist(Usuario.builder()
                .email("dueno@test.com")
                .password("hash")
                .nombre("Carlos")
                .rol(Role.OWNER)
                .planSuscripcion(PlanSuscripcion.TRIAL)
                .isActive(true)
                .emailVerified(true)
                .telefonoVerificado(false)
                .build());

        // Obelisco, CABA
        Establecimiento cercano = entityManager.persist(Establecimientos.establecimientoOperativo(b -> b
                .nombre("Cercano")
                .direccion("Cerca")
                .slug("cercano")
                .latitud(-34.6037)
                .longitud(-58.3816)
                .requiereSena(true)
                .dueno(dueno)));

        // Ushuaia, a ~2500km de CABA: bien fuera del radio de búsqueda
        entityManager.persist(Establecimientos.establecimientoOperativo(b -> b
                .nombre("Lejano")
                .direccion("Lejos")
                .slug("lejano")
                .latitud(-54.8019)
                .longitud(-68.3030)
                .requiereSena(true)
                .dueno(dueno)));

        // Mismas coordenadas que "cercano" (dentro del radio) pero sin verificar: no debe
        // aparecer en el buscador aunque geográficamente calificaría.
        entityManager.persist(Establecimientos.establecimientoPendiente(b -> b
                .nombre("Cercano Sin Verificar")
                .direccion("Cerca")
                .slug("cercano-sin-verificar")
                .latitud(-34.6037)
                .longitud(-58.3816)
                .requiereSena(true)
                .dueno(dueno)));

        entityManager.flush();

        List<Establecimiento> resultado = establecimientoRepository.findCercanosYPorDeporte(
                -34.6037, -58.3816, 10.0, null);

        assertEquals(1, resultado.size());
        assertEquals(cercano.getId(), resultado.get(0).getId());
    }

    @Test
    @DisplayName("existsBySlug_DevuelveTrueSoloParaUnSlugYaAsignado")
    void existsBySlug_DevuelveTrueSoloParaUnSlugYaAsignado() {
        Usuario dueno = entityManager.persist(Usuario.builder()
                .email("dueno2@test.com")
                .password("hash")
                .nombre("Carlos")
                .rol(Role.OWNER)
                .planSuscripcion(PlanSuscripcion.TRIAL)
                .isActive(true)
                .emailVerified(true)
                .telefonoVerificado(false)
                .build());
        entityManager.persist(Establecimientos.establecimientoOperativo(b -> b
                .nombre("Complejo Uno")
                .direccion("Calle Uno")
                .slug("complejo-uno")
                .latitud(-34.6)
                .longitud(-58.4)
                .requiereSena(false)
                .dueno(dueno)));
        entityManager.flush();

        assertTrue(establecimientoRepository.existsBySlug("complejo-uno"));
        assertFalse(establecimientoRepository.existsBySlug("complejo-dos"));
    }

    @Test
    @DisplayName("findBySlugOperativo_NoDevuelveComplejosInactivos")
    void findBySlugOperativo_NoDevuelveComplejosInactivos() {
        Usuario dueno = entityManager.persist(Usuario.builder()
                .email("dueno3@test.com")
                .password("hash")
                .nombre("Carlos")
                .rol(Role.OWNER)
                .planSuscripcion(PlanSuscripcion.TRIAL)
                .isActive(true)
                .emailVerified(true)
                .telefonoVerificado(false)
                .build());
        entityManager.persist(Establecimientos.establecimientoDeshabilitado(b -> b
                .nombre("Complejo Inactivo")
                .direccion("Calle Dos")
                .slug("complejo-inactivo")
                .latitud(-34.6)
                .longitud(-58.4)
                .requiereSena(false)
                .dueno(dueno)));
        entityManager.flush();

        assertTrue(establecimientoRepository.findBySlugOperativo("complejo-inactivo").isEmpty());
        assertTrue(establecimientoRepository.findBySlugOperativo("no-existe").isEmpty());
    }

    @Test
    @DisplayName("findBySlugOperativo_NoDevuelveComplejosNoVerificados")
    void findBySlugOperativo_NoDevuelveComplejosNoVerificados() {
        Usuario dueno = entityManager.persist(Usuario.builder()
                .email("dueno3b@test.com")
                .password("hash")
                .nombre("Carlos")
                .rol(Role.OWNER)
                .planSuscripcion(PlanSuscripcion.TRIAL)
                .isActive(true)
                .emailVerified(true)
                .telefonoVerificado(false)
                .build());
        entityManager.persist(Establecimientos.establecimientoPendiente(b -> b
                .nombre("Complejo Pendiente")
                .direccion("Calle Dos B")
                .slug("complejo-pendiente")
                .latitud(-34.6)
                .longitud(-58.4)
                .requiereSena(false)
                .dueno(dueno)));
        entityManager.flush();

        assertTrue(establecimientoRepository.findBySlugOperativo("complejo-pendiente").isEmpty());
    }

    @Test
    @DisplayName("findBySlugOperativo_DevuelveElComplejoActivoYVerificado")
    void findBySlugOperativo_DevuelveElComplejoActivoYVerificado() {
        Usuario dueno = entityManager.persist(Usuario.builder()
                .email("dueno3c@test.com")
                .password("hash")
                .nombre("Carlos")
                .rol(Role.OWNER)
                .planSuscripcion(PlanSuscripcion.TRIAL)
                .isActive(true)
                .emailVerified(true)
                .telefonoVerificado(false)
                .build());
        Establecimiento operativo = entityManager.persist(Establecimientos.establecimientoOperativo(b -> b
                .nombre("Complejo Operativo")
                .direccion("Calle Dos C")
                .slug("complejo-operativo")
                .latitud(-34.6)
                .longitud(-58.4)
                .requiereSena(false)
                .dueno(dueno)));
        entityManager.flush();

        assertEquals(operativo.getId(), establecimientoRepository.findBySlugOperativo("complejo-operativo")
                .orElseThrow().getId());
    }

    @Test
    @DisplayName("findActivosPorDeporte_FiltraPorDeporteDeCanchasActivas")
    void findActivosPorDeporte_FiltraPorDeporteDeCanchasActivas() {
        Usuario dueno = entityManager.persist(Usuario.builder()
                .email("dueno4@test.com")
                .password("hash")
                .nombre("Carlos")
                .rol(Role.OWNER)
                .planSuscripcion(PlanSuscripcion.TRIAL)
                .isActive(true)
                .emailVerified(true)
                .telefonoVerificado(false)
                .build());
        Establecimiento conPadel = entityManager.persist(Establecimientos.establecimientoOperativo(b -> b
                .nombre("Con Padel")
                .direccion("Calle Tres")
                .slug("con-padel")
                .latitud(-34.6)
                .longitud(-58.4)
                .requiereSena(false)
                .dueno(dueno)));
        entityManager.persist(Cancha.builder()
                .nombre("Cancha 1")
                .deportes(java.util.Set.of(Deporte.PADEL))
                .isActive(true)
                .precioBase(java.math.BigDecimal.valueOf(1000))
                .montoSena(java.math.BigDecimal.valueOf(200))
                .establecimiento(conPadel)
                .build());
        entityManager.persist(Establecimientos.establecimientoOperativo(b -> b
                .nombre("Sin Padel")
                .direccion("Calle Cuatro")
                .slug("sin-padel")
                .latitud(-34.6)
                .longitud(-58.4)
                .requiereSena(false)
                .dueno(dueno)));
        entityManager.persist(Establecimientos.establecimientoEnRevision(b -> b
                .nombre("Sin Verificar")
                .direccion("Calle Cinco")
                .slug("sin-verificar")
                .latitud(-34.6)
                .longitud(-58.4)
                .requiereSena(false)
                .dueno(dueno)));
        entityManager.flush();

        List<Establecimiento> resultado = establecimientoRepository
                .findActivosPorDeporte(Deporte.PADEL, org.springframework.data.domain.Pageable.unpaged());

        assertEquals(1, resultado.size());
        assertEquals(conPadel.getId(), resultado.get(0).getId());
        // 2, no 3: "Sin Verificar" existe, está activo, pero no cuenta porque no está VERIFICADO.
        assertEquals(2, establecimientoRepository
                .findActivosPorDeporte(null, org.springframework.data.domain.Pageable.unpaged()).size());
    }

    @Test
    @DisplayName("findActivosPorDeporte_ConPageable_AcotaLaCantidadDeFilasDevueltas")
    void findActivosPorDeporte_ConPageable_AcotaLaCantidadDeFilasDevueltas() {
        Usuario dueno = entityManager.persist(Usuario.builder()
                .email("dueno-cap@test.com")
                .password("hash")
                .nombre("Carlos")
                .rol(Role.OWNER)
                .planSuscripcion(PlanSuscripcion.TRIAL)
                .isActive(true)
                .emailVerified(true)
                .telefonoVerificado(false)
                .build());
        for (int i = 0; i < 3; i++) {
            int indice = i;
            entityManager.persist(Establecimientos.establecimientoOperativo(b -> b
                    .nombre("Complejo Cap " + indice)
                    .direccion("Calle " + indice)
                    .slug("complejo-cap-" + indice)
                    .latitud(-34.6)
                    .longitud(-58.4)
                    .requiereSena(false)
                    .dueno(dueno)));
        }
        entityManager.flush();

        List<Establecimiento> resultado = establecimientoRepository
                .findActivosPorDeporte(null, org.springframework.data.domain.PageRequest.of(0, 2));

        assertEquals(2, resultado.size());
    }

    // ---------- deletedAt (baja lógica de establecimientos) ----------

    private Usuario duenoEliminacion(String email) {
        return entityManager.persist(Usuario.builder()
                .email(email)
                .password("hash")
                .nombre("Carlos")
                .rol(Role.OWNER)
                .planSuscripcion(PlanSuscripcion.TRIAL)
                .isActive(true)
                .emailVerified(true)
                .telefonoVerificado(false)
                .build());
    }

    @Test
    @DisplayName("findBySlugOperativo_NoDevuelveComplejoEliminado")
    void findBySlugOperativo_NoDevuelveComplejoEliminado() {
        Usuario dueno = duenoEliminacion("dueno-elim-slug@test.com");
        entityManager.persist(Establecimientos.establecimientoEliminado(b -> b
                .nombre("Complejo Eliminado")
                .direccion("Calle Eliminado")
                .slug("complejo-eliminado-slug-test")
                .latitud(-34.6)
                .longitud(-58.4)
                .requiereSena(false)
                .dueno(dueno)));
        entityManager.flush();

        assertTrue(establecimientoRepository.findBySlugOperativo("complejo-eliminado-slug-test").isEmpty());
    }

    @Test
    @DisplayName("findActivosPorDeporte_y_findCercanosYPorDeporte_NoDevuelvenComplejoEliminado")
    void findActivosPorDeporte_y_findCercanosYPorDeporte_NoDevuelvenComplejoEliminado() {
        Usuario dueno = duenoEliminacion("dueno-elim-buscador@test.com");
        entityManager.persist(Establecimientos.establecimientoEliminado(b -> b
                .nombre("Complejo Eliminado Buscador")
                .direccion("Calle Eliminado")
                .slug("complejo-eliminado-buscador")
                .latitud(-34.6037)
                .longitud(-58.3816)
                .requiereSena(false)
                .dueno(dueno)));
        entityManager.flush();

        assertTrue(establecimientoRepository.findActivosPorDeporte(null, Pageable.unpaged()).isEmpty());
        assertTrue(establecimientoRepository.findCercanosYPorDeporte(-34.6037, -58.3816, 10.0, null).isEmpty());
    }

    @Test
    @DisplayName("findByDuenoIdAndIsActiveTrue_NoDevuelveComplejoEliminado")
    void findByDuenoIdAndIsActiveTrue_NoDevuelveComplejoEliminado() {
        Usuario dueno = duenoEliminacion("dueno-elim-panel@test.com");
        entityManager.persist(Establecimientos.establecimientoOperativo(b -> b
                .nombre("Complejo Vivo")
                .direccion("Calle Vivo")
                .slug("complejo-vivo-panel")
                .latitud(-34.6)
                .longitud(-58.4)
                .requiereSena(false)
                .dueno(dueno)));
        // Nace inactivo (isActive=false, ver invariante) y con deletedAt: sin esto, un
        // establecimiento eliminado "de verdad" (isActive=false) ya quedaría afuera por el
        // propio nombre del método (AndIsActiveTrue) -- este test aísla igual el aporte de
        // deletedAt seteando explícitamente isActive=true, un estado que en producción no
        // debería darse (ver EstablecimientoEliminacionService), para probar que el filtro no
        // depende sólo de ese invariante.
        entityManager.persist(Establecimientos.establecimientoEliminado(b -> b
                .nombre("Complejo Eliminado Panel")
                .direccion("Calle Eliminado")
                .slug("complejo-eliminado-panel")
                .latitud(-34.6)
                .longitud(-58.4)
                .requiereSena(false)
                .isActive(true)
                .dueno(dueno)));
        entityManager.flush();

        List<Establecimiento> misEstablecimientos = establecimientoRepository.findByDuenoIdAndIsActiveTrue(dueno.getId());

        assertEquals(1, misEstablecimientos.size());
        assertEquals("Complejo Vivo", misEstablecimientos.get(0).getNombre());
    }

    @Test
    @DisplayName("countByDuenoIdAndDeletedAtIsNull_ExcluyeEliminadosPeroCuentaDeshabilitados")
    void countByDuenoIdAndDeletedAtIsNull_ExcluyeEliminadosPeroCuentaDeshabilitados() {
        Usuario dueno = duenoEliminacion("dueno-elim-limite@test.com");
        entityManager.persist(Establecimientos.establecimientoOperativo(b -> b
                .nombre("Activo").direccion("Calle").slug("limite-activo").latitud(-34.6).longitud(-58.4)
                .requiereSena(false).dueno(dueno)));
        entityManager.persist(Establecimientos.establecimientoDeshabilitado(b -> b
                .nombre("Deshabilitado").direccion("Calle").slug("limite-deshabilitado").latitud(-34.6).longitud(-58.4)
                .requiereSena(false).dueno(dueno)));
        entityManager.persist(Establecimientos.establecimientoEliminado(b -> b
                .nombre("Eliminado").direccion("Calle").slug("limite-eliminado").latitud(-34.6).longitud(-58.4)
                .requiereSena(false).dueno(dueno)));
        entityManager.flush();

        // Deshabilitar no libera cupo (cuenta 2: activo + deshabilitado); eliminar sí
        // (el eliminado no entra en la cuenta).
        assertEquals(2, establecimientoRepository.countByDuenoIdAndDeletedAtIsNull(dueno.getId()));
    }

    @Test
    @DisplayName("findByEstadoVerificacionAndDeletedAtIsNull_y_findAllByDeletedAtIsNull_ExcluyenEliminadoDeLaColaDeAdmin")
    void findByEstadoVerificacionAndDeletedAtIsNull_y_findAllByDeletedAtIsNull_ExcluyenEliminadoDeLaColaDeAdmin() {
        Usuario dueno = duenoEliminacion("dueno-elim-admin@test.com");
        entityManager.persist(Establecimientos.establecimientoEnRevision(b -> b
                .nombre("En Revision").direccion("Calle").slug("admin-en-revision").latitud(-34.6).longitud(-58.4)
                .requiereSena(false).dueno(dueno)));
        // Eliminado, pero todavía EN_REVISION al momento de la baja: aunque el admin nunca
        // llegó a resolverla, un establecimiento eliminado no debe aparecer en ninguna cola.
        entityManager.persist(Establecimientos.establecimientoEliminado(b -> b
                .nombre("Eliminado En Revision").direccion("Calle").slug("admin-eliminado-en-revision")
                .latitud(-34.6).longitud(-58.4).requiereSena(false)
                .estadoVerificacion(EstadoVerificacion.EN_REVISION).dueno(dueno)));
        entityManager.flush();

        assertEquals(1, establecimientoRepository
                .findByEstadoVerificacionAndDeletedAtIsNull(EstadoVerificacion.EN_REVISION, Pageable.unpaged())
                .getTotalElements());
        assertEquals(1, establecimientoRepository.findAllByDeletedAtIsNull(Pageable.unpaged()).getTotalElements());
    }

    @Test
    @DisplayName("existsBySlug_VeElSlugRenombradoDeUnEliminadoComoOcupado_YElNombreOriginalQuedaLibre")
    void existsBySlug_VeElSlugRenombradoDeUnEliminadoComoOcupado_YElNombreOriginalQuedaLibre() {
        Usuario dueno = duenoEliminacion("dueno-elim-slug-liberado@test.com");
        Establecimiento eliminado = entityManager.persist(Establecimientos.establecimientoDeshabilitado(b -> b
                .nombre("Mi Complejo").direccion("Calle").slug("mi-complejo").latitud(-34.6).longitud(-58.4)
                .requiereSena(false).dueno(dueno)));

        // Mismo patrón que EstablecimientoEliminacionService: renombra y marca deletedAt.
        eliminado.setSlug(eliminado.getSlug() + "-eliminado-" + eliminado.getId());
        eliminado.setDeletedAt(java.time.LocalDateTime.now());
        entityManager.persistAndFlush(eliminado);

        // El slug renombrado sigue "ocupado" (la fila existe, sin cascada) -- SlugGenerator
        // no debería poder reasignarlo a otro alta.
        assertTrue(establecimientoRepository.existsBySlug("mi-complejo-eliminado-" + eliminado.getId()));
        // El nombre ORIGINAL queda libre: el dueño puede recrear un complejo con el mismo
        // nombre sin romper el UNIQUE de slug.
        assertFalse(establecimientoRepository.existsBySlug("mi-complejo"));
    }
}
