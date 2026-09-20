package com.matiasmeira.sacaladelangulo.establecimiento.controller;

import com.matiasmeira.sacaladelangulo.establecimiento.dto.AdminEstablecimientoResponse;
import com.matiasmeira.sacaladelangulo.establecimiento.dto.RechazarEstablecimientoRequest;
import com.matiasmeira.sacaladelangulo.establecimiento.model.EstadoVerificacion;
import com.matiasmeira.sacaladelangulo.establecimiento.service.AdminEstablecimientoVerificacionService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Endpoints de administración para revisar y resolver la verificación manual de un
 * establecimiento (EN_REVISION -> VERIFICADO o RECHAZADO).
 *
 * <p><b>Por qué usa {@code @PreAuthorize("hasRole('ADMIN')")} en vez del chequeo manual de
 * AdminUsuarioController/AdminMailsController.</b> Esos dos controllers deliberadamente NO
 * usan {@code @PreAuthorize}: validan el rol a mano dentro del service. Acá se aparta de ese
 * precedente a propósito, no por descuido: en este endpoint la autorización ES la feature --
 * todo el punto de exigir un admin es que un OWNER no pueda autoverificar su propio
 * establecimiento ni auto-otorgarse el inicio del trial. Un chequeo manual es una línea de
 * código dentro del service que se puede borrar, comentar o saltear por accidente en un
 * refactor sin que ningún test de compilación lo note; una anotación declarativa en el
 * controller no se puede sortear desde el service que la implementa. Además, los otros 24
 * usos de {@code @PreAuthorize} del repo son todos {@code hasAnyRole('OWNER', 'ADMIN')} --
 * ese vocabulario deja pasar al OWNER en todos lados, y copiarlo tal cual acá reproduciría
 * exactamente el bug que esta feature existe para evitar. Por eso se usa {@code hasRole}
 * puro, no {@code hasAnyRole}, y se lee distinto a propósito.
 */
@RestController
@RequestMapping("/api/v1/admin/establecimientos")
@RequiredArgsConstructor
public class AdminEstablecimientoController {

    private final AdminEstablecimientoVerificacionService adminEstablecimientoVerificacionService;

    @GetMapping
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Page<AdminEstablecimientoResponse>> listar(
            @RequestParam(required = false) EstadoVerificacion estadoVerificacion,
            @ParameterObject @PageableDefault(size = 20) Pageable pageable) {
        return ResponseEntity.ok(adminEstablecimientoVerificacionService.listar(estadoVerificacion, pageable));
    }

    @PostMapping("/{id}/verificar")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Void> verificar(@PathVariable Long id, @AuthenticationPrincipal UserDetails userDetails) {
        adminEstablecimientoVerificacionService.verificar(id, userDetails.getUsername());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/rechazar")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Void> rechazar(
            @PathVariable Long id,
            @RequestBody @Valid RechazarEstablecimientoRequest request,
            @AuthenticationPrincipal UserDetails userDetails) {
        adminEstablecimientoVerificacionService.rechazar(id, request.motivo(), userDetails.getUsername());
        return ResponseEntity.noContent().build();
    }
}
