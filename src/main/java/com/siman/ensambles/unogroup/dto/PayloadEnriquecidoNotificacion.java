package com.siman.ensambles.unogroup.dto;

import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/**
 * Representa el body de {@code SolicitudNotificacion.payloadEnriquecido}
 * (contrato OpenAPI). El YAML modela esto como un {@code oneOf} entre
 * {@code PayloadEnriquecido} (creación) y {@code PayloadEnriquecidoActualizacion}
 * (actualización) SIN discriminator a nivel de este objeto — el campo
 * {@code accion} de {@code SolicitudNotificacion} (ver
 * {@code SolicitudNotificacionRequest}) es lo que distingue la rama, no la
 * forma del payload. Se modela como un único DTO "unión" con todos los
 * campos de ambas ramas, todos nullable salvo {@code ordenId}/{@code estado}.
 * {@code sku} solo viaja en creación (Diseño §4.4) — en actualización el sku
 * se identifica por el nombre de archivo/path, no por un campo del body.
 */
@Getter
@Setter
public class PayloadEnriquecidoNotificacion {

    private String ordenId;
    private String sku;                      // presente SOLO en creación -> null implica actualización
    private String numeroFactura;
    // TBD: nombre de campo API de origen sin confirmar, incluso en el
    // mapeo ya confirmado hacia Solution One (external_reference_alt_2,
    // Diseño §4.3). Puede llegar null; nunca bloquea el resto del mapeo.
    private String numeroPedidoEcommerce;
    private String nombreCliente;
    private String telefonoCliente;
    private String correoCliente;
    private Boolean clienteVip;
    private String direccion;
    private String ciudad;
    private String departamento;
    private String pais;
    private Double latitud;
    private Double longitud;
    private String tipoServicio;
    private String ubicacionServicio;
    private String referenciaUbicacionServicio;
    private String marcaProducto;
    private String categoriaProducto;
    private String descripcionProducto;
    private Integer cantidad;
    private String estado;                   // TrackingStatus, común a ambas ramas
    private Instant fechaOrden;
    private Instant fechaPlanificadaDespacho;
    private Instant fechaPlanificadaEntrega;
    private Instant fechaRealDespacho;        // presente SOLO en actualización
    private Instant fechaRealEntrega;         // presente SOLO en actualización
}
