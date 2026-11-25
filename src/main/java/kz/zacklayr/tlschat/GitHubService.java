package kz.zacklayr.tlschat;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;

/** Minimal GitHub commits/comments integration. Credentials stay server-side. */
public final class GitHubService {
    private final HttpClient http = HttpClient.newHttpClient();
    private final String repository;
    private final String token;

    public GitHubService() {
        repository = System.getenv().getOrDefault("GITHUB_REPOSITORY", "").trim();
        token = System.getenv().getOrDefault("GITHUB_TOKEN", "").trim();
    }

    public boolean configured() {
        return repository.matches("[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+") && !token.isBlank();
    }

    public String commits() throws IOException, InterruptedException {
        return request("GET", "https://api.github.com/repos/" + repository + "/commits?per_page=50", null);
    }

    public String comment(String sha, String body) throws IOException, InterruptedException {
        if (sha == null || !sha.matches("[A-Fa-f0-9]{7,64}")) {
            throw new IllegalArgumentException("Invalid commit SHA");
        }
        if (body == null || body.isBlank() || body.length() > 4000) {
            throw new IllegalArgumentException("Comment must contain 1..4000 characters");
        }
        return request("POST", "https://api.github.com/repos/" + repository
                + "/commits/" + sha + "/comments", "{\"body\":\"" + escape(body.trim()) + "\"}");
    }

    private String request(String method, String url, String body) throws IOException, InterruptedException {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
                .header("Accept", "application/vnd.github+json")
                .header("X-GitHub-Api-Version", "2022-11-28")
                .header("Authorization", "Bearer " + token);
        HttpRequest request = builder.method(method, body == null
                ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8)).build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IOException("GitHub API returned HTTP " + response.statusCode());
        }
        return response.body();
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\r", "\\r").replace("\n", "\\n");
    }
}
