package com.regionalai.floatingball.server.modules.chronicdisease.examination;

import lombok.Getter;
import lombok.Setter;
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.Size;
import javax.validation.constraints.Pattern;

@Getter @Setter
public class ExaminationRecommendationQuery {
    @NotBlank @Size(max = 128) private String patientId;
    @NotBlank @Size(max = 128) private String visitId;
    @NotBlank @Pattern(regexp = "[0-9]{17}[0-9Xx]") private String idCard;
}
