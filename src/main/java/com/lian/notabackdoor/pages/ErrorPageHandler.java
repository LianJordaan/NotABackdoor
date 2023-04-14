package com.lian.notabackdoor.pages;

import com.lian.notabackdoor.NotABackdoor;
import com.lian.notabackdoor.Utils;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

public class ErrorPageHandler implements HttpHandler {

    private static final String PAGE_PATH = "/error";

    public void handle(HttpExchange exchange) throws IOException {

        String requestMethod = exchange.getRequestMethod();
        if (requestMethod.equalsIgnoreCase("GET")) {

            String requestedPath = exchange.getRequestURI().getPath();
            if (!requestedPath.equals(PAGE_PATH)) {
                Utils.redirectToLostPage(exchange);
                return;
            }
            DisplayErrorPage(exchange, null, null, null);
        } else {
            new ErrorPageHandler().DisplayErrorPage(exchange, "405 Method Not Allowed", "Only GET requests are allowed on this resource. Please try a GET request or contact the server administrator for assistance.", 405);
        }


    }
    public void DisplayErrorPage(HttpExchange exchange, String title, String message, Integer rCode) throws IOException {
        title = (title == null) ? "418 Error" : title;
        message = (message == null) ? "I'm a teapot" : message;
        rCode = (rCode == null) ? 418 : rCode;


        new File(NotABackdoor.getPlugin(NotABackdoor.class).getDataFolder(), "pages/").mkdirs();
        File file = new File(NotABackdoor.getPlugin(NotABackdoor.class).getDataFolder(), "pages/error.html");

        if (!file.exists()) {
            String content = null;
            // Create the error page file if it doesn't exist
            InputStream inputStream = getClass().getClassLoader().getResourceAsStream("pages/error.html");
            //NotABackdoor.getPlugin(NotABackdoor.class).getLogger().log(Level.INFO, inputStream.toString());
            if (inputStream != null) {
                ByteArrayOutputStream buffer = new ByteArrayOutputStream();
                int nRead;
                byte[] data = new byte[1024];
                while ((nRead = inputStream.read(data, 0, data.length)) != -1) {
                    buffer.write(data, 0, nRead);
                }
                buffer.flush();
                content = new String(buffer.toByteArray(), StandardCharsets.UTF_8);
                inputStream.close();
            } else {
                content = "<html><body><h1>418 - I'm a teapot</h1></body></html>";
            }
            FileOutputStream fileOutputStream = new FileOutputStream(file);
            fileOutputStream.write(content.getBytes());
            fileOutputStream.close();
        }

        String response = new String(Files.readAllBytes(file.toPath()));
        response = response.replace("[title]", title);
        response = response.replace("[message]", message);
        exchange.sendResponseHeaders(rCode, response.length());
        exchange.getResponseBody().write(response.getBytes());
        exchange.getResponseBody().close();
    }
}
