package com.getjobs.application.service;

import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 管理 Boss 图片简历，支持按顺序保存多张图片。文件保存在程序启动目录之外，不写入 classpath 或 JAR。
 * <p>
 * 多图按 resume-1.png、resume-2.jpg … 命名，序号即发送顺序；旧版单图 resume.png 在没有多图时作为第 1 张使用。
 */
@Service
public class BossResumeImageService {

    public static final long MAX_FILE_SIZE = 10L * 1024 * 1024;
    public static final int MAX_IMAGE_COUNT = 5;
    public static final String PREVIEW_URL = "/api/boss/config/resumeImages/content";

    private static final List<String> SUPPORTED_EXTENSIONS = List.of("png", "jpg", "gif");
    private static final Pattern NUMBERED_FILE = Pattern.compile("^resume-(\\d+)\\.(png|jpg|gif)$");
    private final Path storageDirectory = Paths.get(System.getProperty("user.dir"), "data", "boss", "resume")
            .toAbsolutePath()
            .normalize();

    /**
     * 上传单张图片，替换当前全部图片简历（兼容旧接口）。
     *
     * @param file MultipartFile，必填，无默认值；上传的图片文件
     * @return ResumeImageInfo，保存后的第 1 张图片信息，不为 null
     * @throws IOException 文件读写失败
     */
    public synchronized ResumeImageInfo save(MultipartFile file) throws IOException {
        return saveAll(file == null ? List.of() : List.of(file)).get(0);
    }

    /**
     * 按上传顺序保存多张图片，整体替换当前全部图片简历；任一张校验失败则全部不生效。
     *
     * @param files List&lt;MultipartFile&gt;，必填，无默认值；1~MAX_IMAGE_COUNT 张图片，列表顺序即发送顺序
     * @return List&lt;ResumeImageInfo&gt;，保存后的图片信息列表，按发送顺序排列，不为空
     * @throws IOException 文件读写失败
     */
    public synchronized List<ResumeImageInfo> saveAll(List<MultipartFile> files) throws IOException {
        List<MultipartFile> validFiles = files == null ? List.of()
                : files.stream().filter(f -> f != null && !f.isEmpty()).toList();
        if (validFiles.isEmpty()) {
            throw new IllegalArgumentException("请选择要上传的图片简历");
        }
        if (validFiles.size() > MAX_IMAGE_COUNT) {
            throw new IllegalArgumentException("图片简历最多 " + MAX_IMAGE_COUNT + " 张");
        }

        Files.createDirectories(storageDirectory);
        List<Path> temporaryFiles = new ArrayList<>();
        List<String> extensions = new ArrayList<>();
        try {
            // 先全部落到临时文件并校验，确保不会出现只替换了一部分的情况。
            for (int i = 0; i < validFiles.size(); i++) {
                MultipartFile file = validFiles.get(i);
                String label = "第 " + (i + 1) + " 张图片";
                if (file.getSize() > MAX_FILE_SIZE) {
                    throw new IllegalArgumentException(label + "大小不能超过 10MB");
                }
                Path temporaryFile = Files.createTempFile(storageDirectory, "resume-upload-", ".tmp");
                temporaryFiles.add(temporaryFile);
                file.transferTo(temporaryFile);
                if (Files.size(temporaryFile) > MAX_FILE_SIZE) {
                    throw new IllegalArgumentException(label + "大小不能超过 10MB");
                }
                try {
                    extensions.add(detectImageExtension(temporaryFile));
                } catch (IllegalArgumentException e) {
                    throw new IllegalArgumentException(label + "：" + e.getMessage());
                }
            }

            deleteAllResumeImages();
            for (int i = 0; i < temporaryFiles.size(); i++) {
                Path target = storageDirectory.resolve("resume-" + (i + 1) + "." + extensions.get(i));
                moveReplacing(temporaryFiles.get(i), target);
            }
            return getCurrentImages();
        } finally {
            for (Path temporaryFile : temporaryFiles) {
                Files.deleteIfExists(temporaryFile);
            }
        }
    }

    /**
     * 获取第 1 张图片简历（兼容旧接口）。
     *
     * @return Optional&lt;ResumeImageInfo&gt;，没有图片时为 Optional.empty()
     */
    public synchronized Optional<ResumeImageInfo> getCurrentImage() {
        List<ResumeImageInfo> images = getCurrentImages();
        return images.isEmpty() ? Optional.empty() : Optional.of(images.get(0));
    }

    /**
     * 按序号获取图片简历。
     *
     * @param index int，必填，无默认值；从 1 开始的序号
     * @return Optional&lt;ResumeImageInfo&gt;，序号越界时为 Optional.empty()
     */
    public synchronized Optional<ResumeImageInfo> getImage(int index) {
        List<ResumeImageInfo> images = getCurrentImages();
        if (index < 1 || index > images.size()) {
            return Optional.empty();
        }
        return Optional.of(images.get(index - 1));
    }

    /**
     * 获取全部图片简历。
     *
     * @return List&lt;ResumeImageInfo&gt;，按发送顺序排列，没有图片时为空列表
     */
    public synchronized List<ResumeImageInfo> getCurrentImages() {
        List<ResumeImageInfo> result = new ArrayList<>();
        for (Path path : findCurrentImagePaths()) {
            try {
                result.add(toInfo(path));
            } catch (IOException ignore) {
                // 文件在读取过程中被替换时跳过，下次读取会恢复正常。
            }
        }
        return result;
    }

    /**
     * 获取第 1 张图片简历的路径（兼容旧调用方）。
     *
     * @return Optional&lt;Path&gt;，没有图片时为 Optional.empty()
     */
    public synchronized Optional<Path> getCurrentImagePath() {
        List<Path> paths = findCurrentImagePaths();
        return paths.isEmpty() ? Optional.empty() : Optional.of(paths.get(0));
    }

    /**
     * 获取全部图片简历的路径，供投递流程按顺序发送。
     *
     * @return List&lt;Path&gt;，按发送顺序排列，没有图片时为空列表
     */
    public synchronized List<Path> getCurrentImagePaths() {
        return findCurrentImagePaths();
    }

    /**
     * 生成管理页面使用的图片简历元数据。
     *
     * @return Map&lt;String, Object&gt;；exists(boolean，默认 false)、count(int，默认 0)、
     *         images(List，每项含 index/fileName/contentType/size/updatedAt/previewUrl)；
     *         另保留第 1 张图片的 fileName/contentType/size/updatedAt/previewUrl 字段兼容旧页面
     */
    public Map<String, Object> getMetadata() {
        List<ResumeImageInfo> images = getCurrentImages();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("exists", !images.isEmpty());
        result.put("count", images.size());
        List<Map<String, Object>> items = new ArrayList<>();
        for (int i = 0; i < images.size(); i++) {
            ResumeImageInfo info = images.get(i);
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("index", i + 1);
            item.put("fileName", info.fileName());
            item.put("contentType", info.contentType());
            item.put("size", info.size());
            item.put("updatedAt", info.updatedAt());
            item.put("previewUrl", PREVIEW_URL + "?index=" + (i + 1));
            items.add(item);
        }
        result.put("images", items);
        if (!items.isEmpty()) {
            Map<String, Object> first = items.get(0);
            result.put("fileName", first.get("fileName"));
            result.put("contentType", first.get("contentType"));
            result.put("size", first.get("size"));
            result.put("updatedAt", first.get("updatedAt"));
            result.put("previewUrl", first.get("previewUrl"));
        }
        return result;
    }

    /**
     * 查找存储目录中的图片简历文件。
     *
     * @return List&lt;Path&gt;，有多图时按序号升序返回多图；否则返回旧版单图 resume.xxx；都没有时为空列表
     */
    private List<Path> findCurrentImagePaths() {
        List<Path> numbered = new ArrayList<>();
        if (Files.isDirectory(storageDirectory)) {
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(storageDirectory, "resume-*.*")) {
                for (Path candidate : stream) {
                    if (NUMBERED_FILE.matcher(candidate.getFileName().toString()).matches()
                            && Files.isRegularFile(candidate) && Files.isReadable(candidate)) {
                        numbered.add(candidate);
                    }
                }
            } catch (IOException ignore) {
                // 目录读取失败时按没有多图处理，继续尝试旧版单图。
            }
        }
        if (!numbered.isEmpty()) {
            numbered.sort(Comparator.comparingInt(BossResumeImageService::sequenceOf));
            return numbered;
        }

        for (String extension : SUPPORTED_EXTENSIONS) {
            Path candidate = storageDirectory.resolve("resume." + extension);
            if (Files.isRegularFile(candidate) && Files.isReadable(candidate)) {
                return List.of(candidate);
            }
        }
        return List.of();
    }

    /**
     * 从多图文件名中解析序号。
     *
     * @param path Path，必填，无默认值；形如 resume-2.png 的文件路径
     * @return int，文件序号；无法解析时返回 Integer.MAX_VALUE 排到最后
     */
    private static int sequenceOf(Path path) {
        Matcher matcher = NUMBERED_FILE.matcher(path.getFileName().toString());
        if (matcher.matches()) {
            try {
                return Integer.parseInt(matcher.group(1));
            } catch (NumberFormatException ignore) {
                // 序号超出 int 范围时排到最后。
            }
        }
        return Integer.MAX_VALUE;
    }

    /**
     * 解析图片头，确认真实格式。
     *
     * @param image Path，必填，无默认值；待检测的图片文件
     * @return String，规范化后的扩展名：png / jpg / gif
     * @throws IOException 文件读取失败
     */
    private String detectImageExtension(Path image) throws IOException {
        try (ImageInputStream input = ImageIO.createImageInputStream(image.toFile())) {
            if (input == null) {
                throw new IllegalArgumentException("无法读取图片文件");
            }
            Iterator<ImageReader> readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) {
                throw new IllegalArgumentException("文件不是有效的 PNG、JPG 或 GIF 图片");
            }

            ImageReader reader = readers.next();
            try {
                reader.setInput(input, true, true);
                // 读取尺寸会让 ImageIO 实际解析图片头，避免只相信文件名或 Content-Type。
                int width = reader.getWidth(0);
                int height = reader.getHeight(0);
                if (width <= 0 || height <= 0) {
                    throw new IllegalArgumentException("图片尺寸无效");
                }
                String format = reader.getFormatName().toLowerCase(Locale.ROOT);
                return switch (format) {
                    case "png" -> "png";
                    case "jpeg", "jpg" -> "jpg";
                    case "gif" -> "gif";
                    default -> throw new IllegalArgumentException("仅支持 PNG、JPG、JPEG 和 GIF 图片");
                };
            } finally {
                reader.dispose();
            }
        }
    }

    /**
     * 移动文件并覆盖目标，优先使用原子移动。
     *
     * @param source Path，必填，无默认值；源文件
     * @param target Path，必填，无默认值；目标文件
     * @throws IOException 移动失败
     */
    private void moveReplacing(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /**
     * 删除存储目录中的全部图片简历（多图与旧版单图）。
     *
     * @throws IOException 删除失败
     */
    private void deleteAllResumeImages() throws IOException {
        for (String extension : SUPPORTED_EXTENSIONS) {
            Files.deleteIfExists(storageDirectory.resolve("resume." + extension));
        }
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(storageDirectory, "resume-*.*")) {
            for (Path candidate : stream) {
                if (NUMBERED_FILE.matcher(candidate.getFileName().toString()).matches()) {
                    Files.deleteIfExists(candidate);
                }
            }
        }
    }

    /**
     * 读取图片文件信息。
     *
     * @param path Path，必填，无默认值；图片文件
     * @return ResumeImageInfo，图片信息
     * @throws IOException 读取文件属性失败
     */
    private ResumeImageInfo toInfo(Path path) throws IOException {
        String fileName = path.getFileName().toString();
        String extension = fileName.substring(fileName.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT);
        String contentType = switch (extension) {
            case "png" -> "image/png";
            case "gif" -> "image/gif";
            default -> "image/jpeg";
        };
        return new ResumeImageInfo(
                path,
                fileName,
                contentType,
                Files.size(path),
                Files.getLastModifiedTime(path).toMillis()
        );
    }

    public record ResumeImageInfo(Path path, String fileName, String contentType, long size, long updatedAt) {
    }
}
