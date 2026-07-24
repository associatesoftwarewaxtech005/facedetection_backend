package com.loginsystem.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.persistence.*;
import java.time.LocalDate;
import java.time.LocalTime;

@Entity
@Table(name = "attendance_records")
@JsonIgnoreProperties({"hibernateLazyInitializer", "handler"})
public class AttendanceRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "employee_id", nullable = false)
    private Employee employee;

    private LocalDate date;
    private LocalTime checkInTime;
    private LocalTime checkOutTime;
    private Double workingHours;
    private String status; // PRESENT, ABSENT, LATE, HALF_DAY
    private Boolean livenessVerified;

    public AttendanceRecord() {
    }

    public AttendanceRecord(Employee employee, LocalDate date, LocalTime checkInTime, LocalTime checkOutTime, Double workingHours, String status, Boolean livenessVerified) {
        this.employee = employee;
        this.date = date;
        this.checkInTime = checkInTime;
        this.checkOutTime = checkOutTime;
        this.workingHours = workingHours;
        this.status = status;
        this.livenessVerified = livenessVerified;
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Employee getEmployee() {
        return employee;
    }

    public void setEmployee(Employee employee) {
        this.employee = employee;
    }

    public LocalDate getDate() {
        return date;
    }

    public void setDate(LocalDate date) {
        this.date = date;
    }

    public LocalTime getCheckInTime() {
        return checkInTime;
    }

    public void setCheckInTime(LocalTime checkInTime) {
        this.checkInTime = checkInTime;
    }

    public LocalTime getCheckOutTime() {
        return checkOutTime;
    }

    public void setCheckOutTime(LocalTime checkOutTime) {
        this.checkOutTime = checkOutTime;
    }

    public Double getWorkingHours() {
        return workingHours;
    }

    public void setWorkingHours(Double workingHours) {
        this.workingHours = workingHours;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public Boolean getLivenessVerified() {
        return livenessVerified;
    }

    public void setLivenessVerified(Boolean livenessVerified) {
        this.livenessVerified = livenessVerified;
    }
}
