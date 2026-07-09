package com.loginsystem.controller;

import com.loginsystem.model.*;
import com.loginsystem.repository.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import com.loginsystem.service.BiometricPythonService;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.*;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/admin")
public class AdminController {

    @Autowired
    private AdminUserRepository adminUserRepository;

    @Autowired
    private EmployeeSessionRepository employeeSessionRepository;

    @Autowired
    private EmployeeRepository employeeRepository;

    @Autowired
    private EmployeeFaceImageRepository employeeFaceImageRepository;

    @Autowired
    private AttendanceRecordRepository attendanceRecordRepository;

    @Autowired
    private LogRepository logRepository;

    @Autowired
    private SecurityLogRepository securityLogRepository;

    @Autowired
    private BiometricPythonService biometricPythonService;

    @Autowired
    private BCryptPasswordEncoder passwordEncoder;

    @PostMapping("/login")
    public ResponseEntity<Map<String, String>> login(@RequestBody Map<String, String> credentials) {
        String username = credentials.get("username");
        String password = credentials.get("password");

        Map<String, String> response = new HashMap<>();
        Optional<AdminUser> adminOpt = adminUserRepository.findByUsername(username);
        if (adminOpt.isPresent()) {
            String stored = adminOpt.get().getPassword();
            // Support both plain-text (legacy) and BCrypt-hashed passwords
            boolean valid = stored.equals(password)
                         || passwordEncoder.matches(password, stored);
            if (valid) {
                response.put("status", "success");
                response.put("message", "Login successful");
                return ResponseEntity.ok(response);
            }
        }
        response.put("status", "error");
        response.put("message", "CRITICAL: ACCESS KEY INVALID");
        return ResponseEntity.status(401).body(response);
    }


    @PostMapping("/logout")
    public ResponseEntity<Map<String, String>> logout() {
        Map<String, String> response = new HashMap<>();
        response.put("status", "success");
        return ResponseEntity.ok(response);
    }

    // ==========================================
    // EMPLOYEE CRUD
    // ==========================================
    @GetMapping("/employees")
    public List<Map<String, Object>> getEmployees() {
        List<Employee> employees = employeeRepository.findAll();
        List<Map<String, Object>> result = new ArrayList<>();
        
        for (Employee emp : employees) {
            Map<String, Object> empMap = new HashMap<>();
            empMap.put("id", emp.getId());
            empMap.put("employeeId", emp.getEmployeeId());
            empMap.put("name", emp.getName());
            empMap.put("department", emp.getDepartment());
            empMap.put("position", emp.getPosition());
            empMap.put("email", emp.getEmail());
            empMap.put("phoneNumber", emp.getPhoneNumber());
            empMap.put("status", emp.getStatus());
            empMap.put("role", emp.getRole());
            
            // Get count of registered face images
            List<EmployeeFaceImage> faces = employeeFaceImageRepository.findByEmployee(emp);
            empMap.put("faceCount", faces.size());
            
            // Return first face image metadata or a simplified list
            List<Map<String, Object>> facesList = faces.stream().map(f -> {
                Map<String, Object> fMap = new HashMap<>();
                fMap.put("id", f.getId());
                // Avoid returning the huge base64 in the general list for performance,
                // but we can return first 50 chars or return it on demand.
                fMap.put("hasImage", f.getFaceImage() != null && !f.getFaceImage().isEmpty());
                fMap.put("embedding", f.getEmbedding());
                return fMap;
            }).collect(Collectors.toList());
            empMap.put("faces", facesList);
            
            result.add(empMap);
        }
        return result;
    }

    @PostMapping("/employees")
    public ResponseEntity<?> createEmployee(@RequestBody Employee employee) {
        if (employeeRepository.findByEmployeeId(employee.getEmployeeId()).isPresent()) {
            return ResponseEntity.badRequest().body(Map.of("message", "Employee ID already exists."));
        }
        Employee saved = employeeRepository.save(employee);
        
        // Log action
        logRepository.save(new Log(LocalTime.now().toString(), "Created employee profile: " + employee.getName() + " (" + employee.getEmployeeId() + ")", "SUCCESS"));
        
        return ResponseEntity.ok(saved);
    }

    @PutMapping("/employees/{id}")
    public ResponseEntity<Employee> updateEmployee(@PathVariable Long id, @RequestBody Employee employeeDetails) {
        return employeeRepository.findById(id).map(employee -> {
            employee.setName(employeeDetails.getName());
            employee.setDepartment(employeeDetails.getDepartment());
            employee.setPosition(employeeDetails.getPosition());
            employee.setEmail(employeeDetails.getEmail());
            employee.setPhoneNumber(employeeDetails.getPhoneNumber());
            employee.setStatus(employeeDetails.getStatus());
            employee.setRole(employeeDetails.getRole());
            if (employeeDetails.getPasscode() != null && !employeeDetails.getPasscode().trim().isEmpty()) {
                employee.setPasscode(employeeDetails.getPasscode());
            }
            Employee updated = employeeRepository.save(employee);
            
            logRepository.save(new Log(LocalTime.now().toString(), "Updated employee profile: " + employee.getName(), "SUCCESS"));
            return ResponseEntity.ok(updated);
        }).orElse(ResponseEntity.notFound().build());
    }

    @DeleteMapping("/employees/{id}")
    @Transactional
    public ResponseEntity<Map<String, Boolean>> deleteEmployee(@PathVariable Long id) {
        return employeeRepository.findById(id).map(employee -> {
            // Cascade delete associations manually
            employeeFaceImageRepository.deleteByEmployee(employee);
            attendanceRecordRepository.deleteByEmployee(employee);
            employeeRepository.delete(employee);
            
            logRepository.save(new Log(LocalTime.now().toString(), "Deleted employee: " + employee.getName(), "ALERT"));
            
            Map<String, Boolean> response = new HashMap<>();
            response.put("deleted", Boolean.TRUE);
            return ResponseEntity.ok(response);
        }).orElse(ResponseEntity.notFound().build());
    }

    // ==========================================
    // FACE REGISTRATION
    // ==========================================
    @PostMapping("/employees/{id}/faces")
    public ResponseEntity<?> registerFace(@PathVariable Long id, @RequestBody Map<String, String> faceData) {
        return employeeRepository.findById(id).map(employee -> {
            String faceImage = faceData.get("faceImage");
            String embedding = faceData.get("embedding");
            
            if (faceImage != null && !faceImage.trim().isEmpty()) {
                BiometricPythonService.DetectionResult detectionResult = biometricPythonService.detectFaces(faceImage);
                if (detectionResult.error != null) {
                    return ResponseEntity.status(500).body(Map.of("message", "Biometric engine error: " + detectionResult.error));
                }
                if (detectionResult.count == 0) {
                    return ResponseEntity.badRequest().body(Map.of("message", "No face detected in registration image."));
                } else if (detectionResult.count > 1) {
                    return ResponseEntity.badRequest().body(Map.of("message", "Multiple faces detected. Registration requires exactly one face."));
                }
            } else {
                return ResponseEntity.badRequest().body(Map.of("message", "Required parameter 'faceImage' (Base64) is missing."));
            }
            
            if (embedding != null && !embedding.trim().isEmpty()) {
                double[] newVector = parseEmbedding(embedding);
                if (isAllZeros(newVector)) {
                    return ResponseEntity.badRequest().body(Map.of("message", "Invalid face embedding. Cannot be all zeros."));
                }
                
                List<EmployeeFaceImage> allFaces = employeeFaceImageRepository.findAll();
                double threshold = 0.48;
                for (EmployeeFaceImage face : allFaces) {
                    if (!face.getEmployee().getId().equals(employee.getId())) {
                        String faceEmb = face.getEmbedding();
                        if (faceEmb != null && !faceEmb.trim().isEmpty()) {
                            double[] storedVector = parseEmbedding(faceEmb);
                            if (!isAllZeros(storedVector)) {
                                double dist = calculateDistance(newVector, storedVector);
                                if (dist < threshold) {
                                    return ResponseEntity.badRequest().body(Map.of("message", "CRITICAL: Face biometric is already registered to employee " + face.getEmployee().getName() + " (" + face.getEmployee().getEmployeeId() + ")."));
                                }
                            }
                        }
                    }
                }
            }
            
            EmployeeFaceImage newFace = new EmployeeFaceImage(employee, faceImage, embedding);
            employeeFaceImageRepository.save(newFace);
            
            triggerModelTrainingAsync();
            
            logRepository.save(new Log(LocalTime.now().toString(), "Registered biometric face image for " + employee.getName(), "SECURE"));
            return ResponseEntity.ok(Map.of("message", "Face image successfully registered.", "id", newFace.getId()));
        }).orElse(ResponseEntity.notFound().build());
    }

    private void triggerModelTrainingAsync() {
        new Thread(() -> {
            try {
                List<EmployeeFaceImage> allFaces = employeeFaceImageRepository.findAll();
                List<BiometricPythonService.TrainSample> samples = new java.util.ArrayList<>();
                for (EmployeeFaceImage face : allFaces) {
                    if (face.getFaceImage() != null && !face.getFaceImage().trim().isEmpty()) {
                        samples.add(new BiometricPythonService.TrainSample(
                            String.valueOf(face.getEmployee().getId()), 
                            face.getFaceImage()
                        ));
                    }
                }
                if (!samples.isEmpty()) {
                    boolean success = biometricPythonService.trainModel(samples);
                    System.out.println("Async biometric model training " + (success ? "succeeded" : "failed"));
                }
            } catch (Exception e) {
                System.err.println("Exception in async biometric model training: " + e.getMessage());
            }
        }).start();
    }

    // ==========================================
    // ATTENDANCE LEDGER
    // ==========================================
    @GetMapping("/attendance")
    public List<AttendanceRecord> getAttendanceLedger() {
        return attendanceRecordRepository.findAllByOrderByDateDescCheckInTimeDesc();
    }

    @PutMapping("/attendance/{id}")
    public ResponseEntity<?> updateAttendanceRecord(@PathVariable Long id, @RequestBody Map<String, Object> updates) {
        return attendanceRecordRepository.findById(id).map(record -> {
            if (updates.containsKey("checkInTime") && updates.get("checkInTime") != null) {
                record.setCheckInTime(LocalTime.parse((String) updates.get("checkInTime")));
            }
            if (updates.containsKey("checkOutTime")) {
                Object checkoutObj = updates.get("checkOutTime");
                if (checkoutObj != null && !checkoutObj.toString().isEmpty()) {
                    record.setCheckOutTime(LocalTime.parse(checkoutObj.toString()));
                } else {
                    record.setCheckOutTime(null);
                }
            }
            if (updates.containsKey("status") && updates.get("status") != null) {
                record.setStatus((String) updates.get("status"));
            }
            if (updates.containsKey("livenessVerified") && updates.get("livenessVerified") != null) {
                record.setLivenessVerified((Boolean) updates.get("livenessVerified"));
            }
            
            // Recalculate working hours if checkout time exists
            if (record.getCheckInTime() != null && record.getCheckOutTime() != null) {
                long checkinSecs = record.getCheckInTime().toSecondOfDay();
                long checkoutSecs = record.getCheckOutTime().toSecondOfDay();
                double hours = (checkoutSecs - checkinSecs) / 3600.0;
                record.setWorkingHours(Math.round(hours * 100.0) / 100.0);
            } else {
                record.setWorkingHours(0.0);
            }
            
            AttendanceRecord updated = attendanceRecordRepository.save(record);
            logRepository.save(new Log(LocalTime.now().toString(), "Manually updated attendance ledger record for " + record.getEmployee().getName(), "ALERT"));
            return ResponseEntity.ok(updated);
        }).orElse(ResponseEntity.notFound().build());
    }

    // ==========================================
    // ANALYTICS DATA
    // ==========================================
    @GetMapping("/analytics")
    public ResponseEntity<Map<String, Object>> getAnalytics() {
        Map<String, Object> stats = new HashMap<>();
        
        long totalEmployees = employeeRepository.count();
        long activeEmployees = employeeRepository.findAll().stream().filter(e -> "ACTIVE".equalsIgnoreCase(e.getStatus())).count();
        
        LocalDate today = LocalDate.now();
        List<AttendanceRecord> todayRecords = attendanceRecordRepository.findByDateBetween(today, today);
        
        long presentToday = todayRecords.size();
        long lateToday = todayRecords.stream().filter(r -> "LATE".equalsIgnoreCase(r.getStatus())).count();
        long absentToday = Math.max(0, activeEmployees - presentToday);
        
        double avgHours = todayRecords.stream()
                .filter(r -> r.getCheckOutTime() != null)
                .mapToDouble(AttendanceRecord::getWorkingHours)
                .average()
                .orElse(8.0);
                
        stats.put("totalEmployees", totalEmployees);
        stats.put("activeEmployees", activeEmployees);
        stats.put("presentToday", presentToday);
        stats.put("lateToday", lateToday);
        stats.put("absentToday", absentToday);
        stats.put("avgWorkingHours", Math.round(avgHours * 10.0) / 10.0);
        
        // Build Weekly Trend (last 7 days)
        List<Map<String, Object>> weeklyTrend = new ArrayList<>();
        for (int i = 6; i >= 0; i--) {
            LocalDate date = today.minusDays(i);
            List<AttendanceRecord> records = attendanceRecordRepository.findByDateBetween(date, date);
            long presentCount = records.size();
            long lateCount = records.stream().filter(r -> "LATE".equalsIgnoreCase(r.getStatus())).count();
            long absentCount = Math.max(0, activeEmployees - presentCount);
            
            Map<String, Object> day = new HashMap<>();
            day.put("date", date.getDayOfWeek().toString().substring(0, 3));
            day.put("present", presentCount);
            day.put("late", lateCount);
            day.put("absent", absentCount);
            weeklyTrend.add(day);
        }
        stats.put("weeklyTrend", weeklyTrend);
        
        // Department breakdown
        Map<String, Long> deptBreakdown = employeeRepository.findAll().stream()
                .collect(Collectors.groupingBy(Employee::getDepartment, Collectors.counting()));
        stats.put("departmentBreakdown", deptBreakdown);
        
        return ResponseEntity.ok(stats);
    }

    // ==========================================
    // SECURITY ACCESS LOGS
    // ==========================================
    @GetMapping("/security-logs")
    public List<SecurityLog> getSecurityLogs() {
        return securityLogRepository.findAllByOrderByTimestampDesc();
    }

    @GetMapping("/employee-sessions")
    public List<EmployeeSession> getEmployeeSessions() {
        return employeeSessionRepository.findAllByOrderByTimestampDesc();
    }

    private boolean isAllZeros(double[] vector) {
        if (vector == null || vector.length == 0) return true;
        for (double val : vector) {
            if (val != 0.0) {
                return false;
            }
        }
        return true;
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
}
