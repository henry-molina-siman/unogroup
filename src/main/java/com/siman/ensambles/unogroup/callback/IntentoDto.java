package com.siman.ensambles.unogroup.callback;

import lombok.Builder;
import lombok.Getter;

/** Espejo de salida de service.IntentoRegistrado, para el body del callback. */
@Getter
@Builder
public class IntentoDto {
    private int numero;
    private String tipoPeticion;
    private Integer codigoHttp;
    private Integer duracionMs;
    private boolean esReintento;
    private boolean exitoso;
    private String errorMensaje;
}
