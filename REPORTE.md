# Práctica: transmisión confiable de un archivo MP3 mediante UDP, ventana deslizante y Go-Back-N

## Resumen

En esta práctica se diseñó e implementó una aplicación cliente-servidor en Java para transferir un archivo de audio MP3 sobre el protocolo UDP. Debido a que UDP no garantiza entrega, orden ni ausencia de duplicados, se construyó un protocolo de aplicación que incorpora control de flujo mediante ventana deslizante y control de errores mediante Go-Back-N. El servidor fragmenta el archivo en datagramas de hasta 1024 bytes, numera los fragmentos, transmite varios sin esperar individualmente y procesa confirmaciones acumulativas. El cliente acepta únicamente paquetes en orden, ensambla temporalmente el archivo y envía un ACK por cada paquete aceptado. La pérdida de confirmaciones se detecta estrictamente con la opción `SO_TIMEOUT` del socket. Al terminar, el cliente verifica tamaño y huella SHA-256, publica el archivo, lee sus etiquetas ID3 y lo reproduce con JLayer. Se agregó una interfaz Swing para configurar y observar el proceso sin bloquear el hilo gráfico.

## 1. Introducción

Los sockets de datagrama proporcionan acceso al servicio de transporte UDP. A diferencia de TCP, UDP es no orientado a conexión: cada datagrama se envía como una unidad independiente y el protocolo no establece una sesión confiable ni garantiza que los datos lleguen, lleguen una sola vez o conserven su orden. Estas características reducen el procesamiento y evitan mecanismos internos como el establecimiento de conexión, pero trasladan a la aplicación cualquier requisito de confiabilidad.

Cuando el emisor puede producir información más rápido de lo que el receptor o la red pueden procesarla, se requiere control de flujo. La ventana deslizante permite tener varios paquetes “en vuelo” sin esperar un ACK individual antes de enviar el siguiente. El borde izquierdo de la ventana representa el primer paquete no confirmado, mientras que el borde derecho limita cuántos paquetes pendientes puede mantener el emisor. Conforme llegan confirmaciones, la ventana avanza y habilita nuevos envíos.

Go-Back-N (GBN) es un protocolo ARQ de ventana deslizante. El receptor mantiene una expectativa estricta sobre el siguiente número de secuencia, descarta los paquetes adelantados y utiliza ACKs acumulativos. El emisor conserva un temporizador para el paquete más antiguo no confirmado. Si ese temporizador expira, retrocede al inicio de la ventana pendiente y retransmite dicho paquete y todos los posteriores que ya habían sido enviados. GBN simplifica al receptor, aunque puede repetir datos que sí llegaron cuando se pierde un solo fragmento.

El objetivo de la práctica fue aplicar estos conceptos para transferir correctamente un archivo MP3, respetando un máximo de 1024 bytes por datagrama de aplicación, y permitir la reproducción únicamente cuando el archivo estuviera completo.

## 2. Objetivos

### 2.1 Objetivo general

Implementar en Java una aplicación cliente-servidor capaz de transmitir un archivo MP3 mediante datagramas UDP con control de flujo por ventana deslizante y recuperación de errores mediante Go-Back-N.

### 2.2 Objetivos específicos

- Fragmentar un archivo binario sin superar 1024 bytes por datagrama UDP.
- Incluir números de secuencia y campos de control en una cabecera binaria de aplicación.
- Enviar varios paquetes de acuerdo con un tamaño de ventana configurable.
- Utilizar `DatagramSocket.setSoTimeout(...)` para detectar pérdida de ACKs.
- Implementar ACKs acumulativos y retransmisión desde la base de la ventana.
- Ensamblar el archivo únicamente con fragmentos recibidos en orden.
- Verificar tamaño e integridad antes de publicar o reproducir el MP3.
- Leer Artista, Álbum y Año desde etiquetas ID3v2 o ID3v1.
- Reproducir el archivo completo mediante JLayer.
- Ofrecer una interfaz intuitiva para configurar, ejecutar y observar el protocolo.

## 3. Desarrollo e implementación

### 3.1 Arquitectura de software

La solución separa responsabilidades en tres niveles. `PaqueteProtocolo` codifica y valida la representación binaria de todos los mensajes. `ServidorTransferencia` y `ClienteTransferencia` implementan el algoritmo de red sin depender de Swing. Finalmente, `Servidor` y `Cliente` constituyen las interfaces gráficas y ejecutan los motores mediante `SwingWorker`, evitando bloquear el Event Dispatch Thread.

La separación permite probar el transporte en modo consola, conservar una interfaz responsiva y modificar la presentación sin alterar el protocolo. `MetadataAudio` encapsula mp3agic y `ReproductorAudio` encapsula JLayer. Los parámetros predeterminados se concentran en `ProtocoloConfig`.

### 3.2 Protocolo de aplicación

Todos los mensajes comienzan con un número mágico de cuatro bytes (`GBN1`), una versión de un byte y un tipo de un byte. Estos campos permiten distinguir datagramas válidos y evolucionar el formato. Cada transferencia recibe además un identificador aleatorio de sesión de ocho bytes para impedir que datagramas retrasados de una ejecución anterior sean aceptados.

Antes de enviar audio, el cliente transmite una solicitud. El servidor responde con metadata que contiene nombre, tamaño, cantidad total de paquetes y SHA-256. El cliente valida dicha información y devuelve `META_ACK`. Después ocurre la transferencia GBN. Una vez confirmado el último fragmento, el servidor envía `FIN` y espera `FIN_ACK`, lo que proporciona un cierre explícito.

### 3.3 Cabecera y número de secuencia

El datagrama de datos tiene una cabecera de aplicación de 21 bytes:

| Campo | Tamaño | Función |
|---|---:|---|
| Magic `GBN1` | 4 bytes | Identificación del protocolo |
| Versión | 1 byte | Versión del formato |
| Tipo | 1 byte | Indica mensaje de datos |
| Session ID | 8 bytes | Identifica la transferencia |
| Número de secuencia | 4 bytes | Posición del fragmento |
| Banderas | 1 byte | Marca el último fragmento |
| Longitud | 2 bytes | Bytes útiles presentes |
| Audio | 0 a 1003 bytes | Fragmento del MP3 |

El número de secuencia se escribe con `ByteBuffer.putInt`, que transforma un entero de 32 bits en cuatro bytes big-endian dentro del payload UDP. El receptor recupera el valor con `getInt` en la misma posición. Esta representación binaria es preferible a enviar texto porque su tamaño es fijo, no requiere separadores y no depende de una codificación de caracteres.

Debido a que la cabecera ocupa 21 bytes, la carga útil máxima es:

```text
1024 - 21 = 1003 bytes de audio por datagrama
```

Así, incluso un fragmento lleno produce exactamente 1024 bytes de payload UDP. El último paquete puede ser menor y su longitud real se indica en la cabecera. El ACK mide 18 bytes e incluye magic, versión, tipo, identificador de sesión y el entero `ackNum`.

### 3.4 Fragmentación

El servidor lee el archivo como datos binarios y calcula la cantidad de fragmentos mediante división entera con redondeo hacia arriba. Para cada secuencia calcula el desplazamiento `secuencia * 1003`, copia como máximo 1003 bytes y activa la bandera final únicamente en la última secuencia. La implementación también representa correctamente un archivo vacío con un único fragmento sin carga, aunque la aplicación está orientada a archivos MP3 válidos.

Antes de transmitir se calcula SHA-256 sobre el contenido original. Esta huella no sustituye a Go-Back-N, pero permite comprobar al final que el archivo ensamblado coincide íntegramente con el ofrecido.

### 3.5 Ventana deslizante en el servidor

El servidor mantiene dos variables principales:

- `base`: número del primer paquete no confirmado;
- `siguiente`: número del próximo paquete que puede enviarse.

Mientras `siguiente < base + N`, el servidor envía paquetes sin esperar un ACK. Después recibe confirmaciones. Como los ACK son acumulativos, un ACK con valor `k` permite asignar `base = k + 1`; todos los fragmentos hasta `k` se consideran confirmados. Un ACK menor que la base es duplicado y no modifica el estado. Un ACK que confirme algo no enviado se descarta como inválido.

El tamaño inicial de ventana es cinco, pero la interfaz permite cambiarlo entre 1 y 64. Una ventana de uno equivale conceptualmente a Stop-and-Wait, mientras que valores mayores aprovechan mejor la red.

### 3.6 Temporizador con SO_TIMEOUT y Retroceder-N

El temporizador corresponde al paquete `base`, es decir, al más antiguo pendiente. El programa registra cuándo comenzó ese temporizador. Antes de invocar `receive`, calcula el tiempo restante y lo aplica estrictamente con:

```java
socket.setSoTimeout(tiempoRestante);
socket.receive(paqueteAck);
```

Si el tiempo expira, Java genera `SocketTimeoutException`. El servidor registra el evento y asigna `siguiente = base`. En la siguiente iteración vuelve a transmitir desde la base hasta el último paquete permitido por la ventana. Esto constituye la operación Retroceder-N. Un ACK duplicado no reinicia el temporizador; un ACK que sí desplaza la base inicia el temporizador del nuevo paquete más antiguo.

### 3.7 Comportamiento del cliente

El cliente mantiene `esperado`, que comienza en cero. Solo acepta un paquete cuando `seqNum == esperado`. En ese caso escribe su carga al archivo temporal, actualiza SHA-256, envía un ACK con el mismo número e incrementa `esperado`. Por tanto, se produce un ACK por cada paquete recibido correctamente en orden.

Si llega un paquete adelantado o duplicado, el cliente no lo almacena. Envía nuevamente `esperado - 1`, que representa el último paquete aceptado y funciona como ACK acumulativo. El receptor no necesita una memoria para reordenar fragmentos, característica propia de GBN.

El cliente valida también IP y puerto del servidor, identificador de sesión, tamaño del datagrama, longitud declarada y posición de la bandera final. Esto evita que datos ajenos o mal formados corrompan el archivo.

### 3.8 Ensamblado, integridad y cierre

Durante la recepción se escribe `nombre.mp3.part`, no el archivo definitivo. Una vez aceptados todos los paquetes se comprueba que el número de bytes sea el anunciado y que el SHA-256 calculado coincida con el enviado por el servidor. Si falla una verificación, se elimina el temporal y la reproducción continúa bloqueada.

Después de verificar los datos, el cliente espera `FIN`. Durante esta espera puede recibir de nuevo el último paquete si su ACK se perdió; en tal caso reenvía el ACK sin escribir otra vez el audio. Al recibir `FIN`, responde `FIN_ACK`. Solo entonces mueve atómicamente el temporal al nombre definitivo. Este intercambio corrige la debilidad de finalizar inmediatamente después de enviar una ráfaga fija de ACKs.

### 3.9 Metadata y reproducción

Cuando el archivo definitivo ya existe, mp3agic abre el MP3. Se priorizan etiquetas ID3v2 y se utiliza ID3v1 como respaldo. La interfaz presenta Título, Artista, Álbum, Año, duración, bitrate y frecuencia de muestreo. Si una etiqueta está ausente se muestra “No especificado”.

JLayer crea el reproductor en un hilo independiente. El control permanece deshabilitado hasta que concluyen la recepción y la verificación. Con la opción predeterminada, la reproducción inicia automáticamente después de mostrar la metadata; también puede iniciarse o detenerse manualmente.

### 3.10 Interfaz gráfica

Se crearon ventanas independientes para servidor y cliente con una identidad visual de laboratorio de redes. La zona izquierda concentra parámetros y acciones. La parte superior muestra telemetría; el centro representa los paquetes confirmados, en vuelo y disponibles en la ventana; la parte inferior conserva un registro con marcas de tiempo. El cliente añade el panel de metadata y controles de audio.

Las tareas de red se ejecutan mediante `SwingWorker` y los eventos actualizan Swing en el EDT. Por ello, esperar datagramas, sufrir un timeout o reproducir audio no congela los botones ni la ventana. Al detener o cerrar se cierra el socket para desbloquear inmediatamente cualquier `receive` pendiente.

### 3.11 Simulación de errores

El servidor puede omitir aleatoriamente intentos de envío de datos y el cliente puede omitir ACKs. Estas opciones no alteran el contenido de los paquetes: simulan pérdida y permiten observar el timeout, el descarte de paquetes fuera de orden y la retransmisión. El porcentaje se configura desde cero hasta 60 %.

## 4. Pruebas y resultados

El proyecto se compiló con compatibilidad OpenJDK 17 y las dependencias declaradas en Maven. Se ejecutó primero una transferencia local sin pérdida. El archivo de 26 fragmentos fue confirmado, se completó el cierre `FIN/FIN_ACK` y el resultado coincidió byte por byte con el MP3 original.

En una segunda prueba se configuró 30 % de pérdida de datos y 20 % de pérdida de ACKs. Durante esa ejecución se registraron 23 omisiones simuladas de datos, 9 omisiones de ACK y 10 expiraciones de `SO_TIMEOUT`. El registro mostró paquetes adelantados descartados y varias operaciones Retroceder-N. A pesar de ello, SHA-256 fue válido y la comparación binaria entre origen y destino resultó idéntica. Finalmente se leyeron las etiquetas ID3v2 y JLayer terminó la reproducción.

Los resultados demuestran que la confiabilidad no proviene de UDP, sino de los mecanismos construidos en la aplicación: secuencias, ACKs acumulativos, temporizador, retransmisión, validación y cierre.

## 5. Conclusiones

La práctica permitió comprobar que es posible transmitir archivos correctamente sobre UDP si la aplicación implementa explícitamente las funciones que TCP ofrece de forma integrada. La ventana deslizante evita la ineficiencia de esperar una confirmación después de cada fragmento y Go-Back-N proporciona una estrategia comprensible para recuperarse de pérdidas. `SO_TIMEOUT` constituye el vínculo práctico entre el concepto de temporizador y la API de sockets de Java.

Entre las ventajas de usar UDP se encuentran la sencillez del servicio de transporte, la ausencia de establecimiento de conexión, el control total de la política de retransmisión y la posibilidad de adaptar el protocolo a tráfico en tiempo real. También facilita estudiar directamente conceptos como secuencias, ventanas y ACKs.

Sin embargo, para transferir un archivo las desventajas son significativas. La aplicación debe resolver pérdida, duplicación, orden, validación, temporización y cierre. Go-Back-N puede desperdiciar ancho de banda al retransmitir paquetes posteriores que ya habían llegado. Además, esta implementación académica no incluye control de congestión, estimación adaptativa del tiempo de ida y vuelta, autenticación ni cifrado. TCP ya ofrece entrega ordenada, retransmisiones, control de flujo y control de congestión ampliamente probados, por lo que sería la opción habitual para una transferencia de archivos real.

En conclusión, UDP con GBN es apropiado aquí como herramienta didáctica y como base para protocolos especializados, pero no reemplaza automáticamente a TCP. La selección debe depender de los requisitos: control personalizado y baja latencia potencial frente a confiabilidad general, interoperabilidad y madurez. La verificación SHA-256 y el archivo temporal mostraron además que “recibir el último paquete” no basta; una aplicación robusta debe comprobar el resultado antes de consumirlo.

## 6. Dependencias

- Java SE 17: `DatagramSocket`, `DatagramPacket`, NIO, Swing y SHA-256.
- mp3agic 0.9.1: lectura de etiquetas ID3v1/ID3v2.
- JLayer 1.0.1: decodificación y reproducción MP3.

## 7. Referencias sugeridas

- Kurose, J. F. y Ross, K. W. *Computer Networking: A Top-Down Approach*. Secciones sobre transporte confiable y protocolos de ventana.
- Oracle. Documentación de Java SE para `DatagramSocket`, `DatagramPacket` y `SocketTimeoutException`.
- JavaZoom. Documentación de JLayer.
- mp3agic. Documentación de la biblioteca para etiquetas MP3.
