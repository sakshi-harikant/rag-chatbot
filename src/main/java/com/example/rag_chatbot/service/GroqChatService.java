package com.example.rag_chatbot.service;

import com.example.rag_chatbot.entity.ChatMessage;
import com.example.rag_chatbot.entity.ChatSession;
import com.example.rag_chatbot.entity.DocumentChunk;
import com.example.rag_chatbot.repository.ChatMessageRepository;
import com.example.rag_chatbot.repository.ChatSessionRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.LocalDateTime;
import java.util.*;

@Service
public class GroqChatService {

    @Autowired
    private ChatSessionRepository chatSessionRepository;

    @Autowired
    private ChatMessageRepository chatMessageRepository;

    @Autowired
    private RAGService ragService;

    private final WebClient webClient;
    private final String model;

    public GroqChatService(
            @Value("${groq.api.url}") String apiUrl,
            @Value("${groq.api.key}") String apiKey,
            @Value("${groq.model}") String model) {

        this.model = model;
        this.webClient = WebClient.builder()
                .baseUrl(apiUrl)
                .defaultHeader("Authorization", "Bearer " + apiKey)
                .defaultHeader("Content-Type", "application/json")
                .build();
    }

    public Map<String, Object> sendMessage(String userId, String sessionId, String message) {

        // 1️⃣ Clean up sessionId
        if (sessionId != null && (sessionId.equals("null") || sessionId.equals("undefined") || sessionId.trim().isEmpty())) {
            sessionId = null;
        }

        // 2️⃣ Get or create session
        ChatSession session;
        if (sessionId == null) {
            session = new ChatSession();
            session.setUserId(userId);
            session.setTitle(message.length() > 30 ? message.substring(0, 30) + "..." : message);
            session.setCreatedAt(LocalDateTime.now());
            session.setUpdatedAt(LocalDateTime.now());
            session = chatSessionRepository.save(session);
            sessionId = session.getId();
            System.out.println("🆕 NEW SESSION CREATED: " + sessionId);
        } else {
            session = chatSessionRepository.findById(sessionId).orElse(null);
            if (session == null) {
                session = new ChatSession();
                session.setUserId(userId);
                session.setTitle("Fallback " + LocalDateTime.now());
                session.setCreatedAt(LocalDateTime.now());
                session.setUpdatedAt(LocalDateTime.now());
                session = chatSessionRepository.save(session);
                sessionId = session.getId();
                System.out.println("⚠️ FALLBACK SESSION CREATED: " + sessionId);
            } else {
                System.out.println("♻️ USING EXISTING SESSION: " + sessionId);
            }
        }

        // 3️⃣ Save user message
        ChatMessage userMsg = new ChatMessage();
        userMsg.setSessionId(sessionId);
        userMsg.setRole("user");
        userMsg.setContent(message);
        userMsg.setTimestamp(LocalDateTime.now());
        chatMessageRepository.save(userMsg);

        // 4️⃣ RAG: Search relevant chunks
        // 4️⃣ RAG: Search relevant chunks
        System.out.println("🔍 Calling searchChunks with sessionId: " + sessionId);
        List<DocumentChunk> relevantChunks = ragService.searchChunks(userId, sessionId, message);
        System.out.println("🔍 Found " + relevantChunks.size() + " relevant chunks");
        // 5️⃣ Build context from chunks
        StringBuilder context = new StringBuilder();
        List<String> sources = new ArrayList<>();
        for (DocumentChunk chunk : relevantChunks) {
            context.append(chunk.getContent()).append("\n\n");
            String docName = chunk.getDocument() != null ? chunk.getDocument().getFileName() : "Unknown";
            sources.add(docName);
        }

        // 6️⃣ Build conversation history
        List<ChatMessage> history = chatMessageRepository.findBySessionIdOrderByTimestampAsc(sessionId);
        List<Map<String, String>> groqMessages = new ArrayList<>();

        // System prompt with RAG context
        String systemPrompt = "You are a helpful AI assistant. Answer questions based on the provided context. If the answer is not in the context, say 'I don't have enough information to answer that.'";
        if (context.length() > 0) {
            systemPrompt += "\n\nContext from uploaded documents:\n" + context.toString();
        }
        System.out.println("📄 System prompt length: " + systemPrompt.length());
        System.out.println("📄 Context length: " + context.length());

        Map<String, String> systemMsg = new HashMap<>();
        systemMsg.put("role", "system");
        systemMsg.put("content", systemPrompt);
        groqMessages.add(systemMsg);

        // Add last 10 messages
        int start = Math.max(0, history.size() - 10);
        for (int i = start; i < history.size(); i++) {
            ChatMessage msg = history.get(i);
            Map<String, String> m = new HashMap<>();
            m.put("role", msg.getRole());
            m.put("content", msg.getContent());
            groqMessages.add(m);
        }

        // 7️⃣ Call Groq API
        String botReply;
        try {
            Map<String, Object> requestBody = new HashMap<>();
            requestBody.put("model", model);
            requestBody.put("messages", groqMessages);
            requestBody.put("temperature", 0.7);

            System.out.println("📤 Calling Groq API with model: " + model);
            System.out.println("📤 Request body: " + requestBody);

            Map<String, Object> response = webClient.post()
                    .bodyValue(requestBody)
                    .retrieve()
                    .bodyToMono(Map.class)
                    .block();

            List<Map<String, Object>> choices = (List<Map<String, Object>>) response.get("choices");
            Map<String, Object> firstChoice = choices.get(0);
            Map<String, String> botMessage = (Map<String, String>) firstChoice.get("message");
            botReply = botMessage.get("content");
            System.out.println("📥 Groq response received");

        } catch (Exception e) {
            e.printStackTrace();
            botReply = "I'm having trouble connecting to my AI service. Please try again later.";
            System.err.println("❌ Groq API error: " + e.getMessage());
        }

        // 8️⃣ Save bot response with sources
        ChatMessage botMsg = new ChatMessage();
        botMsg.setSessionId(sessionId);
        botMsg.setRole("assistant");
        botMsg.setContent(botReply);
        botMsg.setTimestamp(LocalDateTime.now());
        botMsg.setSources(sources);
        chatMessageRepository.save(botMsg);

        // 9️⃣ Update session timestamp
        session.setUpdatedAt(LocalDateTime.now());
        chatSessionRepository.save(session);

        // 🔟 Return response
        Map<String, Object> result = new HashMap<>();
        result.put("answer", botReply);
        result.put("sessionId", sessionId);
        result.put("sources", sources);

        System.out.println("📤 RETURNING sessionId: " + sessionId);
        return result;
    }
}