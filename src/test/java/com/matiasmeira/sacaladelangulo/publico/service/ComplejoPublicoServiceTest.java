package com.matiasmeira.sacaladelangulo.publico.service;

import com.matiasmeira.sacaladelangulo.auth.model.Usuario;
import com.matiasmeira.sacaladelangulo.disponibilidad.dto.DisponibilidadEstablecimientoResponse;
import com.matiasmeira.sacaladelangulo.disponibilidad.service.DisponibilidadService;
import com.matiasmeira.sacaladelangulo.establecimiento.model.BloqueoCancha;
import com.matiasmeira.sacaladelangulo.establecimiento.model.Cancha;
import com.matiasmeira.sacaladelangulo.establecimiento.model.Deporte;
import com.matiasmeira.sacaladelangulo.establecimiento.model.DiaNoLaborable;
import com.matiasmeira.sacaladelangulo.establecimiento.model.Establecimiento;
import com.matiasmeira.sacaladelangulo.establecimiento.model.HorarioAtencion;
import com.matiasmeira.sacaladelangulo.establecimiento.model.Tarifa;
import com.matiasmeira.sacaladelangulo.establecimiento.repository.BloqueoCanchaRepository;
import com.matiasmeira.sacaladelangulo.establecimiento.repository.CanchaRepository;
import com.matiasmeira.sacaladelangulo.establecimiento.repository.DiaNoLaborableRepository;
import com.matiasmeira.sacaladelangulo.establecimiento.repository.EstablecimientoRepository;
import com.matiasmeira.sacaladelangulo.feedback.model.Feedback;
import com.matiasmeira.sacaladelangulo.feedback.repository.FeedbackRepository;
import com.matiasmeira.sacaladelangulo.publico.dto.ComplejoCardResponse;
import com.matiasmeira.sacaladelangulo.reserva.model.Reserva;
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
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
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

    @Mock
    private BloqueoCanchaRepository bloqueoCanchaRepository;

    @Mock
    private DiaNoLaborableRepository diaNoLaborableRepository;

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
                .precioBase(BigDecimal.valueOf(10000))
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
    @DisplayName("buscarComplejos_CanchaSinTarifas_PrecioDesdeCaeAPrecioBase")
    void buscarComplejos_CanchaSinTarifas_PrecioDesdeCaeAPrecioBase() {
        Establecimiento est = establecimiento(1L, "complejo-uno", "Complejo Uno", true);
        Cancha sinTarifas = Cancha.builder()
                .id(10L)
                .nombre("Cancha 10")
                .deportes(Set.of(Deporte.FUTBOL))
                .capacidad(10)
                .isActive(true)
                .precioBase(BigDecimal.valueOf(4000))
                .montoSena(BigDecimal.valueOf(500))
                .establecimiento(est)
                .build();

        when(establecimientoRepository.findActivosPorDeporte(null)).thenReturn(List.of(est));
        when(canchaRepository.findActivasConDeportesYTarifasByEstablecimientoIdIn(List.of(1L)))
                .thenReturn(List.of(sinTarifas));
        when(establecimientoRepository.precargarFotos(List.of(1L))).thenReturn(List.of(est));
        when(feedbackRepository.calcularPromediosPorEstablecimientos(List.of(1L))).thenReturn(List.of());
        when(feedbackRepository.contarPorEstablecimientos(List.of(1L))).thenReturn(List.of());

        Page<ComplejoCardResponse> resultado = complejoPublicoService.buscarComplejos(
                null, null, null, null, null, null, PageRequest.of(0, 20));

        ComplejoCardResponse card = resultado.getContent().get(0);
        assertEquals(BigDecimal.valueOf(4000), card.precioDesde());
        assertEquals(BigDecimal.valueOf(500), card.senaDesde());
    }

    @Test
    @DisplayName("buscarComplejos_TarifaMasCaraQuePrecioBase_PrecioDesdeUsaPrecioBase")
    void buscarComplejos_TarifaMasCaraQuePrecioBase_PrecioDesdeUsaPrecioBase() {
        Establecimiento est = establecimiento(1L, "complejo-uno", "Complejo Uno", true);
        Cancha cancha = Cancha.builder()
                .id(10L)
                .nombre("Cancha 10")
                .deportes(Set.of(Deporte.FUTBOL))
                .capacidad(10)
                .isActive(true)
                .precioBase(BigDecimal.valueOf(2000))
                .montoSena(BigDecimal.valueOf(500))
                .establecimiento(est)
                .build();
        cancha.setTarifas(List.of(Tarifa.builder()
                .cancha(cancha)
                .diaSemana(DayOfWeek.MONDAY)
                .horaInicio(LocalTime.of(9, 0))
                .horaFin(LocalTime.of(23, 0))
                .precio(BigDecimal.valueOf(9000))
                .build()));

        when(establecimientoRepository.findActivosPorDeporte(null)).thenReturn(List.of(est));
        when(canchaRepository.findActivasConDeportesYTarifasByEstablecimientoIdIn(List.of(1L)))
                .thenReturn(List.of(cancha));
        when(establecimientoRepository.precargarFotos(List.of(1L))).thenReturn(List.of(est));
        when(feedbackRepository.calcularPromediosPorEstablecimientos(List.of(1L))).thenReturn(List.of());
        when(feedbackRepository.contarPorEstablecimientos(List.of(1L))).thenReturn(List.of());

        Page<ComplejoCardResponse> resultado = complejoPublicoService.buscarComplejos(
                null, null, null, null, null, null, PageRequest.of(0, 20));

        // La tarifa (9000) es más cara que precioBase (2000): precioDesde tiene que reflejar
        // el mínimo real, no solo el precio de tarifa.
        assertEquals(BigDecimal.valueOf(2000), resultado.getContent().get(0).precioDesde());
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

    @Test
    @DisplayName("buscarComplejos_SinUbicacionConMismoPromedio_DesempataPorSlugAscendente")
    void buscarComplejos_SinUbicacionConMismoPromedio_DesempataPorSlugAscendente() {
        // Dos establecimientos con exactamente el mismo promedioCalificacion: sin un
        // desempate explícito, el orden entre ellos queda a merced del orden "de casualidad"
        // en que vino la lista de la base -- acá se los devuelve deliberadamente en orden
        // "zzz-complejo" antes que "aaa-complejo" para probar que el resultado ordenado no
        // depende de ese orden de entrada, sino del slug.
        Establecimiento zzz = establecimiento(1L, "zzz-complejo", "Zzz", false);
        Establecimiento aaa = establecimiento(2L, "aaa-complejo", "Aaa", false);

        when(establecimientoRepository.findActivosPorDeporte(null)).thenReturn(List.of(zzz, aaa));
        when(canchaRepository.findActivasConDeportesYTarifasByEstablecimientoIdIn(List.of(1L, 2L)))
                .thenReturn(List.of());
        when(establecimientoRepository.precargarFotos(List.of(1L, 2L))).thenReturn(List.of(zzz, aaa));
        when(feedbackRepository.calcularPromediosPorEstablecimientos(List.of(1L, 2L)))
                .thenReturn(List.of(new Object[]{1L, 4.5}, new Object[]{2L, 4.5}));
        when(feedbackRepository.contarPorEstablecimientos(List.of(1L, 2L))).thenReturn(List.of());

        Page<ComplejoCardResponse> resultado = complejoPublicoService.buscarComplejos(
                null, null, null, null, null, null, PageRequest.of(0, 20));

        assertEquals("aaa-complejo", resultado.getContent().get(0).slug());
        assertEquals("zzz-complejo", resultado.getContent().get(1).slug());
    }

    @Test
    @DisplayName("buscarComplejos_ConUbicacionYMismaDistancia_DesempataPorSlugAscendente")
    void buscarComplejos_ConUbicacionYMismaDistancia_DesempataPorSlugAscendente() {
        // Misma idea que el test anterior pero en la rama "con ubicación": dos
        // establecimientos exactamente en el punto de búsqueda (distanciaKm = 0 para
        // ambos), devueltos en orden "zzz" primero para probar que el desempate es por
        // slug y no por el orden de la lista de la base.
        Establecimiento zzz = Establecimiento.builder()
                .id(1L).nombre("Zzz").direccion("D1").slug("zzz-complejo")
                .latitud(-34.6037).longitud(-58.3816).requiereSena(false).isActive(true).build();
        Establecimiento aaa = Establecimiento.builder()
                .id(2L).nombre("Aaa").direccion("D2").slug("aaa-complejo")
                .latitud(-34.6037).longitud(-58.3816).requiereSena(false).isActive(true).build();

        when(establecimientoRepository.findCercanosYPorDeporte(-34.6037, -58.3816, 10.0, null))
                .thenReturn(List.of(zzz, aaa));
        when(canchaRepository.findActivasConDeportesYTarifasByEstablecimientoIdIn(List.of(1L, 2L)))
                .thenReturn(List.of());
        when(establecimientoRepository.precargarFotos(List.of(1L, 2L))).thenReturn(List.of(zzz, aaa));
        when(feedbackRepository.calcularPromediosPorEstablecimientos(List.of(1L, 2L))).thenReturn(List.of());
        when(feedbackRepository.contarPorEstablecimientos(List.of(1L, 2L))).thenReturn(List.of());

        Page<ComplejoCardResponse> resultado = complejoPublicoService.buscarComplejos(
                -34.6037, -58.3816, null, null, null, null, PageRequest.of(0, 20));

        assertEquals("aaa-complejo", resultado.getContent().get(0).slug());
        assertEquals("zzz-complejo", resultado.getContent().get(1).slug());
    }

    @Test
    @DisplayName("buscarComplejos_PageMuyGrande_NoLanzaExcepcionYDevuelveVacio")
    void buscarComplejos_PageMuyGrande_NoLanzaExcepcionYDevuelveVacio() {
        // pageable.getOffset() es long y Spring Data no acota el parámetro "page" (solo
        // "size"): un page=1_000_000_000 con size=20 da un offset (~2*10^10) que, casteado a
        // int SIN acotar antes, desborda a un valor negativo y rompe subList con
        // IndexOutOfBoundsException. Este test prueba que, tras acotar en long antes de
        // castear, un page absurdamente grande devuelve una página vacía en vez de explotar.
        Establecimiento e1 = establecimiento(1L, "uno", "Uno", false);

        when(establecimientoRepository.findActivosPorDeporte(null)).thenReturn(List.of(e1));
        when(canchaRepository.findActivasConDeportesYTarifasByEstablecimientoIdIn(List.of(1L)))
                .thenReturn(List.of());
        when(establecimientoRepository.precargarFotos(List.of(1L))).thenReturn(List.of(e1));
        when(feedbackRepository.calcularPromediosPorEstablecimientos(List.of(1L))).thenReturn(List.of());
        when(feedbackRepository.contarPorEstablecimientos(List.of(1L))).thenReturn(List.of());

        Page<ComplejoCardResponse> resultado = complejoPublicoService.buscarComplejos(
                null, null, null, null, null, null, PageRequest.of(1_000_000_000, 20));

        assertEquals(0, resultado.getContent().size());
        assertEquals(1, resultado.getTotalElements());
    }

    @Test
    @DisplayName("buscarComplejos_ConFechaYHora_IncluyeComplejoAbiertoConCanchaLibre")
    void buscarComplejos_ConFechaYHora_IncluyeComplejoAbiertoConCanchaLibre() {
        // 2026-08-10 es lunes.
        Establecimiento est = establecimiento(1L, "complejo-uno", "Complejo Uno", false);
        est.setHorariosAtencion(List.of(HorarioAtencion.builder()
                .diaSemana(DayOfWeek.MONDAY)
                .horaApertura(LocalTime.of(9, 0))
                .horaCierre(LocalTime.of(23, 0))
                .build()));
        Cancha cancha = Cancha.builder()
                .id(10L).nombre("Cancha 10").deportes(Set.of(Deporte.FUTBOL)).capacidad(10).isActive(true)
                .precioBase(BigDecimal.valueOf(1000)).montoSena(BigDecimal.valueOf(200)).establecimiento(est).build();

        LocalDate fecha = LocalDate.of(2026, 8, 10);
        LocalTime hora = LocalTime.of(10, 0);

        when(establecimientoRepository.findActivosPorDeporte(null)).thenReturn(List.of(est));
        when(establecimientoRepository.precargarHorarios(List.of(1L))).thenReturn(List.of(est));
        when(canchaRepository.findByEstablecimientoIdInAndIsActiveTrue(List.of(1L))).thenReturn(List.of(cancha));
        when(reservaRepository.findCanchaIdsConSolapamiento(eq(List.of(10L)), any(), any())).thenReturn(List.of());
        when(bloqueoCanchaRepository.findByEstablecimientoIdInAndRango(eq(List.of(1L)), any(), any())).thenReturn(List.of());
        when(diaNoLaborableRepository.findByEstablecimientoIdInAndFecha(List.of(1L), fecha)).thenReturn(List.of());
        when(canchaRepository.findActivasConDeportesYTarifasByEstablecimientoIdIn(List.of(1L))).thenReturn(List.of(cancha));
        when(establecimientoRepository.precargarFotos(List.of(1L))).thenReturn(List.of(est));
        when(feedbackRepository.calcularPromediosPorEstablecimientos(List.of(1L))).thenReturn(List.of());
        when(feedbackRepository.contarPorEstablecimientos(List.of(1L))).thenReturn(List.of());

        Page<ComplejoCardResponse> resultado = complejoPublicoService.buscarComplejos(
                null, null, null, null, fecha, hora, PageRequest.of(0, 20));

        assertEquals(1, resultado.getTotalElements());
    }

    @Test
    @DisplayName("buscarComplejos_ConFechaYHora_ExcluyeComplejoSinHorarioAtencionEseDia")
    void buscarComplejos_ConFechaYHora_ExcluyeComplejoSinHorarioAtencionEseDia() {
        Establecimiento est = establecimiento(1L, "complejo-uno", "Complejo Uno", false);
        est.setHorariosAtencion(List.of()); // sin horarios cargados para ningún día

        LocalDate fecha = LocalDate.of(2026, 8, 10);
        LocalTime hora = LocalTime.of(10, 0);

        when(establecimientoRepository.findActivosPorDeporte(null)).thenReturn(List.of(est));
        when(establecimientoRepository.precargarHorarios(List.of(1L))).thenReturn(List.of(est));
        when(canchaRepository.findByEstablecimientoIdInAndIsActiveTrue(List.of(1L))).thenReturn(List.of());
        when(bloqueoCanchaRepository.findByEstablecimientoIdInAndRango(eq(List.of(1L)), any(), any())).thenReturn(List.of());
        when(diaNoLaborableRepository.findByEstablecimientoIdInAndFecha(List.of(1L), fecha)).thenReturn(List.of());

        Page<ComplejoCardResponse> resultado = complejoPublicoService.buscarComplejos(
                null, null, null, null, fecha, hora, PageRequest.of(0, 20));

        assertEquals(0, resultado.getTotalElements());
    }

    @Test
    @DisplayName("buscarComplejos_ConFechaYHora_ExcluyeComplejoConDiaNoLaborable")
    void buscarComplejos_ConFechaYHora_ExcluyeComplejoConDiaNoLaborable() {
        Establecimiento est = establecimiento(1L, "complejo-uno", "Complejo Uno", false);
        est.setHorariosAtencion(List.of(HorarioAtencion.builder()
                .diaSemana(DayOfWeek.MONDAY)
                .horaApertura(LocalTime.of(9, 0))
                .horaCierre(LocalTime.of(23, 0))
                .build()));

        LocalDate fecha = LocalDate.of(2026, 8, 10);
        LocalTime hora = LocalTime.of(10, 0);
        DiaNoLaborable feriado = DiaNoLaborable.builder().fecha(fecha).motivo("Feriado").establecimiento(est).build();

        when(establecimientoRepository.findActivosPorDeporte(null)).thenReturn(List.of(est));
        when(establecimientoRepository.precargarHorarios(List.of(1L))).thenReturn(List.of(est));
        when(canchaRepository.findByEstablecimientoIdInAndIsActiveTrue(List.of(1L))).thenReturn(List.of());
        when(bloqueoCanchaRepository.findByEstablecimientoIdInAndRango(eq(List.of(1L)), any(), any())).thenReturn(List.of());
        when(diaNoLaborableRepository.findByEstablecimientoIdInAndFecha(List.of(1L), fecha)).thenReturn(List.of(feriado));

        Page<ComplejoCardResponse> resultado = complejoPublicoService.buscarComplejos(
                null, null, null, null, fecha, hora, PageRequest.of(0, 20));

        assertEquals(0, resultado.getTotalElements());
    }

    @Test
    @DisplayName("buscarComplejos_ConFechaYHora_ExcluyeCanchaConBloqueoAunSinReservaSuperpuesta")
    void buscarComplejos_ConFechaYHora_ExcluyeCanchaConBloqueoAunSinReservaSuperpuesta() {
        Establecimiento est = establecimiento(1L, "complejo-uno", "Complejo Uno", false);
        est.setHorariosAtencion(List.of(HorarioAtencion.builder()
                .diaSemana(DayOfWeek.MONDAY)
                .horaApertura(LocalTime.of(9, 0))
                .horaCierre(LocalTime.of(23, 0))
                .build()));
        Cancha cancha = Cancha.builder()
                .id(10L).nombre("Cancha 10").deportes(Set.of(Deporte.FUTBOL)).capacidad(10).isActive(true)
                .precioBase(BigDecimal.valueOf(1000)).montoSena(BigDecimal.valueOf(200)).establecimiento(est).build();
        BloqueoCancha bloqueo = BloqueoCancha.builder()
                .cancha(cancha)
                .fechaInicio(LocalDateTime.of(2026, 8, 10, 9, 30))
                .fechaFin(LocalDateTime.of(2026, 8, 10, 12, 0))
                .motivo("Mantenimiento")
                .build();

        LocalDate fecha = LocalDate.of(2026, 8, 10);
        LocalTime hora = LocalTime.of(10, 0);

        when(establecimientoRepository.findActivosPorDeporte(null)).thenReturn(List.of(est));
        when(establecimientoRepository.precargarHorarios(List.of(1L))).thenReturn(List.of(est));
        when(canchaRepository.findByEstablecimientoIdInAndIsActiveTrue(List.of(1L))).thenReturn(List.of(cancha));
        when(reservaRepository.findCanchaIdsConSolapamiento(eq(List.of(10L)), any(), any())).thenReturn(List.of());
        when(bloqueoCanchaRepository.findByEstablecimientoIdInAndRango(eq(List.of(1L)), any(), any())).thenReturn(List.of(bloqueo));
        when(diaNoLaborableRepository.findByEstablecimientoIdInAndFecha(List.of(1L), fecha)).thenReturn(List.of());

        Page<ComplejoCardResponse> resultado = complejoPublicoService.buscarComplejos(
                null, null, null, null, fecha, hora, PageRequest.of(0, 20));

        assertEquals(0, resultado.getTotalElements());
    }

    @Test
    @DisplayName("buscarComplejos_ConFechaYHora_ExcluyeSiLaVentanaSolicitadaNoEntraCompletaEnElHorario")
    void buscarComplejos_ConFechaYHora_ExcluyeSiLaVentanaSolicitadaNoEntraCompletaEnElHorario() {
        Establecimiento est = establecimiento(1L, "complejo-uno", "Complejo Uno", false);
        est.setHorariosAtencion(List.of(HorarioAtencion.builder()
                .diaSemana(DayOfWeek.MONDAY)
                .horaApertura(LocalTime.of(9, 0))
                .horaCierre(LocalTime.of(22, 0)) // cierra a las 22:00
                .build()));

        LocalDate fecha = LocalDate.of(2026, 8, 10);
        LocalTime hora = LocalTime.of(21, 30); // + 60 min de ventana = termina 22:30, después del cierre

        when(establecimientoRepository.findActivosPorDeporte(null)).thenReturn(List.of(est));
        when(establecimientoRepository.precargarHorarios(List.of(1L))).thenReturn(List.of(est));
        when(canchaRepository.findByEstablecimientoIdInAndIsActiveTrue(List.of(1L))).thenReturn(List.of());
        when(bloqueoCanchaRepository.findByEstablecimientoIdInAndRango(eq(List.of(1L)), any(), any())).thenReturn(List.of());
        when(diaNoLaborableRepository.findByEstablecimientoIdInAndFecha(List.of(1L), fecha)).thenReturn(List.of());

        Page<ComplejoCardResponse> resultado = complejoPublicoService.buscarComplejos(
                null, null, null, null, fecha, hora, PageRequest.of(0, 20));

        assertEquals(0, resultado.getTotalElements());
    }

    @Test
    @DisplayName("buscarComplejos_ConFechaYHora_IncluyeHorarioQueCruzaMedianocheSiLaVentanaEntra")
    void buscarComplejos_ConFechaYHora_IncluyeHorarioQueCruzaMedianocheSiLaVentanaEntra() {
        Establecimiento est = establecimiento(1L, "complejo-uno", "Complejo Uno", false);
        est.setHorariosAtencion(List.of(HorarioAtencion.builder()
                .diaSemana(DayOfWeek.MONDAY)
                .horaApertura(LocalTime.of(20, 0))
                .horaCierre(LocalTime.of(2, 0)) // cruza medianoche
                .build()));
        Cancha cancha = Cancha.builder()
                .id(10L).nombre("Cancha 10").deportes(Set.of(Deporte.FUTBOL)).capacidad(10).isActive(true)
                .precioBase(BigDecimal.valueOf(1000)).montoSena(BigDecimal.valueOf(200)).establecimiento(est).build();

        LocalDate fecha = LocalDate.of(2026, 8, 10); // lunes
        LocalTime hora = LocalTime.of(23, 0); // + 60 min = 00:00 del martes, sigue dentro de la ventana (hasta las 02:00)

        when(establecimientoRepository.findActivosPorDeporte(null)).thenReturn(List.of(est));
        when(establecimientoRepository.precargarHorarios(List.of(1L))).thenReturn(List.of(est));
        when(canchaRepository.findByEstablecimientoIdInAndIsActiveTrue(List.of(1L))).thenReturn(List.of(cancha));
        when(reservaRepository.findCanchaIdsConSolapamiento(eq(List.of(10L)), any(), any())).thenReturn(List.of());
        when(bloqueoCanchaRepository.findByEstablecimientoIdInAndRango(eq(List.of(1L)), any(), any())).thenReturn(List.of());
        when(diaNoLaborableRepository.findByEstablecimientoIdInAndFecha(List.of(1L), fecha)).thenReturn(List.of());
        when(canchaRepository.findActivasConDeportesYTarifasByEstablecimientoIdIn(List.of(1L))).thenReturn(List.of(cancha));
        when(establecimientoRepository.precargarFotos(List.of(1L))).thenReturn(List.of(est));
        when(feedbackRepository.calcularPromediosPorEstablecimientos(List.of(1L))).thenReturn(List.of());
        when(feedbackRepository.contarPorEstablecimientos(List.of(1L))).thenReturn(List.of());

        Page<ComplejoCardResponse> resultado = complejoPublicoService.buscarComplejos(
                null, null, null, null, fecha, hora, PageRequest.of(0, 20));

        assertEquals(1, resultado.getTotalElements());
    }

    @Test
    @DisplayName("obtenerDetalle_VariasCanchas_DerivaDeportesPrecioDesdeYSenaDesdeYListaCanchas")
    void obtenerDetalle_VariasCanchas_DerivaDeportesPrecioDesdeYSenaDesdeYListaCanchas() {
        Establecimiento est = establecimiento(1L, "complejo-uno", "Complejo Uno", true);
        Cancha futbol = canchaConTarifa(10L, est, Set.of(Deporte.FUTBOL), BigDecimal.valueOf(1000), BigDecimal.valueOf(5000));
        Cancha padel = canchaConTarifa(11L, est, Set.of(Deporte.PADEL), BigDecimal.valueOf(800), BigDecimal.valueOf(3000));

        when(establecimientoRepository.findBySlugAndIsActiveTrue("complejo-uno")).thenReturn(java.util.Optional.of(est));
        when(canchaRepository.findActivasConDeportesYTarifasByEstablecimientoIdIn(List.of(1L)))
                .thenReturn(List.of(futbol, padel));
        when(feedbackRepository.calcularPromedioByEstablecimientoId(1L)).thenReturn(4.5);
        when(feedbackRepository.contarByEstablecimientoId(1L)).thenReturn(2L);
        when(feedbackRepository.findDestacadoByEstablecimientoId(1L)).thenReturn(java.util.Optional.empty());

        com.matiasmeira.sacaladelangulo.publico.dto.ComplejoDetalleResponse detalle =
                complejoPublicoService.obtenerDetalle("complejo-uno");

        assertEquals(Set.of(Deporte.FUTBOL, Deporte.PADEL), detalle.deportes());
        assertEquals(BigDecimal.valueOf(3000), detalle.precioDesde());
        assertEquals(BigDecimal.valueOf(800), detalle.senaDesde());
        assertEquals(2, detalle.canchas().size());
        assertEquals(4.5, detalle.promedioCalificacion());
    }

    private Feedback feedbackDestacadoDeJugador(String nombreJugador) {
        Usuario jugador = Usuario.builder().id(9L).nombre(nombreJugador).build();
        Reserva reserva = Reserva.builder().id(20L).jugador(jugador).build();
        return Feedback.builder()
                .id(30L)
                .reserva(reserva)
                .puntuacion(5)
                .comentario("Excelente cancha")
                .destacado(true)
                .fechaCreacion(LocalDateTime.of(2026, 8, 1, 10, 0))
                .build();
    }

    @Test
    @DisplayName("obtenerDetalle_ComentarioDestacadoConNombreDeDosPalabras_AnonimizaElApellidoAInicial")
    void obtenerDetalle_ComentarioDestacadoConNombreDeDosPalabras_AnonimizaElApellidoAInicial() {
        Establecimiento est = establecimiento(1L, "complejo-uno", "Complejo Uno", true);
        Feedback destacado = feedbackDestacadoDeJugador("Carlos Fernández");

        when(establecimientoRepository.findBySlugAndIsActiveTrue("complejo-uno")).thenReturn(java.util.Optional.of(est));
        when(canchaRepository.findActivasConDeportesYTarifasByEstablecimientoIdIn(List.of(1L))).thenReturn(List.of());
        when(feedbackRepository.calcularPromedioByEstablecimientoId(1L)).thenReturn(5.0);
        when(feedbackRepository.contarByEstablecimientoId(1L)).thenReturn(1L);
        when(feedbackRepository.findDestacadoByEstablecimientoId(1L)).thenReturn(java.util.Optional.of(destacado));

        com.matiasmeira.sacaladelangulo.publico.dto.ComplejoDetalleResponse detalle =
                complejoPublicoService.obtenerDetalle("complejo-uno");

        assertEquals("Carlos F.", detalle.comentarioDestacado().jugadorNombre());
    }

    @Test
    @DisplayName("obtenerDetalle_ComentarioDestacadoConNombreDeUnaPalabra_LoDevuelveSinCambios")
    void obtenerDetalle_ComentarioDestacadoConNombreDeUnaPalabra_LoDevuelveSinCambios() {
        Establecimiento est = establecimiento(1L, "complejo-uno", "Complejo Uno", true);
        Feedback destacado = feedbackDestacadoDeJugador("Carlos");

        when(establecimientoRepository.findBySlugAndIsActiveTrue("complejo-uno")).thenReturn(java.util.Optional.of(est));
        when(canchaRepository.findActivasConDeportesYTarifasByEstablecimientoIdIn(List.of(1L))).thenReturn(List.of());
        when(feedbackRepository.calcularPromedioByEstablecimientoId(1L)).thenReturn(5.0);
        when(feedbackRepository.contarByEstablecimientoId(1L)).thenReturn(1L);
        when(feedbackRepository.findDestacadoByEstablecimientoId(1L)).thenReturn(java.util.Optional.of(destacado));

        com.matiasmeira.sacaladelangulo.publico.dto.ComplejoDetalleResponse detalle =
                complejoPublicoService.obtenerDetalle("complejo-uno");

        assertEquals("Carlos", detalle.comentarioDestacado().jugadorNombre());
    }

    @Test
    @DisplayName("obtenerDetalle_SinComentarioDestacado_ComentarioDestacadoEsNulo")
    void obtenerDetalle_SinComentarioDestacado_ComentarioDestacadoEsNulo() {
        Establecimiento est = establecimiento(1L, "complejo-uno", "Complejo Uno", true);

        when(establecimientoRepository.findBySlugAndIsActiveTrue("complejo-uno")).thenReturn(java.util.Optional.of(est));
        when(canchaRepository.findActivasConDeportesYTarifasByEstablecimientoIdIn(List.of(1L))).thenReturn(List.of());
        when(feedbackRepository.calcularPromedioByEstablecimientoId(1L)).thenReturn(null);
        when(feedbackRepository.contarByEstablecimientoId(1L)).thenReturn(0L);
        when(feedbackRepository.findDestacadoByEstablecimientoId(1L)).thenReturn(java.util.Optional.empty());

        com.matiasmeira.sacaladelangulo.publico.dto.ComplejoDetalleResponse detalle =
                complejoPublicoService.obtenerDetalle("complejo-uno");

        assertNull(detalle.comentarioDestacado());
    }

    @Test
    @DisplayName("obtenerDetalle_SlugInexistente_LanzaEntityNotFoundException")
    void obtenerDetalle_SlugInexistente_LanzaEntityNotFoundException() {
        when(establecimientoRepository.findBySlugAndIsActiveTrue("no-existe")).thenReturn(java.util.Optional.empty());

        assertThrows(
                com.matiasmeira.sacaladelangulo.core.exception.EntityNotFoundException.class,
                () -> complejoPublicoService.obtenerDetalle("no-existe"));
    }

    @Test
    @DisplayName("obtenerDetalle_ComplejoInactivo_LanzaEntityNotFoundException")
    void obtenerDetalle_ComplejoInactivo_LanzaEntityNotFoundException() {
        // findBySlugAndIsActiveTrue ya filtra por isActive=true en el repositorio: un
        // complejo inactivo llega acá como Optional vacío, igual que un slug inexistente.
        when(establecimientoRepository.findBySlugAndIsActiveTrue("complejo-inactivo")).thenReturn(java.util.Optional.empty());

        assertThrows(
                com.matiasmeira.sacaladelangulo.core.exception.EntityNotFoundException.class,
                () -> complejoPublicoService.obtenerDetalle("complejo-inactivo"));
    }

    @Test
    @DisplayName("obtenerDisponibilidad_ResuelveSlugYDelegaEnDisponibilidadService")
    void obtenerDisponibilidad_ResuelveSlugYDelegaEnDisponibilidadService() {
        Establecimiento est = establecimiento(1L, "complejo-uno", "Complejo Uno", true);
        LocalDate fecha = LocalDate.of(2026, 8, 10);
        com.matiasmeira.sacaladelangulo.disponibilidad.dto.DisponibilidadEstablecimientoResponse respuestaEsperada =
                new com.matiasmeira.sacaladelangulo.disponibilidad.dto.DisponibilidadEstablecimientoResponse(1L, fecha, fecha, List.of());

        when(establecimientoRepository.findBySlugAndIsActiveTrue("complejo-uno")).thenReturn(java.util.Optional.of(est));
        when(disponibilidadService.obtenerDisponibilidad(1L, fecha, fecha)).thenReturn(respuestaEsperada);

        var resultado = complejoPublicoService.obtenerDisponibilidad("complejo-uno", fecha, fecha);

        assertEquals(respuestaEsperada, resultado);
    }

    @Test
    @DisplayName("obtenerDisponibilidad_SlugInexistente_LanzaEntityNotFoundException")
    void obtenerDisponibilidad_SlugInexistente_LanzaEntityNotFoundException() {
        LocalDate fecha = LocalDate.of(2026, 8, 10);
        when(establecimientoRepository.findBySlugAndIsActiveTrue("no-existe")).thenReturn(java.util.Optional.empty());

        assertThrows(
                com.matiasmeira.sacaladelangulo.core.exception.EntityNotFoundException.class,
                () -> complejoPublicoService.obtenerDisponibilidad("no-existe", fecha, fecha));
    }
}
