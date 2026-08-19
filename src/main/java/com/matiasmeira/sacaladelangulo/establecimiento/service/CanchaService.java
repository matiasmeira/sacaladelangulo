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
import com.matiasmeira.sacaladelangulo.establecimiento.model.Establecimiento;
import com.matiasmeira.sacaladelangulo.establecimiento.model.Tarifa;
import com.matiasmeira.sacaladelangulo.establecimiento.repository.CanchaRepository;
import com.matiasmeira.sacaladelangulo.establecimiento.repository.EstablecimientoRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
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

    public CanchaResponse crearCancha(Long establecimientoId, CanchaRequest request, String email) {
        validarSolapamientoTarifas(request.tarifas());

        Establecimiento establecimiento = buscarEstablecimientoPorId(establecimientoId);
        Usuario usuarioAutenticado = autorizacionEmpleadoService.validarPropietarioOAdmin(establecimiento, email);

        BigDecimal montoSena = validarMontoSena(request.montoSena(), usuarioAutenticado.getPlanSuscripcion());
        Integer canchasNecesarias = calcularCanchasNecesarias(request.canchasFisicasIds(), request.cantidadCanchasNecesarias());
        List<Integer> duracionesPermitidas = request.duracionesPermitidas() == null || request.duracionesPermitidas().isEmpty()
                ? DURACIONES_POR_DEFECTO
                : request.duracionesPermitidas();

        validarPreciosPorDuracion(request.preciosPorDuracion(), request.tarifas(), duracionesPermitidas);

        Cancha cancha = Cancha.builder()
                .nombre(request.nombre())
                .deportes(request.deportes())
                .capacidad(request.capacidad())
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
        cancha.setTarifas(mapearTarifas(request.tarifas(), cancha));

        Cancha canchaGuardada = canchaRepository.save(cancha);

        registroAuditoriaService.registrarSobreEstablecimiento(usuarioAutenticado, establecimiento,
                AccionAuditoria.CREAR_CANCHA, canchaGuardada.getId(),
                "Cancha creada: " + canchaGuardada.getNombre() + ", precio base " + canchaGuardada.getPrecioBase());

        return mapToResponse(canchaGuardada);
    }

    @Transactional(readOnly = true)
    public List<CanchaResponse> obtenerCanchasPorEstablecimiento(Long establecimientoId, String email) {
        Establecimiento establecimiento = buscarEstablecimientoPorId(establecimientoId);
        // Mismo conjunto que la agenda: se dibuja POR cancha, así que sin este listado
        // no se renderiza aunque el empleado sólo vaya a cobrar. Alta, edición y baja
        // de canchas siguen siendo del dueño.
        autorizacionEmpleadoService.validarLectura(establecimiento, email,
                AutorizacionEmpleadoService.PERMISOS_OPERATIVOS_DE_RESERVA);

        return canchaRepository.findByEstablecimientoIdAndIsActiveTrue(establecimientoId).stream()
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
        List<Integer> duracionesPermitidas = request.duracionesPermitidas() == null || request.duracionesPermitidas().isEmpty()
                ? DURACIONES_POR_DEFECTO
                : request.duracionesPermitidas();

        validarPreciosPorDuracion(request.preciosPorDuracion(), request.tarifas(), duracionesPermitidas);

        cancha.setNombre(request.nombre());
        cancha.setDeportes(request.deportes());
        cancha.setCapacidad(request.capacidad());
        cancha.setPrecioBase(request.precioBase());
        cancha.setMontoSena(montoSena);
        cancha.setDuracionesPermitidas(duracionesPermitidas);
        cancha.setPreciosPorDuracion(normalizarPrecios(request.preciosPorDuracion()));
        cancha.setPermiteInicioMediaHora(request.permiteInicioMediaHora() != null ? request.permiteInicioMediaHora() : true);
        cancha.setCanchasNecesarias(calcularCanchasNecesarias(request.canchasFisicasIds(), request.cantidadCanchasNecesarias()));
        cancha.setCanchasFisicas(resolverCanchasFisicas(establecimientoId, request.canchasFisicasIds()));

        if (request.tarifas() != null) {
            cancha.getTarifas().clear();
            cancha.getTarifas().addAll(mapearTarifas(request.tarifas(), cancha));
        }

        Cancha canchaGuardada = canchaRepository.save(cancha);

        registroAuditoriaService.registrarSobreEstablecimiento(usuarioAutenticado, establecimiento,
                AccionAuditoria.ACTUALIZAR_CANCHA, canchaGuardada.getId(),
                "Cancha actualizada: " + canchaGuardada.getNombre() + ", precio base " + canchaGuardada.getPrecioBase());

        return mapToResponse(canchaGuardada);
    }

    /**
     * Desactiva una cancha (baja lógica, isActive=false): sin este método no había forma
     * de dar de baja una cancha, solo de crearla o editarla (ver B19 en la auditoría).
     * Misma validación de ownership que actualizarCancha.
     */
    public void desactivarCancha(Long establecimientoId, Long canchaId, String email) {
        Establecimiento establecimiento = buscarEstablecimientoPorId(establecimientoId);
        autorizacionEmpleadoService.validarPropietarioOAdmin(establecimiento, email);

        Cancha cancha = canchaRepository.findById(canchaId)
                .orElseThrow(() -> new EntityNotFoundException("Cancha no encontrada"));

        if (!cancha.getEstablecimiento().getId().equals(establecimientoId)) {
            throw new IllegalArgumentException("La cancha no pertenece a este establecimiento");
        }

        cancha.setIsActive(false);
        canchaRepository.save(cancha);
    }


    /**
     * Los planes limitados (TRIAL/FREE) exigen una seña mínima obligatoria; el resto de
     * los planes permite no cobrar seña (nunca un monto negativo).
     */
    private BigDecimal validarMontoSena(BigDecimal montoSena, PlanSuscripcion plan) {
        boolean planLimitado = plan == PlanSuscripcion.TRIAL || plan == PlanSuscripcion.FREE;
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

    private List<Cancha> resolverCanchasFisicas(Long establecimientoId, List<Long> canchasFisicasIds) {
        if (canchasFisicasIds == null || canchasFisicasIds.isEmpty()) {
            return new ArrayList<>();
        }
        List<Cancha> canchasFisicas = new ArrayList<>();
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
                cancha.getCapacidad(),
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
