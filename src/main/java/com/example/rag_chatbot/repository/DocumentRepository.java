package com.example.rag_chatbot.repository;

import com.example.rag_chatbot.entity.Document;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.List;

public interface DocumentRepository extends JpaRepository<Document, String> {

    List<Document> findByUserId(String userId);

    // ✅ Method 1: Standard JPA method
    List<Document> findByUserIdAndSessionId(String userId, String sessionId);

    // ✅ Method 2: Native query (if method 1 doesn't work)
    @Query(value = "SELECT * FROM documents WHERE user_id = :userId AND session_id = :sessionId", nativeQuery = true)
    List<Document> findByUserIdAndSessionIdNative(@Param("userId") String userId, @Param("sessionId") String sessionId);
}