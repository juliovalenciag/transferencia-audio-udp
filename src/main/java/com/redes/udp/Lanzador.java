package com.redes.udp;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.GraphicsEnvironment;
import java.awt.GridLayout;
import java.awt.Rectangle;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;

public final class Lanzador extends JFrame {

    private static final long serialVersionUID = 1L;

    private Lanzador() {
        super("GBN Audio");
        construirInterfaz();
    }

    private void construirInterfaz() {
        setDefaultCloseOperation(EXIT_ON_CLOSE);
        setResizable(false);
        setSize(new Dimension(480, 255));
        setLocationRelativeTo(null);
        setLayout(new BorderLayout(0, 18));
        ((JPanel) getContentPane()).setBorder(
                BorderFactory.createEmptyBorder(24, 26, 22, 26));

        JPanel encabezado = new JPanel(new GridLayout(0, 1, 0, 3));
        encabezado.setOpaque(false);
        JLabel titulo = new JLabel("Transmisión de audio por UDP");
        titulo.setFont(TemaUI.TITULO);
        JLabel descripcion = new JLabel("Elige la ventana");
        descripcion.setForeground(TemaUI.TEXTO_SUAVE);
        encabezado.add(titulo);
        encabezado.add(descripcion);
        add(encabezado, BorderLayout.NORTH);

        JPanel principales = new JPanel(new GridLayout(1, 2, 10, 0));
        principales.setOpaque(false);
        JButton servidor = TemaUI.boton("Abrir servidor", true);
        JButton cliente = TemaUI.boton("Abrir cliente", true);
        servidor.addActionListener(e -> new Servidor().setVisible(true));
        cliente.addActionListener(e -> new Cliente().setVisible(true));
        principales.add(servidor);
        principales.add(cliente);
        add(principales, BorderLayout.CENTER);

        JButton ambos = TemaUI.boton("Abrir ambos lado a lado", false);
        ambos.addActionListener(e -> abrirAmbos());
        add(ambos, BorderLayout.SOUTH);
    }

    private static void abrirAmbos() {
        Servidor servidor = new Servidor();
        Cliente cliente = new Cliente();
        Rectangle pantalla = GraphicsEnvironment.getLocalGraphicsEnvironment()
                .getMaximumWindowBounds();
        int margen = 10;
        int ancho = (pantalla.width - margen * 3) / 2;
        int alto = Math.min(630, pantalla.height - margen * 2);
        if (ancho >= 610) {
            servidor.setBounds(pantalla.x + margen, pantalla.y + margen, ancho, alto);
            cliente.setBounds(pantalla.x + margen * 2 + ancho, pantalla.y + margen, ancho, alto);
        } else {
            
            int anchoSeguro = Math.min(680, pantalla.width - 40);
            int altoSeguro = Math.min(610, pantalla.height - 60);
            servidor.setBounds(pantalla.x + 10, pantalla.y + 10, anchoSeguro, altoSeguro);
            cliente.setBounds(pantalla.x + 30, pantalla.y + 30, anchoSeguro, altoSeguro);
        }
        servidor.setVisible(true);
        cliente.setVisible(true);
    }

    public static void main(String[] args) {
        TemaUI.aplicar();
        SwingUtilities.invokeLater(() -> new Lanzador().setVisible(true));
    }
}
