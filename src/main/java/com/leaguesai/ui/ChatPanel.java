package com.leaguesai.ui;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.util.function.Consumer;

public class ChatPanel extends JPanel {

    private static final Color BACKGROUND_COLOR = new Color(40, 40, 40);
    private static final Color TEXT_COLOR = new Color(200, 200, 200);
    private static final Color LOADING_COLOR = new Color(100, 180, 255);
    private static final Color INPUT_BACKGROUND = new Color(45, 45, 45);
    private static final Color BORDER_COLOR = new Color(60, 60, 60);
    private static final Color USER_BUBBLE = new Color(0, 122, 255);
    private static final Color AI_BUBBLE = new Color(60, 60, 60);
    private static final Color ERROR_BUBBLE = new Color(120, 30, 30);
    private static final int BUBBLE_MAX_WIDTH = 180;

    private final JPanel messageList;
    private final JScrollPane scrollPane;
    private final JTextField inputField;
    private final JButton sendButton;
    private final JLabel loadingLabel;

    private JPanel emptyStatePanel;

    private Consumer<String> onSendMessage;

    public ChatPanel() {
        setLayout(new BorderLayout(0, 4));
        setBackground(BACKGROUND_COLOR);
        setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));

        // Message list — vertical stack of bubble rows
        messageList = new JPanel();
        messageList.setLayout(new BoxLayout(messageList, BoxLayout.Y_AXIS));
        messageList.setBackground(BACKGROUND_COLOR);
        messageList.setBorder(new EmptyBorder(8, 8, 8, 8));

        scrollPane = new JScrollPane(messageList);
        scrollPane.setVerticalScrollBarPolicy(ScrollPaneConstants.VERTICAL_SCROLLBAR_ALWAYS);
        scrollPane.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        scrollPane.setBorder(null);
        scrollPane.getViewport().setBackground(BACKGROUND_COLOR);
        add(scrollPane, BorderLayout.CENTER);

        appendEmptyState();

        // Loading indicator
        loadingLabel = new JLabel("AI is thinking...");
        loadingLabel.setForeground(LOADING_COLOR);
        loadingLabel.setFont(new Font(Font.SANS_SERIF, Font.ITALIC, 11));
        loadingLabel.setHorizontalAlignment(SwingConstants.LEFT);
        loadingLabel.setVisible(false);
        loadingLabel.setBorder(BorderFactory.createEmptyBorder(2, 2, 2, 2));

        // Input panel
        JPanel inputPanel = new JPanel(new BorderLayout(4, 0));
        inputPanel.setBackground(BACKGROUND_COLOR);
        inputPanel.setBorder(BorderFactory.createEmptyBorder(4, 0, 0, 0));

        inputField = new JTextField();
        inputField.setBackground(INPUT_BACKGROUND);
        inputField.setForeground(TEXT_COLOR);
        inputField.setCaretColor(TEXT_COLOR);
        inputField.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 12));
        inputField.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(BORDER_COLOR),
                BorderFactory.createEmptyBorder(4, 6, 4, 6)
        ));

        sendButton = new JButton("Send");
        sendButton.setBackground(new Color(60, 100, 60));
        sendButton.setForeground(Color.WHITE);
        sendButton.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 12));
        sendButton.setFocusPainted(false);
        sendButton.setBorderPainted(false);
        sendButton.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));

        inputPanel.add(loadingLabel, BorderLayout.NORTH);
        inputPanel.add(inputField, BorderLayout.CENTER);
        inputPanel.add(sendButton, BorderLayout.EAST);

        add(inputPanel, BorderLayout.SOUTH);

        // Wire up actions
        sendButton.addActionListener((ActionEvent e) -> triggerSend());
        inputField.addKeyListener(new KeyAdapter() {
            @Override
            public void keyPressed(KeyEvent e) {
                if (e.getKeyCode() == KeyEvent.VK_ENTER) {
                    triggerSend();
                }
            }
        });
    }

    private void triggerSend() {
        String text = inputField.getText().trim();
        if (text.isEmpty() || onSendMessage == null) {
            return;
        }
        appendMessage("You", text);
        inputField.setText("");
        onSendMessage.accept(text);
    }

    public void appendMessage(String sender, String message) {
        SwingUtilities.invokeLater(() -> {
            clearEmptyStateIfPresent();
            boolean isUser = "You".equalsIgnoreCase(sender);
            JPanel bubble = createBubble(message, isUser);
            JPanel row = new JPanel();
            row.setLayout(new BoxLayout(row, BoxLayout.X_AXIS));
            row.setBackground(messageList.getBackground());
            row.setAlignmentX(Component.LEFT_ALIGNMENT);
            if (isUser) {
                row.add(Box.createHorizontalGlue());
                row.add(bubble);
            } else {
                row.add(bubble);
                row.add(Box.createHorizontalGlue());
            }
            messageList.add(row);
            messageList.add(Box.createVerticalStrut(4));
            messageList.revalidate();
            messageList.repaint();
            SwingUtilities.invokeLater(() -> {
                JScrollBar bar = scrollPane.getVerticalScrollBar();
                bar.setValue(bar.getMaximum());
            });
        });
    }

    private JPanel createBubble(String text, boolean isUser) {
        JPanel bubble = new JPanel();
        bubble.setLayout(new BorderLayout());
        Color bgColor = isUser ? USER_BUBBLE : AI_BUBBLE;
        Color fgColor = Color.WHITE;
        bubble.setBackground(bgColor);
        bubble.setBorder(new EmptyBorder(8, 12, 8, 12));

        JTextArea textArea = new JTextArea(text);
        textArea.setEditable(false);
        textArea.setLineWrap(true);
        textArea.setWrapStyleWord(true);
        textArea.setOpaque(false);
        textArea.setForeground(fgColor);
        textArea.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 12));
        textArea.setBorder(null);

        textArea.setSize(BUBBLE_MAX_WIDTH, Short.MAX_VALUE);
        int height = textArea.getPreferredSize().height;
        textArea.setPreferredSize(new Dimension(BUBBLE_MAX_WIDTH, height));

        bubble.add(textArea, BorderLayout.CENTER);
        bubble.setMaximumSize(new Dimension(BUBBLE_MAX_WIDTH + 24, Integer.MAX_VALUE));
        return bubble;
    }

    private void appendEmptyState() {
        emptyStatePanel = new JPanel();
        emptyStatePanel.setLayout(new BoxLayout(emptyStatePanel, BoxLayout.Y_AXIS));
        emptyStatePanel.setBackground(messageList.getBackground());
        JLabel label = new JLabel("Ask your AI coach anything about Leagues VI...");
        label.setForeground(new Color(150, 150, 150));
        label.setAlignmentX(Component.CENTER_ALIGNMENT);
        label.setFont(new Font(Font.SANS_SERIF, Font.ITALIC, 11));
        emptyStatePanel.add(Box.createVerticalStrut(40));
        emptyStatePanel.add(label);
        messageList.add(emptyStatePanel);
    }

    private void clearEmptyStateIfPresent() {
        if (emptyStatePanel != null) {
            messageList.remove(emptyStatePanel);
            emptyStatePanel = null;
        }
    }

    public void setOnSendMessage(Consumer<String> callback) {
        this.onSendMessage = callback;
    }

    public void setLoading(boolean loading) {
        loadingLabel.setVisible(loading);
        inputField.setEnabled(!loading);
        sendButton.setEnabled(!loading);
    }

    public void showError(String message) {
        SwingUtilities.invokeLater(() -> {
            clearEmptyStateIfPresent();
            JPanel errPanel = new JPanel();
            errPanel.setBackground(ERROR_BUBBLE);
            errPanel.setBorder(new EmptyBorder(6, 10, 6, 10));
            errPanel.setLayout(new BorderLayout());
            JLabel label = new JLabel("<html><div style='width:180px'>&#9888; "
                    + escapeHtml(message) + "</div></html>");
            label.setForeground(Color.WHITE);
            label.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 11));
            errPanel.add(label, BorderLayout.CENTER);
            errPanel.setMaximumSize(new Dimension(220, Integer.MAX_VALUE));
            JPanel row = new JPanel();
            row.setLayout(new BoxLayout(row, BoxLayout.X_AXIS));
            row.setBackground(messageList.getBackground());
            row.add(errPanel);
            row.add(Box.createHorizontalGlue());
            messageList.add(row);
            messageList.add(Box.createVerticalStrut(4));
            messageList.revalidate();
            messageList.repaint();
            SwingUtilities.invokeLater(() -> {
                JScrollBar bar = scrollPane.getVerticalScrollBar();
                bar.setValue(bar.getMaximum());
            });
        });
    }

    public void clearChat() {
        SwingUtilities.invokeLater(() -> {
            messageList.removeAll();
            appendEmptyState();
            messageList.revalidate();
            messageList.repaint();
        });
    }

    private String escapeHtml(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
