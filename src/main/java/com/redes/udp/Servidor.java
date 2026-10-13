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
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.JSpinner;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SpinnerNumberModel;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import javax.swing.filechooser.FileNameExtensionFilter;

@SuppressWarnings("serial")
public final class Servidor extends JFrame {

    private static final long serialVersionUID = 1L;
    private static final DateTimeFormatter HORA = DateTimeFormatter.ofPattern("HH:mm:ss");

    private final JTextField campoArchivo = TemaUI.campo(ProtocoloConfig.ARCHIVO_ORIGEN);
    private final JSpinner campoPuerto = new JSpinner(new SpinnerNumberModel(
            ProtocoloConfig.PUERTO_SERVIDOR, 1, 65535, 1));
    private final JSpinner campoVentana = new JSpinner(new SpinnerNumberModel(
            ProtocoloConfig.TAMANO_VENTANA, 1, 64, 1));
    private final JSpinner campoTimeout = new JSpinner(new SpinnerNumberModel(
            ProtocoloConfig.SO_TIMEOUT_MS, 100, 30_000, 100));
    private final JSpinner campoPerdida = new JSpinner(new SpinnerNumberModel(0, 0, 60, 1));
    private final JButton botonIniciar = TemaUI.boton("Iniciar", true);
    private final JButton botonDetener = TemaUI.boton("Detener", false);
    private final JLabel etiquetaEstado = new JLabel("Listo");
    private final JLabel datoPaquetes = TemaUI.dato("0");
    private final JLabel datoRetransmisiones = TemaUI.dato("0");
    private final JLabel datoProgreso = TemaUI.dato("0 %");
    private final JProgressBar progreso = new JProgressBar(0, 1000);
    private final PanelVentana panelVentana = new PanelVentana();
    private final JTextArea consola = TemaUI.consola();

    private volatile ServidorTransferencia transferencia;

    public Servidor() {
        super("Servidor · GBN Audio");
        TemaUI.configurarSpinner(campoPuerto);
        TemaUI.configurarSpinner(campoVentana);
        TemaUI.configurarSpinner(campoTimeout);
        TemaUI.configurarSpinner(campoPerdida);
        construirInterfaz();
        conectarEventos();
    }

    private void construirInterfaz() {
        setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);
        setMinimumSize(new Dimension(610, 530));
        setSize(680, 610);
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
        JPanel ventana = TemaUI.seccion("Ventana de envío");
        ventana.add(panelVentana, BorderLayout.CENTER);
        centro.add(ventana, BorderLayout.CENTER);
        JPanel registro = TemaUI.seccion("Actividad");
        registro.setPreferredSize(new Dimension(100, 180));
        registro.add(TemaUI.scroll(consola), BorderLayout.CENTER);
        centro.add(registro, BorderLayout.SOUTH);
        contenido.add(centro, BorderLayout.CENTER);
        add(contenido, BorderLayout.CENTER);
    }

    private JPanel crearCabecera() {
        JPanel cabecera = new JPanel(new BorderLayout());
        cabecera.setOpaque(false);
        JPanel titulos = new JPanel(new GridLayout(0, 1, 0, 1));
        titulos.setOpaque(false);
        JLabel titulo = new JLabel("Servidor de audio");
        titulo.setFont(TemaUI.TITULO);
        JLabel bajada = new JLabel("Emisor UDP con ventana deslizante y Retroceder-N");
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
        JPanel exterior = TemaUI.seccion("Configuración");
        JPanel contenido = new JPanel(new BorderLayout(0, 7));
        contenido.setOpaque(false);

        JPanel archivo = new JPanel(new BorderLayout(7, 0));
        archivo.setOpaque(false);
        JLabel etiqueta = new JLabel("Archivo");
        etiqueta.setForeground(TemaUI.TEXTO_SUAVE);
        etiqueta.setPreferredSize(new Dimension(52, 30));
        JButton explorar = TemaUI.boton("Elegir…", false);
        explorar.addActionListener(e -> elegirArchivo());
        archivo.add(etiqueta, BorderLayout.WEST);
        archivo.add(campoArchivo, BorderLayout.CENTER);
        archivo.add(explorar, BorderLayout.EAST);
        contenido.add(archivo, BorderLayout.NORTH);

        JPanel inferior = new JPanel(new BorderLayout(10, 0));
        inferior.setOpaque(false);
        JPanel parametros = new JPanel(new GridLayout(1, 4, 7, 0));
        parametros.setOpaque(false);
        parametros.add(grupoCampo("Puerto", campoPuerto));
        parametros.add(grupoCampo("Ventana", campoVentana));
        parametros.add(grupoCampo("Timeout ms", campoTimeout));
        parametros.add(grupoCampo("Pérdida %", campoPerdida));
        inferior.add(parametros, BorderLayout.CENTER);

        JPanel acciones = new JPanel(new GridLayout(1, 2, 7, 0));
        acciones.setOpaque(false);
        botonDetener.setEnabled(false);
        acciones.add(botonIniciar);
        acciones.add(botonDetener);
        inferior.add(acciones, BorderLayout.EAST);
        contenido.add(inferior, BorderLayout.SOUTH);
        exterior.add(contenido, BorderLayout.CENTER);
        return exterior;
    }

    private JPanel panelResumen() {
        JPanel exterior = TemaUI.seccion("Estado de la transferencia");
        JPanel fila = new JPanel(new GridLayout(1, 3, 16, 0));
        fila.setOpaque(false);
        fila.add(datoCompacto("Datagramas", datoPaquetes));
        fila.add(datoCompacto("Reenvíos", datoRetransmisiones));
        fila.add(datoCompacto("Progreso", datoProgreso));
        exterior.add(fila, BorderLayout.CENTER);
        progreso.setPreferredSize(new Dimension(100, 6));
        exterior.add(progreso, BorderLayout.SOUTH);
        return exterior;
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
        JLabel etiqueta = new JLabel(texto);
        etiqueta.setForeground(TemaUI.TEXTO_SUAVE);
        etiqueta.setFont(new Font(TemaUI.CUERPO.getName(), Font.PLAIN, 11));
        grupo.add(etiqueta, BorderLayout.NORTH);
        grupo.add(campo, BorderLayout.CENTER);
        return grupo;
    }

    private void conectarEventos() {
        botonIniciar.addActionListener(e -> iniciar());
        botonDetener.addActionListener(e -> detener());
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                detener();
                dispose();
            }
        });
    }

    private void elegirArchivo() {
        JFileChooser selector = new JFileChooser();
        selector.setFileFilter(new FileNameExtensionFilter("Audio MP3", "mp3"));
        if (selector.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            campoArchivo.setText(selector.getSelectedFile().getAbsolutePath());
        }
    }

    private void iniciar() {
        try {
            Path archivo = Path.of(campoArchivo.getText().trim());
            int puerto = ((Number) campoPuerto.getValue()).intValue();
            int ventana = ((Number) campoVentana.getValue()).intValue();
            int timeout = ((Number) campoTimeout.getValue()).intValue();
            double perdida = ((Number) campoPerdida.getValue()).doubleValue() / 100.0;
            transferencia = new ServidorTransferencia(archivo, puerto, ventana, timeout,
                    perdida, crearEscucha());
            bloquear(true);
            consola.setText("");
            agregarLog("MTU 1024 · cabecera 21 · carga máxima 1003 bytes");

            new SwingWorker<ServidorTransferencia.Resultado, Void>() {
                @Override
                protected ServidorTransferencia.Resultado doInBackground() throws Exception {
                    return transferencia.ejecutar();
                }

                @Override
                protected void done() {
                    bloquear(false);
                    try {
                        ServidorTransferencia.Resultado resultado = get();
                        agregarLog("Resultado: " + resultado.paquetes() + " paquetes, "
                                + resultado.retransmisiones() + " retransmisiones");
                    } catch (Exception ex) {
                        agregarLog("Error: " + mensajeRaiz(ex));
                        cambiarEstado("Detenido", TemaUI.ROJO);
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

    private void detener() {
        ServidorTransferencia actual = transferencia;
        if (actual != null) {
            actual.close();
            agregarLog("Detención solicitada por el usuario");
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
                });
            }

            @Override
            public void ventana(int base, int siguiente, int total, int tamano) {
                SwingUtilities.invokeLater(() -> panelVentana.actualizar(base, siguiente, total, tamano));
            }

            @Override
            public void estadisticas(long datagramas, long retransmisiones, long bytes) {
                SwingUtilities.invokeLater(() -> {
                    datoPaquetes.setText(Long.toString(datagramas));
                    datoRetransmisiones.setText(Long.toString(retransmisiones));
                });
            }
        };
    }

    private void cambiarEstado(String texto, java.awt.Color color) {
        etiquetaEstado.setText(texto);
        etiquetaEstado.setForeground(color);
    }

    private void bloquear(boolean ejecutando) {
        botonIniciar.setEnabled(!ejecutando);
        botonDetener.setEnabled(ejecutando);
        campoArchivo.setEnabled(!ejecutando);
        campoPuerto.setEnabled(!ejecutando);
        campoVentana.setEnabled(!ejecutando);
        campoTimeout.setEnabled(!ejecutando);
        campoPerdida.setEnabled(!ejecutando);
    }

    private void agregarLog(String texto) {
        consola.append("[" + LocalTime.now().format(HORA) + "] " + texto + "\n");
        consola.setCaretPosition(consola.getDocument().getLength());
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
            Path archivo = Path.of(args.length > 1 ? args[1] : ProtocoloConfig.ARCHIVO_ORIGEN);
            double perdida = args.length > 2 ? Double.parseDouble(args[2]) / 100.0 : 0;
            try {
                new ServidorTransferencia(archivo, ProtocoloConfig.PUERTO_SERVIDOR,
                        ProtocoloConfig.TAMANO_VENTANA, ProtocoloConfig.SO_TIMEOUT_MS,
                        perdida, EscuchaTransferencia.CONSOLA).ejecutar();
            } catch (Exception ex) {
                System.err.println("Error: " + ex.getMessage());
            }
            return;
        }
        TemaUI.aplicar();
        SwingUtilities.invokeLater(() -> new Servidor().setVisible(true));
    }
}
