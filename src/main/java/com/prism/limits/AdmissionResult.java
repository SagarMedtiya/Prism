package com.prism.limits;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class AdmissionResult {
    private boolean admitted;
    private String rejectionCode; // null if admitted
    private int httpStatus; // 0 if admitted

    public static AdmissionResult admitted() {
        return new AdmissionResult(true, null, 0);
    }

    public static AdmissionResult rejected(String code, int status) {
        return new AdmissionResult(false, code, status);
    }
}