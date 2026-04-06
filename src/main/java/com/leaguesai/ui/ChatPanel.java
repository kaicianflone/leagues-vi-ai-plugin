package com.leaguesai.ui;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.awt.datatransfer.StringSelection;
import java.awt.event.ActionEvent;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.util.ArrayList;
import java.util.List;
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
    /** Cap on retained message history to keep memory bounded across long sessions. */
    private static final int MAX_CHAT_HISTORY = 200;

    private final JPanel messageList;
    private final JScrollPane scrollPane;
    private final JTextField inputField;
    private final JButton sendButton;
    private final JButton copyButton;
    private final JButton goalsLinkButton;
    private final JLabel loadingLabel;
    private final JLabel heartbeatLabel;
    private final List<String> messageHistory = new ArrayList<>();

    private JPanel emptyStatePanel;

    private Consumer<String> onSendMessage;
    private Runnable onOpenGoals;

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

        // Top toolbar: [Goals link  ··  Copy chat]
        JPanel toolbar = new JPanel(new BorderLayout());
        toolbar.setBackground(BACKGROUND_COLOR);
        toolbar.setBorder(BorderFactory.createEmptyBorder(0, 0, 4, 0));

        goalsLinkButton = new JButton("\uD83C\uDFAF Goals");
        goalsLinkButton.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 10));
        goalsLinkButton.setBackground(new Color(55, 55, 55));
        goalsLinkButton.setForeground(new Color(120, 170, 240));
        goalsLinkButton.setFocusPainted(false);
        goalsLinkButton.setBorderPainted(false);
        goalsLinkButton.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        goalsLinkButton.setMargin(new Insets(2, 8, 2, 8));
        goalsLinkButton.addActionListener(e -> {
            if (onOpenGoals != null) onOpenGoals.run();
        });

        copyButton = new JButton("Copy chat");
        copyButton.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 10));
        copyButton.setBackground(new Color(55, 55, 55));
        copyButton.setForeground(TEXT_COLOR);
        copyButton.setFocusPainted(false);
        copyButton.setBorderPainted(false);
        copyButton.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        copyButton.setMargin(new Insets(2, 8, 2, 8));
        copyButton.addActionListener(e -> copyChatToClipboard());

        toolbar.add(goalsLinkButton, BorderLayout.WEST);
        toolbar.add(copyButton, BorderLayout.EAST);

        JPanel center = new JPanel(new BorderLayout());
        center.setBackground(BACKGROUND_COLOR);
        center.add(toolbar, BorderLayout.NORTH);
        center.add(scrollPane, BorderLayout.CENTER);
        add(center, BorderLayout.CENTER);

        appendEmptyState();

        // Loading indicator
        loadingLabel = new JLabel("AI is thinking...");
        loadingLabel.setForeground(LOADING_COLOR);
        loadingLabel.setFont(new Font(Font.SANS_SERIF, Font.ITALIC, 11));
        loadingLabel.setHorizontalAlignment(SwingConstants.LEFT);
        loadingLabel.setVisible(false);
        loadingLabel.setBorder(BorderFactory.createEmptyBorder(2, 2, 2, 2));

        // Heartbeat label — driven by HeartbeatTicker every 60s
        heartbeatLabel = new JLabel(" ");
        heartbeatLabel.setForeground(new Color(150, 200, 150));
        heartbeatLabel.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 11));
        heartbeatLabel.setBorder(BorderFactory.createEmptyBorder(2, 2, 4, 2));

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

        // North of the input row stacks heartbeat + loading indicator
        JPanel northStack = new JPanel();
        northStack.setLayout(new BoxLayout(northStack, BoxLayout.Y_AXIS));
        northStack.setBackground(BACKGROUND_COLOR);
        heartbeatLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        loadingLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        northStack.add(heartbeatLabel);
        northStack.add(loadingLabel);

        inputPanel.add(northStack, BorderLayout.NORTH);
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
        messageHistory.add(sender + ": " + message);
        // Cap history to bound memory across very long sessions.
        while (messageHistory.size() > MAX_CHAT_HISTORY) {
            messageHistory.remove(0);
        }
        SwingUtilities.invokeLater(() -> {
            clearEmptyStateIfPresent();
            boolean isUser = "You".equalsIgnoreCase(sender);
            JPanel bubble = createBubble(message, isUser);
            JPanel row = new JPanel() {
                @Override
                public Dimension getMaximumSize() {
                    return new Dimension(Integer.MAX_VALUE, getPreferredSize().height);
                }
            };
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
        Dimension bubblePref = new Dimension(BUBBLE_MAX_WIDTH + 24, height + 16);
        bubble.setPreferredSize(bubblePref);
        bubble.setMaximumSize(bubblePref);
        return bubble;
    }

    private void appendEmptyState() {
        emptyStatePanel = new JPanel() {
            @Override
            public Dimension getMaximumSize() {
                return new Dimension(Integer.MAX_VALUE, getPreferredSize().height);
            }
        };
        emptyStatePanel.setLayout(new BoxLayout(emptyStatePanel, BoxLayout.Y_AXIS));
        emptyStatePanel.setBackground(messageList.getBackground());
        // HTML label so Swing wraps the text. Width matches panel content area.
        JLabel label = new JLabel(
                "<html><div style='text-align:center;width:170px'>"
                        + "Ask your AI coach anything about Leagues VI..."
                        + "</div></html>");
        label.setForeground(new Color(150, 150, 150));
        label.setAlignmentX(Component.CENTER_ALIGNMENT);
        label.setHorizontalAlignment(SwingConstants.CENTER);
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
            errPanel.setMaximumSize(new Dimension(220, errPanel.getPreferredSize().height));
            JPanel row = new JPanel() {
                @Override
                public Dimension getMaximumSize() {
                    return new Dimension(Integer.MAX_VALUE, getPreferredSize().height);
                }
            };
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

    /** Heartbeat label setter — driven by HeartbeatTicker. EDT-safe. */
    public void setHeartbeatText(String text) {
        SwingUtilities.invokeLater(() ->
                heartbeatLabel.setText(text == null || text.isEmpty() ? " " : text));
    }

    /** Wire the "Goals" link in the toolbar. */
    public void setOnOpenGoals(Runnable callback) {
        this.onOpenGoals = callback;
    }

    public void clearChat() {
        messageHistory.clear();
        SwingUtilities.invokeLater(() -> {
            messageList.removeAll();
            appendEmptyState();
            messageList.revalidate();
            messageList.repaint();
        });
    }

    private void copyChatToClipboard() {
        if (messageHistory.isEmpty()) {
            flashCopyButton("Nothing to copy");
            return;
        }
        String dump = String.join("\n\n", messageHistory);
        try {
            Toolkit.getDefaultToolkit()
                    .getSystemClipboard()
                    .setContents(new StringSelection(dump), null);
            flashCopyButton("Copied!");
        } catch (Exception e) {
            flashCopyButton("Copy failed");
        }
    }

    private void flashCopyButton(String text) {
        String original = "Copy chat";
        copyButton.setText(text);
        Timer t = new Timer(1500, e -> copyButton.setText(original));
        t.setRepeats(false);
        t.start();
    }

    private String escapeHtml(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
