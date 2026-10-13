package com.redes.udp;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.GridLayout;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.nio.file.Path;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.JSpinner;
import javax.swing.JTabbedPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SpinnerNumberModel;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import javax.swing.filechooser.FileNameExtensionFilter;

@SuppressWarnings("serial")
public final class Cliente extends JFrame {

    private static final long serialVersionUID = 1L;
    private static final DateTimeFormatter HORA = DateTimeFormatter.ofPattern("HH:mm:ss");

    private final JTextField campoHost = TemaUI.campo(ProtocoloConfig.HOST_SERVIDOR);
    private final JSpinner campoPuerto = new JSpinner(new SpinnerNumberModel(
            ProtocoloConfig.PUERTO_SERVIDOR, 1, 65535, 1));
    private final JTextField campoDestino = TemaUI.campo(ProtocoloConfig.ARCHIVO_DESTINO);
    private final JSpinner campoPerdidaAck = new JSpinner(new SpinnerNumberModel(0, 0, 60, 1));
    private final JCheckBox reproducirAutomatico = new JCheckBox("Reproducir al terminar", true);
    private final JButton botonRecibir = TemaUI.boton("Recibir", true);
    private final JButton botonCancelar = TemaUI.boton("Cancelar", false);
    private final JButton botonReproducir = TemaUI.boton("Reproducir", false);
    private final JButton botonDetenerAudio = TemaUI.boton("Detener audio", false);
    private final JLabel etiquetaEstado = new JLabel("Listo");
    private final JLabel datoBytes = TemaUI.dato("0 B");
    private final JLabel datoPaquetes = TemaUI.dato("0");
    private final JLabel datoProgreso = TemaUI.dato("0 %");
    private final JProgressBar progreso = new JProgressBar(0, 1000);
    private final PanelVentana panelVentana = new PanelVentana();
    private final JTextArea consola = TemaUI.consola();
    private final JTextArea metadata = TemaUI.consola();
    private final ReproductorAudio reproductor = new ReproductorAudio();

    private volatile ClienteTransferencia transferencia;
    private volatile Path archivoCompleto;

    public Cliente() {
        super("Cliente · GBN Audio");
        TemaUI.configurarSpinner(campoPuerto);
        TemaUI.configurarSpinner(campoPerdidaAck);
        construirInterfaz();
        conectarEventos();
    }

    private void construirInterfaz() {
        setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);
        setMinimumSize(new Dimension(610, 550));
        setSize(680, 630);
        setLocationRelativeTo(null);
        getContentPane().setBackground(TemaUI.FONDO);
        setLayout(new BorderLayout(0, 10));
        ((JPanel) getContentPane()).setBorder(BorderFactory.createEmptyBorder(13, 15, 13, 15));
        add(crearCabecera(), BorderLayout.NORTH);

        JPanel contenido = new JPanel(new BorderLayout(0, 9));
        contenido.setOpaque(false);
        contenido.add(panelConfiguracion(), BorderLayout.NORTH);

        JPanel centro = new JPanel(new BorderLayout(0, 8));
        centro.setOpaque(false);
        centro.add(panelResumen(), BorderLayout.NORTH);
        JPanel ventana = TemaUI.seccion("Recepción acumulativa");
        ventana.add(panelVentana, BorderLayout.CENTER);
        centro.add(ventana, BorderLayout.CENTER);
        centro.add(panelDetalles(), BorderLayout.SOUTH);
        contenido.add(centro, BorderLayout.CENTER);
        add(contenido, BorderLayout.CENTER);
    }

    private JPanel crearCabecera() {
        JPanel cabecera = new JPanel(new BorderLayout());
        cabecera.setOpaque(false);
        JPanel titulos = new JPanel(new GridLayout(0, 1, 0, 1));
        titulos.setOpaque(false);
        JLabel titulo = new JLabel("Cliente de audio");
        titulo.setFont(TemaUI.TITULO);
        JLabel bajada = new JLabel("Recibe, verifica y reproduce el MP3 completo");
        bajada.setForeground(TemaUI.TEXTO_SUAVE);
        titulos.add(titulo);
        titulos.add(bajada);
        cabecera.add(titulos, BorderLayout.WEST);
        etiquetaEstado.setFont(new Font("Monospaced", Font.BOLD, 11));
        etiquetaEstado.setForeground(TemaUI.CIAN);
        etiquetaEstado.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(TemaUI.BORDE),
                BorderFactory.createEmptyBorder(5, 9, 5, 9)));
        cabecera.add(etiquetaEstado, BorderLayout.EAST);
        return cabecera;
    }

    private JPanel panelConfiguracion() {
        JPanel exterior = TemaUI.seccion("Conexión y destino");
        JPanel contenido = new JPanel(new BorderLayout(0, 7));
        contenido.setOpaque(false);

        JPanel conexion = new JPanel(new BorderLayout(7, 0));
        conexion.setOpaque(false);
        JLabel etiquetaHost = new JLabel("Servidor");
        etiquetaHost.setForeground(TemaUI.TEXTO_SUAVE);
        etiquetaHost.setPreferredSize(new Dimension(58, 30));
        conexion.add(etiquetaHost, BorderLayout.WEST);
        conexion.add(campoHost, BorderLayout.CENTER);
        JPanel numeros = new JPanel(new GridLayout(1, 2, 7, 0));
        numeros.setOpaque(false);
        numeros.add(grupoCampo("Puerto", campoPuerto));
        numeros.add(grupoCampo("Pérdida ACK %", campoPerdidaAck));
        conexion.add(numeros, BorderLayout.EAST);
        contenido.add(conexion, BorderLayout.NORTH);

        JPanel destino = new JPanel(new BorderLayout(7, 0));
        destino.setOpaque(false);
        JLabel etiquetaDestino = new JLabel("Guardar");
        etiquetaDestino.setForeground(TemaUI.TEXTO_SUAVE);
        etiquetaDestino.setPreferredSize(new Dimension(58, 30));
        JButton explorar = TemaUI.boton("Elegir…", false);
        explorar.addActionListener(e -> elegirDestino());
        destino.add(etiquetaDestino, BorderLayout.WEST);
        destino.add(campoDestino, BorderLayout.CENTER);
        destino.add(explorar, BorderLayout.EAST);
        contenido.add(destino, BorderLayout.CENTER);

        JPanel acciones = new JPanel(new BorderLayout(8, 0));
        acciones.setOpaque(false);
        reproducirAutomatico.setForeground(TemaUI.TEXTO);
        reproducirAutomatico.setBackground(TemaUI.FONDO);
        acciones.add(reproducirAutomatico, BorderLayout.WEST);
        JPanel botones = new JPanel(new GridLayout(1, 2, 7, 0));
        botones.setOpaque(false);
        botonCancelar.setEnabled(false);
        botones.add(botonRecibir);
        botones.add(botonCancelar);
        acciones.add(botones, BorderLayout.EAST);
        contenido.add(acciones, BorderLayout.SOUTH);
        exterior.add(contenido, BorderLayout.CENTER);
        return exterior;
    }

    private JPanel panelResumen() {
        JPanel exterior = TemaUI.seccion("Estado de la recepción");
        JPanel fila = new JPanel(new GridLayout(1, 3, 16, 0));
        fila.setOpaque(false);
        fila.add(datoCompacto("Recibidos", datoBytes));
        fila.add(datoCompacto("En orden", datoPaquetes));
        fila.add(datoCompacto("Progreso", datoProgreso));
        exterior.add(fila, BorderLayout.CENTER);
        progreso.setPreferredSize(new Dimension(100, 6));
        exterior.add(progreso, BorderLayout.SOUTH);
        return exterior;
    }

    private JPanel panelDetalles() {
        JTabbedPane pestanas = new JTabbedPane();
        pestanas.setFont(TemaUI.CUERPO);
        pestanas.setForeground(TemaUI.TEXTO);
        pestanas.setBackground(TemaUI.FONDO);

        JPanel actividad = new JPanel(new BorderLayout());
        actividad.setBackground(TemaUI.SUPERFICIE);
        actividad.add(TemaUI.scroll(consola), BorderLayout.CENTER);
        pestanas.addTab("Actividad", actividad);

        JPanel datos = new JPanel(new BorderLayout(0, 6));
        datos.setBackground(TemaUI.SUPERFICIE);
        metadata.setText("La metadata aparecerá después de verificar el archivo completo.");
        datos.add(TemaUI.scroll(metadata), BorderLayout.CENTER);
        JPanel audio = new JPanel(new GridLayout(1, 2, 7, 0));
        audio.setBackground(TemaUI.SUPERFICIE);
        botonReproducir.setEnabled(false);
        botonDetenerAudio.setEnabled(false);
        audio.add(botonReproducir);
        audio.add(botonDetenerAudio);
        datos.add(audio, BorderLayout.SOUTH);
        pestanas.addTab("Metadata y audio", datos);

        JPanel contenedor = new JPanel(new BorderLayout());
        contenedor.setOpaque(false);
        contenedor.setPreferredSize(new Dimension(100, 190));
        contenedor.add(pestanas, BorderLayout.CENTER);
        return contenedor;
    }

    private static JPanel datoCompacto(String nombre, JLabel dato) {
        JPanel panel = new JPanel(new BorderLayout(7, 0));
        panel.setOpaque(false);
        JLabel etiqueta = new JLabel(nombre);
        etiqueta.setForeground(TemaUI.TEXTO_SUAVE);
        panel.add(etiqueta, BorderLayout.WEST);
        panel.add(dato, BorderLayout.CENTER);
        return panel;
    }

    private static JPanel grupoCampo(String texto, java.awt.Component campo) {
        JPanel grupo = new JPanel(new BorderLayout(0, 2));
        grupo.setOpaque(false);
        grupo.setPreferredSize(new Dimension(98, 44));
        JLabel etiqueta = new JLabel(texto);
        etiqueta.setForeground(TemaUI.TEXTO_SUAVE);
        etiqueta.setFont(new Font(TemaUI.CUERPO.getName(), Font.PLAIN, 11));
        grupo.add(etiqueta, BorderLayout.NORTH);
        grupo.add(campo, BorderLayout.CENTER);
        return grupo;
    }

    private void conectarEventos() {
        botonRecibir.addActionListener(e -> recibir());
        botonCancelar.addActionListener(e -> cancelar());
        botonReproducir.addActionListener(e -> iniciarAudio());
        botonDetenerAudio.addActionListener(e -> detenerAudio());
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                cancelar();
                reproductor.close();
                dispose();
            }
        });
    }

    private void elegirDestino() {
        JFileChooser selector = new JFileChooser();
        selector.setSelectedFile(Path.of(campoDestino.getText().trim()).toFile());
        selector.setFileFilter(new FileNameExtensionFilter("Audio MP3", "mp3"));
        if (selector.showSaveDialog(this) == JFileChooser.APPROVE_OPTION) {
            String ruta = selector.getSelectedFile().getAbsolutePath();
            campoDestino.setText(ruta.toLowerCase().endsWith(".mp3") ? ruta : ruta + ".mp3");
        }
    }

    private void recibir() {
        try {
            Path destino = Path.of(campoDestino.getText().trim());
            if (!destino.getFileName().toString().toLowerCase().endsWith(".mp3")) {
                throw new IllegalArgumentException("El destino debe terminar en .mp3");
            }
            int puerto = ((Number) campoPuerto.getValue()).intValue();
            double perdida = ((Number) campoPerdidaAck.getValue()).doubleValue() / 100.0;
            transferencia = new ClienteTransferencia(campoHost.getText(), puerto, destino,
                    perdida, crearEscucha());
            archivoCompleto = null;
            bloquear(true);
            consola.setText("");
            metadata.setText("Esperando el archivo completo…");
            progreso.setValue(0);
            agregarLog("La reproducción se habilitará después de validar SHA-256");

            new SwingWorker<ClienteTransferencia.Resultado, Void>() {
                @Override
                protected ClienteTransferencia.Resultado doInBackground() throws Exception {
                    return transferencia.ejecutar();
                }

                @Override
                protected void done() {
                    bloquear(false);
                    try {
                        ClienteTransferencia.Resultado resultado = get();
                        archivoCompleto = resultado.archivo();
                        botonReproducir.setEnabled(true);
                        mostrarMetadata(resultado);
                        if (reproducirAutomatico.isSelected()) {
                            iniciarAudio();
                        }
                    } catch (Exception ex) {
                        agregarLog("Error: " + mensajeRaiz(ex));
                        cambiarEstado("Falló", TemaUI.ROJO);
                        metadata.setText("La recepción no terminó correctamente. La reproducción sigue bloqueada.");
                    } finally {
                        transferencia = null;
                    }
                }
            }.execute();
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(this, ex.getMessage(), "Revisa la configuración",
                    JOptionPane.ERROR_MESSAGE);
        }
    }

    private void mostrarMetadata(ClienteTransferencia.Resultado resultado) {
        try {
            MetadataAudio.Info info = MetadataAudio.leer(resultado.archivo());
            metadata.setText("Título:  " + info.titulo()
                    + "\nArtista: " + info.artista()
                    + "\nÁlbum:   " + info.album()
                    + "\nAño:     " + info.anio()
                    + "\n\n" + info.tipoEtiqueta() + " · "
                    + info.duracionSegundos() + " s · " + info.bitrate() + " kbps · "
                    + info.frecuencia() + " Hz"
                    + "\n\nSHA-256\n" + HexFormat.of().formatHex(resultado.sha256()));
            agregarLog("Metadata " + info.tipoEtiqueta() + " leída después de completar el archivo");
        } catch (Exception ex) {
            metadata.setText("Archivo completo y verificado.\n\nNo fue posible leer etiquetas ID3: "
                    + ex.getMessage());
            agregarLog("Aviso de metadata: " + ex.getMessage());
        }
    }

    private void iniciarAudio() {
        Path archivo = archivoCompleto;
        if (archivo == null) {
            return;
        }
        try {
            reproductor.reproducir(archivo, mensaje -> SwingUtilities.invokeLater(() -> {
                agregarLog(mensaje);
                botonDetenerAudio.setEnabled("Reproduciendo".equals(mensaje));
            }));
        } catch (Exception ex) {
            agregarLog("No se pudo iniciar JLayer: " + ex.getMessage());
        }
    }

    private void detenerAudio() {
        reproductor.close();
        botonDetenerAudio.setEnabled(false);
        agregarLog("Reproducción detenida por el usuario");
    }

    private void cancelar() {
        ClienteTransferencia actual = transferencia;
        if (actual != null) {
            actual.close();
            agregarLog("Cancelación solicitada por el usuario");
        }
    }

    private EscuchaTransferencia crearEscucha() {
        return new EscuchaTransferencia() {
            @Override
            public void log(String mensaje) {
                SwingUtilities.invokeLater(() -> agregarLog(mensaje));
            }

            @Override
            public void estado(String estado) {
                SwingUtilities.invokeLater(() -> cambiarEstado(estado, TemaUI.CIAN));
            }

            @Override
            public void progreso(long actual, long total) {
                SwingUtilities.invokeLater(() -> {
                    int valor = total == 0 ? 1000 : (int) Math.min(1000, actual * 1000 / total);
                    progreso.setValue(valor);
                    datoProgreso.setText(String.format("%.1f %%", valor / 10.0));
                    datoBytes.setText(formatearBytes(actual));
                });
            }

            @Override
            public void ventana(int base, int siguiente, int total, int tamano) {
                SwingUtilities.invokeLater(() -> {
                    panelVentana.actualizar(base, siguiente, total, tamano);
                    datoPaquetes.setText(Integer.toString(base));
                });
            }
        };
    }

    private void cambiarEstado(String texto, java.awt.Color color) {
        etiquetaEstado.setText(texto);
        etiquetaEstado.setForeground(color);
    }

    private void bloquear(boolean ejecutando) {
        botonRecibir.setEnabled(!ejecutando);
        botonCancelar.setEnabled(ejecutando);
        campoHost.setEnabled(!ejecutando);
        campoPuerto.setEnabled(!ejecutando);
        campoDestino.setEnabled(!ejecutando);
        campoPerdidaAck.setEnabled(!ejecutando);
    }

    private void agregarLog(String texto) {
        consola.append("[" + LocalTime.now().format(HORA) + "] " + texto + "\n");
        consola.setCaretPosition(consola.getDocument().getLength());
    }

    private static String formatearBytes(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }
        if (bytes < 1024 * 1024) {
            return String.format("%.1f KiB", bytes / 1024.0);
        }
        return String.format("%.1f MiB", bytes / (1024.0 * 1024));
    }

    private static String mensajeRaiz(Throwable ex) {
        Throwable actual = ex;
        while (actual.getCause() != null) {
            actual = actual.getCause();
        }
        return actual.getMessage() == null ? actual.toString() : actual.getMessage();
    }

    public static void main(String[] args) {
        if (args.length > 0 && "--cli".equals(args[0])) {
            String host = args.length > 1 ? args[1] : ProtocoloConfig.HOST_SERVIDOR;
            int puerto = args.length > 2 ? Integer.parseInt(args[2]) : ProtocoloConfig.PUERTO_SERVIDOR;
            Path destino = Path.of(args.length > 3 ? args[3] : ProtocoloConfig.ARCHIVO_DESTINO);
            double perdidaAck = args.length > 4 ? Double.parseDouble(args[4]) / 100.0 : 0;
            try {
                ClienteTransferencia.Resultado resultado = new ClienteTransferencia(
                        host, puerto, destino, perdidaAck, EscuchaTransferencia.CONSOLA).ejecutar();
                MetadataAudio.Info info = MetadataAudio.leer(resultado.archivo());
                System.out.println("Artista: " + info.artista());
                System.out.println("Álbum: " + info.album());
                System.out.println("Año: " + info.anio());
                ReproductorAudio reproductor = new ReproductorAudio();
                reproductor.reproducir(resultado.archivo(), System.out::println);
                reproductor.esperar();
            } catch (Exception ex) {
                System.err.println("Error: " + ex.getMessage());
            }
            return;
        }
        TemaUI.aplicar();
        SwingUtilities.invokeLater(() -> new Cliente().setVisible(true));
    }
}
