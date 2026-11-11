# UITV implementation — Java only

`com.dpw.fos.uitv` replaces the supplied Python/pika adapter. No subprocess, Python interpreter or embedded script is used.

## Configuration

| Environment variable | Purpose/default |
|---|---|
| UITV_RABBIT_HOST | Broker hostname; default localhost |
| UITV_RABBIT_PORT | Explicit broker port; default 5671 |
| UITV_RABBIT_USER | Required broker account |
| UITV_RABBIT_PASSWORD | Required broker secret |
| UITV_RABBIT_VHOST | Virtual host; default `/` |
| UITV_RABBIT_TLS | Default true; false supports explicitly configured plain AMQP |

The pasted script used plain AMQP on port 5671. Port number alone does not determine TLS: set `UITV_RABBIT_TLS=false` explicitly to match that broker in a non-production environment. Production requires TLS. Java uses the JVM truststore and hostname verification, never a trust-all manager. Supply a trusted private CA via approved JVM truststore configuration when needed.

Additional `fos.uitv` keys: `available`, `input-queue` (tenant51.telemetry), `output-exchange` (dpwfms.business.v1), `routing-key` (telemetry.asset.position.v1), defaults for vehicle/equipment/fuel type, both low thresholds (20), max-bytes (262144) and confirm-timeout-ms (5000). The admin switch controls persisted runtime enablement, not broker secrets. Never put broker credentials in browser forms, logs or committed YAML.

## Local RabbitMQ demo

```bash
export UITV_RABBIT_USER='fos-demo'
export UITV_RABBIT_PASSWORD='choose-a-local-broker-password'
export UITV_RABBIT_HOST='localhost'
export UITV_RABBIT_PORT='5672'
export UITV_RABBIT_TLS='false'
docker compose --profile uitv up -d rabbitmq
```

Start FOS with these variables and the normal local DB/password variables. Open RabbitMQ management at http://localhost:15672 with the configured broker account. Before sending telemetry, create a durable receiving queue and bind it to `dpwfms.business.v1` with routing key `telemetry.asset.position.v1`. The bridge declares the input queue and output topic exchange on connection. You can enable it first to create the exchange, then bind the receiving queue before publishing input.

Enable UITV in `/admin`. Publish a synthetic JSON object/array to queue `tenant51.telemetry` via the default exchange. Inspect the receiving queue for array output. Missing bindings deliberately cause mandatory returns and input requeue; they do not count as success. Preview in FOS does not need RabbitMQ and does not publish.

## Transformation parity

- Prefer EnergySource, then VehicleType, then configured default. ELEC/ELECTRIC/EV → ELECTRIC; FUEL/DIESEL/PETROL → FUEL.
- Prefer top-level fuel/battery percentages in the supplied alias order, then real CAN values. Direct CAN mappings and named records are supported, with punctuation/case-insensitive keys.
- Accept finite 0–100 numbers or numeric strings with optional `%`. Zero is valid. Boolean, NaN, infinity, invalid and out-of-range percentages become unavailable. Low flag is true at or below the configured threshold.
- No battery state of charge is inferred from voltage. Preserve BatteryVoltage separately. Electric records have null fuel fields; fuel records have null battery percentage/low fields.
- Map Operation T1/Engineering T1, Operation T2 and Operation T3 to their terminals. Preserve normalized inside/outside and other Mapped locations. Unknown locations become OUTSIDE.
- Preserve all four default availability flags and supplied extra flags; recompute FuelDataAvailable from valid fuel percentage and vehicle type.
- Always emit an array. Apply one **current machine timestamp, seconds precision and explicit offset** to the whole message. Preserve source LastUpdatedTime. This intentionally replaces input Timestamp as the Python code does; unlike other FOS business contracts, UITV is a transformation.
- To use Dubai machine time explicitly, start Java with `-Duser.timezone=Asia/Dubai`. UTC clocks render `+00:00`; Dubai clocks render `+04:00`.

Output fields: AssetID, AssetNumber, Make, Model, VehicleType, EquipmentType, FuelType, Timestamp, LastUpdatedTime, Latitude, Longitude, Speed, BatteryVoltage, BatteryLevelPercent, LowBattery, FuelLevelPercent, LowFuel, FuelConsumptionRate, TotalFuelUsed, EngineHours, Location, DataAvailabilityFlag and CANParameters.

## Delivery semantics

A dedicated single worker fetches one unacknowledged input at a time, transforms it, publishes persistent JSON with `mandatory=true`, checks publisher confirmation and returned-message status, then acknowledges input. Message ID, correlation ID and headers are preserved. Broker connection/processing failures retry with backoff; input is requeued. Poison records are not silently discarded. Resolve/quarantine poison inputs through approved broker operations if they block the stream.

This is **at-least-once**, matching the source design: a crash after output confirmation but before input acknowledgement can duplicate output. Downstream consumers must deduplicate where required. A redelivery can carry a newly generated current Timestamp. Counters are per-process attempt/confirmation counts, not unique business effects. All broker credentials remain externally supplied; no connection to the supplied live broker was made during implementation.
