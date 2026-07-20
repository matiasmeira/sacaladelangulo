package com.matiasmeira.sacaladelangulo.establecimiento.service;

import com.matiasmeira.sacaladelangulo.auth.model.PlanSuscripcion;
import com.matiasmeira.sacaladelangulo.auth.model.Role;
import com.matiasmeira.sacaladelangulo.auth.model.Usuario;
import com.matiasmeira.sacaladelangulo.auth.repository.UsuarioRepository;
import com.matiasmeira.sacaladelangulo.core.exception.EntityNotFoundException;
import com.matiasmeira.sacaladelangulo.establecimiento.dto.CanchaRequest;
import com.matiasmeira.sacaladelangulo.establecimiento.dto.CanchaResponse;
import com.matiasmeira.sacaladelangulo.establecimiento.dto.TarifaDto;
import com.matiasmeira.sacaladelangulo.establecimiento.model.Cancha;
import com.matiasmeira.sacaladelangulo.establecimiento.model.Establecimiento;
import com.matiasmeira.sacaladelangulo.establecimiento.model.Tarifa;
import com.matiasmeira.sacaladelangulo.establecimiento.repository.CanchaRepository;
import com.matiasmeira.sacaladelangulo.establecimiento.repository.EstablecimientoRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
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
    private final UsuarioRepository usuarioRepository;

    public CanchaResponse crearCancha(Long establecimientoId, CanchaRequest request, String email) {
        validarSolapamientoTarifas(request.tarifas());

        Establecimiento establecimiento = buscarEstablecimientoPorId(establecimientoId);
        Usuario usuarioAutenticado = validarPropietario(establecimiento, email);

        BigDecimal montoSena = validarMontoSena(request.montoSena(), usuarioAutenticado.getPlanSuscripcion());
        Integer canchasNecesarias = calcularCanchasNecesarias(request.canchasFisicasIds(), request.cantidadCanchasNecesarias());
        List<Integer> duracionesPermitidas = request.duracionesPermitidas() == null || request.duracionesPermitidas().isEmpty()
                ? DURACIONES_POR_DEFECTO
                : request.duracionesPermitidas();

        Cancha cancha = Cancha.builder()
                .nombre(request.nombre())
                .deportes(request.deportes())
                .capacidad(request.capacidad())
                .precioBase(request.precioBase())
                .montoSena(montoSena)
                .duracionesPermitidas(duracionesPermitidas)
                .permiteInicioMediaHora(request.permiteInicioMediaHora() != null ? request.permiteInicioMediaHora() : true)
                .isActive(true)
                .establecimiento(establecimiento)
                .canchasNecesarias(canchasNecesarias)
                .build();

        cancha.setCanchasFisicas(resolverCanchasFisicas(request.canchasFisicasIds()));
        cancha.setTarifas(mapearTarifas(request.tarifas(), cancha));

        Cancha canchaGuardada = canchaRepository.save(cancha);

        return mapToResponse(canchaGuardada);
    }

    @Transactional(readOnly = true)
    public List<CanchaResponse> obtenerCanchasPorEstablecimiento(Long establecimientoId, String email) {
        Establecimiento establecimiento = buscarEstablecimientoPorId(establecimientoId);
        validarPropietario(establecimiento, email);

        return canchaRepository.findByEstablecimientoIdAndIsActiveTrue(establecimientoId).stream()
                .map(this::mapToResponse)
                .collect(Collectors.toList());
    }

    public CanchaResponse actualizarCancha(Long establecimientoId, Long canchaId, CanchaRequest request, String email) {
        validarSolapamientoTarifas(request.tarifas());

        Establecimiento establecimiento = buscarEstablecimientoPorId(establecimientoId);
        Usuario usuarioAutenticado = validarPropietario(establecimiento, email);

        Cancha cancha = canchaRepository.findById(canchaId)
                .orElseThrow(() -> new EntityNotFoundException("Cancha no encontrada"));

        if (!cancha.getEstablecimiento().getId().equals(establecimientoId)) {
            throw new IllegalArgumentException("La cancha no pertenece a este establecimiento");
        }

        BigDecimal montoSena = validarMontoSena(request.montoSena(), usuarioAutenticado.getPlanSuscripcion());

        cancha.setNombre(request.nombre());
        cancha.setDeportes(request.deportes());
        cancha.setCapacidad(request.capacidad());
        cancha.setPrecioBase(request.precioBase());
        cancha.setMontoSena(montoSena);
        cancha.setDuracionesPermitidas(request.duracionesPermitidas() == null || request.duracionesPermitidas().isEmpty()
                ? DURACIONES_POR_DEFECTO
                : request.duracionesPermitidas());
        cancha.setPermiteInicioMediaHora(request.permiteInicioMediaHora() != null ? request.permiteInicioMediaHora() : true);
        cancha.setCanchasNecesarias(calcularCanchasNecesarias(request.canchasFisicasIds(), request.cantidadCanchasNecesarias()));
        cancha.setCanchasFisicas(resolverCanchasFisicas(request.canchasFisicasIds()));

        if (request.tarifas() != null) {
            cancha.getTarifas().clear();
            cancha.getTarifas().addAll(mapearTarifas(request.tarifas(), cancha));
        }

        Cancha canchaGuardada = canchaRepository.save(cancha);
        return mapToResponse(canchaGuardada);
    }

    /**
     * Desactiva una cancha (baja lógica, isActive=false): sin este método no había forma
     * de dar de baja una cancha, solo de crearla o editarla (ver B19 en la auditoría).
     * Misma validación de ownership que actualizarCancha.
     */
    public void desactivarCancha(Long establecimientoId, Long canchaId, String email) {
        Establecimiento establecimiento = buscarEstablecimientoPorId(establecimientoId);
        validarPropietario(establecimiento, email);

        Cancha cancha = canchaRepository.findById(canchaId)
                .orElseThrow(() -> new EntityNotFoundException("Cancha no encontrada"));

        if (!cancha.getEstablecimiento().getId().equals(establecimientoId)) {
            throw new IllegalArgumentException("La cancha no pertenece a este establecimiento");
        }

        cancha.setIsActive(false);
        canchaRepository.save(cancha);
    }

    /**
     * Valida que el usuario autenticado sea el dueño del establecimiento o un administrador.
     */
    private Usuario validarPropietario(Establecimiento establecimiento, String email) {
        Usuario usuarioAutenticado = usuarioRepository.findByEmail(email)
                .orElseThrow(() -> new EntityNotFoundException("Usuario no encontrado"));

        if (usuarioAutenticado.getRol() != Role.ADMIN && !establecimiento.getDueno().getId().equals(usuarioAutenticado.getId())) {
            throw new AccessDeniedException("No autorizado en este establecimiento");
        }
        return usuarioAutenticado;
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

    private List<Cancha> resolverCanchasFisicas(List<Long> canchasFisicasIds) {
        if (canchasFisicasIds == null || canchasFisicasIds.isEmpty()) {
            return new ArrayList<>();
        }
        List<Cancha> canchasFisicas = new ArrayList<>();
        canchaRepository.findAllById(canchasFisicasIds).forEach(canchasFisicas::add);

        if (canchasFisicas.size() != canchasFisicasIds.size()) {
            throw new IllegalArgumentException("Algunas canchas físicas no existen");
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
                        .build())
                .collect(Collectors.toList());
    }

    private Establecimiento buscarEstablecimientoPorId(Long establecimientoId) {
        return establecimientoRepository.findById(establecimientoId)
                .orElseThrow(() -> new EntityNotFoundException("Establecimiento no encontrado"));
    }

    private TarifaDto mapToTarifaDto(Tarifa tarifa) {
        return new TarifaDto(
                tarifa.getDiaSemana(),
                tarifa.getHoraInicio(),
                tarifa.getHoraFin(),
                tarifa.getPrecio()
        );
    }

    private CanchaResponse mapToResponse(Cancha cancha) {
        return new CanchaResponse(
                cancha.getId(),
                cancha.getNombre(),
                cancha.getDeportes(),
                cancha.getCapacidad(),
                cancha.getIsActive(),
                cancha.getEstablecimiento().getId(),
                cancha.getPrecioBase(),
                cancha.getMontoSena(),
                cancha.getDuracionesPermitidas(),
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
}
