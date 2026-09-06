package com.matiasmeira.sacaladelangulo.reserva.dto;

import com.matiasmeira.sacaladelangulo.establecimiento.model.Cancha;
import com.matiasmeira.sacaladelangulo.establecimiento.model.Deporte;
import com.matiasmeira.sacaladelangulo.reserva.model.EstadoReserva;
import com.matiasmeira.sacaladelangulo.reserva.model.Reserva;
import com.matiasmeira.sacaladelangulo.reserva.model.TurnoFijo;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("ReservaMapper")
class ReservaMapperTest {

    private final ReservaMapper reservaMapper = new ReservaMapper();

    @Test
    @DisplayName("mapToResponse_ReservaDeTurnoFijo_ExponeElIdDeLaSerie")
    void mapToResponse_ReservaDeTurnoFijo_ExponeElIdDeLaSerie() {
        TurnoFijo regla = TurnoFijo.builder().id(7L).build();
        Reserva reserva = reservaBase();
        reserva.setTurnoFijo(regla);

        assertThat(reservaMapper.mapToResponse(reserva).turnoFijoId()).isEqualTo(7L);
    }

    @Test
    @DisplayName("mapToResponse_ReservaPuntual_DejaElIdDeSerieEnNulo")
    void mapToResponse_ReservaPuntual_DejaElIdDeSerieEnNulo() {
        assertThat(reservaMapper.mapToResponse(reservaBase()).turnoFijoId()).isNull();
    }

    /** Reserva puntual "genérica" (sin turno fijo), para los tests que no necesitan más. */
    private Reserva reservaBase() {
        Cancha cancha = Cancha.builder().id(1L).nombre("Cancha 1").build();
        return Reserva.builder()
                .id(1L)
                .cancha(cancha)
                .fechaHoraInicio(LocalDateTime.of(2030, 1, 7, 18, 0))
                .fechaHoraFin(LocalDateTime.of(2030, 1, 7, 19, 0))
                .estado(EstadoReserva.CONFIRMADA)
                .precioTotal(BigDecimal.valueOf(1000))
                .senaPagada(BigDecimal.ZERO)
                .deporteSeleccionado(Deporte.PADEL)
                .build();
    }
}
