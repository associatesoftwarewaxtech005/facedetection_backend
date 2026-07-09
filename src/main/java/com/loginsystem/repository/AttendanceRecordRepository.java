package com.loginsystem.repository;

import com.loginsystem.model.Employee;
import com.loginsystem.model.AttendanceRecord;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Repository
public interface AttendanceRecordRepository extends JpaRepository<AttendanceRecord, Long> {
    List<AttendanceRecord> findByEmployee(Employee employee);
    Optional<AttendanceRecord> findByEmployeeAndDate(Employee employee, LocalDate date);
    List<AttendanceRecord> findByDateBetween(LocalDate start, LocalDate end);
    List<AttendanceRecord> findAllByOrderByDateDescCheckInTimeDesc();
    void deleteByEmployee(Employee employee);
}
