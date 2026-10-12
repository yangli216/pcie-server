package com.regionalai.floatingball.server.modules.publichealth.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.DecimalNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.regionalai.floatingball.server.modules.publichealth.dto.PublicHealthRequest;
import org.springframework.stereotype.Component;
import java.math.BigDecimal;
import java.math.RoundingMode;

@Component
public strictfp class WeightMetricsCalculator {
    public static final String VERSION = "legacy-bsoft-bmi-bmr.v1";
    private final ObjectMapper mapper;
    public WeightMetricsCalculator(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public ObjectNode calculate(PublicHealthRequest r) {
        PublicHealthRequest.WeightProfile p = r.getFacts().getWeightProfile();
        double heightMetres = p.getHeightCm().doubleValue() / 100.0;
        double weight = p.getWeightKg().doubleValue();
        double age = r.getPatient().getAgeYears();
        double heightCentimetres = heightMetres * 100.0;
        double rawBmi = weight / Math.pow(heightMetres, 2.0);
        boolean male = "male".equals(r.getPatient().getSex());
        if (!male && !"female".equals(r.getPatient().getSex())) {
            throw PublicHealthValidator.missing("patient.sex");
        }
        double rawBmr = male
                ? 66 + 13.7 * weight + 5.0 * heightCentimetres - 6.8 * age
                : 655 + 9.5 * weight + 1.8 * heightCentimetres - 4.7 * age;
        BigDecimal bmi = pythonRoundOneDecimal(rawBmi);
        BigDecimal bmr = pythonRoundOneDecimal(rawBmr);
        if (bmi.signum() <= 0 || bmi.compareTo(BigDecimal.valueOf(1000)) > 0
                || bmr.signum() <= 0 || bmr.compareTo(BigDecimal.valueOf(100000)) > 0) {
            throw PublicHealthValidator.invalid("输入不适用当前计算公式");
        }
        ObjectNode out = mapper.createObjectNode();
        out.set("bmi", new DecimalNode(bmi));
        out.set("bmrKcalPerDay", new DecimalNode(bmr));
        out.put("formulaVersion", VERSION);
        return out;
    }

    private BigDecimal pythonRoundOneDecimal(double value) {
        if (!Double.isFinite(value)) {
            throw PublicHealthValidator.invalid("输入不适用当前计算公式");
        }
        // The double constructor retains the binary value. valueOf would round its
        // decimal display first and would change Python round(12.35, 1) semantics.
        return new BigDecimal(value).setScale(1, RoundingMode.HALF_EVEN);
    }
}
