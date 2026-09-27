package com.matiasmeira.sacaladelangulo.auth.model;

import java.math.BigDecimal;

/**
 * Constantes con los límites/reglas que impone cada {@link PlanSuscripcion}. Única fuente de
 * la seña mínima obligatoria del plan FREE: la referencian tanto CanchaService (al validar el
 * alta/edición de una cancha) como DegradacionPlanService (al ajustar canchas existentes de un
 * dueño que se degrada de TRIAL a FREE), para que ningún lugar duplique el número.
 */
public final class PlanSuscripcionLimites {

    public static final BigDecimal SENA_MINIMA_PLAN_LIMITADO = BigDecimal.valueOf(500);

    private PlanSuscripcionLimites() {
    }
}
