package com.example.rag_chatbot;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import java.util.TimeZone;

@SpringBootApplication
public class RagChatbotApplication {

	public static void main(String[] args) {
		TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
		SpringApplication.run(RagChatbotApplication.class, args);
		System.out.println("🚀 RAG Chatbot is running!");
		System.out.println("📍 Open: http://localhost:8080");
	}
}