package com.example.rag_chatbot.service;

import com.example.rag_chatbot.entity.Document;
import com.example.rag_chatbot.entity.DocumentChunk;
import com.example.rag_chatbot.repository.DocumentChunkRepository;
import com.example.rag_chatbot.repository.DocumentRepository;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableRow;
import org.apache.poi.xwpf.usermodel.XWPFTableCell;
import org.jsoup.Jsoup;
import net.sourceforge.tess4j.ITesseract;
import net.sourceforge.tess4j.Tesseract;
import java.awt.image.BufferedImage;
import javax.imageio.ImageIO;
import java.nio.charset.StandardCharsets;


import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional


public class RAGService {

    @Autowired
    private DocumentRepository documentRepository;

    @Autowired
    private DocumentChunkRepository documentChunkRepository;

    private static final int CHUNK_SIZE = 500; // characters per chunk

    // Upload and process document
    public Document uploadDocument(String userId, String sessionId, MultipartFile file) throws Exception {
        Document doc = new Document();
        doc.setUserId(userId);

        doc.setSessionId(sessionId);  // ✅ Associate with chat session
        doc.setFileName(file.getOriginalFilename());
        doc.setFileType(file.getContentType());
        doc.setFileSize(file.getSize());
        doc.setUploadDate(LocalDateTime.now());
        doc.setStatus("PROCESSING");
        doc = documentRepository.save(doc);

        // 2. Extract text
        String text = extractText(file);

        // 3. Split into chunks
        List<String> chunks = splitText(text, CHUNK_SIZE);

        // 4. Save chunks
        for (int i = 0; i < chunks.size(); i++) {
            DocumentChunk chunk = new DocumentChunk();
            chunk.setDocument(doc);
            chunk.setContent(chunks.get(i));
            chunk.setChunkIndex(i);
            // Store embedding as empty string for now (will be used later)
            chunk.setEmbedding("");
            documentChunkRepository.save(chunk);
        }

        // 5. Update document status
        doc.setStatus("READY");
        return documentRepository.save(doc);
    }

    private String extractText(MultipartFile file) throws Exception {
        String fileName = file.getOriginalFilename();
        if (fileName == null) {
            throw new Exception("Filename is missing");
        }
        String lowerName = fileName.toLowerCase();

        // PDF
        if (lowerName.endsWith(".pdf")) {
            try (PDDocument document = Loader.loadPDF(file.getBytes())) {
                PDFTextStripper stripper = new PDFTextStripper();
                return stripper.getText(document);
            }
        }

        // DOCX
        if (lowerName.endsWith(".docx")) {
            try (XWPFDocument document = new XWPFDocument(file.getInputStream())) {
                StringBuilder text = new StringBuilder();
                for (XWPFParagraph paragraph : document.getParagraphs()) {
                    text.append(paragraph.getText()).append("\n");
                }
                for (XWPFTable table : document.getTables()) {
                    for (XWPFTableRow row : table.getRows()) {
                        for (XWPFTableCell cell : row.getTableCells()) {
                            text.append(cell.getText()).append(" ");
                        }
                        text.append("\n");
                    }
                }
                return text.toString();
            }
        }

        // HTML
        if (lowerName.endsWith(".html") || lowerName.endsWith(".htm")) {
            String html = new String(file.getBytes(), StandardCharsets.UTF_8);
            org.jsoup.nodes.Document doc = Jsoup.parse(html);
            return doc.text();
        }

        // Images (OCR)
        if (lowerName.endsWith(".png") || lowerName.endsWith(".jpg") ||
                lowerName.endsWith(".jpeg") || lowerName.endsWith(".bmp") ||
                lowerName.endsWith(".gif") || lowerName.endsWith(".tiff")) {
            return extractTextFromImage(file);
        }

        // TXT
        if (lowerName.endsWith(".txt")) {
            return new String(file.getBytes(), StandardCharsets.UTF_8);
        }

        throw new Exception("Unsupported file type. Supported: PDF, DOCX, HTML, TXT, PNG, JPG, BMP, GIF, TIFF");
    }
    // Split text into chunks
    private List<String> splitText(String text, int chunkSize) {
        List<String> chunks = new ArrayList<>();
        String[] sentences = text.split("(?<=[.!?])\\s+");

        StringBuilder currentChunk = new StringBuilder();
        for (String sentence : sentences) {
            if (currentChunk.length() + sentence.length() > chunkSize && currentChunk.length() > 0) {
                chunks.add(currentChunk.toString());
                currentChunk = new StringBuilder();
            }
            currentChunk.append(sentence).append(" ");
        }

        if (currentChunk.length() > 0) {
            chunks.add(currentChunk.toString().trim());
        }

        return chunks;
    }
    private String extractTextFromImage(MultipartFile file) throws Exception {
        try {
            BufferedImage image = ImageIO.read(file.getInputStream());
            if (image == null) {
                throw new Exception("Could not read image file");
            }

            ITesseract tesseract = new Tesseract();
            // ✅ Set the data path to the tessdata folder
            tesseract.setDatapath("./src/main/resources/tessdata");
            tesseract.setLanguage("eng");
            tesseract.setOcrEngineMode(1);
            tesseract.setPageSegMode(3);

            String result = tesseract.doOCR(image);
            System.out.println("📄 OCR Result length: " + (result != null ? result.length() : 0));

            return result != null ? result.trim() : "";

        } catch (Exception e) {
            System.err.println("❌ OCR Error: " + e.getMessage());
            e.printStackTrace();
            throw new Exception("OCR failed: " + e.getMessage());
        }
    }
    public List<DocumentChunk> searchChunks(String userId, String sessionId, String query) {
        System.out.println("🔍 ===== SEARCH CHUNKS CALLED =====");
        System.out.println("🔍 userId: " + userId);
        System.out.println("🔍 sessionId: " + sessionId);
        System.out.println("🔍 query: " + query);

        List<Document> documents = new ArrayList<>();

        // ✅ Try to find documents for this session
        if (sessionId != null && !sessionId.isEmpty()) {
            documents = documentRepository.findByUserIdAndSessionId(userId, sessionId);
            System.out.println("🔍 Found " + documents.size() + " documents for session: " + sessionId);

            // ✅ If no documents found with sessionId, try native query
            if (documents.isEmpty()) {
                documents = documentRepository.findByUserIdAndSessionIdNative(userId, sessionId);
                System.out.println("🔍 Native query found " + documents.size() + " documents for session: " + sessionId);
            }
        }

        // ✅ Fallback: get documents with null sessionId
        if (documents.isEmpty()) {
            documents = documentRepository.findByUserIdAndSessionId(userId, null);
            System.out.println("🔍 Fallback: Found " + documents.size() + " documents with null sessionId");
        }

        if (documents.isEmpty()) {
            System.out.println("⚠️ NO DOCUMENTS FOUND!");
            return new ArrayList<>();
        }

        // Print document names
        for (Document doc : documents) {
            System.out.println("📄 Document in this session: " + doc.getFileName() + " (sessionId: " + doc.getSessionId() + ")");
        }

        // Get all chunks from these documents
        List<DocumentChunk> allChunks = new ArrayList<>();
        for (Document doc : documents) {
            List<DocumentChunk> chunks = documentChunkRepository.findByDocumentId(doc.getId());
            System.out.println("📊 Document " + doc.getFileName() + " has " + chunks.size() + " chunks");
            allChunks.addAll(chunks);
        }

        System.out.println("📊 Total chunks in this session: " + allChunks.size());

        if (allChunks.isEmpty()) {
            System.out.println("⚠️ NO CHUNKS FOUND!");
            return new ArrayList<>();
        }

        // Search for matches
        // Search for matches - IMPROVED
        String[] keywords = query.toLowerCase().split("\\s+");
        System.out.println("🔑 Keywords: " + Arrays.toString(keywords));

        List<DocumentChunk> results = new ArrayList<>();
        for (DocumentChunk chunk : allChunks) {
            String content = chunk.getContent().toLowerCase();
            int matchCount = 0;

            for (String keyword : keywords) {
                if (keyword.length() > 2 && content.contains(keyword)) {
                    matchCount++;
                }
            }

            // ✅ If at least 1 keyword matches, include the chunk
            if (matchCount > 0) {
                results.add(chunk);
                System.out.println("✅ Found match in chunk: " + content.substring(0, Math.min(100, content.length())) + "...");
            }

            if (results.size() >= 5) break;
        }

        System.out.println("📊 Found " + results.size() + " matching chunks in this session");
        return results;
    }
    // Get all documents for a user
    public List<Document> getUserDocuments(String userId) {
        return documentRepository.findByUserId(userId);
    }

    // Delete document
    public void deleteDocument(String documentId) {
        documentRepository.deleteById(documentId);
    }
}