package com.regionalai.floatingball.server.modules.inpatientlog.service;

import com.regionalai.floatingball.server.common.exception.BusinessException;
import com.regionalai.floatingball.server.modules.inpatientlog.dto.InpatientLogSnapshot;
import javax.validation.ConstraintViolation;
import javax.validation.Validator;
import javax.validation.constraints.Max;
import javax.validation.constraints.Min;
import javax.validation.constraints.Size;
import java.lang.annotation.Annotation;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

final class InpatientLogValidation {
    private static final String PREFIX = "住院日志字段缺失、过长或格式非法：";
    private static final int MAX_DETAILS = 8;

    private InpatientLogValidation() {}

    static void validate(InpatientLogSnapshot snapshot, Validator validator) {
        if (snapshot == null) throw new BusinessException(PREFIX + "snapshot(NotNull)");
        Set<ConstraintViolation<InpatientLogSnapshot>> violations = validator.validate(snapshot);
        if (violations.isEmpty()) return;

        // Paths come only from this typed DTO and list indexes. Never format invalid values or messages.
        Set<String> sorted = new TreeSet<>();
        for (ConstraintViolation<InpatientLogSnapshot> violation : violations) sorted.add(detail(violation));
        List<String> details = new ArrayList<>(sorted);
        String summary = String.join("; ", details.subList(0, Math.min(MAX_DETAILS, details.size())));
        if (details.size() > MAX_DETAILS) summary += "; 另有" + (details.size() - MAX_DETAILS) + "项";
        throw new BusinessException(PREFIX + summary);
    }

    private static String detail(ConstraintViolation<InpatientLogSnapshot> violation) {
        Annotation rule = violation.getConstraintDescriptor().getAnnotation();
        StringBuilder detail = new StringBuilder(violation.getPropertyPath().toString())
            .append('(').append(rule.annotationType().getSimpleName());
        if (rule instanceof Size) {
            Object value = violation.getInvalidValue();
            if (value instanceof CharSequence) detail.append(",length=").append(((CharSequence) value).length());
            else if (value instanceof Collection) detail.append(",count=").append(((Collection<?>) value).size());
            Size size = (Size) rule;
            if (size.min() > 0) detail.append(",min=").append(size.min());
            detail.append(",max=").append(size.max());
        } else if (rule instanceof Min) detail.append(",min=").append(((Min) rule).value());
        else if (rule instanceof Max) detail.append(",max=").append(((Max) rule).value());
        return detail.append(')').toString();
    }
}
