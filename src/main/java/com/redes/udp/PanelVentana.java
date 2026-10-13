package com.redes.udp;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import javax.swing.JPanel;

public final class PanelVentana extends JPanel {

    private static final long serialVersionUID = 1L;

    private int base;
    private int siguiente;
    private int total;
    private int tamanoVentana = ProtocoloConfig.TAMANO_VENTANA;

    public PanelVentana() {
        setOpaque(true);
        setBackground(TemaUI.FONDO);
        setPreferredSize(new Dimension(560, 82));
    }

    public void actualizar(int base, int siguiente, int total, int tamanoVentana) {
        this.base = Math.max(0, base);
        this.siguiente = Math.max(0, siguiente);
        this.total = Math.max(0, total);
        this.tamanoVentana = Math.max(1, tamanoVentana);
        repaint();
    }

    @Override
    protected void paintComponent(Graphics graphics) {
        super.paintComponent(graphics);
        Graphics2D g = (Graphics2D) graphics.create();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        int ancho = getWidth();
        int visibles = Math.min(12, Math.max(1, total));
        int inicio = total <= visibles ? 0 : Math.min(Math.max(0, base - 2), total - visibles);
        int separacion = 7;
        int caja = Math.min(50, (ancho - 28 - separacion * (visibles - 1)) / visibles);
        int x0 = 14;
        int y = 25;

        if (total == 0) {
            g.setColor(TemaUI.TEXTO_SUAVE);
            g.drawString("La secuencia aparecerá al comenzar la transferencia.", 14, 45);
            g.dispose();
            return;
        }

        for (int i = 0; i < visibles; i++) {
            int seq = inicio + i;
            int x = x0 + i * (caja + separacion);
            Color relleno;
            if (seq < base) {
                relleno = new Color(0xDCEFE5); // confirmado
            } else if (seq < siguiente) {
                relleno = new Color(0xDCEAF5); // enviado, espera ACK
            } else if (seq < base + tamanoVentana) {
                relleno = new Color(0xEDF0F3); // disponible en ventana
            } else {
                relleno = new Color(0xF7F8F9); // fuera de ventana
            }
            g.setColor(relleno);
            g.fillRoundRect(x, y, caja, 32, 7, 7);
            g.setColor(seq == base ? TemaUI.AMBAR : TemaUI.BORDE);
            g.setStroke(new BasicStroke(seq == base ? 2f : 1f));
            g.drawRoundRect(x, y, caja, 32, 7, 7);
            String s = Integer.toString(seq);
            FontMetrics fm = g.getFontMetrics();
            g.setColor(TemaUI.TEXTO);
            g.drawString(s, x + (caja - fm.stringWidth(s)) / 2, y + 21);
        }

        g.setColor(TemaUI.TEXTO_SUAVE);
        g.drawString("confirmados / en vuelo / disponibles", 14, 14);
        g.setColor(TemaUI.AMBAR);
        g.drawString("base " + base, 14, 75);
        g.setColor(TemaUI.TEXTO_SUAVE);
        g.drawString("siguiente " + siguiente + "   total " + total, 78, 75);
        g.dispose();
    }
}
