package com.loginsystem.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.persistence.*;

@Entity
@Table(name = "employee_face_images")
@JsonIgnoreProperties({"hibernateLazyInitializer", "handler"})
public class EmployeeFaceImage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "employee_id", nullable = false)
    private Employee employee;

    @Lob
    @Column(columnDefinition = "LONGTEXT")
    private String faceImage; // Base64 encoded JPEG representation

    @Lob
    @Column(columnDefinition = "LONGTEXT")
    private String embedding; // JSON string representing the 128-dimensional embedding vector

    public EmployeeFaceImage() {
    }

    public EmployeeFaceImage(Employee employee, String faceImage, String embedding) {
        this.employee = employee;
        this.faceImage = faceImage;
        this.embedding = embedding;
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

    public String getFaceImage() {
        return faceImage;
    }

    public void setFaceImage(String faceImage) {
        this.faceImage = faceImage;
    }

    public String getEmbedding() {
        return embedding;
    }

    public void setEmbedding(String embedding) {
        this.embedding = embedding;
    }
}
