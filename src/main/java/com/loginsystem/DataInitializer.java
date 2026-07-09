package com.loginsystem;

import com.loginsystem.model.*;
import com.loginsystem.repository.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;
import com.loginsystem.service.BiometricPythonService;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;

@Component
public class DataInitializer implements CommandLineRunner {

    @Autowired
    private LogRepository logRepository;

    @Autowired
    private AdminUserRepository adminUserRepository;

    @Autowired
    private EmployeeRepository employeeRepository;

    @Autowired
    private EmployeeFaceImageRepository employeeFaceImageRepository;

    @Autowired
    private AttendanceRecordRepository attendanceRecordRepository;

    @Autowired
    private SystemNotificationRepository systemNotificationRepository;

    @Autowired
    private BiometricPythonService biometricPythonService;

    @Override
    public void run(String... args) throws Exception {
        // Fix any existing employee face records that have empty or invalid embeddings in database
        List<EmployeeFaceImage> faces = employeeFaceImageRepository.findAll();
        for (EmployeeFaceImage face : faces) {
            if (face.getEmbedding() == null || face.getEmbedding().trim().isEmpty() || face.getEmbedding().replace("[","").replace("]","").trim().isEmpty()) {
                double seed = 0.5;
                if (face.getEmployee() != null) {
                    String empId = face.getEmployee().getEmployeeId();
                    if (empId != null) {
                        seed = Math.abs(empId.hashCode() % 100) / 120.0 + 0.1;
                    }
                }
                String newEmb = generateMockEmbedding(seed);
                face.setEmbedding(newEmb);
                employeeFaceImageRepository.save(face);
                logRepository.save(new Log(LocalTime.now().toString(), "Repaired empty face embedding for employee: " + (face.getEmployee() != null ? face.getEmployee().getName() : "Unknown"), "SECURE"));
            }
        }

        // 1. Seed Admin credentials
        if (adminUserRepository.count() == 0) {
            adminUserRepository.save(new AdminUser("admin", "admin"));
        }

        // 2. Seed Test Employees if empty
        if (employeeRepository.count() == 0) {
            Employee alice = new Employee("EMP001", "Alice Smith", "Engineering", "Lead Engineer", "alice@axon.io", "+1-555-0101", "ACTIVE", "ADMIN");
            Employee bob = new Employee("EMP002", "Bob Jones", "Human Resources", "HR Manager", "bob@axon.io", "+1-555-0102", "ACTIVE", "MANAGER");
            Employee charlie = new Employee("EMP003", "Charlie Davis", "Operations", "Operations Analyst", "charlie@axon.io", "+1-555-0103", "INACTIVE", "EMPLOYEE");
            Employee Diana = new Employee("EMP004", "Diana Prince", "Security", "Chief Security Officer", "diana@axon.io", "+1-555-0104", "ACTIVE", "EMPLOYEE");
            Employee abhi = new Employee("EMP005", "Abhi", "Engineering", "Security Analyst", "abhi@axon.io", "+1-555-0105", "ACTIVE", "EMPLOYEE");

            employeeRepository.save(alice);
            employeeRepository.save(bob);
            employeeRepository.save(charlie);
            employeeRepository.save(Diana);
            employeeRepository.save(abhi);

            // Seed mock face images & embeddings (128-dim mock JSON array)
            // Embedding is a mock float array starting with different signatures
            String aliceEmbedding = generateMockEmbedding(0.15);
            String bobEmbedding = generateMockEmbedding(0.45);
            String dianaEmbedding = generateMockEmbedding(0.75);
            String abhiEmbedding = generateMockEmbedding(0.95);

            employeeFaceImageRepository.save(new EmployeeFaceImage(alice, "MOCK_ALICE_BASE64_1", aliceEmbedding));
            employeeFaceImageRepository.save(new EmployeeFaceImage(alice, "MOCK_ALICE_BASE64_2", aliceEmbedding));
            employeeFaceImageRepository.save(new EmployeeFaceImage(bob, "MOCK_BOB_BASE64_1", bobEmbedding));
            employeeFaceImageRepository.save(new EmployeeFaceImage(Diana, "MOCK_DIANA_BASE64_1", dianaEmbedding));
            employeeFaceImageRepository.save(new EmployeeFaceImage(abhi, "MOCK_ABHI_BASE64_1", abhiEmbedding));

            // Seed historical attendance data
            LocalDate today = LocalDate.now();
            
            // Alice Smith Records
            attendanceRecordRepository.save(new AttendanceRecord(alice, today.minusDays(2), LocalTime.of(8, 55), LocalTime.of(17, 30), 8.58, "PRESENT", true));
            attendanceRecordRepository.save(new AttendanceRecord(alice, today.minusDays(1), LocalTime.of(9, 22), LocalTime.of(18, 0), 8.63, "LATE", true));
            attendanceRecordRepository.save(new AttendanceRecord(alice, today, LocalTime.of(9, 2), null, 0.0, "PRESENT", true));

            // Bob Jones Records
            attendanceRecordRepository.save(new AttendanceRecord(bob, today.minusDays(2), LocalTime.of(9, 5), LocalTime.of(17, 0), 7.92, "PRESENT", true));
            attendanceRecordRepository.save(new AttendanceRecord(bob, today.minusDays(1), LocalTime.of(9, 45), LocalTime.of(13, 15), 3.5, "HALF_DAY", true));
            attendanceRecordRepository.save(new AttendanceRecord(bob, today, LocalTime.of(9, 30), null, 0.0, "LATE", true));

            // Diana Records
            attendanceRecordRepository.save(new AttendanceRecord(Diana, today.minusDays(2), LocalTime.of(8, 45), LocalTime.of(17, 45), 9.0, "PRESENT", true));
            attendanceRecordRepository.save(new AttendanceRecord(Diana, today.minusDays(1), LocalTime.of(8, 50), LocalTime.of(18, 10), 9.33, "PRESENT", true));
            
            // Charlie is inactive/absent - no checkins
        }

        // 3. Seed System Logs
        if (logRepository.count() == 0) {
            logRepository.save(new Log("14:02:44", "Webcam model frame capture initialized", "SUCCESS"));
            logRepository.save(new Log("13:58:12", "Anti-spoofing challenge library active", "SECURE"));
            logRepository.save(new Log("13:42:01", "Database sync cycles completed", "SUCCESS"));
            logRepository.save(new Log("13:10:05", "Admin authenticated session starting", "SECURE"));
        }

        // 4. Seed system notifications
        if (systemNotificationRepository.count() == 0) {
            systemNotificationRepository.save(new SystemNotification("System database initialization complete.", "INFO"));
            systemNotificationRepository.save(new SystemNotification("Liveness detector verified 3 daily sign-ins.", "INFO"));
            SystemNotification warnSess = new SystemNotification("Biometric alert: Unrecognized face attempt on scanner #2.", "UNKNOWN_FACE");
            systemNotificationRepository.save(warnSess);
        }

        // Train biometric face model with seeded/stored faces
        try {
            List<EmployeeFaceImage> allFaces = employeeFaceImageRepository.findAll();
            List<BiometricPythonService.TrainSample> samples = new ArrayList<>();
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
                System.out.println("Startup biometric model training: " + (success ? "succeeded" : "failed"));
            }
        } catch (Exception e) {
            System.err.println("Error training biometric model on startup: " + e.getMessage());
        }
    }

    private String generateMockEmbedding(double seed) {
        List<Double> embedding = new ArrayList<>();
        for (int i = 0; i < 128; i++) {
            embedding.add(Math.sin(i * seed) * 0.5 + 0.5);
        }
        return embedding.toString();
    }
}
