package site.cuichacha.processor;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * @author Will Dufresne
 * @Date: 2023-03-05 17:57
 * @Description: The translator class
 */
public class Processor {

    private static String apiKey = "";
    private static String filePath = "";

    public static void main(String[] args) {
        try {
            introduction();
            processMarkdown();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private static void introduction() {
        Scanner scanner = new Scanner(System.in);
        System.out.println("------Welcome to Document Processor------");
        System.out.println("Please provide ChatGPT API Key: ");
        apiKey = scanner.nextLine();
        if (apiKey.trim().length() == 0) {
            throw new RuntimeException("Invalid API Key!");
        }
        System.out.println("Please provide file path: ");
        scanner.nextLine();
        if (filePath.trim().length() == 0) {
            throw new RuntimeException("Invalid file path!");
        }
        filePath = scanner.nextLine();
    }

    private static void processMarkdown() throws Exception {
        String sourceName = filePath.substring(filePath.lastIndexOf("/") + 1);
        System.out.printf("Start processing markdown file: %s%n", sourceName);
        String sourceFile = filePath;
        String sourcePath = filePath.substring(0, filePath.lastIndexOf("/"));
        boolean headerFlag = true;
        boolean headerMark = false;
        StringBuilder document = new StringBuilder();
        List<String> parsedBlocks = MarkdownParser.parse(sourceFile);
        StringBuilder buffer = new StringBuilder();
        for (int i = 0; i < parsedBlocks.size(); i++) {
            String block = parsedBlocks.get(i);
            if (headerFlag) {
                if (block.startsWith("---") && headerMark) {
                    for (int j = 0; j < i; j++) {
                        document.append(parsedBlocks.get(j));
                    }
                    headerFlag = false;
                    headerMark = false;
                    i--;
                } else if (block.startsWith("---")) {
                    headerMark = true;
                }
                continue;
            }
            if (!block.isBlank() && !block.isEmpty()) {
                if ((buffer.length() + block.length()) >= 2000) {
                    String result = HttpRequester.request(buffer.append(block).toString(), apiKey).replaceAll("\\\\\\\\\\\\\\\\n", "\n").replaceAll("\\\\\\\\\\\\", "");
                    document.append(result);
                    buffer = new StringBuilder();
                } else {
                    buffer.append(block);
                }
            }
            if (i == parsedBlocks.size() - 1 && !buffer.isEmpty()) {
                String result = HttpRequester.request(buffer.toString(), apiKey).replaceAll("\\\\\\\\\\\\\\\\n", "\n").replaceAll("\\\\\\\\\\\\", "");
                document.append(result);
            }
        }
        String suffix = sourceFile.substring(sourceFile.lastIndexOf("."));
        String outputFile = sourceFile.substring(0, sourceFile.lastIndexOf(".")) + "--output" + suffix;
        File file = new File(outputFile);
        if (!file.exists()) {
            file.createNewFile();
        }
        FileOutputStream fileOutputStream = new FileOutputStream(file);
        fileOutputStream.write(document.toString().getBytes());
        fileOutputStream.close();
        System.out.printf("Processing finished, output file path: %s%n", sourcePath + outputFile);
    }
}

record ChatGPTRequestBody(String model, List<ChatGPTMessage> messages) {

    public ChatGPTRequestBody(List<ChatGPTMessage> messages) {
        this("gpt-3.5-turbo", messages);
    }
}

class ChatGPTMessage {
    private String role;
    private String content;

    public ChatGPTMessage() {
    }

    ChatGPTMessage(String role, String content) {
        this.role = role;
        this.content = content;
    }

    public ChatGPTMessage(String content) {
        this("user", content);
    }

    public String getRole() {
        return role;
    }

    public String getContent() {
        return content;
    }
}

class ChatGPTResponse {

    private String id;
    private String object;
    private String created;
    private String model;
    private ChatGPTUsage usage;
    private List<ChatGPTChoice> choices;

    public ChatGPTResponse() {
    }

    public List<ChatGPTChoice> getChoices() {
        return choices;
    }
}

class ChatGPTUsage {
    private String prompt_tokens;
    private String completion_tokens;
    private String total_tokens;

    public ChatGPTUsage() {
    }

}

class ChatGPTChoice {
    private ChatGPTMessage message;
    private String finish_reason;
    private String index;

    public ChatGPTChoice() {
    }

    public ChatGPTMessage getMessage() {
        return message;
    }
}

final class HttpRequester {
    final static String chinesePunctuation = "《》、「」“”【 】";

    private HttpRequester() {
    }

    public static String request(String text, String apiKey) throws Exception {
        String result = doPostInProxy("https://api.openai.com/v1/chat/completions",
                apiKey,
                List.of("Now, you are a document translator",
                        "1. Translate this markdown to English with best quality and accuracy",
                        "2. Do not break any markdown format",
                        "3. Ignore any markdown tags for translation, KEEP them where they are",
                        "4. Ignore any punctuation marks，KEEP them where they are",
                        "5. Ignore these marks:" + chinesePunctuation + ", KEEP them where they are",
                        "6. DO NOT keep original text",
                        text));
        ChatGPTResponse chatGPTResponse = JsonConvertor.jsonToObject(result, ChatGPTResponse.class);
        return chatGPTResponse.getChoices().get(0).getMessage().getContent();
    }

    public static String doPost(String url, String apiKey, List<String> messages) throws IOException {
        return doPost(url, apiKey, messages, false);
    }

    public static String doPostInProxy(String url, String apiKey, List<String> messages) throws IOException {
        return doPost(url, apiKey, messages, true);
    }

    private static String doPost(String url, String apiKey, List<String> messages, boolean isProxy) throws IOException {

        if (messages == null || messages.isEmpty()) {
            throw new RuntimeException("No valid prompts");
        }

        URL requestUrl = new URL(url);
        HttpURLConnection con = isProxy ? (HttpURLConnection) requestUrl.openConnection(new Proxy(Proxy.Type.HTTP, new InetSocketAddress("127.0.0.1", 7890))) : (HttpURLConnection) requestUrl.openConnection();
        con.setRequestMethod("POST");
        con.setRequestProperty("Content-Type", "application/json");
        con.setRequestProperty("Accept", "application/json");
        con.setRequestProperty("Authorization", "Bearer " + apiKey);
        String request = JsonConvertor.convertToJson(new ChatGPTRequestBody(messages.stream().map(ChatGPTMessage::new).toList()));

        // Send the request body.
        con.setDoOutput(true);
        con.getOutputStream().write(request.getBytes(StandardCharsets.UTF_8));

        // Get the response.
        Scanner scanner = new Scanner(con.getInputStream(), StandardCharsets.UTF_8);
        return scanner.useDelimiter("\\A").next();
    }

}

final class JsonConvertor {

    private JsonConvertor() {
    }

    public static String convertToJson(Object obj) {
        if (obj == null) {
            return "null";
        }
        if (obj.getClass().isArray()) {
            return arrayToJson(obj);
        }
        if (obj instanceof List) {
            return listToJson((List) obj);
        }
        if (obj instanceof Map) {
            return mapToJson((Map) obj);
        }
        if (obj instanceof String || obj instanceof Character) {
            return "\"" + escapeString(obj.toString()) + "\"";
        }
        if (obj instanceof Number || obj instanceof Boolean) {
            return obj.toString();
        }
        return objectToJson(obj);
    }

    private static String objectToJson(Object obj) {
        List<String> fields = new ArrayList<>();
        Class<?> clazz = obj.getClass();
        while (clazz != null) {
            for (Field field : clazz.getDeclaredFields()) {
                int modifiers = field.getModifiers();
                if (Modifier.isStatic(modifiers) || Modifier.isTransient(modifiers) || Modifier.isNative(modifiers)) {
                    continue;
                }
                field.setAccessible(true);
                Object fieldValue;
                try {
                    fieldValue = field.get(obj);
                } catch (IllegalAccessException e) {
                    throw new RuntimeException(e);
                }
                fields.add("\"" + field.getName() + "\":" + convertToJson(fieldValue));
            }
            clazz = clazz.getSuperclass();
        }
        return "{" + String.join(",", fields) + "}";
    }

    private static String arrayToJson(Object obj) {
        List<String> list = new ArrayList<>();
        int length = java.lang.reflect.Array.getLength(obj);
        for (int i = 0; i < length; i++) {
            Object val = java.lang.reflect.Array.get(obj, i);
            list.add(convertToJson(val));
        }
        return "[" + String.join(",", list) + "]";
    }

    private static String listToJson(List<?> list) {
        List<String> values = new ArrayList<>();
        for (Object val : list) {
            values.add(convertToJson(val));
        }
        return "[" + String.join(",", values) + "]";
    }

    private static String mapToJson(Map<?, ?> map) {
        List<String> entries = new ArrayList<>();
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            entries.add(convertToJson(entry.getKey()) + ":" + convertToJson(entry.getValue()));
        }
        return "{" + String.join(",", entries) + "}";
    }

    private static String escapeString(String s) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\"') {
                sb.append("\\\"");
            } else if (c == '\\') {
                sb.append("\\\\");
            } else if (c == '\b') {
                sb.append("\\b");
            } else if (c == '\f') {
                sb.append("\\f");
            } else if (c == '\n') {
                sb.append("\\n");
            } else if (c == '\r') {
                sb.append("\\r");
            } else if (c == '\t') {
                sb.append("\\t");
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    public static <T> T jsonToObject(String json, Class<T> clazz) throws Exception {
        T instance = clazz.getDeclaredConstructor().newInstance();
        Map<String, Object> map = parseJson(json);
        for (Map.Entry<String, Object> entry : map.entrySet()) {
            String fieldName = entry.getKey();
            Object fieldValue = entry.getValue();
            Field field = getField(clazz, fieldName);
            if (field != null) {
                Class<?> fieldClass = field.getType();
                if (fieldClass.isPrimitive()) {
                    setPrimitiveField(instance, field, fieldValue);
                } else if (fieldClass.equals(String.class)) {
                    setStringField(instance, field, fieldValue);
                } else if (fieldClass.equals(List.class)) {
                    setListField(instance, field, fieldValue);
                } else {
                    Object nestedObject = jsonToObject(convertToJson(fieldValue), fieldClass);
                    setField(instance, field, nestedObject);
                }
            }
        }
        return instance;
    }

    private static Map<String, Object> parseJson(String json) {
        Map<String, Object> map = new HashMap<>();
        int level = 0;
        int start = -1;
        int end = 0;
        boolean inString = false;
        while (end < json.length()) {
            char c = json.charAt(end);
            char cBefore = 0;
            if (end > 1) {
                cBefore = json.charAt(end - 1);
            }
            if (!inString && (c == '{' || c == '[')) {
                level++;
                if (start == -1) {
                    start = end;
                }
            } else if (!inString && (c == '}' || c == ']')) {
                level--;
                if (level == 0) {
                    String token = json.substring(start, end + 1);
                    String[] parts = token.split(":", 2);
                    String key = parts[0].substring(1, parts[0].length() - 1);
                    String value = parts[1];
                    Object parsedValue = parseValue(value);
                    map.put(key, parsedValue);
                    start = end + 1;
                }
            } else if (c == ',' && level == 1 && !inString) {
                start = separate(json, map, start, end);
            } else if (c == '"' && cBefore != '\\') {
                inString = !inString;
            }
            end++;
        }
        if (start != -1) {
            String token = json.substring(start);
            if (token.length() > 1) {
                String[] parts = token.split(":", 2);
                String key = parts[0].substring(1, parts[0].length() - 1);
                String value = parts[1];
                Object parsedValue = parseValue(value);
                map.put(key, parsedValue);
            }
        }
        return map;
    }

    private static int separate(String json, Map<String, Object> map, int start, int end) {
        String token = json.substring(start, end);
        String[] parts = token.split(":", 2);
        String key = parts[0].substring(parts[0].startsWith("{") ? 2 : 1, parts[0].length() - 1);
        String value = parts[1];
        Object parsedValue = parseValue(value);
        map.put(key, parsedValue);
        start = end + 1;
        return start;
    }

    private static Object parseValue(String value) {
        if (!value.startsWith("{") && value.endsWith("}")) {
            value = value.substring(0, value.lastIndexOf("}"));
        }
        if ("null".equals(value)) {
            return null;
        } else if (value.startsWith("{") && value.endsWith("}")) {
            return parseJson(value);
        } else if (value.startsWith("[") && value.endsWith("]")) {
            List<Object> list = new ArrayList<>();
            int level = 0;
            int start = 1;
            for (int i = 1; i < value.length() - 1; i++) {
                if (value.charAt(i) == '{' || value.charAt(i) == '[') {
                    level++;
                } else if (value.charAt(i) == '}' || value.charAt(i) == ']') {
                    level--;
                } else if (value.charAt(i) == ',' && level == 0) {
                    String token = value.substring(start, i);
                    Object parsedValue = parseValue(token);
                    list.add(parsedValue);
                    start = i + 1;
                }
            }
            if (start < value.length() - 1) {
                String token = value.substring(start, value.length() - 1);
                Object parsedValue = parseValue(token);
                list.add(parsedValue);
            }
            return list;
        } else if (value.startsWith("\"") && value.endsWith("\"")) {
            return value.substring(1, value.length() - 1);
        } else if ("true".equals(value) || "false".equals(value)) {
            return Boolean.parseBoolean(value);
        } else if (value.contains(".")) {
            return Double.parseDouble(value);
        } else {
            return Integer.parseInt(value);
        }
    }


    private static Field getField(Class<?> clazz, String fieldName) {
        try {
            return clazz.getDeclaredField(fieldName);
        } catch (NoSuchFieldException e) {
            Class<?> superclass = clazz.getSuperclass();
            if (superclass == null) {
                return null;
            } else {
                return getField(superclass, fieldName);
            }
        }
    }

    private static void setPrimitiveField(Object instance, Field field, Object value) throws Exception {
        if (field.getType().equals(int.class)) {
            int intValue = ((Number) value).intValue();
            field.setInt(instance, intValue);
        } else if (field.getType().equals(long.class)) {
            long longValue = ((Number) value).longValue();
            field.setLong(instance, longValue);
        } else if (field.getType().equals(float.class)) {
            float floatValue = ((Number) value).floatValue();
            field.setFloat(instance, floatValue);
        } else if (field.getType().equals(double.class)) {
            double doubleValue = ((Number) value).doubleValue();
            field.setDouble(instance, doubleValue);
        } else if (field.getType().equals(boolean.class)) {
            boolean booleanValue = (boolean) value;
            field.setBoolean(instance, booleanValue);
        } else if (field.getType().equals(byte.class)) {
            byte byteValue = ((Number) value).byteValue();
            field.setByte(instance, byteValue);
        } else if (field.getType().equals(short.class)) {
            short shortValue = ((Number) value).shortValue();
            field.setShort(instance, shortValue);
        }
    }

    private static void setStringField(Object instance, Field field, Object value) throws Exception {
        field.setAccessible(true);
        field.set(instance, value == null ? null : String.valueOf(value));
    }

    private static void setListField(Object instance, Field field, Object value) throws Exception {
        field.setAccessible(true);
        if (value == null) {
            return;
        }
        List<?> listValue = (List<?>) value;
        Class<?> elementType = (Class<?>) ((ParameterizedType) field.getGenericType()).getActualTypeArguments()[0];
        List<Object> convertedList = new ArrayList<>();
        for (Object item : listValue) {
            Object convertedItem = null;
            if (item instanceof Map) {
                convertedItem = jsonToObject(convertToJson(item), elementType);
            } else {
                convertedItem = item;
            }
            convertedList.add(convertedItem);
        }
        field.set(instance, convertedList);
    }

    private static void setField(Object instance, Field field, Object value) throws Exception {
        field.setAccessible(true);
        field.set(instance, value);
    }
}

final class MarkdownParser {

    private static final Pattern HEADING_PATTERN = Pattern.compile("^(#{1,6})\\s+(.*?)\\s*#*");
    private static final Pattern LIST_PATTERN = Pattern.compile("^([*+-]|\\d+\\.)\\s+(.*?)$");
    private static final Pattern BLOCKQUOTE_PATTERN = Pattern.compile("^>\\s+(.*?)$");
    private static final Pattern CODE_PATTERN = Pattern.compile("^`{3}.*|`{1}.*`{1}$");
    private static final Pattern HORIZONTAL_RULE_PATTERN = Pattern.compile("^[-_*]{3,}\\s*$");
    private static final Pattern LINK_PATTERN = Pattern.compile("\\[([^\\[]+?)\\]\\(([^\\)]+?)\\)");
    private static final Pattern IMAGE_PATTERN = Pattern.compile("!\\[([^\\[]+?)\\]\\(([^\\)]+?)\\)");
    private static final Pattern PARAGRAPH_PATTERN = Pattern.compile("^(?!\\s*$).+");

    public static List<String> parse(String filePath) {
        List<String> contentBlocks = new ArrayList<>();
        StringBuilder block = new StringBuilder();

        try {
            List<String> lines = Files.readAllLines(Paths.get(filePath), StandardCharsets.UTF_8);

            for (String line : lines) {
                Matcher headingMatcher = HEADING_PATTERN.matcher(line);
                Matcher listMatcher = LIST_PATTERN.matcher(line);
                Matcher blockquoteMatcher = BLOCKQUOTE_PATTERN.matcher(line);
                Matcher codeMatcher = CODE_PATTERN.matcher(line);
                Matcher horizontalRuleMatcher = HORIZONTAL_RULE_PATTERN.matcher(line);
                Matcher paragraphMatcher = PARAGRAPH_PATTERN.matcher(line);

                if (codeMatcher.matches()) {
                    block.append(line).append("\n");
                } else if (headingMatcher.matches() || listMatcher.matches() || blockquoteMatcher.matches() || horizontalRuleMatcher.matches()) {
                    contentBlocks.add(block.toString());
                    block = new StringBuilder();
                    block.append(line).append("\n");
                } else if (paragraphMatcher.matches()) {
                    block.append(line).append("\n");
                }
            }

            contentBlocks.add(block.toString());
        } catch (IOException e) {
            System.err.println("Error reading file: " + e.getMessage());
        }

        return contentBlocks;
    }
}