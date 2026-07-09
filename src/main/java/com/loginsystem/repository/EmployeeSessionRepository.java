package com.loginsystem.repository;

import com.loginsystem.model.EmployeeSession;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface EmployeeSessionRepository extends JpaRepository<EmployeeSession, Long> {
    List<EmployeeSession> findAllByOrderByTimestampDesc();
}
