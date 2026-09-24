# Handoff del Proyecto: Carrier Entitlement & CAMARA Validator

**Fecha de Cierre:** 24 de Septiembre de 2026  
**Última Actualización:** 00:19 ART  
**Repositorio GitHub:** [https://github.com/g-gruszka/carrier-entitlement-validator](https://github.com/g-gruszka/carrier-entitlement-validator)  
**Rama Activa:** `main`  
**Dispositivo:** Motorola Edge 30 Neo (XT2245-1 / `miami`), Android 14 (Build `U1SM34.29-37-3`).  
**Identificador ADB:** `NIAR420318`

---

## 1. Estado Actual del Sistema

1. **Aplicación Android Nativa:**
   - Totalmente implementada en Jetpack Compose + Material 3 + OkHttp.
   - Motor criptográfico **3GPP Milenage f2** integrado en [EapAkaEngine.kt](file:///Users/ggruszka/Documents/antigravity/focused-archimedes/app/src/main/java/com/carrier/entitlement/validator/sim/EapAkaEngine.kt), permitiendo calcular `RES`, `K_aut` y `AT_MAC` sin requerir permisos de sistema de Android ni root.
   - Cliente **GSMA TS.43 (`ap2014`)** ([Ts43Client.kt](file:///Users/ggruszka/Documents/antigravity/focused-archimedes/app/src/main/java/com/carrier/entitlement/validator/data/network/Ts43Client.kt)) corregido y testeado.
   - Cliente **CAMARA Number Verification v2.1.0** ([CamaraClient.kt](file:///Users/ggruszka/Documents/antigravity/focused-archimedes/app/src/main/java/com/carrier/entitlement/validator/data/network/CamaraClient.kt)) configurado.
   - Visualizador de trazas en vivo a nivel de cable (wire-level) mediante interceptor OkHttp en memoria.

2. **Infraestructura de Laboratorio (Cloud OCI):**
   - Core HSS/AAA y Entitlement Server en Docker:
     - `entitlement_server` escuchando en puerto `18080`.
     - `opengateway` escuchando en puerto `8081`.
     - `aaa_server` y `pyhss` (MySQL Core).
   - Túnel SSH persistente desde Mac hacia `ubuntu@oci-gg` con flags `-L 18080:localhost:18080 -L 8081:localhost:8081`.
   - Redirección inversa ADB activa en el teléfono:
     - `tcp:18080 -> tcp:18080` (Entitlement Server).
     - `tcp:8081 -> tcp:8081` (CAMARA Gateway).

3. **Credenciales del Suscriptor Activo:**
   - **IMSI:** `722340390000126` (auc_id 6 en PyHSS)
   - **MSISDN:** `541170000005`
   - **Ki:** `51A609FE8A3B18CEE53A5EB2F3D6C051`
   - **OPc:** `A7695F045F0488396480353433A90007`
   - **NAI Raíz:** `0722340390000126@nai.epc.mnc034.mcc722.3gppnetwork.org`
   - **Requestor ID TS.43:** `00000000-0000-4000-8000-0000000000b1`

---

## 2. Lo que Quedó Validado Hoy

- **Ronda 1 TS.43 (EAP-Init):** HTTP 200 OK. Recepción de `RAND` y `AUTN`.
- **Ronda 2 TS.43 (EAP-Response):** HTTP 200 OK. Derivación Milenage exitosa en el teléfono. El servidor reporta:
  `✅ Autenticación EAP-AKA Exitosa (Diameter 2001)`.
  Emisión exitosa del `TemporaryToken` con TTL de 300 segundos.
- **Ronda 3 TS.43 (VerifyPhoneNumber):** Resuelto el bug del error HTTP 400. El servidor valida contra el HSS y responde con `<parm name="OperationResult" value="1"/>` confirmando la coincidencia del número sin exponerlo.
- **Depósito del Código:** Repositorio creado y subido a GitHub en `g-gruszka/carrier-entitlement-validator`.
- **Informes Técnicos:**
  - `docs/informe_ejecucion_laboratorio_ts43.md`: Informe completo del laboratorio y resolución técnica.
  - `docs/informe_gms_carrier_validation_camara.md`: Informe sobre GMS, proceso de whitelist, sandboxes y agregadores CAMARA alternativos a Firebase.

---

## 3. Próximos Pasos para Mañana

1. **Validación Visual de Ronda 3 y CAMARA en el Dispositivo:**
   - Abrir la app en el teléfono y verificar que la ejecución 1-Click finalice con `OperationResult=1`.
   - Navegar a la pestaña **`CAMARA`** e invocar la verificación de número (`POST /number-verification/v2/verify`) con `+541170000005` para corroborar el flujo contra el gateway OpenGateway.
2. **Pruebas de Casos de Borde (Edge Cases):**
   - Probar un número incorrecto (mismatch) en TS.43 y CAMARA y verificar que el sistema responda de forma segura `OperationResult=0` y `devicePhoneNumberVerified: false`.
   - Probar expiración del `TemporaryToken` (esperar 300s o forzar token inválido) para validar la respuesta `HTTP 511 Network Authentication Required`.
3. **Exploración de la Integración con GMS / Sandboxes:**
   - Evaluar la viabilidad de registrar el endpoint del ECS en un proyecto de prueba de Google Cloud Console para validar el flujo a través de Google Play Services en un track interno cerrado (*Closed Testing Track*).
4. **Desacople e Integración con Agregadores:**
   - Probar la orquestación del BFF (`services/personal_pay_bff`) o mock de agregador CAMARA para simular el consumo de terceros sin Firebase.

---

## 4. Comandos de Reconexión Rápida

Si se reinicia el entorno o la Mac:

```bash
# 1. Reconectar túnel SSH con el Core (en segundo plano o terminal dedicada):
ssh -i ~/.ssh/ssh-key-2026-06-18.key -o ExitOnForwardFailure=yes -o ServerAliveInterval=60 -N \
  -L 18080:localhost:18080 -L 8081:localhost:8081 -L 8085:localhost:8085 -L 8088:localhost:8088 ubuntu@oci-gg

# 2. Restablecer túneles ADB hacia el teléfono:
adb reverse tcp:18080 tcp:18080
adb reverse tcp:8081 tcp:8081

# 3. Verificar estado de los servicios desde el teléfono:
adb shell curl -s http://127.0.0.1:18080/api/health
adb shell curl -s http://127.0.0.1:8081/health

# 4. Lanzar la aplicación en el dispositivo:
adb shell am start -n com.carrier.entitlement.validator.debug/com.carrier.entitlement.validator.MainActivity
```
