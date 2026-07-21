package com.siman.ensambles.unogroup.service;

import lombok.Builder;
import lombok.Getter;

/**
 * Detalle de un único intento HTTP hacia Solution One (AUTH_TOKEN,
 * UPLOAD_CREATE o UPLOAD_UPDATE) — se acumula en memoria durante el
 * procesamiento y se reporta completo en el callback hacia orquestador-app
 * (Diseño §2.4/§2.11).
 */
@Getter
@Builder
public class IntentoRegistrado {
    private int numero;
    private String tipoPeticion;   // AUTH_TOKEN | UPLOAD_CREATE | UPLOAD_UPDATE
    private Integer codigoHttp;    // null si la llamada ni siquiera obtuvo respuesta (timeout/red)
    private int duracionMs;
    private boolean esReintento;
    private boolean exitoso;
    private String errorMensaje;
}
