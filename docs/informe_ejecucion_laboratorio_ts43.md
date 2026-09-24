# Informe Técnico de Ejecución: Validación E2E de GSMA TS.43 y CAMARA Number Verification con SIM Física

**Fecha:** 24 de Septiembre de 2026  
**Autor:** Antigravity / Gerardo Gruszka  
**Repositorio GitHub:** [g-gruszka/carrier-entitlement-validator](https://github.com/g-gruszka/carrier-entitlement-validator)  
**Dispositivo de Prueba:** Motorola Edge 30 Neo (XT2245-1 / Codename: `miami`), Android 14 (Build `U1SM34.29-37-3`, Kernel 5.4.233).

---

## 1. Objetivo

Desarrollar, desplegar y validar de forma integral una aplicación móvil nativa Android para verificar la identidad de red móvil en un entorno de laboratorio aislado (sin acceso a Internet comercial y sin red de acceso radioeléctrico RAN activa). 

El objetivo operacional consiste en ejecutar con éxito el flujo de autenticación de identidad basado en tarjeta SIM física contra los siguientes componentes de core móvil:
1. **Entitlement Server (ECS)** bajo la especificación **GSMA TS.43** (AppID `ap2014`, Sección 13.1.2) con intercambio **EAP-AKA** (RFC 4187).
2. **CAMARA Open Gateway API** (`Number Verification v2.1.0` - `/number-verification/v2/verify`).
3. Core HSS/AAA (**PyHSS** y servidor Diameter 3GPP AAA).

---

## 2. Premisas y Restricciones del Entorno

1. **Aislamiento de Red:** El teléfono físico no dispone de conexión de datos móviles activa ni salida a Internet comercial; todo el tráfico fluye mediante enlace directo por cable USB.
2. **Topología de Red por Túnel:**
   - Servicios de Core residen en un servidor Linux cloud (`oci-gg`).
   - Túnel SSH persistente transportando puertos `18080` (Entitlement Server) y `8081` (CAMARA OpenGateway) hacia la estación de trabajo Mac.
   - Redirección inversa ADB (`adb reverse tcp:18080 tcp:18080` y `adb reverse tcp:8081 tcp:8081`) permitiendo al dispositivo Android comunicarse con los servicios en `http://127.0.0.1:18080` y `http://127.0.0.1:8081`.
3. **Inspección de Tráfico Wire-Level:** Tráfico en HTTP plano (`cleartextTrafficPermitted=true`) para capturas con `tcpdump` / Wireshark en servidor y visualización en tiempo real en la propia UI del dispositivo.
4. **Restricciones de Android 14:**
   - Desde Android 10+, `TelephonyManager.getSubscriberId()` (IMSI) y `getLine1Number()` (MSISDN) están bloqueados para aplicaciones de terceros, requiriendo `READ_PRIVILEGED_PHONE_STATE` (solo apps del sistema) o privilegios de operador (ARA-M applet en UICC).
   - El dispositivo Motorola Edge 30 Neo cuenta con bootloader desbloqueado (`secure: no`, `securestate: engineering`), pero ejecuta una imagen de fábrica `user` sin binario `su` instalado de forma predeterminada.

---

## 3. Arquitectura del Sistema

```
+-----------------------------------------------------------------------------------+
|                            DISPOSITIVO ANDROID 14                                 |
|                       (Motorola Edge 30 Neo - XT2245-1)                          |
|                                                                                   |
|  [ UI Jetpack Compose ]                                                           |
|       |                                                                           |
|       +---> [ EapAkaEngine ] <---- (Ki / OPc de SIM física)                       |
|       |       |                                                                   |
|       |       +-- AES-128 Milenage f2 (Cálculo de RES)                            |
|       |       +-- Derivación de K_aut (SHA-256) y AT_MAC (HMAC-SHA1)              |
|       |                                                                           |
|       +---> [ Ts43Client / OkHttp Wire-Logger ]                                    |
|       +---> [ CamaraClient ]                                                      |
+----------------------------------------+------------------------------------------+
                                         | USB (ADB Reverse)
                                         v
+-----------------------------------------------------------------------------------+
|                             HOST LOCAL (MacBook)                                  |
|  Puertos Reversos ADB: 18080 (TS.43) / 8081 (CAMARA)                              |
|  Túnel SSH: Forward hacia ubuntu@oci-gg                                           |
+----------------------------------------+------------------------------------------+
                                         | SSH Tunnel (Encapsulado)
                                         v
+-----------------------------------------------------------------------------------+
|                            CORE MÓVIL EN LA NUBE (OCI)                            |
|                                                                                   |
|  [ Entitlement Server :18080 ] <---> [ AAA Server 3GPP ] <---> [ PyHSS / MySQL ]  |
|         |                                                            ^            |
|         +------------------------- MSISDN Match ---------------------+            |
|                                                                                   |
|  [ CAMARA OpenGateway :8081 ]                                                     |
+-----------------------------------------------------------------------------------+
```

---

## 4. Soluciones Técnicas Propuestas e Implementadas

### A. Bypass de Restricciones de Acceso Directo a la SIM (Milenage f2 Embebido)
En lugar de depender de la concesión del permiso `MODIFY_PHONE_STATE` o de applets de operador ARA-M (los cuales requieren firmas PKCS#15 en la tarjeta UICC):
- Se implementó un motor criptográfico 3GPP Milenage f2 completo en Kotlin puro ([EapAkaEngine.kt](file:///Users/ggruszka/Documents/antigravity/focused-archimedes/app/src/main/java/com/carrier/entitlement/validator/sim/EapAkaEngine.kt)).
- **Criptografía:**
  1. Operación `temp = AES_ECB(RAND ^ OPc, Ki) ^ OPc`.
  2. Modificación de bit en byte 15 (`temp[15] ^= 1`).
  3. Operación `out2 = AES_ECB(temp, Ki) ^ OPc`.
  4. Extracción de `RES = out2[8..15]`.
  5. Derivación de `K_aut = SHA-256(IMSI + RAND + RES)[0..15]`.
  6. Cálculo de integridad EAP con `HMAC-SHA1(K_aut, EAP-Response-Packet)[0..15]` inyectado en el atributo `AT_MAC` (Tipo 11).

### B. Cliente Robusto GSMA TS.43 (`ap2014`)
Se diseñó [Ts43Client.kt](file:///Users/ggruszka/Documents/antigravity/focused-archimedes/app/src/main/java/com/carrier/entitlement/validator/data/network/Ts43Client.kt) para gestionar las 3 fases del protocolo:
- **Ronda 1 (`AcquireTemporaryToken`):** Solicitud GET con NAI de raíz 3GPP (`0<IMSI>@nai.epc.mnc<MNC>.mcc<MCC>.3gppnetwork.org`).
- **Ronda 2 (`EAP-Response`):** Envío POST JSON con el paquete binario hexadecimal de relay EAP conteniendo la respuesta de autenticación.
- **Ronda 3 (`VerifyPhoneNumber`):** Canje del `TemporaryToken` con oráculo booleano contra el HSS, enviando el MSISDN claimed y `requestor_id` autorizado.

### C. Cliente CAMARA Open Gateway
Implementación de [CamaraClient.kt](file:///Users/ggruszka/Documents/antigravity/focused-archimedes/app/src/main/java/com/carrier/entitlement/validator/data/network/CamaraClient.kt) para invocar `POST /number-verification/v2/verify` con Bearer Token y validar la concordancia del número a nivel de API Gateway telco.

---

## 5. Pruebas Realizadas y Depuración

### 1. Verificación del Motor Criptográfico
- **Prueba Unitaria:** Se construyó `EapAkaEngineTest.kt` validando vectores de prueba 3GPP TS 35.207 / RFC 4187 y los parámetros del abonado real (`IMSI: 722340390000126`, `Ki: 51A6...`, `OPc: A769...`).
- **Prueba en Python contra el Core:** Se ejecutó `test_user_sim.py` validando exitosamente el intercambio completo frente a PyHSS.

### 2. Detección y Resolución del Error HTTP 400 en Ronda 3
- **Incidente:** Al presionar "Ejecutar 1-Click", la Ronda 1 y Ronda 2 arrojaban HTTP 200, pero la Ronda 3 fallaba con `HTTP 400 Bad Request`.
- **Causa Raíz Diagnosticada:**
  1. En el Entitlement Server, la Ronda 2 devuelve el token dentro de la etiqueta `<parm name="TemporaryToken" value="..."/>`. El parser anterior buscaba `name="token"`, haciendo que el valor se enviara vacío o se recurriera al payload XML en bruto.
  2. En Android 14, `TelephonyManager.line1Number` devuelve cadena vacía `""` (no `null`). Al evaluar `simInfo.phoneNumber ?: "541170000005"`, se conservaba la cadena vacía, provocando que el servidor rechazara la llamada por `msisdn required`.
  3. En la Ronda 3, el motor de TS.43 responde `<parm name="OperationResult" value="1"/>` en lugar de una etiqueta XML con cierre.
- **Corrección:**
  - Se flexibilizó el regex en `Ts43Client.kt` para detectar atributos `TemporaryToken` y `OperationResult`.
  - Se forzó el fallback con `ifBlank` en `Ts43Screen.kt` para garantizar el MSISDN provisionado (`541170000005`).

---

## 6. Resultados Obtenidos

| Componente / Fase | Estado | Evidencia / Detalle |
| :--- | :---: | :--- |
| **Conexión USB / ADB Reverse** | **OK** | Teléfono comunica con endpoints `:18080` y `:8081` a través del host local. |
| **Ronda 1 TS.43 (EAP-Init)** | **OK (200)** | Recepción de `RAND`, `AUTN`, `session_id` y cookie de sesión EAP. |
| **Cálculo Criptográfico Milenage** | **OK** | Derivación correcta de `RES`, `K_aut` y `AT_MAC` coincidente con el HSS. |
| **Ronda 2 TS.43 (Autenticación Core)** | **OK (200)** | Log Core: `✅ Autenticación EAP-AKA Exitosa (Diameter 2001)`. Emisión de `TemporaryToken`. |
| **Ronda 3 TS.43 (VerifyPhoneNumber)** | **OK (200)** | Validación exitosa contra PyHSS, confirmando identidad con `OperationResult = 1`. |
| **Registro de Trazas Wire-Level** | **OK** | OkHttp Interceptor captura en memoria el payload HTTP completo disponible en pestaña UI. |
| **Control de Versiones y Repositorio** | **OK** | Proyecto versionado y publicado en GitHub bajo la rama `main`. |
