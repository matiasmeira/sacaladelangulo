package com.matiasmeira.sacaladelangulo.reportes.controller;

import com.matiasmeira.sacaladelangulo.reportes.dto.ClientesReporteResponse;
import com.matiasmeira.sacaladelangulo.reportes.dto.FacturacionReporteResponse;
import com.matiasmeira.sacaladelangulo.reportes.dto.HorariosPedidosReporteResponse;
import com.matiasmeira.sacaladelangulo.reportes.dto.OcupacionReporteResponse;
import com.matiasmeira.sacaladelangulo.reportes.service.ReporteClientesService;
import com.matiasmeira.sacaladelangulo.reportes.service.ReporteFacturacionService;
import com.matiasmeira.sacaladelangulo.reportes.service.ReporteHorariosService;
import com.matiasmeira.sacaladelangulo.reportes.service.ReporteOcupacionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

/**
 * Endpoints de reportes agregados para el panel del dueño de un establecimiento
 * (facturación, ocupación, horarios más pedidos, clientes). Todo el cálculo se agrega en
 * el backend -el front no debe traer reservas crudas y sumarlas- y devuelve, salvo donde se
 * indica lo contrario, tanto el período pedido como el inmediatamente anterior de igual
 * duración para comparar.
 */
@RestController
@RequestMapping("/api/v1/establecimientos/{establecimientoId}/reportes")
@RequiredArgsConstructor
@Tag(name = "Reportes", description = "Métricas agregadas para el panel del dueño de establecimiento")
public class ReporteController {

    private final ReporteFacturacionService reporteFacturacionService;
    private final ReporteOcupacionService reporteOcupacionService;
    private final ReporteHorariosService reporteHorariosService;
    private final ReporteClientesService reporteClientesService;

    @GetMapping("/facturacion")
    @PreAuthorize("hasAnyRole('OWNER', 'ADMIN')")
    @Operation(
            summary = "Facturación del establecimiento en un rango de fechas",
            description = "Total facturado, desglose por método de pago real (EFECTIVO/TRANSFERENCIA/MERCADO_PAGO/" +
                    "TARJETA_DEBITO/TARJETA_CREDITO) y serie temporal diaria. Solo cuentan reservas en estado FINALIZADA. " +
                    "Incluye el período pedido y el inmediatamente anterior de igual duración para comparar."
    )
    public ResponseEntity<FacturacionReporteResponse> obtenerFacturacion(
            @PathVariable Long establecimientoId,
            @Parameter(description = "Fecha de inicio del período (inclusive)") @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate desde,
            @Parameter(description = "Fecha de fin del período (inclusive)") @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate hasta,
            @AuthenticationPrincipal UserDetails userDetails) {
        return ResponseEntity.ok(reporteFacturacionService.obtenerFacturacion(establecimientoId, desde, hasta, userDetails.getUsername()));
    }

    @GetMapping("/ocupacion")
    @PreAuthorize("hasAnyRole('OWNER', 'ADMIN')")
    @Operation(
            summary = "Porcentaje de ocupación del establecimiento en un rango de fechas",
            description = "% de ocupación general, por franja horaria (mañana 06-13/tarde 13-19/noche 19-24) y por " +
                    "cancha. Horas disponibles = horario de atención × canchas activas, menos días no laborables y " +
                    "bloqueos de mantenimiento (ver campo notaMetodologica de la respuesta para el detalle y las " +
                    "limitaciones conocidas). Incluye el período pedido y el anterior de igual duración."
    )
    public ResponseEntity<OcupacionReporteResponse> obtenerOcupacion(
            @PathVariable Long establecimientoId,
            @Parameter(description = "Fecha de inicio del período (inclusive)") @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate desde,
            @Parameter(description = "Fecha de fin del período (inclusive)") @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate hasta,
            @AuthenticationPrincipal UserDetails userDetails) {
        return ResponseEntity.ok(reporteOcupacionService.obtenerOcupacion(establecimientoId, desde, hasta, userDetails.getUsername()));
    }

    @GetMapping("/horarios-pedidos")
    @PreAuthorize("hasAnyRole('OWNER', 'ADMIN')")
    @Operation(
            summary = "Ranking de horarios más pedidos",
            description = "Top N combinaciones de día de semana + hora con más reservas FINALIZADA en el rango. " +
                    "No compara con el período anterior (es un ranking, no una métrica escalar)."
    )
    public ResponseEntity<HorariosPedidosReporteResponse> obtenerHorariosPedidos(
            @PathVariable Long establecimientoId,
            @Parameter(description = "Fecha de inicio del período (inclusive)") @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate desde,
            @Parameter(description = "Fecha de fin del período (inclusive)") @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate hasta,
            @Parameter(description = "Cantidad de resultados del ranking a devolver") @RequestParam(defaultValue = "10") int topN,
            @AuthenticationPrincipal UserDetails userDetails) {
        return ResponseEntity.ok(reporteHorariosService.obtenerHorariosPedidos(establecimientoId, desde, hasta, topN, userDetails.getUsername()));
    }

    @GetMapping("/clientes")
    @PreAuthorize("hasAnyRole('OWNER', 'ADMIN')")
    @Operation(
            summary = "Reporte de clientes del establecimiento",
            description = "Clientes nuevos (primera reserva FINALIZADA de ese jugador en este establecimiento dentro " +
                    "del período), ausencias (no implementado todavía: el modelo no registra asistencia/no-show) y " +
                    "top N clientes por cantidad de reservas en el rango (sin comparar con el período anterior)."
    )
    public ResponseEntity<ClientesReporteResponse> obtenerClientes(
            @PathVariable Long establecimientoId,
            @Parameter(description = "Fecha de inicio del período (inclusive)") @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate desde,
            @Parameter(description = "Fecha de fin del período (inclusive)") @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate hasta,
            @Parameter(description = "Cantidad de clientes del ranking a devolver") @RequestParam(defaultValue = "10") int topN,
            @AuthenticationPrincipal UserDetails userDetails) {
        return ResponseEntity.ok(reporteClientesService.obtenerClientes(establecimientoId, desde, hasta, topN, userDetails.getUsername()));
    }
}
