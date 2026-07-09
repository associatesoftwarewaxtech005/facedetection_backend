package com.loginsystem.repository;

import com.loginsystem.model.Employee;
import com.loginsystem.model.EmployeeFaceImage;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface EmployeeFaceImageRepository extends JpaRepository<EmployeeFaceImage, Long> {
    List<EmployeeFaceImage> findByEmployee(Employee employee);
    void deleteByEmployee(Employee employee);
}
