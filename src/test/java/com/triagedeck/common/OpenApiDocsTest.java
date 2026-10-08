package com.triagedeck.common;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.triagedeck.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

/** 接口文档不用登录就能拿到，并且包含我们的接口和 Bearer 认证方式。 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class OpenApiDocsTest {

    @Autowired
    MockMvc mockMvc;

    @Test
    void apiDocsArePublicAndDescribeOurEndpoints() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.info.title").value("TriageDeck API"))
                .andExpect(jsonPath("$.paths['/api/auth/login']").exists())
                .andExpect(jsonPath("$.paths['/api/orgs/{orgId}/invitations']").exists())
                .andExpect(jsonPath("$.components.securitySchemes.bearerAuth.scheme")
                        .value("bearer"));
    }

    @Test
    void swaggerUiIsPublic() throws Exception {
        mockMvc.perform(get("/swagger-ui/index.html")).andExpect(status().isOk());
    }
}
