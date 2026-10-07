package com.triagedeck.common;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** 不启动 Spring 容器：只把一个会抛异常的 Controller 和全局处理器组装起来测试。 */
class GlobalExceptionHandlerTest {

    @RestController
    static class BrokenController {
        @GetMapping("/broken")
        String broken() {
            throw new IllegalStateException("secret internal detail: SELECT * FROM app_user");
        }
    }

    private final MockMvc mockMvc = MockMvcBuilders.standaloneSetup(new BrokenController())
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();

    @Test
    void unexpectedExceptionBecomesProblemDetail500WithoutLeakingDetails() throws Exception {
        mockMvc.perform(get("/broken"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
                .andExpect(jsonPath("$.detail").value("Internal server error"))
                .andExpect(content().string(Matchers.not(Matchers.containsString("SELECT"))));
    }
}
