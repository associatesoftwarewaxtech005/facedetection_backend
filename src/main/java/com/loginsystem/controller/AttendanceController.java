package com.loginsystem.controller;

import com.loginsystem.model.*;
import com.loginsystem.repository.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import com.loginsystem.service.BiometricPythonService;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.*;

@RestController
@RequestMapping("/api/attendance")
public class AttendanceController {

    @Autowired
    private EmployeeRepository employeeRepository;

    @Autowired
    private EmployeeFaceImageRepository employeeFaceImageRepository;

    @Autowired
    private AttendanceRecordRepository attendanceRecordRepository;

    @Autowired
    private LogRepository logRepository;

    @Autowired
    private SystemNotificationRepository systemNotificationRepository;

    @Autowired
    private EmployeeSessionRepository employeeSessionRepository;

    @Autowired
    private SecurityLogRepository securityLogRepository;

    @Autowired
    private BiometricPythonService biometricPythonService;

    @org.springframework.beans.factory.annotation.Value("${biometric.liveness-threshold:0.25}")
    private double livenessThreshold;

    // Helper to log unauthorized or failed authentication attempts
    private void createSecurityLog(String image, String eventType, Map<String, String> payload, jakarta.servlet.http.HttpServletRequest request) {
        String clientIp = payload != null ? payload.getOrDefault("ipAddress", request.getRemoteAddr()) : request.getRemoteAddr();
        String clientDevice = payload != null ? payload.getOrDefault("deviceInfo", request.getHeader("User-Agent")) : request.getHeader("User-Agent");
        String clientImage = payload != null ? payload.getOrDefault("capturedImage", image) : image;
        if (clientImage == null) clientImage = "";
        
        SecurityLog log = new SecurityLog(clientImage, eventType, clientDevice, clientIp, java.time.LocalDateTime.now().toString());
        securityLogRepository.save(log);
    }

    // ==========================================
    // CHECK-IN BIOMETRIC SCAN
    // ==========================================
    @PostMapping("/check-in")
    public ResponseEntity<?> checkIn(@RequestBody Map<String, String> payload, jakarta.servlet.http.HttpServletRequest request) {
        String scannedEmbStr = payload.get("embedding");
        String capturedImage = payload.get("capturedImage");
        String warning = null;

        if (capturedImage == null || capturedImage.trim().isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("message", "No image captured. Face detection failed."));
        }

        BiometricPythonService.DetectionResult detectionResult = biometricPythonService.detectFaces(capturedImage);
        if (detectionResult.error != null) {
            return ResponseEntity.status(500).body(Map.of("message", "Biometric engine error: " + detectionResult.error));
        }
        if (detectionResult.count == 0) {
            return ResponseEntity.badRequest().body(Map.of("message", "No face detected. Access Denied."));
        } else if (detectionResult.count > 1) {
            warning = "Multiple faces detected. Please scan one person at a time.";
        }
        
        // Liveness check: evaluated server-side from Python script score
        if (detectionResult.livenessScore < livenessThreshold) {
            systemNotificationRepository.save(new SystemNotification("Check-in blocked: Anti-spoofing liveness verification failed. Score: " + detectionResult.livenessScore, "WARN"));
            logRepository.save(new Log(LocalTime.now().toString(), "Spoof attack blocked at check-in: liveness score " + detectionResult.livenessScore + " < threshold " + livenessThreshold, "ALERT"));
            createSecurityLog(payload.get("capturedImage"), "SPOOF_ATTACK_DETECTED", payload, request);
            return ResponseEntity.status(403).body(Map.of("message", "LIVENESS FAILURE: Photo or spoofing suspected."));
        }

        Employee matchedEmployee = matchFace(scannedEmbStr, capturedImage);
        if (matchedEmployee == null) {
            systemNotificationRepository.save(new SystemNotification("Unauthorized face scan attempt at portal.", "UNKNOWN_FACE"));
            logRepository.save(new Log(LocalTime.now().toString(), "Unrecognized face scan at check-in portal", "ALERT"));
            createSecurityLog(payload.get("capturedImage"), "UNAUTHORIZED_PERSON_DETECTED", payload, request);
            return ResponseEntity.status(404).body(Map.of("message", "Unauthorized Person Detected. Access Denied."));
        }

        if (!"ACTIVE".equalsIgnoreCase(matchedEmployee.getStatus())) {
            logRepository.save(new Log(LocalTime.now().toString(), "Inactive employee " + matchedEmployee.getName() + " blocked", "ALERT"));
            createSecurityLog(payload.get("capturedImage"), "INACTIVE_ACCOUNT_BLOCKED", payload, request);
            return ResponseEntity.status(403).body(Map.of("message", "ACCESS DENIED: Account status is INACTIVE."));
        }

        LocalDate today = LocalDate.now();
        LocalTime nowTime = LocalTime.now();
        String formattedTime = String.format("%02d:%02d:%02d", nowTime.getHour(), nowTime.getMinute(), nowTime.getSecond());
        
        Optional<AttendanceRecord> existing = attendanceRecordRepository.findByEmployeeAndDate(matchedEmployee, today);
        if (existing.isPresent()) {
            AttendanceRecord rec = existing.get();
            if (rec.getCheckOutTime() == null) {
                rec.setCheckOutTime(nowTime);
                if (rec.getCheckInTime() != null) {
                    long checkinSecs = rec.getCheckInTime().toSecondOfDay();
                    long checkoutSecs = nowTime.toSecondOfDay();
                    double hours = Math.max(0.0, (checkoutSecs - checkinSecs) / 3600.0);
                    rec.setWorkingHours(Math.round(hours * 100.0) / 100.0);
                } else {
                    rec.setCheckInTime(nowTime);
                    rec.setWorkingHours(0.0);
                }
                attendanceRecordRepository.save(rec);
                logRepository.save(new Log(formattedTime, "Check-out successful: " + matchedEmployee.getName() + " at " + formattedTime + " [Worked: " + rec.getWorkingHours() + " hrs]", "SUCCESS"));

                Map<String, Object> resp = new HashMap<>();
                resp.put("employee", matchedEmployee);
                resp.put("record", rec);
                resp.put("message", "Goodbye, " + matchedEmployee.getName() + ". Check-out recorded at " + formattedTime + ". Worked: " + rec.getWorkingHours() + " hours.");
                if (warning != null) {
                    resp.put("warning", warning);
                }
                return ResponseEntity.ok(resp);
            } else {
                String checkinTimeStr = rec.getCheckInTime() != null ? String.format("%02d:%02d:%02d", rec.getCheckInTime().getHour(), rec.getCheckInTime().getMinute(), rec.getCheckInTime().getSecond()) : formattedTime;
                Map<String, Object> resp = new HashMap<>();
                resp.put("employee", matchedEmployee);
                resp.put("record", rec);
                resp.put("message", "Welcome back, " + matchedEmployee.getName() + ". You already completed check-in at " + checkinTimeStr + " and check-out today.");
                if (warning != null) {
                    resp.put("warning", warning);
                }
                return ResponseEntity.ok(resp);
            }
        }

        // Check if Late (threshold: 09:15 AM)
        String status = nowTime.isAfter(LocalTime.of(9, 15)) ? "LATE" : "PRESENT";
        
        AttendanceRecord newRecord = new AttendanceRecord(matchedEmployee, today, nowTime, null, 0.0, status, true);
        attendanceRecordRepository.save(newRecord);

        logRepository.save(new Log(formattedTime, "Check-in successful: " + matchedEmployee.getName() + " at " + formattedTime + " [" + status + "]", "SUCCESS"));
        
        Map<String, Object> resp = new HashMap<>();
        resp.put("employee", matchedEmployee);
        resp.put("record", newRecord);
        resp.put("message", "Welcome, " + matchedEmployee.getName() + ". Check-in recorded at " + formattedTime + " [" + status + "].");
        if (warning != null) {
            resp.put("warning", warning);
        }
        return ResponseEntity.ok(resp);
    }

    // ==========================================
    // CHECK-OUT BIOMETRIC SCAN
    // ==========================================
    @PostMapping("/check-out")
    public ResponseEntity<?> checkOut(@RequestBody Map<String, String> payload, jakarta.servlet.http.HttpServletRequest request) {
        String scannedEmbStr = payload.get("embedding");
        String capturedImage = payload.get("capturedImage");
        String warning = null;

        if (capturedImage == null || capturedImage.trim().isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("message", "No image captured. Face detection failed."));
        }

        BiometricPythonService.DetectionResult detectionResult = biometricPythonService.detectFaces(capturedImage);
        if (detectionResult.error != null) {
            return ResponseEntity.status(500).body(Map.of("message", "Biometric engine error: " + detectionResult.error));
        }
        if (detectionResult.count == 0) {
            return ResponseEntity.badRequest().body(Map.of("message", "No face detected. Access Denied."));
        } else if (detectionResult.count > 1) {
            warning = "Multiple faces detected. Please scan one person at a time.";
        }
        
        // Liveness check: evaluated server-side from Python script score
        if (detectionResult.livenessScore < livenessThreshold) {
            systemNotificationRepository.save(new SystemNotification("Check-out blocked: Liveness verification failed. Score: " + detectionResult.livenessScore, "WARN"));
            logRepository.save(new Log(LocalTime.now().toString(), "Spoof check-out blocked: liveness score " + detectionResult.livenessScore, "ALERT"));
            createSecurityLog(payload.get("capturedImage"), "SPOOF_ATTACK_DETECTED", payload, request);
            return ResponseEntity.status(403).body(Map.of("message", "LIVENESS FAILURE: Photo or spoofing suspected."));
        }

        Employee matchedEmployee = matchFace(scannedEmbStr, capturedImage);
        if (matchedEmployee == null) {
            systemNotificationRepository.save(new SystemNotification("Unauthorized face scan attempt at check-out portal.", "UNKNOWN_FACE"));
            logRepository.save(new Log(LocalTime.now().toString(), "Unrecognized face scan at check-out portal", "ALERT"));
            createSecurityLog(payload.get("capturedImage"), "UNAUTHORIZED_PERSON_DETECTED", payload, request);
            return ResponseEntity.status(404).body(Map.of("message", "Unauthorized Person Detected. Access Denied."));
        }

        LocalDate today = LocalDate.now();
        LocalTime nowTime = LocalTime.now();
        String formattedTime = String.format("%02d:%02d:%02d", nowTime.getHour(), nowTime.getMinute(), nowTime.getSecond());
        
        Optional<AttendanceRecord> existing = attendanceRecordRepository.findByEmployeeAndDate(matchedEmployee, today);
        if (!existing.isPresent()) {
            AttendanceRecord record = new AttendanceRecord(matchedEmployee, today, nowTime, nowTime, 0.0, "PRESENT", true);
            attendanceRecordRepository.save(record);
            
            logRepository.save(new Log(formattedTime, "Check-out (Auto Check-in) for: " + matchedEmployee.getName() + " at " + formattedTime, "SUCCESS"));
            Map<String, Object> resp = new HashMap<>();
            resp.put("employee", matchedEmployee);
            resp.put("record", record);
            resp.put("message", "Goodbye, " + matchedEmployee.getName() + ". Check-out recorded at " + formattedTime + ".");
            if (warning != null) {
                resp.put("warning", warning);
            }
            return ResponseEntity.ok(resp);
        }

        AttendanceRecord record = existing.get();
        record.setCheckOutTime(nowTime);
        
        // Calculate exact working hours
        if (record.getCheckInTime() != null) {
            long checkinSecs = record.getCheckInTime().toSecondOfDay();
            long checkoutSecs = nowTime.toSecondOfDay();
            double hours = Math.max(0.0, (checkoutSecs - checkinSecs) / 3600.0);
            record.setWorkingHours(Math.round(hours * 100.0) / 100.0);
        } else {
            record.setCheckInTime(nowTime);
            record.setWorkingHours(0.0);
        }
        
        attendanceRecordRepository.save(record);

        logRepository.save(new Log(formattedTime, "Check-out successful: " + matchedEmployee.getName() + " at " + formattedTime + " [Worked: " + record.getWorkingHours() + " hrs]", "SUCCESS"));
        
        Map<String, Object> resp = new HashMap<>();
        resp.put("employee", matchedEmployee);
        resp.put("record", record);
        resp.put("message", "Goodbye, " + matchedEmployee.getName() + ". Check-out recorded at " + formattedTime + ". Worked: " + record.getWorkingHours() + " hours.");
        if (warning != null) {
            resp.put("warning", warning);
        }
        return ResponseEntity.ok(resp);
    }

    // ==========================================
    // INDIVIDUAL HISTORY
    // ==========================================
    @GetMapping("/employee/{employeeId}")
    public ResponseEntity<?> getEmployeeHistory(@PathVariable String employeeId) {
        Optional<Employee> empOpt = employeeRepository.findByEmployeeId(employeeId);
        if (!empOpt.isPresent()) {
            return ResponseEntity.status(404).body(Map.of("message", "Employee not found."));
        }
        
        Employee employee = empOpt.get();
        
        // 1. Enforce that only registered employees with active biometric faces can access data
        List<EmployeeFaceImage> faces = employeeFaceImageRepository.findByEmployee(employee);
        if (faces.isEmpty()) {
            return ResponseEntity.status(403).body(Map.of(
                "message", "ACCESS DENIED: Biometrics not registered. Please register face first."
            ));
        }

        // 2. Enforce that only employees with an active portal session can view logs
        List<EmployeeSession> sessions = employeeSessionRepository.findAllByOrderByTimestampDesc();
        Optional<EmployeeSession> latestSession = sessions.stream()
            .filter(s -> s.getEmployeeId() != null && s.getEmployeeId().equalsIgnoreCase(employee.getEmployeeId()))
            .findFirst();
            
        if (!latestSession.isPresent() || !"LOGIN".equalsIgnoreCase(latestSession.get().getAction())) {
            return ResponseEntity.status(401).body(Map.of(
                "message", "UNAUTHORIZED: Active biometric portal session required. Please verify biometrics."
            ));
        }

        List<AttendanceRecord> records = attendanceRecordRepository.findByEmployee(employee);
        return ResponseEntity.ok(records);
    }

    // ==========================================
    // PORTAL VERIFY BIOMETRICS SCAN
    // ==========================================
    @PostMapping("/verify-biometrics")
    public ResponseEntity<?> verifyBiometrics(@RequestBody Map<String, String> payload, jakarta.servlet.http.HttpServletRequest request) {
        String scannedEmbStr = payload.get("embedding");
        String expectedId = payload.get("expectedEmployeeId");
        String capturedImage = payload.get("capturedImage");
        String warning = null;

        if (capturedImage == null || capturedImage.trim().isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("message", "No image captured. Face detection failed."));
        }

        BiometricPythonService.DetectionResult detectionResult = biometricPythonService.detectFaces(capturedImage);
        if (detectionResult.error != null) {
            return ResponseEntity.status(500).body(Map.of("message", "Biometric engine error: " + detectionResult.error));
        }
        if (detectionResult.count == 0) {
            return ResponseEntity.badRequest().body(Map.of("message", "No face detected. Access Denied."));
        } else if (detectionResult.count > 1) {
            warning = "Multiple faces detected. Please scan one person at a time.";
        }
        
        // Liveness check: evaluated server-side from Python script score
        if (detectionResult.livenessScore < livenessThreshold) {
            systemNotificationRepository.save(new SystemNotification("Portal login blocked: Liveness verification failed. Score: " + detectionResult.livenessScore, "WARN"));
            logRepository.save(new Log(LocalTime.now().toString(), "Spoof portal login blocked: liveness score " + detectionResult.livenessScore, "ALERT"));
            createSecurityLog(payload.get("capturedImage"), "SPOOF_ATTACK_DETECTED", payload, request);
            return ResponseEntity.status(403).body(Map.of("message", "LIVENESS FAILURE: Photo or spoofing suspected."));
        }

        Employee matchedEmployee = matchFace(scannedEmbStr, capturedImage);
        if (matchedEmployee == null) {
            systemNotificationRepository.save(new SystemNotification("Unauthorized portal login attempt.", "UNKNOWN_FACE"));
            logRepository.save(new Log(LocalTime.now().toString(), "Unrecognized face scan at portal login", "ALERT"));
            createSecurityLog(payload.get("capturedImage"), "UNAUTHORIZED_PERSON_DETECTED", payload, request);
            return ResponseEntity.status(404).body(Map.of("message", "Unauthorized Person Detected. Access Denied."));
        }

        if (!"ACTIVE".equalsIgnoreCase(matchedEmployee.getStatus())) {
            logRepository.save(new Log(LocalTime.now().toString(), "Portal login denied for inactive employee: " + matchedEmployee.getName(), "ALERT"));
            createSecurityLog(payload.get("capturedImage"), "INACTIVE_ACCOUNT_BLOCKED", payload, request);
            return ResponseEntity.status(403).body(Map.of("message", "ACCESS DENIED: Account status is INACTIVE."));
        }

        // Backend enforcement: Verify scanned employee ID matches the portal login target ID
        if (expectedId != null && !expectedId.trim().isEmpty() && !expectedId.equalsIgnoreCase(matchedEmployee.getEmployeeId())) {
            systemNotificationRepository.save(new SystemNotification("Portal login blocked: Identity mismatch.", "WARN"));
            logRepository.save(new Log(LocalTime.now().toString(), "Portal login ID mismatch: " + expectedId + " matched to " + matchedEmployee.getName(), "ALERT"));
            createSecurityLog(payload.get("capturedImage"), "IDENTITY_MISMATCH", payload, request);
            return ResponseEntity.status(403).body(Map.of("message", "IDENTITY MISMATCH: Scanned face does not match input Employee ID."));
        }

        logRepository.save(new Log(LocalTime.now().toString(), "Portal login verified for: " + matchedEmployee.getName(), "SUCCESS"));
        Map<String, Object> resp = new HashMap<>();
        resp.put("employee", matchedEmployee);
        resp.put("message", "Biometrics verified. Welcome, " + matchedEmployee.getName() + ".");
        if (warning != null) {
            resp.put("warning", warning);
        }
        return ResponseEntity.ok(resp);
    }

    // ==========================================
    // BIOMETRIC HELPER MATCHING ENGINE
    // ==========================================
    private boolean isAllZeros(double[] vector) {
        if (vector == null || vector.length == 0) return true;
        for (double val : vector) {
            if (val != 0.0) {
                return false;
            }
        }
        return true;
    }

    private Employee matchFace(String scannedEmbStr, String capturedImage) {
        // 1. Primary Match: Real Camera Facial Recognition
        if (capturedImage != null && !capturedImage.trim().isEmpty()) {
            BiometricPythonService.RecognitionResult recResult = biometricPythonService.recognizeFace(capturedImage);
            if (recResult.error != null) {
                System.err.println("Biometric recognition engine notice/error: " + recResult.error);
            } else if (recResult.faceDetected && recResult.embedding != null && !recResult.embedding.isEmpty()) {
                double[] liveVector = convertToDoubleArray(recResult.embedding);
                List<EmployeeFaceImage> allFaces = employeeFaceImageRepository.findAll();
                EmployeeFaceImage bestMatch = null;
                double maxSimilarity = -1.0;
                double minDistance = Double.MAX_VALUE;

                for (EmployeeFaceImage face : allFaces) {
                    String faceEmb = face.getEmbedding();
                    double[] storedVector = null;

                    if (faceEmb != null && !faceEmb.trim().isEmpty()) {
                        storedVector = parseEmbedding(faceEmb);
                    }

                    // If stored vector is empty/zero or legacy, dynamically compute embedding from face image
                    if ((storedVector == null || isAllZeros(storedVector)) && face.getFaceImage() != null && face.getFaceImage().startsWith("data:image")) {
                        try {
                            BiometricPythonService.RecognitionResult storedRec = biometricPythonService.recognizeFace(face.getFaceImage());
                            if (storedRec.faceDetected && storedRec.embedding != null && !storedRec.embedding.isEmpty()) {
                                storedVector = convertToDoubleArray(storedRec.embedding);
                                face.setEmbedding(Arrays.toString(storedVector));
                                employeeFaceImageRepository.save(face);
                            }
                        } catch (Exception e) {
                            System.err.println("Notice: Dynamic face embedding extraction for stored image skipped: " + e.getMessage());
                        }
                    }

                    if (storedVector != null && !isAllZeros(storedVector)) {
                        double sim = calculateCosineSimilarity(liveVector, storedVector);
                        double dist = calculateDistance(liveVector, storedVector);

                        if (sim > maxSimilarity) {
                            maxSimilarity = sim;
                            minDistance = dist;
                            bestMatch = face;
                        }
                    }
                }

                // Match condition: Cosine Similarity > 0.40 or Euclidean Distance < 1.15
                if (bestMatch != null && (maxSimilarity >= 0.40 || minDistance < 1.15)) {
                    System.out.println("Facial recognition match succeeded: " + bestMatch.getEmployee().getName() + " (" + bestMatch.getEmployee().getEmployeeId() + ") - Similarity: " + maxSimilarity + ", Distance: " + minDistance);
                    return bestMatch.getEmployee();
                } else {
                    System.out.println("Facial recognition match rejected: max similarity " + maxSimilarity + " < 0.40 and min distance " + minDistance + " >= 1.15. Access denied.");
                    return null;
                }
            }
        }

        // 2. Secondary Match: 128-D vector embedding comparison (for developer simulator)
        if (scannedEmbStr != null && !scannedEmbStr.trim().isEmpty()) {
            Employee vectorMatch = legacyMatchFace(scannedEmbStr);
            if (vectorMatch != null) {
                System.out.println("Face matched via embedding vector to employee: " + vectorMatch.getName() + " (" + vectorMatch.getEmployeeId() + ")");
                return vectorMatch;
            }
        }

        return null;
    }

    private double[] convertToDoubleArray(List<Double> list) {
        if (list == null) return new double[0];
        double[] arr = new double[list.size()];
        for (int i = 0; i < list.size(); i++) {
            arr[i] = list.get(i) != null ? list.get(i) : 0.0;
        }
        return arr;
    }

    private Employee legacyMatchFace(String scannedEmbStr) {
        if (scannedEmbStr == null || scannedEmbStr.trim().isEmpty()) return null;
        double[] scannedVector = parseEmbedding(scannedEmbStr);
        if (isAllZeros(scannedVector)) {
            return null;
        }
        
        List<EmployeeFaceImage> allFaces = employeeFaceImageRepository.findAll();
        EmployeeFaceImage bestMatch = null;
        double minDistance = Double.MAX_VALUE;
        double threshold = 0.85;

        for (EmployeeFaceImage face : allFaces) {
            String faceEmb = face.getEmbedding();
            if (faceEmb == null || faceEmb.trim().isEmpty()) {
                continue;
            }
            double[] storedVector = parseEmbedding(faceEmb);
            if (isAllZeros(storedVector)) {
                continue;
            }
            double dist = calculateDistance(scannedVector, storedVector);
            if (dist < minDistance) {
                minDistance = dist;
                bestMatch = face;
            }
        }

        if (bestMatch != null && minDistance < threshold) {
            return bestMatch.getEmployee();
        }
        return null;
    }

    private double[] parseEmbedding(String embStr) {
        if (embStr == null || embStr.isEmpty()) return new double[128];
        try {
            embStr = embStr.replace("[", "").replace("]", "").trim();
            String[] parts = embStr.split(",");
            double[] res = new double[parts.length];
            for (int i = 0; i < parts.length; i++) {
                res[i] = Double.parseDouble(parts[i].trim());
            }
            return res;
        } catch (Exception e) {
            return new double[128];
        }
    }

    private double calculateDistance(double[] v1, double[] v2) {
        double sum = 0;
        int len = Math.min(v1.length, v2.length);
        if (len == 0) return Double.MAX_VALUE;
        for (int i = 0; i < len; i++) {
            double diff = v1[i] - v2[i];
            sum += diff * diff;
        }
        return Math.sqrt(sum);
    }

    private double calculateCosineSimilarity(double[] v1, double[] v2) {
        if (v1 == null || v2 == null || v1.length == 0 || v2.length == 0) return 0.0;
        int len = Math.min(v1.length, v2.length);
        double dot = 0.0;
        double norm1 = 0.0;
        double norm2 = 0.0;
        for (int i = 0; i < len; i++) {
            dot += v1[i] * v2[i];
            norm1 += v1[i] * v1[i];
            norm2 += v2[i] * v2[i];
        }
        if (norm1 <= 1e-6 || norm2 <= 1e-6) return 0.0;
        return dot / (Math.sqrt(norm1) * Math.sqrt(norm2));
    }
}
