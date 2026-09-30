package qingcai.douyinvideo.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestTemplate;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.URI;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@RestController
@RequestMapping("/api/douyin")
@CrossOrigin
public class DouyinController {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final RestTemplate restTemplate = createRestTemplate();
    @Value("${parser.base-url:http://127.0.0.1:8000}")
    private String parserBaseUrl;

    @GetMapping("/parse")
    public ResponseEntity<?> parse(@RequestParam String shareText) {
        try {
            String pureUrl = extractPureUrl(shareText);
            if (pureUrl == null) {
                return ResponseEntity.badRequest().body(Map.of("code", 400, "msg", "未检测到网址"));
            }

            String apiUrl = parserBaseUrl + "/api/hybrid/video_data?url={url}";

            String responseStr = restTemplate.getForObject(apiUrl, String.class, pureUrl);
            JsonNode root = objectMapper.readTree(responseStr);

            if (root.path("code").asInt() != 200) {
                return ResponseEntity.status(500).body(Map.of("code", 500, "msg", "解析引擎内部错误"));
            }

            JsonNode data = root.path("data");
            String title = data.path("desc").asText();
            String cover = "";
            String videoUrl = "";
            List<String> imagesList = new ArrayList<>();

            // 智能判断图文还是视频 (适配 V4 结构)
            if (data.has("images") && data.path("images").isArray() && !data.path("images").isEmpty()) {
                System.out.println("-> 解析为图文作品");
                for (JsonNode imgNode : data.path("images")) {
                    imagesList.add(imgNode.path("url_list").get(0).asText());
                }
                cover = imagesList.get(0);

                if (data.path("images").get(0).has("video")) {
                    JsonNode playAddr = data.path("images").get(0).path("video").path("play_addr").path("url_list");
                    if (playAddr.isArray() && !playAddr.isEmpty()) {
                        videoUrl = playAddr.get(0).asText();
                    }
                }
            } else if (data.has("video")) {
                System.out.println("-> 解析为视频作品");
                JsonNode videoNode = data.path("video");
                if (videoNode.has("cover")) {
                    cover = videoNode.path("cover").path("url_list").get(0).asText();
                }
                if (videoNode.has("play_addr")) {
                    videoUrl = videoNode.path("play_addr").path("url_list").get(0).asText();
                }
            }

            if (videoUrl != null && !videoUrl.isEmpty()) {
                videoUrl = videoUrl.replace("http://", "https://");
            }

            Map<String, Object> result = new HashMap<>();
            result.put("code", 200);
            result.put("title", title);
            result.put("cover", cover);
            result.put("video_url", videoUrl);
            result.put("images", imagesList);

            return ResponseEntity.ok(result);

        } catch (Exception e) {
            e.printStackTrace();
            return ResponseEntity.status(500).body(Map.of("code", 500, "msg", "解析失败，请稍后重试"));
        }
    }

    /**
     * 通过本站安全代理下载远端媒体，并强制浏览器按附件保存。
     *
     * @param url 解析服务返回的公网媒体地址
     * @param filename 用户下载时看到的文件名
     * @param response 当前下载响应；成功时会流式写入视频内容
     */
    @GetMapping("/download")
    public void downloadFile(@RequestParam String url, @RequestParam String filename, HttpServletResponse response) {
        try {
            URL targetUrl = validatePublicHttpUrl(url);
            for (int redirectCount = 0; redirectCount <= 5; redirectCount++) {
                HttpURLConnection connection = openDownloadConnection(targetUrl);
                int status = connection.getResponseCode();

                if (status >= 300 && status < 400) {
                    String location = connection.getHeaderField("Location");
                    connection.disconnect();
                    if (location == null || redirectCount == 5) {
                        response.setStatus(HttpServletResponse.SC_BAD_GATEWAY);
                        return;
                    }
                    // 安全规则：每次跳转都重新校验目标，防止公网 URL 二次跳入服务器内网。
                    targetUrl = validatePublicHttpUrl(
                            targetUrl.toURI().resolve(location).toString()
                    );
                    continue;
                }

                if (status < 200 || status >= 300) {
                    connection.disconnect();
                    response.setStatus(HttpServletResponse.SC_BAD_GATEWAY);
                    return;
                }

                streamDownload(connection, filename, response);
                return;
            }
        } catch (IllegalArgumentException e) {
            response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
        } catch (Exception e) {
            response.setStatus(HttpServletResponse.SC_BAD_GATEWAY);
            System.err.println("代理下载失败: " + e.getMessage());
        }
    }

    /**
     * 创建禁用自动重定向的下载连接，确保每个 Location 都经过内网地址校验。
     */
    private HttpURLConnection openDownloadConnection(URL targetUrl) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) targetUrl.openConnection();
        connection.setRequestMethod("GET");
        connection.setInstanceFollowRedirects(false);
        connection.setConnectTimeout(10_000);
        connection.setReadTimeout(120_000);
        connection.setRequestProperty(
                "User-Agent",
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"
        );
        connection.setRequestProperty("Referer", "https://www.douyin.com/");
        return connection;
    }

    /**
     * 流式转发远端文件，不在服务器磁盘落盘，也不把完整视频载入 JVM 内存。
     */
    private void streamDownload(
            HttpURLConnection connection,
            String filename,
            HttpServletResponse response
    ) throws Exception {
        // 兼容性说明：video/mp4 容易被手机浏览器直接播放，二进制类型配合附件头强制进入下载流程。
        response.setContentType("application/octet-stream");
        response.setHeader("X-Content-Type-Options", "nosniff");
        if (connection.getContentLengthLong() >= 0) {
            response.setContentLengthLong(connection.getContentLengthLong());
        }

        String safeFilename = filename == null ? "download.mp4" : filename.replaceAll("[\\r\\n\"]", "_");
        String encodedFilename = URLEncoder.encode(safeFilename, StandardCharsets.UTF_8)
                .replace("+", "%20");
        response.setHeader(
                "Content-Disposition",
                "attachment; filename*=UTF-8''" + encodedFilename
        );

        try (InputStream input = connection.getInputStream();
             OutputStream output = response.getOutputStream()) {
            byte[] buffer = new byte[8192];
            int bytesRead;
            while ((bytesRead = input.read(buffer)) != -1) {
                output.write(buffer, 0, bytesRead);
            }
        } finally {
            connection.disconnect();
        }
    }

    /**
     * 只允许指向公网的 HTTP(S) 地址，阻断 localhost、局域网和云元数据等 SSRF 目标。
     */
    private URL validatePublicHttpUrl(String rawUrl) {
        try {
            if (rawUrl == null || rawUrl.trim().isEmpty()) {
                throw new IllegalArgumentException("URL 不能为空");
            }

            URI uri = URI.create(rawUrl.trim());
            String scheme = uri.getScheme();
            if (!("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))
                    || uri.getHost() == null
                    || uri.getUserInfo() != null) {
                throw new IllegalArgumentException("只允许公网 HTTP(S) 地址");
            }

            for (InetAddress address : InetAddress.getAllByName(uri.getHost())) {
                byte[] bytes = address.getAddress();
                boolean uniqueLocalIpv6 = bytes.length == 16 && (bytes[0] & 0xFE) == 0xFC;
                if (address.isAnyLocalAddress()
                        || address.isLoopbackAddress()
                        || address.isLinkLocalAddress()
                        || address.isSiteLocalAddress()
                        || address.isMulticastAddress()
                        || uniqueLocalIpv6) {
                    throw new IllegalArgumentException("禁止访问本机或内网地址");
                }
            }
            return uri.toURL();
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("无效的下载地址", e);
        }
    }

    /**
     * 本地解析引擎异常时尽快释放 Web 请求线程，避免单个坏链接长期拖住整个应用。
     */
    private static RestTemplate createRestTemplate() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(5));
        factory.setReadTimeout(Duration.ofSeconds(60));
        return new RestTemplate(factory);
    }

    private String extractPureUrl(String text) {
        if (text == null) return null;
        Pattern pattern = Pattern.compile("https?://[-A-Za-z0-9+&@#/%?=~_|!:,.;]+[-A-Za-z0-9+&@#/%=~_|]");
        Matcher matcher = pattern.matcher(text);
        if (matcher.find()) return matcher.group();
        return null;
    }
}
