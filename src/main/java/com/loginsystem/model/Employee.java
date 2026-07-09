package com.loginsystem.model;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "employees")
public class Employee {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(unique = true, nullable = false)
    private String employeeId;

    private String name;
    private String department;
    private String position;
    private String email;
    private String phoneNumber;
    private String status; // ACTIVE or INACTIVE
    private String role;   // ADMIN, EMPLOYEE, MANAGER
    private String passcode = "1234";

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    public Employee() {
        this.createdAt = LocalDateTime.now();
        this.passcode = "1234";
    }

    public Employee(String employeeId, String name, String department, String position, String email, String phoneNumber, String status, String role) {
        this.employeeId = employeeId;
        this.name = name;
        this.department = department;
        this.position = position;
        this.email = email;
        this.phoneNumber = phoneNumber;
        this.status = status;
        this.role = role;
        this.passcode = "1234";
        this.createdAt = LocalDateTime.now();
    }

    public Employee(String employeeId, String name, String department, String position, String email, String phoneNumber, String status, String role, String passcode) {
        this.employeeId = employeeId;
        this.name = name;
        this.department = department;
        this.position = position;
        this.email = email;
        this.phoneNumber = phoneNumber;
        this.status = status;
        this.role = role;
        this.passcode = passcode != null && !passcode.trim().isEmpty() ? passcode : "1234";
        this.createdAt = LocalDateTime.now();
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getEmployeeId() {
        return employeeId;
    }

    public void setEmployeeId(String employeeId) {
        this.employeeId = employeeId;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getDepartment() {
        return department;
    }

    public void setDepartment(String department) {
        this.department = department;
    }

    public String getPosition() {
        return position;
    }

    public void setPosition(String position) {
        this.position = position;
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public String getPhoneNumber() {
        return phoneNumber;
    }

    public void setPhoneNumber(String phoneNumber) {
        this.phoneNumber = phoneNumber;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getRole() {
        return role;
    }

    public void setRole(String role) {
        this.role = role;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public String getPasscode() {
        return passcode;
    }

    public void setPasscode(String passcode) {
        this.passcode = passcode;
    }
}
