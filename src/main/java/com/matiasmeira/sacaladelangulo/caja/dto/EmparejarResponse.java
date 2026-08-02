package com.matiasmeira.sacaladelangulo.caja.dto;

import java.time.LocalDateTime;

public record EmparejarResponse(String codigo, LocalDateTime expiraEn, String urlEmparejamiento) {
}
