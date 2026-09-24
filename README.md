# Carrier Entitlement & CAMARA Validator (APK Android)

Aplicación Android nativa desarrollada para validar y depurar entornos móviles cerrados (sin internet, sin RAN) compuestos por un **Entitlement Server (ECS)** bajo **GSMA TS.43 (`ap2014` / Sección 13.1.2)** y un Gateway bajo estándares **CAMARA Open Gateway (`/number-verification/v2/verify`)**, utilizando una **SIM Card física real** provisionada en el HSS (PyHSS).

---

## 📱 Características Principales

1. **Autenticación Criptográfica con SIM Física Real**:
   - Integra la API nativa `TelephonyManager.getIccAuthentication(APPTYPE_USIM, AUTHTYPE_EAP_AKA)`.
   - Extrae automáticamente el **RAND** y **AUTN** del reto devuelto por el Entitlement Server en la **Ronda 1**.
   - Delega en el chip de la tarjeta SIM el cálculo del secreto \(RES\), validación de secuencia \(SQN\) y comprobación criptográfica con su \(K\) y \(OPc\).
   - Ensambla y envía el paquete `EAP-Response/AKA-Challenge` en la **Ronda 2** (RFC 4187).
   - Incluye asistente integrado para dispositivos con **Root (su)** para auto-concederse el permiso `MODIFY_PHONE_STATE` con un solo toque.

2. **Validación de Identidad TS.43 ap2014 (Sección 13.1.2)**:
   - Emite la solicitud estándar de *Phone Number Information*.
   - Canjea el `TemporaryToken` emitido por el ECS contra el endpoint de verificación `VerifyPhoneNumber` contrastando el número MSISDN.
   - Botón **1-Click Flujo E2E** para ejecutar de punta a punta las 3 rondas en segundos.

3. **Validador CAMARA Number Verification (v2.1.0)**:
   - Prueba interactiva del endpoint `POST /number-verification/v2/verify`.
   - Envío de número en formato E.164 (`+5411...`), correlator UUID y Bearer Token.
   - Indicador visual inmediato de coincidencia: `devicePhoneNumberVerified: true / false`.

4. **Visor Wire-Level HTTP en Tiempo Real**:
   - Registro de todas las transacciones HTTP sin necesidad de recurrir a `logcat`.
   - Vista expandible con método, URL, latencia (ms), cabeceras de petición, payloads, cabeceras de respuesta y cuerpo JSON/XML formateado.
   - Botón **Copiar cURL** para reproducir cualquier petición exacta desde la terminal de una PC.

5. **Tráfico en Texto Claro (HTTP Debugging)**:
   - Configuración explícita `cleartextTrafficPermitted="true"` y `usesCleartextTraffic="true"` para permitir inspección total con Wireshark o tcpdump en el servidor.

---

## 🚀 Instalación y Despliegue

El APK compilado y verificado se encuentra disponible en:
```
build/outputs/apk/debug/app-debug.apk
```

### Paso 1: Conectar el teléfono por USB
Asegúrate de tener habilitadas las **Opciones de desarrollador** y la **Depuración USB** en el terminal.

### Paso 2: Instalar el APK mediante ADB
Desde la terminal de tu máquina:
```bash
adb install -r build/outputs/apk/debug/app-debug.apk
```

### Paso 3: Configurar los Túneles ADB Reverse (Conectividad sin RAN)
Como el entorno no dispone de RAN y el servidor corre sobre el puerto `18080` (Entitlement Server) y `8081` (OpenGateway), ejecuta:
```bash
# Redirige el tráfico del teléfono hacia el Entitlement Server en tu PC/SSH
adb reverse tcp:18080 tcp:18080

# Redirige el tráfico hacia el OpenGateway CAMARA
adb reverse tcp:8081 tcp:8081
```

*(Si usas el túnel SSH persistente hacia el servidor OCI, el tráfico viajará automáticamente: Dispositivo USB → PC Local → Túnel SSH → Contenedores Docker).*

### Paso 4 (Opcional): Conceder Permiso USIM por ADB (si el teléfono no tiene Root)
Si el terminal de testing no está rooteado pero tienes acceso ADB:
```bash
adb shell pm grant com.carrier.entitlement.validator.debug android.permission.MODIFY_PHONE_STATE
```
*(Si el teléfono cuenta con Root `su`, simplemente presiona el botón **"Conceder Permiso USIM vía Root"** dentro de la pestaña `SIM / HW` de la aplicación).*

---

## 📋 Guía de Uso de la Aplicación

La aplicación cuenta con 5 pestañas en la barra inferior:

### 1. Pestaña `Conexión`
- Configura las URLs base (por defecto `http://127.0.0.1:18080` para TS.43 y `http://127.0.0.1:8081` para CAMARA).
- Presiona **Health Check** en ambos servicios para comprobar que responden en verde.
- Dispone de botones rápidos para copiar los comandos `adb reverse` al portapapeles.

### 2. Pestaña `TS.43`
- Presiona **"Leer de SIM"** para autocompletar el IMSI y MCC/MNC desde la tarjeta SIM física insertada.
- Presiona el botón verde **"EJECUTAR VALIDACIÓN E2E (1-CLICK)"**:
  - `[1/3]` Envía el NAI raíz al Entitlement Server (`AcquireTemporaryToken`).
  - `[2/3]` Pasa el desafío EAP (RAND/AUTN) a la SIM física y envía la respuesta EAP calculada con el secreto \(RES\), recibiendo el `TemporaryToken`.
  - `[3/3]` Contrasta el MSISDN contra el HSS (`VerifyPhoneNumber`), mostrando el resultado de coincidencia (`OperationResult=1`).
- También puedes ejecutar cada paso individualmente para inspeccionar los datos intermedios.

### 3. Pestaña `CAMARA`
- Ingresa el número telefónico objetivo (`+541179999999`) y el token Bearer.
- Presiona **"VERIFICAR CON CAMARA"**.
- Observa la respuesta del gateway: estado HTTP, correlator y `devicePhoneNumberVerified`.

### 4. Pestaña `Trazas`
- Revisa el historial de peticiones HTTP enviadas.
- Toca cualquier tarjeta para expandir las cabeceras y el cuerpo de respuesta.
- Presiona **"Copiar cURL"** para pegar la petición en tu consola de Linux/macOS.

### 5. Pestaña `SIM / HW`
- Visualiza el estado en tiempo real de la tarjeta SIM física (IMSI, SPN, operador, línea).
- Estado de permisos del sistema y disponibilidad de `su`.
- Identificadores de hardware reportados en los estándares GSMA TS.43 (`terminal_vendor`, `terminal_model`, `terminal_sw_version`).

---

## 🛠️ Compilación y Pruebas Unitarias

Para volver a compilar el proyecto o ejecutar los tests unitarios:
```bash
./gradlew testDebugUnitTest assembleDebug
```
