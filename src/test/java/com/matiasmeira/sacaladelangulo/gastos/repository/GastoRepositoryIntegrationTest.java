package com.matiasmeira.sacaladelangulo.gastos.repository;

import com.matiasmeira.sacaladelangulo.auth.model.PlanSuscripcion;
import com.matiasmeira.sacaladelangulo.auth.model.Role;
import com.matiasmeira.sacaladelangulo.auth.model.Usuario;
import com.matiasmeira.sacaladelangulo.auth.repository.UsuarioRepository;
import com.matiasmeira.sacaladelangulo.core.pago.MetodoPago;
import com.matiasmeira.sacaladelangulo.establecimiento.model.Establecimiento;
import com.matiasmeira.sacaladelangulo.establecimiento.repository.EstablecimientoRepository;
import com.matiasmeira.sacaladelangulo.gastos.model.CategoriaGasto;
import com.matiasmeira.sacaladelangulo.gastos.model.Gasto;
import com.matiasmeira.sacaladelangulo.support.AbstractPostgresIntegrationTest;
import com.matiasmeira.sacaladelangulo.support.Establecimientos;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Reproduce contra Postgres real (Testcontainers) el 500 diagnosticado el 2026-09-24:
 * GastoRepository.buscar con los tres filtros opcionales en null tira SQLState 42P18
 * ("no se pudo determinar el tipo del parámetro") porque pgjdbc no puede inferir el tipo
 * de un parámetro cuya única aparición es un "? IS NULL" sin contexto. Un test con
 * gastoRepository mockeado (como los que ya existen en GastoServiceTest) no detecta esto:
 * nunca llega a ejecutarse el JPQL real.
 *
 * <p>Para ver el rojo: revertir el cast(...) de las tres condiciones IS NULL en
 * GastoRepository.buscar (dejarlas como ":desde IS NULL", ":hasta IS NULL",
 * ":categoria IS NULL") y volver a correr esta clase con Docker disponible.
 */
@Tag("testcontainers")
@DisplayName("GastoRepository.buscar - filtros opcionales en null contra Postgres real")
class GastoRepositoryIntegrationTest extends AbstractPostgresIntegrationTest {

    @Autowired
    private GastoRepository gastoRepository;
    @Autowired
    private EstablecimientoRepository establecimientoRepository;
    @Autowired
    private UsuarioRepository usuarioRepository;

    private Establecimiento establecimiento;
    private Usuario dueno;

    @BeforeEach
    void setUp() {
        dueno = usuarioRepository.save(Usuario.builder()
                .email("dueno-gastos-" + System.nanoTime() + "@test.com")
                .password("hash")
                .nombre("Dueño Gastos")
                .rol(Role.OWNER)
                .planSuscripcion(PlanSuscripcion.TRIAL)
                .isActive(true)
                .emailVerified(true)
                .telefonoVerificado(false)
                .unsubscribeToken("tok-" + System.nanoTime())
                .build());

        establecimiento = establecimientoRepository.save(Establecimientos.establecimientoOperativo(b -> b
                .nombre("Complejo Gastos")
                .direccion("Calle Gastos 123")
                .slug("complejo-gastos-" + System.nanoTime())
                .dueno(dueno)));
    }

    @Test
    @DisplayName("Establecimiento recién creado, sin ningún gasto: buscar con desde/hasta/categoria=null no rompe")
    void buscar_EstablecimientoSinGastos_FiltrosEnNull_NoRompe() {
        Pageable pageable = PageRequest.of(0, 20);

        Page<Gasto> resultado = assertDoesNotThrow(
                () -> gastoRepository.buscar(establecimiento.getId(), null, null, null, pageable));

        assertEquals(0, resultado.getTotalElements());
    }

    @Test
    @DisplayName("Establecimiento CON gastos cargados: buscar con filtros=null también rompía, no es exclusivo de 'sin datos'")
    void buscar_EstablecimientoConGastos_FiltrosEnNull_NoRompeYTraeElGasto() {
        gastoRepository.save(Gasto.builder()
                .establecimiento(establecimiento)
                .fecha(LocalDate.now())
                .monto(BigDecimal.valueOf(1000))
                .categoria(CategoriaGasto.SERVICIOS)
                .descripcion("Luz")
                .metodoPago(MetodoPago.EFECTIVO)
                .usuarioRegistro(dueno)
                .build());

        Pageable pageable = PageRequest.of(0, 20);

        Page<Gasto> resultado = assertDoesNotThrow(
                () -> gastoRepository.buscar(establecimiento.getId(), null, null, null, pageable));

        assertEquals(1, resultado.getTotalElements());
    }
}
