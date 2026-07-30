package com.siman.ensambles.unogroup.service;

import lombok.Builder;
import lombok.Getter;

/**
 * Un único ciclo de llamada HTTP saliente hacia el partner de ensamble,
 * ya con {@code request} siempre presente y exactamente uno de
 * {@code response}/{@code error} — espejo de {@code TransaccionHttp} del
 * contrato (Guía de Transacciones HTTP §2). Reemplaza a la antigua
 * {@code IntentoRegistrado} (modelo v2, campos HTTP sueltos).
 */
@Getter
@Builder
public class TransaccionRegistrada {
    private TransaccionMetadata metadata;
    private HttpRequestRegistrado request;
    private HttpResponseRegistrado response; // nullable
    private TransaccionErrorRegistrado error; // nullable
}
