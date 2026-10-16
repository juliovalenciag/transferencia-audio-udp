package com.redes.udp;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Insets;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSpinner;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SwingConstants;
import javax.swing.UIManager;
import javax.swing.border.Border;
import javax.swing.plaf.basic.BasicButtonUI;

public final class TemaUI {

    public static final Color FONDO = new Color(0xF3F5F7);
    public static final Color SUPERFICIE = new Color(0xFFFFFF);
    public static final Color SUPERFICIE_2 = new Color(0xE9EDF1);
    public static final Color BORDE = new Color(0xC9D0D8);
    public static final Color TEXTO = new Color(0x17202A);
    public static final Color TEXTO_SUAVE = new Color(0x5F6B78);
    public static final Color CIAN = new Color(0x236A9F);
    public static final Color AZUL = new Color(0x2E73A8);
    public static final Color AMBAR = new Color(0xA45B13);
    public static final Color ROJO = new Color(0xB53B3B);
    public static final Color EXITO = new Color(0x26734D);

    private static final String FUENTE_UI = System.getProperty("os.name", "")
            .toLowerCase().contains("mac") ? "Helvetica Neue" : "Segoe UI";
    public static final Font TITULO = new Font(FUENTE_UI, Font.BOLD, 22);
    public static final Font SUBTITULO = new Font(FUENTE_UI, Font.BOLD, 14);
    public static final Font CUERPO = new Font(FUENTE_UI, Font.PLAIN, 13);
    public static final Font MONO = new Font("Monospaced", Font.PLAIN, 11);

    private TemaUI() {
    }

    public static void aplicar() {
        try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        } catch (Exception ignored) {
           
        }
        UIManager.put("Panel.background", FONDO);
        UIManager.put("Label.foreground", TEXTO);
        UIManager.put("Label.font", CUERPO);
        UIManager.put("Button.foreground", TEXTO);
        UIManager.put("Button.background", SUPERFICIE);
        UIManager.put("Button.disabledText", new Color(0x7C8792));
        UIManager.put("TextField.background", SUPERFICIE);
        UIManager.put("TextField.foreground", TEXTO);
        UIManager.put("TextField.caretForeground", TEXTO);
        UIManager.put("TextField.border", bordeCampo());
        UIManager.put("Spinner.background", SUPERFICIE);
        UIManager.put("Spinner.foreground", TEXTO);
        UIManager.put("CheckBox.background", FONDO);
        UIManager.put("CheckBox.foreground", TEXTO);
        UIManager.put("ProgressBar.background", SUPERFICIE_2);
        UIManager.put("ProgressBar.foreground", AZUL);
        UIManager.put("ProgressBar.selectionBackground", TEXTO);
        UIManager.put("ProgressBar.selectionForeground", TEXTO);
        UIManager.put("OptionPane.background", SUPERFICIE);
        UIManager.put("OptionPane.messageForeground", TEXTO);
        UIManager.put("TabbedPane.background", FONDO);
        UIManager.put("TabbedPane.foreground", TEXTO);
        UIManager.put("TabbedPane.selected", SUPERFICIE);
        UIManager.put("ScrollPane.background", SUPERFICIE);
        UIManager.put("Viewport.background", SUPERFICIE);
    }

    public static JPanel seccion(String titulo) {
        JPanel panel = new JPanel(new BorderLayout(0, 12));
        panel.setBackground(FONDO);
        panel.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(1, 0, 0, 0, BORDE),
                BorderFactory.createEmptyBorder(9, 0, 0, 0)));
        JLabel encabezado = new JLabel(titulo);
        encabezado.setForeground(TEXTO_SUAVE);
        encabezado.setFont(new Font(FUENTE_UI, Font.BOLD, 12));
        panel.add(encabezado, BorderLayout.NORTH);
        return panel;
    }

    public static JButton boton(String texto, boolean primario) {
        JButton b = new JButton(texto);
        Color normal = primario ? AZUL : SUPERFICIE;
        Color hover = primario ? new Color(0x1F608F) : new Color(0xE7ECF1);
        b.setUI(new BasicButtonUI());
        b.setFont(new Font(FUENTE_UI, Font.BOLD, 12));
        b.setForeground(primario ? Color.WHITE : TEXTO);
        b.setBackground(normal);
        b.setOpaque(true);
        b.setContentAreaFilled(true);
        b.setBorderPainted(true);
        b.setBorder(bordeBoton(primario ? AZUL : BORDE));
        b.setFocusPainted(false);
        b.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        b.setPreferredSize(new Dimension(100, 32));
        b.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseEntered(MouseEvent e) {
                if (b.isEnabled()) {
                    b.setBackground(hover);
                }
            }

            @Override
            public void mouseExited(MouseEvent e) {
                b.setBackground(normal);
            }

            @Override
            public void mousePressed(MouseEvent e) {
                if (b.isEnabled()) {
                    b.setBackground(primario ? new Color(0x194E75) : new Color(0xDCE2E8));
                }
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                b.setBackground(b.contains(e.getPoint()) ? hover : normal);
            }
        });
        b.addFocusListener(new FocusAdapter() {
            @Override
            public void focusGained(FocusEvent e) {
                b.setBorder(bordeBoton(AZUL));
            }

            @Override
            public void focusLost(FocusEvent e) {
                b.setBorder(bordeBoton(primario ? AZUL : BORDE));
            }
        });
        b.addPropertyChangeListener("enabled", e -> {
            if (b.isEnabled()) {
                b.setForeground(primario ? Color.WHITE : TEXTO);
                b.setBackground(normal);
            } else {
                b.setForeground(new Color(0x717C87));
                b.setBackground(SUPERFICIE_2);
            }
        });
        return b;
    }

    public static JTextField campo(String texto) {
        JTextField campo = new JTextField(texto);
        campo.setFont(CUERPO);
        campo.setForeground(TEXTO);
        campo.setBackground(SUPERFICIE);
        campo.setCaretColor(TEXTO);
        campo.setPreferredSize(new Dimension(100, 30));
        return campo;
    }

    public static void configurarSpinner(JSpinner spinner) {
        spinner.setFont(CUERPO);
        spinner.setForeground(TEXTO);
        spinner.setBackground(SUPERFICIE);
        spinner.setPreferredSize(new Dimension(72, 30));
        if (spinner.getModel() instanceof javax.swing.SpinnerNumberModel) {
            spinner.setEditor(new JSpinner.NumberEditor(spinner, "#")); // puerto 9876, no 9,876
        }
        Component editor = spinner.getEditor();
        if (editor instanceof JSpinner.DefaultEditor defaultEditor) {
            defaultEditor.getTextField().setForeground(TEXTO);
            defaultEditor.getTextField().setBackground(SUPERFICIE);
            defaultEditor.getTextField().setHorizontalAlignment(SwingConstants.RIGHT);
            defaultEditor.getTextField().setCaretColor(TEXTO);
        }
    }

    public static JTextArea consola() {
        JTextArea area = new JTextArea();
        area.setEditable(false);
        area.setLineWrap(true);
        area.setWrapStyleWord(true);
        area.setFont(MONO);
        area.setForeground(TEXTO);
        area.setBackground(SUPERFICIE);
        area.setCaretColor(TEXTO);
        area.setMargin(new Insets(8, 9, 8, 9));
        return area;
    }

    public static JScrollPane scroll(Component contenido) {
        JScrollPane scroll = new JScrollPane(contenido);
        scroll.setBorder(BorderFactory.createLineBorder(BORDE));
        scroll.getViewport().setBackground(SUPERFICIE);
        return scroll;
    }

    public static JLabel dato(String valor) {
        JLabel label = new JLabel(valor);
        label.setFont(new Font("Monospaced", Font.BOLD, 14));
        label.setForeground(TEXTO);
        return label;
    }

    private static Border bordeCampo() {
        return BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(BORDE),
                BorderFactory.createEmptyBorder(5, 8, 5, 8));
    }

    private static Border bordeBoton(Color color) {
        return BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(color),
                BorderFactory.createEmptyBorder(6, 11, 6, 11));
    }
}
