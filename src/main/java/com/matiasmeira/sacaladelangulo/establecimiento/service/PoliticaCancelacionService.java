package com.matiasmeira.sacaladelangulo.establecimiento.service;

import com.matiasmeira.sacaladelangulo.auth.model.Usuario;
import com.matiasmeira.sacaladelangulo.core.exception.EntityNotFoundException;
import com.matiasmeira.sacaladelangulo.empleado.model.AccionAuditoria;
import com.matiasmeira.sacaladelangulo.empleado.service.AutorizacionEmpleadoService;
import com.matiasmeira.sacaladelangulo.empleado.service.RegistroAuditoriaService;
import com.matiasmeira.sacaladelangulo.establecimiento.dto.ActualizarPoliticaCancelacionRequest;
import com.matiasmeira.sacaladelangulo.establecimiento.dto.PoliticaCancelacionResponse;
import com.matiasmeira.sacaladelangulo.establecimiento.model.Establecimiento;
import com.matiasmeira.sacaladelangulo.establecimiento.repository.EstablecimientoRepository;
import com.matiasmeira.sacaladelangulo.reserva.repository.ReservaRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * Servicio de negocio para la política de cancelación de un establecimiento. Separado de
 * EstablecimientoService (que gestiona el alta/edición del perfil) porque es un sub-recurso
 * con endpoint, DTOs y acción de auditoría propios -- mismo criterio que
 * FotoEstablecimientoService/CanchaService.
 */
@Service
@RequiredArgsConstructor
@Transactional
public class PoliticaCancelacionService {

    private final EstablecimientoRepository establecimientoRepository;
    private final AutorizacionEmpleadoService autorizacionEmpleadoService;
    private final RegistroAuditoriaService registroAuditoriaService;
    private final ReservaRepository reservaRepository;

    @Transactional(readOnly = true)
    public PoliticaCancelacionResponse obtenerPoliticaCancelacion(Long establecimientoId, String email) {
        Establecimiento establecimiento = buscarEstablecimientoPorId(establecimientoId);
        autorizacionEmpleadoService.validarPropietarioOAdmin(establecimiento, email);

        return new PoliticaCancelacionResponse(
                establecimiento.getHorasCancelacionAntesPartido(),
                establecimiento.getMinutosGraciaCancelacion(),
                null
        );
    }

    public PoliticaCancelacionResponse actualizarPoliticaCancelacion(Long establecimientoId, ActualizarPoliticaCancelacionRequest request, String email) {
        if (request.horasCancelacionAntesPartido() == null && request.minutosGraciaCancelacion() == null) {
            throw new IllegalArgumentException("Tenés que indicar al menos un valor para actualizar la política de cancelación");
        }

        Establecimiento establecimiento = buscarEstablecimientoPorId(establecimientoId);
        Usuario usuarioAutenticado = autorizacionEmpleadoService.validarPropietarioOAdmin(establecimiento, email);

        Integer horasAnteriores = establecimiento.getHorasCancelacionAntesPartido();
        Integer minutosAnteriores = establecimiento.getMinutosGraciaCancelacion();

        if (request.horasCancelacionAntesPartido() != null) {
            establecimiento.setHorasCancelacionAntesPartido(request.horasCancelacionAntesPartido());
        }
        if (request.minutosGraciaCancelacion() != null) {
            establecimiento.setMinutosGraciaCancelacion(request.minutosGraciaCancelacion());
        }

        Establecimiento establecimientoActualizado = establecimientoRepository.save(establecimiento);

        int reservasFuturasAfectadas = (int) reservaRepository.countReservasFuturasActivas(establecimientoId, LocalDateTime.now());

        registroAuditoriaService.registrarSobreEstablecimiento(usuarioAutenticado, establecimientoActualizado,
                AccionAuditoria.ACTUALIZAR_POLITICA_CANCELACION, establecimientoActualizado.getId(),
                String.format("Política de cancelación actualizada: horasCancelacionAntesPartido %d -> %d, minutosGraciaCancelacion %d -> %d",
                        horasAnteriores, establecimientoActualizado.getHorasCancelacionAntesPartido(),
                        minutosAnteriores, establecimientoActualizado.getMinutosGraciaCancelacion()));

        return new PoliticaCancelacionResponse(
                establecimientoActualizado.getHorasCancelacionAntesPartido(),
                establecimientoActualizado.getMinutosGraciaCancelacion(),
                reservasFuturasAfectadas
        );
    }

    private Establecimiento buscarEstablecimientoPorId(Long id) {
        return establecimientoRepository.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("Establecimiento no encontrado"));
    }
}
