package com.matiasmeira.sacaladelangulo.publico.service;

import com.matiasmeira.sacaladelangulo.disponibilidad.service.DisponibilidadService;
import com.matiasmeira.sacaladelangulo.establecimiento.model.Cancha;
import com.matiasmeira.sacaladelangulo.establecimiento.model.Deporte;
import com.matiasmeira.sacaladelangulo.establecimiento.model.Establecimiento;
import com.matiasmeira.sacaladelangulo.establecimiento.model.Tarifa;
import com.matiasmeira.sacaladelangulo.establecimiento.repository.CanchaRepository;
import com.matiasmeira.sacaladelangulo.establecimiento.repository.EstablecimientoRepository;
import com.matiasmeira.sacaladelangulo.feedback.repository.FeedbackRepository;
import com.matiasmeira.sacaladelangulo.publico.dto.ComplejoCardResponse;
import com.matiasmeira.sacaladelangulo.reserva.repository.ReservaRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalTime;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("ComplejoPublicoService - Listado público de complejos")
class ComplejoPublicoServiceTest {

    @Mock
    private EstablecimientoRepository establecimientoRepository;

    @Mock
    private CanchaRepository canchaRepository;

    @Mock
    private ReservaRepository reservaRepository;

    @Mock
    private FeedbackRepository feedbackRepository;

    @Mock
    private DisponibilidadService disponibilidadService;

    @InjectMocks
    private ComplejoPublicoService complejoPublicoService;

    private Establecimiento establecimiento(Long id, String slug, String nombre, boolean requiereSena) {
        return Establecimiento.builder()
                .id(id)
                .nombre(nombre)
                .direccion("Direccion " + id)
                .slug(slug)
                .latitud(-34.6)
                .longitud(-58.4)
                .requiereSena(requiereSena)
                .isActive(true)
                .build();
    }

    private Cancha canchaConTarifa(Long id, Establecimiento est, Set<Deporte> deportes, BigDecimal montoSena, BigDecimal precioTarifa) {
        Cancha cancha = Cancha.builder()
                .id(id)
                .nombre("Cancha " + id)
                .deportes(deportes)
                .capacidad(10)
                .isActive(true)
                .precioBase(BigDecimal.valueOf(1000))
                .montoSena(montoSena)
                .establecimiento(est)
                .build();
        cancha.setTarifas(List.of(Tarifa.builder()
                .cancha(cancha)
                .diaSemana(DayOfWeek.MONDAY)
                .horaInicio(LocalTime.of(9, 0))
                .horaFin(LocalTime.of(23, 0))
                .precio(precioTarifa)
                .build()));
        return cancha;
    }

    @Test
    @DisplayName("buscarComplejos_VariasCanchas_DerivaDeportesPrecioDesdeYSenaDesdeSinFiltroDeDeporte")
    void buscarComplejos_VariasCanchas_DerivaDeportesPrecioDesdeYSenaDesdeSinFiltroDeDeporte() {
        Establecimiento est = establecimiento(1L, "complejo-uno", "Complejo Uno", true);
        Cancha futbol = canchaConTarifa(10L, est, Set.of(Deporte.FUTBOL), BigDecimal.valueOf(1000), BigDecimal.valueOf(5000));
        Cancha padel = canchaConTarifa(11L, est, Set.of(Deporte.PADEL), BigDecimal.valueOf(800), BigDecimal.valueOf(3000));

        when(establecimientoRepository.findActivosPorDeporte(null)).thenReturn(List.of(est));
        when(canchaRepository.findActivasConDeportesYTarifasByEstablecimientoIdIn(List.of(1L)))
                .thenReturn(List.of(futbol, padel));
        when(establecimientoRepository.precargarFotos(List.of(1L))).thenReturn(List.of(est));
        when(feedbackRepository.calcularPromediosPorEstablecimientos(List.of(1L))).thenReturn(List.of());
        when(feedbackRepository.contarPorEstablecimientos(List.of(1L))).thenReturn(List.of());

        Page<ComplejoCardResponse> resultado = complejoPublicoService.buscarComplejos(
                null, null, null, null, null, null, PageRequest.of(0, 20));

        assertEquals(1, resultado.getTotalElements());
        ComplejoCardResponse card = resultado.getContent().get(0);
        assertEquals(Set.of(Deporte.FUTBOL, Deporte.PADEL), card.deportes());
        assertEquals(BigDecimal.valueOf(3000), card.precioDesde());
        assertEquals(BigDecimal.valueOf(800), card.senaDesde());
        assertNull(card.distanciaKm());
    }

    @Test
    @DisplayName("buscarComplejos_ConFiltroDeDeporte_AcotaPrecioDesdeYSenaDesdeALasCanchasDeEseDeporte")
    void buscarComplejos_ConFiltroDeDeporte_AcotaPrecioDesdeYSenaDesdeALasCanchasDeEseDeporte() {
        Establecimiento est = establecimiento(1L, "complejo-uno", "Complejo Uno", true);
        Cancha futbol = canchaConTarifa(10L, est, Set.of(Deporte.FUTBOL), BigDecimal.valueOf(1000), BigDecimal.valueOf(5000));
        Cancha padel = canchaConTarifa(11L, est, Set.of(Deporte.PADEL), BigDecimal.valueOf(800), BigDecimal.valueOf(3000));

        when(establecimientoRepository.findActivosPorDeporte(Deporte.PADEL)).thenReturn(List.of(est));
        when(canchaRepository.findActivasConDeportesYTarifasByEstablecimientoIdIn(List.of(1L)))
                .thenReturn(List.of(futbol, padel));
        when(establecimientoRepository.precargarFotos(List.of(1L))).thenReturn(List.of(est));
        when(feedbackRepository.calcularPromediosPorEstablecimientos(List.of(1L))).thenReturn(List.of());
        when(feedbackRepository.contarPorEstablecimientos(List.of(1L))).thenReturn(List.of());

        Page<ComplejoCardResponse> resultado = complejoPublicoService.buscarComplejos(
                null, null, null, Deporte.PADEL, null, null, PageRequest.of(0, 20));

        ComplejoCardResponse card = resultado.getContent().get(0);
        assertEquals(Set.of(Deporte.FUTBOL, Deporte.PADEL), card.deportes());
        assertEquals(BigDecimal.valueOf(3000), card.precioDesde());
        assertEquals(BigDecimal.valueOf(800), card.senaDesde());
    }

    @Test
    @DisplayName("buscarComplejos_ConUbicacion_OrdenaPorDistanciaAscendente")
    void buscarComplejos_ConUbicacion_OrdenaPorDistanciaAscendente() {
        Establecimiento lejano = Establecimiento.builder()
                .id(1L).nombre("Lejano").direccion("D1").slug("lejano")
                .latitud(-54.8019).longitud(-68.3030).requiereSena(false).isActive(true).build();
        Establecimiento cercano = Establecimiento.builder()
                .id(2L).nombre("Cercano").direccion("D2").slug("cercano")
                .latitud(-34.6037).longitud(-58.3816).requiereSena(false).isActive(true).build();

        when(establecimientoRepository.findCercanosYPorDeporte(-34.6037, -58.3816, 10.0, null))
                .thenReturn(List.of(lejano, cercano));
        when(canchaRepository.findActivasConDeportesYTarifasByEstablecimientoIdIn(List.of(1L, 2L)))
                .thenReturn(List.of());
        when(establecimientoRepository.precargarFotos(List.of(1L, 2L))).thenReturn(List.of(lejano, cercano));
        when(feedbackRepository.calcularPromediosPorEstablecimientos(List.of(1L, 2L))).thenReturn(List.of());
        when(feedbackRepository.contarPorEstablecimientos(List.of(1L, 2L))).thenReturn(List.of());

        Page<ComplejoCardResponse> resultado = complejoPublicoService.buscarComplejos(
                -34.6037, -58.3816, null, null, null, null, PageRequest.of(0, 20));

        assertEquals("cercano", resultado.getContent().get(0).slug());
        assertEquals("lejano", resultado.getContent().get(1).slug());
    }

    @Test
    @DisplayName("buscarComplejos_Paginacion_RespetaPageYSize")
    void buscarComplejos_Paginacion_RespetaPageYSize() {
        Establecimiento e1 = establecimiento(1L, "uno", "Uno", false);
        Establecimiento e2 = establecimiento(2L, "dos", "Dos", false);
        Establecimiento e3 = establecimiento(3L, "tres", "Tres", false);

        when(establecimientoRepository.findActivosPorDeporte(null)).thenReturn(List.of(e1, e2, e3));
        when(canchaRepository.findActivasConDeportesYTarifasByEstablecimientoIdIn(List.of(1L, 2L, 3L)))
                .thenReturn(List.of());
        when(establecimientoRepository.precargarFotos(List.of(1L, 2L, 3L))).thenReturn(List.of(e1, e2, e3));
        when(feedbackRepository.calcularPromediosPorEstablecimientos(List.of(1L, 2L, 3L))).thenReturn(List.of());
        when(feedbackRepository.contarPorEstablecimientos(List.of(1L, 2L, 3L))).thenReturn(List.of());

        Page<ComplejoCardResponse> primeraPagina = complejoPublicoService.buscarComplejos(
                null, null, null, null, null, null, PageRequest.of(0, 2));

        assertEquals(2, primeraPagina.getContent().size());
        assertEquals(3, primeraPagina.getTotalElements());
        assertEquals(2, primeraPagina.getTotalPages());
    }
}
