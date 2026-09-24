# Arquitectura de Validación de Identidad Móvil vía Google Mobile Services (GMS), Whitelist, Sandboxes y Federación CAMARA

**Fecha:** 24 de Septiembre de 2026  
**Documento:** Informe Técnico de Arquitectura y Gobernanza  
**Área:** Identidad Digital Móvil, GSMA TS.43 y CAMARA Open Gateway  

---

## 1. Funcionamiento de la Validación de Número mediante GMS en Android

En el ecosistema Android comercial, Google implementa el cliente de **Carrier Entitlement** directamente dentro del proceso privilegiado de **Google Play Services (GMS)** (paquete `com.google.android.gms`), interactuando con el framework de telefonía de Android a nivel de sistema (`com.android.phone`).

```
+-----------------------------------------------------------------------------------+
|                            DISPOSITIVO ANDROID COMERCIAL                          |
|                                                                                   |
|  [ Aplicación Cliente / App Bancaria / Telco App ]                                |
|        |                                                                          |
|        | 1. Request Verification (Google Identity / PNV API)                     |
|        v                                                                          |
|  [ Google Play Services (GMS) - Proceso Privilegiado de Sistema ]                |
|        |                                                                          |
|        | 2. TelephonyManager.getIccAuthentication() / ARA-M (Privilegio Sistema)  |
|        v                                                                          |
|  [ SIM Card Física / eSIM ] ---> Ejecuta Milenage f2 (APDU UICC Segura)           |
|        |                                                                          |
|        | 3. EAP-AKA Exchange (GSMA TS.43 ap2014 / ap2015)                         |
|        v                                                                          |
+--------+--------------------------------------------------------------------------+
         |
         | 4. HTTPS (TLS Estricto)
         v
+-----------------------------------------------------------------------------------+
|                        INFRAESTRUCTURA DEL OPERADOR / CORE                         |
|                                                                                   |
|  [ Entitlement Server (ECS) ] <---> [ 3GPP AAA ] <---> [ HSS / UDM (Core) ]       |
|        |                                                                          |
|        | 5. Emite TemporaryToken firmado o confirma Identidad                     |
|        v                                                                          |
|  [ GMS Backend / Google Identity Cloud ]                                          |
|        |                                                                          |
|        | 6. Emite Assertion Token (JWT firmado por clave privada de Google)       |
|        v                                                                          |
+--------+--------------------------------------------------------------------------+
         |
         v
+-----------------------------------------------------------------------------------+
|                         BACKEND DEL AGREGADOR / CAMARA                            |
|  Recibe y valida la aserción criptográfica de Google sin exponer datos de red.     |
+-----------------------------------------------------------------------------------+
```

### Mecanismos Clave de GMS:
1. **Acceso Privilegiado al Hardware:** GMS cuenta con la firma de plataforma o el permiso `READ_PRIVILEGED_PHONE_STATE`. Puede enviar comandos APDU `EAP-AKA` directamente a la tarjeta SIM sin necesidad de root ni de que la app cliente posea permisos intrusivos.
2. **Cliente TS.43 Nativo:** GMS incluye un cliente TS.43 (`ap2014` para verificación de número y `ap2015` para Web/VoLTE entitlements).
3. **Generación de Aserciones Firmadas:** Al concluir la autenticación con el Entitlement Server del operador, GMS emite una prueba criptográfica (Assertion Token / JWS) que el backend de la aplicación puede contrastar con los servidores de Google.

---

## 2. Proceso de Onboarding y Whitelist en Google Mobile Services

Para que una aplicación o un operador pueda hacer uso de las APIs de validación de identidad y carrier entitlements de Google, es mandatorio completar el proceso de admisión:

### A. Lado del Operador Móvil (MNO / MVNO)
1. **Google Carrier Console (Partner Portal):**
   - El operador debe registrarse formalmente como Carrier Partner ante Google.
   - Declaración de los rangos de PLMN (`MCC` + `MNC`) de las tarjetas SIM y perfiles eSIM propios.
   - Registro de los FQDNs del Entitlement Server de producción y homologación (requiere HTTPS con certificados emitidos por CAs comerciales reconocidas por Google, pinning TLS estricto).
2. **Homologación Técnica TS.43:**
   - Superar la suite de pruebas de Google Carrier Test Suite (CTS/GTS).
   - Validar tiempos de respuesta (< 800 ms para Ronda 1 y Ronda 2 EAP-AKA).
   - Certificación de políticas de rotación de tokens y oráculo booleano sin fuga de MSISDN según directrices de privacidad de Android.

### B. Lado del Desarrollador / Agregador de Aplicaciones
1. **Google Cloud Console & OAuth 2.0:**
   - Creación de un proyecto en Google Cloud Console vinculando la organización verificada (mediante número D-U-N-S).
   - Habilitación de las APIs de identidad correspondientes (*Google Identity Services for Android* / *Phone Number Verification API*).
2. **Vinculación Criptográfica de la App:**
   - Registro del `Application ID` (Package Name, ej: `com.carrier.entitlement.validator`).
   - Registro de la huella digital criptográfica SHA-256 del certificado con el que se firma el APK (Keystore de release o firma gestionada de Google Play).
3. **Aprobación de Política de Uso:**
   - Justificación de negocio para la verificación de número celular (prevención de fraude, cumplimiento KYC financiero, etc.).

---

## 3. Sandboxes y Entornos de Prueba sin Publicación Comercial

Es posible validar todo el ciclo de vida sin necesidad de publicar una aplicación comercial en producción en Google Play Store:

```
+-------------------------------------------------------------------------------+
|                            ESTRATEGIAS DE SANDBOX                             |
+-------------------------------------------------------------------------------+
| 1. Internal App Sharing (Uso Inmediato)                                       |
|    - Carga directa del APK/AAB firmado.                                       |
|    - Enlace de descarga accesible solo por cuentas Google autorizadas.        |
|    - No requiere pasar por la revisión editorial de Play Store.               |
+-------------------------------------------------------------------------------+
| 2. Closed Testing Track (Pruebas Cerradas)                                    |
|    - Gestión de listas de probadores (Google Groups o correos individuales).  |
|    - Vincula GMS real con las credenciales registradas en Cloud Console.      |
|    - Permite actualizar versiones en minutos para los dispositivos del lab.   |
+-------------------------------------------------------------------------------+
| 3. Test Providers & Números Ficticios en Play Console                         |
|    - Configuración de números de teléfono de prueba con respuestas predefinidas|
|      (simulación de éxito 200, mismatch o timeout).                          |
|    - Permite verificar el comportamiento de la UI sin consumir transacciones  |
|      del HSS en etapas preliminares.                                          |
+-------------------------------------------------------------------------------+
| 4. Test UICC (SIM de Laboratorio en Modo Desarrollador)                       |
|    - En dispositivos Pixel o Motorola con Developer Options activadas,        |
|      Android permite forzar la selección de perfiles de operador de test      |
|      mediante SIMs con MCC/MNC de prueba (ej. 001/01 o 722/034).              |
+-------------------------------------------------------------------------------+
```

---

## 4. Arquitectura CAMARA con Agregadores Alternativos a Firebase

En arquitecturas empresariales y telco de grado productivo, **no es obligatorio ni común depender de Firebase**. La especificación **CAMARA Open Gateway** (liderada por GSMA y Linux Foundation) está expresamente diseñada para permitir la interoperabilidad con agregadores globales independientes:

```
+--------------------+           +----------------------+           +--------------------+
|  Android Client    |           |  Agregador CAMARA    |           |  MNO Core Network  |
|  (Tu Aplicación)   |           |  (Vonage / Infobip)  |           |  (Personal / PyHSS)|
+---------+----------+           +----------+-----------+           +---------+----------+
          |                                 |                                 |
          | 1. Init Verify (+541170000005)  |                                 |
          +-------------------------------->|                                 |
          |                                 | 2. CAMARA POST /verify          |
          |                                 |    (Client Credentials OAuth2)  |
          |                                 +-------------------------------->|
          |                                 |                                 | 3. HSS / Diameter
          |                                 |                                 |    Check
          |                                 | 4. { "devicePhoneNumberVerified"|
          |                                 |      : true }                   |
          |                                 |<--------------------------------+
          | 5. Token de Sesión Verificado   |                                 |
          |<--------------------------------+                                 |
```

### Agregadores de APIs Telco Alternativos Destacados:
1. **Vonage (Ericsson):**
   - Es el mayor impulsor de la iniciativa *Global Network Platform* de Ericsson junto a las principales telcos del mundo.
   - Dispone de soporte nativo para **CAMARA Number Verification v2.x** y **SIM Swap API**.
   - Provee SDKs para Android y Web que interactúan con su plataforma sin intermediación de Firebase.
2. **Infobip:**
   - Miembro activo del Open Gateway working group.
   - Ofrece el servicio *Mobile Identity* totalmente alineado con las interfaces REST de CAMARA.
   - Soporta validación basada tanto en Silent Network Authentication (SNA vía datos celulares) como en APIs server-side.
3. **Sinch / Twilio / Boku:**
   - Agregadores con cobertura global y contratos directos con los operadores de telecomunicaciones para ejecutar verificación de red directa.

### Modelo de Disparo Desacoplado (Sin Firebase):
- **Front-end Móvil:** La aplicación en Android inicia el flujo comunicándose directamente con el **BFF (Backend-For-Frontend)** propio o con el SDK del agregador seleccionado (Vonage/Infobip).
- **Consumo CAMARA Puro:**
  - El agregador autentica contra el Gateway del operador utilizando tokens **OAuth 2.0 con Client Credentials** o el perfil **OIDC CIBA** (Client Initiated Backchannel Authentication).
  - El agregador consume `POST /number-verification/v2/verify` enviando el número reclamado en formato E.164 (`+541170000005`).
  - La respuesta de la red devuelve un booleano puro (`devicePhoneNumberVerified: true/false`), garantizando privacidad total y cumplimiento regulatorio GDPR / Ley de Protección de Datos Personales sin compartir el IMSI ni claves criptográficas con terceros.

---

## 5. Conclusiones y Hoja de Ruta Recomendada

1. **Laboratorio Actual vs Producción:**
   - El laboratorio construido con el Motorola Edge 30 Neo valida la **capa física y de transporte criptográfico real (Milenage f2 / TS.43 / EAP-AKA)** en su forma más pura.
   - Esto demostró que el Entitlement Server, el AAA y PyHSS funcionan con estricta adherencia a los estándares 3GPP y GSMA.
2. **Transición a GMS:**
   - Para llevar esta solución a producción comercial sin requerir la clave `Ki/OPc` en la app, la ruta oficial es la integración con **Google Play Services Phone Number Verification** tras registrar el FQDN del Entitlement Server en Google Carrier Console.
3. **Agregación CAMARA:**
   - Se recomienda desacoplar la arquitectura de Firebase y federar la verificación con agregadores globales alineados a CAMARA (ej: Vonage o Infobip), permitiendo un modelo multi-operador transparente.
