package com.example.dto;

import java.util.List;

public class BulkQueryRequest {

    private List<String> agencyCodes;

    public BulkQueryRequest() {
    }

    public BulkQueryRequest(List<String> agencyCodes) {
        this.agencyCodes = agencyCodes;
    }

    public List<String> getAgencyCodes() {
        return agencyCodes;
    }

    public void setAgencyCodes(List<String> agencyCodes) {
        this.agencyCodes = agencyCodes;
    }
}
