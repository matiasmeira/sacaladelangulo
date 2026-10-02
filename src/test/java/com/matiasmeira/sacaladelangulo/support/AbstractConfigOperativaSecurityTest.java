package com.matiasmeira.sacaladelangulo.support;

import com.matiasmeira.sacaladelangulo.auth.model.Usuario;
import com.matiasmeira.sacaladelangulo.establecimiento.model.BloqueoCancha;
import com.matiasmeira.sacaladelangulo.establecimiento.model.BloqueoJugador;
import com.matiasmeira.sacaladelangulo.establecimiento.model.Cancha;
import com.matiasmeira.sacaladelangulo.establecimiento.model.DiaNoLaborable;
import com.matiasmeira.sacaladelangulo.establecimiento.repository.BloqueoCanchaRepository;
import com.matiasmeira.sacaladelangulo.establecimiento.repository.BloqueoJugadorRepository;
import com.matiasmeira.sacaladelangulo.establecimiento.repository.DiaNoLaborableRepository;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.LocalDate;

/**
 * Capa sobre {@link AbstractSecurityWebTest} para la configuración operativa del complejo (bloqueos de
 * cancha, días no laborables y bloqueos de jugador). Siembra, además del escenario base, UN recurso de cada
 * tipo en el complejo A y UNO en el B (con una cancha propia, canchaB), para poder aseverar tanto que el
 * recurso propio sobrevive a un rechazo como que un dueño no ve ni toca los del otro complejo.
 */
public abstract class AbstractConfigOperativaSecurityTest extends AbstractSecurityWebTest {

    protected static final String MENSAJE_403_SERVICE = "No autorizado en este establecimiento";

    @Autowired
    protected BloqueoCanchaRepository bloqueoCanchaRepository;
    @Autowired
    protected DiaNoLaborableRepository diaNoLaborableRepository;
    @Autowired
    protected BloqueoJugadorRepository bloqueoJugadorRepository;

    protected Cancha canchaB;
    protected BloqueoCancha bloqueoA;
    protected BloqueoCancha bloqueoB;
    protected DiaNoLaborable diaA;
    protected DiaNoLaborable diaB;
    /** Jugadores bloqueados: uno en el complejo A y otro en el B. */
    protected Usuario jugadorBloqueadoA;
    protected Usuario jugadorBloqueadoB;

    @BeforeEach
    void sembrarConfiguracionOperativa() {
        canchaB = canchaRepository.save(Canchas.canchaActiva(establecimientoB));

        LocalDate fecha = LocalDate.now().plusDays(10);
        bloqueoA = bloqueoCanchaRepository.save(bloqueo(canchaA, fecha));
        bloqueoB = bloqueoCanchaRepository.save(bloqueo(canchaB, fecha));

        diaA = diaNoLaborableRepository.save(DiaNoLaborable.builder()
                .establecimiento(establecimientoA).fecha(fecha).motivo("Feriado A").build());
        diaB = diaNoLaborableRepository.save(DiaNoLaborable.builder()
                .establecimiento(establecimientoB).fecha(fecha).motivo("Feriado B").build());

        jugadorBloqueadoA = jugadorExtra();
        jugadorBloqueadoB = jugadorExtra();
        bloqueoJugadorRepository.save(BloqueoJugador.builder()
                .establecimiento(establecimientoA).jugador(jugadorBloqueadoA).motivo("Bloqueo A").build());
        bloqueoJugadorRepository.save(BloqueoJugador.builder()
                .establecimiento(establecimientoB).jugador(jugadorBloqueadoB).motivo("Bloqueo B").build());
    }

    private static BloqueoCancha bloqueo(Cancha cancha, LocalDate fecha) {
        return BloqueoCancha.builder()
                .cancha(cancha)
                .fechaInicio(fecha.atTime(10, 0))
                .fechaFin(fecha.atTime(12, 0))
                .motivo("Mantenimiento")
                .build();
    }
}
