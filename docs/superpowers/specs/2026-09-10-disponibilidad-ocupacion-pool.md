# Ocupación derivada del pool en la agenda del panel

> Spec original tal como la escribió el usuario. Repo `sacaladelangulo`, rama `test`.

## CONTEXTO

La agenda del panel no muestra la ocupación derivada del pool: si se reserva la
Cancha 9 (lógica, pool de 3 físicas, necesita 3), las tres de 5 y la Cancha 7
quedan efectivamente ocupadas — el backend las rechaza al reservar — pero el
front no tiene con qué pintarlas.

## REGLA — es la única, no hay casos especiales

Una cancha está ocupada en un rango si y sólo si una reserva nueva en esa
cancha, en ese rango, sería rechazada por `PoolCanchaCalculator.hayDisponibilidad`.
Ya produce el comportamiento deseado en ambos casos sin lógica adicional: con
la de 7 reservada (consume 2 de 3) las físicas siguen siendo reservables y
quedan libres; con la de 9 (consume 3 de 3) queda todo ocupado.

## POR QUÉ NO ALCANZA CON slotsLibres

`generarSlotsLibres` no devuelve "libre de pool" sino "vendible ahora mismo": ya
descontó horas pasadas (recibe `ahora`), bloqueos de mantenimiento, la reserva
propia de la cancha, y está cuantizado por duración permitida. Derivar el gris
de la ausencia de slots pintaría toda la mañana como ocupada por pool. Hace
falta el dato explícito.

## IMPLEMENTACIÓN — sin endpoint nuevo

Agregá un campo a `DisponibilidadCanchaResponse`, por ejemplo
`List<RangoOcupadoResponse> ocupadaPorPool` con (inicio, fin): los rangos en los
que esa cancha NO es reservable por consumo de pool AJENO. Excluí los rangos
ya cubiertos por una reserva propia de esa cancha — el front las dibuja aparte
y pintarlas dos veces es ruido.

## VISIBILIDAD — importante

El endpoint público `/api/v1/publico/complejos/{slug}/disponibilidad` delega en
el MISMO `DisponibilidadService` (`ComplejoPublicoService:410`) y devuelve el
mismo DTO. Este campo NO debe viajar al público: le revelaría al jugador
anónimo la ocupación interna del complejo, que hoy no se expone.
Meté un flag en `obtenerDisponibilidad`: `DisponibilidadController` lo pasa en
`true`, `ComplejoPublicoService` en `false`. Con `false` el campo va en `null`
(no en lista vacía: `null` distingue "no se pidió" de "no hay ocupación
derivada"). Agregá un test que verifique que la respuesta pública lo trae en
`null`.

## CÁLCULO

`calcularDisponibilidadDelDia` ya tiene cargadas las canchas, las reservas
solapadas, los bloqueos y la ventana horaria: reutilizá todo eso, no agregues
consultas.

1. Puntos de corte: todos los inicios y fines de las reservas del día que
   ocupan cupo, acotados a la ventana horaria.
2. Para cada intervalo entre cortes consecutivos y cada cancha, corré
   `PoolCanchaCalculator.hayDisponibilidad` con las reservas que solapan ese
   intervalo. Si da `false` y la cancha no tiene reserva propia ahí, el
   intervalo va a su lista.
3. Fusioná intervalos contiguos de la misma cancha.

**OBLIGATORIO:** reutilizá `PoolCanchaCalculator`. NO reimplementes la regla en
otra clase — es exactamente lo que hizo que la agenda y la validación
divergieran.

## TESTS

1. Cancha 9 (pool F1,F2,F3, necesita 3) reservada 17-18 -> F1, F2, F3 y
   Cancha 7 traen 17-18 en `ocupadaPorPool`.
2. Cancha 7 (mismo pool, necesita 2) reservada 15-16 -> F1, F2, F3 traen
   `ocupadaPorPool` vacío; Cancha 9 trae 15-16.
3. Una física suelta reservada -> no genera ocupación derivada sobre las otras
   dos físicas; sí sobre Cancha 9 si no queda cupo.
4. Dos reservas contiguas del mismo grupo (15-16 y 16-17) -> el rango derivado
   sale fusionado 15-17, no partido en dos.
5. Prereserva PENDIENTE_SENA vencida -> no genera ocupación derivada.
6. Establecimiento sin canchas lógicas -> `ocupadaPorPool` vacío en todas.
7. Respuesta del endpoint público -> `ocupadaPorPool` en `null`.

**NO tocar `slotsLibres` ni el cálculo existente de disponibilidad.**
