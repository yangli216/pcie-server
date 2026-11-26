package com.regionalai.floatingball.server.modules.emrqc.service;

import java.util.regex.Pattern;

/** Identifies an explicit current proxy refill in the extracted chief complaint only. */
final class EmrQcProxyRefill {
    static final String SOURCE = "proxy_refill";
    private static final String TERM = "代配药";
    private static final Pattern QUOTED = Pattern.compile("“[^”]*”|‘[^’]*’|\"[^\"]*\"|'[^']*'");
    private static final Pattern NON_CURRENT = Pattern.compile(
        "既往|曾经|曾|上次|过去|以往|以前|昨日|昨天|咨询|询问|关于|例如|举例|是否|能否|可否|如果|假如|可能|考虑|计划|建议|拟|若");
    private static final Pattern NEGATED = Pattern.compile(
        "(?:没有|没|无需|无须|无|非|不|未|否认|取消|拒绝|禁止|排除)"
            + "(?:(?:为|系|属|属于|是|需要|需|须|要求|进行|过|由|家长|家属|患者|患儿|本次|此次|今日|今天|的|就诊|行|予|"
            + "来|前来|来院|请|要|近期|近来|父亲|母亲|父母|爸爸|妈妈|丈夫|妻子|儿子|女儿|亲属|监护人))*$");
    private static final Pattern NON_AFFIRMATIVE_SUFFIX = Pattern.compile(
        "^(?:史|咨询|流程|问题|是否|不存在|不属实|并非本次|不是本次|[？?])");

    private EmrQcProxyRefill() { }

    static boolean matches(String chiefComplaint) {
        if (chiefComplaint == null) return false;
        String text = QUOTED.matcher(chiefComplaint).replaceAll("");
        boolean affirmative = false;
        boolean denied = false;
        for (String clause : text.split("[，,。；;！!\\r\\n\\u0000]")) {
            for (int start = clause.indexOf(TERM); start >= 0; start = clause.indexOf(TERM, start + TERM.length())) {
                String prefix = clause.substring(0, start).replaceAll("[\\s\\u3000]+", "");
                String suffix = clause.substring(start + TERM.length()).trim();
                if (NON_CURRENT.matcher(prefix).find() || NON_AFFIRMATIVE_SUFFIX.matcher(suffix).find()) continue;
                if (NEGATED.matcher(prefix).find()) denied = true;
                else affirmative = true;
            }
        }
        // Conflicting current assertions cannot establish an exemption.
        return affirmative && !denied;
    }
}
