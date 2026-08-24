package com.rag.documentChatBot;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;

@Service
public class LoginNotificationService {
    private static final Logger logger = LoggerFactory.getLogger(LoginNotificationService.class);

    private final JavaMailSender mailSender;
    private final String sender;

    public LoginNotificationService(
            JavaMailSender mailSender,
            @Value("${spring.mail.username:}") String sender) {
        this.mailSender = mailSender;
        this.sender = sender;
    }

    public void sendLoginSuccess(String userName, String userEmail) {
        if (userEmail == null || userEmail.isBlank() || sender.isBlank()) {
            logger.warn("Login notification skipped because the authenticated email or mail sender is not configured");
            return;
        }

        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(sender);
        message.setTo(userEmail);
        message.setSubject("Login successful - Document ChatBot");
        message.setText("Login successfully completed for the Document ChatBot application.\n\n"
                + "User: " + userName + "\n"
                + "Email: " + userEmail);

        try {
            mailSender.send(message);
        } catch (RuntimeException exception) {
            logger.error("Could not send the login success notification", exception);
        }
    }
}