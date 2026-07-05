package com.matiasmeira.sacaladelangulo.establecimiento.service;

import com.matiasmeira.sacaladelangulo.establecimiento.dto.BloqueoCanchaRequest;
import com.matiasmeira.sacaladelangulo.establecimiento.dto.BloqueoCanchaResponse;
import com.matiasmeira.sacaladelangulo.establecimiento.model.BloqueoCancha;
import com.matiasmeira.sacaladelangulo.establecimiento.model.Cancha;
import com.matiasmeira.sacaladelangulo.establecimiento.repository.BloqueoCanchaRepository;
import com.matiasmeira.sacaladelangulo.establecimiento.repository.CanchaRepository;
import com.matiasmeira.sacaladelangulo.reserva.model.Reserva;
import com.matiasmeira.sacaladelangulo.reserva.model.EstadoReserva;
import com.matiasmeira.sacaladelangulo.reserva.repository.ReservaRepository;
import com.matiasmeira.sacaladelangulo.reserva.dto.ReservaMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class BloqueoCanchaService {

    private final BloqueoCanchaRepository bloqueoCanchaRepository;
    private final CanchaRepository canchaRepository;
    private final ReservaRepository reservaRepository;
    private final ReservaMapper reservaMapper;

    @Transactional
    public BloqueoCanchaResponse crearBloqueo(Long canchaId, BloqueoCanchaRequest request) {
        if (request.fechaInicio().isAfter(request.fechaFin())) {
            throw new IllegalArgumentException("La fecha de inicio no puede ser posterior a la de fin");
        }

        Cancha cancha = canchaRepository.findById(canchaId)
                .orElseThrow(() -> new IllegalArgumentException("Cancha no encontrada"));

        BloqueoCancha bloqueo = BloqueoCancha.builder()
                .cancha(cancha)
                .fechaInicio(request.fechaInicio())
                .fechaFin(request.fechaFin())
                .motivo(request.motivo())
                .build();

        bloqueoCanchaRepository.save(bloqueo);

        List<Reserva> reservasAfectadas = reservaRepository.findOverlappingByCanchaId(
                canchaId,
                request.fechaInicio(),
                request.fechaFin()
        );

        log.info("Bloqueo creado para cancha {}. Reservas afectadas: {}", canchaId, reservasAfectadas.size());

        return new BloqueoCanchaResponse(
                bloqueo.getId(),
                cancha.getId(),
                bloqueo.getFechaInicio(),
                bloqueo.getFechaFin(),
                bloqueo.getMotivo(),
                reservasAfectadas.stream().map(reservaMapper::mapToResponse).toList()
        );
    }
}
