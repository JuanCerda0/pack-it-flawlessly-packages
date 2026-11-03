# Contratos de mensajería de PackIt

## Convenciones comunes

Los cuerpos son JSON UTF-8. `eventId` es un UUID único por mensaje; los consumidores lo usan para evitar duplicados. `schemaVersion` comienza en `1` y versiona el cuerpo, no la API ni la base de datos. `occurredAt` es una fecha ISO 8601 en UTC. `packageId` es el ID de Paquetes. Los campos ausentes opcionales se representan como `null`; nunca se transmite un JWT.

| Destino | Exchange | Tipo | Routing key | Cola |
| --- | --- | --- | --- | --- |
| Historial | `packit.package.events` | `topic` | `package.created`, `package.updated`, `package.status.changed`, `package.item.added`, `package.item.updated` | `history.package` |
| Documentos ingreso | `packit.document.commands` | `direct` | `document.intake` | `documents.intake` |
| Documentos despacho | `packit.document.commands` | `direct` | `document.dispatch` | `documents.dispatch` |

## Eventos para Historial

`type` toma uno de estos valores: `PACKAGE_CREATED`, `PACKAGE_UPDATED`, `PACKAGE_STATUS_CHANGED`, `PACKAGE_ITEM_ADDED`, `PACKAGE_ITEM_UPDATED`. `actorId` es el claim `sub` del access token de Cognito, o `null` si la operación no tiene usuario autenticado. `before` y `after` son objetos con los campos relevantes del cambio; en una creación, `before` es `null`. Las ediciones de paquete contienen `name`, `reference` y `description`; los cambios de estado contienen `status`; los productos contienen `itemId`, `sku`, `productName` y `quantity`. La creación de paquete incluye además `status` en `after`.

```json
{
  "eventId": "6fd2a37c-c0d8-4e5d-9c10-b91e6ae5c2bd",
  "schemaVersion": 1,
  "occurredAt": "2026-11-03T15:10:00Z",
  "packageId": 42,
  "type": "PACKAGE_STATUS_CHANGED",
  "actorId": "cognito-user-sub",
  "before": { "status": "RECEIVED" },
  "after": { "status": "PREPARING" }
}
```

## Trabajos para Documentos

`documentType` es `INTAKE` al pasar a `PREPARING` y `DISPATCH` al pasar a `READY`. `package` es una instantánea tomada en esa transición: nombre, referencia, estado e ítems. Cada ítem contiene `sku`, `productName` y `quantity`. Documentos no consulta la base de Paquetes ni modifica un PDF ya emitido.

```json
{
  "eventId": "d975c1fb-f850-4c14-9821-8a77dabcbaba",
  "schemaVersion": 1,
  "occurredAt": "2026-11-03T15:10:00Z",
  "packageId": 42,
  "documentType": "INTAKE",
  "package": {
    "name": "Pedido de cocina",
    "reference": "PF-2026-042",
    "status": "PREPARING",
    "items": [
      { "sku": "TAZA-01", "productName": "Taza", "quantity": 2 }
    ]
  }
}
```

Cada mensaje se guarda en `outbox_events` en la misma transacción que el cambio del paquete. El publicador lo envía con confirmación de recepción y enrutamiento; si no obtiene ambas, conserva la fila pendiente para reintento. El contrato no exige que el consumidor reciba los mensajes en orden ni excluye duplicados tras una falla entre la confirmación y el registro en PostgreSQL; los consumidores deduplicarán por `eventId`.
