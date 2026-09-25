package com.matiasmeira.sacaladelangulo.establecimiento.repository;

import com.matiasmeira.sacaladelangulo.auth.model.PlanSuscripcion;
import com.matiasmeira.sacaladelangulo.auth.model.Role;
import com.matiasmeira.sacaladelangulo.auth.model.Usuario;
import com.matiasmeira.sacaladelangulo.auth.repository.UsuarioRepository;
import com.matiasmeira.sacaladelangulo.establecimiento.model.Cancha;
import com.matiasmeira.sacaladelangulo.establecimiento.model.Deporte;
import com.matiasmeira.sacaladelangulo.establecimiento.model.Establecimiento;
import com.matiasmeira.sacaladelangulo.support.AbstractPostgresIntegrationTest;
import com.matiasmeira.sacaladelangulo.support.Establecimientos;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * El sitio de más incertidumbre de los tres (ver diagnóstico del 2026-09-24): acá el
 * parámetro no sólo se usa en "IS NULL", también participa de un MEMBER OF contra una
 * colección (Cancha.deportes), no de una comparación simple. Este test confirma que
 * cast(:deporte as string) IS NULL, aplicado SOLO en la condición de null (nunca en el
 * MEMBER OF), sigue siendo JPQL válido y no cambia el resultado -- alimenta el listado
 * público de la home sin filtros (ComplejoPublicoService), sin login, así que es el
 * camino de entrada del producto.
 *
 * <p>Para ver el rojo: revertir el cast(...) de las dos queries en
 * EstablecimientoRepository (findCercanosYPorDeporte y findActivosPorDeporte), dejando
 * ":deporte IS NULL", y volver a correr esta clase con Docker disponible.
 */
@Tag("testcontainers")
@DisplayName("EstablecimientoRepository - deporte=null (MEMBER OF) contra Postgres real")
class EstablecimientoRepositoryNullDeporteIntegrationTest extends AbstractPostgresIntegrationTest {

    @Autowired
    private EstablecimientoRepository establecimientoRepository;
    @Autowired
    private CanchaRepository canchaRepository;
    @Autowired
    private UsuarioRepository usuarioRepository;

    private Establecimiento establecimiento;

    @BeforeEach
    void setUp() {
        Usuario dueno = usuarioRepository.save(Usuario.builder()
                .email("dueno-publico-" + System.nanoTime() + "@test.com")
                .password("hash")
                .nombre("Dueño Público")
                .rol(Role.OWNER)
                .planSuscripcion(PlanSuscripcion.TRIAL)
                .isActive(true)
                .emailVerified(true)
                .telefonoVerificado(false)
                .unsubscribeToken("tok-" + System.nanoTime())
                .build());

        establecimiento = establecimientoRepository.save(Establecimientos.establecimientoOperativo(b -> b
                .nombre("Complejo Público")
                .direccion("Calle Pública 123")
                .slug("complejo-publico-" + System.nanoTime())
                .latitud(-34.6037)
                .longitud(-58.3816)
                .dueno(dueno)));

        canchaRepository.save(Cancha.builder()
                .nombre("Cancha 1")
                .establecimiento(establecimiento)
                .isActive(true)
                .precioBase(BigDecimal.valueOf(10000))
                .montoSena(BigDecimal.valueOf(2000))
                .deportes(Set.of(Deporte.PADEL))
                .build());
    }

    @Test
    @DisplayName("findActivosPorDeporte con deporte=null (home sin filtro) no rompe y trae el complejo")
    void findActivosPorDeporte_DeporteNull_NoRompeYTraeElComplejo() {
        List<Establecimiento> resultado = assertDoesNotThrow(() ->
                establecimientoRepository.findActivosPorDeporte(null, PageRequest.of(0, 20)));

        assertEquals(1, resultado.size());
        assertEquals(establecimiento.getId(), resultado.get(0).getId());
    }

    @Test
    @DisplayName("findCercanosYPorDeporte con deporte=null no rompe y trae el complejo")
    void findCercanosYPorDeporte_DeporteNull_NoRompeYTraeElComplejo() {
        List<Establecimiento> resultado = assertDoesNotThrow(() ->
                establecimientoRepository.findCercanosYPorDeporte(-34.6037, -58.3816, 10.0, null));

        assertEquals(1, resultado.size());
        assertEquals(establecimiento.getId(), resultado.get(0).getId());
    }
}
