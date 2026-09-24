package com.matiasmeira.sacaladelangulo.establecimiento.repository;

import com.matiasmeira.sacaladelangulo.auth.model.PlanSuscripcion;
import com.matiasmeira.sacaladelangulo.auth.model.Role;
import com.matiasmeira.sacaladelangulo.auth.model.Usuario;
import com.matiasmeira.sacaladelangulo.core.exception.EntityNotFoundException;
import com.matiasmeira.sacaladelangulo.establecimiento.model.EstadoVerificacion;
import com.matiasmeira.sacaladelangulo.establecimiento.model.Establecimiento;
import com.matiasmeira.sacaladelangulo.establecimiento.service.EstablecimientoOperativoGuard;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.data.domain.Pageable;
import org.springframework.test.context.TestPropertySource;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * EL CRITERIO DE "ESTABLECIMIENTO OPERATIVO" ESTÁ DUPLICADO A PROPÓSITO EN DOS CAPAS QUE NADA
 * ATA ENTRE SÍ: EstablecimientoOperativoGuard (Java, valida un establecimiento puntual) y las
 * tres queries públicas de EstablecimientoRepository (SQL/JPQL, deciden qué aparece en el
 * buscador y en el detalle por slug -- findActivosPorDeporte, findCercanosYPorDeporte,
 * findBySlugOperativo). Con una sola condición (isActive) la duplicación era tolerable; con
 * tres (isActive + estadoVerificacion + deletedAt) ya no lo es: nada impide que alguien sume
 * una condición nueva al guard sin acordarse de las queries (o al revés), y el síntoma sería
 * el peor posible -- un establecimiento que aparece en el buscador pero devuelve 404 al
 * entrar, o viceversa. Este test no prueba ninguna de las dos capas en particular: prueba que
 * están de acuerdo, para las 4 (EstadoVerificacion) x 2 (isActive) x 2 (deletedAt) = 16
 * combinaciones. Si el día de mañana se agrega un quinto estado de verificación,
 * @MethodSource lo cubre solo porque itera EstadoVerificacion.values() en vez de casos
 * escritos a mano.
 */
@DataJpaTest
@TestPropertySource(properties = {
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.flyway.enabled=false"
})
@DisplayName("EstablecimientoRepository <-> EstablecimientoOperativoGuard - Coherencia del criterio de operatividad")
class EstablecimientoOperativoCoherenciaTest {

    @Autowired
    private TestEntityManager entityManager;

    @Autowired
    private EstablecimientoRepository establecimientoRepository;

    private final EstablecimientoOperativoGuard guard = new EstablecimientoOperativoGuard();

    private static final AtomicInteger SECUENCIA = new AtomicInteger();

    /**
     * Cartesiano completo EstadoVerificacion x {true, false} para isActive x {true, false}
     * para "está eliminado", generado a partir del enum (no una lista de casos a mano): un
     * valor nuevo de EstadoVerificacion queda cubierto sin tocar este método.
     */
    static Stream<Arguments> combinaciones() {
        return Stream.of(EstadoVerificacion.values())
                .flatMap(estado -> Stream.of(true, false)
                        .flatMap(activo -> Stream.of(
                                Arguments.of(estado, activo, true),
                                Arguments.of(estado, activo, false))));
    }

    private boolean elGuardLoDejaPasar(Establecimiento establecimiento) {
        try {
            guard.validarEstablecimientoOperativoParaJugador(establecimiento);
            return true;
        } catch (EntityNotFoundException ex) {
            return false;
        }
    }

    @ParameterizedTest(name = "isActive={1}, estadoVerificacion={0}, eliminado={2}: buscador, detalle por slug y geo-búsqueda coinciden con el guard")
    @MethodSource("combinaciones")
    @DisplayName("ElGuardYLasTresQueriesPublicasCoincidenParaTodaCombinacion")
    void elGuardYLasTresQueriesPublicasCoincidenParaTodaCombinacion(EstadoVerificacion estadoVerificacion, boolean isActive, boolean eliminado) {
        int n = SECUENCIA.incrementAndGet();

        Usuario dueno = entityManager.persist(Usuario.builder()
                .email("dueno-coherencia-" + n + "@test.com")
                .password("hash")
                .nombre("Dueno Coherencia " + n)
                .rol(Role.OWNER)
                .planSuscripcion(PlanSuscripcion.TRIAL)
                .isActive(true)
                .emailVerified(true)
                .telefonoVerificado(false)
                .build());

        double lat = -34.6037;
        double lng = -58.3816;
        String slug = "complejo-coherencia-" + n;

        Establecimiento establecimiento = entityManager.persist(Establecimiento.builder()
                .nombre("Complejo Coherencia " + n)
                .direccion("Calle Coherencia " + n)
                .slug(slug)
                .latitud(lat)
                .longitud(lng)
                .requiereSena(false)
                .isActive(isActive)
                .estadoVerificacion(estadoVerificacion)
                .deletedAt(eliminado ? LocalDateTime.now() : null)
                .dueno(dueno)
                .build());
        entityManager.flush();
        entityManager.clear();

        boolean esperado = elGuardLoDejaPasar(establecimiento);

        boolean apareceEnBuscadorSinUbicacion = establecimientoRepository
                .findActivosPorDeporte(null, Pageable.unpaged()).stream()
                .anyMatch(e -> e.getId().equals(establecimiento.getId()));

        boolean apareceEnBuscadorCercano = establecimientoRepository
                .findCercanosYPorDeporte(lat, lng, 10.0, null).stream()
                .anyMatch(e -> e.getId().equals(establecimiento.getId()));

        boolean apareceEnDetallePorSlug = establecimientoRepository.findBySlugOperativo(slug).isPresent();

        String contexto = "isActive=" + isActive + ", estadoVerificacion=" + estadoVerificacion + ", eliminado=" + eliminado;
        assertEquals(esperado, apareceEnBuscadorSinUbicacion, "findActivosPorDeporte divergió del guard para " + contexto);
        assertEquals(esperado, apareceEnBuscadorCercano, "findCercanosYPorDeporte divergió del guard para " + contexto);
        assertEquals(esperado, apareceEnDetallePorSlug, "findBySlugOperativo divergió del guard para " + contexto);
    }

    /**
     * Mismo espíritu que el test de arriba pero para el filtro por deporte: si alguien mueve
     * el criterio de operatividad a un lugar de la query donde el AND de deporte lo pisa (o
     * viceversa), esto lo detecta. No hace falta el cartesiano completo acá -- alcanza con un
     * caso operativo y uno no operativo, ambos con una cancha real del deporte buscado.
     */
    @Test
    @DisplayName("ElCriterioDeOperatividadNoSeRompeAlCombinarseConElFiltroPorDeporte")
    void elCriterioDeOperatividadNoSeRompeAlCombinarseConElFiltroPorDeporte() {
        int n = SECUENCIA.incrementAndGet();
        Usuario dueno = entityManager.persist(Usuario.builder()
                .email("dueno-coherencia-deporte-" + n + "@test.com")
                .password("hash")
                .nombre("Dueno Coherencia Deporte")
                .rol(Role.OWNER)
                .planSuscripcion(PlanSuscripcion.TRIAL)
                .isActive(true)
                .emailVerified(true)
                .telefonoVerificado(false)
                .build());

        Establecimiento operativo = entityManager.persist(Establecimiento.builder()
                .nombre("Operativo Con Padel " + n)
                .direccion("Calle " + n)
                .slug("operativo-con-padel-" + n)
                .latitud(-34.6)
                .longitud(-58.4)
                .requiereSena(false)
                .isActive(true)
                .estadoVerificacion(EstadoVerificacion.VERIFICADO)
                .dueno(dueno)
                .build());
        entityManager.persist(com.matiasmeira.sacaladelangulo.establecimiento.model.Cancha.builder()
                .nombre("Cancha 1")
                .deportes(java.util.Set.of(com.matiasmeira.sacaladelangulo.establecimiento.model.Deporte.PADEL))
                .isActive(true)
                .precioBase(java.math.BigDecimal.valueOf(1000))
                .montoSena(java.math.BigDecimal.valueOf(200))
                .establecimiento(operativo)
                .build());

        Establecimiento noOperativo = entityManager.persist(Establecimiento.builder()
                .nombre("Pendiente Con Padel " + n)
                .direccion("Calle B " + n)
                .slug("pendiente-con-padel-" + n)
                .latitud(-34.6)
                .longitud(-58.4)
                .requiereSena(false)
                .isActive(true)
                .estadoVerificacion(EstadoVerificacion.PENDIENTE)
                .dueno(dueno)
                .build());
        entityManager.persist(com.matiasmeira.sacaladelangulo.establecimiento.model.Cancha.builder()
                .nombre("Cancha 1")
                .deportes(java.util.Set.of(com.matiasmeira.sacaladelangulo.establecimiento.model.Deporte.PADEL))
                .isActive(true)
                .precioBase(java.math.BigDecimal.valueOf(1000))
                .montoSena(java.math.BigDecimal.valueOf(200))
                .establecimiento(noOperativo)
                .build());
        entityManager.flush();
        entityManager.clear();

        List<Establecimiento> resultado = establecimientoRepository.findActivosPorDeporte(
                com.matiasmeira.sacaladelangulo.establecimiento.model.Deporte.PADEL, Pageable.unpaged());

        assertEquals(1, resultado.size());
        assertEquals(operativo.getId(), resultado.get(0).getId());
    }
}
