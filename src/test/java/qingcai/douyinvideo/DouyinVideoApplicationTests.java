package qingcai.douyinvideo;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;

@SpringBootTest
@AutoConfigureMockMvc
class DouyinVideoApplicationTests {

    @Autowired
    private MockMvc mockMvc;

    @Value("${parser.base-url}")
    private String parserBaseUrl;

    @Test
    void contextLoads() {
        assertEquals("http://118.195.192.26:8000", parserBaseUrl);
    }

    @Test
    void proxyDownloadRejectsPrivateTargetsBeforeConnecting() throws Exception {
        mockMvc.perform(get("/api/douyin/download")
                        .param("url", "http://localhost:8000/internal")
                        .param("filename", "video.mp4"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void proxyDownloadRejectsUnsupportedSchemes() throws Exception {
        mockMvc.perform(get("/api/douyin/download")
                        .param("url", "file:///etc/passwd")
                        .param("filename", "video.mp4"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void homepageUsesProxyForThePrimaryDownloadAction() throws Exception {
        // Welcome Page 会先内部转发，直接请求静态资源才能校验实际交付给浏览器的脚本。
        mockMvc.perform(get("/index.html"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("id=\"downloadVideoBtn\"")))
                .andExpect(content().string(containsString("/api/douyin/download?url=")))
                .andExpect(content().string(containsString("id=\"openVideoBtn\"")));
    }

}
