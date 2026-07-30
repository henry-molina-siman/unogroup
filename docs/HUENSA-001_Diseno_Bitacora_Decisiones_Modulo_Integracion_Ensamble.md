# Bitácora de Decisiones — Módulo de Integración de Servicios de Ensamble

**Proyecto:** HUENSA-001 — Integración de Pedidos que Requieren Ensamble
**Propósito:** registrar cómo y cuándo llegó el diseño a su estado actual — hallazgos de auditoría, discrepancias detectadas, decisiones tomadas sin confirmación externa, y el historial completo de preguntas abiertas (resueltas y pendientes).

> **Diseño vigente:** ver `HUENSA-001_Diseno_Requerimientos_Modulo_Integracion_Ensamble.md`. Ese documento describe únicamente el estado actual acordado; este documento es el registro de auditoría/proceso que lo respalda. Las referencias de sección (`§N`) en este documento apuntan al documento de Diseño, salvo que se indique lo contrario.

---

## Índice

1. [Historial de revisiones](#1-historial-de-revisiones)
2. [Reconciliaciones de documentos divergentes](#2-reconciliaciones-de-documentos-divergentes)
3. [Historial de hallazgos sobre el contrato de Solution One](#3-historial-de-hallazgos-sobre-el-contrato-de-solution-one)
4. [Índice Maestro de Preguntas Abiertas](#4-índice-maestro-de-preguntas-abiertas)

---

# 1. Historial de revisiones

| Versión | Cambio principal |
|---|---|
| v1 | Diseño original — un solo módulo, 3 capas internas. |
| v2 | El módulo se separa en dos microservicios independientes (Orquestador + Comunicación con UnoGroup), comunicados vía notificación asíncrona con callback (`unogroup-app` sin base de datos propia). Mecanismo de entrada unificado (un solo tópico Pub/Sub para WMS y Guías Manuales). Nuevo hallazgo confirmado sobre el contrato real de Solution One (`mkdir_parents`). Actualización de stack: Java 21, Spring Boot 4.x, OpenFeign. |
| v2.1 | Nomenclatura de tablas actualizada por schema físico compartido: la instancia/schema de Cloud SQL se comparte con otros sistemas de Siman (no solo con `unogroup-app`). Las tres tablas del módulo adoptan el prefijo `ensamble_` para evitar colisiones de nombre. No cambia el modelo de datos ni la propiedad lógica de escritura. |
| v2.1 + reconciliación 2026-07-29 | Orquestador y UnoGroup habían quedado con copias separadas del documento de diseño tras dividirse en dos proyectos Maven, y cada equipo solo había actualizado su propia parte. Se fusionan ambas líneas de cambio en un solo documento — ver §2 de esta bitácora para el detalle de qué aportó cada lado. |
| v3 (esta reorganización) | El documento de diseño se separa en dos: **Diseño/Requerimientos** (el estado actual acordado, sin anotaciones de proceso) y esta **Bitácora** (historial, auditorías, preguntas abiertas). No cambia ningún contenido técnico — es una reorganización editorial. |
| v3.1 | **Corrección contra el diagrama de infraestructura actualizado:** `WMS-Order Provider` no existe como componente separado — es **DMS/OMS** quien solicita crear la orden en WMS y, por separado, publica el evento de creación al tópico único de Pub/Sub. Corrige el diagrama de arquitectura, la tabla de componentes y las descripciones de UC1/UC2 en el documento de Diseño (§2.1, §2.5, §3.1, §3.2). No cambia el comportamiento del sistema — solo el nombre correcto del componente publicador. |
| v3.2 — contrato `transacciones[]` (2026-07-29) | **Cambio de ruptura en el contrato del callback.** `ResultadoSolicitud.intentos[]` (campos HTTP sueltos, acoplados a Solution One) se reemplaza por `transacciones[]` (`TransaccionHttp`: `metadata`+`request`+`response`/`error`, genérico, no acoplado a ningún partner) — contrato `HUENSA-001_openapi_V3.yaml` (promovido desde `docs/bitacora/`, reemplaza `openapi_V2.yaml`). `ensamble_bitacora_partner` se recreó por completo (no retrocompatible); `ensamble_solicitud` pierde `payload_partner`/`nombre_archivo` (redundantes frente a la nueva bitácora). Resuelve F8 del lado del contrato (`request.method`/`request.url` obligatorios); la autenticación entre servicios sigue abierta. |
| v3.2 + reconciliación 2026-07-30 | Segunda vez que Orquestador y UnoGroup quedan con copias separadas tras la migración v3: la copia de `orquestador-app` de esta bitácora registró la migración, mientras la de `unogroup-app` no registró ningún avance, aunque su copia de Diseño/Requerimientos sí incorporó el nuevo modelo `transacciones[]` (con explicaciones y ejemplos adicionales que no estaban del lado de Orquestador). Se fusionan ambas copias — ver §2.2 de esta bitácora para el detalle de qué aportó cada lado y qué gaps quedaron expuestos por la reconciliación. |

---

# 2. Reconciliaciones de documentos divergentes

Esta sección registra los momentos en que las copias de `orquestador-app` y `unogroup-app` de los documentos de Diseño se separaron y volvieron a fusionarse — no hallazgos de código (esos viven en `HUENSA-001_Implementacion_Bitacora_Decisiones_Modulo_Integracion_Ensamble.md`).

## 2.1 Reconciliación 2026-07-29 — primer fork por proyectos Maven

Orquestador y UnoGroup quedaron con copias separadas del documento de diseño tras dividirse en dos proyectos Maven, y cada equipo solo había actualizado su propia parte. Se fusionaron ambas líneas de cambio en un solo documento (ver fila correspondiente en §1).

## 2.2 Reconciliación 2026-07-30 — segundo fork tras la migración a `transacciones[]`

Tras la migración al contrato v3 (fila v3.2 de §1), las copias de `orquestador-app` y `unogroup-app` de **ambos** documentos (Diseño/Requerimientos y esta Bitácora) volvieron a divergir.

**Del lado Orquestador:** el registro de la decisión de diseño quedó documentado solo en esa copia; la copia de UnoGroup no había registrado ningún avance de este lado, y su Bitácora seguía idéntica al estado anterior a la migración.

**Del lado UnoGroup:** su copia de Diseño/Requerimientos sí incorporó el modelo `transacciones[]`, con contenido adicional que se fusiona en esta reconciliación — la aclaración de qué campos se enmascaran (`url`/`headers` sí, `body` todavía no), un ejemplo de consulta para reconstruir "el último payload/archivo" sin la columna eliminada, y la justificación de por qué `orden_id`/`sku` dejan de ser nullable en `ensamble_bitacora_partner` (ver Diseño §2.4, §2.6.1).

**Inconsistencia interna detectada y corregida:** la copia de UnoGroup de Diseño/Requerimientos ya describía el modelo v3 en el cuerpo del documento, pero la referencia al archivo de contrato en §5 seguía apuntando a `HUENSA-001_openapi_V2.yaml` en vez de `HUENSA-001_openapi_V3.yaml` — un desfase entre el texto y la referencia de archivo dentro de la misma copia, no una discrepancia real entre equipos. Se corrigió a `V3` en el documento fusionado, consistente con el resto del contenido y con la fila v3.2 de §1.

**Gap de artefacto, resuelto 2026-07-30:** ninguna de las dos copias traía el archivo `HUENSA-001_openapi_V3.yaml` como artefacto real — el repositorio del proyecto solo tenía `HUENSA-001_openapi_V2.yaml`, aunque la fila v3.2 de §1 y varias referencias en Diseño §2.4/§2.6/§5 daban por hecho que V3 ya estaba "promovido". El archivo fue agregado al proyecto el mismo día — ver F21 (§4.6), cerrado.

**Corrección de higiene documental:** el documento de Diseño/Requerimientos (§2.4) traía una anotación sobre qué faltaba construir en un repositorio, que no le correspondía — ese documento describe el estado actual acordado *sin anotaciones de proceso*. Se retiró en la fusión. **La misma regla aplica a esta Bitácora de Diseño:** el estado de qué está o no construido en `orquestador-app`/`unogroup-app` es tema de `HUENSA-001_Implementacion_Bitacora_Decisiones_Modulo_Integracion_Ensamble.md`, no de esta bitácora — se retiraron de aquí todos los hallazgos de auditoría de código (nombres de clase, scripts SQL, workarounds), que ya viven, con más detalle, en esa bitácora. Esta bitácora se limita a las preguntas y decisiones de diseño en sí (por ejemplo, F8 sigue abierta como pregunta de diseño — qué mecanismo de autenticación se usa entre los dos servicios — no como pendiente de código).

---

# 3. Historial de hallazgos sobre el contrato de Solution One

Estos hallazgos documentan cómo se llegó al estado actual de §6 del documento de Diseño (contrato PTI-IRRIS-16).

## 3.1 Nombre de campo `customer_*` vs. `customer_location_*` (C2)

El instructivo PTI-IRRIS-16 es internamente inconsistente:
- **Tabla (sección 2):** `customer_location_address`, `customer_location_city`, `customer_location_state`, `customer_location_country`, `customer_location_latitude`, `customer_location_longitude`.
- **Ejemplo JSON (sección 3):** `customer_address`, `customer_city`, `customer_state`, `customer_country`, `customer_latitude`, `customer_longitude` — sin el prefijo `location`.

**Resuelto:** contra el mapeo real Orquestador→UnoGroup→Solution One (Diseño §4.4), la forma correcta es la del ejemplo JSON — `customer_*` sin `location`. La tabla del instructivo tenía el error.

## 3.2 Tres formatos de nomenclatura de archivo en la misma sección del instructivo

El instructivo describe la nomenclatura de archivo de tres formas distintas dentro de la misma sección 6:
1. Texto descriptivo: `{timestamp}_{external_reference}.json`
2. Ejemplo inmediato: `20260410050000_9013059587_104929691.json` (agrega SKU, sin explicarlo en el texto)
3. Tabla de componentes + ejemplo de carpetas (sección 7): `create_20260410050000_9013059587_104929691.json` (agrega prefijo de acción)

El formato vigente (Diseño §2.9) sigue la variante 3.

## 3.3 Estructura de carpetas — reabierto el 2026-07-15 (C3)

Historial de esta pregunta, en orden cronológico:
1. El instructivo es contradictorio entre sí: sección 7 muestra la ruta raíz como `asm/{fecha}/...`; sección 10 (manejo de error 403) dice *"verificar que la ruta sea exactamente `/siman/create/` o `/siman/update/`"*.
2. **"Resuelto" en una sesión previa:** una petición real exitosa había usado la carpeta raíz `assembly` (no `asm`, no `/siman/create|update/`): `assembly/{fecha}/{accion}_{timestamp}_{external_reference}_{sku}.json`.
3. **Reabierto 2026-07-15:** en producción, con las mismas credenciales (`siman.assembly`) que la petición exitosa anterior, la misma estructura `assembly/{fecha}/...` empezó a responder `403`/`permission denied` (`"Unable to write file \"/assembly/{fecha}/...\""`). Se confirmó que la sección 10 del instructivo tenía razón después de todo: la raíz real es `/siman/create/` para creación y `/siman/update/` para actualización — la opción que se había descartado por error en la sesión previa.

**Estado vigente (reflejado en Diseño §2.9):** `siman/{accion}/{fecha}/{accion}_{timestamp}_{external_reference}_{sku}.json`.

**Sigue pendiente:** entender por qué la petición de prueba anterior tuvo éxito contra `assembly/` — hipótesis: ambiente/permiso temporal distinto al de producción, sin confirmar con UnoGroup (ver C3 en §4).

## 3.4 Longitud de `external_reference` (C4)

El instructivo documenta dos longitudes distintas para el mismo campo: `string(32)` en creación, `string(50)` en actualización — inconsistencia que está en el propio documento oficial aprobado, no es un error de transcripción de este proyecto.

**Decisión tomada (conservadora):** se usa 32 en todo el sistema (DDL, entidades, contrato OpenAPI) — el menor de los dos valores, para evitar truncamiento silencioso o rechazo si UnoGroup en realidad usa el límite más corto. Confirmación directa con UnoGroup sigue pendiente, pero ya no bloquea implementación.

## 3.5 Codificación URL del parámetro `path` (C5)

El ejemplo de codificación URL del instructivo estaba mal formado:
```
asm/20260410/create_20260410050000_9013059587_104929691.json
→ asm%220260410%2Fcreate_F20260410050000_9013059587_104929691.json
```
Debería ser `asm%2F20260410%2Fcreate_20260410050000_...json` (solo las barras `/` se codifican como `%2F`). El ejemplo tenía un `%22` (código de comilla doble, no de barra) y un `_F` insertado sin explicación — y esta misma cadena mal formada aparecía repetida, textualmente, como valor de ejemplo del query param `path` en la sección 9 del instructivo.

**Resuelto:** una petición real exitosa usa codificación estándar (`assembly%2F20260713%2Fcreate_...json`, solo `%2F` para las barras) — confirma que el ejemplo del instructivo era un error de documentación, no un comportamiento real a replicar.

---

# 4. Índice Maestro de Preguntas Abiertas

## 4.1 Para el equipo de Infor WMS

| # | Pregunta | Bloquea / Impacta | Origen |
|---|---|---|---|
| ~~A1~~ | ~~¿El evento trae el campo que distingue ASSE de ENSA (`ext_udf_str10` o equivalente) en UP05/UP06?~~ — **Resuelto:** no viene en el evento; vive en `orderdetails[].ext_udf_str10` de la respuesta de `GET .../shipment/get-shipment/{whseId}/{externOrderKey}`, a nivel de línea (una orden puede tener líneas ASSE y ENSA a la vez). | **Crítico** — sin esto no se podía enrutar el flujo | Diseño §4.1, §4.3 |
| A2 | `susr3` (header) en `WmsShipmentDetail`: nueva evidencia sugiere que duplica `externorderkey`, no es el número de factura/pedido — confirmar con WMS si es así o si en algún caso trae un valor distinto. | Mapeo de `external_reference_alt_2` | Diseño §4.1 nota 1 |
| A3 | ¿Cómo se deriva el código ISO de país — vía `storerkey`, `whseid`, u otro campo? Confirmado que `ccountry` viene `null` en `WmsShipmentDetail`. | Mapeo de `customer_country` (`pais`), multipaís | Diseño §4.1 nota 2 |
| ~~A4~~ | ~~En UP05 el mismo `sku` aparece en varias líneas de `detail[]` con `qty` distintas — ¿sumar o generar sub-orden por línea?~~ — **No aplica:** `detail[]`/`pickDetail[]` dejaron de usarse como fuente de datos; la cantidad real viene de `orderdetails[].originalqty`/`shippedqty` en `WmsShipmentDetail`, sin esta ambigüedad. | Modelo de datos / idempotencia | Diseño §4.1 nota 3 |
| A5 | Fuente de `customer_vip`, `latitud/longitud`, `item_brand/category/description`, `tracking_dispatch_plan_time` — ninguno visto en el ejemplo real de `WmsShipmentDetail`; ¿RMS es la fuente? | Capa de enriquecimiento | Diseño §4.1, §4.3 |
| A6 | ¿`tracking_order_time` sale de `adddate` u `orderdate` de `WmsShipmentDetail`? Ambos presentes en el ejemplo real, con valores distintos. | Mapeo de `tracking_order_time` | Diseño §4.1 nota 4 |
| A7 | ¿`tracking_dispatched_time`/`tracking_delivered_time` salen del campo `fecha` del payload crudo de UP05/UP06 (formato no ISO 8601), o de la hora de recepción del evento en el Orquestador? | Mapeo de `tracking_dispatched_time` | Diseño §4.1 nota 4 |

## 4.2 Para el equipo de App de Guías Manuales

| # | Pregunta | Bloquea / Impacta | Origen |
|---|---|---|---|
| B1 | Todas las filas `desconocido` de la Tabla 2 — identidad de guía, datos de cliente, ubicación, producto, fechas | Mapeo completo del origen "Guías Manuales" | Diseño §4.2 |
| ~~B2~~ | ~~Selector "¿requiere ensamble?": ¿en qué momento se activa, aplica a todos los tipos de formulario, se llama por ítem o por guía completa?~~ — **Resuelto:** por guía completa. Un solo evento por orden con `items[]`; el Orquestador hace el fan-out. Sigue sin confirmar el momento exacto de activación. | Diseño del evento CARM | Diseño §4.2.1 |
| B3 | ~~¿El formulario nuevo de Armado/Desarmado vive dentro del aplicativo existente o es separado?~~ — **Resuelto:** vive dentro del aplicativo de guías manuales existente, como formularios adicionales (ver Diseño §3.4). **Siguen abiertas:** ¿qué campos captura?, ¿quién cierra el servicio y desde dónde? | Diseño de los eventos TARM/DARM | Diseño §3.4, §4.2.1 |
| ~~B4~~ | ~~¿Qué estructura de mensaje/atributos usará App de Guías al publicar al tópico único?~~ — **Resuelto:** `origen=guias`, `flujo` (CARM/TARM/DARM), `tipo_evento` (`creacion` o `actualizacion`). Los tres flujos comparten un único schema de body, `EventoGuias`. | Consumer del Orquestador | Diseño §2.5, §4.2.1, §4 (F19) |
| B5 | ¿`tipoServicio`/`ubicacionServicio` viajan explícitos en el evento de Guías, o el Orquestador los deriva del atributo `flujo`? ¿`external_reference_alt_2` es el mismo dato que `guiaRelacionada` (vínculo DARM↔CARM) o un campo distinto? ¿El formulario captura marca/categoría/descripción por ítem, o solo SKU? | Schema final de `EventoGuias`, mapeo Diseño §4.3 | Diseño §4.2, §4.2.1 |

## 4.3 Para UnoGroup / Solution One

| # | Pregunta | Origen |
|---|---|---|
| C1 | ¿Existe mecanismo (webhook, endpoint de estado, o archivo de resultado) para confirmar si un archivo fue aceptado o rechazado a nivel de contenido? | Diseño §2.10, §3 de esta bitácora #6 |
| ~~C2~~ | ~~Nombre real de campo: ¿`customer_location_*` o `customer_*`?~~ — **Resuelto**: `customer_*` (Diseño §4.4, §3.1 de esta bitácora) | §3.1 |
| C3 | Estructura real de carpetas: ¿`asm/{fecha}/...`, `assembly/{fecha}/...` o `/siman/create/` y `/siman/update/`? — **Reabierto 2026-07-15**, vigente ahora es `/siman/create/{fecha}/...` o `/siman/update/{fecha}/...`. Falta confirmar con UnoGroup por qué `assembly/` funcionó en la prueba anterior. Ver historial completo en §3.3. | §3.3 |
| ~~C4~~ | ~~Longitud real de `external_reference`: ¿`string(32)` o `string(50)`?~~ — **Decisión tomada (conservadora):** 32. Confirmación directa con UnoGroup sigue pendiente, no bloquea implementación. Ver §3.4. | §3.4 |
| ~~C5~~ | ~~Codificación URL del `path` (`%22`, `_F`): ¿error de documentación o comportamiento real a replicar?~~ — **Resuelto**: era error de documentación; la codificación estándar (`%2F`) funciona en una petición real exitosa. Ver §3.5. | §3.5 |
| C6 | ¿"LifeOne" y "UnoGroup" son el mismo canal de escalamiento, o distintos? | Diseño §6.4 |
| C7 | ¿Qué operaciones soportará el partner en Fase 2 para cancelación/reprogramación? | Diseño §2.5, §7.1, §8.2 |

## 4.4 Para Beetrack / Logística (canal de notificación)

| # | Pregunta | Bloquea | Origen |
|---|---|---|---|
| D1 | ~~Canal de notificación de "entregado" desde Beetrack hacia el módulo — sin definir~~ — **Canal resuelto:** componente "Tracking" publica al tópico único vía Pub/Sub. **Sigue abierto:** schema/atributos exactos del mensaje | Update de UC1 (ASSE), UC3 (CARM) y Momento 3 de UC5 | Diseño §2.1, §3.1, §3.3, §3.5.3 |
| D2 | Sin Beetrack de por medio (TARM en tienda, DARM en tienda) — ¿quién notifica que el servicio se completó, y cómo? | Update de UC4 y Momento 1 de UC5 | Diseño §3.4, §3.5.1 |

## 4.5 Preguntas de negocio

Ya consolidadas en **Diseño §7** — proceso manual para cancelaciones, riesgo aceptado en Fase 1, DPA con UnoGroup, credenciales multipaís, gobernanza de geocodificación, volumetría, UAT y sandbox.

## 4.6 Decisiones de diseño interno pendientes (no dependen de un tercero)

*(La numeración no es consecutiva: los IDs son estables y se citan por número en el documento de Implementación — F10 fue renumerado a F14 en su momento, y no se reutiliza.)*

| # | Pendiente | Origen |
|---|---|---|
| F1 | Tabla completa de transiciones válidas/anómalas de la máquina de estados (`tracking_status`) | Diseño §2.8 |
| ~~F2~~ | ~~Redundancia entre `solicitud_reintento` y `ensamble_bitacora_partner`~~ — **Resuelto**: se eliminó `solicitud_reintento` por completo | Diseño §2.6 |
| F3 | Vínculo DARM↔CARM (UC5): ¿se envía a Solution One o queda solo interno en Siman? | Diseño §3.5.3 |
| F4 | N ítems en una guía = N sub-órdenes = N archivos JSON — ¿es el comportamiento deseado? | Diseño §3.3 |
| F5 | `tracking_status: retornada` — ¿se documenta explícitamente como "no usado en Fase 1"? | Diseño §6.5, §7.1 |
| ~~F6~~ | ~~¿Cómo se distingue `origen`/`flujo` dentro del tópico único de Pub/Sub?~~ — **Resuelto**: atributos de mensaje `origen` y `flujo` únicamente (se descartaron `país`, `bodega`, `estado` — ver Diseño §2.5) | Diseño §2.5 |
| F7 | Intervalo exacto del job de reconciliación (orden de 15-30 min, valor final sin cerrar) | Diseño §2.11, §8.2 |
| F8 | Contrato exacto de los dos endpoints internos — notificación (Orquestador→UnoGroup) y callback (UnoGroup→Orquestador) — autenticación entre servicios dentro del clúster (¿mTLS interno, token compartido, nada por estar en red privada?), formato exacto de ambos payloads. **Formato del callback resuelto 2026-07-29 vía contrato v3:** `ResultadoSolicitud.transacciones[]` reemplaza `intentos[]` — `request.method`/`request.url` son obligatorios en el contrato. **Sigue sin resolver:** qué mecanismo de autenticación se usa entre los dos servicios dentro del clúster. | Diseño §2.4 |
| ~~F9~~ | ~~Validar contra ambiente de prueba real si el body binario de la carga a Solution One requiere encoder Feign personalizado~~ — **Resuelto**: sí requiere encoder custom (Feign no serializa binario por defecto); probado contra el ambiente de prueba real, `Content-Type: application/json` funciona para el body binario | Diseño §2.9, §8.2 |
| ~~F11~~ | ~~¿`DMS/OMS` conoce el flujo (ASSE/ENSA) en el momento de crear la orden, para poder setear el atributo `flujo` al publicar?~~ — **Resuelto: no lo conoce, y estructuralmente no puede.** El flujo se determina por línea, dentro de `orderdetails[].ext_udf_str10`, solo después de que el Orquestador consulta el shipment — una misma orden puede tener líneas ASSE y ENSA a la vez. `flujo` deja de ser `required` para `origen=wms` (ver Diseño §2.5). | Diseño §3.1, §3.2, §4.1 |
| ~~F12~~ | ~~El payload de WMS no indica si un evento de actualización es `UP05` o `UP06`~~ — **Resuelto**: nuevo atributo de mensaje `tipo_evento`, con los códigos nativos del origen (`CREAR`, `UP05`, `UP06` para WMS) — ver Diseño §2.5 | Diseño §3.1, §3.2 |
| F13 | Schema/atributos del mensaje "Actualización Entrega a Cliente" que publica el componente Tracking (incluye Beetrack) — el canal ya está confirmado (Pub/Sub), falta el contenido exacto | Diseño §2.1, §3.1, §3.3, §3.5.3 |
| F14 | ¿Se necesita Cloud Router/Cloud NAT para que el pod del microservicio de UnoGroup tenga salida a internet hacia Solution One (`data.solution1.us`)? Marcado como duda explícita en el propio diagrama de infraestructura del equipo. | Diseño §2.1 |
| F15 | Confirmar que `DMS/OMS` y `Infor WMS` comparten el mismo `ordenId`/`externOrderKey` entre la creación y las actualizaciones posteriores (UP05/UP06) — no verificado explícitamente, solo asumido por consistencia de diseño. | Diseño §2.1, §3.1, §3.2 |
| F16 | Cómo detecta el job de reconciliación la tercera zona atascada (UnoGroup procesó pero el callback se perdió) — UnoGroup ya no tiene base de datos propia donde dejar rastro de qué procesó, ¿logging estructurado + alerta externa, o algún otro mecanismo? | Diseño §2.11 |
| F17 | ¿Qué valores lleva `tipo_evento` para los eventos de Guías Manuales (CARM/TARM/DARM)? — **Resuelto**: `creacion` / `actualizacion` (ver Diseño §4.2.1). El estado de construcción del ruteo real para `actualizacion` se registra en `HUENSA-001_Implementacion_Bitacora_Decisiones_Modulo_Integracion_Ensamble.md`, no aquí. | Diseño §2.5, §4.2.1 |
| F18 | Valor de `service_type` (`tipoServicio`) para el flujo DARM — solo se confirmó `"armado"` (ASSE/ENSA/CARM/TARM); ¿DARM usa `"desarmado"` u otro valor? | Diseño §4.1, §4.3 |
| ~~F19~~ | ~~¿El nombre de archivo de actualización usa prefijo `update`?~~ — **Decidido:** sí, por simetría con `create`. Sin ejemplo real que lo confirme todavía — es una decisión de diseño, no una confirmación de UnoGroup. | Diseño §2.9 |
| ~~F20~~ | ~~¿El nombre de archivo de actualización incluye `sku`?~~ — **Decidido:** sí, siempre se incluye (a diferencia del body, que no lo lleva en actualizaciones — Diseño §4.4). Es lo que le permite a UnoGroup identificar la sub-orden exacta cuando el body no lo indica. | Diseño §2.9 |
| ~~F21~~ | ~~`HUENSA-001_openapi_V3.yaml` se referencia en Diseño (§2.4, §2.6, §5) y en la fila v3.2 de §1 como el contrato "promovido"/canónico, pero no existía como artefacto en el repositorio del proyecto.~~ — **Resuelto 2026-07-30:** el archivo fue agregado al proyecto. | Diseño §2.4, §2.6, §5 |
