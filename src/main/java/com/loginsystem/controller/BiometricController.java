package com.loginsystem.controller;

import com.loginsystem.service.BiometricPythonService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/biometric")
public class BiometricController {

    @Autowired
    private BiometricPythonService biometricPythonService;

    @PostMapping("/detect-face")
    public ResponseEntity<?> detectFace(@RequestBody Map<String, String> payload) {
        String base64Image = payload.get("image");
        if (base64Image == null || base64Image.trim().isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("message", "Required parameter 'image' (Base64) is missing."));
        }

        BiometricPythonService.DetectionResult result = biometricPythonService.detectFaces(base64Image);
        
        if (result.error != null) {
            return ResponseEntity.status(500).body(result);
        }

        return ResponseEntity.ok(result);
    }
}
