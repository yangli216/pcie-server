package com.regionalai.floatingball.server.modules.release.controller;

import com.regionalai.floatingball.server.modules.release.dto.ReleaseDownloadItem;
import com.regionalai.floatingball.server.modules.release.service.ReleaseService;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import javax.servlet.http.HttpServletRequest;
import java.util.List;

@Controller
public class ClientDownloadPageController {

    private final ReleaseService releaseService;

    public ClientDownloadPageController(ReleaseService releaseService) {
        this.releaseService = releaseService;
    }

    @GetMapping("/client-download")
    public ResponseEntity<String> downloadPage(@RequestParam(required = false) String channel,
                                               HttpServletRequest request) {
        String selectedChannel = resolveChannel(channel);
        String baseUrl = ServletUriComponentsBuilder.fromRequestUri(request)
            .replacePath(null)
            .replaceQuery(null)
            .build()
            .toUriString();
        String publicBaseUrl = releaseService.normalizeExternalBaseUrl(baseUrl);
        List<ReleaseDownloadItem> items = releaseService.downloadItems(selectedChannel, publicBaseUrl);
        return ResponseEntity.ok()
            .contentType(MediaType.TEXT_HTML)
            .body(renderPage(selectedChannel, items));
    }

    private String renderPage(String selectedChannel, List<ReleaseDownloadItem> items) {
        String title = "全医慧助客户端下载";
        StringBuilder html = new StringBuilder(8192);
        html.append("<!doctype html><html lang=\"zh-CN\"><head><meta charset=\"utf-8\">")
            .append("<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">")
            .append("<title>").append(title).append("</title>")
            .append("<style>")
            .append(":root{font-family:-apple-system,BlinkMacSystemFont,\"Segoe UI\",sans-serif;color:#1f2933;background:#f6f7f9;}")
            .append("*{box-sizing:border-box}body{margin:0}.shell{max-width:980px;margin:0 auto;padding:40px 20px 56px}")
            .append(".header{display:flex;justify-content:space-between;gap:16px;align-items:flex-start;margin-bottom:22px}")
            .append("h1{margin:0 0 8px;font-size:28px;line-height:1.25;color:#17212b}.sub{margin:0;color:#5d6975;font-size:14px}")
            .append(".tabs{display:flex;gap:8px;flex-wrap:wrap}.tab{display:inline-flex;align-items:center;min-height:34px;padding:0 12px;border:1px solid #d8dde3;border-radius:6px;color:#344054;text-decoration:none;background:#fff;font-size:14px}")
            .append(".tab.active{border-color:#1d9e75;color:#116b50;background:#eaf7f2;font-weight:600}")
            .append(".panel{background:#fff;border:1px solid #e3e7eb;border-radius:8px;overflow:hidden;box-shadow:0 8px 24px rgba(17,24,39,.06)}")
            .append(".row{display:grid;grid-template-columns:1.2fr 1fr 1fr auto;gap:14px;align-items:center;padding:18px 20px;border-top:1px solid #edf0f2}.row:first-child{border-top:0}")
            .append(".name{font-weight:650;color:#1f2933;word-break:break-word}.meta{margin-top:6px;color:#66727f;font-size:13px;word-break:break-word}")
            .append(".label{color:#66727f;font-size:12px;margin-bottom:4px}.value{color:#1f2933;font-size:14px;word-break:break-word}")
            .append(".btn{display:inline-flex;align-items:center;justify-content:center;min-height:36px;padding:0 14px;border-radius:6px;background:#1d9e75;color:#fff;text-decoration:none;font-weight:650;white-space:nowrap}")
            .append(".empty{padding:42px 24px;text-align:center;color:#66727f}.empty strong{display:block;margin-bottom:8px;color:#1f2933;font-size:18px}")
            .append("@media(max-width:760px){.shell{padding:24px 14px 40px}.header{display:block}.tabs{margin-top:16px}.row{grid-template-columns:1fr}.btn{width:100%}}")
            .append("</style></head><body><main class=\"shell\">")
            .append("<div class=\"header\"><div><h1>").append(title).append("</h1>")
            .append("<p class=\"sub\">选择当前内网发布通道中的客户端安装包。</p></div>")
            .append("<nav class=\"tabs\">")
            .append(channelLink("production", "正式内网", selectedChannel))
            .append(channelLink("testing", "测试内网", selectedChannel))
            .append("</nav></div><section class=\"panel\">");

        if (items == null || items.isEmpty()) {
            html.append("<div class=\"empty\"><strong>暂无可下载客户端</strong><span>请先在后台“版本发布”上传安装包。</span></div>");
        } else {
            for (ReleaseDownloadItem item : items) {
                html.append("<div class=\"row\">")
                    .append("<div><div class=\"name\">").append(escapeHtml(item.getFileName())).append("</div>")
                    .append("<div class=\"meta\">版本 ").append(escapeHtml(item.getVersion()))
                    .append(" · ").append(escapeHtml(item.getChannel())).append("</div></div>")
                    .append("<div><div class=\"label\">平台</div><div class=\"value\">").append(escapeHtml(item.getTarget())).append("</div></div>")
                    .append("<div><div class=\"label\">大小 / 时间</div><div class=\"value\">")
                    .append(escapeHtml(formatFileSize(item.getFileSize()))).append(" · ")
                    .append(escapeHtml(emptyAsDash(item.getPubDate()))).append("</div></div>")
                    .append("<a class=\"btn\" href=\"").append(escapeHtml(item.getDownloadUrl())).append("\">下载</a>")
                    .append("</div>");
            }
        }

        html.append("</section></main></body></html>");
        return html.toString();
    }

    private String channelLink(String channel, String label, String selectedChannel) {
        boolean active = channel.equals(selectedChannel);
        return "<a class=\"tab" + (active ? " active" : "") + "\" href=\"/client-download?channel=" + channel + "\">"
            + escapeHtml(label) + "</a>";
    }

    private String resolveChannel(String channel) {
        if (!StringUtils.hasText(channel)) {
            return "production";
        }
        String value = channel.trim();
        return "testing".equals(value) ? "testing" : "production";
    }

    private String formatFileSize(Long value) {
        if (value == null || value <= 0) {
            return "--";
        }
        double size = value.doubleValue();
        if (size >= 1024 * 1024) {
            return String.format("%.1f MB", size / 1024 / 1024);
        }
        if (size >= 1024) {
            return String.format("%.1f KB", size / 1024);
        }
        return value + " B";
    }

    private String emptyAsDash(String value) {
        return StringUtils.hasText(value) ? value : "--";
    }

    private String escapeHtml(String value) {
        if (value == null) {
            return "";
        }
        return value
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&#39;");
    }
}
