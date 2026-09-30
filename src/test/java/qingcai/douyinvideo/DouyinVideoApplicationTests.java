package qingcai.douyinvideo;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class DouyinVideoApplicationTests {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void contextLoads() {
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

}
