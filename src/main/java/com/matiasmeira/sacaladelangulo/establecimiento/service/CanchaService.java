package com.matiasmeira.sacaladelangulo.establecimiento.service;

import com.matiasmeira.sacaladelangulo.auth.model.PlanSuscripcion;
import com.matiasmeira.sacaladelangulo.auth.model.Usuario;
import com.matiasmeira.sacaladelangulo.core.exception.EntityNotFoundException;
import com.matiasmeira.sacaladelangulo.empleado.model.AccionAuditoria;
import com.matiasmeira.sacaladelangulo.empleado.service.AutorizacionEmpleadoService;
import com.matiasmeira.sacaladelangulo.empleado.service.RegistroAuditoriaService;
import com.matiasmeira.sacaladelangulo.establecimiento.dto.CanchaRequest;
import com.matiasmeira.sacaladelangulo.establecimiento.dto.CanchaResponse;
import com.matiasmeira.sacaladelangulo.establecimiento.dto.TarifaDto;
import com.matiasmeira.sacaladelangulo.establecimiento.model.Cancha;
import com.matiasmeira.sacaladelangulo.establecimiento.model.Deporte;
import com.matiasmeira.sacaladelangulo.establecimiento.model.Establecimiento;
import com.matiasmeira.sacaladelangulo.establecimiento.model.Tarifa;
import com.matiasmeira.sacaladelangulo.establecimiento.repository.CanchaRepository;
import com.matiasmeira.sacaladelangulo.establecimiento.repository.EstablecimientoRepository;
import com.matiasmeira.sacaladelangulo.publico.service.ComplejoDetalleCache;
import com.matiasmeira.sacaladelangulo.reserva.model.Reserva;
import com.matiasmeira.sacaladelangulo.reserva.repository.ReservaRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Servicio de negocio para canchas.
 */
@Service
@RequiredArgsConstructor
@Transactional
public class CanchaService {

    private static final BigDecimal SENA_MINIMA_PLAN_LIMITADO = BigDecimal.valueOf(500);
    private static final List<Integer> DURACIONES_POR_DEFECTO = List.of(60, 90, 120);

    private final CanchaRepository canchaRepository;
    private final EstablecimientoRepository establecimientoRepository;
    private final AutorizacionEmpleadoService autorizacionEmpleadoService;
    private final RegistroAuditoriaService registroAuditoriaService;
    private final ComplejoDetalleCache complejoDetalleCache;
    private final ReservaRepository reservaRepository;

    public CanchaResponse crearCancha(Long establecimientoId, CanchaRequest request, String email) {
        validarSolapamientoTarifas(request.tarifas());

        Establecimiento establecimiento = buscarEstablecimientoPorId(establecimientoId);
        Usuario usuarioAutenticado = autorizacionEmpleadoService.validarPropietarioOAdmin(establecimiento, email);

        BigDecimal montoSena = validarMontoSena(request.montoSena(), usuarioAutenticado.getPlanSuscripcion());
        Integer canchasNecesarias = calcularCanchasNecesarias(request.canchasFisicasIds(), request.cantidadCanchasNecesarias());
        List<Integer> duracionesPermitidas = resolverDuraciones(request.duracionesPermitidas());

        validarPreciosPorDuracion(request.preciosPorDuracion(), request.tarifas(), duracionesPermitidas);

        Cancha cancha = Cancha.builder()
                .nombre(request.nombre())
                .deportes(copiaDeportes(request.deportes()))
                .precioBase(request.precioBase())
                .montoSena(montoSena)
                .duracionesPermitidas(duracionesPermitidas)
                .preciosPorDuracion(normalizarPrecios(request.preciosPorDuracion()))
                .permiteInicioMediaHora(request.permiteInicioMediaHora() != null ? request.permiteInicioMediaHora() : true)
                .isActive(true)
                .establecimiento(establecimiento)
                .canchasNecesarias(canchasNecesarias)
                .build();

        cancha.setCanchasFisicas(resolverCanchasFisicas(establecimientoId, request.canchasFisicasIds()));
        validarConfiguracionDePool(establecimientoId, null, cancha.getCanchasFisicas());
        cancha.setTarifas(mapearTarifas(request.tarifas(), cancha));

        Cancha canchaGuardada = canchaRepository.save(cancha);

        registroAuditoriaService.registrarSobreEstablecimiento(usuarioAutenticado, establecimiento,
                AccionAuditoria.CREAR_CANCHA, canchaGuardada.getId(),
                "Cancha creada: " + canchaGuardada.getNombre() + ", precio base " + canchaGuardada.getPrecioBase());

        // La ficha pública lista las canchas activas y calcula precioDesde/senaDesde a
        // partir de ellas, así que un alta la deja desactualizada.
        complejoDetalleCache.invalidarPorEstablecimientoId(establecimientoId);

        return mapToResponse(canchaGuardada);
    }

    /**
     * @param incluirInactivas si es {@code true}, también trae canchas desactivadas (para
     *                         que el panel las pueda reactivar, ver actualizarCancha). Uso
     *                         exclusivo del panel: la agenda y cualquier vista pública deben
     *                         seguir viendo únicamente canchas activas.
     *
     *                         Ver canchas inactivas es parte de LA GESTIÓN de canchas
     *                         (reactivar/editar), no de operar el mostrador: exige dueño/admin,
     *                         igual que actualizarCancha/desactivarCancha — no el criterio más
     *                         laxo de validarLectura (PERMISOS_OPERATIVOS_DE_RESERVA), que un
     *                         EMPLOYEE que sólo cobra o cancela turnos también cumple y no
     *                         tiene por qué ver canchas que ni puede reactivar.
     */
    @Transactional(readOnly = true)
    public List<CanchaResponse> obtenerCanchasPorEstablecimiento(Long establecimientoId, String email, boolean incluirInactivas) {
        Establecimiento establecimiento = buscarEstablecimientoPorId(establecimientoId);
        if (incluirInactivas) {
            autorizacionEmpleadoService.validarPropietarioOAdmin(establecimiento, email);
        } else {
            // Mismo conjunto que la agenda: se dibuja POR cancha, así que sin este listado
            // no se renderiza aunque el empleado sólo vaya a cobrar. Alta, edición y baja
            // de canchas siguen siendo del dueño.
            autorizacionEmpleadoService.validarLectura(establecimiento, email,
                    AutorizacionEmpleadoService.PERMISOS_OPERATIVOS_DE_RESERVA);
        }

        List<Cancha> canchas = incluirInactivas
                ? canchaRepository.findByEstablecimientoId(establecimientoId)
                : canchaRepository.findByEstablecimientoIdAndIsActiveTrue(establecimientoId);

        return canchas.stream()
                .map(this::mapToResponse)
                .collect(Collectors.toList());
    }

    public CanchaResponse actualizarCancha(Long establecimientoId, Long canchaId, CanchaRequest request, String email) {
        validarSolapamientoTarifas(request.tarifas());

        Establecimiento establecimiento = buscarEstablecimientoPorId(establecimientoId);
        Usuario usuarioAutenticado = autorizacionEmpleadoService.validarPropietarioOAdmin(establecimiento, email);

        Cancha cancha = canchaRepository.findById(canchaId)
                .orElseThrow(() -> new EntityNotFoundException("Cancha no encontrada"));

        if (!cancha.getEstablecimiento().getId().equals(establecimientoId)) {
            throw new IllegalArgumentException("La cancha no pertenece a este establecimiento");
        }

        BigDecimal montoSena = validarMontoSena(request.montoSena(), usuarioAutenticado.getPlanSuscripcion());
        List<Integer> duracionesPermitidas = resolverDuraciones(request.duracionesPermitidas());

        validarPreciosPorDuracion(request.preciosPorDuracion(), request.tarifas(), duracionesPermitidas);

        // isActive es reversible (ver B19/M-XX en la auditoría): null en el request deja el
        // estado actual sin tocar, así un edit que no incluye este campo nunca reactiva ni
        // desactiva por accidente. Se resuelve ANTES de tocar la entidad para poder comparar
        // el estado previo contra el nuevo.
        boolean estabaActiva = Boolean.TRUE.equals(cancha.getIsActive());
        boolean debeQuedarActiva = request.isActive() != null ? request.isActive() : estabaActiva;
        if (estabaActiva && !debeQuedarActiva) {
            validarDesactivacion(cancha);
        }

        cancha.setNombre(request.nombre());
        cancha.setDeportes(copiaDeportes(request.deportes()));
        cancha.setPrecioBase(request.precioBase());
        cancha.setMontoSena(montoSena);
        cancha.setDuracionesPermitidas(duracionesPermitidas);
        cancha.setPreciosPorDuracion(normalizarPrecios(request.preciosPorDuracion()));
        cancha.setPermiteInicioMediaHora(request.permiteInicioMediaHora() != null ? request.permiteInicioMediaHora() : true);
        cancha.setCanchasNecesarias(calcularCanchasNecesarias(request.canchasFisicasIds(), request.cantidadCanchasNecesarias()));
        cancha.setCanchasFisicas(resolverCanchasFisicas(establecimientoId, request.canchasFisicasIds()));
        cancha.setIsActive(debeQuedarActiva);
        // Corre siempre, incluso si el pool no cambió: al reactivar, otra lógica pudo haberse
        // creado con un pool parcialmente superpuesto mientras ésta estaba inactiva (el guard
        // solo mira lógicas ACTIVAS, así que no la vio en ese momento).
        validarConfiguracionDePool(establecimientoId, canchaId, cancha.getCanchasFisicas());

        if (request.tarifas() != null) {
            cancha.getTarifas().clear();
            cancha.getTarifas().addAll(mapearTarifas(request.tarifas(), cancha));
        }

        Cancha canchaGuardada = canchaRepository.save(cancha);

        registroAuditoriaService.registrarSobreEstablecimiento(usuarioAutenticado, establecimiento,
                AccionAuditoria.ACTUALIZAR_CANCHA, canchaGuardada.getId(),
                "Cancha actualizada: " + canchaGuardada.getNombre() + ", precio base " + canchaGuardada.getPrecioBase());

        complejoDetalleCache.invalidarPorEstablecimientoId(establecimientoId);

        return mapToResponse(canchaGuardada);
    }

    /**
     * Desactiva una cancha (baja lógica, isActive=false): sin este método no había forma
     * de dar de baja una cancha, solo de crearla o editarla (ver B19 en la auditoría).
     * Misma validación de ownership que actualizarCancha. isActive es reversible: se puede
     * volver a activar desde actualizarCancha.
     */
    public void desactivarCancha(Long establecimientoId, Long canchaId, String email) {
        Establecimiento establecimiento = buscarEstablecimientoPorId(establecimientoId);
        autorizacionEmpleadoService.validarPropietarioOAdmin(establecimiento, email);

        Cancha cancha = canchaRepository.findById(canchaId)
                .orElseThrow(() -> new EntityNotFoundException("Cancha no encontrada"));

        if (!cancha.getEstablecimiento().getId().equals(establecimientoId)) {
            throw new IllegalArgumentException("La cancha no pertenece a este establecimiento");
        }

        validarDesactivacion(cancha);
        cancha.setIsActive(false);
        canchaRepository.save(cancha);
        complejoDetalleCache.invalidarPorEstablecimientoId(establecimientoId);
    }

    /**
     * Bloquea la desactivación si alguna reserva futura del GRUPO de pool de {@code cancha}
     * (cierre transitivo de PoolCanchaCalculator, no solo el pool propio: ver ejemplo de
     * F1/F2/F3-C9 en el diagnóstico) deja de tener capacidad al sacarla. No es una regla
     * nueva: se recalcula con el mismo PoolCanchaCalculator que usa producción, sobre una
     * copia en memoria que simula el estado post-desactivación — footprint() ya excluye las
     * físicas inactivas de la capacidad del grupo.
     *
     * La simulación NO muta la entidad managed: {@code cancha} está dentro de la
     * transacción, y flipear isActive en el objeto real quedaría un auto-flush de distancia
     * de convertirse en un UPDATE persistido como efecto colateral de una validación. En su
     * lugar, simularDesactivacion arma copias descartables (nunca pasan por save) y
     * reemplazaPorSimulada resuelve, por id, qué objeto le corresponde a cada Cancha/Reserva
     * antes de llamar a PoolCanchaCalculator — necesario porque una lógica que referencia a
     * "cancha" como física tiene su PROPIA copia de canchasFisicas con el reemplazo hecho.
     */
    private void validarDesactivacion(Cancha cancha) {
        Long establecimientoId = cancha.getEstablecimiento().getId();
        List<Cancha> todasLasCanchas = canchaRepository.findByEstablecimientoId(establecimientoId);

        Set<Long> canchasRelacionadas = PoolCanchaCalculator.canchasRelacionadas(cancha, todasLasCanchas);
        LocalDateTime ahora = LocalDateTime.now();
        List<Reserva> reservasFuturasDelGrupo = reservaRepository.findFuturasPorCanchaIds(canchasRelacionadas, ahora);
        if (reservasFuturasDelGrupo.isEmpty()) {
            return;
        }

        Map<Long, Cancha> simuladasPorId = simularDesactivacion(cancha, todasLasCanchas);
        List<Cancha> todasLasCanchasSimuladas = todasLasCanchas.stream()
                .map(c -> simuladasPorId.getOrDefault(c.getId(), c))
                .toList();

        for (Reserva reservaAValidar : reservasFuturasDelGrupo) {
            List<Reserva> otrasSolapadas = reservasFuturasDelGrupo.stream()
                    .filter(r -> !r.getId().equals(reservaAValidar.getId()))
                    .filter(r -> seSuperponenEnTiempo(r, reservaAValidar))
                    .map(r -> conCanchaSimulada(r, simuladasPorId))
                    .toList();

            Cancha candidataSimulada = simuladasPorId.getOrDefault(
                    reservaAValidar.getCancha().getId(), reservaAValidar.getCancha());

            boolean sigueDisponible = PoolCanchaCalculator.hayDisponibilidad(
                    candidataSimulada, otrasSolapadas, todasLasCanchasSimuladas);
            if (!sigueDisponible) {
                throw new IllegalArgumentException(
                        "No se puede desactivar \"" + cancha.getNombre() + "\": la reserva de \""
                                + reservaAValidar.getCancha().getNombre() + "\" del "
                                + reservaAValidar.getFechaHoraInicio().toLocalDate() + " de "
                                + reservaAValidar.getFechaHoraInicio().toLocalTime() + " a "
                                + reservaAValidar.getFechaHoraFin().toLocalTime()
                                + " se queda sin cupo disponible. Cancelá o reprogramá esa reserva antes de desactivar la cancha.");
            }
        }
    }

    /**
     * Copias descartables (id + campos que lee PoolCanchaCalculator, nada más) que
     * representan el estado post-desactivación de {@code cancha}: ella misma inactiva, y
     * cualquier lógica de {@code todasLasCanchas} que la tenga como física, con su
     * canchasFisicas reconstruido para apuntar a esa copia inactiva en vez de a la original.
     * Devuelve un mapa id->copia; una cancha ausente del mapa no cambia (se usa tal cual).
     */
    private Map<Long, Cancha> simularDesactivacion(Cancha cancha, List<Cancha> todasLasCanchas) {
        Cancha copiaInactiva = Cancha.builder()
                .id(cancha.getId())
                .isActive(false)
                .canchasFisicas(cancha.getCanchasFisicas())
                .canchasNecesarias(cancha.getCanchasNecesarias())
                .build();

        Map<Long, Cancha> simuladas = new HashMap<>();
        simuladas.put(cancha.getId(), copiaInactiva);

        for (Cancha otra : todasLasCanchas) {
            if (otra.getId().equals(cancha.getId())
                    || otra.getCanchasFisicas() == null
                    || otra.getCanchasFisicas().stream().noneMatch(f -> f.getId().equals(cancha.getId()))) {
                continue;
            }
            Set<Cancha> fisicasSimuladas = otra.getCanchasFisicas().stream()
                    .map(f -> f.getId().equals(cancha.getId()) ? copiaInactiva : f)
                    .collect(Collectors.toCollection(LinkedHashSet::new));
            simuladas.put(otra.getId(), Cancha.builder()
                    .id(otra.getId())
                    .canchasFisicas(fisicasSimuladas)
                    .canchasNecesarias(otra.getCanchasNecesarias())
                    .build());
        }
        return simuladas;
    }

    /** Reserva descartable con la misma cancha (por id) que use la simulación en curso. */
    private Reserva conCanchaSimulada(Reserva reserva, Map<Long, Cancha> simuladasPorId) {
        Cancha simulada = simuladasPorId.get(reserva.getCancha().getId());
        if (simulada == null) {
            return reserva;
        }
        return Reserva.builder().id(reserva.getId()).cancha(simulada)
                .fechaHoraInicio(reserva.getFechaHoraInicio()).fechaHoraFin(reserva.getFechaHoraFin()).build();
    }

    private boolean seSuperponenEnTiempo(Reserva a, Reserva b) {
        return a.getFechaHoraInicio().isBefore(b.getFechaHoraFin()) && a.getFechaHoraFin().isAfter(b.getFechaHoraInicio());
    }


    /**
     * El plan FREE exige una seña mínima obligatoria; el resto de los planes (incluido
     * TRIAL) permite no cobrar seña (nunca un monto negativo).
     */
    /**
     * Devuelve siempre una lista nueva y mutable, nunca DURACIONES_POR_DEFECTO ni la lista
     * del request. Cancha.duracionesPermitidas es una {@code @ElementCollection}: durante el
     * merge, Hibernate hace clear()+addAll() sobre la instancia que se le haya asignado a la
     * entidad. Devolver la constante directamente rompía actualizarCancha con
     * UnsupportedOperationException cada vez que el request no traía duraciones (el campo es
     * opcional en CanchaRequest), y aunque la constante fuese mutable el efecto sería peor:
     * Hibernate pasaría a pisar una lista estática compartida por todas las canchas.
     */
    private static List<Integer> resolverDuraciones(List<Integer> pedidas) {
        return (pedidas == null || pedidas.isEmpty())
                ? new ArrayList<>(DURACIONES_POR_DEFECTO)
                : new ArrayList<>(pedidas);
    }

    /**
     * Mismo motivo que resolverDuraciones: Cancha.deportes también es
     * {@code @ElementCollection}, así que la entidad tiene que quedarse con una copia propia
     * y no con la colección del caller. No contempla null a propósito — deportes es
     * {@code @NotEmpty} en CanchaRequest, y devolver un set vacío ante un null taparía esa
     * violación en vez de dejarla fallar.
     */
    private static Set<Deporte> copiaDeportes(Set<Deporte> deportes) {
        return new HashSet<>(deportes);
    }

    private BigDecimal validarMontoSena(BigDecimal montoSena, PlanSuscripcion plan) {
        boolean planLimitado = plan == PlanSuscripcion.FREE;
        if (planLimitado) {
            if (montoSena == null || montoSena.compareTo(SENA_MINIMA_PLAN_LIMITADO) < 0) {
                throw new IllegalArgumentException("El plan actual requiere configurar una seña obligatoria de mínimo $" + SENA_MINIMA_PLAN_LIMITADO);
            }
            return montoSena;
        }
        return (montoSena == null || montoSena.compareTo(BigDecimal.ZERO) < 0) ? BigDecimal.ZERO : montoSena;
    }

    private Integer calcularCanchasNecesarias(List<Long> canchasFisicasIds, Integer cantidadSolicitada) {
        if (canchasFisicasIds == null || canchasFisicasIds.isEmpty()) {
            return null;
        }
        int totalCanchasSeleccionadas = canchasFisicasIds.size();
        if (cantidadSolicitada == null || cantidadSolicitada < 1) {
            return totalCanchasSeleccionadas;
        }
        if (cantidadSolicitada > totalCanchasSeleccionadas) {
            throw new IllegalArgumentException("Las canchas necesarias no pueden superar el total de canchas seleccionadas");
        }
        return cantidadSolicitada;
    }

    private Set<Cancha> resolverCanchasFisicas(Long establecimientoId, List<Long> canchasFisicasIds) {
        if (canchasFisicasIds == null || canchasFisicasIds.isEmpty()) {
            return new LinkedHashSet<>();
        }
        Set<Cancha> canchasFisicas = new LinkedHashSet<>();
        canchaRepository.findAllById(canchasFisicasIds).forEach(canchasFisicas::add);

        if (canchasFisicas.size() != canchasFisicasIds.size()) {
            throw new IllegalArgumentException("Algunas canchas físicas no existen");
        }
        // Sin este chequeo, un pool podía armarse con canchas de OTRO establecimiento (ver
        // M-03 en la auditoría): el cálculo de disponibilidad y el lock pesimista de
        // ReservaService operarían sobre filas de un tenant ajeno.
        boolean todasDelEstablecimiento = canchasFisicas.stream()
                .allMatch(c -> c.getEstablecimiento().getId().equals(establecimientoId));
        if (!todasDelEstablecimiento) {
            throw new IllegalArgumentException("Las canchas físicas deben pertenecer a este establecimiento");
        }
        return canchasFisicas;
    }

    /**
     * PoolCanchaCalculator.hayDisponibilidad valida la capacidad de un GRUPO de físicas
     * (cierre transitivo de pools que se intersectan) sumando las demandas de las reservas
     * que caen dentro de ese grupo. Esa suma solo es exacta si, dentro de un mismo grupo,
     * todos los pools son idénticos entre sí: si dos lógicas se pisan con pools PARCIALMENTE
     * distintos (ej. una usa [F1,F2,F3] y otra [F1,F2]), la suma sobrevende en silencio (ver
     * diagnóstico del bug de disponibilidad entre lógicas superpuestas). Por eso este guard
     * corre solo en altas/ediciones, nunca retroactivamente: no hay que desactivar
     * configuraciones ya existentes, solo impedir que se sume una nueva configuración
     * inconsistente.
     *
     * "Otra lógica que importa" para este chequeo es activas ∪ {inactivas con al menos una
     * reserva futura vigente} — NO sólo activas. isActive dejó de ser un proxy válido de
     * "existe": una lógica desactivada puede seguir ocupando su grupo de físicas mientras
     * tenga reservas futuras (es exactamente el escenario que resuelve
     * CanchaService.validarDesactivacion). Sin esto, se podía crear/editar una lógica activa
     * con un pool PARCIALMENTE superpuesto al de otra que estaba inactivada en ese momento
     * pero con turnos vendidos, y esa inconsistencia sólo se descubría más tarde al intentar
     * reactivarla — dejando al dueño sin forma de recuperar una cancha con reservas ya
     * cobradas sin tocar la lógica nueva primero.
     */
    private void validarConfiguracionDePool(Long establecimientoId, Long canchaIdActual, Set<Cancha> canchasFisicas) {
        if (canchasFisicas.isEmpty()) {
            return;
        }

        boolean incluyeOtraCanchaCombinada = canchasFisicas.stream()
                .anyMatch(c -> c.getCanchasFisicas() != null && !c.getCanchasFisicas().isEmpty());
        if (incluyeOtraCanchaCombinada) {
            throw new IllegalArgumentException(
                    "No podés armar una cancha combinada usando otra cancha combinada como si fuera física: "
                            + "elegí únicamente canchas físicas individuales para el pool.");
        }

        Set<Long> idsPool = canchasFisicas.stream().map(Cancha::getId).collect(Collectors.toSet());

        List<Cancha> candidatas = canchaRepository.findByEstablecimientoId(establecimientoId).stream()
                .filter(c -> c.getCanchasFisicas() != null && !c.getCanchasFisicas().isEmpty())
                .filter(c -> canchaIdActual == null || !c.getId().equals(canchaIdActual))
                .toList();

        List<Cancha> inactivasQuePisanParcialmente = new ArrayList<>();
        for (Cancha otraLogica : candidatas) {
            Set<Long> idsOtroPool = otraLogica.getCanchasFisicas().stream().map(Cancha::getId).collect(Collectors.toSet());
            boolean seIntersectan = idsPool.stream().anyMatch(idsOtroPool::contains);
            boolean sonIdenticos = idsPool.equals(idsOtroPool);
            if (!seIntersectan || sonIdenticos) {
                continue;
            }
            if (Boolean.TRUE.equals(otraLogica.getIsActive())) {
                throw new IllegalArgumentException(
                        "Esta combinación de canchas se pisa parcialmente con \"" + otraLogica.getNombre()
                                + "\", que usa un grupo distinto de canchas físicas. Para combinar canchas que "
                                + "comparten físicas, todas las combinaciones que se solapen tienen que usar "
                                + "exactamente el mismo grupo de canchas físicas.");
            }
            // Inactiva: sólo importa si todavía tiene reservas futuras vigentes (ver javadoc).
            inactivasQuePisanParcialmente.add(otraLogica);
        }

        if (inactivasQuePisanParcialmente.isEmpty()) {
            return;
        }

        Set<Long> idsInactivasCandidatas = inactivasQuePisanParcialmente.stream().map(Cancha::getId).collect(Collectors.toSet());
        Map<Long, LocalDateTime> ultimaReservaFuturaPorCancha = reservaRepository
                .findFuturasPorCanchaIds(idsInactivasCandidatas, LocalDateTime.now()).stream()
                .collect(Collectors.toMap(r -> r.getCancha().getId(), Reserva::getFechaHoraFin, (a, b) -> a.isAfter(b) ? a : b));

        for (Cancha otraLogica : inactivasQuePisanParcialmente) {
            LocalDateTime ultimaReservaFutura = ultimaReservaFuturaPorCancha.get(otraLogica.getId());
            if (ultimaReservaFutura == null) {
                // Sin reservas futuras vigentes: la restricción ya caducó sola, no bloquea.
                continue;
            }
            throw new IllegalArgumentException(
                    "Esta combinación de canchas se pisa parcialmente con \"" + otraLogica.getNombre()
                            + "\", que está desactivada pero tiene reservas vigentes hasta el "
                            + ultimaReservaFutura.toLocalDate() + " a las " + ultimaReservaFutura.toLocalTime()
                            + ". Esta restricción se levanta sola después de esa fecha, sin que haga falta ninguna "
                            + "acción; hasta entonces, para combinar canchas que comparten físicas con \""
                            + otraLogica.getNombre() + "\" hay que usar exactamente el mismo grupo de canchas físicas.");
        }
    }

    private List<Tarifa> mapearTarifas(List<TarifaDto> tarifasDto, Cancha cancha) {
        if (tarifasDto == null || tarifasDto.isEmpty()) {
            return new ArrayList<>();
        }
        return tarifasDto.stream()
                .map(dto -> Tarifa.builder()
                        .cancha(cancha)
                        .diaSemana(dto.diaSemana())
                        .horaInicio(dto.horaInicio())
                        .horaFin(dto.horaFin())
                        .precio(dto.precio())
                        .preciosPorDuracion(normalizarPrecios(dto.preciosPorDuracion()))
                        .build())
                .collect(Collectors.toList());
    }

    private Map<Integer, BigDecimal> normalizarPrecios(Map<Integer, BigDecimal> precios) {
        return precios == null ? new HashMap<>() : new HashMap<>(precios);
    }

    private Establecimiento buscarEstablecimientoPorId(Long establecimientoId) {
        return establecimientoRepository.findById(establecimientoId)
                .orElseThrow(() -> new EntityNotFoundException("Establecimiento no encontrado"));
    }

    /** Mismo motivo que mapToResponse: preciosPorDuracion es @ElementCollection. */
    private TarifaDto mapToTarifaDto(Tarifa tarifa) {
        return new TarifaDto(
                tarifa.getDiaSemana(),
                tarifa.getHoraInicio(),
                tarifa.getHoraFin(),
                tarifa.getPrecio(),
                Map.copyOf(tarifa.getPreciosPorDuracion())
        );
    }

    /**
     * Las colecciones se COPIAN, no se pasan por referencia.
     *
     * Con open-in-view=false la sesión ya está cerrada cuando Jackson serializa
     * la respuesta, así que meter una colección perezosa de Hibernate en el DTO
     * termina en LazyInitializationException al escribir el JSON — fuera del
     * @Transactional, donde el @ControllerAdvice sólo puede devolver un 500
     * genérico. Copiar acá fuerza la inicialización dentro de la transacción y
     * además evita que un proxy de Hibernate se filtre a la capa de transporte.
     *
     * El síntoma era engañoso: tarifas y canchasFisicas no fallaban porque el
     * .stream() de abajo ya las inicializa; sólo reventaban duracionesPermitidas
     * y preciosPorDuracion, que eran las únicas que nadie recorría.
     */
    private CanchaResponse mapToResponse(Cancha cancha) {
        return new CanchaResponse(
                cancha.getId(),
                cancha.getNombre(),
                Set.copyOf(cancha.getDeportes()),
                cancha.getIsActive(),
                cancha.getEstablecimiento().getId(),
                cancha.getPrecioBase(),
                cancha.getMontoSena(),
                List.copyOf(cancha.getDuracionesPermitidas()),
                Map.copyOf(cancha.getPreciosPorDuracion()),
                cancha.getPermiteInicioMediaHora(),
                cancha.getTarifas().stream().map(this::mapToTarifaDto).collect(Collectors.toList()),
                cancha.getCanchasFisicas().stream().map(Cancha::getId).toList(),
                cancha.getCanchasNecesarias()
        );
    }

    private void validarSolapamientoTarifas(List<TarifaDto> tarifasDto) {
        if (tarifasDto == null || tarifasDto.isEmpty()) return;

        for (int i = 0; i < tarifasDto.size(); i++) {
            var tA = tarifasDto.get(i);

            if (!tA.horaInicio().isBefore(tA.horaFin())) {
                throw new IllegalArgumentException("La hora de inicio de la tarifa debe ser anterior a la de fin para el día " + tA.diaSemana());
            }

            for (int j = i + 1; j < tarifasDto.size(); j++) {
                var tB = tarifasDto.get(j);

                if (tA.diaSemana() == tB.diaSemana()) {
                    boolean seSolapan = tA.horaInicio().isBefore(tB.horaFin()) && tA.horaFin().isAfter(tB.horaInicio());
                    if (seSolapan) {
                        throw new IllegalArgumentException(String.format(
                            "Conflictos de configuración: Se detectaron tarifas variables solapadas para el día %s en los rangos (%s - %s) y (%s - %s)",
                            tA.diaSemana(), tA.horaInicio(), tA.horaFin(), tB.horaInicio(), tB.horaFin()
                        ));
                    }
                }
            }
        }
    }

    /**
     * Los precios por duración (a nivel cancha y a nivel de cada tarifa) sólo tienen
     * sentido para una duración que la cancha efectivamente permite reservar, y deben ser
     * positivos como cualquier otro precio del dominio.
     */
    private void validarPreciosPorDuracion(Map<Integer, BigDecimal> preciosCancha, List<TarifaDto> tarifasDto, List<Integer> duracionesPermitidas) {
        validarPreciosPorDuracion(preciosCancha, duracionesPermitidas, "de la cancha");

        if (tarifasDto == null) return;
        for (TarifaDto tarifa : tarifasDto) {
            validarPreciosPorDuracion(tarifa.preciosPorDuracion(), duracionesPermitidas, "de la tarifa del día " + tarifa.diaSemana());
        }
    }

    private void validarPreciosPorDuracion(Map<Integer, BigDecimal> precios, List<Integer> duracionesPermitidas, String contexto) {
        if (precios == null || precios.isEmpty()) return;

        for (Map.Entry<Integer, BigDecimal> entry : precios.entrySet()) {
            if (!duracionesPermitidas.contains(entry.getKey())) {
                throw new IllegalArgumentException(
                        "El precio por duración " + contexto + " incluye " + entry.getKey()
                                + " minutos, que no está entre las duraciones permitidas " + duracionesPermitidas);
            }
            if (entry.getValue() == null || entry.getValue().compareTo(BigDecimal.ZERO) <= 0) {
                throw new IllegalArgumentException(
                        "El precio por duración " + contexto + " para " + entry.getKey() + " minutos debe ser mayor a 0");
            }
        }
    }
}
