package com.matiasmeira.sacaladelangulo.publico.service;

import com.matiasmeira.sacaladelangulo.auth.model.Usuario;
import com.matiasmeira.sacaladelangulo.core.exception.EntityNotFoundException;
import com.matiasmeira.sacaladelangulo.disponibilidad.dto.DisponibilidadEstablecimientoResponse;
import com.matiasmeira.sacaladelangulo.disponibilidad.service.DisponibilidadService;
import com.matiasmeira.sacaladelangulo.establecimiento.dto.FeedbackDestacadoDto;
import com.matiasmeira.sacaladelangulo.establecimiento.dto.HorarioAtencionDto;
import com.matiasmeira.sacaladelangulo.establecimiento.model.BloqueoCancha;
import com.matiasmeira.sacaladelangulo.establecimiento.model.Cancha;
import com.matiasmeira.sacaladelangulo.establecimiento.model.Deporte;
import com.matiasmeira.sacaladelangulo.establecimiento.model.Establecimiento;
import com.matiasmeira.sacaladelangulo.establecimiento.model.Tarifa;
import com.matiasmeira.sacaladelangulo.establecimiento.repository.BloqueoCanchaRepository;
import com.matiasmeira.sacaladelangulo.establecimiento.repository.CanchaRepository;
import com.matiasmeira.sacaladelangulo.establecimiento.repository.DiaNoLaborableRepository;
import com.matiasmeira.sacaladelangulo.establecimiento.repository.EstablecimientoRepository;
import com.matiasmeira.sacaladelangulo.establecimiento.service.GeoUtils;
import com.matiasmeira.sacaladelangulo.establecimiento.service.HorarioAtencionCalculator;
import com.matiasmeira.sacaladelangulo.feedback.model.Feedback;
import com.matiasmeira.sacaladelangulo.feedback.repository.FeedbackRepository;
import com.matiasmeira.sacaladelangulo.publico.dto.CanchaPublicaDto;
import com.matiasmeira.sacaladelangulo.publico.dto.ComplejoCardResponse;
import com.matiasmeira.sacaladelangulo.publico.dto.ComplejoDetalleResponse;
import com.matiasmeira.sacaladelangulo.reserva.repository.ReservaRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Zona pública del marketplace: listado, detalle y disponibilidad de complejos para un
 * visitante anónimo. Ninguno de los DTOs que devuelve incluye duenoId ni otro dato interno
 * del dueño (ver contrato de zona pública).
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ComplejoPublicoService {

    private static final double RADIO_BUSQUEDA_DEFAULT_KM = 10.0;
    private static final double RADIO_BUSQUEDA_MAXIMO_KM = 100.0;
    private static final int MAX_CANDIDATOS_SIN_UBICACION = 500;
    private static final int VENTANA_DISPONIBILIDAD_MINUTOS = 60;

    private final EstablecimientoRepository establecimientoRepository;
    private final CanchaRepository canchaRepository;
    private final ReservaRepository reservaRepository;
    private final FeedbackRepository feedbackRepository;
    private final DisponibilidadService disponibilidadService;
    private final BloqueoCanchaRepository bloqueoCanchaRepository;
    private final DiaNoLaborableRepository diaNoLaborableRepository;

    /**
     * Listado público de complejos: sirve tanto al home (sin lat/lng, ordenado por rating)
     * como a la búsqueda con ubicación (ordenado por distancia). Cuando se pide fecha/hora,
     * filtra a los complejos con al menos una cancha libre en esa ventana -- ese filtro no
     * es una columna de base, así que en ese caso se pagina en memoria sobre el conjunto ya
     * acotado por geo/deporte a nivel de base (mismo trade-off que ya tenía el viejo
     * /buscar, que tampoco paginaba).
     */
    public Page<ComplejoCardResponse> buscarComplejos(Double lat, Double lng, Double distanciaKm, Deporte deporte,
            LocalDate fecha, LocalTime hora, Pageable pageable) {
        validarUbicacion(lat, lng);
        boolean conUbicacion = lat != null && lng != null;
        // distanciaKm es un radio pedido por un caller anónimo y sin acotar de por sí puede
        // forzar un scan efectivamente ilimitado en la query geo-filtrada (ej. distanciaKm=25000):
        // se clampea al máximo razonable de búsqueda "cercana" en vez de usarlo tal cual.
        Double radio = (distanciaKm != null && distanciaKm > 0) ? Math.min(distanciaKm, RADIO_BUSQUEDA_MAXIMO_KM) : RADIO_BUSQUEDA_DEFAULT_KM;

        List<Establecimiento> candidatos = conUbicacion
                ? establecimientoRepository.findCercanosYPorDeporte(lat, lng, radio, deporte)
                : establecimientoRepository.findActivosPorDeporte(deporte, PageRequest.of(0, MAX_CANDIDATOS_SIN_UBICACION, Sort.by("id")));

        if (fecha != null && hora != null) {
            candidatos = filtrarPorDisponibilidad(candidatos, deporte, fecha, hora);
        }

        List<ComplejoCardResponse> cards = mapearACards(candidatos, conUbicacion ? lat : null, conUbicacion ? lng : null, deporte);
        List<ComplejoCardResponse> ordenados = conUbicacion
                ? cards.stream()
                        .sorted(Comparator.comparing(ComplejoCardResponse::distanciaKm, Comparator.nullsLast(Comparator.naturalOrder()))
                                .thenComparing(ComplejoCardResponse::slug))
                        .toList()
                : cards.stream()
                        .sorted(Comparator.comparing(ComplejoCardResponse::promedioCalificacion, Comparator.nullsLast(Comparator.reverseOrder()))
                                .thenComparing(ComplejoCardResponse::slug))
                        .toList();

        return paginarEnMemoria(ordenados, pageable);
    }

    private void validarUbicacion(Double lat, Double lng) {
        if ((lat == null) != (lng == null)) {
            throw new IllegalArgumentException("lat y lng deben proveerse juntos");
        }
    }

    private List<Establecimiento> filtrarPorDisponibilidad(List<Establecimiento> candidatos, Deporte deporte, LocalDate fecha, LocalTime hora) {
        List<Long> establecimientoIds = candidatos.stream().map(Establecimiento::getId).toList();
        establecimientoRepository.precargarHorarios(establecimientoIds);

        List<Cancha> canchas = canchaRepository.findByEstablecimientoIdInAndIsActiveTrue(establecimientoIds);
        if (deporte != null) {
            canchas = canchas.stream().filter(c -> c.getDeportes().contains(deporte)).toList();
        }

        Map<Long, List<Cancha>> canchasPorEstablecimiento = canchas.stream()
                .collect(Collectors.groupingBy(c -> c.getEstablecimiento().getId()));

        LocalDateTime inicioReserva = LocalDateTime.of(fecha, hora);
        LocalDateTime finReserva = inicioReserva.plusMinutes(VENTANA_DISPONIBILIDAD_MINUTOS);

        List<Long> canchaIds = canchas.stream().map(Cancha::getId).toList();
        Set<Long> canchasNoDisponibles = canchaIds.isEmpty()
                ? new HashSet<>()
                : new HashSet<>(reservaRepository.findCanchaIdsConSolapamiento(canchaIds, inicioReserva, finReserva));
        // A diferencia de la query de reservas (que filtra por canchaId y no tiene sentido
        // disparar si no hay ninguna cancha activa), esta filtra por establecimientoId: se
        // consulta siempre, igual que precargarHorarios y diaNoLaborableRepository más abajo.
        bloqueoCanchaRepository.findByEstablecimientoIdInAndRango(establecimientoIds, inicioReserva, finReserva).stream()
                .map(b -> b.getCancha().getId())
                .forEach(canchasNoDisponibles::add);

        Set<Long> establecimientosNoLaborables = diaNoLaborableRepository
                .findByEstablecimientoIdInAndFecha(establecimientoIds, fecha).stream()
                .map(d -> d.getEstablecimiento().getId())
                .collect(Collectors.toSet());

        return candidatos.stream()
                .filter(est -> !establecimientosNoLaborables.contains(est.getId()))
                .filter(est -> estaAbiertoEnVentana(est, fecha, inicioReserva, finReserva))
                .filter(est -> canchasPorEstablecimiento.getOrDefault(est.getId(), List.of()).stream()
                        .anyMatch(c -> !canchasNoDisponibles.contains(c.getId())))
                .toList();
    }

    /**
     * ¿El complejo tiene un HorarioAtencion para el día de la semana de "fecha" que cubra
     * por completo la ventana [inicioReserva, finReserva)? Mismo criterio que
     * DisponibilidadService.generarSlotsLibres: el turno completo tiene que entrar en el
     * horario, no solo su inicio.
     */
    private boolean estaAbiertoEnVentana(Establecimiento establecimiento, LocalDate fecha, LocalDateTime inicioReserva, LocalDateTime finReserva) {
        return establecimiento.getHorariosAtencion().stream()
                .filter(h -> h.getDiaSemana() == fecha.getDayOfWeek())
                .findFirst()
                .map(horario -> HorarioAtencionCalculator.calcularVentana(horario, fecha))
                .map(ventana -> !inicioReserva.isBefore(ventana.inicio()) && !finReserva.isAfter(ventana.fin()))
                .orElse(false);
    }

    private List<ComplejoCardResponse> mapearACards(List<Establecimiento> establecimientos, Double lat, Double lng, Deporte deporte) {
        if (establecimientos.isEmpty()) {
            return List.of();
        }

        List<Long> ids = establecimientos.stream().map(Establecimiento::getId).toList();
        establecimientoRepository.precargarFotos(ids);

        Map<Long, List<Cancha>> canchasPorEstablecimiento = canchaRepository
                .findActivasConDeportesYTarifasByEstablecimientoIdIn(ids).stream()
                .collect(Collectors.groupingBy(c -> c.getEstablecimiento().getId()));

        Map<Long, Double> promedios = new HashMap<>();
        for (Object[] fila : feedbackRepository.calcularPromediosPorEstablecimientos(ids)) {
            promedios.put((Long) fila[0], (Double) fila[1]);
        }
        Map<Long, Long> cantidades = new HashMap<>();
        for (Object[] fila : feedbackRepository.contarPorEstablecimientos(ids)) {
            cantidades.put((Long) fila[0], (Long) fila[1]);
        }

        return establecimientos.stream()
                .map(est -> construirCard(est, canchasPorEstablecimiento.getOrDefault(est.getId(), List.of()), deporte,
                        lat, lng, promedios.get(est.getId()), cantidades.getOrDefault(est.getId(), 0L)))
                .toList();
    }

    private ComplejoCardResponse construirCard(Establecimiento establecimiento, List<Cancha> canchas, Deporte deporte,
            Double lat, Double lng, Double promedioCalificacion, Long cantidadCalificaciones) {

        Set<Deporte> deportes = canchas.stream().flatMap(c -> c.getDeportes().stream()).collect(Collectors.toSet());
        List<Cancha> relevantes = deporte == null
                ? canchas
                : canchas.stream().filter(c -> c.getDeportes().contains(deporte)).toList();

        BigDecimal precioDesde = relevantes.stream()
                .map(this::precioMinimoDeCancha)
                .min(Comparator.naturalOrder())
                .orElse(null);
        BigDecimal senaDesde = relevantes.stream()
                .map(Cancha::getMontoSena)
                .filter(Objects::nonNull)
                .min(Comparator.naturalOrder())
                .orElse(null);

        String fotoPrincipal = establecimiento.getFotos().isEmpty() ? null : establecimiento.getFotos().get(0);
        Double distanciaKm = (lat != null && lng != null)
                ? GeoUtils.distanciaKm(lat, lng, establecimiento.getLatitud(), establecimiento.getLongitud())
                : null;

        return new ComplejoCardResponse(
                establecimiento.getSlug(),
                establecimiento.getNombre(),
                establecimiento.getDireccion(),
                fotoPrincipal,
                deportes,
                precioDesde,
                establecimiento.getRequiereSena(),
                senaDesde,
                distanciaKm,
                promedioCalificacion,
                cantidadCalificaciones
        );
    }

    /**
     * Precio mínimo que puede llegar a cobrar esta cancha: el menor entre sus
     * Tarifa.precio configuradas y su precioBase (que PrecioReservaCalculator ya usa
     * como fallback cuando ninguna Tarifa matchea una reserva puntual). precioBase es
     * NOT NULL en el modelo, así que esto nunca devuelve null -- a diferencia de antes,
     * cuando una cancha sin tarifas no aportaba ningún candidato y precioDesde podía
     * quedar en null pese a ser reservable.
     */
    private BigDecimal precioMinimoDeCancha(Cancha cancha) {
        return Stream.concat(cancha.getTarifas().stream().map(Tarifa::getPrecio), Stream.of(cancha.getPrecioBase()))
                .min(Comparator.naturalOrder())
                .orElseThrow();
    }

    private Page<ComplejoCardResponse> paginarEnMemoria(List<ComplejoCardResponse> items, Pageable pageable) {
        int total = items.size();
        // getOffset() devuelve long y Spring Data no acota el parámetro "page" (solo "size",
        // en 2000 por default): con un page muy grande el offset puede superar Integer.MAX_VALUE,
        // así que el clamp tiene que hacerse en long y recién casteamos a int al final -- castear
        // el long crudo antes de acotar puede dar un negativo (overflow) y romper el subList.
        long desdeLong = Math.min(Math.max(pageable.getOffset(), 0L), (long) total);
        int desde = (int) desdeLong;
        int hasta = Math.min(desde + pageable.getPageSize(), total);
        return new PageImpl<>(items.subList(desde, hasta), pageable, total);
    }

    /**
     * Detalle público de un complejo activo. 404 si el slug no existe o si el complejo
     * está inactivo (findBySlugAndIsActiveTrue ya filtra eso, así que ambos casos llegan
     * acá como Optional vacío).
     */
    public ComplejoDetalleResponse obtenerDetalle(String slug) {
        Establecimiento establecimiento = establecimientoRepository.findBySlugAndIsActiveTrue(slug)
                .orElseThrow(() -> new EntityNotFoundException("Establecimiento no encontrado"));

        List<Cancha> canchas = canchaRepository
                .findActivasConDeportesYTarifasByEstablecimientoIdIn(List.of(establecimiento.getId()));

        Set<Deporte> deportes = canchas.stream().flatMap(c -> c.getDeportes().stream()).collect(Collectors.toSet());
        BigDecimal precioDesde = canchas.stream()
                .map(this::precioMinimoDeCancha)
                .min(Comparator.naturalOrder())
                .orElse(null);
        BigDecimal senaDesde = canchas.stream()
                .map(Cancha::getMontoSena)
                .filter(Objects::nonNull)
                .min(Comparator.naturalOrder())
                .orElse(null);

        List<CanchaPublicaDto> canchasPublicas = canchas.stream()
                .map(c -> new CanchaPublicaDto(
                        c.getId(),
                        c.getNombre(),
                        Set.copyOf(c.getDeportes()),
                        precioMinimoDeCancha(c)))
                .toList();

        List<HorarioAtencionDto> horarios = establecimiento.getHorariosAtencion() == null ? List.of()
                : establecimiento.getHorariosAtencion().stream()
                        .map(h -> new HorarioAtencionDto(h.getDiaSemana(), h.getHoraApertura(), h.getHoraCierre()))
                        .toList();

        Double promedio = feedbackRepository.calcularPromedioByEstablecimientoId(establecimiento.getId());
        Long cantidad = feedbackRepository.contarByEstablecimientoId(establecimiento.getId());
        FeedbackDestacadoDto destacado = feedbackRepository.findDestacadoByEstablecimientoId(establecimiento.getId())
                .map(this::mapFeedbackDestacado)
                .orElse(null);

        return new ComplejoDetalleResponse(
                establecimiento.getSlug(),
                establecimiento.getNombre(),
                establecimiento.getDireccion(),
                establecimiento.getLatitud(),
                establecimiento.getLongitud(),
                deportes,
                Set.copyOf(establecimiento.getServicios()),
                List.copyOf(establecimiento.getFotos()),
                horarios,
                canchasPublicas,
                precioDesde,
                establecimiento.getRequiereSena(),
                senaDesde,
                promedio,
                cantidad != null ? cantidad : 0L,
                destacado
        );
    }

    private FeedbackDestacadoDto mapFeedbackDestacado(Feedback feedback) {
        Usuario jugador = feedback.getReserva().getJugador();
        return new FeedbackDestacadoDto(
                feedback.getId(),
                feedback.getPuntuacion(),
                feedback.getComentario(),
                jugador != null ? anonimizarNombre(jugador.getNombre()) : null,
                feedback.getFechaCreacion()
        );
    }

    /**
     * Esta es la zona pública y anónima del marketplace: no corresponde exponer el nombre
     * completo del jugador que dejó la reseña a cualquier visitante sin autenticar. Se
     * muestra el primer nombre tal cual y solo la inicial del segundo token (ej. "Carlos
     * Fernández" -> "Carlos F."). Si el nombre tiene un solo token no hay nada más para
     * anonimizar y se devuelve tal cual.
     */
    private String anonimizarNombre(String nombreCompleto) {
        if (nombreCompleto == null || nombreCompleto.isBlank()) {
            return nombreCompleto;
        }
        String[] tokens = nombreCompleto.trim().split("\\s+");
        if (tokens.length < 2) {
            return nombreCompleto;
        }
        return tokens[0] + " " + Character.toUpperCase(tokens[1].charAt(0)) + ".";
    }

    /**
     * Disponibilidad pública de un complejo activo: resuelve slug -> id y reusa
     * DisponibilidadService tal cual, sin proyección propia. Su árbol de respuesta
     * (DisponibilidadEstablecimientoResponse -> DisponibilidadDiaResponse ->
     * DisponibilidadCanchaResponse -> DisponibilidadDuracionResponse ->
     * SlotDisponibleResponse) ya es 100% libre/ocupado por slot: no tiene ningún campo de
     * jugador/titular, así que no hace falta filtrar nada acá.
     */
    public DisponibilidadEstablecimientoResponse obtenerDisponibilidad(String slug, LocalDate fecha, LocalDate fechaFin) {
        Establecimiento establecimiento = establecimientoRepository.findBySlugAndIsActiveTrue(slug)
                .orElseThrow(() -> new EntityNotFoundException("Establecimiento no encontrado"));
        return disponibilidadService.obtenerDisponibilidad(establecimiento.getId(), fecha, fechaFin);
    }
}
