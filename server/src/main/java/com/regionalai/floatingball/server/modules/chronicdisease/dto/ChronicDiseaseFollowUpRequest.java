package com.regionalai.floatingball.server.modules.chronicdisease.dto;

import lombok.Data;

import javax.validation.Valid;
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.Size;
import java.util.ArrayList;
import java.util.List;

/**
 * 原慢病系统 TcdVisitForm.getFormData() 的强类型镜像。
 *
 * 页面中的多选数组在请求前已按原实例转换为逗号字符串。
 */
@Data
public class ChronicDiseaseFollowUpRequest {

    @NotBlank(message = "人员主键 idPhr 不能为空")
    @Size(max = 64, message = "idPhr 不能超过 64 个字符")
    private String idPhr;

    @NotBlank(message = "登记表主键 idRecord 不能为空")
    @Size(max = 64, message = "idRecord 不能超过 64 个字符")
    private String idRecord;

    @Size(max = 64, message = "id 不能超过 64 个字符")
    private String id;

    @NotBlank(message = "status 不能为空")
    private String status;

    @NotBlank(message = "sdVisitKind 不能为空")
    @Size(max = 8, message = "sdVisitKind 格式不正确")
    private String sdVisitKind;

    private String dtHyPlan;
    private String dtDbsPlan;
    private String sdDataWay;
    private String stature;
    private String avoirdupois;
    private String advAdp;
    private String bmi;
    private String waistline;
    private String advWaistline;
    private String pressureH;
    private String pressureL;
    private String heartRate;
    private String glu;
    private String fbgMeal;
    private String isGlu;
    private String inputUser;
    private String idUser;
    private String sdHySymptom;
    private String sdDbsSymptom;

    @Size(max = 255, message = "desOther 不能超过 255 个字符")
    private String desOther;

    private String sdArteriopalmus;
    private String sdProAct;
    private String sdPsychicAdj;
    private String fgCardiovascular;
    private String lowEffects;

    @Size(max = 255, message = "otherDisease 不能超过 255 个字符")
    private String otherDisease;

    @Size(max = 255, message = "note 不能超过 255 个字符")
    private String note;

    private String sdWehtherSmoke;
    private String daySmoke;
    private String advDaySmoke;
    private String sdWhetherDrink;
    private String dayDrink;
    private String advDayDrink;
    private String sdMainDrinking;
    private String sportWeek;
    private String advSportWeek;
    private String sportMinute;
    private String advSportMinute;
    private String sdSalt;
    private String sdAdvSalt;
    private String rice;
    private String targRice;
    private String fgDrugChange;
    private String sdDrugPro;
    private String sdSideEffects;

    @Size(max = 255, message = "desSideEffects 不能超过 255 个字符")
    private String desSideEffects;

    @Valid
    private List<TcdVisitDrugRequest> drugList = new ArrayList<TcdVisitDrugRequest>();

    private String fgRef;
    private String sdRefStatus;
    private String desRef;

    @Size(max = 64, message = "refDep 不能超过 64 个字符")
    private String refDep;

    @Size(max = 255, message = "desNoRef 不能超过 255 个字符")
    private String desNoRef;

    private String desAdr;
    private String sdComplications;

    @Size(max = 255, message = "desComplications 不能超过 255 个字符")
    private String desComplications;

    private String desComor;
    private String sdComorbidity;

    @Size(max = 255, message = "desComorbidity 不能超过 255 个字符")
    private String desComorbidity;

    private String sdMajorCc;
    private String targetOrganDamage;

    @Size(max = 5007, message = "desPresAdvice 不能超过 5007 个字符")
    private String desPresAdvice;
}
