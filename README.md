# GBN Audio Lab

Aplicación cliente-servidor en Java 17 para transmitir un archivo MP3 mediante UDP, una ventana deslizante y el mecanismo de control de errores Go-Back-N (Retroceder-N). Incluye interfaz Swing, simulación de pérdida, verificación SHA-256, lectura ID3 y reproducción con JLayer.

## Funcionalidad incluida

- Datagramas UDP de **máximo 1024 bytes** (cabecera de aplicación incluida).
- Número de secuencia binario dentro del payload UDP.
- Ventana configurable; valor inicial: 5 paquetes.
- ACK acumulativo por cada paquete aceptado en orden.
- Temporizador real con `DatagramSocket.setSoTimeout(...)`.
- Retransmisión Go-Back-N desde el paquete base al vencer el temporizador.
- Descarte de datagramas fuera de orden, mal formados, de otra sesión o de otro origen.
- Archivo temporal `.part`; el MP3 final solo aparece después de recibirlo completo.
- Validación del tamaño y SHA-256 antes de leer metadata o reproducir.
- Cierre explícito `FIN` / `FIN_ACK` resistente a la pérdida del último ACK.
- Metadata ID3v2 con respaldo a ID3v1 mediante mp3agic.
- Reproducción posterior a la transferencia mediante JLayer.
- Interfaz gráfica no bloqueante con progreso, estadísticas, registro y ventana GBN.
- Modo consola opcional para pruebas.

## Estructura del proyecto

```text
src/main/java/com/redes/udp/
├── Servidor.java                 Interfaz y punto de entrada del emisor
├── Cliente.java                  Interfaz y punto de entrada del receptor
├── Lanzador.java                 Entrada principal detectada por NetBeans
├── ServidorTransferencia.java    Algoritmo de ventana deslizante y Go-Back-N
├── ClienteTransferencia.java     Recepción ordenada, ACKs y archivo temporal
├── PaqueteProtocolo.java         Codificación/validación de datagramas
├── ProtocoloConfig.java          MTU y valores predeterminados
├── MetadataAudio.java            Lectura ID3 con mp3agic
├── ReproductorAudio.java         Reproducción con JLayer
├── EscuchaTransferencia.java     Eventos para consola o interfaz
├── PanelVentana.java             Visualización didáctica de la ventana
└── TemaUI.java                   Sistema visual compartido
```

El borrador se encuentra en [`REPORTE.md`](REPORTE.md) y la especificación técnica completa en [`ESPECIFICACIONES_TECNICAS.md`](ESPECIFICACIONES_TECNICAS.md).

## Formato del número de secuencia

Un datagrama UDP solo transporta bytes. El emisor reserva cuatro bytes de la cabecera de aplicación para `seqNum` y los escribe en orden de red (big-endian) mediante `ByteBuffer.putInt(secuencia)`. El receptor usa `ByteBuffer.getInt()` en la misma posición.

```java
// Emisor: int de 32 bits -> cuatro bytes dentro del payload UDP
buffer.putInt(secuencia);

// Receptor: mismos cuatro bytes -> int de 32 bits
int secuencia = buffer.getInt();
```

No se envía el número como texto. Por ejemplo, la secuencia `258` se representa como `00 00 01 02`, siempre ocupa cuatro bytes y no depende de la codificación de caracteres.

### Datagrama de datos (máximo 1024 bytes)

```text
 0               4  5  6              14             18 19   21
+-----------------+--+--+---------------+---------------+--+-----+----------------+
| magic "GBN1"   |V |T | sessionId     | seqNum        |Fl| len | audio MP3      |
| 4 bytes         |1 |1 | 8 bytes       | 4 bytes       |1 | 2   | 0..1003 bytes  |
+-----------------+--+--+---------------+---------------+--+-----+----------------+
|<---------------- cabecera de aplicación: 21 bytes ---------------->|
|<---------------- datagrama UDP: como máximo 1024 bytes ---------------------->|
```

- `magic`: identifica el protocolo y evita interpretar tráfico ajeno.
- `V`: versión del protocolo.
- `T`: tipo de mensaje (`DATOS`).
- `sessionId`: evita mezclar una transferencia anterior con la actual.
- `seqNum`: número de secuencia de 32 bits.
- `Fl`: bit 0 igual a 1 en el último paquete.
- `len`: longitud real de la carga útil.
- carga máxima: `1024 - 21 = 1003` bytes.

El ACK contiene `magic`, versión, tipo, `sessionId` y `ackNum`, para un total de 18 bytes. Un ACK `k` confirma acumulativamente todos los paquetes desde `0` hasta `k`.

> Nota técnica: en la consigna se usa “MTU de 1024 bytes” para el tamaño máximo del datagrama de la aplicación. El código respeta 1024 bytes de payload UDP; las cabeceras UDP/IP pertenecen a capas inferiores.

## Abrir y ejecutar en NetBeans (Maven)

Requisitos: JDK 17 o posterior y una versión reciente de Apache NetBeans con soporte Maven.

1. En NetBeans, selecciona **File > Open Project**.
2. Abre la carpeta `redes_udp_gbn_mp3` (la que contiene `pom.xml`).
3. Espera a que Maven descargue `jlayer 1.0.1` y `mp3agic 0.9.1`.
4. Si NetBeans muestra otra plataforma Java, abre **Project Properties > Libraries > Java Platform** y elige JDK 17 o posterior.
5. Pulsa **Run Project (F6)**. Maven tiene declarada `com.redes.udp.Lanzador` como clase principal.
6. En el lanzador selecciona **Abrir ambos lado a lado**, o abre cada ventana por separado.
7. En el servidor, elige el MP3, revisa puerto/ventana/timeout y pulsa **Iniciar**.
8. En el cliente, indica la IP del servidor y una ruta de salida. Pulsa **Recibir**.
9. Al terminar, el cliente verifica SHA-256, muestra Artista/Álbum/Año y habilita el reproductor. La opción **Reproducir al terminar** está activada inicialmente.

Si el proyecto ya estaba abierto cuando se modificó el `pom.xml`, usa **clic derecho sobre el proyecto > Reload Project** o ciérralo y vuelve a abrirlo. Después ejecuta **Clean and Build** una vez. `Servidor.java` y `Cliente.java` conservan sus propios métodos `main`, por lo que también se pueden ejecutar con **Run File**, pero ya no es necesario para usar la aplicación.

El archivo `nbactions.xml` incluido define explícitamente las acciones **Run Project** y **Run File** mediante `exec:java`. Esto evita el fallo `org.apache.commons.exec.ExecuteException` que puede producir la acción automática `exec:exec` de NetBeans.

Para una demostración de Go-Back-N, configura entre 10 % y 30 % de pérdida de datos en el servidor o pérdida de ACK en el cliente. En el registro aparecerán `SO_TIMEOUT` y `Retroceder-N desde paquete ...`.

### Ejecutar en dos computadoras

1. Conecta ambas computadoras a la misma red.
2. Inicia el servidor y permite el puerto UDP elegido en el firewall (9876 por defecto).
3. Obtén la IP local del servidor (`ipconfig` en Windows, `ip addr` en Linux o `ifconfig` en macOS).
4. Escribe esa IP en el campo **Dirección del servidor** del cliente.
5. Mantén el mismo puerto en ambos lados.

## Modo consola opcional

Desde NetBeans es más sencillo usar **Run File**, pero las clases también aceptan:

```bash
# Servidor: porcentaje de pérdida de DATA opcional
java com.redes.udp.Servidor --cli cancion1.mp3 20

# Cliente: host, puerto, destino y porcentaje de pérdida de ACK opcional
java com.redes.udp.Cliente --cli 127.0.0.1 9876 recibido_cancion1.mp3 10
```

Al ejecutarlas fuera de NetBeans se deben incluir JLayer y mp3agic en el classpath; Maven/NetBeans lo hace automáticamente.

## Alternativa: proyecto Ant y JAR manuales

El proyecto entregado es Maven. Si la materia exige Ant:

1. Crea en NetBeans **Java with Ant > Java Application** con JDK 17 o posterior.
2. Copia el paquete `com.redes.udp` dentro de `Source Packages`.
3. Descarga `jlayer-1.0.1.jar` y `mp3agic-0.9.1.jar` desde Maven Central.
4. Haz clic derecho en **Libraries > Add JAR/Folder** y agrega ambos archivos.
5. Copia `cancion1.mp3` a la carpeta del proyecto o selecciónalo desde la interfaz.
6. Ejecuta por separado `Servidor.java` y `Cliente.java`.

## Cómo demuestra Go-Back-N

El emisor mantiene:

- `base`: primer paquete todavía no confirmado;
- `siguiente`: próximo número por enviar;
- la ventana válida `[base, base + N)`.

Llena la ventana y espera ACKs acumulativos. El temporizador corresponde al paquete `base`. Antes de cada recepción se calcula el tiempo restante y se aplica con `setSoTimeout(restante)`. Un ACK nuevo mueve `base`; un ACK duplicado no reinicia el temporizador. Cuando ocurre `SocketTimeoutException`, `siguiente` vuelve a `base`, por lo que se retransmiten todos los paquetes pendientes de esa ventana.

El receptor solo acepta `seqNum == esperado`. Escribe ese fragmento y responde ACK. Si recibe un fragmento duplicado o adelantado, lo descarta y repite el ACK acumulativo `esperado - 1`. Este comportamiento obliga al emisor a retroceder cuando falta un paquete.
