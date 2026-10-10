package com.example.dto;

import java.io.Serializable;

public class AgencyDto implements Serializable {

    private Long id;
    private String agencyCode;
    private String name;
    private String email;
    private String status;

    public AgencyDto() {
    }

    public AgencyDto(Long id, String agencyCode, String name, String email, String status) {
        this.id = id;
        this.agencyCode = agencyCode;
        this.name = name;
        this.email = email;
        this.status = status;
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

    @Override
    public String toString() {
        return "AgencyDto{" +
                "id=" + id +
                ", agencyCode='" + agencyCode + '\'' +
                ", name='" + name + '\'' +
                ", email='" + email + '\'' +
                ", status='" + status + '\'' +
                '}';
    }
}
