package com.regionalai.floatingball.server.modules.release.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.regionalai.floatingball.server.common.api.PageRequest;
import com.regionalai.floatingball.server.common.api.PageResponse;
import com.regionalai.floatingball.server.common.exception.BusinessException;
import com.regionalai.floatingball.server.modules.release.dto.ReleaseBatchUploadRequest;
import com.regionalai.floatingball.server.modules.release.dto.ReleaseDownloadItem;
import com.regionalai.floatingball.server.modules.release.dto.ReleaseHistoryView;
import com.regionalai.floatingball.server.modules.release.dto.ReleasePlatformView;
import com.regionalai.floatingball.server.modules.release.dto.ReleasePolicyUpdateRequest;
import com.regionalai.floatingball.server.modules.release.dto.ReleasePolicyView;
import com.regionalai.floatingball.server.modules.release.dto.ReleaseRollbackRequest;
import com.regionalai.floatingball.server.modules.release.dto.ReleaseUploadRequest;
import com.regionalai.floatingball.server.modules.release.dto.ReleaseView;
import com.regionalai.floatingball.server.modules.release.dto.TauriLatestJson;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;
import org.springframework.web.util.UriComponentsBuilder;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Enumeration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;

@Service
public class ReleaseService {

    private static final Logger log = LoggerFactory.getLogger(ReleaseService.class);

    private static final List<String> CHANNELS = Arrays.asList(
        "production",
        "testing",
        "win7-production",
        "win7-testing"
    );
    private static final Pattern SAFE_SEGMENT = Pattern.compile("[A-Za-z0-9._-]+");
    private static final String LATEST_FILE = "latest.json";
    private static final String POLICY_FILE = "policy.json";
    private static final String HISTORY_DIR = "history";

    private final Path storageRoot;
    private final ObjectMapper objectMapper;
    private final String publicBaseUrl;

    public ReleaseService(@Value("${floating-ball.release.storage-dir:${java.io.tmpdir}/floating-ball-server/releases}") String storageRoot,
                          @Value("${floating-ball.release.public-base-url:}") String publicBaseUrl,
                          ObjectMapper objectMapper) {
        this.storageRoot = Paths.get(storageRoot).toAbsolutePath().normalize();
        this.publicBaseUrl = trimTrailingSlash(publicBaseUrl);
        this.objectMapper = objectMapper;
    }

    public List<ReleaseView> list(String channel) {
        if (StringUtils.hasText(channel)) {
            return Collections.singletonList(readReleaseView(normalizeChannel(channel)));
        }

        List<ReleaseView> views = new ArrayList<ReleaseView>();
        for (String item : CHANNELS) {
            views.add(readReleaseView(item));
        }
        return views;
    }

    public List<ReleaseHistoryView> history(String channel) {
        if (StringUtils.hasText(channel)) {
            return readHistoryViews(normalizeChannel(channel));
        }

        List<ReleaseHistoryView> views = new ArrayList<ReleaseHistoryView>();
        for (String item : CHANNELS) {
            views.addAll(readHistoryViews(item));
        }
        views.sort(historyUpdatedAtDesc());
        return views;
    }

    public PageResponse<ReleaseHistoryView> history(String channel, long current, long size) {
        PageRequest pageRequest = PageRequest.of(current, size);
        List<ReleaseHistoryView> views = history(channel);
        int fromIndex = pageRequest.fromIndex(views.size());
        int toIndex = pageRequest.toIndex(views.size());
        return new PageResponse<ReleaseHistoryView>(
            pageRequest.getCurrent(),
            pageRequest.getSize(),
            views.size(),
            new ArrayList<ReleaseHistoryView>(views.subList(fromIndex, toIndex))
        );
    }

    public List<ReleaseDownloadItem> downloadItems(String channel, String baseUrl) {
        String normalizedChannel = normalizePolicyChannel(channel);
        TauriLatestJson latestJson = getAvailableLatestJson(normalizedChannel, baseUrl);
        if (latestJson == null || latestJson.getPlatforms() == null || latestJson.getPlatforms().isEmpty()) {
            return Collections.emptyList();
        }

        List<ReleaseDownloadItem> items = new ArrayList<ReleaseDownloadItem>();
        for (String target : latestJson.getPlatforms().keySet()) {
            TauriLatestJson.PlatformInfo platformInfo = latestJson.getPlatforms().get(target);
            String fileName = platformInfo == null ? "" : extractFileName(platformInfo.getUrl());
            ReleaseDownloadItem item = new ReleaseDownloadItem();
            item.setChannel(normalizedChannel);
            item.setVersion(latestJson.getVersion());
            item.setTarget(target);
            item.setFileName(fileName);
            item.setDownloadUrl(platformInfo == null ? "" : platformInfo.getUrl());
            item.setPubDate(latestJson.getPubDate());
            item.setNotes(latestJson.getNotes());
            item.setFileSize(resolveFileSize(normalizedChannel, target, fileName));
            items.add(item);
        }
        return items;
    }

    public ReleaseView rollback(ReleaseRollbackRequest request) {
        if (request == null) {
            throw new BusinessException("请求体不能为空");
        }
        String channel = normalizeChannel(request.getChannel());
        String version = requireSafeText(request.getVersion(), "回滚版本不能为空", "回滚版本号非法");
        TauriLatestJson snapshotLatestJson = readHistoryLatestJson(channel, version);
        ReleasePolicyView snapshotPolicy = readHistoryPolicy(channel, version, snapshotLatestJson);
        validateSnapshotFiles(channel, snapshotLatestJson);

        TauriLatestJson currentLatestJson = readLatestJson(channel);
        if (StringUtils.hasText(currentLatestJson.getVersion()) && !version.equals(currentLatestJson.getVersion())) {
            try {
                writeHistorySnapshot(channel, currentLatestJson, readPolicy(channel));
            } catch (IOException ex) {
                throw new BusinessException("RELEASE-IO", "保存当前发布快照失败: " + ex.getMessage());
            }
        }

        rewritePlatformUrls(snapshotLatestJson, channel);
        normalizePolicyForLatest(channel, snapshotPolicy, snapshotLatestJson);
        snapshotPolicy.setUpdatedAt(System.currentTimeMillis());

        try {
            writeLatestJson(channel, snapshotLatestJson);
            writePolicy(channel, snapshotPolicy);
            writeHistorySnapshot(channel, snapshotLatestJson, snapshotPolicy);
        } catch (IOException ex) {
            throw new BusinessException("RELEASE-IO", "回滚发布版本失败: " + ex.getMessage());
        }

        log.info("release rollback. channel={}, version={}", channel, snapshotLatestJson.getVersion());
        return readReleaseView(channel);
    }

    public ReleaseView updatePolicy(ReleasePolicyUpdateRequest request) {
        if (request == null) {
            throw new BusinessException("请求体不能为空");
        }
        String channel = normalizeChannel(request.getChannel());
        boolean forceUpdate = Boolean.TRUE.equals(request.getForceUpdate());
        TauriLatestJson latestJson = readLatestJson(channel);
        if (!StringUtils.hasText(latestJson.getVersion()) || latestJson.getPlatforms().isEmpty()) {
            throw new BusinessException("RELEASE-404", "当前通道暂无可用版本，无法切换强制更新");
        }

        ReleasePolicyView policy = readPolicy(channel);
        policy.setChannel(channel);
        policy.setLatestVersion(latestJson.getVersion());
        policy.setForceUpdate(forceUpdate);
        policy.setMinSupportedVersion(forceUpdate ? latestJson.getVersion() : null);
        policy.setLatestJsonUrl(buildLatestJsonUrl(channel));
        policy.setNotes(latestJson.getNotes());
        policy.setPubDate(latestJson.getPubDate());
        policy.setUpdatedAt(System.currentTimeMillis());

        try {
            writePolicy(channel, policy);
            writeHistorySnapshot(channel, latestJson, policy);
        } catch (IOException ex) {
            throw new BusinessException("RELEASE-IO", "更新强制更新策略失败: " + ex.getMessage());
        }

        log.info("release policy updated. channel={}, forceUpdate={}", channel, forceUpdate);
        return readReleaseView(channel);
    }

    public synchronized ReleaseView upload(ReleaseUploadRequest request) {
        String channel = normalizeChannel(request.getChannel());
        MultipartFile file = request.getFile();
        if (file == null || file.isEmpty()) {
            throw new BusinessException("安装包文件不能为空");
        }

        String originalFileName = safeFileName(file.getOriginalFilename());
        TauriLatestJson uploadedLatestJson = readUploadedLatestJson(request.getMetadataFile());
        ReleaseMetadata releaseMetadata = resolveReleaseMetadata(request, uploadedLatestJson, originalFileName);
        TauriLatestJson currentLatestJson = readLatestJson(channel);
        String version = releaseMetadata.version;
        String target = releaseMetadata.target;
        String signature = releaseMetadata.signature;
        String pubDate = releaseMetadata.pubDate;
        String notes = releaseMetadata.notes;
        validateWin7ReleaseVersion(channel, version, currentLatestJson);
        validatePackageMatchesMetadata(uploadedLatestJson, originalFileName, releaseMetadata);
        Path targetDirectory = storageRoot.resolve(channel).resolve(target).normalize();
        ensureInsideStorage(targetDirectory);

        try {
            Files.createDirectories(targetDirectory);
            Path targetPath = targetDirectory.resolve(originalFileName).normalize();
            ensureInsideStorage(targetPath);
            try (InputStream inputStream = file.getInputStream()) {
                Files.copy(inputStream, targetPath, StandardCopyOption.REPLACE_EXISTING);
            }

            TauriLatestJson latestJson = currentLatestJson;
            if (StringUtils.hasText(currentLatestJson.getVersion()) && !version.equals(currentLatestJson.getVersion())) {
                writeHistorySnapshot(channel, currentLatestJson, readPolicy(channel));
                latestJson = new TauriLatestJson();
            }
            latestJson.setVersion(version);
            latestJson.setNotes(notes);
            latestJson.setPubDate(pubDate);
            TauriLatestJson.PlatformInfo platformInfo = new TauriLatestJson.PlatformInfo();
            platformInfo.setSignature(signature);
            platformInfo.setUrl(buildFileUrl(channel, target, originalFileName));
            latestJson.getPlatforms().put(target, platformInfo);
            writeLatestJson(channel, latestJson);
            ReleasePolicyView policy = updateReleasePolicy(channel, version, pubDate, notes, request.getForceUpdate());
            writeHistorySnapshot(channel, latestJson, policy);

            log.info("release uploaded. channel={}, version={}, target={}, fileName={}", channel, version, target, originalFileName);
            return toReleaseView(channel, version, target, originalFileName, Files.size(targetPath), platformInfo.getUrl(), pubDate, notes, policy);
        } catch (IOException ex) {
            throw new BusinessException("RELEASE-IO", "保存发布文件失败: " + ex.getMessage());
        }
    }

    public synchronized List<ReleaseView> uploadBatch(ReleaseBatchUploadRequest request) {
        if (request == null) {
            throw new BusinessException("请求体不能为空");
        }
        List<String> channels = normalizeChannels(request);
        List<MultipartFile> files = normalizeUploadFiles(request);
        TauriLatestJson uploadedLatestJson = readUploadedLatestJson(request.getMetadataFile());
        if (uploadedLatestJson == null || uploadedLatestJson.getPlatforms() == null || uploadedLatestJson.getPlatforms().isEmpty()) {
            throw new BusinessException("批量发布必须上传包含 platforms 的 latest.json");
        }

        List<ReleasePackage> packages = resolveReleasePackages(request, uploadedLatestJson, files);
        ReleaseMetadata releaseMetadata = firstReleaseMetadata(packages);
        List<ReleaseView> views = new ArrayList<ReleaseView>();
        try {
            for (String channel : channels) {
                validateWin7ReleaseVersion(channel, releaseMetadata.version, readLatestJson(channel));
            }
            for (String channel : channels) {
                applyReleasePackages(channel, releaseMetadata, packages, request.getForceUpdate());
                views.add(readReleaseView(channel));
            }
            return views;
        } catch (IOException ex) {
            throw new BusinessException("RELEASE-IO", "保存发布文件失败: " + ex.getMessage());
        }
    }

    private List<String> normalizeChannels(ReleaseBatchUploadRequest request) {
        LinkedHashSet<String> values = new LinkedHashSet<String>();
        if (request.getChannels() != null) {
            for (String channel : request.getChannels()) {
                if (StringUtils.hasText(channel)) {
                    values.add(normalizeChannel(channel));
                }
            }
        }
        if (StringUtils.hasText(request.getChannel())) {
            values.add(normalizeChannel(request.getChannel()));
        }
        if (values.isEmpty()) {
            throw new BusinessException("发布通道不能为空");
        }
        return new ArrayList<String>(values);
    }

    private List<MultipartFile> normalizeUploadFiles(ReleaseBatchUploadRequest request) {
        List<MultipartFile> values = new ArrayList<MultipartFile>();
        if (request.getFiles() != null) {
            for (MultipartFile file : request.getFiles()) {
                if (file != null && !file.isEmpty()) {
                    values.add(file);
                }
            }
        }
        if (request.getFile() != null && !request.getFile().isEmpty()) {
            values.add(request.getFile());
        }
        if (values.isEmpty()) {
            throw new BusinessException("安装包文件不能为空");
        }
        return values;
    }

    private List<ReleasePackage> resolveReleasePackages(ReleaseBatchUploadRequest request,
                                                        TauriLatestJson uploadedLatestJson,
                                                        List<MultipartFile> files) {
        List<ReleasePackage> packages = new ArrayList<ReleasePackage>();
        Set<String> targets = new HashSet<String>();
        Set<String> fileNames = new HashSet<String>();
        String version = null;
        for (MultipartFile file : files) {
            String originalFileName = safeFileName(file.getOriginalFilename());
            if (!fileNames.add(originalFileName)) {
                throw new BusinessException("同一次发布中存在重复安装包文件名: " + originalFileName);
            }
            List<ReleaseMetadata> metadataList = resolveBatchReleaseMetadataList(request, uploadedLatestJson, originalFileName);
            for (ReleaseMetadata metadata : metadataList) {
                validatePackageMatchesMetadata(uploadedLatestJson, originalFileName, metadata);
                if (version == null) {
                    version = metadata.version;
                } else if (!version.equals(metadata.version)) {
                    throw new BusinessException("同一次批量发布中的安装包版本必须一致");
                }
                if (!targets.add(metadata.target)) {
                    throw new BusinessException("同一次发布中存在重复平台 target: " + metadata.target);
                }
            }

            ReleasePackage releasePackage = new ReleasePackage();
            releasePackage.file = file;
            releasePackage.fileName = originalFileName;
            releasePackage.metadataList = metadataList;
            packages.add(releasePackage);
        }
        return packages;
    }

    private ReleaseMetadata firstReleaseMetadata(List<ReleasePackage> packages) {
        if (packages == null || packages.isEmpty()
            || packages.get(0).metadataList == null || packages.get(0).metadataList.isEmpty()) {
            throw new BusinessException("安装包文件不能为空");
        }
        return packages.get(0).metadataList.get(0);
    }

    private List<ReleaseMetadata> resolveBatchReleaseMetadataList(ReleaseBatchUploadRequest request,
                                                                  TauriLatestJson uploadedLatestJson,
                                                                  String originalFileName) {
        String version = trimToNull(request.getVersion());
        String notes = trimToNull(request.getNotes());
        String pubDate = trimToNull(request.getPubDate());

        version = firstText(version, uploadedLatestJson.getVersion());
        notes = firstText(notes, uploadedLatestJson.getNotes());
        pubDate = firstText(pubDate, uploadedLatestJson.getPubDate());

        List<PlatformMatch> platformMatches = findPlatformMatches(uploadedLatestJson, originalFileName, null);
        if (platformMatches.isEmpty()) {
            throw new BusinessException("无法根据安装包文件名识别平台 target: " + originalFileName);
        }

        List<ReleaseMetadata> metadataList = new ArrayList<ReleaseMetadata>();
        for (PlatformMatch platformMatch : platformMatches) {
            ReleaseMetadata metadata = new ReleaseMetadata();
            metadata.version = requireSafeText(version, "版本号不能为空，请上传 latest.json 或手工填写版本号", "版本号只能包含字母、数字、点、下划线和短横线");
            metadata.target = requireSafeText(platformMatch.target, "平台 target 不能为空", "平台 target 只能包含字母、数字、点、下划线和短横线");
            metadata.signature = requireText(platformMatch.platformInfo.getSignature(), "latest.json 缺少 " + metadata.target + " 的签名");
            metadata.notes = notes;
            metadata.pubDate = normalizePubDate(pubDate);
            metadataList.add(metadata);
        }
        return metadataList;
    }

    private void applyReleasePackages(String channel,
                                      ReleaseMetadata releaseMetadata,
                                      List<ReleasePackage> packages,
                                      Boolean forceUpdate) throws IOException {
        TauriLatestJson currentLatestJson = readLatestJson(channel);
        validateWin7ReleaseVersion(channel, releaseMetadata.version, currentLatestJson);
        TauriLatestJson latestJson = currentLatestJson;
        if (StringUtils.hasText(currentLatestJson.getVersion()) && !releaseMetadata.version.equals(currentLatestJson.getVersion())) {
            writeHistorySnapshot(channel, currentLatestJson, readPolicy(channel));
            latestJson = new TauriLatestJson();
        }

        latestJson.setVersion(releaseMetadata.version);
        latestJson.setNotes(releaseMetadata.notes);
        latestJson.setPubDate(releaseMetadata.pubDate);

        for (ReleasePackage releasePackage : packages) {
            for (ReleaseMetadata metadata : releasePackage.metadataList) {
                Path targetDirectory = storageRoot.resolve(channel).resolve(metadata.target).normalize();
                ensureInsideStorage(targetDirectory);
                Files.createDirectories(targetDirectory);
                Path targetPath = targetDirectory.resolve(releasePackage.fileName).normalize();
                ensureInsideStorage(targetPath);
                try (InputStream inputStream = releasePackage.file.getInputStream()) {
                    Files.copy(inputStream, targetPath, StandardCopyOption.REPLACE_EXISTING);
                }

                TauriLatestJson.PlatformInfo platformInfo = new TauriLatestJson.PlatformInfo();
                platformInfo.setSignature(metadata.signature);
                platformInfo.setUrl(buildFileUrl(channel, metadata.target, releasePackage.fileName));
                latestJson.getPlatforms().put(metadata.target, platformInfo);
                log.info("release file uploaded. channel={}, version={}, target={}, fileName={}", channel, metadata.version, metadata.target, releasePackage.fileName);
            }
        }

        writeLatestJson(channel, latestJson);
        ReleasePolicyView policy = updateReleasePolicy(channel, releaseMetadata.version, releaseMetadata.pubDate, releaseMetadata.notes, forceUpdate);
        writeHistorySnapshot(channel, latestJson, policy);
        log.info("release batch uploaded. channel={}, version={}, packageCount={}", channel, releaseMetadata.version, packages.size());
    }

    private TauriLatestJson readUploadedLatestJson(MultipartFile metadataFile) {
        if (metadataFile == null || metadataFile.isEmpty()) {
            return null;
        }
        try (InputStream inputStream = metadataFile.getInputStream()) {
            return objectMapper.readValue(inputStream, TauriLatestJson.class);
        } catch (IOException ex) {
            throw new BusinessException("RELEASE-JSON", "解析 latest.json 失败: " + ex.getMessage());
        }
    }

    private ReleaseMetadata resolveReleaseMetadata(ReleaseUploadRequest request,
                                                   TauriLatestJson uploadedLatestJson,
                                                   String originalFileName) {
        String version = trimToNull(request.getVersion());
        String target = trimToNull(request.getTarget());
        String signature = trimToNull(request.getSignature());
        String notes = trimToNull(request.getNotes());
        String pubDate = trimToNull(request.getPubDate());

        if (uploadedLatestJson != null) {
            version = firstText(version, uploadedLatestJson.getVersion());
            notes = firstText(notes, uploadedLatestJson.getNotes());
            pubDate = firstText(pubDate, uploadedLatestJson.getPubDate());

            PlatformMatch platformMatch = findPlatformMatch(uploadedLatestJson, originalFileName, target);
            if (platformMatch != null) {
                target = firstText(target, platformMatch.target);
                signature = firstText(signature, platformMatch.platformInfo.getSignature());
            }
        }

        version = requireSafeText(version, "版本号不能为空，请上传 latest.json 或手工填写版本号", "版本号只能包含字母、数字、点、下划线和短横线");
        target = requireSafeText(target, "平台 target 不能为空，请上传可匹配安装包的 latest.json 或手工填写 target", "平台 target 只能包含字母、数字、点、下划线和短横线");
        signature = requireText(signature, "签名不能为空，请上传包含当前安装包签名的 latest.json");

        ReleaseMetadata metadata = new ReleaseMetadata();
        metadata.version = version;
        metadata.target = target;
        metadata.signature = signature;
        metadata.notes = notes;
        metadata.pubDate = normalizePubDate(pubDate);
        return metadata;
    }

    private void validatePackageMatchesMetadata(TauriLatestJson uploadedLatestJson,
                                                String originalFileName,
                                                ReleaseMetadata metadata) {
        if (uploadedLatestJson == null) {
            return;
        }
        TauriLatestJson.PlatformInfo platformInfo = uploadedLatestJson.getPlatforms().get(metadata.target);
        if (platformInfo == null || !StringUtils.hasText(platformInfo.getUrl())) {
            return;
        }
        String signedFileName = extractFileName(platformInfo.getUrl());
        if (!originalFileName.equals(signedFileName)) {
            throw new BusinessException(
                "上传的安装包与 latest.json 中 target=" + metadata.target + " 的签名文件不一致：latest.json 签名对应 "
                    + signedFileName + "，当前上传 " + originalFileName + "。请上传 latest.json 里 url 指向的同名文件，否则 Tauri 会签名校验失败。"
            );
        }
    }

    private PlatformMatch findPlatformMatch(TauriLatestJson latestJson, String originalFileName, String preferredTarget) {
        List<PlatformMatch> matches = findPlatformMatches(latestJson, originalFileName, preferredTarget);
        return matches.isEmpty() ? null : matches.get(0);
    }

    private List<PlatformMatch> findPlatformMatches(TauriLatestJson latestJson, String originalFileName, String preferredTarget) {
        if (latestJson == null || latestJson.getPlatforms() == null || latestJson.getPlatforms().isEmpty()) {
            return Collections.emptyList();
        }
        if (StringUtils.hasText(preferredTarget) && latestJson.getPlatforms().containsKey(preferredTarget)) {
            return Collections.singletonList(new PlatformMatch(preferredTarget, latestJson.getPlatforms().get(preferredTarget)));
        }
        List<PlatformMatch> matches = new ArrayList<PlatformMatch>();
        for (String target : latestJson.getPlatforms().keySet()) {
            TauriLatestJson.PlatformInfo platformInfo = latestJson.getPlatforms().get(target);
            if (platformInfo != null && originalFileName.equals(extractFileName(platformInfo.getUrl()))) {
                matches.add(new PlatformMatch(target, platformInfo));
            }
        }
        if (!matches.isEmpty()) {
            return matches;
        }
        if (latestJson.getPlatforms().size() == 1) {
            String target = latestJson.getPlatforms().keySet().iterator().next();
            return Collections.singletonList(new PlatformMatch(target, latestJson.getPlatforms().get(target)));
        }
        throw new BusinessException("latest.json 中包含多个平台，但无法根据安装包文件名匹配；请在平台 target 中选择正确项");
    }

    public TauriLatestJson getLatestJson(String channel) {
        return getLatestJson(channel, null);
    }

    public TauriLatestJson getLatestJson(String channel, String baseUrl) {
        TauriLatestJson latestJson = getAvailableLatestJson(channel, baseUrl);
        if (latestJson == null) {
            throw new BusinessException("RELEASE-404", "当前通道暂无可用版本");
        }
        return latestJson;
    }

    public TauriLatestJson getAvailableLatestJson(String channel, String baseUrl) {
        String normalizedChannel = normalizeChannel(channel);
        TauriLatestJson latestJson = readLatestJson(normalizedChannel);
        if (!StringUtils.hasText(latestJson.getVersion()) || latestJson.getPlatforms().isEmpty()) {
            return null;
        }
        if (StringUtils.hasText(baseUrl)) {
            rewriteDownloadUrls(latestJson, normalizedChannel, baseUrl.trim().replaceAll("/+$", ""));
        }
        return latestJson;
    }

    public ReleasePolicyView getPolicy(String channel) {
        return getPolicy(channel, null);
    }

    public ReleasePolicyView getPolicy(String channel, String baseUrl) {
        String normalizedChannel = normalizeChannel(channel);
        ReleasePolicyView policy = readPolicy(normalizedChannel);
        if (StringUtils.hasText(baseUrl)) {
            policy.setLatestJsonUrl(baseUrl.trim().replaceAll("/+$", "") + "/v1/client/releases/" + normalizedChannel + "/latest.json");
        }
        return policy;
    }

    public boolean isUpdateRequired(String channel, String clientVersion) {
        ReleasePolicyView policy = readPolicy(normalizePolicyChannel(channel));
        String minSupportedVersion = trimToNull(policy.getMinSupportedVersion());
        if (!Boolean.TRUE.equals(policy.getForceUpdate()) || !StringUtils.hasText(minSupportedVersion)) {
            return false;
        }
        return compareVersions(clientVersion, minSupportedVersion) < 0;
    }

    public ReleasePolicyView getRequiredPolicy(String channel) {
        ReleasePolicyView policy = readPolicy(normalizePolicyChannel(channel));
        if (StringUtils.hasText(policy.getMinSupportedVersion())) {
            return policy;
        }
        String normalizedChannel = normalizePolicyChannel(channel);
        policy.setChannel(normalizedChannel);
        policy.setLatestJsonUrl(buildLatestJsonUrl(normalizedChannel));
        return policy;
    }

    private void rewriteDownloadUrls(TauriLatestJson latestJson, String channel, String baseUrl) {
        for (String target : latestJson.getPlatforms().keySet()) {
            TauriLatestJson.PlatformInfo platformInfo = latestJson.getPlatforms().get(target);
            String fileName = extractFileName(platformInfo.getUrl());
            if (StringUtils.hasText(fileName)) {
                platformInfo.setUrl(baseUrl + "/v1/client/releases/" + channel + "/files/" + target + "/" + fileName);
            }
        }
    }

    public Path resolveFile(String channel, String target, String fileName) {
        String normalizedChannel = normalizeChannel(channel);
        String normalizedTarget = requireSafeText(target, "平台 target 不能为空", "平台 target 非法");
        String normalizedFileName = safeFileName(fileName);
        Path filePath = storageRoot.resolve(normalizedChannel).resolve(normalizedTarget).resolve(normalizedFileName).normalize();
        ensureInsideStorage(filePath);
        if (!Files.isRegularFile(filePath)) {
            throw new BusinessException("RELEASE-404", "安装包文件不存在");
        }
        return filePath;
    }

    private ReleaseView readReleaseView(String channel) {
        TauriLatestJson latestJson = readLatestJson(channel);
        if (!StringUtils.hasText(latestJson.getVersion()) || latestJson.getPlatforms().isEmpty()) {
            ReleaseView empty = new ReleaseView();
            empty.setChannel(channel);
            empty.setLatestJsonUrl(buildLatestJsonUrl(channel));
            empty.setPolicyUrl(buildPolicyUrl(channel));
            ReleasePolicyView policy = readPolicy(channel);
            empty.setForceUpdate(Boolean.TRUE.equals(policy.getForceUpdate()));
            empty.setMinSupportedVersion(policy.getMinSupportedVersion());
            empty.setUpdatedAt(policy.getUpdatedAt());
            return empty;
        }

        List<ReleasePlatformView> platforms = buildPlatformViews(channel, latestJson);
        ReleasePlatformView firstPlatform = platforms.isEmpty() ? new ReleasePlatformView() : platforms.get(0);
        ReleaseView view = toReleaseView(
            channel,
            latestJson.getVersion(),
            firstPlatform.getTarget(),
            firstPlatform.getFileName(),
            firstPlatform.getFileSize(),
            firstPlatform.getDownloadUrl(),
            latestJson.getPubDate(),
            latestJson.getNotes(),
            readPolicy(channel)
        );
        view.setPlatforms(platforms);
        return view;
    }

    private List<ReleasePlatformView> buildPlatformViews(String channel, TauriLatestJson latestJson) {
        if (latestJson == null || latestJson.getPlatforms() == null || latestJson.getPlatforms().isEmpty()) {
            return Collections.emptyList();
        }
        List<ReleasePlatformView> platforms = new ArrayList<ReleasePlatformView>();
        for (String target : latestJson.getPlatforms().keySet()) {
            TauriLatestJson.PlatformInfo platformInfo = latestJson.getPlatforms().get(target);
            String fileName = platformInfo == null ? "" : extractFileName(platformInfo.getUrl());
            ReleasePlatformView platform = new ReleasePlatformView();
            platform.setTarget(target);
            platform.setFileName(fileName);
            platform.setFileSize(resolveFileSize(channel, target, fileName));
            platform.setDownloadUrl(platformInfo == null ? "" : platformInfo.getUrl());
            platforms.add(platform);
        }
        return platforms;
    }

    private Long resolveFileSize(String channel, String target, String fileName) {
        if (!StringUtils.hasText(fileName)) {
            return null;
        }
        try {
            Path filePath = storageRoot.resolve(channel).resolve(target).resolve(fileName).normalize();
            ensureInsideStorage(filePath);
            if (Files.isRegularFile(filePath)) {
                return Files.size(filePath);
            }
        } catch (IOException ex) {
            log.debug("release file size check failed. channel={}, target={}, fileName={}", channel, target, fileName);
        }
        return null;
    }

    private List<ReleaseHistoryView> readHistoryViews(String channel) {
        List<ReleaseHistoryView> views = new ArrayList<ReleaseHistoryView>();
        TauriLatestJson activeLatestJson = readLatestJson(channel);
        String activeVersion = activeLatestJson.getVersion();
        Set<String> versions = new HashSet<String>();
        Path root = historyRoot(channel);

        if (Files.isDirectory(root)) {
            try (Stream<Path> stream = Files.list(root)) {
                stream
                    .filter(Files::isDirectory)
                    .forEach(path -> {
                        String version = path.getFileName().toString();
                        TauriLatestJson latestJson = readHistoryLatestJson(channel, version);
                        ReleasePolicyView policy = readHistoryPolicy(channel, version, latestJson);
                        String historyVersion = latestJson.getVersion();
                        if (StringUtils.hasText(historyVersion)) {
                            versions.add(historyVersion);
                        }
                        views.add(toHistoryView(channel, latestJson, policy, version.equals(activeVersion)));
                    });
            } catch (IOException ex) {
                throw new BusinessException("RELEASE-IO", "读取历史版本失败: " + ex.getMessage());
            }
        }

        if (StringUtils.hasText(activeVersion) && !versions.contains(activeVersion)) {
            views.add(toHistoryView(channel, activeLatestJson, readPolicy(channel), true));
        }
        views.sort(historyUpdatedAtDesc());
        return views;
    }

    private ReleaseHistoryView toHistoryView(String channel,
                                             TauriLatestJson latestJson,
                                             ReleasePolicyView policy,
                                             boolean active) {
        ReleaseHistoryView view = new ReleaseHistoryView();
        view.setChannel(channel);
        view.setVersion(latestJson.getVersion());
        view.setActive(active);
        view.setForceUpdate(Boolean.TRUE.equals(policy.getForceUpdate()));
        view.setMinSupportedVersion(policy.getMinSupportedVersion());
        view.setLatestJsonUrl(buildLatestJsonUrl(channel));
        view.setNotes(firstText(policy.getNotes(), latestJson.getNotes()));
        view.setPubDate(firstText(policy.getPubDate(), latestJson.getPubDate()));
        view.setUpdatedAt(policy.getUpdatedAt());

        if (latestJson.getPlatforms() != null) {
            for (String target : latestJson.getPlatforms().keySet()) {
                view.getTargets().add(target);
                TauriLatestJson.PlatformInfo platformInfo = latestJson.getPlatforms().get(target);
                String fileName = platformInfo == null ? "" : extractFileName(platformInfo.getUrl());
                if (StringUtils.hasText(fileName)) {
                    view.getFileNames().add(fileName);
                }
            }
        }
        return view;
    }

    private Comparator<ReleaseHistoryView> historyUpdatedAtDesc() {
        return (left, right) -> {
            long leftValue = left.getUpdatedAt() == null ? 0L : left.getUpdatedAt();
            long rightValue = right.getUpdatedAt() == null ? 0L : right.getUpdatedAt();
            int updatedCompare = Long.compare(rightValue, leftValue);
            if (updatedCompare != 0) {
                return updatedCompare;
            }
            return String.valueOf(right.getVersion()).compareTo(String.valueOf(left.getVersion()));
        };
    }

    private TauriLatestJson readLatestJson(String channel) {
        Path latestPath = latestJsonPath(channel);
        if (!Files.isRegularFile(latestPath)) {
            return new TauriLatestJson();
        }
        try {
            return objectMapper.readValue(latestPath.toFile(), TauriLatestJson.class);
        } catch (IOException ex) {
            throw new BusinessException("RELEASE-JSON", "读取 latest.json 失败: " + ex.getMessage());
        }
    }

    private void writeLatestJson(String channel, TauriLatestJson latestJson) throws IOException {
        Path channelDirectory = storageRoot.resolve(channel).normalize();
        ensureInsideStorage(channelDirectory);
        Files.createDirectories(channelDirectory);
        objectMapper.writerWithDefaultPrettyPrinter().writeValue(latestJsonPath(channel).toFile(), latestJson);
    }

    private ReleasePolicyView updateReleasePolicy(String channel,
                                                  String version,
                                                  String pubDate,
                                                  String notes,
                                                  Boolean forceUpdate) throws IOException {
        ReleasePolicyView existing = readPolicy(channel);
        boolean force = forceUpdate == null ? Boolean.TRUE.equals(existing.getForceUpdate()) : Boolean.TRUE.equals(forceUpdate);
        ReleasePolicyView policy = new ReleasePolicyView();
        policy.setChannel(channel);
        policy.setLatestVersion(version);
        policy.setForceUpdate(force);
        policy.setMinSupportedVersion(force ? version : null);
        policy.setLatestJsonUrl(buildLatestJsonUrl(channel));
        policy.setNotes(notes);
        policy.setPubDate(pubDate);
        policy.setUpdatedAt(System.currentTimeMillis());
        writePolicy(channel, policy);
        return policy;
    }

    private ReleasePolicyView readPolicy(String channel) {
        ReleasePolicyView policy = readStoredPolicy(channel);
        TauriLatestJson latestJson = readLatestJson(channel);
        if (!StringUtils.hasText(policy.getChannel())) {
            policy.setChannel(channel);
        }
        if (!StringUtils.hasText(policy.getLatestVersion())) {
            policy.setLatestVersion(latestJson.getVersion());
        }
        if (policy.getForceUpdate() == null) {
            policy.setForceUpdate(false);
        }
        if (!StringUtils.hasText(policy.getLatestJsonUrl())) {
            policy.setLatestJsonUrl(buildLatestJsonUrl(channel));
        }
        if (!StringUtils.hasText(policy.getNotes())) {
            policy.setNotes(latestJson.getNotes());
        }
        if (!StringUtils.hasText(policy.getPubDate())) {
            policy.setPubDate(latestJson.getPubDate());
        }
        return policy;
    }

    private ReleasePolicyView readStoredPolicy(String channel) {
        Path policyPath = policyPath(channel);
        if (!Files.isRegularFile(policyPath)) {
            return new ReleasePolicyView();
        }
        try {
            return objectMapper.readValue(policyPath.toFile(), ReleasePolicyView.class);
        } catch (IOException ex) {
            throw new BusinessException("RELEASE-JSON", "读取 policy.json 失败: " + ex.getMessage());
        }
    }

    private void writePolicy(String channel, ReleasePolicyView policy) throws IOException {
        Path channelDirectory = storageRoot.resolve(channel).normalize();
        ensureInsideStorage(channelDirectory);
        Files.createDirectories(channelDirectory);
        objectMapper.writerWithDefaultPrettyPrinter().writeValue(policyPath(channel).toFile(), policy);
    }

    private void writeHistorySnapshot(String channel,
                                      TauriLatestJson latestJson,
                                      ReleasePolicyView policy) throws IOException {
        if (latestJson == null || !StringUtils.hasText(latestJson.getVersion())) {
            return;
        }
        String version = requireSafeText(latestJson.getVersion(), "历史版本号不能为空", "历史版本号非法");
        ReleasePolicyView snapshotPolicy = copyPolicy(policy);
        normalizePolicyForLatest(channel, snapshotPolicy, latestJson);
        Path directory = historyVersionPath(channel, version);
        ensureInsideStorage(directory);
        Files.createDirectories(directory);
        objectMapper.writerWithDefaultPrettyPrinter().writeValue(historyLatestJsonPath(channel, version).toFile(), latestJson);
        objectMapper.writerWithDefaultPrettyPrinter().writeValue(historyPolicyPath(channel, version).toFile(), snapshotPolicy);
    }

    private TauriLatestJson readHistoryLatestJson(String channel, String version) {
        String normalizedVersion = requireSafeText(version, "历史版本号不能为空", "历史版本号非法");
        Path path = historyLatestJsonPath(channel, normalizedVersion);
        if (!Files.isRegularFile(path)) {
            throw new BusinessException("RELEASE-404", "历史版本不存在: " + normalizedVersion);
        }
        try {
            return objectMapper.readValue(path.toFile(), TauriLatestJson.class);
        } catch (IOException ex) {
            throw new BusinessException("RELEASE-JSON", "读取历史 latest.json 失败: " + ex.getMessage());
        }
    }

    private ReleasePolicyView readHistoryPolicy(String channel, String version, TauriLatestJson latestJson) {
        String normalizedVersion = requireSafeText(version, "历史版本号不能为空", "历史版本号非法");
        Path path = historyPolicyPath(channel, normalizedVersion);
        ReleasePolicyView policy = new ReleasePolicyView();
        if (Files.isRegularFile(path)) {
            try {
                policy = objectMapper.readValue(path.toFile(), ReleasePolicyView.class);
            } catch (IOException ex) {
                throw new BusinessException("RELEASE-JSON", "读取历史 policy.json 失败: " + ex.getMessage());
            }
        }
        normalizePolicyForLatest(channel, policy, latestJson);
        return policy;
    }

    private ReleasePolicyView copyPolicy(ReleasePolicyView source) {
        ReleasePolicyView target = new ReleasePolicyView();
        if (source == null) {
            return target;
        }
        target.setChannel(source.getChannel());
        target.setLatestVersion(source.getLatestVersion());
        target.setForceUpdate(source.getForceUpdate());
        target.setMinSupportedVersion(source.getMinSupportedVersion());
        target.setLatestJsonUrl(source.getLatestJsonUrl());
        target.setNotes(source.getNotes());
        target.setPubDate(source.getPubDate());
        target.setUpdatedAt(source.getUpdatedAt());
        return target;
    }

    private void normalizePolicyForLatest(String channel, ReleasePolicyView policy, TauriLatestJson latestJson) {
        policy.setChannel(channel);
        policy.setLatestVersion(latestJson.getVersion());
        policy.setLatestJsonUrl(buildLatestJsonUrl(channel));
        if (policy.getForceUpdate() == null) {
            policy.setForceUpdate(false);
        }
        if (Boolean.TRUE.equals(policy.getForceUpdate())) {
            if (!StringUtils.hasText(policy.getMinSupportedVersion())) {
                policy.setMinSupportedVersion(latestJson.getVersion());
            }
        } else {
            policy.setMinSupportedVersion(null);
        }
        if (!StringUtils.hasText(policy.getNotes())) {
            policy.setNotes(latestJson.getNotes());
        }
        if (!StringUtils.hasText(policy.getPubDate())) {
            policy.setPubDate(latestJson.getPubDate());
        }
        if (policy.getUpdatedAt() == null) {
            policy.setUpdatedAt(System.currentTimeMillis());
        }
    }

    private void validateSnapshotFiles(String channel, TauriLatestJson latestJson) {
        if (latestJson.getPlatforms() == null || latestJson.getPlatforms().isEmpty()) {
            throw new BusinessException("历史版本缺少平台安装包信息");
        }
        for (String target : latestJson.getPlatforms().keySet()) {
            String normalizedTarget = requireSafeText(target, "历史版本平台 target 不能为空", "历史版本平台 target 非法");
            TauriLatestJson.PlatformInfo platformInfo = latestJson.getPlatforms().get(target);
            String fileName = platformInfo == null ? "" : extractFileName(platformInfo.getUrl());
            if (!StringUtils.hasText(fileName)) {
                throw new BusinessException("历史版本缺少 " + target + " 的安装包文件名");
            }
            Path path = storageRoot.resolve(channel).resolve(normalizedTarget).resolve(fileName).normalize();
            ensureInsideStorage(path);
            if (!Files.isRegularFile(path)) {
                throw new BusinessException("历史版本安装包不存在: " + target + "/" + fileName);
            }
        }
    }

    private void rewritePlatformUrls(TauriLatestJson latestJson, String channel) {
        if (latestJson.getPlatforms() == null) {
            return;
        }
        for (String target : latestJson.getPlatforms().keySet()) {
            TauriLatestJson.PlatformInfo platformInfo = latestJson.getPlatforms().get(target);
            if (platformInfo == null) {
                continue;
            }
            String fileName = extractFileName(platformInfo.getUrl());
            if (StringUtils.hasText(fileName)) {
                platformInfo.setUrl(buildFileUrl(channel, target, fileName));
            }
        }
    }

    private Path latestJsonPath(String channel) {
        Path latestPath = storageRoot.resolve(channel).resolve(LATEST_FILE).normalize();
        ensureInsideStorage(latestPath);
        return latestPath;
    }

    private Path policyPath(String channel) {
        Path path = storageRoot.resolve(channel).resolve(POLICY_FILE).normalize();
        ensureInsideStorage(path);
        return path;
    }

    private Path historyRoot(String channel) {
        Path path = storageRoot.resolve(channel).resolve(HISTORY_DIR).normalize();
        ensureInsideStorage(path);
        return path;
    }

    private Path historyVersionPath(String channel, String version) {
        String normalizedVersion = requireSafeText(version, "历史版本号不能为空", "历史版本号非法");
        Path path = historyRoot(channel).resolve(normalizedVersion).normalize();
        ensureInsideStorage(path);
        return path;
    }

    private Path historyLatestJsonPath(String channel, String version) {
        Path path = historyVersionPath(channel, version).resolve(LATEST_FILE).normalize();
        ensureInsideStorage(path);
        return path;
    }

    private Path historyPolicyPath(String channel, String version) {
        Path path = historyVersionPath(channel, version).resolve(POLICY_FILE).normalize();
        ensureInsideStorage(path);
        return path;
    }

    private ReleaseView toReleaseView(String channel,
                                      String version,
                                      String target,
                                      String fileName,
                                      Long fileSize,
                                      String downloadUrl,
                                      String pubDate,
                                      String notes,
                                      ReleasePolicyView policy) {
        ReleaseView view = new ReleaseView();
        view.setChannel(channel);
        view.setVersion(version);
        view.setTarget(target);
        view.setFileName(fileName);
        view.setFileSize(fileSize);
        view.setDownloadUrl(downloadUrl);
        view.setLatestJsonUrl(buildLatestJsonUrl(channel));
        view.setPolicyUrl(buildPolicyUrl(channel));
        view.setPubDate(pubDate);
        view.setNotes(notes);
        view.setForceUpdate(Boolean.TRUE.equals(policy.getForceUpdate()));
        view.setMinSupportedVersion(policy.getMinSupportedVersion());
        view.setUpdatedAt(policy.getUpdatedAt() == null ? System.currentTimeMillis() : policy.getUpdatedAt());
        if (StringUtils.hasText(target)) {
            ReleasePlatformView platform = new ReleasePlatformView();
            platform.setTarget(target);
            platform.setFileName(fileName);
            platform.setFileSize(fileSize);
            platform.setDownloadUrl(downloadUrl);
            view.getPlatforms().add(platform);
        }
        return view;
    }

    private String normalizeChannel(String channel) {
        String value = requireText(channel, "发布通道不能为空").trim();
        if (!CHANNELS.contains(value)) {
            throw new BusinessException(
                "发布通道仅支持 production、testing、win7-production 或 win7-testing"
            );
        }
        return value;
    }

    private String requireText(String value, String message) {
        if (!StringUtils.hasText(value)) {
            throw new BusinessException(message);
        }
        return value.trim();
    }

    private String requireSafeText(String value, String emptyMessage, String invalidMessage) {
        String text = requireText(value, emptyMessage);
        if (!SAFE_SEGMENT.matcher(text).matches()) {
            throw new BusinessException(invalidMessage);
        }
        return text;
    }

    private String safeFileName(String value) {
        if (!StringUtils.hasText(value)) {
            throw new BusinessException("文件名不能为空");
        }
        try {
            String fileName = Paths.get(value.trim()).getFileName().toString();
            if (!SAFE_SEGMENT.matcher(fileName).matches()) {
                throw new BusinessException("文件名只能包含字母、数字、点、下划线和短横线");
            }
            return fileName;
        } catch (InvalidPathException ex) {
            throw new BusinessException("文件名非法");
        }
    }

    private String normalizePubDate(String pubDate) {
        if (!StringUtils.hasText(pubDate)) {
            return Instant.now().toString();
        }
        try {
            return Instant.parse(pubDate.trim()).toString();
        } catch (DateTimeParseException ex) {
            throw new BusinessException("发布时间必须是 ISO-8601 格式，例如 2026-04-24T10:00:00Z");
        }
    }

    private String trimToNull(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        return value.trim();
    }

    private String firstText(String preferred, String fallback) {
        return StringUtils.hasText(preferred) ? preferred.trim() : trimToNull(fallback);
    }

    private void ensureInsideStorage(Path path) {
        if (!path.normalize().startsWith(storageRoot)) {
            throw new BusinessException("文件路径非法");
        }
    }

    private String buildLatestJsonUrl(String channel) {
        return externalBaseUrlBuilder()
            .path("/v1/client/releases/")
            .path(channel)
            .path("/latest.json")
            .toUriString();
    }

    private String buildPolicyUrl(String channel) {
        return externalBaseUrlBuilder()
            .path("/v1/client/releases/")
            .path(channel)
            .path("/policy.json")
            .toUriString();
    }

    private String buildFileUrl(String channel, String target, String fileName) {
        return externalBaseUrlBuilder()
            .path("/v1/client/releases/")
            .path(channel)
            .path("/files/")
            .path(target)
            .path("/")
            .path(fileName)
            .toUriString();
    }

    private String normalizePolicyChannel(String channel) {
        if (!StringUtils.hasText(channel)) {
            return "production";
        }
        String value = channel.trim();
        return CHANNELS.contains(value) ? value : "production";
    }

    private int compareVersions(String currentVersion, String requiredVersion) {
        String current = normalizeVersionText(currentVersion);
        String required = normalizeVersionText(requiredVersion);
        if (!StringUtils.hasText(current) && !StringUtils.hasText(required)) {
            return 0;
        }
        if (!StringUtils.hasText(current)) {
            return -1;
        }
        if (!StringUtils.hasText(required)) {
            return 1;
        }

        String[] currentParts = current.split("[._-]");
        String[] requiredParts = required.split("[._-]");
        int length = Math.max(currentParts.length, requiredParts.length);
        for (int i = 0; i < length; i += 1) {
            String left = i < currentParts.length ? currentParts[i] : "0";
            String right = i < requiredParts.length ? requiredParts[i] : "0";
            int result = compareVersionPart(left, right);
            if (result != 0) {
                return result;
            }
        }
        return 0;
    }

    private void validateWin7ReleaseVersion(String channel,
                                            String candidateVersion,
                                            TauriLatestJson currentLatestJson) {
        if (!channel.startsWith("win7-")) {
            return;
        }

        String currentVersion = currentLatestJson == null ? null : trimToNull(currentLatestJson.getVersion());
        if (candidateVersion.equals(currentVersion)) {
            return;
        }

        String highestVersion = currentVersion;
        for (ReleaseHistoryView history : readHistoryViews(channel)) {
            String historyVersion = trimToNull(history.getVersion());
            if (historyVersion != null
                && (highestVersion == null || compareVersions(historyVersion, highestVersion) > 0)) {
                highestVersion = historyVersion;
            }
        }
        if (highestVersion != null && compareVersions(candidateVersion, highestVersion) <= 0) {
            throw new BusinessException(
                "RELEASE-VERSION",
                "Win7 通道新版本必须高于历史最高版本 " + highestVersion
                    + "；当前提交 " + candidateVersion + "。降级或恢复旧版本请使用回滚功能。"
            );
        }
    }

    private String normalizeVersionText(String value) {
        if (!StringUtils.hasText(value)) {
            return "";
        }
        String text = value.trim();
        if (text.length() > 1 && (text.charAt(0) == 'v' || text.charAt(0) == 'V')) {
            return text.substring(1);
        }
        return text;
    }

    private int compareVersionPart(String left, String right) {
        boolean leftNumeric = left.matches("\\d+");
        boolean rightNumeric = right.matches("\\d+");
        if (leftNumeric && rightNumeric) {
            return new BigInteger(left).compareTo(new BigInteger(right));
        }
        if (leftNumeric != rightNumeric) {
            return leftNumeric ? 1 : -1;
        }
        return left.compareToIgnoreCase(right);
    }

    public String normalizeExternalBaseUrl(String requestBaseUrl) {
        if (StringUtils.hasText(publicBaseUrl)) {
            return publicBaseUrl;
        }
        String baseUrl = trimTrailingSlash(requestBaseUrl);
        if (!StringUtils.hasText(baseUrl)) {
            return "";
        }
        try {
            java.net.URI uri = java.net.URI.create(baseUrl);
            String host = uri.getHost();
            if (!isLoopbackHost(host)) {
                return baseUrl;
            }
            String lanIp = findLanIp();
            if (!StringUtils.hasText(lanIp)) {
                return baseUrl;
            }
            int port = uri.getPort();
            String portPart = port > 0 ? ":" + port : "";
            return uri.getScheme() + "://" + lanIp + portPart;
        } catch (RuntimeException ex) {
            return baseUrl;
        }
    }

    private UriComponentsBuilder externalBaseUrlBuilder() {
        if (StringUtils.hasText(publicBaseUrl)) {
            return ServletUriComponentsBuilder.fromHttpUrl(publicBaseUrl);
        }
        String currentContext = ServletUriComponentsBuilder.fromCurrentContextPath().toUriString();
        return ServletUriComponentsBuilder.fromHttpUrl(normalizeExternalBaseUrl(currentContext));
    }

    private String trimTrailingSlash(String value) {
        if (!StringUtils.hasText(value)) {
            return "";
        }
        return value.trim().replaceAll("/+$", "");
    }

    private boolean isLoopbackHost(String host) {
        return "localhost".equalsIgnoreCase(host)
            || "127.0.0.1".equals(host)
            || "::1".equals(host)
            || "0:0:0:0:0:0:0:1".equals(host);
    }

    private String findLanIp() {
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces.hasMoreElements()) {
                NetworkInterface networkInterface = interfaces.nextElement();
                if (!networkInterface.isUp() || networkInterface.isLoopback() || networkInterface.isVirtual()) {
                    continue;
                }
                Enumeration<InetAddress> addresses = networkInterface.getInetAddresses();
                while (addresses.hasMoreElements()) {
                    InetAddress address = addresses.nextElement();
                    if (address instanceof Inet4Address && !address.isLoopbackAddress()) {
                        String hostAddress = address.getHostAddress();
                        if (isUsableLanIp(hostAddress)) {
                            return hostAddress;
                        }
                    }
                }
            }
        } catch (Exception ex) {
            log.debug("lan ip detection failed. error={}", ex.getMessage());
            return "";
        }
        return "";
    }

    private boolean isUsableLanIp(String hostAddress) {
        return StringUtils.hasText(hostAddress)
            && !hostAddress.startsWith("169.254.")
            && !hostAddress.startsWith("198.18.")
            && !hostAddress.startsWith("198.19.")
            && !hostAddress.startsWith("100.64.")
            && !hostAddress.startsWith("100.65.")
            && !hostAddress.startsWith("100.66.")
            && !hostAddress.startsWith("100.67.")
            && !hostAddress.startsWith("100.68.")
            && !hostAddress.startsWith("100.69.")
            && !hostAddress.startsWith("100.70.")
            && !hostAddress.startsWith("100.71.")
            && !hostAddress.startsWith("100.72.")
            && !hostAddress.startsWith("100.73.")
            && !hostAddress.startsWith("100.74.")
            && !hostAddress.startsWith("100.75.")
            && !hostAddress.startsWith("100.76.")
            && !hostAddress.startsWith("100.77.")
            && !hostAddress.startsWith("100.78.")
            && !hostAddress.startsWith("100.79.")
            && !hostAddress.startsWith("100.80.")
            && !hostAddress.startsWith("100.81.")
            && !hostAddress.startsWith("100.82.")
            && !hostAddress.startsWith("100.83.")
            && !hostAddress.startsWith("100.84.")
            && !hostAddress.startsWith("100.85.")
            && !hostAddress.startsWith("100.86.")
            && !hostAddress.startsWith("100.87.")
            && !hostAddress.startsWith("100.88.")
            && !hostAddress.startsWith("100.89.")
            && !hostAddress.startsWith("100.90.")
            && !hostAddress.startsWith("100.91.")
            && !hostAddress.startsWith("100.92.")
            && !hostAddress.startsWith("100.93.")
            && !hostAddress.startsWith("100.94.")
            && !hostAddress.startsWith("100.95.")
            && !hostAddress.startsWith("100.96.")
            && !hostAddress.startsWith("100.97.")
            && !hostAddress.startsWith("100.98.")
            && !hostAddress.startsWith("100.99.")
            && !hostAddress.startsWith("100.100.")
            && !hostAddress.startsWith("100.101.")
            && !hostAddress.startsWith("100.102.")
            && !hostAddress.startsWith("100.103.")
            && !hostAddress.startsWith("100.104.")
            && !hostAddress.startsWith("100.105.")
            && !hostAddress.startsWith("100.106.")
            && !hostAddress.startsWith("100.107.")
            && !hostAddress.startsWith("100.108.")
            && !hostAddress.startsWith("100.109.")
            && !hostAddress.startsWith("100.110.")
            && !hostAddress.startsWith("100.111.")
            && !hostAddress.startsWith("100.112.")
            && !hostAddress.startsWith("100.113.")
            && !hostAddress.startsWith("100.114.")
            && !hostAddress.startsWith("100.115.")
            && !hostAddress.startsWith("100.116.")
            && !hostAddress.startsWith("100.117.")
            && !hostAddress.startsWith("100.118.")
            && !hostAddress.startsWith("100.119.")
            && !hostAddress.startsWith("100.120.")
            && !hostAddress.startsWith("100.121.")
            && !hostAddress.startsWith("100.122.")
            && !hostAddress.startsWith("100.123.")
            && !hostAddress.startsWith("100.124.")
            && !hostAddress.startsWith("100.125.")
            && !hostAddress.startsWith("100.126.")
            && !hostAddress.startsWith("100.127.");
    }

    private String extractFileName(String url) {
        if (!StringUtils.hasText(url)) {
            return null;
        }
        int index = url.lastIndexOf('/');
        return index >= 0 && index < url.length() - 1 ? url.substring(index + 1) : url;
    }

    private static class ReleaseMetadata {
        private String version;
        private String target;
        private String signature;
        private String notes;
        private String pubDate;
    }

    private static class ReleasePackage {
        private MultipartFile file;
        private String fileName;
        private List<ReleaseMetadata> metadataList;
    }

    private static class PlatformMatch {
        private final String target;
        private final TauriLatestJson.PlatformInfo platformInfo;

        private PlatformMatch(String target, TauriLatestJson.PlatformInfo platformInfo) {
            this.target = target;
            this.platformInfo = platformInfo;
        }
    }
}
