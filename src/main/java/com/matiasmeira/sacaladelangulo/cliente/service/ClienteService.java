package com.matiasmeira.sacaladelangulo.cliente.service;

import com.matiasmeira.sacaladelangulo.auth.model.Usuario;
import com.matiasmeira.sacaladelangulo.auth.repository.UsuarioRepository;
import com.matiasmeira.sacaladelangulo.cliente.dto.ClienteDetalleResponse;
import com.matiasmeira.sacaladelangulo.cliente.dto.ClienteResponse;
import com.matiasmeira.sacaladelangulo.core.exception.EntityNotFoundException;
import com.matiasmeira.sacaladelangulo.empleado.service.AutorizacionEmpleadoService;
import com.matiasmeira.sacaladelangulo.establecimiento.model.BloqueoJugador;
import com.matiasmeira.sacaladelangulo.establecimiento.model.Establecimiento;
import com.matiasmeira.sacaladelangulo.establecimiento.repository.BloqueoJugadorRepository;
import com.matiasmeira.sacaladelangulo.establecimiento.repository.EstablecimientoRepository;
import com.matiasmeira.sacaladelangulo.reserva.dto.ReservaMapper;
import com.matiasmeira.sacaladelangulo.reserva.dto.ReservaResponse;
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
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class ClienteService {

    private static final int TAMANIO_PAGINA_MAXIMO = 100;

    private final ReservaRepository reservaRepository;
    private final UsuarioRepository usuarioRepository;
    private final BloqueoJugadorRepository bloqueoJugadorRepository;
    private final EstablecimientoRepository establecimientoRepository;
    private final AutorizacionEmpleadoService autorizacionEmpleadoService;
    private final ReservaMapper reservaMapper;

    @Transactional(readOnly = true)
    public Page<ClienteResponse> listarClientes(Long establecimientoId, String buscar, Boolean soloBloqueados,
                                                  Pageable pageable, String email) {
        validarAcceso(establecimientoId, email);
        Pageable pageableCapado = capPageSize(pageable);

        List<Long> jugadorIds = reservaRepository.jugadorIdsDelEstablecimiento(establecimientoId);
        if (jugadorIds.isEmpty()) {
            return Page.empty(pageableCapado);
        }

        Map<Long, Object[]> historico = reservaRepository.historicoAgregadoPorJugador(establecimientoId).stream()
                .collect(Collectors.toMap(fila -> (Long) fila[0], fila -> fila));
        Map<Long, Long> ausencias = reservaRepository.countAusenciasPorJugador(establecimientoId).stream()
                .collect(Collectors.toMap(fila -> (Long) fila[0], fila -> ((Number) fila[1]).longValue()));
        Map<Long, Usuario> usuarios = usuarioRepository.findAllById(jugadorIds).stream()
                .collect(Collectors.toMap(Usuario::getId, Function.identity()));
        Map<Long, BloqueoJugador> bloqueados = bloqueoJugadorRepository
                .findByEstablecimientoIdOrderByFechaBloqueoDesc(establecimientoId).stream()
                .collect(Collectors.toMap(b -> b.getJugador().getId(), Function.identity()));

        List<ClienteResponse> clientes = jugadorIds.stream()
                .map(id -> construirClienteResponse(id, usuarios.get(id), historico.get(id),
                        ausencias.getOrDefault(id, 0L), bloqueados.containsKey(id)))
                .filter(cliente -> coincideBusqueda(cliente, buscar))
                .filter(cliente -> !Boolean.TRUE.equals(soloBloqueados) || Boolean.TRUE.equals(cliente.bloqueado()))
                .sorted(comparador(pageableCapado.getSort()))
                .toList();

        return paginar(clientes, pageableCapado);
    }

    private boolean coincideBusqueda(ClienteResponse cliente, String buscar) {
        if (buscar == null || buscar.isBlank()) {
            return true;
        }
        String termino = buscar.toLowerCase();
        return contiene(cliente.nombre(), termino) || contiene(cliente.telefono(), termino) || contiene(cliente.email(), termino);
    }

    private boolean contiene(String valor, String terminoEnMinuscula) {
        return valor != null && valor.toLowerCase().contains(terminoEnMinuscula);
    }

    private Comparator<ClienteResponse> comparador(Sort sort) {
        if (sort.isUnsorted()) {
            return Comparator.comparing(ClienteResponse::nombre, String.CASE_INSENSITIVE_ORDER);
        }
        Comparator<ClienteResponse> resultado = null;
        for (Sort.Order orden : sort) {
            Comparator<ClienteResponse> comparadorCampo = comparadorDeCampo(orden.getProperty());
            if (orden.isDescending()) {
                comparadorCampo = comparadorCampo.reversed();
            }
            resultado = resultado == null ? comparadorCampo : resultado.thenComparing(comparadorCampo);
        }
        return resultado;
    }

    private Comparator<ClienteResponse> comparadorDeCampo(String propiedad) {
        return switch (propiedad) {
            case "nombre" -> Comparator.comparing(ClienteResponse::nombre, String.CASE_INSENSITIVE_ORDER);
            case "ultimaReserva" -> Comparator.comparing(ClienteResponse::ultimaReserva, Comparator.nullsLast(Comparator.naturalOrder()));
            case "reservasTotales" -> Comparator.comparingLong(ClienteResponse::reservasTotales);
            case "ausencias" -> Comparator.comparingLong(ClienteResponse::ausencias);
            default -> throw new IllegalArgumentException("Propiedad de orden no soportada: " + propiedad);
        };
    }

    private Page<ClienteResponse> paginar(List<ClienteResponse> clientes, Pageable pageable) {
        int total = clientes.size();
        int desde = Math.min((int) pageable.getOffset(), total);
        int hasta = Math.min(desde + pageable.getPageSize(), total);
        return new PageImpl<>(clientes.subList(desde, hasta), pageable, total);
    }

    @Transactional(readOnly = true)
    public ClienteDetalleResponse obtenerDetalle(Long establecimientoId, Long jugadorId, String email) {
        validarAcceso(establecimientoId, email);
        if (!reservaRepository.existsByJugador_IdAndCancha_Establecimiento_Id(jugadorId, establecimientoId)) {
            throw new EntityNotFoundException("El jugador no tiene reservas en este establecimiento");
        }
        Usuario usuario = usuarioRepository.findById(jugadorId)
                .orElseThrow(() -> new EntityNotFoundException("Usuario no encontrado"));

        Object[] historico = reservaRepository.historicoAgregadoDeJugador(establecimientoId, jugadorId).get(0);
        long ausencias = reservaRepository.countAusenciasDeJugador(establecimientoId, jugadorId);
        var bloqueo = bloqueoJugadorRepository.findByEstablecimientoIdAndJugadorId(establecimientoId, jugadorId);

        ClienteResponse cliente = construirClienteResponseDesdeHistoricoSingular(
                jugadorId, usuario, historico, ausencias, bloqueo.isPresent());

        LocalDateTime fechaPrimeraReserva = reservaRepository.primeraReservaPorJugador(establecimientoId).stream()
                .filter(fila -> jugadorId.equals(fila[0]))
                .map(fila -> (LocalDateTime) fila[1])
                .findFirst()
                .orElse(null);

        return new ClienteDetalleResponse(cliente, bloqueo.map(BloqueoJugador::getMotivo).orElse(null), fechaPrimeraReserva);
    }

    private ClienteResponse construirClienteResponseDesdeHistoricoSingular(Long jugadorId, Usuario usuario, Object[] historico,
                                                                             long ausencias, boolean bloqueado) {
        long reservasTotales = ((Number) historico[0]).longValue();
        BigDecimal totalGastado = historico[1] != null ? (BigDecimal) historico[1] : BigDecimal.ZERO;
        LocalDateTime ultimaReserva = (LocalDateTime) historico[2];
        return new ClienteResponse(
                jugadorId,
                usuario.getNombre(),
                usuario.getTelefono(),
                usuario.getEmail(),
                reservasTotales,
                ultimaReserva,
                ausencias,
                totalGastado,
                bloqueado
        );
    }

    private Establecimiento validarAcceso(Long establecimientoId, String email) {
        Establecimiento establecimiento = establecimientoRepository.findById(establecimientoId)
                .orElseThrow(() -> new EntityNotFoundException("Establecimiento no encontrado"));
        autorizacionEmpleadoService.validarPropietarioOAdmin(establecimiento, email);
        return establecimiento;
    }

    private ClienteResponse construirClienteResponse(Long jugadorId, Usuario usuario, Object[] historico,
                                                       long ausencias, boolean bloqueado) {
        long reservasTotales = historico != null ? ((Number) historico[1]).longValue() : 0L;
        BigDecimal totalGastado = historico != null && historico[2] != null ? (BigDecimal) historico[2] : BigDecimal.ZERO;
        LocalDateTime ultimaReserva = historico != null ? (LocalDateTime) historico[3] : null;
        return new ClienteResponse(
                jugadorId,
                usuario.getNombre(),
                usuario.getTelefono(),
                usuario.getEmail(),
                reservasTotales,
                ultimaReserva,
                ausencias,
                totalGastado,
                bloqueado
        );
    }

    @Transactional(readOnly = true)
    public Page<ReservaResponse> listarReservasDeCliente(
            Long establecimientoId, Long jugadorId, Pageable pageable, String email) {
        validarAcceso(establecimientoId, email);
        return reservaRepository
                .findByJugador_IdAndCancha_Establecimiento_Id(jugadorId, establecimientoId, capPageSize(pageable))
                .map(reservaMapper::mapToResponse);
    }

    private Pageable capPageSize(Pageable pageable) {
        if (pageable.getPageSize() <= TAMANIO_PAGINA_MAXIMO) {
            return pageable;
        }
        return PageRequest.of(pageable.getPageNumber(), TAMANIO_PAGINA_MAXIMO, pageable.getSort());
    }
}
