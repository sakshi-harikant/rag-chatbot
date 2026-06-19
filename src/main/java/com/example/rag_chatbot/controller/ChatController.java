package com.example.rag_chatbot.controller;

import com.example.rag_chatbot.entity.ChatMessage;
import com.example.rag_chatbot.entity.ChatSession;
import com.example.rag_chatbot.entity.Document;
import com.example.rag_chatbot.entity.User;
import com.example.rag_chatbot.entity.DocumentChunk;
import com.example.rag_chatbot.repository.ChatMessageRepository;
import com.example.rag_chatbot.repository.ChatSessionRepository;
import com.example.rag_chatbot.repository.DocumentChunkRepository;
import com.example.rag_chatbot.repository.UserRepository;
import com.example.rag_chatbot.service.GroqChatService;
import com.example.rag_chatbot.service.RAGService;
import com.example.rag_chatbot.service.UserService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDateTime;
import java.util.*;

@RestController
public class ChatController {

    @Autowired
    private ChatSessionRepository chatSessionRepository;

    @Autowired
    private ChatMessageRepository chatMessageRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private UserService userService;

    @Autowired
    private GroqChatService groqChatService;

    @Autowired
    private RAGService ragService;

    @Autowired
    private DocumentChunkRepository documentChunkRepository;

    @GetMapping("/user")
    public Map<String, String> getUser(@AuthenticationPrincipal OAuth2User oauthUser) {
        User user = userService.getOrCreateUser(oauthUser);
        Map<String, String> info = new HashMap<>();
        info.put("name", user.getName());
        info.put("email", user.getEmail());
        info.put("picture", user.getPicture());
        return info;
    }

    @GetMapping("/api/sessions")
    public List<ChatSession> getSessions(@AuthenticationPrincipal OAuth2User oauthUser) {
        User user = userService.getOrCreateUser(oauthUser);
        return chatSessionRepository.findByUserIdOrderByUpdatedAtDesc(user.getId());
    }

    @GetMapping("/api/messages/{sessionId}")
    public List<ChatMessage> getMessages(@PathVariable String sessionId) {
        return chatMessageRepository.findBySessionIdOrderByTimestampAsc(sessionId);
    }

    @PostMapping("/api/chat")
    public Map<String, Object> chat(
            @AuthenticationPrincipal OAuth2User oauthUser,
            @RequestBody Map<String, String> request) {

        User user = userService.getOrCreateUser(oauthUser);
        String message = request.get("message");
        String sessionId = request.get("sessionId");

        return groqChatService.sendMessage(user.getId(), sessionId, message);
    }

    @DeleteMapping("/api/sessions/{sessionId}")
    public Map<String, String> deleteSession(
            @AuthenticationPrincipal OAuth2User oauthUser,
            @PathVariable String sessionId) {

        User user = userService.getOrCreateUser(oauthUser);

        ChatSession session = chatSessionRepository.findById(sessionId)
                .orElseThrow(() -> new RuntimeException("Session not found"));

        if (!session.getUserId().equals(user.getId())) {
            throw new RuntimeException("Unauthorized: You don't own this session");
        }

        List<ChatMessage> messages = chatMessageRepository.findBySessionIdOrderByTimestampAsc(sessionId);
        chatMessageRepository.deleteAll(messages);
        chatSessionRepository.delete(session);

        Map<String, String> response = new HashMap<>();
        response.put("message", "Chat deleted successfully");
        response.put("sessionId", sessionId);
        return response;
    }

    @PutMapping("/api/sessions/{sessionId}")
    public Map<String, String> renameSession(
            @AuthenticationPrincipal OAuth2User oauthUser,
            @PathVariable String sessionId,
            @RequestBody Map<String, String> request) {

        User user = userService.getOrCreateUser(oauthUser);
        String newTitle = request.get("title");

        ChatSession session = chatSessionRepository.findById(sessionId)
                .orElseThrow(() -> new RuntimeException("Session not found"));

        if (!session.getUserId().equals(user.getId())) {
            throw new RuntimeException("Unauthorized");
        }

        session.setTitle(newTitle);
        session.setUpdatedAt(LocalDateTime.now());
        chatSessionRepository.save(session);

        Map<String, String> response = new HashMap<>();
        response.put("message", "Chat renamed successfully");
        response.put("sessionId", sessionId);
        response.put("title", newTitle);
        return response;
    }
    @PostMapping("/api/upload")
    public Map<String, String> uploadDocument(
            @AuthenticationPrincipal OAuth2User oauthUser,
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "sessionId", required = false) String sessionId) {  // ✅ ADD THIS

        try {
            System.out.println("📎 Uploading file: " + file.getOriginalFilename() + " | Size: " + file.getSize());

            String userId;
            if (oauthUser != null) {
                User user = userService.getOrCreateUser(oauthUser);
                userId = user.getId();
            } else {
                List<User> users = userRepository.findAll();
                if (users.isEmpty()) {
                    throw new RuntimeException("No user found. Please login first.");
                }
                userId = users.get(0).getId();
                System.out.println("⚠️ Using fallback user: " + userId);
            }

            // ✅ Pass sessionId to RAGService
            Document doc = ragService.uploadDocument(userId, sessionId, file);

            int chunkCount = 0;
            if (doc.getChunks() != null) {
                chunkCount = doc.getChunks().size();
            } else {
                List<DocumentChunk> chunks = documentChunkRepository.findByDocumentId(doc.getId());
                chunkCount = chunks.size();
            }

            Map<String, String> response = new HashMap<>();
            response.put("message", "✅ Document uploaded successfully: " + doc.getFileName());
            response.put("status", doc.getStatus());
            response.put("chunks", String.valueOf(chunkCount));
            return response;

        } catch (Exception e) {
            e.printStackTrace();
            Map<String, String> response = new HashMap<>();
            response.put("message", "❌ Error uploading document: " + e.getMessage());
            return response;
        }
    }

    @GetMapping("/api/documents")
    public List<Document> getDocuments(@AuthenticationPrincipal OAuth2User oauthUser) {
        User user = userService.getOrCreateUser(oauthUser);
        return ragService.getUserDocuments(user.getId());
    }
}