package com.regionalai.floatingball.server.modules.auth.bbp;

import lombok.Data;

@Data
public class BbpPersonView {
    private String id;
    private String userId;
    private String personId;
    private String loginName;
    private String tenantId;
    private String personType;
    private String personTypeText;
    private String mpiId;
    private String mpi;
    private String code;
    private String name;
    private String description;
    private String py;
    private String wb;
    private String zj;
    private String instr;
    private String orgId;
    private String orgName;
    private String departmentId;
    private String departmentName;
    private String mobile;
    private String email;
    private String feature;
    private String cardType;
    private String cardTypeText;
    private String cardId;
    private String gender;
    private String genderText;
    private String birthday;
    private String avatar;
    private Boolean active;
    private String createDate;
    private String modifyDate;
    private String titleType;
    private String titleTypeText;
}
