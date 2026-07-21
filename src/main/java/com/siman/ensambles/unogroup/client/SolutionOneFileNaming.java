package com.siman.ensambles.unogroup.client;

import java.time.format.DateTimeFormatter;
import java.time.Instant;
import java.time.ZoneOffset;

/**
 * Algoritmo de path/nombre de archivo (Diseño §2.9, doc Implementación
 * §1.4.6/§6.4.3): la raíz depende del tipo de subida —
 * {ruta-base}/create/ para creación, {ruta-base}/update/ para
 * actualización —
 * {ruta-base}/{accion}/{fecha_envio}/{accion}_{timestamp_orden}_{external_reference}_{sku}.json
 */
public class SolutionOneFileNaming {

    private static final DateTimeFormatter FECHA_CARPETA =
        DateTimeFormatter.ofPattern("yyyyMMdd").withZone(ZoneOffset.UTC);
    private static final DateTimeFormatter TIMESTAMP_ARCHIVO =
        DateTimeFormatter.ofPattern("yyyyMMddHHmmss").withZone(ZoneOffset.UTC);

    private SolutionOneFileNaming() {
    }

    public static String construirPath(String rutaBase, String accion,
            Instant trackingOrderTime, String externalReference, String sku) {
        String fecha = FECHA_CARPETA.format(Instant.now());       // fecha de envío, no de negocio
        String timestamp = TIMESTAMP_ARCHIVO.format(trackingOrderTime); // del pedido, no "ahora"
        // sku siempre incluido — en creación Y en actualización (Diseño §9.6 F20),
        // aunque el body de actualización no lo lleve como campo.
        String nombreArchivo = String.format("%s_%s_%s_%s.json", accion, timestamp, externalReference, sku);
        return String.format("%s/%s/%s/%s", rutaBase, accion, fecha, nombreArchivo);
    }
}
