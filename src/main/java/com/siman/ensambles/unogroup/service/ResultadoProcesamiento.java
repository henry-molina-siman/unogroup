package com.siman.ensambles.unogroup.service;

import java.util.List;

/**
 * Resultado completo de intentar subir un archivo a Solution One: si se
 * logró (201 recibido) y la lista completa de intentos (incluyendo
 * AUTH_TOKEN), para reportarlos tal cual en el callback.
 */
public record ResultadoProcesamiento(boolean exitoso, List<IntentoRegistrado> intentos) {
}
