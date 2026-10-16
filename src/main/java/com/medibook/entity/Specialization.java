package com.medibook.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(name = "specializations", uniqueConstraints = @UniqueConstraint(name = "uk_spec_name", columnNames = "name"))
@Getter @Setter
public class Specialization {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(nullable = false, length = 80) private String name;
    @Column(length = 255) private String description;
}
