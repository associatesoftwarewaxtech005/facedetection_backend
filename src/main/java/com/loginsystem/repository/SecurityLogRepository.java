package com.loginsystem.repository;

import com.loginsystem.model.SecurityLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface SecurityLogRepository extends JpaRepository<SecurityLog, Long> {
    List<SecurityLog> findAllByOrderByTimestampDesc();
}
