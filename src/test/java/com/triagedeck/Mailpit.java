package com.triagedeck;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.testcontainers.containers.GenericContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** 测试里读 Mailpit 收到的邮件，用的是 Mailpit 自带的 HTTP API。 */
public class Mailpit {

    static final int SMTP_PORT = 1025;
    static final int HTTP_PORT = 8025;

    private final GenericContainer<?> container;
    private final HttpClient http = HttpClient.newHttpClient();
    private final JsonMapper json = JsonMapper.builder().build();

    Mailpit(GenericContainer<?> container) {
        this.container = container;
    }

    /** 发给这个地址的所有邮件的纯文本正文，最新的在前。 */
    public List<String> textsSentTo(String address) {
        String query = URLEncoder.encode("to:" + address, StandardCharsets.UTF_8);
        JsonNode result = json.readTree(call("GET", "/api/v1/search?query=" + query));
        List<String> texts = new ArrayList<>();
        for (JsonNode message : result.get("messages")) {
            JsonNode full = json.readTree(
                    call("GET", "/api/v1/message/" + message.get("ID").asString()));
            texts.add(full.get("Text").asString());
        }
        return texts;
    }

    public void deleteAll() {
        call("DELETE", "/api/v1/messages");
    }

    private String call(String method, String path) {
        URI uri = URI.create("http://" + container.getHost() + ":" + container.getMappedPort(HTTP_PORT) + path);
        HttpRequest request = HttpRequest.newBuilder(uri)
                .method(method, HttpRequest.BodyPublishers.noBody())
                .build();
        try {
            return http.send(request, HttpResponse.BodyHandlers.ofString()).body();
        } catch (Exception e) {
            throw new IllegalStateException("Mailpit request failed: " + method + " " + path, e);
        }
    }
}
