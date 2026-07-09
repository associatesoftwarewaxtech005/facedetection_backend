package com.loginsystem.controller;

import com.loginsystem.model.EmployeeSession;
import com.loginsystem.model.Employee;
import com.loginsystem.repository.EmployeeSessionRepository;
import com.loginsystem.repository.EmployeeRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

@RestController
@RequestMapping("/api/employee")
public class EmployeeController {

    @Autowired
    private EmployeeSessionRepository employeeSessionRepository;

    @Autowired
    private EmployeeRepository employeeRepository;

    @Autowired
    private BCryptPasswordEncoder passwordEncoder;

    @GetMapping("/verify")
    public ResponseEntity<?> verifyEmployee(@RequestParam String employeeId) {
        Optional<Employee> empOpt = employeeRepository.findByEmployeeId(employeeId);
        Map<String, String> response = new HashMap<>();
        
        if (empOpt.isPresent()) {
            Employee employee = empOpt.get();
            if ("ACTIVE".equalsIgnoreCase(employee.getStatus())) {
                return ResponseEntity.ok(employee);
            } else {
                response.put("status", "error");
                response.put("message", "CRITICAL: EMPLOYEE ACCOUNT INACTIVE");
                return ResponseEntity.status(403).body(response);
            }
        }
        
        response.put("status", "error");
        response.put("message", "CRITICAL: IDENTITY NOT RECOGNIZED");
        return ResponseEntity.status(404).body(response);
    }

    @PostMapping("/login")
    public EmployeeSession loginEmployee(@RequestBody EmployeeSession session) {
        session.setAction("LOGIN");
        return employeeSessionRepository.save(session);
    }

    @PostMapping("/logout")
    public EmployeeSession logoutEmployee(@RequestBody EmployeeSession session) {
        session.setAction("LOGOUT");
        return employeeSessionRepository.save(session);
    }

    @PostMapping("/login-with-pin")
    public ResponseEntity<?> loginWithPin(@RequestBody Map<String, String> credentials) {
        String employeeId = credentials.get("employeeId");
        String passcode = credentials.get("passcode");
        
        Map<String, String> response = new HashMap<>();
        if (employeeId == null || passcode == null) {
            response.put("status", "error");
            response.put("message", "Employee ID and Passcode are required.");
            return ResponseEntity.badRequest().body(response);
        }
        
        Optional<Employee> empOpt = employeeRepository.findByEmployeeId(employeeId);
        if (empOpt.isPresent()) {
            Employee employee = empOpt.get();
            if (!"ACTIVE".equalsIgnoreCase(employee.getStatus())) {
                response.put("status", "error");
                response.put("message", "CRITICAL: EMPLOYEE ACCOUNT INACTIVE");
                return ResponseEntity.status(403).body(response);
            }
            if (passcode.equals(employee.getPasscode()) || passwordEncoder.matches(passcode, employee.getPasscode())) {
                return ResponseEntity.ok(employee);
            } else {
                response.put("status", "error");
                response.put("message", "INVALID PASSCODE. ACCESS DENIED.");
                return ResponseEntity.status(401).body(response);
            }
        }
        
        response.put("status", "error");
        response.put("message", "CRITICAL: IDENTITY NOT RECOGNIZED");
        return ResponseEntity.status(404).body(response);
    }
}
