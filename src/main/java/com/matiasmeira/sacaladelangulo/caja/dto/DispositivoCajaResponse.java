package com.matiasmeira.sacaladelangulo.caja.dto;

import java.time.LocalDateTime;

public record DispositivoCajaResponse(Long id, String label, LocalDateTime createdAt, LocalDateTime lastUsedAt) {
}
