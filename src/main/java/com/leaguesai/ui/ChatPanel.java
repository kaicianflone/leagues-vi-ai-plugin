package com.leaguesai.ui;

import javax.swing.*;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.util.function.Consumer;

public class ChatPanel extends JPanel {

    private static final Color BACKGROUND_COLOR = new Color(30, 30, 30);
    private static final Color TEXT_COLOR = new Color(200, 200, 200);
    private static final Color ERROR_COLOR = new Color(220, 60, 60);
    private static final Color LOADING_COLOR = new Color(100, 180, 255);
    private static final Color INPUT_BACKGROUND = new Color(45, 45, 45);
    private static final Color BORDER_COLOR = new Color(60, 60, 60);

    private final JTextArea chatHistory;
    private final JScrollPane scrollPane;
    private final JTextField inputField;
    private final JButton sendButton;
    private final JLabel loadingLabel;
    private final JLabel emptyStateLabel;

    private Consumer<String> onSendMessage;
    private boolean hasMessages = false;

    public ChatPanel() {
        setLayout(new BorderLayout(0, 4));
        setBackground(BACKGROUND_COLOR);
        setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));

        // Chat history area
        chatHistory = new JTextArea();
        chatHistory.setEditable(false);
        chatHistory.setLineWrap(true);
        chatHistory.setWrapStyleWord(true);
        chatHistory.setBackground(INPUT_BACKGROUND);
        chatHistory.setForeground(TEXT_COLOR);
        chatHistory.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 12));
        chatHistory.setBorder(BorderFactory.createEmptyBorder(6, 6, 6, 6));
        chatHistory.setCaretColor(TEXT_COLOR);

        scrollPane = new JScrollPane(chatHistory);
        scrollPane.setBorder(BorderFactory.createLineBorder(BORDER_COLOR));
        scrollPane.setBackground(BACKGROUND_COLOR);
        scrollPane.getViewport().setBackground(INPUT_BACKGROUND);

        // Empty state label — overlaid on top of the scroll pane area via a layered panel
        emptyStateLabel = new JLabel("Ask the AI anything about your current tasks...");
        emptyStateLabel.setForeground(new Color(120, 120, 120));
        emptyStateLabel.setFont(new Font(Font.SANS_SERIF, Font.ITALIC, 12));
        emptyStateLabel.setHorizontalAlignment(SwingConstants.CENTER);
        emptyStateLabel.setVerticalAlignment(SwingConstants.CENTER);
        emptyStateLabel.setVisible(true);

        // Layered panel so empty state floats over scroll pane
        JLayeredPane centerLayer = new JLayeredPane() {
            @Override
            public void doLayout() {
                for (Component c : getComponents()) {
                    c.setBounds(0, 0, getWidth(), getHeight());
                }
            }
        };
        centerLayer.add(scrollPane, JLayeredPane.DEFAULT_LAYER);
        centerLayer.add(emptyStateLabel, JLayeredPane.PALETTE_LAYER);
        centerLayer.setPreferredSize(new Dimension(200, 300));
        centerLayer.setBackground(BACKGROUND_COLOR);
        centerLayer.setOpaque(false);

        add(centerLayer, BorderLayout.CENTER);

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
        hasMessages = true;
        emptyStateLabel.setVisible(false);

        String existing = chatHistory.getText();
        String separator = existing.isEmpty() ? "" : "\n";
        chatHistory.append(separator + sender + ": " + message);

        // Scroll to bottom
        SwingUtilities.invokeLater(() -> {
            JScrollBar vertical = scrollPane.getVerticalScrollBar();
            vertical.setValue(vertical.getMaximum());
        });
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
        hasMessages = true;
        emptyStateLabel.setVisible(false);

        // Append styled error via HTML workaround — use a red marker in plain text
        String existing = chatHistory.getText();
        String separator = existing.isEmpty() ? "" : "\n";
        chatHistory.setForeground(ERROR_COLOR);
        chatHistory.append(separator + "[Error] " + message);
        chatHistory.setForeground(TEXT_COLOR);

        // Reset color to default after append so future messages look normal
        SwingUtilities.invokeLater(() -> {
            chatHistory.setForeground(TEXT_COLOR);
            JScrollBar vertical = scrollPane.getVerticalScrollBar();
            vertical.setValue(vertical.getMaximum());
        });
    }

    public void clearChat() {
        chatHistory.setText("");
        hasMessages = false;
        emptyStateLabel.setVisible(true);
    }
}
