package com.matiasmeira.sacaladelangulo.establecimiento.service;

import com.matiasmeira.sacaladelangulo.auth.model.PlanSuscripcion;
import com.matiasmeira.sacaladelangulo.auth.model.Role;
import com.matiasmeira.sacaladelangulo.auth.model.Usuario;
import com.matiasmeira.sacaladelangulo.auth.repository.UsuarioRepository;
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

    private final CanchaRepository canchaRepository;
    private final EstablecimientoRepository establecimientoRepository;
    private final UsuarioRepository usuarioRepository;

    public CanchaResponse crearCancha(Long establecimientoId, CanchaRequest request, String email) {
        Establecimiento establecimiento = establecimientoRepository.findById(establecimientoId)
                .orElseThrow(() -> new IllegalArgumentException("Establecimiento no encontrado"));

        Usuario usuarioAutenticado = usuarioRepository.findByEmail(email)
                .orElseThrow(() -> new IllegalArgumentException("Usuario no encontrado"));

        if (usuarioAutenticado.getRol() != Role.ADMIN && !establecimiento.getDueno().getId().equals(usuarioAutenticado.getId())) {
            throw new AccessDeniedException("No autorizado en este establecimiento");
        }

        BigDecimal montoSena = request.montoSena();
        if (usuarioAutenticado.getPlanSuscripcion() == PlanSuscripcion.TRIAL ||
                usuarioAutenticado.getPlanSuscripcion() == PlanSuscripcion.FREE) {
            if (montoSena == null || montoSena.compareTo(BigDecimal.valueOf(500)) < 0) {
                throw new IllegalArgumentException("El plan actual requiere configurar una seña obligatoria de mínimo $500");
            }
        } else {
            if (montoSena == null || montoSena.compareTo(BigDecimal.ZERO) < 0) {
                montoSena = BigDecimal.ZERO;
            }
        }

        // Validar y procesar el campo canchasNecesarias
        Integer canchasNecesarias = null;
        if (request.canchasFisicasIds() != null && !request.canchasFisicasIds().isEmpty()) {
            Integer cantidadSolicitada = request.cantidadCanchasNecesarias();
            int totalCanchasSeleccionadas = request.canchasFisicasIds().size();

            if (cantidadSolicitada == null || cantidadSolicitada < 1) {
                canchasNecesarias = totalCanchasSeleccionadas;
            } else if (cantidadSolicitada > totalCanchasSeleccionadas) {
                throw new IllegalArgumentException("Las canchas necesarias no pueden superar el total de canchas seleccionadas");
            } else {
                canchasNecesarias = cantidadSolicitada;
            }
        }

        Cancha cancha = Cancha.builder()
                .nombre(request.nombre())
                .deporte(request.deporte())
                .capacidad(request.capacidad())
                .precioBase(request.precioBase())
                .montoSena(montoSena)
                .isActive(true)
                .establecimiento(establecimiento)
                .canchasNecesarias(canchasNecesarias)
                .build();

        if (request.canchasFisicasIds() != null && !request.canchasFisicasIds().isEmpty()) {
            List<Cancha> canchasFisicas = new ArrayList<>();
            canchaRepository.findAllById(request.canchasFisicasIds()).forEach(canchasFisicas::add);

            if (canchasFisicas.size() != request.canchasFisicasIds().size()) {
                throw new IllegalArgumentException("Algunas canchas físicas no existen");
            }

            cancha.setCanchasFisicas(canchasFisicas);
        }

        if (request.tarifas() != null && !request.tarifas().isEmpty()) {
            List<Tarifa> tarifas = request.tarifas().stream()
                    .map(dto -> Tarifa.builder()
                            .cancha(cancha)
                            .diaSemana(dto.diaSemana())
                            .horaInicio(dto.horaInicio())
                            .horaFin(dto.horaFin())
                            .precio(dto.precio())
                            .build())
                    .collect(Collectors.toList());
            cancha.setTarifas(tarifas);
        }

        Cancha canchaGuardada = canchaRepository.save(cancha);

        return mapToResponse(canchaGuardada);
    }

    public List<CanchaResponse> obtenerCanchasPorEstablecimiento(Long establecimientoId, String email) {
        Establecimiento establecimiento = establecimientoRepository.findById(establecimientoId)
                .orElseThrow(() -> new IllegalArgumentException("Establecimiento no encontrado"));

        Usuario usuarioAutenticado = usuarioRepository.findByEmail(email)
                .orElseThrow(() -> new IllegalArgumentException("Usuario no encontrado"));

        if (usuarioAutenticado.getRol() != Role.ADMIN && !establecimiento.getDueno().getId().equals(usuarioAutenticado.getId())) {
            throw new AccessDeniedException("No autorizado en este establecimiento");
        }

        return canchaRepository.findByEstablecimientoIdAndIsActiveTrue(establecimientoId).stream()
                .map(this::mapToResponse)
                .collect(Collectors.toList());
    }

    public CanchaResponse actualizarCancha(Long establecimientoId, Long canchaId, CanchaRequest request, String email) {
        Establecimiento establecimiento = establecimientoRepository.findById(establecimientoId)
                .orElseThrow(() -> new IllegalArgumentException("Establecimiento no encontrado"));

        Usuario usuarioAutenticado = usuarioRepository.findByEmail(email)
                .orElseThrow(() -> new IllegalArgumentException("Usuario no encontrado"));

        if (usuarioAutenticado.getRol() != Role.ADMIN && !establecimiento.getDueno().getId().equals(usuarioAutenticado.getId())) {
            throw new AccessDeniedException("No autorizado en este establecimiento");
        }

        Cancha cancha = canchaRepository.findById(canchaId)
                .orElseThrow(() -> new IllegalArgumentException("Cancha no encontrada"));

        if (!cancha.getEstablecimiento().getId().equals(establecimientoId)) {
            throw new IllegalArgumentException("La cancha no pertenece a este establecimiento");
        }

        BigDecimal montoSena = request.montoSena();
        if (usuarioAutenticado.getPlanSuscripcion() == PlanSuscripcion.TRIAL ||
                usuarioAutenticado.getPlanSuscripcion() == PlanSuscripcion.FREE) {
            if (montoSena == null || montoSena.compareTo(BigDecimal.valueOf(500)) < 0) {
                throw new IllegalArgumentException("El plan actual requiere configurar una seña obligatoria de mínimo $500");
            }
        } else {
            if (montoSena == null || montoSena.compareTo(BigDecimal.ZERO) < 0) {
                montoSena = BigDecimal.ZERO;
            }
        }

        cancha.setNombre(request.nombre());
        cancha.setDeporte(request.deporte());
        cancha.setCapacidad(request.capacidad());
        cancha.setPrecioBase(request.precioBase());
        cancha.setMontoSena(montoSena);

        Integer canchasNecesarias = null;
        if (request.canchasFisicasIds() != null && !request.canchasFisicasIds().isEmpty()) {
            int totalCanchasSeleccionadas = request.canchasFisicasIds().size();
            Integer cantidadSolicitada = request.cantidadCanchasNecesarias();

            if (cantidadSolicitada == null || cantidadSolicitada < 1) {
                canchasNecesarias = totalCanchasSeleccionadas;
            } else if (cantidadSolicitada > totalCanchasSeleccionadas) {
                throw new IllegalArgumentException("Las canchas necesarias no pueden superar el total de canchas seleccionadas");
            } else {
                canchasNecesarias = cantidadSolicitada;
            }

            List<Cancha> canchasFisicas = new ArrayList<>();
            canchaRepository.findAllById(request.canchasFisicasIds()).forEach(canchasFisicas::add);

            if (canchasFisicas.size() != request.canchasFisicasIds().size()) {
                throw new IllegalArgumentException("Algunas canchas físicas no existen");
            }

            cancha.setCanchasFisicas(canchasFisicas);
        } else {
            cancha.setCanchasFisicas(new ArrayList<>());
        }

        cancha.setCanchasNecesarias(canchasNecesarias);

        if (request.tarifas() != null) {
            cancha.getTarifas().clear();
            cancha.setTarifas(request.tarifas().stream()
                    .map(dto -> Tarifa.builder()
                            .cancha(cancha)
                            .diaSemana(dto.diaSemana())
                            .horaInicio(dto.horaInicio())
                            .horaFin(dto.horaFin())
                            .precio(dto.precio())
                            .build())
                    .collect(Collectors.toList()));
        }

        Cancha canchaGuardada = canchaRepository.save(cancha);
        return mapToResponse(canchaGuardada);
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
                cancha.getDeporte(),
                cancha.getCapacidad(),
                cancha.getIsActive(),
                cancha.getEstablecimiento().getId(),
                cancha.getPrecioBase(),
                cancha.getMontoSena(),
                cancha.getTarifas().stream().map(this::mapToTarifaDto).collect(Collectors.toList()),
                cancha.getCanchasFisicas().stream().map(Cancha::getId).toList(),
                cancha.getCanchasNecesarias()
        );
    }
}
