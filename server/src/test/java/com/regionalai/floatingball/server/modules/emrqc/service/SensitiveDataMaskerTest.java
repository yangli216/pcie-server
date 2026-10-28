package com.regionalai.floatingball.server.modules.emrqc.service;

import com.regionalai.floatingball.server.modules.phisemr.model.PhisEmrSection;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class SensitiveDataMaskerTest {

    private final SensitiveDataMasker masker = new SensitiveDataMasker();

    @Test
    void maskNameKeepsOnlyFirstCharacter() {
        assertEquals("张*", masker.maskName("张三"));
        assertEquals("欧*", masker.maskName("欧阳锋"));
        assertNull(masker.maskName(" "));
    }

    @Test
    void maskIdCardKeepsPrefixAndSuffix() {
        assertEquals("330***********1234", masker.maskIdCard("330102199001011234"));
        assertNull(masker.maskIdCard("1234567"));
    }

    @Test
    void maskSectionsReplacesAddressContactAndIdentityLiterals() {
        List<PhisEmrSection> sections = Arrays.asList(
            section("基本信息",
                field("家庭住址", "杭州市萧山区人民路 1 号"),
                field("联系电话", "13800138000"),
                field("患者姓名", "张三"),
                field("身份证号", "330102199001011234"),
                field("病案号", "BA0001")),
            section("主诉",
                field("主诉", "张三反复咳嗽3天，可联系13800138000"))
        );

        List<PhisEmrSection> masked = masker.maskSections(sections, "张三", "330102199001011234", "BA0001");

        assertEquals("[地址]", masked.get(0).getFields().get(0).getValue());
        assertEquals("[联系方式]", masked.get(0).getFields().get(1).getValue());
        assertEquals("[姓名]", masked.get(0).getFields().get(2).getValue());
        assertEquals("[身份证号]", masked.get(0).getFields().get(3).getValue());
        assertEquals("[病案号]", masked.get(0).getFields().get(4).getValue());
        assertEquals("[姓名]反复咳嗽3天，可联系[联系方式]", masked.get(1).getFields().get(0).getValue());
        assertEquals("主诉", masked.get(1).getName());
    }

    @Test
    void maskTextKeepsNonSensitiveContent() {
        assertEquals("反复咳嗽3天", masker.maskText("反复咳嗽3天", "张三", "330102199001011234", "BA0001"));
    }

    @Test
    void masksIdentifiersInFreeTextBeforeAi() {
        String masked = masker.maskText("联系人：李四，身份证号：330102199001011234，病案号：ABC123，住址：杭州市人民路1号，电话0571-12345678", null, null, null);
        assertEquals("联系人：[姓名]，身份证号：[号码]，病案号：[号码]，住址：[地址]，电话[联系方式]", masked);
    }

    private PhisEmrSection section(String name, PhisEmrSection.Field... fields) {
        PhisEmrSection section = new PhisEmrSection();
        section.setName(name);
        section.setFields(new ArrayList<PhisEmrSection.Field>(Arrays.asList(fields)));
        return section;
    }

    private PhisEmrSection.Field field(String name, String value) {
        PhisEmrSection.Field field = new PhisEmrSection.Field();
        field.setName(name);
        field.setValue(value);
        return field;
    }
}
