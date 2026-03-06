package qingcai.douyinvideo.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestTemplate;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
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
    private final RestTemplate restTemplate = new RestTemplate();

    @GetMapping("/parse")
    public ResponseEntity<?> parse(@RequestParam String shareText) {
        try {
            String pureUrl = extractPureUrl(shareText);
            if (pureUrl == null) {
                return ResponseEntity.badRequest().body(Map.of("code", 400, "msg", "未检测到网址"));
            }

          //  String targetIp = "118.195.192.26";
            String targetIp = "127.0.0.1";
            String apiUrl = "http://" + targetIp + ":8000/api/hybrid/video_data?url={url}";

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
            return ResponseEntity.status(500).body(Map.of("code", 500, "msg", "系统异常: " + e.getMessage()));
        }
    }

    @GetMapping("/download")
    public void downloadFile(@RequestParam String url, @RequestParam String filename, HttpServletResponse response) {
        // 加个安全拦截：如果前端传了空 URL，直接终止，不报 500
        if (url == null || url.trim().isEmpty()) {
            response.setStatus(400);
            return;
        }
        try {
            URL targetUrl = new URL(url);
            HttpURLConnection conn = (HttpURLConnection) targetUrl.openConnection();
            conn.setRequestMethod("GET");
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36");
            conn.setRequestProperty("Referer", "https://www.douyin.com/");

            response.setContentType("application/octet-stream");
            String encodedFilename = java.net.URLEncoder.encode(filename, "UTF-8");
            response.setHeader("Content-Disposition", "attachment; filename=\"" + encodedFilename + "\"");

            try (InputStream in = conn.getInputStream(); OutputStream out = response.getOutputStream()) {
                byte[] buffer = new byte[8192];
                int bytesRead;
                while ((bytesRead = in.read(buffer)) != -1) {
                    out.write(buffer, 0, bytesRead);
                }
            }
        } catch (Exception e) {
            response.setStatus(500);
            System.err.println("代理下载失败: " + e.getMessage());
        }
    }

    private String extractPureUrl(String text) {
        if (text == null) return null;
        Pattern pattern = Pattern.compile("https?://[-A-Za-z0-9+&@#/%?=~_|!:,.;]+[-A-Za-z0-9+&@#/%=~_|]");
        Matcher matcher = pattern.matcher(text);
        if (matcher.find()) return matcher.group();
        return null;
    }
}