package com.example.rag_chatbot.controller;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class AuthController {

    @GetMapping("/")
    public String home(@AuthenticationPrincipal OAuth2User user) {
        if (user != null) {
            return "redirect:/chat";
        }
        return "login";
    }

    @GetMapping("/chat")
    public String chat(@AuthenticationPrincipal OAuth2User user) {
        if (user == null) {
            return "redirect:/";
        }
        return "chat";
    }
}