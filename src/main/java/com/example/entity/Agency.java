package com.example.entity;

import com.example.dto.AgencyDto;
import com.example.listener.AgencyEntityListener;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;

@Entity
@Table(name = "agency")
@EntityListeners(AgencyEntityListener.class)
@JsonIgnoreProperties(value = {"persistent"}, ignoreUnknown = true)
public class Agency extends PanacheEntityBase {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "agency_code", nullable = false, unique = true, length = 50)
    private String agencyCode;

    @Column(nullable = false, length = 150)
    private String name;

    @Column(length = 100)
    private String email;

    @Column(length = 30)
    private String status;

    public Agency() {
    }

    public Agency(String agencyCode, String name, String email, String status) {
        this.agencyCode = agencyCode;
        this.name = name;
        this.email = email;
        this.status = status;
    }

    public Agency(Long id, String agencyCode, String name, String email, String status) {
        this.id = id;
        this.agencyCode = agencyCode;
        this.name = name;
        this.email = email;
        this.status = status;
    }

    public static Agency fromDto(AgencyDto dto) {
        if (dto == null) return null;
        return new Agency(dto.getId(), dto.getAgencyCode(), dto.getName(), dto.getEmail(), dto.getStatus());
    }

    public AgencyDto toDto() {
        return new AgencyDto(this.id, this.agencyCode, this.name, this.email, this.status);
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getAgencyCode() {
        return agencyCode;
    }

    public void setAgencyCode(String agencyCode) {
        this.agencyCode = agencyCode;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }
}
