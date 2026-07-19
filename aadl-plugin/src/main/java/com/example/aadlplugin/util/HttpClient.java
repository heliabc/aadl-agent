package com.example.aadlplugin.util;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.logging.Logger;

public class HttpClient {

    private static final Logger log = Logger.getLogger(HttpClient.class.getName());
    private static final ObjectMapper objectMapper = new ObjectMapper();

    public static String postJson(String url, Object body) {
        return postJson(url, body, null);
    }

    public static String postJson(String url, Object body, Map<String, String> headers) {
        try {
            URL obj = new URL(url);
            HttpURLConnection con = (HttpURLConnection) obj.openConnection();

            con.setRequestMethod("POST");
            con.setRequestProperty("Content-Type", "application/json");

            if (headers != null) {
                headers.forEach(con::setRequestProperty);
            }

            con.setDoOutput(true);
            try (OutputStream os = con.getOutputStream()) {
                byte[] input = objectMapper.writeValueAsString(body).getBytes(StandardCharsets.UTF_8);
                os.write(input, 0, input.length);
            }

            int responseCode = con.getResponseCode();
            log.fine("POST request to URL: " + url + ", Response Code: " + responseCode);

            StringBuilder response;
            if (responseCode >= 200 && responseCode < 300) {
                response = new StringBuilder();
                try (BufferedReader in = new BufferedReader(new InputStreamReader(con.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = in.readLine()) != null) {
                        response.append(line);
                    }
                }
                return response.toString();
            } else {
                StringBuilder error = new StringBuilder();
                try (BufferedReader in = new BufferedReader(new InputStreamReader(con.getErrorStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = in.readLine()) != null) {
                        error.append(line);
                    }
                }
                log.severe("POST request failed: " + responseCode + " - " + error);
                return null;
            }
        } catch (Exception e) {
            log.severe("POST request exception: " + e.getMessage());
            return null;
        }
    }

    public static String get(String url) {
        return get(url, null);
    }

    public static String get(String url, Map<String, String> headers) {
        try {
            URL obj = new URL(url);
            HttpURLConnection con = (HttpURLConnection) obj.openConnection();

            con.setRequestMethod("GET");

            if (headers != null) {
                headers.forEach(con::setRequestProperty);
            }

            int responseCode = con.getResponseCode();
            log.fine("GET request to URL: " + url + ", Response Code: " + responseCode);

            if (responseCode >= 200 && responseCode < 300) {
                StringBuilder response = new StringBuilder();
                try (BufferedReader in = new BufferedReader(new InputStreamReader(con.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = in.readLine()) != null) {
                        response.append(line);
                    }
                }
                return response.toString();
            } else {
                log.severe("GET request failed: " + responseCode);
                return null;
            }
        } catch (Exception e) {
            log.severe("GET request exception: " + e.getMessage());
            return null;
        }
    }

    public static String putJson(String url, Object body, Map<String, String> headers) {
        try {
            URL obj = new URL(url);
            HttpURLConnection con = (HttpURLConnection) obj.openConnection();

            con.setRequestMethod("PUT");
            con.setRequestProperty("Content-Type", "application/json");

            if (headers != null) {
                headers.forEach(con::setRequestProperty);
            }

            con.setDoOutput(true);
            try (OutputStream os = con.getOutputStream()) {
                byte[] input = objectMapper.writeValueAsString(body).getBytes(StandardCharsets.UTF_8);
                os.write(input, 0, input.length);
            }

            int responseCode = con.getResponseCode();
            log.fine("PUT request to URL: " + url + ", Response Code: " + responseCode);

            StringBuilder response;
            if (responseCode >= 200 && responseCode < 300) {
                response = new StringBuilder();
                try (BufferedReader in = new BufferedReader(new InputStreamReader(con.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = in.readLine()) != null) {
                        response.append(line);
                    }
                }
                return response.toString();
            } else {
                StringBuilder error = new StringBuilder();
                try (BufferedReader in = new BufferedReader(new InputStreamReader(con.getErrorStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = in.readLine()) != null) {
                        error.append(line);
                    }
                }
                log.severe("PUT request failed: " + responseCode + " - " + error);
                return null;
            }
        } catch (Exception e) {
            log.severe("PUT request exception: " + e.getMessage());
            return null;
        }
    }

    public static String delete(String url, Map<String, String> headers) {
        try {
            URL obj = new URL(url);
            HttpURLConnection con = (HttpURLConnection) obj.openConnection();

            con.setRequestMethod("DELETE");

            if (headers != null) {
                headers.forEach(con::setRequestProperty);
            }

            int responseCode = con.getResponseCode();
            log.fine("DELETE request to URL: " + url + ", Response Code: " + responseCode);

            if (responseCode >= 200 && responseCode < 300) {
                StringBuilder response = new StringBuilder();
                try (BufferedReader in = new BufferedReader(new InputStreamReader(con.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = in.readLine()) != null) {
                        response.append(line);
                    }
                }
                return response.toString();
            } else {
                log.severe("DELETE request failed: " + responseCode);
                return null;
            }
        } catch (Exception e) {
            log.severe("DELETE request exception: " + e.getMessage());
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    public static <T> T parseJson(String json, Class<T> clazz) {
        try {
            if (json == null || json.isEmpty()) {
                return null;
            }
            return objectMapper.readValue(json, clazz);
        } catch (Exception e) {
            log.severe("Failed to parse JSON: " + e.getMessage());
            return null;
        }
    }

    public static <T> T parseJson(String json, TypeReference<T> typeRef) {
        try {
            if (json == null || json.isEmpty()) {
                return null;
            }
            return objectMapper.readValue(json, typeRef);
        } catch (Exception e) {
            log.severe("Failed to parse JSON: " + e.getMessage());
            return null;
        }
    }

    public static String toJson(Object obj) {
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (Exception e) {
            log.severe("Failed to serialize to JSON: " + e.getMessage());
            return null;
        }
    }
}
